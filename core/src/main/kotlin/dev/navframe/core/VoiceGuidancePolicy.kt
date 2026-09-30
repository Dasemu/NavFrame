package dev.navframe.core

/** Spoken checkpoints are consumed once per maneuver, including noisy distance oscillations. */
class VoiceGuidancePolicy(private val language: GuidanceLanguage = GuidanceLanguage.SPANISH) {
    private var route: RouteResult? = null
    private val checkpoints = mutableMapOf<Maneuver, Int>()
    private val statuses = mutableSetOf<NavigationStatus>()
    fun reset() { route = null; checkpoints.clear(); statuses.clear() }

    fun announcement(state: NavigationState): String? {
        val current = state.route ?: run { reset(); return null }
        if (route !== current) { reset(); route = current }
        when (state.navigationStatus) {
            NavigationStatus.ARRIVED -> return once(state.navigationStatus, language.status(state.navigationStatus))
            NavigationStatus.REROUTING -> return once(state.navigationStatus, language.status(state.navigationStatus))
            NavigationStatus.OFF_ROUTE -> return once(state.navigationStatus, language.status(state.navigationStatus))
            NavigationStatus.GPS_LOST -> return once(state.navigationStatus, language.status(state.navigationStatus))
            NavigationStatus.NAVIGATING -> Unit
            else -> return null
        }
        val maneuver = state.nextManeuver ?: return null
        val distance = state.distanceToNextManeuverMeters?.takeIf { it >= 0 } ?: return null
        val stage = when { distance <= 30 -> 3; distance <= 100 -> 2; distance <= 300 -> 1; else -> return null }
        if ((checkpoints[maneuver] ?: 0) >= stage) return null
        checkpoints[maneuver] = stage
        val instruction = maneuver.instruction.trim().takeIf { it.isNotEmpty() } ?: return null
        return language.maneuver(instruction, distance, stage == 3)
    }

    private fun once(status: NavigationStatus, text: String): String? = if (statuses.add(status)) text else null
}
