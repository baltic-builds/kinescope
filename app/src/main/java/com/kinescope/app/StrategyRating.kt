package com.kinescope.app

/**
 * Patch 53: the one number the app ranks a strategy by, for one connection class: 0-100, made of two kinds of evidence.
 *
 * - The TEST: what the last strategy search found on this class (`StoredResult.score`: hosts through, a real transfer,
 *   simultaneous connections, handshake speed). A test says how a strategy did at one moment, so its weight fades
 *   towards "unknown" ([NEUTRAL]) with a half-life of [TEST_HALF_LIFE_MS]: a test result from two months ago is a hint.
 * - The REAL use: what happened when the app really used the strategy (a finished download or a window of real traffic
 *   through the tunnel counts for it, a failure that another strategy then survived counts against it, double; see
 *   [StrategyMemory]). Real evidence fades with its own half-life of a week.
 *
 * The two are blended by how much real evidence there is: with none, the rating IS the test; each finished download shifts
 * weight to real use, which equals the test's weight at [REAL_EVIDENCE_HALF] units of evidence and outweighs it beyond
 * that. So a strategy that tested well but keeps failing for real sinks, and one that tested badly or never but keeps
 * carrying traffic rises. A strategy nothing is known about has no rating (null), not a middle one: it is not offered.
 *
 * Pure, unit-tested in `StrategyRatingTest`.
 */
internal object StrategyRating {
    /** The rating of "nothing known": what a faded test or missing evidence tends to. */
    const val NEUTRAL = 50

    /** The lowest rating that is worth offering and putting in a chain: better than unknown. */
    const val ELIGIBLE = 50

    const val TEST_HALF_LIFE_MS = 14L * MaintenanceSchedule.DAY_MS

    /** The evidence (finished downloads, in effect) at which real use weighs as much as the test. */
    const val REAL_EVIDENCE_HALF = 3.0

    private fun testFade(at: Long, now: Long): Double {
        val age = (now - at).coerceAtLeast(0L)
        return Math.pow(0.5, age.toDouble() / TEST_HALF_LIFE_MS.toDouble())
    }

    /** The rating of one strategy, or null when neither a test nor real use has ever been recorded for it. */
    fun rate(test: StoredResult?, real: LearnedStrategy?, now: Long): Int? {
        if (test == null && real == null) return null
        val testPart = if (test == null) {
            NEUTRAL.toDouble()
        } else {
            val score = if (test.passed) test.score.coerceIn(0, 100) else 0
            NEUTRAL + (score - NEUTRAL) * testFade(test.at, now)
        }
        val faded = real?.let { StrategyMemory.faded(it, now) }
        val evidence = faded?.let { it.okWeight + it.badWeight } ?: 0.0
        val realShare = evidence / (evidence + REAL_EVIDENCE_HALF)
        val realPart = 100.0 * StrategyMemory.score(real, now)
        return Math.round((1.0 - realShare) * testPart + realShare * realPart).toInt().coerceIn(0, 100)
    }

    /** The ratings of every strategy that has a test or a real record, as of [now]. */
    fun ratings(tests: Map<String, StoredResult>, real: Map<String, LearnedStrategy>, now: Long): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (line in tests.keys + real.keys) {
            rate(tests[line], real[line], now)?.let { out[line] = it }
        }
        return out
    }

    /** The lines rated at least [ELIGIBLE], best first (equal ratings keep the order of [ratings]). */
    fun eligible(ratings: Map<String, Int>): List<String> =
        ratings.entries.filter { it.value >= ELIGIBLE }.sortedByDescending { it.value }.map { it.key }
}
