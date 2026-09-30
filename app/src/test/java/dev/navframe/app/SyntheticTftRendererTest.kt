package dev.navframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.navframe.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** Executes the production Canvas renderer and native JPEG codec; exports its actual decoded framebuffer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyntheticTftRendererTest {
    private fun export(frame: TftFrame, name: String): Bitmap {
        val directory = File(requireNotNull(System.getProperty("navframe.previewDir"))).apply { mkdirs() }
        File(directory, "$name.jpg").writeBytes(frame.encodedBytes)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(frame.encodedBytes, 0, frame.encodedBytes.size))
        assertEquals(480, decoded.width)
        assertEquals(240, decoded.height)
        File(directory, "$name.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return decoded
    }
    @Test fun demoProducesNativeJpegAndChangesWhenApproachingOppositeTurn() = runBlocking {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        try {
            val renderer = SyntheticTftRenderer()
            val epoch = Instant.parse("2026-09-29T15:24:00Z").toEpochMilli()
            val first = renderer.render(DemoNavigation.stateAt(8_000, epoch), NavigationMode.DEMO, null)
            val next = renderer.render(DemoNavigation.stateAt(45_000, epoch), NavigationMode.DEMO, null)
            assertFalse(first.encodedBytes.contentEquals(next.encodedBytes))
            val decoded = export(first, "demo")
            val nextDecoded = export(next, "demo-left")
            try {
                // Native rasterization must contain the high-contrast route and rider, not a blank shadow bitmap.
                var routePixels = 0
                for (y in 30 until 200) for (x in 0 until 295) {
                    val color = decoded.getPixel(x, y)
                    if (Color.green(color) > Color.red(color) + 30) routePixels++
                }
                assertTrue("Native route must rasterize", routePixels > 400)
                assertTrue(Color.red(decoded.getPixel(170, 141)) > 180)
            } finally { decoded.recycle(); nextDecoded.recycle() }
        } finally { TimeZone.setDefault(previousZone) }
    }
    @Test fun gpsFrameUsesRealFixAndLostSignalClearsLiveSpeed() = runBlocking {
        val renderer = SyntheticTftRenderer()
        val active = NavigationState(position = GeoPoint(43.36, -5.85), bearing = 72f, speed = 13.5f,
            accuracyMeters = 8f, navigationStatus = NavigationStatus.NAVIGATING)
        val frame = renderer.render(active, NavigationMode.GPS, active.accuracyMeters)
        val lost = renderer.render(active.copy(speed = 0f, bearing = Float.NaN, navigationStatus = NavigationStatus.GPS_LOST, accuracyMeters = null), NavigationMode.GPS, null)
        assertFalse(frame.encodedBytes.contentEquals(lost.encodedBytes))
        export(frame, "gps").recycle()
        export(lost, "gps-lost").recycle()
    }
    @Test fun idleFrameIsDifferentFromLiveNavigation() = runBlocking {
        val frame = SyntheticTftRenderer().render(NavigationState(), NavigationMode.IDLE, null)
        export(frame, "stopped").recycle()
        assertTrue(frame.encodedBytes.size > 500)
    }
}
