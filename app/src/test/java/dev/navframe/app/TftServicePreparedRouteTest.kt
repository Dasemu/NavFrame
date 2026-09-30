package dev.navframe.app

import android.os.Looper
import dev.navframe.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TftServicePreparedRouteTest {
    @Test fun stoppingWhileAutomaticPreviewRendersCannotPublishTheOldRouteLater() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        val pendingFrame = CompletableDeferred<TftFrame>()
        val route = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.37, -5.85)), emptyList(), 1112, 120)
        service.routingEngineFactory = { object : RoutingEngine {
            override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions) = route
            override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = route
        } }
        service.mapRenderOverride = { _, _ -> pendingFrame.await() }
        try {
            service.calculateRoute(route.geometry.last(), true, "https://example.test/route")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(NavigationStatus.ROUTE_PREVIEW, service.navigationState.value.navigationStatus)
            assertNull(service.lastFrame.value)
            service.stopNavigation(clearFrame = false)
            pendingFrame.complete(TftFrame(encodedBytes = byteArrayOf(9), timestampMs = 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertNull(service.lastFrame.value)
            assertFalse(service.hasActiveGuidance())
            assertEquals(NavigationStatus.IDLE, service.navigationState.value.navigationStatus)
        } finally { controller.destroy() }
    }
    @Test fun missingStoredOfflineSourceFallsBackWithoutBreakingService() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        try {
            service.getSharedPreferences("map", android.content.Context.MODE_PRIVATE).edit()
                .putString("style", "file:///missing/offline/style.json").putString("attribution", "offline").commit()
            assertEquals("asset://map/tft-style.json", service.mapSource().uri)
            assertFalse(service.getSharedPreferences("map", android.content.Context.MODE_PRIVATE).contains("style"))
            assertEquals(NavigationStatus.IDLE, service.navigationState.value.navigationStatus)
            service.getSharedPreferences("map", android.content.Context.MODE_PRIVATE).edit()
                .putString("style", "https://private.example/map?token=secret").commit()
            val report = service.diagnosticReport()
            assertTrue(report.contains("Origen del mapa: ONLINE"))
            assertFalse(report.contains("private.example"))
            assertFalse(report.contains("secret"))
        } finally { controller.destroy() }
    }
    @Test fun invalidFailedAndPendingAlternativeKeepActiveGuidanceUntilExplicitStart() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        val old = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.37, -5.85)), emptyList(), 1112, 120)
        val replacement = old.copy(geometry = listOf(GeoPoint(43.36, -5.85), GeoPoint(43.36, -5.83)), distanceMeters = 1610)
        var reply = CompletableDeferred(old)
        var receivedOptions: RouteOptions? = null
        val frames = mutableListOf<NavigationState>()
        service.routingEngineFactory = {
            object : RoutingEngine {
                override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult { receivedOptions = options; return reply.await() }
                override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = reply.await()
            }
        }
        service.mapRenderOverride = { state, _ ->
            assertEquals(state, service.navigationState.value) // Same guidance input; phone and TFT have different render cadence.
            frames += state; TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0)
        }
        fun tick() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500)) }
        try {
            val options = RouteOptions(RouteProfile.AVOID_UNPAVED, avoidMotorways = true)
            service.calculateRoute(GeoPoint(43.37, -5.85), true, "https://example.test/route", options)
            tick()
            assertEquals(options, receivedOptions)
            assertFalse(service.hasActiveGuidance())
            assertFalse(service.isGpsSessionActive())
            assertEquals(NavigationStatus.ROUTE_PREVIEW, service.navigationState.value.navigationStatus)
            service.startRoute(); tick()
            assertSame(old, frames.last().route)
            assertEquals(NavigationStatus.NAVIGATING, frames.last().navigationStatus)

            service.invalidateRoutePlan() // Activity invalidates before parsing coordinate fields.
            try { service.calculateRoute(GeoPoint(Double.NaN, 0.0), true, "https://example.test/route"); fail() }
            catch (_: IllegalArgumentException) { }
            tick()
            assertSame(old, frames.last().route)

            reply = CompletableDeferred<RouteResult>().apply { completeExceptionally(IOException("offline")) }
            service.calculateRoute(GeoPoint(43.36, -5.83), true, "https://example.test/route")
            tick()
            assertTrue(service.routeInfo.value.startsWith("No se pudo calcular"))
            assertSame(old, frames.last().route)

            reply = CompletableDeferred()
            service.calculateRoute(GeoPoint(43.36, -5.83), true, "https://example.test/route")
            tick()
            assertSame(old, frames.last().route)
            reply.complete(replacement); tick()
            assertSame(old, frames.last().route) // Receiving a preview does not switch guidance.
            service.startRoute(true); tick()
            assertSame(old, frames.last().route) // A preview click must also preserve active guidance.
            assertTrue(service.routeInfo.value.startsWith("Detén el guiado"))
            service.startRoute(); tick()
            assertSame(replacement, frames.last().route)
            assertEquals(NavigationStatus.NAVIGATING, frames.last().navigationStatus)
        } finally { controller.destroy() }
    }
}
