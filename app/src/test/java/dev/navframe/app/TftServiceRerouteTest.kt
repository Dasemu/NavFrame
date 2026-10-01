package dev.navframe.app

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import dev.navframe.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TftServiceRerouteTest {
    @Test fun reliableFixesMovingBackAlongOldRoadRequestRouteFromCurrentPositionAndResumeGuidance() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = service.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val destination = GeoPoint(0.0, 0.02)
        val original = RouteResult(listOf(GeoPoint(0.0, 0.0), destination), emptyList(), 2224, 180)
        val pendingReroute = CompletableDeferred<RouteResult>()
        val options = RouteOptions(RouteProfile.AVOID_UNPAVED, avoidMotorways = true)
        var calculations = 0
        var rerouteOrigin: GeoPoint? = null
        var rerouteOptions: RouteOptions? = null
        service.routingEngineFactory = { object : RoutingEngine {
            override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult {
                calculations++
                if (calculations == 1) return original
                rerouteOrigin = origin
                rerouteOptions = options
                return pendingReroute.await()
            }
            override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = error("Unexpected routing API")
        } }
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        fun tick(millis: Long = 100) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
        fun awaitNavigationStatus(expected: NavigationStatus) {
            // The initial no-fix frame is encoded on Dispatchers.Default; draining only the
            // Android looper does not wait for that worker to resume the service loop.
            val deadline = System.nanoTime() + 3_000_000_000L
            while (service.navigationState.value.navigationStatus != expected && System.nanoTime() < deadline) {
                Thread.sleep(10)
                tick(10)
            }
            assertEquals(service.diagnosticReport() + "\n" + service.routeInfo.value,
                expected, service.navigationState.value.navigationStatus)
        }
        var timestamp = 0L
        fun position(longitude: Double, accuracy: Float = 5f) {
            timestamp += 1000
            shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0; this.longitude = longitude; this.accuracy = accuracy
                speed = 12f; bearing = 270f; time = timestamp
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            tick()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (service.navigationState.value.position.longitude != longitude && System.nanoTime() < deadline) {
                Thread.sleep(10)
                tick(10)
            }
            assertEquals(service.diagnosticReport(), longitude, service.navigationState.value.position.longitude, 0.0)
        }
        try {
            service.setPhoneVisible(true); tick()
            position(0.01)
            service.calculateRoute(destination, false, "https://example.test/route", options); tick()
            service.startRoute(); tick(600)
            position(0.01)
            tick(1000)
            position(0.01)
            awaitNavigationStatus(NavigationStatus.NAVIGATING)
            for (longitude in listOf(0.0098, 0.0095, 0.0092, 0.0089, 0.0086, 0.0083, 0.0080)) {
                tick(1500)
                position(longitude)
            }
            awaitNavigationStatus(NavigationStatus.REROUTING)
            assertEquals(2, calculations)
            assertEquals(options, rerouteOptions)
            assertTrue(requireNotNull(rerouteOrigin).longitude < 0.0095)
            assertEquals(NavigationStatus.REROUTING, service.navigationState.value.navigationStatus)
            assertEquals(GeoPoint(0.0, 0.0080), service.navigationState.value.position)
            val replacement = RouteResult(listOf(requireNotNull(rerouteOrigin), GeoPoint(0.0, 0.007), destination),
                listOf(Maneuver("Arrive", 0, 4, 2, 2)), 1300, 120)
            pendingReroute.complete(replacement); tick(1000)
            position(0.0079)
            assertSame(replacement, service.navigationState.value.route)
            assertEquals(NavigationStatus.NAVIGATING, service.navigationState.value.navigationStatus)
            assertNotNull(service.navigationState.value.nextManeuver)
            assertEquals(2, calculations)
        } finally { controller.destroy() }
    }
}
