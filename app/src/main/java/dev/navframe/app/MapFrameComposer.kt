package dev.navframe.app

import android.graphics.*
import dev.navframe.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Native JPEG overlay, independent of a map SDK so its layout can be verified without GPU access. */
class MapFrameComposer {
    suspend fun compose(map: Bitmap?, marker: PointF?, state: NavigationState, mode: NavigationMode, attribution: String, unavailable: Boolean = false, failureCode: String? = null): TftFrame = withContext(Dispatchers.Default) {
        require(map == null || (map.width == WIDTH && map.height == HEIGHT)) { "Bitmap dimensions invalid" }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(13, 19, 25))
            map?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
                paint.apply { this.color = color; textSize = size; style = Paint.Style.FILL; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
                canvas.drawText(value, x, y, paint)
            }
            fun fitted(value: String, size: Float, width: Float): String {
                paint.textSize = size
                if (paint.measureText(value) <= width) return value
                var result = value
                while (result.isNotEmpty() && paint.measureText("$result…") > width) result = result.dropLast(1)
                return if (result.isEmpty()) "" else "$result…"
            }

            val lost = state.navigationStatus == NavigationStatus.GPS_LOST
            val accent = Color.rgb(80, 227, 194)
            val guidanceVisible = state.route != null && state.navigationStatus != NavigationStatus.ROUTE_PREVIEW
            val preview = state.route != null && !guidanceVisible
            val topHeight = when { guidanceVisible -> GUIDANCE_HEIGHT; preview -> PREVIEW_HEIGHT; else -> STATUS_HEIGHT }

