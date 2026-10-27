package com.kinescope.app

/**
 * Patch 46: when the background upkeep is due. Patch 55: what is left of it is the yt-dlp update calendar (every
 * [INTERVAL_MS]) and the day length the other calendars count in; the 25-day strategy re-check is gone, the strategies are
 * checked after an install or an update and by the two-a-day random check instead (`ClassCheckPolicy`, `RandomCheckPolicy`).
 * Pure, unit-tested in `MaintenanceScheduleTest`.
 */
internal object MaintenanceSchedule {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** How often yt-dlp is refreshed when nothing else asked for it: 25 days. */
    const val INTERVAL_MS = 25L * DAY_MS

    /** True when [lastAt] (0 = never) is at least [INTERVAL_MS] ago. A time in the future (the clock moved back) counts as old. */
    fun isStale(lastAt: Long, now: Long): Boolean = lastAt <= 0L || (now - lastAt) !in 0L until INTERVAL_MS
}
