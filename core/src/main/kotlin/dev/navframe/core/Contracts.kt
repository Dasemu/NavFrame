package dev.navframe.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED, ERROR }

data class TftFrame(
    val width: Int = 480,
    val height: Int = 240,
    val encodedBytes: ByteArray,
    val timestampMs: Long,
) {
    init { require(width == 480 && height == 240) { "M1 requires a native 480×240 frame" } }
}

interface TftTransport {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendFrame(frame: TftFrame)
}
interface TftRenderer { suspend fun render(state: NavigationState): TftFrame }

data class GeoPoint(val latitude: Double, val longitude: Double)
data class Maneuver(
    val instruction: String,
    /** Length of this maneuver's segment, not the rider's distance to the next turn. */
    val distanceMeters: Int,
    val type: Int? = null,
    val beginShapeIndex: Int? = null,
    val endShapeIndex: Int? = null,
    val durationSeconds: Int = 0,
    val streetNames: List<String> = emptyList(),
)
data class RouteResult(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** Requested destination; may differ from the endpoint snapped to a road. */
    val destination: GeoPoint? = null,
    /** Valhalla maneuver.rough evidence; null when response does not establish the surface. */
    val hasUnpaved: Boolean? = null,
    /** Locale of the server-provided maneuver text; retained until this route is replaced. */
    val guidanceLanguage: String = "es-ES",
)
enum class RouteProfile { FASTEST, AVOID_MOTORWAYS, TOURING, AVOID_UNPAVED }
/** Preferences, not guarantees or legal/surface certification. Presets combine with explicit flags. */
data class RouteOptions(
    val profile: RouteProfile = RouteProfile.FASTEST,
    val avoidMotorways: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val avoidTolls: Boolean = false,
)
interface RoutingEngine {
    suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult
    suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions): RouteResult
}
data class SearchResult(
    val name: String,
    val position: GeoPoint,
    val attribution: String = "© OpenStreetMap contributors",
)
interface Geocoder { suspend fun search(query: String): List<SearchResult> }
data class MapRegion(val name: String, val southWest: GeoPoint, val northEast: GeoPoint)
data class TileSource(val uri: String, val attribution: String)
interface MapDataSource { suspend fun prepareRegion(region: MapRegion); fun tileSource(): TileSource }
data class LocationFix(val position: GeoPoint, val bearing: Float, val speedMetersPerSecond: Float, val accuracyMeters: Float, val timestampMs: Long)
interface LocationProvider { val locations: Flow<LocationFix>; suspend fun start(); suspend fun stop() }
enum class NavigationStatus { IDLE, SEARCHING, ROUTE_PREVIEW, NAVIGATING, OFF_ROUTE, REROUTING, ARRIVED, GPS_LOST, TFT_DISCONNECTED, ERROR }
data class NavigationState(
    val position: GeoPoint = GeoPoint(0.0, 0.0),
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val route: RouteResult? = null,
    val nextManeuver: Maneuver? = null,
    val distanceToNextManeuverMeters: Int? = null,
    val remainingDistanceMeters: Int? = null,
    val etaEpochMillis: Long? = null,
    val navigationStatus: NavigationStatus = NavigationStatus.IDLE,
    val accuracyMeters: Float? = null,
)
