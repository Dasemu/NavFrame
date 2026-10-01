package dev.navframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.text.Html
import dev.navframe.core.*
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The service owns this dedicated 480×240 GPU snapshot target, not a phone MapView or Activity. */
class MapLibreTftRenderer(context: Context, private val source: MapDataSource, private val reportStage: (MapRenderStage) -> Unit = {}) : TftRenderer {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val composer = MapFrameComposer()
    private var snapshotter: MapSnapshotter? = null
    private var closed = false
    private var stage = MapRenderStage.INITIALIZE
    private var capturedOnce = false
    private fun phase(value: MapRenderStage) { stage = value; reportStage(value) }
    private data class Raster(val bitmap: Bitmap, val marker: PointF, val attribution: String)
    override suspend fun render(state: NavigationState) = render(state, NavigationMode.MAP_GPS)

    suspend fun render(state: NavigationState, mode: NavigationMode): TftFrame = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        val raster = try { withTimeout(if (capturedOnce) 10_000 else 30_000) { capture(state) } }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); throw MapRenderException(MapRenderFailure.TIMEOUT, stage, MapErrorDetail(errorClass = "TimeoutCancellationException")) }
        catch (error: Exception) { throw if (error is MapRenderException) error else MapRenderException(classifyMapRenderFailure(error.message), stage, mapErrorDetail(error.message, error.javaClass.simpleName)) }
        capturedOnce = true
        phase(MapRenderStage.COMPOSE)
        try { composer.compose(raster.bitmap, raster.marker, state, mode, raster.attribution) }
        finally { raster.bitmap.recycle() }
    }

    private suspend fun capture(state: NavigationState): Raster = suspendCancellableCoroutine { continuation ->
        phase(MapRenderStage.INITIALIZE)
        MapLibre.getInstance(context)
        val position = LatLng(state.position.latitude, state.position.longitude)
        val camera = TftCameraPolicy().parameters(state)
        val cameraPosition = CameraPosition.Builder().target(position).zoom(camera.zoom)
            .bearing(camera.bearingDegrees.toDouble()).padding(0.0, 50.0, 0.0, 0.0).build()
        val current = snapshotter ?: run {
            val tile = source.tileSource()
            val builder = if (tile.uri.startsWith("asset://")) Style.Builder().fromJson(context.assets.open(tile.uri.removePrefix("asset://")).bufferedReader().use { it.readText() })
                else Style.Builder().fromUri(tile.uri)
            builder.withSources(GeoJsonSource("navframe-route", routeGeoJson(state.route)))
                .withLayers(LineLayer("navframe-route-casing", "navframe-route").withProperties(lineColor("#071017"), lineWidth(11f)),
                    LineLayer("navframe-route-line", "navframe-route").withProperties(lineColor("#50e3c2"), lineWidth(7f)))
            object : MapSnapshotter(context, MapSnapshotter.Options(480, 240).withPixelRatio(1f)
                .withLogo(false).withAttribution(false).withStyleBuilder(builder).withCameraPosition(cameraPosition)) {
                // SDK otherwise measures Android Views/logo even when both flags are false.
                // Our native composer supplies all attribution, without density/theme-dependent SDK overlays.
                override fun addOverlay(mapSnapshot: org.maplibre.android.snapshotter.MapSnapshot) = Unit
            }.also {
                snapshotter = it
                it.setObserver(object : MapSnapshotter.Observer {
                    override fun onDidFinishLoadingStyle() { if (!closed && snapshotter === it) phase(MapRenderStage.STYLE_READY) }
                    override fun onStyleImageMissing(imageName: String) = Unit
                })
            }
        }
        current.setCameraPosition(cameraPosition)
        (current.getSource("navframe-route") as? GeoJsonSource)?.setGeoJson(routeGeoJson(state.route))
        continuation.invokeOnCancellation {
            // An old callback must not be delivered into a new capture: discard after cancellation/error.
            val cancel = { current.cancel(); if (snapshotter === current) snapshotter = null }
            if (Looper.myLooper() == Looper.getMainLooper()) cancel() else main.post { cancel() }
        }
        try {
            phase(if (current.getSource("navframe-route") == null) MapRenderStage.STYLE_LOADING else MapRenderStage.STYLE_READY)
            current.start({ snapshot ->
                if (continuation.isActive && !closed && snapshotter === current) phase(MapRenderStage.SNAPSHOT_READY)
                val bitmap = snapshot.bitmap
                val marker = snapshot.pixelForLatLng(position)
                // SDK invokes ready BEFORE resetting its active flag; defer resume until reset finishes.
                main.post {
                    if (continuation.isActive) {
                        val credits = snapshot.attributions.map { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }.filter { it.isNotEmpty() }
                        val configured = source.tileSource().attribution
                        val attribution = if (source.tileSource().uri.startsWith("asset://")) configured else if (credits.isNotEmpty()) credits.distinct().joinToString(" · ") else configured
                        continuation.resume(Raster(bitmap, marker, attribution), onCancellation = { _, returned, _ -> returned.bitmap.recycle() })
                    } else bitmap.recycle()
                }
            }, { reason ->
                main.post {
                    current.cancel()
                    if (snapshotter === current) snapshotter = null
                    if (continuation.isActive) continuation.resumeWithException(MapRenderException(classifyMapRenderFailure(reason), stage, mapErrorDetail(reason)))
                }
            })
        } catch (error: Exception) {
            current.cancel()
            if (snapshotter === current) snapshotter = null
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun routeGeoJson(route: RouteResult?): FeatureCollection =
        if (route == null || route.geometry.size < 2) FeatureCollection.fromFeatures(emptyArray<Feature>())
        else FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) }))))

    /** Must be called on Main, just as the snapshotter was created there. */
    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        snapshotter?.cancel()
        snapshotter = null
    }
}
