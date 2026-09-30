package dev.navframe.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.navframe.core.NavigationState
import dev.navframe.core.TftFrame
import dev.navframe.core.TftRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Draws at native TFT size; no phone view is captured or scaled. */
class TestTftRenderer : TftRenderer {
    override suspend fun render(state: NavigationState): TftFrame = renderTest(false)

    suspend fun renderTest(synthetic: Boolean): TftFrame = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
            if (synthetic) {
                text.textSize = 32f
                canvas.drawText("YAMAHA TFT NAV", 240f, 48f, text)
                text.textSize = 24f
                canvas.drawText("480 × 240", 240f, 84f, text)
                text.color = Color.rgb(80, 227, 194)
                text.textSize = 32f
                canvas.drawText("NEXT TURN → 350 m", 240f, 144f, text)
                text.color = Color.WHITE
                text.textSize = 28f
                canvas.drawText("23 km       ETA 17:42", 240f, 204f, text)
            } else {
                text.textSize = 38f
                canvas.drawText("YAMAHA TFT TEST", 240f, 112f, text)
                text.textSize = 32f
                canvas.drawText("480 × 240", 240f, 164f, text)
            }
            val encoded = ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bytes))
                bytes.toByteArray()
            }
            android.util.Log.i("NavFrame", "FRAME_ENCODE width=480 height=240 bytes=${encoded.size} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
            TftFrame(encodedBytes = encoded, timestampMs = System.currentTimeMillis())
        } finally { bitmap.recycle() }
    }
}
