package dev.navframe.core

import kotlin.math.sin

/** Deterministic synthetic demonstration. These are invented positions and maneuvers, never a GPS fix. */
object DemoNavigation {
    fun stateAt(elapsedMs: Long, startEpochMillis: Long = 1_800_000_000_000L): NavigationState {
        require(elapsedMs >= 0)
        val seconds = elapsedMs / 1000.0
        val progressSeconds = (elapsedMs % 300_000) / 1000.0
        val turnCycle = (elapsedMs / 30_000) % 2
        val turnDistance = (350 - (elapsedMs % 30_000) * 350 / 30_000).toInt()
        val speed = (8 + 2 * sin(seconds / 12)).toFloat()
        return NavigationState(
            position = GeoPoint(43.36 + progressSeconds * 0.00004, -5.85 + 0.0002 * sin(seconds / 10)),
            bearing = normalizeBearing((35 + 30 * sin(seconds / 8)).toFloat()),
            speed = speed,
            nextManeuver = Maneuver(if (turnCycle == 0L) "DEMO · giro derecha" else "DEMO · giro izquierda", turnDistance),
            distanceToNextManeuverMeters = turnDistance,
            remainingDistanceMeters = (23_000 - progressSeconds * 8).toInt(),
            etaEpochMillis = startEpochMillis + 18 * 60_000,
            navigationStatus = NavigationStatus.NAVIGATING,
        )
    }
}
