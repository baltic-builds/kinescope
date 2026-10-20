package com.kinescope.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 46: the 25-day upkeep calendar. */
class MaintenanceScheduleTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val now = 1_000_000L * day

    @Test
    fun theIntervalIsTwentyFiveDays() {
        assertTrue(MaintenanceSchedule.INTERVAL_MS == 25L * day)
    }

    @Test
    fun aRecheckThatNeverRanIsDue() {
        assertTrue(MaintenanceSchedule.isDue(lastDoneAt = 0L, lastTriedAt = 0L, now = now))
    }

    @Test
    fun aRecheckIsNotDueBeforeTwentyFiveDaysAndDueFromThen() {
        assertFalse(MaintenanceSchedule.isDue(now - 24 * day, 0L, now))
        assertFalse(MaintenanceSchedule.isDue(now - 25 * day + 1L, 0L, now))
        assertTrue(MaintenanceSchedule.isDue(now - 25 * day, 0L, now))
        assertTrue(MaintenanceSchedule.isDue(now - 90 * day, 0L, now))
    }

    @Test
    fun aRecentAttemptHoldsTheNextOneBackEvenWhenTheLastFinishedCheckIsOld() {
        val old = now - 60 * day
        assertFalse(MaintenanceSchedule.isDue(old, now - 3_600_000L, now))
        assertFalse(MaintenanceSchedule.isDue(old, now - day + 1L, now))
        assertTrue(MaintenanceSchedule.isDue(old, now - day, now))
    }

    @Test
    fun aTimeInTheFutureCountsAsOldBecauseTheClockMovedBack() {
        assertTrue(MaintenanceSchedule.isDue(now + 10 * day, 0L, now))
        assertTrue(MaintenanceSchedule.isDue(now - 60 * day, now + 10 * day, now))
        assertTrue(MaintenanceSchedule.isStale(now + 10 * day, now))
    }

    @Test
    fun theYtDlpUpdateFollowsTheSameInterval() {
        assertTrue(MaintenanceSchedule.isStale(0L, now))
        assertFalse(MaintenanceSchedule.isStale(now - 12 * 3_600_000L, now))
        assertFalse(MaintenanceSchedule.isStale(now - 24 * day, now))
        assertTrue(MaintenanceSchedule.isStale(now - 25 * day, now))
    }
}
