package dev.navframe.core

/** Parameters for a bearing-up, rider-following TFT renderer, independent of any map SDK. */
data class TftCameraParameters(
    val bearingDegrees: Float,
    val zoom: Double,
    val riderAnchorY: Float = 0.72f,
)

class TftCameraPolicy {
    fun parameters(state: NavigationState): TftCameraParameters {
        val kmh = state.speed.coerceAtLeast(0f) * 3.6f
        val approachingTurn = state.nextManeuver != null &&
            state.distanceToNextManeuverMeters?.let { it in 0..150 } == true
        val zoom = when {
            approachingTurn -> 17.5
            kmh < 30 -> 17.0
            kmh <= 70 -> 16.0
            else -> 15.0
        }
        return TftCameraParameters(normalizeBearing(state.bearing), zoom)
    }
}
