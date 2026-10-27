package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 46, cut down in patch 55: the yt-dlp update calendar (the strategies no longer have one). */
class MaintenanceScheduleTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val now = 1_000_000L * day

    @Test
    fun theIntervalIsTwentyFiveDays() {
        assertEquals(25L * day, MaintenanceSchedule.INTERVAL_MS)
    }

    @Test
    fun aTimeInTheFutureCountsAsStale() {
        assertTrue(MaintenanceSchedule.isStale(now + 10 * day, now))
    }

    @Test
    fun neverUpdatedIsStaleAndRecentIsNot() {
        assertTrue(MaintenanceSchedule.isStale(0L, now))
        assertFalse(MaintenanceSchedule.isStale(now - 12 * 3_600_000L, now))
        assertFalse(MaintenanceSchedule.isStale(now - 24 * day, now))
        assertTrue(MaintenanceSchedule.isStale(now - 25 * day, now))
    }
}
