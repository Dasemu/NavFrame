package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceTest {
    // Equatorial segments are ~111.2m each; original route duration is 30 seconds.
    private val route = RouteResult(
        listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)),
        listOf(Maneuver("Start", 111, 1, 0, 1, 15), Maneuver("Left", 111, 15, 1, 2, 15), Maneuver("Arrive", 0, 4, 2, 2)),
        222, 30,
    )
    private fun fix(lat: Double = 0.0, lon: Double = 0.0, time: Long = 1, accuracy: Float = 5f, speed: Float = 12f) =
        LocationFix(GeoPoint(lat, lon), 90f, speed, accuracy, time)

    @Test fun `projection yields next turn countdown remaining and estimated ETA`() {
        val state = RouteGuidanceTracker(route).update(fix(lon = 0.0005), 0, 100_000)
        assertEquals(NavigationStatus.NAVIGATING, state.navigationStatus)
        assertEquals("Left", state.nextManeuver!!.instruction)
        assertEquals(56, state.distanceToNextManeuverMeters!!)
        assertEquals(167, state.remainingDistanceMeters!!)
        assertEquals(122_500L, state.etaEpochMillis!!)
    }

    @Test fun `jitter never moves countdown backwards and crossed turn advances with margin`() {
        val tracker = RouteGuidanceTracker(route)
        val a = tracker.update(fix(lon = 0.0008), 0, 0)
        val jitter = tracker.update(fix(lon = 0.00075, time = 2), 1_000, 1_000)
        assertEquals(a.remainingDistanceMeters, jitter.remainingDistanceMeters)
        assertEquals("Left", jitter.nextManeuver!!.instruction)
        val crossed = tracker.update(fix(lat = 0.0001, lon = 0.001, time = 3), 2_000, 2_000)
        assertEquals("Arrive", crossed.nextManeuver!!.instruction)
        assertEquals(100, crossed.distanceToNextManeuverMeters!!)
    }

    @Test fun `off route requires distinct fresh fixes time and resets after jitter recovery`() {
        val tracker = RouteGuidanceTracker(route)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(lat = -0.0005), 0, 0).navigationStatus)
        // Ticks on the same fix do not satisfy either count or sustained-duration confirmation.
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(lat = -0.0005), 8_000, 8_000).navigationStatus)
        tracker.update(fix(lat = -0.0005, time = 2), 9_000, 9_000)
        val outside = tracker.update(fix(lat = -0.0005, time = 3), 10_000, 10_000)
        assertEquals(NavigationStatus.OFF_ROUTE, outside.navigationStatus)
        assertNull(outside.nextManeuver)
        assertNull(outside.distanceToNextManeuverMeters)
        assertNull(outside.etaEpochMillis)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(time = 4), 11_000, 11_000).navigationStatus)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(lat = -0.0005, time = 5), 12_000, 12_000).navigationStatus)
    }

    @Test fun `a long gap breaks consecutive deviation evidence`() {
        val tracker = RouteGuidanceTracker(route)
        tracker.update(fix(lat = -0.0005), 0, 0)
        tracker.update(fix(lat = -0.0005, time = 2), 3_000, 0)
        val resumed = tracker.update(fix(lat = -0.0005, time = 3), 30_000, 0)
        assertEquals(NavigationStatus.GPS_LOST, resumed.navigationStatus)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(lat = -0.0005, time = 4), 31_000, 0).navigationStatus)
    }

    @Test fun `accuracy margin rejects jitter at forty five meters with twenty five meter accuracy`() {
        val tracker = RouteGuidanceTracker(route)
        for (i in 1..5) {
            val state = tracker.update(fix(lat = -0.0004, time = i.toLong(), accuracy = 25f), i * 3_000L, 0)
            assertEquals(NavigationStatus.NAVIGATING, state.navigationStatus)
        }
    }

    @Test fun `poor stale invalid fixes cannot confirm deviation or arrival`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 26f, -1f).forEach { accuracy ->
            val state = RouteGuidanceTracker(route).update(fix(accuracy = accuracy), 0, 0)
            assertEquals(NavigationStatus.GPS_LOST, state.navigationStatus)
            assertNull(state.nextManeuver)
            assertNull(state.etaEpochMillis)
        }
        val tracker = RouteGuidanceTracker(route)
        assertEquals(NavigationStatus.GPS_LOST, tracker.update(fix(), 0, 0, 15_001).navigationStatus)
        assertEquals(NavigationStatus.GPS_LOST, tracker.update(fix(lat = Double.NaN), 1_000, 0).navigationStatus)
    }

    @Test fun `arrival requires endpoint geometry repeated fixes and time then stays arrived`() {
        val tracker = RouteGuidanceTracker(route)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(0.001, 0.001), 0, 0).navigationStatus)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(0.001, 0.001), 5_000, 0).navigationStatus)
        tracker.update(fix(0.001, 0.001, time = 2), 6_000, 0)
        val arrived = tracker.update(fix(0.001, 0.001, time = 3), 7_000, 0)
        assertEquals(NavigationStatus.ARRIVED, arrived.navigationStatus)
        assertNull(arrived.nextManeuver)
        assertEquals(0, arrived.remainingDistanceMeters!!)
        assertEquals(NavigationStatus.ARRIVED, tracker.update(fix(0.0009, 0.001, time = 4), 8_000, 0).navigationStatus)
    }

    @Test fun `isolated jump and return cannot advance route or cause reroute`() {
        val tracker = RouteGuidanceTracker(route)
        val start = tracker.update(fix(), 0, 0)
        assertEquals(NavigationStatus.GPS_LOST, tracker.update(fix(0.001, 0.001, time = 2), 100, 0).navigationStatus)
        assertEquals(NavigationStatus.GPS_LOST, tracker.update(fix(time = 3), 200, 0).navigationStatus)
        val recovered = tracker.update(fix(time = 4), 300, 0)
        assertEquals(start.remainingDistanceMeters, recovered.remainingDistanceMeters)
        assertEquals(NavigationStatus.NAVIGATING, recovered.navigationStatus)
    }

    @Test fun `crossing route does not jump forward to distant later branch`() {
        val crossing = route.copy(geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.002),
            GeoPoint(0.002, 0.002), GeoPoint(0.002, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, -0.002)), maneuvers = emptyList())
        val tracker = RouteGuidanceTracker(crossing)
        val initial = tracker.update(fix(), 0, 0)
        val next = tracker.update(fix(lon = -0.00005, time = 2), 1_000, 0)
        assertEquals(initial.remainingDistanceMeters, next.remainingDistanceMeters)
        assertEquals(NavigationStatus.NAVIGATING, next.navigationStatus)
    }

    @Test fun `dateline projection follows short segment`() {
        val crossing = RouteResult(listOf(GeoPoint(0.0, 179.999), GeoPoint(0.0, -179.999)), emptyList(), 222, 30)
        val state = RouteGuidanceTracker(crossing).update(fix(lon = 180.0), 0, 0)
        assertEquals(111, state.remainingDistanceMeters!!)
    }

    @Test fun `reroute policy single flight cooldown and retry after suppress loops`() {
        val policy = ReroutePolicy()
        val off = NavigationState(navigationStatus = NavigationStatus.OFF_ROUTE)
        assertTrue(policy.shouldRequest(off, 0))
        policy.markRequested(0)
        assertFalse(policy.shouldRequest(off, 100_000))
        policy.markFinished(1_000, retryAfterMs = 60_000)
        assertFalse(policy.shouldRequest(off, 60_999))
        assertTrue(policy.shouldRequest(off, 61_000))
        assertFalse(policy.shouldRequest(off.copy(navigationStatus = NavigationStatus.GPS_LOST), 61_000))
        policy.reset()
        assertTrue(policy.shouldRequest(off, 0))
    }

    @Test fun `demo is deterministic interpolated and includes guidance then arrival`() {
        val demo = RouteDemo(route)
        val state = demo.stateAt(5_000, 100_000)
        assertEquals(state, demo.stateAt(5_000, 100_000))
        assertEquals("Left", state.nextManeuver!!.instruction)
        assertEquals(51, state.distanceToNextManeuverMeters!!)
        assertEquals(12f, state.speed, 0f)
        assertEquals(0.0005396, state.position.longitude, 0.000001)
        assertEquals(90f, state.bearing, 0.1f)
        val arrived = demo.stateAt(30_000, 130_000)
        assertEquals(NavigationStatus.ARRIVED, arrived.navigationStatus)
        assertEquals(route.geometry.last(), arrived.position)
        assertNull(arrived.nextManeuver)
    }

    @Test fun `repeated ticks preserve movement baseline until new fix`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)), emptyList(), 1112, 100)
        val tracker = RouteGuidanceTracker(longRoute)
        val original = fix(speed = 30f)
        tracker.update(original, 0, 0)
        for (tick in 1..49) tracker.update(original, tick * 100L, 0)
        val moved = tracker.update(fix(lon = 0.0015, time = 2, speed = 30f), 5_000, 0)
        assertEquals(NavigationStatus.NAVIGATING, moved.navigationStatus)
        assertEquals(945, moved.remainingDistanceMeters!!)
    }

    @Test fun `first acquisition can begin far inside calculated route`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)), emptyList(), 1112, 100)
        val tracker = RouteGuidanceTracker(longRoute)
        val midway = tracker.update(fix(lon = 0.005), 0, 0)
        assertEquals(556, midway.remainingDistanceMeters!!)
        assertEquals(534, tracker.update(fix(lon = 0.0052, time = 2), 2_000, 0).remainingDistanceMeters!!)
    }

    @Test fun `start right and start left are departure instructions not upcoming turns`() {
        for (startType in 1..3) {
            val withDeparture = route.copy(maneuvers = listOf(route.maneuvers.first().copy(type = startType)) + route.maneuvers.drop(1))
            assertEquals("Left", RouteGuidanceTracker(withDeparture).update(fix(), 0, 0).nextManeuver!!.instruction)
        }
    }

    @Test fun `GPS recovery needs two new stable fixes and ignores absent time for movement`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.02)), emptyList(), 2224, 100)
        val tracker = RouteGuidanceTracker(longRoute)
        tracker.update(fix(speed = 25f), 0, 0)
        val first = tracker.update(fix(lon = 0.01, time = 2, speed = 25f), 60_000, 0)
        assertEquals(NavigationStatus.GPS_LOST, first.navigationStatus)
        assertNull(first.nextManeuver)
        val second = tracker.update(fix(lon = 0.0101, time = 3, speed = 25f), 61_000, 0)
        assertEquals(NavigationStatus.NAVIGATING, second.navigationStatus)
        assertEquals(1101, second.remainingDistanceMeters!!)
    }

    @Test fun `ambiguous distant on route jump does not show old turn guidance`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)), emptyList(), 1112, 100)
        val tracker = RouteGuidanceTracker(longRoute)
        tracker.update(fix(), 0, 0)
        tracker.update(fix(lon = 0.005, time = 2), 1_000, 0)
        val stableButDistant = tracker.update(fix(lon = 0.005, time = 3), 2_000, 0)
        assertEquals(NavigationStatus.NAVIGATING, stableButDistant.navigationStatus)
        assertNull(stableButDistant.remainingDistanceMeters)
        assertNull(stableButDistant.nextManeuver)
        tracker.update(fix(lon = 0.005, time = 4), 5_000, 0)
        val offRoute = tracker.update(fix(lon = 0.005, time = 5), 7_000, 0)
        assertEquals(NavigationStatus.OFF_ROUTE, offRoute.navigationStatus)
        assertTrue(ReroutePolicy().shouldRequest(offRoute, 7_000))
    }

    @Test fun `turning back on the same road confirms deviation rather than GPS loss`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.02)), emptyList(), 2224, 180)
        val tracker = RouteGuidanceTracker(longRoute)
        tracker.update(fix(lon = 0.01), 0, 0)
        tracker.update(fix(lon = 0.0097, time = 2), 1_000, 0)
        tracker.update(fix(lon = 0.0094, time = 3), 2_000, 0)
        tracker.update(fix(lon = 0.0091, time = 4), 4_000, 0)
        val reversed = tracker.update(fix(lon = 0.0088, time = 5), 7_000, 0)
        assertEquals(NavigationStatus.OFF_ROUTE, reversed.navigationStatus)
        assertTrue(ReroutePolicy().shouldRequest(reversed, 7_000))
        assertNull(reversed.nextManeuver)
        assertEquals(GeoPoint(0.0, 0.0088), reversed.position)
    }

    @Test fun `small movement past progress window uses boundary instead of false GPS loss`() {
        val longRoute = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.02)), emptyList(), 2224, 180)
        val tracker = RouteGuidanceTracker(longRoute)
        val initial = tracker.update(fix(lon = 0.01), 0, 0)
        val reversed = tracker.update(fix(lon = 0.00975, time = 2), 1_000, 0)
        assertEquals(NavigationStatus.NAVIGATING, reversed.navigationStatus)
        assertEquals(initial.remainingDistanceMeters, reversed.remainingDistanceMeters)
    }

    @Test fun `maneuver arrows use type rather than translated prose`() {
        assertEquals(ManeuverDirection.LEFT, maneuverDirection(15))
        assertEquals(ManeuverDirection.RIGHT, maneuverDirection(10))
        assertEquals(ManeuverDirection.UTURN, maneuverDirection(12))
        assertEquals(ManeuverDirection.ROUNDABOUT, maneuverDirection(26))
        assertEquals(ManeuverDirection.ARRIVAL, maneuverDirection(6))
        assertEquals(ManeuverDirection.UNKNOWN, maneuverDirection(null))
    }
}
