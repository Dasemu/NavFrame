package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class RenderSchedulerTest {
    private fun state(speed: Float = 0f, bearing: Float = 0f, lat: Double = 43.0) =
        NavigationState(position = GeoPoint(lat, -5.0), speed = speed, bearing = bearing)

    @Test fun stationaryUnchangedDoesNotRedrawEvenAfterLongWait() {
        val scheduler = RenderScheduler()
        assertTrue(scheduler.renderIfDue(state(), 0))
        assertFalse(scheduler.renderIfDue(state(), 60_000))
    }

    @Test fun stationaryPositionAndHeadingJitterDoesNotRedraw() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(), 0)
        assertFalse(scheduler.renderIfDue(state(bearing = 90f, lat = 43.000005), 5_000))
    }

    @Test fun stationaryUiChangesRespectOneFpsMaximum() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(), 0)
        val changed = state().copy(navigationStatus = NavigationStatus.GPS_LOST)
        assertFalse(scheduler.renderIfDue(changed, 999))
        assertTrue(scheduler.renderIfDue(changed, 1_000))
    }

    @Test fun movingPositionUpdatesRespectFiveFpsMaximum() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(speed = 8f), 0)
        val moved = state(speed = 8f, lat = 43.0001)
        assertFalse(scheduler.renderIfDue(moved, 199))
        assertTrue(scheduler.renderIfDue(moved, 200))
    }

    @Test fun rapidTurnCanRenderAtTenFps() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(speed = 8f), 0)
        val turned = state(speed = 8f, bearing = 20f)
        assertFalse(scheduler.renderIfDue(turned, 99))
        assertTrue(scheduler.renderIfDue(turned, 100))
    }

    @Test fun compassNorthWrapIsSmallChangeRatherThanRapidTurn() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(speed = 8f, bearing = 359f), 0)
        assertFalse(scheduler.renderIfDue(state(speed = 8f, bearing = 1f), 50))
        assertFalse(scheduler.renderIfDue(state(speed = 8f, bearing = 4f), 100))
        assertTrue(scheduler.renderIfDue(state(speed = 8f, bearing = 4f), 200))
    }

    @Test fun queuedStateIsReplacedWithLatestWhileThrottled() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(speed = 8f), 0)
        scheduler.offer(state(speed = 8f, lat = 43.0001))
        assertNull(scheduler.takeIfDue(100))
        val newest = state(speed = 8f, lat = 43.0002)
        scheduler.offer(newest)
        assertEquals(newest, scheduler.takeIfDue(200))
        scheduler.markRendered(newest, 200)
        assertNull(scheduler.takeIfDue(400))
    }

    @Test fun newManeuverAndLabelChangeRenderWithoutPositionChange() {
        val scheduler = RenderScheduler()
        val first = state().copy(nextManeuver = Maneuver("derecha", 350), distanceToNextManeuverMeters = 350)
        scheduler.renderIfDue(first, 0)
        assertTrue(scheduler.renderIfDue(first.copy(nextManeuver = Maneuver("izquierda", 350)), 1_000))
        assertTrue(scheduler.renderIfDue(first.copy(nextManeuver = Maneuver("izquierda", 350), distanceToNextManeuverMeters = 330), 2_000))
    }

    @Test fun etaOnlyChangesRenderedMinuteRatherThanEverySecond() {
        val scheduler = RenderScheduler()
        val first = state().copy(etaEpochMillis = 120_000)
        scheduler.renderIfDue(first, 0)
        assertFalse(scheduler.renderIfDue(first.copy(etaEpochMillis = 121_000), 1_000))
        assertTrue(scheduler.renderIfDue(first.copy(etaEpochMillis = 180_000), 2_000))
    }

    @Test fun configurablePolicyChangesMovementThresholdAndRate() {
        val scheduler = RenderScheduler(RenderScheduleConfig(movingMaxFps = 2.0, positionDeltaMeters = 20.0))
        scheduler.renderIfDue(state(speed = 8f), 0)
        assertFalse(scheduler.renderIfDue(state(speed = 8f, lat = 43.0001), 100))
        val moved = state(speed = 8f, lat = 43.0003)
        assertFalse(scheduler.renderIfDue(moved, 499))
        assertTrue(scheduler.renderIfDue(moved, 500))
    }

    @Test fun resetAndRestartedMonotonicClockAllowFirstFrameImmediately() {
        val scheduler = RenderScheduler()
        scheduler.renderIfDue(state(), 10_000)
        assertTrue(scheduler.renderIfDue(state(), 100))
        scheduler.reset()
        assertTrue(scheduler.renderIfDue(state(), 101))
    }

    @Test fun failedRenderDoesNotCommitAndNewerFixSurvivesOlderRenderCompletion() {
        val scheduler = RenderScheduler()
        val first = state(speed = 8f)
        scheduler.offer(first)
        assertEquals(first, scheduler.takeIfDue(0))
        // Encoding failed: querying again still yields the first frame, without waiting.
        assertEquals(first, scheduler.takeIfDue(1))
        val newest = first.copy(position = GeoPoint(43.0002, -5.0))
        scheduler.offer(newest)
        scheduler.markRendered(first, 2)
        assertNull(scheduler.takeIfDue(100))
        assertEquals(newest, scheduler.takeIfDue(202))
    }

    private fun RenderScheduler.renderIfDue(state: NavigationState, nowMs: Long): Boolean {
        val due = shouldRender(state, nowMs)
        if (due) markRendered(state, nowMs)
        return due
    }

    @Test fun stationaryAccuracyAndBearingAvailabilityChangesAreVisible() {
        val scheduler = RenderScheduler()
        val first = state().copy(accuracyMeters = 5f)
        scheduler.renderIfDue(first, 0)
        assertFalse(scheduler.renderIfDue(first.copy(accuracyMeters = 6f), 1_000))
        assertTrue(scheduler.renderIfDue(first.copy(accuracyMeters = 10f), 2_000))
        assertTrue(scheduler.renderIfDue(first.copy(accuracyMeters = null), 3_000))
        assertTrue(scheduler.renderIfDue(first.copy(accuracyMeters = null, bearing = Float.NaN), 4_000))
    }

    @Test fun invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RenderScheduleConfig(movingMaxFps = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { RenderScheduleConfig(distanceLabelStepMeters = 0) }
    }
}
