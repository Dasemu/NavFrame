package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class VoiceGuidancePolicyTest {
    private val turn = Maneuver("Gira a la izquierda", 100, 15, 1, 2)
    private val route = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)), listOf(turn), 1000, 100)
    private fun state(distance: Int = 250, status: NavigationStatus = NavigationStatus.NAVIGATING) =
        NavigationState(route = route, nextManeuver = turn, distanceToNextManeuverMeters = distance, navigationStatus = status)

    @Test fun `distance checkpoints do not repeat or replay skipped stages`() {
        val policy = VoiceGuidancePolicy()
        assertNotNull(policy.announcement(state()))
        repeat(100) { assertNull(policy.announcement(state(260))) }
        assertNotNull(policy.announcement(state(25)))
        assertNull(policy.announcement(state(90)))
        assertNull(policy.announcement(state(25)))
    }
    @Test fun `preview and no route never speak and replacement resets checkpoint`() {
        val policy = VoiceGuidancePolicy()
        assertNull(policy.announcement(state(status = NavigationStatus.ROUTE_PREVIEW)))
        assertNotNull(policy.announcement(state()))
        assertNotNull(policy.announcement(state().copy(route = route.copy())))
        assertNull(policy.announcement(NavigationState()))
        assertNotNull(policy.announcement(state()))
    }
    @Test fun `arrival rerouting and poor GPS speak once per route and suppress turns`() {
        val policy = VoiceGuidancePolicy()
        for (status in listOf(NavigationStatus.REROUTING, NavigationStatus.GPS_LOST, NavigationStatus.ARRIVED)) {
            assertNotNull(policy.announcement(state(status = status)))
            repeat(10) { assertNull(policy.announcement(state(status = status))) }
        }
    }
}
