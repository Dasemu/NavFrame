package dev.navframe.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.time.Instant
import kotlin.math.abs

private val regionalJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
private const val MAX_REGIONAL_JSON = 32 * 1024 * 1024
private const val MAX_REGION_FILE = 500L * 1024 * 1024

/** Local package metadata only: it cannot enable networking or provide an offline routing engine. */
data class RegionalBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all { it.isFinite() })
        require(south >= -85.051129 && north <= 85.051129 && south < north && west >= -180 && east <= 180 && west < east)
        // Region packs may represent any Geofabrik country or continental extract.
        // Keep Web Mercator latitude bounds and reject antimeridian-spanning boxes.
    }
    fun contains(point: GeoPoint) = point.latitude in south..north && point.longitude in west..east
}
data class RegionalFile(val path: String, val bytes: Long, val sha256: String)
data class RegionalSource(
    val provider: String, val extractUrl: String, val pbfSha256: String,
    val coverageUrl: String, val coverageSha256: String, val license: String,
    val recipe: String, val planetiler: String, val poiBuilder: String,
)
data class RegionalCoverage(val polygons: List<List<List<GeoPoint>>>) {
    /** GeoJSON outer rings include their edges; holes exclude their interiors and edges. No antimeridian packs. */
    fun contains(point: GeoPoint): Boolean = polygons.any { polygon ->
        insideRing(point, polygon.first()) && polygon.drop(1).none { insideRing(point, it) }
    }
}
data class RegionalManifest(
    val schemaVersion: Int, val id: String, val name: String, val version: String, val osmTimestamp: String,
    val bounds: RegionalBounds, val minZoom: Int, val maxZoom: Int, val attribution: String,
    val mapSchema: String, val poiSchema: Int, val glyphs: String, val source: RegionalSource,
    val map: RegionalFile, val pois: RegionalFile, val coverage: RegionalCoverage? = null,
) {
    val totalBytes: Long get() = map.bytes + pois.bytes
    fun covers(point: GeoPoint): Boolean = bounds.contains(point) && (coverage?.contains(point) ?: true)
}
data class RegionalArchive(val path: String, val bytes: Long, val sha256: String, val url: String? = null)
data class RegionalCatalogEntry(
    val manifest: RegionalManifest,
    val archive: RegionalArchive,
    val countryCode: String? = null,
    val country: String? = null,
    val region: String? = null,
    val cameraCount: Int? = null,
)
data class RegionalCatalog(val schemaVersion: Int, val regions: List<RegionalCatalogEntry>)

/** Strict original parser; errors never include untrusted JSON, coordinates, paths or URLs. */
fun parseRegionalManifest(json: String): RegionalManifest = regionalDecode { parseManifest(regionalObject(json)) }
fun parseRegionalCatalog(json: String): RegionalCatalog = regionalDecode {
    val root = regionalObject(json)
    require(root.int("schemaVersion") == 1)
    val entries = root.required("regions").arrayValue()
    require(entries.size() in 1..4096)
    val seen = HashSet<String>()
    val seenPaths = HashSet<String>()
    RegionalCatalog(1, entries.map { item ->
        val entry = item.objectValue()
        val manifest = parseManifest(entry.required("manifest").objectValue())
        require(seen.add(manifest.id))
        val archive = entry.required("archive").objectValue()
        val path = archive.text("path")
        require(path.length <= 120 && path.matches(Regex("[a-z0-9][a-z0-9._-]*\\.navframe")) && !path.contains("..") && seenPaths.add(path))
        val size = archive.long("bytes")
        require(size in 127..(2 * MAX_REGION_FILE + MAX_REGIONAL_JSON))
        val hash = archive.hash()
        val url = archive.optionalText("url")?.also {
            validateHttpsUrl(it)
            require(java.net.URI(it).path.substringAfterLast('/') == path)
        }
        val countryCode = entry.optionalText("countryCode")?.also { require(it.matches(Regex("[A-Z]{2}"))) }
        val country = entry.optionalText("country")?.humanText(100)
        val region = entry.optionalText("region")?.also {
            require(it.length in 1..160 && it.matches(Regex("[a-z0-9]+(?:[a-z0-9._/-]*[a-z0-9])?")) &&
                !it.contains("..") && !it.contains("//"))
        }
        val cameraCount = entry.optionalInt("cameraCount")?.also { require(it >= 0) }
        RegionalCatalogEntry(manifest, RegionalArchive(path, size, hash, url), countryCode, country, region, cameraCount)
    })
}

