package dev.navframe.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import dev.navframe.core.*
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
class ImportedRouteTest {
    class PhoneWithoutGpu : MainActivity() { override fun createPhoneMap(savedState: Bundle?): PhoneMapView? = null }
    /** A detailed trace (about 11 m between points) is followed as is, without a routing server. */
    private fun imported() = GpxRouteImporter.parse(("<gpx><trk><name>Weekend</name><trkseg>" +
        (0..90).joinToString("") { "<trkpt lat=\"${43.36 + it * 0.0001}\" lon=\"-5.85\"/>" } + "</trkseg></trk></gpx>").byteInputStream())
    private class RoadEngine(val fail: Boolean = false) : RoutingEngine {
        val requests = mutableListOf<Pair<GeoPoint, GeoPoint>>()
        override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult {
            requests += origin to destination
            if (fail) throw java.io.IOException("offline")
            val bend = GeoPoint((origin.latitude + destination.latitude) / 2, origin.longitude + 0.002)
            return RouteResult(listOf(origin, bend, destination), listOf(
                Maneuver("Gire a la derecha", 600, type = 10, beginShapeIndex = 1, endShapeIndex = 2),
                Maneuver("Ha llegado", 0, type = 4, beginShapeIndex = 2, endShapeIndex = 2),
            ), 1_200, 120, destination)
        }
        override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = error("unused")
    }
    private fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun importedPreviewRequiresGpsAndStartsWithoutRoutingOrRerouting() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        service.routingEngineFactory = { error("Imported routes must not use a server") }
        try {
            val route = imported()
            service.prepareImportedRoute(route); tick()
            assertTrue(service.preparedRouteIsImported())
            assertTrue(service.routeUsesGps())
            assertEquals(NavigationStatus.ROUTE_PREVIEW, service.navigationState.value.navigationStatus)
            assertSame(route.route, service.navigationState.value.route)
            service.startRoute(); tick()
            assertFalse(service.hasActiveGuidance())
            assertTrue(service.routeInfo.value.contains("GPS reciente"))

            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val manager = application.getSystemService(LocationManager::class.java)
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            service.setPhoneVisible(true); tick()
            shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 43.36; longitude = -5.85; accuracy = 3f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }); tick()
            assertTrue(service.hasFreshGpsFix())
            service.startRoute(); tick()
            assertTrue(service.hasActiveGuidance())
            assertSame(route.route, service.navigationState.value.route)
            service.requestReroute(); tick()
            assertTrue(service.routeInfo.value.contains("no se modifica"))
            assertSame(route.route, service.navigationState.value.route)
            assertNull(service.navigationState.value.nextManeuver)
            assertNull(service.navigationState.value.etaEpochMillis)
            service.stopRoute(); tick()
            assertFalse(service.hasActiveGuidance())
            assertFalse(service.hasPreparedRoute())
            assertNull(service.navigationState.value.route)
        } finally { controller.destroy() }
    }
    @Test fun filePickerUsesSafAndCancelKeepsThePreparedRoute() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        try {
            field("service").set(activity, service)
            service.prepareImportedRoute(imported()); tick()
            MainActivity::class.java.getDeclaredMethod("chooseRouteFile").apply { isAccessible = true }.invoke(activity)
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
            MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, 76, Activity.RESULT_CANCELED, null)
            assertTrue(service.preparedRouteIsImported())
            assertFalse(service.hasActiveGuidance())
        } finally { activityController.destroy(); serviceController.destroy() }
    }
    @Test fun importedStartRequestsFineLocationAndDenialNeverStartsNavigation() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        try {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            field("service").set(activity, service)
            service.prepareImportedRoute(imported()); tick()
            val actionClass = Class.forName("dev.navframe.app.MainActivity\$Action")
            val action = actionClass.enumConstants.first { (it as Enum<*>).name == "IMPORT_ROUTE" }
            MainActivity::class.java.getDeclaredMethod("withPermissions", actionClass).apply { isAccessible = true }.invoke(activity, action)
            assertNotNull(field("pendingAction").get(activity))
            activity.onRequestPermissionsResult(42, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            tick()
            assertFalse(service.hasActiveGuidance())
            assertTrue(service.preparedRouteIsImported())
            assertTrue((field("hint").get(activity) as android.widget.TextView).text.contains("Permiso denegado"))
        } finally { activityController.destroy(); serviceController.destroy() }
    }
    @Test fun validDocumentAutomaticallyRequestsNavigationPermissionAndInvalidDocumentPreservesTrace() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        fun importDocument(xml: String) {
            val uri = android.net.Uri.parse("content://test/route.gpx")
            shadowOf(activity.contentResolver).registerInputStream(uri, xml.byteInputStream())
            MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, 76, Activity.RESULT_OK, Intent().setData(uri))
            val job = field("importJob").get(activity) as kotlinx.coroutines.Job
            for (attempt in 0..200) { if (job.isCompleted) break; Thread.sleep(5); tick() }
            assertTrue("Document import should finish", job.isCompleted)
        }
        try {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            field("service").set(activity, service)
            val engine = RoadEngine()
            service.routingEngineFactory = { engine }
            importDocument("""<gpx><rte><rtept lat="43.36" lon="-5.85"/><rtept lat="43.37" lon="-5.85"/><rtept lat="43.38" lon="-5.85"/></rte></gpx>""")
            tick()
            // Route points are joined along roads, keeping turn instructions, never by straight lines.
            assertEquals(2, engine.requests.size)
            assertTrue(service.preparedRouteIsImported())
            val routed = requireNotNull(service.navigationState.value.route)
            assertEquals(5, routed.geometry.size)
            assertEquals(listOf(10, 10, 4), routed.maneuvers.map { it.type })
            assertEquals(GeoPoint(43.38, -5.85), routed.destination)
            assertEquals("IMPORT_ROUTE", (field("pendingAction").get(activity) as Enum<*>).name)
            assertFalse(service.hasActiveGuidance())
            val oldRoute = service.navigationState.value.route
            importDocument("<gpx><rte><rtept lat=\"NaN\" lon=\"0\"/></rte></gpx>")
            assertSame(oldRoute, service.navigationState.value.route)
            assertTrue((field("hint").get(activity) as android.widget.TextView).text.contains("coordenadas inválidas"))
        } finally { activityController.destroy(); serviceController.destroy() }
    }
    @Test fun closingImportedPanelCancelsThePendingGpsStart() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        try {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            field("service").set(activity, service)
            service.prepareImportedRoute(imported()); tick()
            MainActivity::class.java.getDeclaredMethod("showImportedRouteSheet", String::class.java).apply { isAccessible = true }.invoke(activity, "Test")
            MainActivity::class.java.getDeclaredMethod("startImportedRoute").apply { isAccessible = true }.invoke(activity)
            val pending = field("positionWait").get(activity) as kotlinx.coroutines.Job
            assertTrue(pending.isActive)
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss(); tick()
            assertTrue(pending.isCancelled)
            assertFalse(service.hasActiveGuidance())
        } finally { activityController.destroy(); serviceController.destroy() }
    }

    @Test fun importedStartWaitsForGuidanceAccuracyEvenWhenMapReadinessStaysTrue() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        try {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            field("service").set(activity, service)
            val manager = application.getSystemService(LocationManager::class.java)
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            service.prepareImportedRoute(imported()); tick()
            service.setPhoneVisible(true); tick()
            fun fix(accuracy: Float) {
                shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 43.36; longitude = -5.85; this.accuracy = accuracy
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                tick()
            }
            fix(80f)
            assertTrue(service.gpsReady.value)
            assertTrue(service.hasFreshGpsFix())
            assertFalse(service.hasGuidanceGpsFix())
            MainActivity::class.java.getDeclaredMethod("startImportedRoute").apply { isAccessible = true }.invoke(activity)
            val pending = field("positionWait").get(activity) as kotlinx.coroutines.Job
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertTrue(pending.isActive)
            assertNull(shadowOf(activity).nextStartedService)
            assertFalse(service.hasActiveGuidance())
            fix(20f)
            assertTrue(service.gpsReady.value)
            assertTrue(service.hasGuidanceGpsFix())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertTrue(pending.isCompleted)
            assertEquals(TftService.ACTION_ROUTE_GPS, shadowOf(activity).nextStartedService.action)
        } finally { activityController.destroy(); serviceController.destroy() }
    }

    @Test fun failedRoadCalculationNeverOffersStraightLinesAndCanBeRetried() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        try {
            val stops = GpxRouteImporter.parse("""<gpx><rte><rtept lat="43.36" lon="-5.85"/><rtept lat="43.37" lon="-5.85"/></rte></gpx>""".byteInputStream())
            assertTrue(stops.needsRoadRouting)
            service.routingEngineFactory = { RoadEngine(fail = true) }
            var ready = false
            service.calculateImportedStops(stops) { ready = true }; tick()
            assertFalse(ready)
            assertFalse(service.hasPreparedRoute())
            assertTrue(service.routeInfo.value.startsWith("No se pudo"))
            service.routingEngineFactory = { RoadEngine() }
            service.calculateImportedStops(stops) { ready = true }; tick()
            assertTrue(ready)
            assertTrue(service.preparedRouteIsImported())
            assertEquals(3, service.navigationState.value.route?.geometry?.size)
        } finally { controller.destroy() }
    }
}
