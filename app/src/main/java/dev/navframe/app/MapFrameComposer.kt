package dev.navframe.app

import android.graphics.*
import dev.navframe.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Native JPEG overlay, independent of a map SDK so its layout can be verified without GPU access. */
class MapFrameComposer {
    suspend fun compose(map: Bitmap?, marker: PointF?, state: NavigationState, mode: NavigationMode, attribution: String, unavailable: Boolean = false, failureCode: String? = null): TftFrame = withContext(Dispatchers.Default) {
        require(map == null || (map.width == 480 && map.height == 240)) { "Bitmap dimensions invalid" }
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(13, 19, 25))
            map?.let { canvas.drawBitmap(it, 0f, 0f, null) } // Same dimensions: no phone-resolution render or downscale.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
                paint.apply { this.color = color; textSize = size; style = Paint.Style.FILL; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                canvas.drawText(value, x, y, paint)
            }
            val lost = state.navigationStatus == NavigationStatus.GPS_LOST
            val accent = Color.rgb(80, 227, 194)
            paint.color = Color.rgb(7, 12, 17)
            canvas.drawRect(0f, 0f, 480f, 27f, paint)
            text(if (mode == NavigationMode.MAP_DEMO) "MAPA · DEMO${if (state.route != null) " · RUTA" else " SIMULADO"}" else if (lost) "MAPA GPS · SIN SEÑAL" else if (state.route != null) "MAPA GPS · RUTA CALCULADA" else "MAPA GPS · SIN RUTA", 9f, 20f, 17f, if (lost) Color.YELLOW else accent)
            if (map != null && marker != null) {
                val x = marker.x
                val y = marker.y
                val arrow = Path().apply { moveTo(x, y - 19); lineTo(x + 14, y + 16); lineTo(x, y + 8); lineTo(x - 14, y + 16); close() }
                paint.apply { color = if (lost) Color.GRAY else Color.WHITE; style = Paint.Style.FILL }
                canvas.drawPath(arrow, paint)
                paint.apply { color = if (lost) Color.YELLOW else accent; style = Paint.Style.STROKE; strokeWidth = 3f }
                canvas.drawPath(arrow, paint)
            }
            if (unavailable || map == null) {
                text(if (unavailable) "MAPA NO DISPONIBLE" else "ESPERANDO GPS", 60f, 109f, 27f)
                text(if (unavailable) "TFT: ${failureCode ?: "SDK"} · ${if (lost) "SIN GPS" else "Info"}" else "No se usa una posición ficticia", 52f, 149f, 20f)
            }
            val guidanceVisible = state.route != null && state.navigationStatus != NavigationStatus.ROUTE_PREVIEW
            state.route?.let { route ->
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(7, 12, 17)
                canvas.drawRect(0f, 28f, 480f, if (guidanceVisible) 88f else 51f, paint)
                if (!guidanceVisible) text("PREVIA · %.1f km · %d min".format(java.util.Locale.ROOT, route.distanceMeters / 1000.0, (route.durationSeconds + 59) / 60), 9f, 45f, 16f)
                else {
                    val statusText = when {
                        unavailable || map == null -> "GUIADO EN ESPERA · SIN MAPA"
                        lost -> "SIN GPS · esperando posición precisa"
                        state.navigationStatus == NavigationStatus.REROUTING -> "RECALCULANDO · espera nueva ruta"
                        state.navigationStatus == NavigationStatus.OFF_ROUTE -> "FUERA DE RUTA · revisa el recorrido"
                        state.navigationStatus == NavigationStatus.ARRIVED -> "HAS LLEGADO AL DESTINO"
                        else -> null
                    }
                    if (statusText != null) text(statusText, 9f, 64f, 20f, if (state.navigationStatus == NavigationStatus.ARRIVED) accent else Color.YELLOW)
                    else {
                        val maneuver = state.nextManeuver
                        val direction = maneuverDirection(maneuver?.type).name
                        canvas.save()
                        canvas.translate(40f, 61f)
                        canvas.rotate(if (direction.contains("LEFT")) -90f else if (direction.contains("RIGHT")) 90f else 0f)
                        paint.apply { style = Paint.Style.STROKE; color = accent; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                        val icon = Path().apply { moveTo(0f, 19f); lineTo(0f, -18f); moveTo(-13f, -5f); lineTo(0f, -18f); lineTo(13f, -5f) }
                        if (direction == "ARRIVAL") { canvas.drawLine(-10f, 20f, -10f, -20f, paint); val flag = Path().apply { moveTo(-10f, -20f); lineTo(17f, -12f); lineTo(-10f, -3f) }; canvas.drawPath(flag, paint) }
                        else if (direction.contains("ROUNDABOUT")) canvas.drawCircle(0f, 1f, 16f, paint)
                        else if (direction.contains("UTURN") || direction.contains("U_TURN")) { canvas.drawArc(-15f, -17f, 15f, 13f, 180f, 180f, false, paint); canvas.drawLine(-15f, -2f, -15f, 19f, paint); canvas.drawLine(15f, -2f, 15f, 19f, paint) }
                        else canvas.drawPath(icon, paint)
                        canvas.restore()
                        paint.strokeCap = Paint.Cap.BUTT
                        var instruction = maneuver?.instruction ?: "Continúa por la ruta"
                        paint.textSize = 18f
                        while (instruction.isNotEmpty() && paint.measureText(instruction) > 384f) instruction = instruction.dropLast(1)
                        text(instruction, 81f, 49f, 18f)
                        val next = state.distanceToNextManeuverMeters?.let { if (it >= 1000) "%.1f km".format(java.util.Locale.ROOT, it / 1000.0) else "$it m" } ?: "-- m"
                        text(next, 81f, 79f, 28f, accent)
                    }
                }
            }
            // Wrap rather than clipping a custom provider's mandatory credits.
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 12f
            val lines = mutableListOf<String>()
            var line = ""
            for (character in attribution.trim().replace(Regex("\\s+"), " ")) {
                val next = line + character
                if (line.isNotEmpty() && paint.measureText(next) > 464f) { lines += line.trim(); line = character.toString() }
                else line = next
            }
            if (line.isNotEmpty()) lines += line
            val footer = (240f - lines.size * 15f - if (guidanceVisible) 55f else 34f).coerceAtLeast(27f)
            paint.apply { color = Color.rgb(7, 12, 17); style = Paint.Style.FILL }
            canvas.drawRect(0f, footer, 480f, 240f, paint)
            val speed = if (lost || unavailable || map == null) "-- km/h" else "${(state.speed * 3.6f).roundToInt()} km/h"
            text(speed, 9f, footer + 24f, 23f)
            val heading = if (lost || unavailable || map == null || !state.bearing.isFinite()) "--" else state.bearing.roundToInt().toString()
            text("Rumbo $heading°", 182f, footer + 24f, 20f)
            text(if (mode == NavigationMode.MAP_DEMO) "DEMO" else "GPS", 393f, footer + 24f, 19f, accent)
            if (guidanceVisible) {
                val valid = !lost && !unavailable && map != null && state.navigationStatus in setOf(NavigationStatus.NAVIGATING, NavigationStatus.ARRIVED)
                val remaining = if (valid) state.remainingDistanceMeters?.let { "%.1f km restantes".format(java.util.Locale.ROOT, it / 1000.0) } ?: "Restante --" else "Restante --"
                val eta = if (valid) state.etaEpochMillis?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it)) } ?: "--:--" else "--:--"
                text(remaining, 9f, footer + 44f, 17f)
                text("ETA $eta", 285f, footer + 44f, 17f)
            }
            paint.apply { textSize = 12f; typeface = Typeface.DEFAULT; color = Color.WHITE }
            lines.forEachIndexed { index, credit -> canvas.drawText(credit, 8f, footer + (if (guidanceVisible) 67f else 46f) + index * 15f, paint) }
            val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)); out.toByteArray() }
            TftFrame(encodedBytes = bytes, timestampMs = System.currentTimeMillis())
        } finally { bitmap.recycle() }
    }
}