private fun parseManifest(root: JsonObject): RegionalManifest {
    require(root.int("schemaVersion") == 1)
    val id = root.text("id")
    require(id.length in 3..64 && id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
    val name = root.text("name").humanText(100)
    val version = root.text("version")
    require(version.length in 1..40 && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && !version.contains(".."))
    val timestamp = root.text("osmTimestamp")
    require(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
    Instant.parse(timestamp)
    val area = root.required("bounds").objectValue()
    val bounds = RegionalBounds(area.number("south"), area.number("west"), area.number("north"), area.number("east"))
    val minZoom = root.int("minZoom")
    val maxZoom = root.int("maxZoom")
    require(minZoom in 0..16 && maxZoom in minZoom..16)
    val attribution = root.text("attribution").humanText(100)
    require(attribution.contains("OpenStreetMap"))
    val mapSchema = root.text("mapSchema")
    val poiSchema = root.int("poiSchema")
    val glyphs = root.text("glyphs")
    require(mapSchema == "NavFrame Offline/1.0.0" && poiSchema == 1 && glyphs == "noto-sans-regular-0-2047")
    val capabilities = root.required("capabilities").arrayValue().map { it.textValue() }
    require(capabilities.size == 2 && capabilities.toSet() == setOf("map", "poi"))
    val sourceObject = root.required("source").objectValue()
    val source = RegionalSource(
        sourceObject.text("provider"), sourceObject.text("extractUrl"), sourceObject.hash("pbfSha256"),
        sourceObject.text("coverageUrl"), sourceObject.hash("coverageSha256"),
        sourceObject.text("license"), sourceObject.text("recipe"), sourceObject.text("planetiler"), sourceObject.text("poiBuilder"),
    )
    require(source.provider == "Geofabrik" && source.license == "ODbL-1.0")
    requireGeofabrikUrl(source.extractUrl, ".osm.pbf")
    requireGeofabrikUrl(source.coverageUrl, ".poly")
    require(source.recipe in setOf("NavFrame regional pack 1.0.0", "NavFrame regional pack 1.1.0") &&
        source.planetiler == "0.10.2" && source.poiBuilder in setOf("NavFrame POI 1.0.0", "NavFrame POI 1.1.0"))
    // Unknown routing/network capabilities are not silently adopted by this local-only format.
    val map = parseFile(root.required("map").objectValue(), "map.pmtiles", 127)
    val pois = parseFile(root.required("pois").objectValue(), "pois.sqlite", 4096)
    val coverage = parseCoverage(root.required("coverage").objectValue(), bounds)
    return RegionalManifest(1, id, name, version, timestamp, bounds, minZoom, maxZoom, attribution, mapSchema, poiSchema, glyphs, source, map, pois, coverage)
}
private fun requireGeofabrikUrl(value: String, suffix: String) {
    val path = value.removePrefix("https://download.geofabrik.de/")
    require(value.length <= 512 && value.startsWith("https://download.geofabrik.de/") &&
        value.endsWith(suffix) && !value.contains("..") && !path.contains("//") &&
        path.matches(Regex("[a-z0-9._/-]+")))
}
private fun validateHttpsUrl(value: String) {
    try {
        val uri = java.net.URI(value)
        require(value.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid regional archive URL")
    }
}
private fun parseFile(root: JsonObject, expectedPath: String, minimum: Long): RegionalFile {
    val path = root.text("path")
    require(path == expectedPath)
    val bytes = root.long("bytes")
    require(bytes in minimum..MAX_REGION_FILE)
    return RegionalFile(path, bytes, root.hash())
}
private fun parseCoverage(root: JsonObject, bounds: RegionalBounds): RegionalCoverage {
    val coordinates = root.required("coordinates").arrayValue()
    val polygons = when (root.text("type")) {
        "Polygon" -> listOf(coordinates)
        "MultiPolygon" -> coordinates.map { it.arrayValue() }
        else -> error("Unsupported coverage")
    }
    require(polygons.size in 1..64)
    var count = 0
    return RegionalCoverage(polygons.map { polygon ->
        require(polygon.size() in 1..64)
        polygon.map { ring ->
            val points = ring.arrayValue()
            require(points.size() in 4..8192)
            count += points.size()
            require(count <= 8192)
            points.map { item ->
                val pair = item.arrayValue()
                require(pair.size() == 2)
                val point = GeoPoint(pair[1].finiteNumber(), pair[0].finiteNumber())
                require(bounds.contains(point))
                point
            }.also { require(it.first() == it.last() && it.toSet().size >= 3) }
        }
    })
}
private fun insideRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
    var inside = false
    for (index in 0 until ring.lastIndex) {
        val a = ring[index]; val b = ring[index + 1]
        val cross = (point.longitude - a.longitude) * (b.latitude - a.latitude) - (point.latitude - a.latitude) * (b.longitude - a.longitude)
        if (abs(cross) < 1e-10 && point.longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude) &&
            point.latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude)) return true
        if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
            point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude) inside = !inside
    }
    return inside
}
private inline fun <T> regionalDecode(block: () -> T): T = try { block() } catch (_: RuntimeException) {
    throw IllegalArgumentException("Paquete regional inválido o incompatible")
}
private fun regionalObject(json: String): JsonObject {
    require(json.length in 2..MAX_REGIONAL_JSON)
    return (regionalJson.fromJson(json, JsonElement::class.java) ?: error("Missing JSON")).objectValue()
}
private fun JsonObject.required(name: String): JsonElement = get(name) ?: error("Missing field")
private fun JsonElement.objectValue() = if (isJsonObject) asJsonObject else error("Invalid object")
private fun JsonElement.arrayValue() = if (isJsonArray) asJsonArray else error("Invalid array")
private fun JsonElement.textValue(): String = if (isJsonPrimitive && asJsonPrimitive.isString) asString else error("Invalid text")
private fun JsonObject.text(name: String) = required(name).textValue()
private fun JsonObject.optionalText(name: String): String? = if (!has(name) || get(name).isJsonNull) null else text(name)
private fun JsonObject.optionalInt(name: String): Int? = if (!has(name) || get(name).isJsonNull) null else int(name)
private fun JsonElement.finiteNumber(): Double {
    require(isJsonPrimitive && asJsonPrimitive.isNumber)
    return asDouble.also { require(it.isFinite()) }
}
private fun JsonObject.number(name: String) = required(name).finiteNumber()
private fun JsonObject.long(name: String): Long {
    val value = required(name)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    val decimal = value.asBigDecimal
    return decimal.longValueExact()
}
private fun JsonObject.int(name: String): Int = long(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun JsonObject.hash(): String = text("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
private fun JsonObject.hash(name: String): String = text(name).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
private fun String.humanText(limit: Int): String = also { require(length in 1..limit && isNotBlank() && none { it.isISOControl() }) }
