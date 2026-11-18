package com.kinescope.app

/**
 * Patch 46: when the background upkeep is due. Patch 55: what is left of it is the calendar of yt-dlp and (patch 64) of the community strategy
 * list (every [INTERVAL_MS]) and the day length the other calendars count in; the strategies are tested when the network says so
 * (`LaunchUpdate`, patch 64), not by a calendar.
 * Pure, unit-tested in `MaintenanceScheduleTest`.
 */
internal object MaintenanceSchedule {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** How often yt-dlp and the community strategy list are refreshed when nothing else asked for it: 30 days (patch 64; was 25, yt-dlp only). */
    const val INTERVAL_MS = 30L * DAY_MS

    /** True when [lastAt] (0 = never) is at least [INTERVAL_MS] ago. A time in the future (the clock moved back) counts as old. */
    fun isStale(lastAt: Long, now: Long): Boolean = lastAt <= 0L || (now - lastAt) !in 0L until INTERVAL_MS
}
