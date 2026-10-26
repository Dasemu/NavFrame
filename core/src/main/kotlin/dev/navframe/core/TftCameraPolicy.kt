package dev.navframe.core

/** Parameters for a bearing-up, rider-following TFT renderer, independent of any map SDK. */
data class TftCameraParameters(
    val bearingDegrees: Float,
    val zoom: Double,
    /** Rider position as a fraction of the frame height, measured from the top. */
    val riderAnchorY: Float = 0.78f,
    /** Perspective tilt; the road ahead stays visible further away than in a flat view. */
    val pitchDegrees: Double = 0.0,
)

/**
 * Wide enough to read the next bends before reaching them. [zoomOffset] is the rider's own
 * adjustment (handlebar or phone controls), added to the speed-based level.
 */
class TftCameraPolicy(private val zoomOffset: Double = 0.0) {
    fun parameters(state: NavigationState): TftCameraParameters {
        val kmh = state.speed.coerceAtLeast(0f) * 3.6f
        val approachingTurn = state.nextManeuver != null &&
            state.distanceToNextManeuverMeters?.let { it in 0..200 } == true
        val base = when {
            approachingTurn -> 15.5
            kmh < 30 -> 15.5
            kmh <= 70 -> 15.0
            else -> 14.5
        }
        val zoom = (base + zoomOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)).coerceIn(12.0, 18.0)
        return TftCameraParameters(normalizeBearing(state.bearing), zoom, pitchDegrees = PITCH_DEGREES)
    }
    companion object {
        const val PITCH_DEGREES = 45.0
        const val ZOOM_STEP = 0.5
        const val MIN_OFFSET = -2.0
        const val MAX_OFFSET = 2.0
        fun stepOffset(current: Double, steps: Int): Double = (current + steps * ZOOM_STEP).coerceIn(MIN_OFFSET, MAX_OFFSET)
    }
}
