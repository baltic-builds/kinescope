package com.kinescope.app

/**
 * Patch 46: when the background upkeep is due -- the yt-dlp update, the strategy list and the strategy re-check.
 * It used to be tied to the app version (every update of the app started a full re-check) and, for yt-dlp, to 12 hours.
 * Now both follow one calendar: every [INTERVAL_MS], never because the app itself was updated. Pure, unit-tested in
 * `MaintenanceScheduleTest`.
 */
internal object MaintenanceSchedule {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** How often the upkeep runs: 25 days. */
    const val INTERVAL_MS = 25L * DAY_MS

    /**
     * After an attempt that did not finish (stopped by the user, no network, a crash) the next one waits at least this
     * long, so opening the app again does not restart a long check every time.
     */
    const val RETRY_GAP_MS = DAY_MS

    /** True when [lastAt] (0 = never) is at least [gapMs] ago. A time in the future (the clock moved back) counts as old. */
    private fun elapsed(lastAt: Long, now: Long, gapMs: Long): Boolean = lastAt <= 0L || (now - lastAt) !in 0L until gapMs

    /** Whether something last done at [lastAt] is overdue: used for the yt-dlp update, which keeps its own timestamp. */
    fun isStale(lastAt: Long, now: Long): Boolean = elapsed(lastAt, now, INTERVAL_MS)

    /** Whether the strategy re-check is due: the last finished one is old enough and the last attempt is not recent. */
    fun isDue(lastDoneAt: Long, lastTriedAt: Long, now: Long): Boolean =
        elapsed(lastDoneAt, now, INTERVAL_MS) && elapsed(lastTriedAt, now, RETRY_GAP_MS)
}
