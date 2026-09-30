package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class DemoNavigationTest {
    @Test fun demoIsDeterministicAndAdvancesMotionAndManeuver() {
        val first = DemoNavigation.stateAt(0)
        assertEquals(DemoNavigation.stateAt(5_000), DemoNavigation.stateAt(5_000))
        val later = DemoNavigation.stateAt(5_000)
        assertNotEquals(first.position, later.position)
        assertNotEquals(first.bearing, later.bearing)
        assertTrue(later.distanceToNextManeuverMeters!! < first.distanceToNextManeuverMeters!!)
        assertTrue(later.remainingDistanceMeters!! < first.remainingDistanceMeters!!)
        assertNotEquals(first.nextManeuver?.instruction, DemoNavigation.stateAt(30_000).nextManeuver?.instruction)
        assertTrue(later.nextManeuver!!.instruction.startsWith("DEMO"))
        assertNull(later.route) // Synthetic road is not represented as a calculated route.
    }

    @Test fun demoCountdownRestartsCleanlyAndKeepsHeadingInRange() {
        assertEquals(350, DemoNavigation.stateAt(30_000).distanceToNextManeuverMeters)
        assertTrue(DemoNavigation.stateAt(29_999).distanceToNextManeuverMeters!! in 0..1)
        for (elapsed in 0L..300_000L step 500L) {
            val state = DemoNavigation.stateAt(elapsed)
            assertTrue(state.bearing >= 0 && state.bearing < 360)
            assertTrue(state.speed > 0)
        }
    }

    @Test fun cameraWidensWithSpeedAndZoomsForUpcomingManeuver() {
        val policy = TftCameraPolicy()
        val slow = policy.parameters(NavigationState(speed = 5f, bearing = -1f))
        val fast = policy.parameters(NavigationState(speed = 25f, bearing = 361f))
        assertTrue(fast.zoom < slow.zoom)
        assertEquals(359f, slow.bearingDegrees)
        assertEquals(1f, fast.bearingDegrees)
        assertTrue(fast.riderAnchorY in 0.66f..0.8f)
        val approaching = policy.parameters(NavigationState(speed = 25f, nextManeuver = Maneuver("derecha", 100), distanceToNextManeuverMeters = 100))
        assertTrue(approaching.zoom > slow.zoom)
    }
}
