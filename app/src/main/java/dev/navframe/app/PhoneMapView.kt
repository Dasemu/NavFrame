package dev.navframe.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import dev.navframe.core.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression as Expr
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

/** Phone rendering consumes service state directly; it never supplies a screenshot to the TFT. */
class PhoneMapView(context: Context, savedState: Bundle?, private val onAttribution: (String) -> Unit = {}, private val onPlaceSelected: (OfflinePlace) -> Unit = {}, private val onError: () -> Unit, private val onViewportChanged: (PlaceViewport) -> Unit = {}) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var source: TileSource? = null
    private var style: Style? = null
    private var state = NavigationState()
    private var renderedAt = 0L
    private var followed = true
    private var destroyed = false
    private var styleGeneration = 0L
    private var reportedCredits = ""
    private var places: List<OfflinePlace> = emptyList()
    init {
        MapLibre.getInstance(context.applicationContext)
        view = MapView(context)
        view.onCreate(savedState)
        view.addOnDidFinishRenderingFrameListener { _, _, _ -> updateAttribution() }
        view.addOnDidFailLoadingMapListener { if (!destroyed) onError() }
        view.setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_DOWN) followed = false; false }
        view.getMapAsync { current ->
            if (destroyed) return@getMapAsync
            map = current
            current.cameraPosition = CameraPosition.Builder().target(LatLng(43.36, -5.85)).zoom(12.0).build()
            current.addOnMapClickListener { point ->
                val hit = current.queryRenderedFeatures(current.projection.toScreenLocation(point), "phone-places-single", "phone-places-clusters").firstOrNull()
                if (hit == null) false else {
                    followed = false
                    if (hit.hasProperty("point_count")) current.moveCamera(CameraUpdateFactory.newLatLngZoom(point, current.cameraPosition.zoom + 1.5))
                    else places.firstOrNull { it.id == hit.getStringProperty("place_id") }?.let(onPlaceSelected)
                    true
                }
            }
            current.addOnCameraMoveListener { reportViewport() }
            current.addOnCameraIdleListener { reportViewport() }
            reportViewport()
            source?.let { loadStyle(it) }
        }
    }
    fun loadStyle(value: TileSource) {
        if (destroyed || (source == value && style != null)) return
        source = value
        styleGeneration++
        val generation = styleGeneration
        style = null
        val current = map ?: return
        val builder = if (value.uri.startsWith("asset://")) Style.Builder().fromJson(view.context.assets.open(value.uri.removePrefix("asset://")).bufferedReader().use { it.readText() }) else Style.Builder().fromUri(value.uri)
        builder.withSources(GeoJsonSource("phone-route", empty()), GeoJsonSource("phone-rider", empty()), GeoJsonSource("phone-places", empty(), GeoJsonOptions().withCluster(true).withClusterRadius(36).withClusterMaxZoom(14)))
            .withLayers(LineLayer("phone-route-casing", "phone-route").withProperties(lineColor("#071017"), lineWidth(11f)),
                LineLayer("phone-route-line", "phone-route").withProperties(lineColor("#50e3c2"), lineWidth(6f)),
                CircleLayer("phone-places-clusters", "phone-places").withFilter(Expr.has("point_count")).withProperties(circleRadius(15f), circleColor("#ffcf56"), circleStrokeColor("#071017"), circleStrokeWidth(2f)),
                SymbolLayer("phone-places-count", "phone-places").withFilter(Expr.has("point_count")).withProperties(textField(Expr.toString(Expr.get("point_count"))), textFont(arrayOf("Noto Sans Regular")), textSize(11f), textColor("#071017"), textAllowOverlap(true)),
                CircleLayer("phone-places-single", "phone-places").withFilter(Expr.not(Expr.has("point_count"))).withProperties(circleRadius(7f), circleColor(Expr.match(Expr.get("category"), Expr.literal("FUEL"), Expr.literal("#ffcf56"), Expr.literal("WORKSHOP"), Expr.literal("#ff8a65"), Expr.literal("FOOD"), Expr.literal("#81c784"), Expr.literal("LODGING"), Expr.literal("#90caf9"), Expr.literal("SHOPPING"), Expr.literal("#ce93d8"), Expr.literal("#ffcf56"))), circleStrokeColor("#071017"), circleStrokeWidth(2f)),
                CircleLayer("phone-rider-circle", "phone-rider").withProperties(circleRadius(9f), circleColor("#ffffff"), circleStrokeColor("#50e3c2"), circleStrokeWidth(4f)))
        current.setStyle(builder) { loaded ->
            if (!destroyed && source == value && generation == styleGeneration) { style = loaded; renderedAt = 0; applyPlaces(); updateAttribution(); update(state, true) }
        }
    }
    fun update(value: NavigationState, force: Boolean = false) {
        if (destroyed) return
        val changedRoute = state.route != value.route || state.navigationStatus != value.navigationStatus
        state = value
        val now = SystemClock.elapsedRealtime()
        if (!force && !changedRoute && now - renderedAt < 200) return
        renderedAt = now
        val current = map ?: return
        val loaded = style ?: return
        val route = value.route
        val routeData = if (route != null && route.geometry.size >= 2) FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) })))) else empty()
        loaded.getSourceAs<GeoJsonSource>("phone-route")?.setGeoJson(routeData)
        val positionKnown = value.navigationStatus != NavigationStatus.IDLE && value.position != GeoPoint(0.0, 0.0)
        loaded.getSourceAs<GeoJsonSource>("phone-rider")?.setGeoJson(if (positionKnown) FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(value.position.longitude, value.position.latitude)))) else empty())
        loaded.getLayerAs<CircleLayer>("phone-rider-circle")?.setProperties(circleColor(if (value.navigationStatus == NavigationStatus.GPS_LOST) "#89919c" else "#ffffff"), circleStrokeColor(if (value.navigationStatus == NavigationStatus.GPS_LOST) "#ffcf56" else "#50e3c2"))
        if (followed && value.navigationStatus == NavigationStatus.ROUTE_PREVIEW && route != null && (changedRoute || force)) {
            current.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(route.geometry.map { LatLng(it.latitude, it.longitude) }).build(), 36))
        } else if (followed && positionKnown) {
            val camera = TftCameraPolicy().parameters(value)
            current.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(value.position.latitude, value.position.longitude)).zoom(camera.zoom - .8).bearing(camera.bearingDegrees.toDouble()).build()))
        }
    }
    private fun updateAttribution() {
        if (destroyed) return
        val configured = source?.attribution ?: return
        val native = style?.sources.orEmpty().mapNotNull { it.attribution }.map { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }.filter { it.isNotEmpty() }
        val base = if (source?.uri?.startsWith("https:") == true && native.isNotEmpty()) native.distinct().joinToString(" · ") else configured
        val text = if (places.isNotEmpty() && !base.contains("OpenStreetMap")) "$base · © OpenStreetMap contributors" else base
        if (reportedCredits != text) { reportedCredits = text; onAttribution(text) }
    }
    private fun reportViewport() {
        if (destroyed) return
        val bounds = map?.projection?.visibleRegion?.latLngBounds ?: return
        onViewportChanged(PlaceViewport(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast))
    }
    fun refreshPlaces() = reportViewport()
    fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
    fun setPlaces(value: List<OfflinePlace>) { places = value.take(300).toList(); applyPlaces(); updateAttribution() }
    private fun applyPlaces() {
        val features = places.map { place -> Feature.fromGeometry(Point.fromLngLat(place.position.longitude, place.position.latitude)).apply { addStringProperty("place_id", place.id); addStringProperty("category", place.category.name) } }
        style?.getSourceAs<GeoJsonSource>("phone-places")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    fun recenter() { followed = true; update(state, true) }
    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onSaveInstanceState(bundle: Bundle) = view.onSaveInstanceState(bundle)
    fun onLowMemory() = view.onLowMemory()
    fun onDestroy() { destroyed = true; style = null; map = null; view.onDestroy() }
    private fun empty() = FeatureCollection.fromFeatures(emptyArray<Feature>())
}