            // Keep navigation cues in one shallow band; let the map occupy the whole center.
            paint.color = Color.rgb(7, 12, 17)
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), topHeight.toFloat(), paint)
            val modeText = if (mode == NavigationMode.MAP_DEMO) "DEMO" else "GPS"
            val routeText = when {
                lost -> "SIN SEÑAL GPS"
                unavailable || map == null -> "MAPA NO DISPONIBLE"
                state.route != null -> "RUTA"
                else -> "MAPA"
            }
            text("$modeText · $routeText", 8f, 13f, 10f, if (lost || unavailable) Color.YELLOW else accent)

            if (guidanceVisible) {
                val warning = when {
                    unavailable || map == null -> "GUIADO EN ESPERA · SIN MAPA"
                    lost -> "SIN GPS · esperando posición precisa"
                    state.navigationStatus == NavigationStatus.REROUTING -> "RECALCULANDO · espera nueva ruta"
                    state.navigationStatus == NavigationStatus.OFF_ROUTE -> "FUERA DE RUTA · revisa el recorrido"
                    state.navigationStatus == NavigationStatus.ARRIVED -> "HAS LLEGADO AL DESTINO"
                    else -> null
                }
                if (warning != null) {
                    text(fitted(warning, 16f, 460f), 10f, 39f, 16f, if (state.navigationStatus == NavigationStatus.ARRIVED) accent else Color.YELLOW)
                } else {
                    val maneuver = state.nextManeuver
                    val direction = maneuverDirection(maneuver?.type).name
                    canvas.save()
                    canvas.translate(27f, 33f)
                    canvas.rotate(if (direction.contains("LEFT")) -90f else if (direction.contains("RIGHT")) 90f else 0f)
                    paint.apply { style = Paint.Style.STROKE; color = accent; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                    val icon = Path().apply { moveTo(0f, 14f); lineTo(0f, -13f); moveTo(-10f, -3f); lineTo(0f, -13f); lineTo(10f, -3f) }
                    if (direction == "ARRIVAL") {
                        canvas.drawLine(-8f, 15f, -8f, -15f, paint)
                        canvas.drawPath(Path().apply { moveTo(-8f, -15f); lineTo(13f, -9f); lineTo(-8f, -2f) }, paint)
                    } else if (direction.contains("ROUNDABOUT")) canvas.drawCircle(0f, 0f, 13f, paint)
                    else if (direction.contains("UTURN") || direction.contains("U_TURN")) {
                        canvas.drawArc(-12f, -14f, 12f, 11f, 180f, 180f, false, paint)
                        canvas.drawLine(-12f, -2f, -12f, 15f, paint); canvas.drawLine(12f, -2f, 12f, 15f, paint)
                    } else canvas.drawPath(icon, paint)
                    canvas.restore()
                    paint.strokeCap = Paint.Cap.BUTT
                    text(fitted(maneuver?.instruction ?: "Continúa por la ruta", 15f, 392f), 48f, 30f, 15f)
                    val next = state.distanceToNextManeuverMeters?.let { if (it >= 1000) "%.1f km".format(java.util.Locale.ROOT, it / 1000.0) else "$it m" } ?: "-- m"
                    text(next, 48f, 49f, 19f, accent)
                }
            } else if (preview) {
                val route = requireNotNull(state.route)
                val summary = "PREVIA · %.1f km · %d min".format(java.util.Locale.ROOT, route.distanceMeters / 1000.0, (route.durationSeconds + 59) / 60)
                text(fitted(summary, 13f, 460f), 8f, 31f, 13f)
            }

            if (map != null && marker != null) {
                val x = marker.x; val y = marker.y
                val arrow = Path().apply { moveTo(x, y - 16); lineTo(x + 12, y + 13); lineTo(x, y + 7); lineTo(x - 12, y + 13); close() }
                paint.apply { color = if (lost) Color.GRAY else Color.WHITE; style = Paint.Style.FILL }
                canvas.drawPath(arrow, paint)
                paint.apply { color = if (lost) Color.YELLOW else accent; style = Paint.Style.STROKE; strokeWidth = 2.5f }
                canvas.drawPath(arrow, paint)
                paint.style = Paint.Style.FILL
            }

            if (unavailable || map == null) {
                paint.color = Color.rgb(7, 12, 17)
                canvas.drawRect(36f, 78f, 444f, 132f, paint)
                text(if (unavailable) "MAPA NO DISPONIBLE" else "ESPERANDO GPS", 50f, 101f, 21f)
                text(if (unavailable) "TFT: ${failureCode ?: "SDK"} · ${if (lost) "SIN GPS" else "Comprueba fuente"}" else "No se usa una posición ficticia", 50f, 121f, 13f)
            }

            // Credits stay visible, but consume only their actual wrapped lines.
            val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = CREDIT_SIZE; typeface = Typeface.DEFAULT }
            val credits = wrap(attribution, creditPaint, WIDTH - 16f)
            val footerDataHeight = if (guidanceVisible) FOOTER_DATA_HEIGHT else 0
            val footerHeight = footerDataHeight + credits.size * CREDIT_LINE_HEIGHT
            val footer = (HEIGHT - footerHeight).coerceAtLeast(topHeight + MIN_MAP_HEIGHT)
            paint.apply { color = Color.rgb(7, 12, 17); style = Paint.Style.FILL }
            canvas.drawRect(0f, footer.toFloat(), WIDTH.toFloat(), HEIGHT.toFloat(), paint)

            if (guidanceVisible) {
                val valid = !lost && !unavailable && map != null && state.navigationStatus in setOf(NavigationStatus.NAVIGATING, NavigationStatus.ARRIVED)
                val remaining = if (valid) state.remainingDistanceMeters?.let { "%.1f km restantes".format(java.util.Locale.ROOT, it / 1000.0) } ?: "Restante --" else "Restante --"
                val eta = if (valid) state.etaEpochMillis?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it)) } ?: "--:--" else "--:--"
                // Speed and heading already occupy the Yamaha instrument panel; keep only trip data here.
                text(fitted(remaining, 12f, 340f), 8f, footer + 14f, 12f)
                text("ETA $eta", 397f, footer + 14f, 12f)
            }
            credits.forEachIndexed { index, credit ->
                canvas.drawText(credit, 8f, footer + footerDataHeight + (index + 1) * CREDIT_LINE_HEIGHT.toFloat() - 2f, creditPaint)
            }
            val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)); out.toByteArray() }
            TftFrame(encodedBytes = bytes, timestampMs = System.currentTimeMillis())
        } finally { bitmap.recycle() }
    }

    private fun wrap(value: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (character in value.trim().replace(Regex("\\s+"), " ")) {
            val next = line + character
            if (line.isNotEmpty() && paint.measureText(next) > maxWidth) { lines += line.trim(); line = character.toString() }
            else line = next
        }
        if (line.isNotEmpty()) lines += line
        return lines.ifEmpty { listOf("") }
    }

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 240
        const val GUIDANCE_HEIGHT = 50
        const val PREVIEW_HEIGHT = 36
        const val STATUS_HEIGHT = 20
        const val MIN_MAP_HEIGHT = 88
        private const val FOOTER_DATA_HEIGHT = 18
        private const val CREDIT_SIZE = 8.5f
        private const val CREDIT_LINE_HEIGHT = 10
    }
}
