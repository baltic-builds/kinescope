package com.kinescope.app

/**
 * Patch 56: what the YouTube tunnel does when its transport (the engine process or the TUN bridge) stops without being asked to,
 * and what the Home dashboard is told about it. Pure, unit-tested in `TransportRecoveryTest`.
 *
 * Read from a device report (2026-10-28): the tunnel came up on a strategy that had just passed its live check and its transport
 * stopped three seconds later; the monitor blamed the strategy (`quarantined=1`, a real failure on its record, moved to the
 * bottom of the chain although the next start on the same strategy ran fine). The same line appeared when the user switched the
 * bypass off: the stop closed the TUN and the monitor, waking up a moment later, called it an unexpected end and blamed the
 * strategy that was carrying the traffic. A transport that dies says little about the strategy, so:
 *
 * - a stop the user (or the system) asked for is never a failure;
 * - the first early death of a strategy is answered with a reconnect on the same strategy, nothing is blamed;
 * - a second early death of the same strategy before it has carried traffic, is its failure: it is quarantined and the router
 *   moves on, as before;
 * - a transport that ran for [LONG_RUN_MS] or longer and then died (the system killed a process, a network blip) is answered with
 *   a reconnect again and never counted: it had carried the traffic.
 */
internal enum class TransportFate { STOPPED_BY_USER, RESTART_SAME, ROTATE }

internal object TransportRecovery {
    /** A transport that ran at least this long before it died is not blamed on the strategy: two minutes. */
    const val LONG_RUN_MS = 120_000L

    /** How many early deaths of one strategy are answered with a plain reconnect before the strategy is blamed. */
    const val FREE_EARLY_DEATHS = 1

    /**
     * [runMs] is how long the transport had run, [earlyDeathsOfLine] how many early deaths the strategy in use already had since
     * it last carried traffic (see [deathsAfter]).
     */
    fun decide(stopRequested: Boolean, runMs: Long, earlyDeathsOfLine: Int): TransportFate = when {
        stopRequested -> TransportFate.STOPPED_BY_USER
        runMs >= LONG_RUN_MS -> TransportFate.RESTART_SAME
        earlyDeathsOfLine < FREE_EARLY_DEATHS -> TransportFate.RESTART_SAME
        else -> TransportFate.ROTATE
    }

    /** The early deaths of a strategy after one more death with [fate]: only a free reconnect after a short run counts. */
    fun deathsAfter(deaths: Int, runMs: Long, fate: TransportFate): Int =
        if (fate == TransportFate.RESTART_SAME && runMs < LONG_RUN_MS) deaths + 1 else deaths
}

/**
 * Patch 58: candidates for the restart caused by [failed]. Quarantine normally puts that route last, but when
 * every route is quarantined its pin could lead again and pass the light startup check. Excluding it for this
 * one restart makes a failure-driven switch real; ordinary starts and later quarantine expiry are unchanged.
 */
internal object TunnelRotation {
    fun withoutFailed(chain: List<String>, failed: String?): List<String> =
        if (failed == null) chain else chain.filter { it != failed }
}

/** Why the tunnel changed what it runs on, in the words the dashboard uses. */
internal enum class RouterEventKind {
    /** Two live probes of the engine failed in a row. */
    PROBE,

    /** The YouTube app kept sending and nothing came back. */
    ONE_WAY,

    /** The transport died twice in a row on the same strategy. */
    TRANSPORT,

    /** The phone moved from Wi-Fi to mobile data or back. */
    NETWORK,

    /** An update of the strategies found a different chain. */
    UPDATE,

    /** The transport stopped by itself and the same strategy was started again (nothing blamed). */
    RECONNECTED
}

/** The last change the tunnel made on its own: what and when (epoch ms). Kept in memory for the life of the tunnel. */
internal data class RouterEvent(val kind: RouterEventKind, val at: Long)
