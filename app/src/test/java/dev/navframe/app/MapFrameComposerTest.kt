package dev.navframe.app

import android.graphics.*
import dev.navframe.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Test fixtures verify overlay/encoding only; they do not claim to execute MapLibre's Android GPU renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapFrameComposerTest {
    private fun decode(frame: TftFrame) = requireNotNull(BitmapFactory.decodeByteArray(frame.encodedBytes, 0, frame.encodedBytes.size))
    @Test fun preservesNativeMapPixelsAndProjectsMarkerAtSuppliedSnapshotPoint() = runBlocking {
        val map = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(44, 60, 76)) }
        val state = NavigationState(speed = 8f, bearing = 40f, navigationStatus = NavigationStatus.NAVIGATING)
        try {
            val decoded = decode(MapFrameComposer().compose(map, PointF(240f, 151f), state, NavigationMode.MAP_DEMO, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            try {
                assertEquals(480, decoded.width); assertEquals(240, decoded.height)
                val pixel = decoded.getPixel(20, 90)
                assertTrue(kotlin.math.abs(Color.red(pixel) - 44) < 8)
                assertTrue(Color.red(decoded.getPixel(240, 144)) > 180)
            } finally { decoded.recycle() }
        } finally { map.recycle() }
    }
    @Test fun unavailableSourceDoesNotRepeatOldMapOrLiveSpeed() = runBlocking {
        val state = NavigationState(speed = 19f, bearing = 60f, navigationStatus = NavigationStatus.NAVIGATING)
        val composer = MapFrameComposer()
        val frame = composer.compose(null, null, state, NavigationMode.MAP_GPS, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION, unavailable = true)
        val decoded = decode(frame)
        try { assertEquals(480, decoded.width); assertTrue(Color.red(decoded.getPixel(20, 50)) < 25) }
        finally { decoded.recycle() }
    }
    @Test fun guidedLayoutReservesMoreOfTheTftForTheMap() = runBlocking {
        val map = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(44, 60, 76)) }
        try {
            val route = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.845)), emptyList(), 12000, 900)
            val state = NavigationState(route = route, nextManeuver = Maneuver("Gira a la derecha", 400, 10, 0, 1, 20),
                distanceToNextManeuverMeters = 400, navigationStatus = NavigationStatus.NAVIGATING)
            val decoded = decode(MapFrameComposer().compose(map, PointF(240f, 145f), state, NavigationMode.MAP_GPS, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            try {
                assertEquals(480, decoded.width); assertEquals(240, decoded.height)
                assertTrue("Map should begin immediately below the compact maneuver band", Color.red(decoded.getPixel(10, 56)) > 35)
                assertTrue("Map should remain visible almost to the compact footer", Color.red(decoded.getPixel(10, 205)) > 35)
                assertTrue("Footer should start below the enlarged map viewport", Color.red(decoded.getPixel(250, 225)) < 25)
            } finally { decoded.recycle() }
        } finally { map.recycle() }
    }
    @Test fun longAttributionRemainsInsideFrameAndUsesMultipleLines() = runBlocking {
        val frame = MapFrameComposer().compose(null, null, NavigationState(), NavigationMode.MAP_GPS, "© Example map provider and its contributors · OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors")
        val decoded = decode(frame)
        try {
            var whitePixels = 0
            for (y in 215 until 240) for (x in 8 until 472) if (Color.red(decoded.getPixel(x, y)) > 160) whitePixels++
            assertTrue("Credits must remain visible after JPEG encoding", whitePixels > 150)
        } finally { decoded.recycle() }
    }    @Test fun routePreviewShowsPlannedTotalsWithoutCountdownOrInventedManeuver() = runBlocking {
        val map = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(44, 60, 76)) }
        try {
            val route = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.845)), emptyList(), 12000, 900)
            val base = NavigationState(speed = 8f, bearing = 20f, navigationStatus = NavigationStatus.NAVIGATING)
            val composer = MapFrameComposer()
            val plain = decode(composer.compose(map, PointF(240f, 151f), base, NavigationMode.MAP_DEMO, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            val planned = decode(composer.compose(map, PointF(240f, 151f), base.copy(route = route, navigationStatus = NavigationStatus.ROUTE_PREVIEW), NavigationMode.MAP_DEMO, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            try {
                assertTrue(Color.red(plain.getPixel(470, 35)) > 35)
                assertTrue(Color.red(planned.getPixel(470, 35)) < 25)
                assertEquals(480, planned.width)
                assertTrue(Color.red(planned.getPixel(240, 144)) > 180)
            } finally { plain.recycle(); planned.recycle() }
        } finally { map.recycle() }
    }
    @Test fun guidanceHudExportsRealCanvasPreviewAndSuppressesStaleTurnOnSignalLoss(): Unit = runBlocking {
        val route = RouteResult(
            listOf(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.85), GeoPoint(43.365, -5.845)),
            listOf(Maneuver("Sal hacia el norte", 556, 1, 0, 1, 50), Maneuver("Gira a la derecha hacia Calle de prueba", 404, 10, 1, 2, 40), Maneuver("Has llegado", 0, 4, 2, 2)), 960, 90)
        val state = RouteDemo(route).stateAt(12_000, 1_800_000_000_000L)
        assertTrue(requireNotNull(state.distanceToNextManeuverMeters) in 380..450)
        val map = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(map)
        canvas.drawColor(Color.rgb(23, 31, 40))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(43, 53, 65); strokeWidth = 2f }
        for (x in 0..480 step 40) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 240f, p)
        for (y in 0..240 step 40) canvas.drawLine(0f, y.toFloat(), 480f, y.toFloat(), p)
        p.color = Color.rgb(73, 99, 122); p.strokeWidth = 17f
        canvas.drawLine(240f, 190f, 240f, 108f, p); canvas.drawLine(240f, 108f, 430f, 108f, p)
        p.color = Color.rgb(80, 227, 194); p.strokeWidth = 6f
        canvas.drawLine(240f, 190f, 240f, 108f, p); canvas.drawLine(240f, 108f, 430f, 108f, p)
        p.color = Color.LTGRAY; p.textSize = 11f
        canvas.drawText("BASE DE PRUEBA · NO TESELAS", 8f, 102f, p)
        try {
            val composer = MapFrameComposer()
            val guided = decode(composer.compose(map, PointF(240f, 150f), state, NavigationMode.MAP_DEMO, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            val lost = decode(composer.compose(map, PointF(240f, 150f), state.copy(navigationStatus = NavigationStatus.GPS_LOST), NavigationMode.MAP_GPS, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            val rerouting = decode(composer.compose(map, PointF(240f, 150f), state.copy(navigationStatus = NavigationStatus.REROUTING), NavigationMode.MAP_GPS, ConfiguredMapDataSource.DEFAULT_ATTRIBUTION))
            fun iconPixels(bitmap: Bitmap): Int {
                var count = 0
                for (y in 31..85) for (x in 15..66) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.green(color) > 150 && Color.blue(color) > 130 && Color.red(color) < 130) count++
                }
                return count
            }
            try {
                assertTrue(iconPixels(guided) > 100)
                assertEquals(0, iconPixels(lost))
                assertEquals(0, iconPixels(rerouting))
                val directory = java.io.File(requireNotNull(System.getProperty("navframe.previewDir"))).apply { mkdirs() }
                java.io.File(directory, "guidance.png").outputStream().use { guided.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { guided.recycle(); lost.recycle(); rerouting.recycle() }
        } finally { map.recycle() }
        Unit
    }

}
