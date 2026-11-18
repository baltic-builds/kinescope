package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 60: the rules that keep the tunnel up or bring it back (see `TunnelLifecycle`). */
class TunnelLifecycleTest {
    @Test
    fun aStartWhileNothingRunsBegins() {
        assertEquals(StartAction.BEGIN, TunnelLifecycle.onStartRequest(tunnelRunning = false, startOrRestartInProgress = false, stopInFlight = false))
    }

    @Test
    fun aStartWhileTheTunnelIsUpOrStartingChangesNothing() {
        assertEquals(StartAction.ALREADY_UP, TunnelLifecycle.onStartRequest(true, false, false))
        assertEquals(StartAction.ALREADY_STARTING, TunnelLifecycle.onStartRequest(false, true, false))
    }

    @Test
    fun aStartDuringAStopWaitsForTheStopInsteadOfBeingLost() {
        // The user switched the bypass off and on again within a second or two.
        assertEquals(StartAction.AFTER_STOP, TunnelLifecycle.onStartRequest(false, false, true))
        assertEquals(StartAction.AFTER_STOP, TunnelLifecycle.onStartRequest(true, true, true))
    }

    @Test
    fun aFailedStartIsRetriedAboutThreeMinutesAndThenGivenUp() {
        assertEquals(3_000L, StartRetry.delayBeforeRetry(1))
        assertEquals(60_000L, StartRetry.delayBeforeRetry(6))
        assertNull(StartRetry.delayBeforeRetry(7))
        assertNull(StartRetry.delayBeforeRetry(0))
        assertTrue(StartRetry.BACKOFF_MS.sum() in 150_000L..240_000L)
        assertTrue(StartRetry.BACKOFF_MS.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun anExhaustedTunnelCoolsDownThreeTimesAndThenStops() {
        assertEquals(30_000L, StartRetry.exhaustedCooldown(1))
        assertEquals(240_000L, StartRetry.exhaustedCooldown(3))
        assertNull(StartRetry.exhaustedCooldown(4))
    }

    @Test
    fun aMonitorThatKeepsThrowingIsRebuiltAfterFiveSeconds() {
        assertEquals(MonitorFault.IGNORE, MonitorErrors.after(1))
        assertEquals(MonitorFault.IGNORE, MonitorErrors.after(9))
        assertEquals(MonitorFault.REBUILD, MonitorErrors.after(10))
        assertEquals(MonitorFault.REBUILD, MonitorErrors.after(25))
    }

    @Test
    fun aLoopOfErrorsDoesNotFillTheLog() {
        val logged = (1..100).count { MonitorErrors.shouldLog(it) }
        assertEquals(11, logged)
        assertTrue(MonitorErrors.shouldLog(1))
        assertFalse(MonitorErrors.shouldLog(2))
    }

    @Test
    fun resumesAfterAKillAreLimitedPerHour() {
        val hour = ResumePolicy.WINDOW_MS
        val now = 10 * hour
        assertTrue(ResumePolicy.allow(emptyList(), now))
        assertTrue(ResumePolicy.allow(listOf(now - 10_000L, now - 20_000L), now))
        assertFalse(ResumePolicy.allow(listOf(now - 10_000L, now - 20_000L, now - 30_000L), now))
        // Resumes older than the window do not count.
        assertTrue(ResumePolicy.allow(listOf(now - 2 * hour, now - 3 * hour, now - 4 * hour), now))
    }
}
