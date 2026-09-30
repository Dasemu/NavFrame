package dev.navframe.core

import kotlin.math.*

/** Initial conservative thresholds, pending physical validation. Times use a monotonic clock. */
data class GuidanceConfig(
    val maxFixAgeMs: Long = 15_000,
    val maxAccuracyMeters: Float = 25f,
    val offRouteMeters: Double = 40.0,
    val offRouteDurationMs: Long = 5_000,
    val offRouteFixes: Int = 3,
    val arrivalRadiusMeters: Double = 25.0,
    val arrivalRemainingMeters: Double = 30.0,
    val arrivalDurationMs: Long = 2_000,
    val arrivalFixes: Int = 3,
) {
    init {
        require(maxFixAgeMs > 0 && maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(offRouteMeters.isFinite() && offRouteMeters > 0 && offRouteDurationMs >= 0 && offRouteFixes >= 2)
        require(arrivalRadiusMeters.isFinite() && arrivalRadiusMeters > 0 && arrivalRemainingMeters.isFinite() && arrivalRemainingMeters > 0)
        require(arrivalDurationMs >= 0 && arrivalFixes >= 2)
    }
}

/** Stateful projection/progress for one route. Create another tracker when replacing the route. */
class RouteGuidanceTracker(private val route: RouteResult, private val config: GuidanceConfig = GuidanceConfig()) {
    private val line = RouteLine(route)
    private var progress = 0.0
    private var acquired = false
    private var lastPoint: GeoPoint? = null
    private var lastNow: Long? = null
    private var lastTimestamp: Long? = null
    private val outside = SustainedFixes()
    private val arrival = SustainedFixes()
    private var arrived = false
    private var needsRecovery = false
    private var lastState: NavigationState? = null
    private fun remember(state: NavigationState): NavigationState { lastState = state; return state }

    fun update(fix: LocationFix, nowMs: Long, epochMs: Long, fixAgeMs: Long = 0): NavigationState {
        val base = NavigationState(position = fix.position, bearing = fix.bearing,
            speed = fix.speedMetersPerSecond.takeIf { it.isFinite() && it >= 0 } ?: 0f,
            route = route, accuracyMeters = fix.accuracyMeters)
        val usable = validPoint(fix.position) && fixAgeMs in 0..config.maxFixAgeMs &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..config.maxAccuracyMeters &&
            (lastNow == null || nowMs >= lastNow!!)
        if (!usable) {
            outside.reset(); arrival.reset()
            needsRecovery = lastPoint != null
            return remember(base.copy(speed = 0f, navigationStatus = NavigationStatus.GPS_LOST))
        }
        val distinct = lastTimestamp != fix.timestampMs
        if (!distinct) return lastState ?: base.copy(navigationStatus = NavigationStatus.GPS_LOST)
        if (arrived) return remember(base.copy(speed = 0f, remainingDistanceMeters = 0,
            etaEpochMillis = epochMs, navigationStatus = NavigationStatus.ARRIVED))
        if (needsRecovery || lastNow?.let { nowMs - it > config.maxFixAgeMs } == true) {
            outside.reset(); arrival.reset()
            needsRecovery = false
            acquired = false
            lastPoint = fix.position
            lastNow = nowMs
            lastTimestamp = fix.timestampMs
            return remember(base.copy(speed = 0f, navigationStatus = NavigationStatus.GPS_LOST))
        }
        val elapsed = lastNow?.let { (nowMs - it).coerceAtMost(config.maxFixAgeMs) / 1000.0 } ?: 0.0
        val movementAllowance = 60.0 + base.speed * elapsed * 3 + fix.accuracyMeters * 2
        val forwardAllowance = if (!acquired) line.total else movementAllowance
        val jumped = lastPoint?.let { earthDistance(it, fix.position) > movementAllowance } ?: false
        // Remember an isolated jump so a subsequently stable, genuine off-route position can recover.
        lastPoint = fix.position
        lastNow = nowMs
        lastTimestamp = fix.timestampMs
        if (jumped) {
            outside.reset(); arrival.reset()
            return remember(base.copy(speed = 0f, navigationStatus = NavigationStatus.GPS_LOST))
        }
        val projection = line.project(fix.position, progress, forwardAllowance)
        val outsideThreshold = max(config.offRouteMeters, fix.accuracyMeters * 2.0)
        val offRoute = outside.observe(projection.distance > outsideThreshold, distinct, nowMs,
            config.offRouteFixes, config.offRouteDurationMs)
        // Freeze progress during inaccurate projection, so driving parallel to the road does not advance turns.
        if (projection.distance <= outsideThreshold && projection.localDistance <= outsideThreshold && projection.local != null) {
            progress = max(progress, projection.local)
            acquired = true
        }
        if (offRoute) {
            arrival.reset()
            return remember(base.copy(navigationStatus = NavigationStatus.OFF_ROUTE))
        }
        if (projection.distance > outsideThreshold) {
            arrival.reset()
            return remember(base.copy(navigationStatus = NavigationStatus.NAVIGATING))
        }
        if (projection.local == null || projection.localDistance > outsideThreshold) {
            arrival.reset()
            return remember(base.copy(speed = 0f, navigationStatus = NavigationStatus.GPS_LOST))
        }
        val remaining = line.total - progress
        val close = projection.distance <= outsideThreshold && remaining <= config.arrivalRemainingMeters &&
            earthDistance(fix.position, route.geometry.last()) <= config.arrivalRadiusMeters
        arrived = arrival.observe(close, distinct, nowMs, config.arrivalFixes, config.arrivalDurationMs)
        if (arrived) return remember(base.copy(speed = 0f, remainingDistanceMeters = 0,
            etaEpochMillis = epochMs, navigationStatus = NavigationStatus.ARRIVED))
        return remember(line.guidedState(base, progress, epochMs))
    }
}

/** HTTP remains outside core. A request must be marked before launching IO and finished on all exits. */
class ReroutePolicy(private val cooldownMs: Long = 30_000) {
    init { require(cooldownMs >= 1_000) }
    private var inFlight = false
    private var nextAllowedMs = Long.MIN_VALUE
    fun shouldRequest(state: NavigationState, nowMs: Long): Boolean =
        state.navigationStatus == NavigationStatus.OFF_ROUTE && !inFlight && nowMs >= nextAllowedMs
    fun markRequested(nowMs: Long) {
        check(!inFlight && nowMs >= nextAllowedMs) { "Reroute request already active or cooling down" }
        inFlight = true
        nextAllowedMs = saturatedAdd(nowMs, cooldownMs)
    }
    fun markFinished(nowMs: Long, retryAfterMs: Long = 0) {
        require(retryAfterMs >= 0)
        inFlight = false
        nextAllowedMs = max(nextAllowedMs, saturatedAdd(nowMs, max(cooldownMs, retryAfterMs)))
    }
    fun reset() { inFlight = false; nextAllowedMs = Long.MIN_VALUE }
}

/** Deterministic simulation along a calculated route; caller must visibly label it DEMO. */
class RouteDemo(private val route: RouteResult, private val speedMetersPerSecond: Float = 12f) {
    init { require(speedMetersPerSecond.isFinite() && speedMetersPerSecond > 0) }
    private val line = RouteLine(route)
    fun stateAt(elapsedMs: Long, epochMs: Long): NavigationState {
        val progress = (elapsedMs.coerceAtLeast(0) / 1000.0 * speedMetersPerSecond).coerceAtMost(line.total)
        val (position, bearing) = line.pointAt(progress)
        val base = NavigationState(position = position, bearing = bearing, speed = speedMetersPerSecond,
            route = route, accuracyMeters = 3f)
        if (progress >= line.total) return base.copy(speed = 0f, remainingDistanceMeters = 0,
            etaEpochMillis = epochMs, navigationStatus = NavigationStatus.ARRIVED)
        val state = line.guidedState(base, progress, epochMs)
        return state.copy(etaEpochMillis = saturatedAdd(epochMs, ((line.total - progress) / speedMetersPerSecond * 1000).roundToLong()))
    }
}

enum class ManeuverDirection { LEFT, RIGHT, STRAIGHT, UTURN, ROUNDABOUT, ARRIVAL, UNKNOWN }
/** Valhalla's stable maneuver type IDs, independent of instruction language. */
fun maneuverDirection(type: Int?): ManeuverDirection = when (type) {
    2, 9, 10, 11, 18, 20, 23, 37 -> ManeuverDirection.RIGHT
    3, 14, 15, 16, 19, 21, 24, 38 -> ManeuverDirection.LEFT
    12, 13 -> ManeuverDirection.UTURN
    26, 27 -> ManeuverDirection.ROUNDABOUT
    4, 5, 6 -> ManeuverDirection.ARRIVAL
    1, 7, 8, 17, 22, 25 -> ManeuverDirection.STRAIGHT
    else -> ManeuverDirection.UNKNOWN
}

private class SustainedFixes {
    private var since: Long? = null
    private var count = 0
    private var confirmed = false
    fun observe(condition: Boolean, distinct: Boolean, now: Long, needed: Int, duration: Long): Boolean {
        if (!condition) { reset(); return false }
        if (distinct) {
            if (since == null) since = now
            count = min(count + 1, needed)
            confirmed = count >= needed && since?.let { now - it >= duration } == true
        }
        return confirmed
    }
    fun reset() { since = null; count = 0; confirmed = false }
}

private class RouteLine(private val route: RouteResult) {
    val cumulative: DoubleArray
    val total: Double
    private val indexed: List<Pair<Maneuver, Double>>
    init {
        require(route.geometry.size >= 2 && route.geometry.all(::validPoint)) { "Invalid guidance geometry" }
        cumulative = DoubleArray(route.geometry.size)
        for (i in 1 until route.geometry.size) cumulative[i] = cumulative[i - 1] + earthDistance(route.geometry[i - 1], route.geometry[i])
        total = cumulative.last()
        require(total.isFinite() && total > 0) { "Empty guidance geometry" }
        require(route.durationSeconds >= 0 && route.distanceMeters >= 0)
        indexed = route.maneuvers.mapNotNull { maneuver ->
            val begin = maneuver.beginShapeIndex ?: return@mapNotNull null
            val end = maneuver.endShapeIndex ?: return@mapNotNull null
            require(begin in route.geometry.indices && end in begin until route.geometry.size) { "Invalid guidance maneuver index" }
            if (maneuver.type != null && maneuver.type in 1..3) null else maneuver to cumulative[begin]
        }.sortedBy { it.second }
    }
    data class Projection(val distance: Double, val local: Double?, val localDistance: Double)
    fun project(point: GeoPoint, previous: Double, forwardAllowance: Double): Projection {
        var nearestDistance = Double.POSITIVE_INFINITY
        var localDistance = Double.POSITIVE_INFINITY
        var localProgress: Double? = null
        for (i in 1 until route.geometry.size) {
            val a = route.geometry[i - 1]; val b = route.geometry[i]
            val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(0.000001)
            val dx = wrappedLongitude(b.longitude - a.longitude) * scale
            val dy = b.latitude - a.latitude
            val px = wrappedLongitude(point.longitude - a.longitude) * scale
            val py = point.latitude - a.latitude
            val norm = dx * dx + dy * dy
            val fraction = if (norm == 0.0) 0.0 else ((px * dx + py * dy) / norm).coerceIn(0.0, 1.0)
            val projected = GeoPoint(a.latitude + dy * fraction, wrappedLongitude(a.longitude + wrappedLongitude(b.longitude - a.longitude) * fraction))
            val distance = earthDistance(point, projected)
            val along = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * fraction
            nearestDistance = min(nearestDistance, distance)
            if (along >= previous - 20 && along <= previous + forwardAllowance && distance < localDistance - 0.01) {
                localDistance = distance; localProgress = along
            }
        }
        return Projection(nearestDistance, localProgress, localDistance)
    }
    fun guidedState(base: NavigationState, progress: Double, epochMs: Long): NavigationState {
        val next = indexed.firstOrNull { (_, at) -> at + 5 >= progress }
        val remaining = (total - progress).coerceAtLeast(0.0)
        val etaMs = (route.durationSeconds * 1000.0 * remaining / total).roundToLong()
        return base.copy(nextManeuver = next?.first,
            distanceToNextManeuverMeters = next?.let { (it.second - progress).coerceAtLeast(0.0).roundToInt() },
            remainingDistanceMeters = remaining.roundToInt(), etaEpochMillis = saturatedAdd(epochMs, etaMs),
            navigationStatus = NavigationStatus.NAVIGATING)
    }
    fun pointAt(progress: Double): Pair<GeoPoint, Float> {
        val end = (1 until cumulative.size).firstOrNull { cumulative[it] >= progress } ?: cumulative.lastIndex
        val a = route.geometry[end - 1]; val b = route.geometry[end]
        val segment = cumulative[end] - cumulative[end - 1]
        val fraction = if (segment <= 0) 0.0 else ((progress - cumulative[end - 1]) / segment).coerceIn(0.0, 1.0)
        val position = when {
            fraction <= 0.0 -> a
            fraction >= 1.0 -> b
            else -> GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction,
                wrappedLongitude(a.longitude + wrappedLongitude(b.longitude - a.longitude) * fraction))
        }
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val lon = Math.toRadians(wrappedLongitude(b.longitude - a.longitude))
        val bearing = ((Math.toDegrees(atan2(sin(lon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon))) + 360) % 360).toFloat()
        return position to bearing
    }
}
private fun validPoint(point: GeoPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
    point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
private fun wrappedLongitude(value: Double): Double = ((value + 180) % 360 + 360) % 360 - 180
private fun earthDistance(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dlat = lat2 - lat1; val dlon = Math.toRadians(wrappedLongitude(b.longitude - a.longitude))
    val square = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
    return 6_371_000.0 * 2 * asin(sqrt(square.coerceIn(0.0, 1.0)))
}
private fun saturatedAdd(a: Long, nonnegative: Long): Long = if (a > Long.MAX_VALUE - nonnegative) Long.MAX_VALUE else a + nonnegative
