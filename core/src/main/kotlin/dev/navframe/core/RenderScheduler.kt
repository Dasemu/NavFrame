package dev.navframe.core

import kotlin.math.*

/** Visual redraw policy. TFT keepalive retransmits a cached JPEG independently of this class. */
data class RenderScheduleConfig(
    val stoppedMaxFps: Double = 1.0,
    val movingMaxFps: Double = 5.0,
    val turningMaxFps: Double = 10.0,
    val movingSpeedMetersPerSecond: Float = 0.7f,
    val positionDeltaMeters: Double = 2.0,
    val bearingDeltaDegrees: Float = 3f,
    val turningDeltaDegrees: Float = 15f,
    val speedDeltaMetersPerSecond: Float = 0.5f,
    val distanceLabelStepMeters: Int = 10,
) {
    init {
        require(listOf(stoppedMaxFps, movingMaxFps, turningMaxFps).all { it.isFinite() && it > 0 })
        require(movingSpeedMetersPerSecond.isFinite() && movingSpeedMetersPerSecond >= 0)
        require(positionDeltaMeters.isFinite() && positionDeltaMeters > 0)
        require(bearingDeltaDegrees.isFinite() && bearingDeltaDegrees > 0)
        require(turningDeltaDegrees.isFinite() && turningDeltaDegrees >= bearingDeltaDegrees)
        require(speedDeltaMetersPerSecond.isFinite() && speedDeltaMetersPerSecond > 0)
        require(distanceLabelStepMeters > 0)
    }
}

/** Single-owner policy; callers use elapsed monotonic milliseconds and offer their freshest state. */
class RenderScheduler(private val config: RenderScheduleConfig = RenderScheduleConfig()) {
    private var pending: NavigationState? = null
    private var rendered: NavigationState? = null
    private var renderedAtMs: Long? = null

    fun offer(state: NavigationState) { pending = state }

    /** Retains only the freshest offered state during a throttle interval; no queue of old fixes. */
    fun takeIfDue(nowMs: Long): NavigationState? {
        require(nowMs >= 0)
        val latest = pending ?: return null
        val previous = rendered
        val previousTime = renderedAtMs
        val clockReset = previousTime != null && nowMs < previousTime
        if (previous != null && !clockReset) {
            if (!changed(previous, latest)) return null
            val moving = max(previous.speed, latest.speed) >= config.movingSpeedMetersPerSecond
            val turning = moving && bearingDistance(previous.bearing, latest.bearing) >= config.turningDeltaDegrees
            val fps = when { turning -> config.turningMaxFps; moving -> config.movingMaxFps; else -> config.stoppedMaxFps }
            if (previousTime != null && nowMs - previousTime < ceil(1000.0 / fps).toLong()) return null
        }
        return latest
    }

    /** Commit only after successful rendering/encoding; a failed render must not advance this baseline. */
    fun markRendered(state: NavigationState, nowMs: Long) {
        require(nowMs >= 0)
        rendered = state
        renderedAtMs = nowMs
        if (pending == state) pending = null
    }

    /** Query for a loop that supplies current state each tick. Call markRendered after success. */
    fun shouldRender(state: NavigationState, nowMs: Long): Boolean {
        offer(state)
        return takeIfDue(nowMs) != null
    }

    /** Call on renderer/source replacement, so its first frame renders immediately. */
    fun reset() { pending = null; rendered = null; renderedAtMs = null }

    private fun changed(old: NavigationState, fresh: NavigationState): Boolean =
        old.navigationStatus != fresh.navigationStatus || old.route != fresh.route ||
        old.bearing.isFinite() != fresh.bearing.isFinite() || old.speed.isFinite() != fresh.speed.isFinite() ||
        accuracyChanged(old.accuracyMeters, fresh.accuracyMeters) ||
        old.nextManeuver?.instruction != fresh.nextManeuver?.instruction ||
        label(old.distanceToNextManeuverMeters) != label(fresh.distanceToNextManeuverMeters) ||
        label(old.remainingDistanceMeters) != label(fresh.remainingDistanceMeters) ||
        old.etaEpochMillis?.div(60_000) != fresh.etaEpochMillis?.div(60_000) ||
        abs(old.speed - fresh.speed) >= config.speedDeltaMetersPerSecond ||
        distanceMeters(old.position, fresh.position) >= config.positionDeltaMeters ||
        (max(old.speed, fresh.speed) >= config.movingSpeedMetersPerSecond &&
            bearingDistance(old.bearing, fresh.bearing) >= config.bearingDeltaDegrees)

    private fun label(distance: Int?): Int? = distance?.div(config.distanceLabelStepMeters)

    private fun accuracyChanged(old: Float?, fresh: Float?): Boolean {
        val first = old?.takeIf { it.isFinite() && it >= 0 }
        val second = fresh?.takeIf { it.isFinite() && it >= 0 }
        return if (first == null || second == null) first != second else abs(first - second) >= 2f
    }
}

/** Heading is circular: 359° → 1° is a two-degree change. */
internal fun bearingDistance(first: Float, second: Float): Float {
    val delta = abs(normalizeBearing(first) - normalizeBearing(second))
    return min(delta, 360f - delta)
}

fun normalizeBearing(bearing: Float): Float =
    if (!bearing.isFinite()) 0f else ((bearing % 360f) + 360f) % 360f

private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(second.longitude - first.longitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
