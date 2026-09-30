package dev.navframe.app

import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.view.View
import android.view.ViewGroup
import dev.navframe.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityDestinationTest {
    class PhoneWithoutGpu : MainActivity() { override fun createPhoneMap(savedState: Bundle?): PhoneMapView? = null }
    class RecenterTestActivity : MainActivity() {
        var reliable = false
        var recenterCalls = 0
        override fun createPhoneMap(savedState: Bundle?): PhoneMapView? = null
        override fun hasReliablePosition() = reliable
        override fun recenterPhoneMap() { recenterCalls++ }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
    @Test fun exportsCompactPhoneLayoutWithPlaceholderMap() {
        val controller = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        try {
            val activity = controller.get()
            val view = activity.findViewById<View>(android.R.id.content)
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 720)
            val bitmap = android.graphics.Bitmap.createBitmap(360, 720, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(requireNotNull(System.getProperty("navframe.previewDir"))).apply { mkdirs() }
            java.io.File(directory, "phone-layout-placeholder.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(view.height > 0)
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("destinationName").set(activity, "Destino de ejemplo")
            (field("routeInfo").get(activity) as android.widget.TextView).text = "Vista previa · 12,4 km · 18 min estimados"
            MainActivity::class.java.getDeclaredMethod("showRouteSheet").apply { isAccessible = true }.invoke(activity)
            val dialogView = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            dialogView.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.AT_MOST))
            dialogView.layout(0, 0, 360, dialogView.measuredHeight)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.save(); canvas.translate(0f, (720 - dialogView.height).toFloat()); dialogView.draw(canvas); canvas.restore()
            java.io.File(directory, "phone-route-sheet-placeholder.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { controller.destroy() }
    }
    @Test fun editingQueryClearsChosenCoordinatesAndCannotRecalculateOldDestination() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        val route = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.37, -5.85)), emptyList(), 1112, 120)
        var requests = 0
        service.routingEngineFactory = { object : RoutingEngine {
            override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult { requests++; return route }
            override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = route
        } }
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        try {
            field("service").set(activity, service)
            MainActivity::class.java.getDeclaredMethod("observeService").apply { isAccessible = true }.invoke(activity)
            field("destination").set(activity, route.geometry.last())
            service.calculateRoute(route.geometry.last(), true, "https://example.test/route")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(1, requests)
            (field("searchQuery").get(activity) as EditText).setText("Una dirección nueva")
            assertNull(field("destination").get(activity))
            (field("origin").get(activity) as Spinner).setSelection(1)
            MainActivity::class.java.getDeclaredMethod("calculateRoute").apply { isAccessible = true }.invoke(activity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(1, requests)
            assertNull(service.navigationState.value.route)
        } finally { activityController.destroy(); serviceController.destroy() }
    }
    @Test fun recenterButtonRequiresFreshGpsAndRepeatedNoticeReplacesItsTimer() {
        val activityController = Robolectric.buildActivity(RecenterTestActivity::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        // Locate through its accessible label, as a UI test would.
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        val recenter = children(activity.window.decorView).first { it.contentDescription == "Centrar en mi posición y seguir el mapa" }
        try {
            field("service").set(activity, service)
            recenter.performClick()
            val notice = field("positionNotice").get(activity) as android.widget.TextView
            assertEquals(0, activity.recenterCalls)
            assertEquals(View.VISIBLE, notice.visibility)
            assertTrue(notice.text.toString().contains("GPS"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            recenter.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
            assertEquals(View.VISIBLE, notice.visibility)
            // The first tap's timer would have expired now; the second tap replaced it.
            activity.reliable = true
            recenter.performClick()
            assertEquals(1, activity.recenterCalls)
            assertEquals(View.GONE, notice.visibility)
        } finally { activityController.destroy(); serviceController.destroy() }
    }
    @Test fun choosingLocalPlaceFromItsRealDetailDialogCalculatesPreviewWithoutStartingGuidance() {
        val activityController = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val serviceController = Robolectric.buildService(TftService::class.java).create()
        val activity = activityController.get()
        val service = serviceController.get()
        val place = OfflinePlace("n/123", "Taller de prueba", PlaceCategory.WORKSHOP, GeoPoint(43.37, -5.85), openingHours = "Mo-Fr 09:00-18:00")
        var requested: GeoPoint? = null
        service.routingEngineFactory = { object : RoutingEngine {
            override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult {
                requested = destination
                kotlinx.coroutines.delay(50)
                return RouteResult(listOf(origin, destination), emptyList(), 1000, 120)
            }
            override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions) = previousRoute
        } }
        service.mapRenderOverride = { _, _ -> TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0) }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            field("service").set(activity, service)
            MainActivity::class.java.getDeclaredMethod("observeService").apply { isAccessible = true }.invoke(activity)
            (field("origin").get(activity) as Spinner).setSelection(1)
            MainActivity::class.java.getDeclaredMethod("showPlaceDetails", OfflinePlace::class.java).apply { isAccessible = true }.invoke(activity, place)
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertNull(requested)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(place.position, requested)
            assertEquals(place.position, field("destination").get(activity))
            assertEquals(place.name, (field("searchQuery").get(activity) as EditText).text.toString())
            assertTrue(service.routeInfo.value.contains("Vista previa"))
            assertFalse(service.hasActiveGuidance())
            val sheet = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(sheet.isShowing)
            fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
            val start = children(sheet.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == "Iniciar" }
            assertTrue(service.hasPreparedRoute())
            // A real press starts the prepared route; STOP clears its geometry.
            assertTrue(start.isEnabled)
            start.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue(service.hasActiveGuidance())
            assertFalse(sheet.isShowing)
            val replacement = GeoPoint(43.38, -5.86)
            MainActivity::class.java.getDeclaredMethod("chooseDestination", GeoPoint::class.java, String::class.java, String::class.java).apply { isAccessible = true }.invoke(activity, replacement, "Nuevo lugar", "© OpenStreetMap contributors")
            val replacementSheet = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val replacementStart = children(replacementSheet.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == "Iniciar" }
            assertFalse(replacementStart.isEnabled)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertTrue(replacementSheet.isShowing)
            assertTrue(service.hasActiveGuidance())
            assertTrue(replacementStart.isEnabled)
            val stop = field("stopButton").get(activity) as Button
            stop.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertFalse(service.hasActiveGuidance())
            assertNull(service.navigationState.value.route)
            assertEquals(View.GONE, stop.visibility)
        } finally { activityController.destroy(); serviceController.destroy() }
    }

}
