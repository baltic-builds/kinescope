package com.kinescope.app

/** Patch 63: what [ProbeJudge] makes of one engine probe of the running tunnel. */
internal enum class ProbeVerdict {
    /** Enough of the probe's connections completed. */
    OK,

    /** The probe failed and nothing else explains it: counts toward a switch of strategy. */
    FAIL,

    /** The probe failed while the YouTube app was receiving real data through the same strategy: the app is the better witness. */
    IGNORED_TRAFFIC,

    /** The probe failed and the phone's own name lookup is dead: the probe's by-name connections could not have succeeded. */
    IGNORED_DNS
}

/**
 * Patch 63: the periodic probe of the tunnel opens three fresh connections to the engine BY NAME and used to switch strategy after two
 * failures in a row. A device report (2026-11-04) shows why that was wrong: the tunnel was carrying 3-4 MB in every ten seconds
 * (`tun win ... v=flowing rx=4160774B`) when the probe passed 1 of 3 twice (a flaky name lookup, a busy engine), the strategy was
 * quarantined, the tunnel was torn down and, with the lookup dead, could not be started again. Patch 61 already ignores a stall of a
 * strategy that proved itself in the run; the probe is judged the same way now:
 * - real data that reached the YouTube app in the last [TRAFFIC_GRACE_MS] outweighs a failed probe (a probe is a synthetic witness);
 * - a probe that fails while the system name lookup is dead is not the strategy's failure either;
 * - only a probe that fails with no traffic and a working lookup counts, and two of those in a row still switch.
 * Pure, unit-tested in `ProbeJudgeTest`.
 */
internal object ProbeJudge {
    /** Real data seen this recently (ms) clears a failed probe: about one probe interval. */
    const val TRAFFIC_GRACE_MS = 35_000L

    /** One bridge sample (about a second) that brought back at least this many bytes counts as real data. */
    const val REAL_RX_BYTES = 8_000L

    fun passes(passed: Int, total: Int): Boolean = passed * 3 >= total * 2

    /**
     * [msSinceRealData] is how long ago the app last received real data through the tunnel (null = not in this run); [lookupWorks] is
     * asked only when it matters (a failed probe and no recent traffic), because it makes a lookup.
     */
    fun judge(passed: Int, total: Int, msSinceRealData: Long?, lookupWorks: () -> Boolean): ProbeVerdict = when {
        passes(passed, total) -> ProbeVerdict.OK
        msSinceRealData != null && msSinceRealData in 0L..TRAFFIC_GRACE_MS -> ProbeVerdict.IGNORED_TRAFFIC
        !lookupWorks() -> ProbeVerdict.IGNORED_DNS
        else -> ProbeVerdict.FAIL
    }
}
