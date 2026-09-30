package dev.navframe.core

import kotlin.math.*

/** Community mapped fixed camera; raw OSM direction is evidence, not a certified traffic direction. */
data class SpeedCamera(val id: String, val position: GeoPoint, val direction: String? = null)
data class SpeedCameraAlert(val cameraId: String, val distanceMeters: Int) {
    val label: String get() = "Posible radar fijo cerca · ≈$distanceMeters m"
}

/** Conservative proximity advisory. Distance is straight-line, never a route or lane guarantee. */
class SpeedCameraAlertEngine {
    private val previous = mutableMapOf<String, Double>()
    private val notified = mutableMapOf<String, Long>()
    fun reset() { previous.clear(); notified.clear() }
    fun update(fix: LocationFix, cameras: List<SpeedCamera>, nowMs: Long, fixAgeMs: Long = 0, route: RouteResult? = null): SpeedCameraAlert? {
        if (route == null || route.geometry.size < 2 || fixAgeMs !in 0..5_000 || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..25f ||
            !fix.speedMetersPerSecond.isFinite() || fix.speedMetersPerSecond < 3f || !fix.bearing.isFinite() ||
            !valid(fix.position)) { previous.clear(); return null }
        notified.entries.removeAll { nowMs - it.value > 600_000 }
        val candidates = cameras.distinctBy { it.id }.filter { valid(it.position) && onRoute(it.position, route.geometry) }.mapNotNull { camera ->
            val distance = cameraDistance(fix.position, camera.position)
            val before = previous.put(camera.id, distance)
            val heading = cameraBearing(fix.position, camera.position)
            val delta = abs(((heading - fix.bearing + 540) % 360) - 180)
            // Require a second fix establishing approach; bearing alone can be stale or noisy.
            if (distance !in 35.0..500.0 || before == null || before - distance < 3 || delta > 25 ||
                distance * sin(Math.toRadians(delta)) > 35 || notified.containsKey(camera.id)) null
            else camera to distance
        }
        val visibleIds = cameras.map { it.id }.toSet()
        previous.keys.retainAll(visibleIds)
        val closest = candidates.minByOrNull { it.second } ?: return null
        notified[closest.first.id] = nowMs
        return SpeedCameraAlert(closest.first.id, (closest.second / 25).roundToInt() * 25)
    }
    private fun onRoute(camera: GeoPoint, geometry: List<GeoPoint>): Boolean = geometry.zipWithNext().any { (a, b) ->
        val scale = cos(Math.toRadians(camera.latitude))
        val ax = (a.longitude-camera.longitude)*scale*111_195; val ay = (a.latitude-camera.latitude)*111_195
        val bx = (b.longitude-camera.longitude)*scale*111_195; val by = (b.latitude-camera.latitude)*111_195
        val dx = bx-ax; val dy = by-ay
        val length = dx*dx+dy*dy
        val t = if (length == 0.0) 0.0 else ((-ax*dx-ay*dy)/length).coerceIn(0.0,1.0)
        hypot(ax+t*dx, ay+t*dy) <= 12
    }
    private fun valid(p: GeoPoint) = p.latitude.isFinite() && p.longitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0
}
fun cameraDistance(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
private fun cameraBearing(a: GeoPoint, b: GeoPoint): Double {
    val lon = Math.toRadians(b.longitude - a.longitude)
    val latA = Math.toRadians(a.latitude); val latB = Math.toRadians(b.latitude)
    return (Math.toDegrees(atan2(sin(lon) * cos(latB), cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(lon))) + 360) % 360
}
