package dev.navframe.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import kotlin.math.*

/**
 * [stops] is non-empty when the geometry is a list of points to be joined along roads
 * (route points, waypoints or a sparse track) rather than a trace that can be followed as is.
 */
data class ImportedRoute(val name: String?, val route: RouteResult, val isTrack: Boolean = false, val stops: List<GeoPoint> = emptyList()) {
    val needsRoadRouting: Boolean get() = stops.size >= 2
}

/** Imports geometry only: GPX points do not establish road turns, speeds or travel time. */
object GpxRouteImporter {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_POINTS = 10_000
    /** Upper bound of points sent to the routing server for one import. */
    const val MAX_STOPS = 400
    /** Recorded or road-snapped traces are much denser; planners' hand-placed points are not. */
    const val SPARSE_TRACK_MEDIAN_METERS = 250.0
    const val MAX_DEPTH = 64
    private val namespaces = setOf("", "http://www.topografix.com/GPX/1/0", "http://www.topografix.com/GPX/1/1")

    fun parse(input: InputStream): ImportedRoute {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (bytes.size() + read > MAX_BYTES) fail("El archivo GPX supera los 5 MiB. Exporta una ruta más pequeña.")
            bytes.write(buffer, 0, read)
        }
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            val reader = factory.newSAXParser().xmlReader
            // Lexical handling is mandatory: reject DTD before any entity can be expanded.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { reader.setFeature(feature, false) } catch (_: SAXException) { /* The rejecting resolver remains active. */ }
            }
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(bytes.toByteArray())))
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            fail("No se pudo leer el GPX. Comprueba que sea un archivo GPX válido sin DTD ni entidades externas.")
        }
        val points = handler.points
        if (points.size < 2) fail("El GPX necesita al menos dos puntos de ruta o track.")
        val distance = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        if (!distance.isFinite() || distance <= 0.0) fail("El GPX necesita al menos dos puntos distintos.")
        val stops = if (!handler.isTrack || isSparse(points)) downsample(points.filterIndexed { i, point -> i == 0 || point != points[i - 1] }) else emptyList()
        return ImportedRoute(handler.name?.trim()?.takeIf { it.isNotEmpty() }, RouteResult(
            geometry = points.toList(), maneuvers = emptyList(), distanceMeters = distance.roundToInt().coerceAtLeast(1),
            durationSeconds = 0, destination = points.last(), imported = true,
        ), handler.isTrack, stops)
    }

    private fun isSparse(points: List<GeoPoint>): Boolean {
        val gaps = points.zipWithNext().map { (a, b) -> distance(a, b) }.filter { it > 0.0 }.sorted()
        return gaps.isNotEmpty() && gaps[gaps.size / 2] > SPARSE_TRACK_MEDIAN_METERS
    }
    /** Keep the endpoints and an even spread of the remaining points. */
    private fun downsample(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size <= MAX_STOPS) return points
        return (0 until MAX_STOPS).map { points[(it.toLong() * points.lastIndex / (MAX_STOPS - 1)).toInt()] }
    }

    private class Handler : DefaultHandler2() {
        private val routePoints = mutableListOf<GeoPoint>()
        private val trackPoints = mutableListOf<GeoPoint>()
        private val waypoints = mutableListOf<GeoPoint>()
        private var routeName: String? = null
        private var trackName: String? = null
        val points: List<GeoPoint> get() = if (tracks > 0) trackPoints else if (routes > 0) routePoints else waypoints
        val isTrack: Boolean get() = tracks > 0
        val name: String? get() = if (tracks > 0) trackName else routeName
        private val path = mutableListOf<String>()
        private var rootNamespace = ""
        private var routes = 0
        private var tracks = 0
        private var nonemptySegments = 0
        private var segmentPoints = 0
        private var nameText: StringBuilder? = null

        override fun startDTD(name: String?, publicId: String?, systemId: String?) = fail("El GPX contiene un DTD no permitido.")
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = fail("El GPX contiene entidades externas no permitidas.")
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (path.size >= MAX_DEPTH) fail("El GPX tiene demasiados niveles XML.")
            if (path.isEmpty()) {
                if (localName != "gpx" || uri !in namespaces) fail("Selecciona un archivo GPX 1.0 o 1.1 válido.")
                rootNamespace = uri
            }
            path.add(if (uri == rootNamespace) localName else "#extension")
            val route = path == listOf("gpx", "rte")
            val track = path == listOf("gpx", "trk")
            if (route || track) {
                if (route) routes++ else tracks++
                if (routes > 1 || tracks > 1) fail("El GPX contiene varias rutas o tracks. Exporta solo la ruta que quieras seguir.")
            }
            if (path == listOf("gpx", "trk", "trkseg")) segmentPoints = 0
            if (path == listOf("gpx", "rte", "name") || path == listOf("gpx", "trk", "name")) nameText = StringBuilder()
            if (path == listOf("gpx", "rte", "rtept") || path == listOf("gpx", "trk", "trkseg", "trkpt") || path == listOf("gpx", "wpt")) {
                if (routePoints.size + trackPoints.size + waypoints.size >= MAX_POINTS) fail("El GPX supera los 10000 puntos. Simplifica la ruta antes de importarla.")
                val lat = attributes.getValue("lat")?.toDoubleOrNull()
                val lon = attributes.getValue("lon")?.toDoubleOrNull()
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) fail("El GPX contiene coordenadas inválidas.")
                when (path[1]) {
                    "trk" -> { trackPoints.add(GeoPoint(lat, lon)); segmentPoints++ }
                    "rte" -> routePoints.add(GeoPoint(lat, lon))
                    else -> waypoints.add(GeoPoint(lat, lon))
                }
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (path == listOf("gpx", "rte", "name") || path == listOf("gpx", "trk", "name")) {
                nameText?.let { if (it.length < 256) it.append(ch, start, minOf(length, 256 - it.length)) }
            }
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            if (path == listOf("gpx", "rte", "name") || path == listOf("gpx", "trk", "name")) {
                if (path[1] == "trk") trackName = nameText?.toString() else routeName = nameText?.toString()
                nameText = null
            }
            if (path == listOf("gpx", "trk", "trkseg") && segmentPoints > 0) {
                nonemptySegments++
                if (nonemptySegments > 1) fail("El GPX contiene varios segmentos separados. Exporta un solo segmento continuo.")
            }
            path.removeAt(path.lastIndex)
        }
    }
    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
