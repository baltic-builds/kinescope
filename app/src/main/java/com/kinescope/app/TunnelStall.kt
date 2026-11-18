package com.kinescope.app

/**
 * Patch 59: the seven-second rule. The tunnel judges the strategy it runs on by what the YouTube app itself gets through it,
 * once a second, and switches away from a strategy that has had the app waiting for about seven seconds.
 *
 * Why the old detectors never fired (read from the code and from a device report where only the hand-picked strategy worked):
 * - the 10 s flow window (`TunnelFlow`) needed THREE one-way windows IN A ROW, each with at least 20 packets sent. A blocked
 *   connection does not keep sending: the app sends its ClientHello once, the bridge acknowledges it, and then the app waits in
 *   silence, so the very next window is idle and idle ends the streak. Thirty seconds at the very best, and in practice never;
 * - the engine probe (30 s apart, two failures, the first one 45 s after the start) talks to a handful of YouTube hosts through
 *   the engine, so a strategy that passes it but is cut on the video servers the app really uses (googlevideo.com nodes) looked
 *   healthy for ever;
 * - after all that a switch still had to wait 90 s from the previous one and could happen only three times in ten minutes.
 *
 * The rule here is built on the same bridge counters ([TunnelCounters]) read every [SAMPLE_MS]:
 * 1. An EPISODE opens at the first sample in which the app sent something and next to nothing came back.
 * 2. The episode stays open through silent samples (a blocked connection is silent), and everything the app sent and got back
 *    since it opened is added up.
 * 3. As soon as [WORKING_RX_BYTES] have come back in total, the strategy carries data: the episode closes ([StallVerdict.WORKING]).
 * 4. After [STALL_MS] without that, with enough sent to be sure the app really asked ([MIN_TX_PACKETS] packets, [MIN_TX_BYTES]
 *    bytes) and clearly more sent than received, the verdict is [StallVerdict.STALLED]: the tunnel switches strategy.
 * 5. An episode that reaches [STALL_MS] without enough sent to be sure (a few keep-alives, an app that gave up after one try)
 *    closes without a verdict.
 *
 * What it cannot see: a YouTube app whose API calls go through but whose video connections do not still gets bytes back, so the
 * episode closes as working. The 30 s engine probe stays as the slow net under this one.
 *
 * Pure (no Android types), unit-tested in `TunnelStallTest`. The state is one immutable value the service keeps.
 */
internal data class StallState(
    /** When the episode opened (elapsed-realtime ms), or [NONE] when there is none. */
    val since: Long = NONE,
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L
) {
    val open: Boolean get() = since != NONE

    companion object {
        const val NONE = -1L
    }
}

internal enum class StallVerdict {
    /** Nothing to judge: no episode is open. */
    NONE,

    /** An episode is open and has not run long enough. */
    WATCHING,

    /** The strategy brought enough data back: the episode is over and the strategy is fine. */
    WORKING,

    /** The app has waited [TunnelStall.STALL_MS] and nothing real came back: switch strategy. */
    STALLED
}

/** What a finished stalled episode looked like, for the log line. */
internal data class StallReport(val ms: Long, val txPackets: Long, val txBytes: Long, val rxBytes: Long)

internal data class StallStep(val state: StallState, val verdict: StallVerdict, val report: StallReport? = null)

internal object TunnelStall {
    /** How long the app may wait for an answer before the strategy is given up: seven seconds. */
    const val STALL_MS = 4_000L

    /** How often the bridge counters are read. */
    const val SAMPLE_MS = 1_000L

    /** The app must have sent at least this many packets in the episode (a hello and its retries, a few connections). */
    const val MIN_TX_PACKETS = 8L

    /** ... and at least this many bytes, so a few keep-alives are never mistaken for a page that does not load. */
    const val MIN_TX_BYTES = 3_000L

    /** This much coming back in total during an episode is a working strategy (a TLS answer is a few KB). */
    const val WORKING_RX_BYTES = 16_000L

    /** Stalled needs the app to have sent more than this many times what came back. */
    const val TX_TO_RX_RATIO = 3L

    /** A sample that brings back more than this does not open an episode. */
    const val OPEN_RX_BYTES = 2_000L

    /**
     * One step: the counters [previous] (read at [previousAt]) and [current] (read at [now], both elapsed-realtime ms) move
     * [state] on. A counter that went down means the bridge was restarted (its counters start from zero again): the episode ends.
     */
    fun step(state: StallState, previous: TunnelCounters, current: TunnelCounters, previousAt: Long, now: Long): StallStep {
        val txPackets = current.txPackets - previous.txPackets
        val txBytes = current.txBytes - previous.txBytes
        val rxBytes = current.rxBytes - previous.rxBytes
        if (txPackets < 0L || txBytes < 0L || rxBytes < 0L) return StallStep(StallState(), StallVerdict.NONE)

        if (!state.open) {
            val opens = txPackets >= 1L && rxBytes < OPEN_RX_BYTES && rxBytes * TX_TO_RX_RATIO < txBytes
            if (!opens) return StallStep(state, StallVerdict.NONE)
            // The episode began somewhere inside the sample that showed it: count from the start of that sample.
            val opened = StallState(
                since = previousAt,
                txPackets = txPackets,
                txBytes = txBytes,
                rxBytes = rxBytes
            )
            return StallStep(opened, StallVerdict.WATCHING)
        }

        val total = state.copy(
            txPackets = state.txPackets + txPackets,
            txBytes = state.txBytes + txBytes,
            rxBytes = state.rxBytes + rxBytes
        )
        if (total.rxBytes >= WORKING_RX_BYTES) return StallStep(StallState(), StallVerdict.WORKING)
        if (now - total.since < STALL_MS) return StallStep(total, StallVerdict.WATCHING)

        val asked = total.txPackets >= MIN_TX_PACKETS && total.txBytes >= MIN_TX_BYTES
        val unanswered = total.rxBytes * TX_TO_RX_RATIO < total.txBytes
        if (!asked || !unanswered) return StallStep(StallState(), StallVerdict.NONE)
        val report = StallReport(now - total.since, total.txPackets, total.txBytes, total.rxBytes)
        return StallStep(StallState(), StallVerdict.STALLED, report)
    }
}
