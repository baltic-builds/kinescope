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

/** Patch 59: what [RotationLimits.decide] says about one more strategy switch. */
internal enum class RotationDecision { GO, TOO_SOON, EXHAUSTED }

/**
 * Patch 59: how often the tunnel may switch strategy on its own. Before, a switch had to wait 90 s from the previous one and
 * at most three were allowed in ten minutes: with the seven-second rule ([TunnelStall]) that would stop the tunnel after the
 * third bad strategy of a chain of six, so the best fallbacks were never reached. Now:
 * - a switch needs [MIN_GAP_MS] since the last one (a restart takes longer than that anyway; this only stops a loop that spins);
 * - at most one switch per strategy of the chain in [WINDOW_MS] (and never fewer than [MIN_ALLOWED]), so a whole chain can be
 *   walked once; after that the tunnel is called unusable, as before;
 * - a chain with nothing to switch to is exhausted at once (the caller first tries one short search for spare strategies).
 *
 * Pure, unit-tested in `RotationLimitsTest`.
 */
internal object RotationLimits {
    const val WINDOW_MS = 10L * 60L * 1000L
    const val MIN_GAP_MS = 4_000L
    const val MIN_ALLOWED = 3

    /** How many switches the window allows for a chain of [chainSize]. */
    fun allowed(chainSize: Int): Int = maxOf(MIN_ALLOWED, chainSize)

    /** [recent] are the times (elapsed-realtime ms) of the switches made so far, oldest first; [alternatives] the strategies to move to. */
    fun decide(recent: List<Long>, now: Long, chainSize: Int, alternatives: Int): RotationDecision {
        val inWindow = recent.filter { now - it in 0L..WINDOW_MS }
        if (alternatives <= 0 || inWindow.size >= allowed(chainSize)) return RotationDecision.EXHAUSTED
        val last = inWindow.lastOrNull()
        if (last != null && now - last < MIN_GAP_MS) return RotationDecision.TOO_SOON
        return RotationDecision.GO
    }
}
