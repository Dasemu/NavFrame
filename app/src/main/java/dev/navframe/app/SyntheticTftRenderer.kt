package dev.navframe.app

import android.graphics.*
import dev.navframe.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

enum class NavigationMode { IDLE, DEMO, GPS, MAP_DEMO, MAP_GPS }

/** Native 480×240 framebuffer. Roads and turns in DEMO are illustrations, never real map data. */
class SyntheticTftRenderer : TftRenderer {
    override suspend fun render(state: NavigationState) = render(state, NavigationMode.DEMO, null)

    suspend fun render(state: NavigationState, mode: NavigationMode, accuracyMeters: Float?): TftFrame = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(13, 19, 25))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun text(label: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
                paint.apply { this.color = color; textSize = size; style = Paint.Style.FILL; textAlign = Paint.Align.LEFT; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                canvas.drawText(label, x, y, paint)
            }
            fun stroke(path: Path, color: Int, width: Float) {
                paint.apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                canvas.drawPath(path, paint)
            }
            val accent = Color.rgb(80, 227, 194)
            val camera = TftCameraPolicy().parameters(state)
            val gpsLost = mode == NavigationMode.IDLE || state.navigationStatus == NavigationStatus.GPS_LOST
            if (mode == NavigationMode.DEMO) {
                // Curve and perspective respond to the simulated heading and approach to a turn.
                val left = state.nextManeuver?.instruction?.contains("izquierda") == true
                val bend = sin(Math.toRadians(state.bearing.toDouble())).toFloat() * 70f * (if (left) -1f else 1f)
                val closeness = (1f - ((state.distanceToNextManeuverMeters ?: 350) / 500f)).coerceIn(0f, 1f)
                val road = Path().apply {
                    moveTo(170f, 230f)
                    cubicTo(170f, 190f, 170f, 166f, 170f, camera.riderAnchorY * 200f)
                    cubicTo(170f, 108f, 215f + bend * closeness, 80f, 150f + bend, -20f)
                }
                stroke(Path().apply { moveTo(0f, 108f); lineTo(300f, 85f) }, Color.rgb(41, 50, 58), 13f)
                stroke(Path().apply { moveTo(60f, 0f); lineTo(96f, 210f) }, Color.rgb(35, 44, 52), 11f)
                stroke(road, Color.rgb(55, 65, 75), (40f + (camera.zoom - 16.0).toFloat() * 4f))
                stroke(road, Color.rgb(25, 34, 43), 32f)
                stroke(road, accent, 10f)
                text("DEMO · RUTA FICTICIA", 10f, 23f, 17f, accent)
                paint.color = Color.rgb(27, 38, 49); paint.style = Paint.Style.FILL
                canvas.drawRoundRect(300f, 35f, 474f, 151f, 12f, 12f, paint)
                val arrow = if (left) Path().apply { moveTo(366f, 82f); lineTo(366f, 57f); lineTo(324f, 57f); moveTo(340f, 43f); lineTo(324f, 57f); lineTo(340f, 71f) }
                    else Path().apply { moveTo(328f, 82f); lineTo(328f, 57f); lineTo(370f, 57f); moveTo(354f, 43f); lineTo(370f, 57f); lineTo(354f, 71f) }
                stroke(arrow, accent, 7f)
                text("${state.distanceToNextManeuverMeters ?: 0} m", 313f, 116f, 30f)
                text(if (left) "IZQUIERDA" else "DERECHA", 311f, 141f, 15f)
                text("${(state.speed * 3.6f).roundToInt()} km/h", 320f, 185f, 26f)
            } else {
                // Local meter grid makes actual motion visible while explicitly showing no map.
                val northMeters = state.position.latitude * 111_320.0
                val eastMeters = state.position.longitude * 111_320.0 * cos(Math.toRadians(state.position.latitude))
                val offsetX = ((eastMeters % 40.0) + 40.0).toFloat() % 40f
                val offsetY = ((northMeters % 40.0) + 40.0).toFloat() % 40f
                paint.apply { color = Color.rgb(39, 49, 59); style = Paint.Style.STROKE; strokeWidth = 1f }
                for (n in -1..7) {
                    canvas.drawLine(n * 40f - offsetX, 30f, n * 40f - offsetX, 198f, paint)
                    canvas.drawLine(0f, n * 40f + offsetY, 292f, n * 40f + offsetY, paint)
                }
                text(if (mode == NavigationMode.IDLE) "NAVEGACIÓN DETENIDA" else "GPS · SIN MAPA/RUTA", 10f, 23f, 18f, accent)
                text(if (mode == NavigationMode.IDLE) "DETENIDO" else if (gpsLost) "SIN SEÑAL" else "GPS ACTIVO", 306f, 65f, 21f, if (gpsLost) Color.YELLOW else accent)
                text(if (gpsLost) "-- km/h" else "${(state.speed * 3.6f).roundToInt()} km/h", 306f, 105f, 30f)
                text(if (gpsLost || !state.bearing.isFinite()) "Rumbo --" else "Rumbo ${state.bearing.roundToInt()}°", 306f, 143f, 22f)
                val accuracy = accuracyMeters?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "--"
                text(if (gpsLost) "Precisión --" else "± $accuracy m", 306f, 181f, 22f)
                text("N", 278f, 53f, 20f)
            }
            // Rider remains in lower third; GPS heading is actual (no synthetic turn instructions).
            val centerX = 170f
            val centerY = camera.riderAnchorY * 200f
            canvas.save()
            canvas.rotate(if (mode == NavigationMode.GPS && !gpsLost && state.bearing.isFinite()) state.bearing else 0f, centerX, centerY)
            val marker = Path().apply { moveTo(centerX, centerY - 21); lineTo(centerX + 15, centerY + 17); lineTo(centerX, centerY + 9); lineTo(centerX - 15, centerY + 17); close() }
            paint.apply { color = if (gpsLost) Color.GRAY else Color.WHITE; style = Paint.Style.FILL }
            canvas.drawPath(marker, paint)
            stroke(marker, Color.rgb(8, 15, 21), 3f)
            canvas.restore()
            paint.apply { color = Color.rgb(7, 12, 17); style = Paint.Style.FILL }
            canvas.drawRect(0f, 200f, 480f, 240f, paint)
            if (mode == NavigationMode.DEMO) {
                val remaining = (state.remainingDistanceMeters ?: 0) / 1000.0
                text(String.format(Locale.ROOT, "%.1f km", remaining), 12f, 228f, 23f)
                val eta = state.etaEpochMillis?.let { SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(it)) } ?: "--:--"
                text("ETA $eta · DEMO", 270f, 228f, 22f)
            } else text(if (mode == NavigationMode.IDLE) "Última navegación finalizada" else if (gpsLost) "Esperando una posición GPS reciente" else "Movimiento real · cuadrícula de prueba", 12f, 227f, 20f)
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output))
                output.toByteArray()
            }
            android.util.Log.i("NavFrame", "FRAME_RENDER mode=$mode bytes=${bytes.size} elapsedMs=${(System.nanoTime()-start)/1_000_000}")
            TftFrame(encodedBytes = bytes, timestampMs = System.currentTimeMillis())
        } finally { bitmap.recycle() }
    }
}
