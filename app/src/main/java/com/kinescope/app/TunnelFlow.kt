package com.kinescope.app

/**
 * Patch 44: the counters of the TUN bridge (hev-socks5-tunnel, `TProxyGetStats`).
 *
 * Directions as the bridge counts them: `tx` is what the YouTube app sent INTO the tunnel (packets read from
 * the TUN device), `rx` is what came back OUT to the app (packets written to it). So a healthy page load shows
 * a small `tx` and a large `rx`; an app that keeps sending while nothing real comes back shows the opposite.
 */
internal data class TunnelCounters(
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long
) {
    companion object {
        val ZERO = TunnelCounters(0L, 0L, 0L, 0L)

        /** `TProxyGetStats()` returns `[tx_packets, tx_bytes, rx_packets, rx_bytes]`; anything else is unusable. */
        fun fromStats(stats: LongArray?): TunnelCounters? =
            if (stats == null || stats.size < 4) null else TunnelCounters(stats[0], stats[1], stats[2], stats[3])
    }
}

internal enum class FlowVerdict {
    /** The app sent too little in this window to judge anything. */
    IDLE,

    /** The app sent traffic and a real amount came back. */
    FLOWING,

    /** The app kept sending, but almost nothing came back: the bridge is a black hole for it. */
    ONE_WAY
}

/**
 * Patch 44: judges the traffic of the YouTube app through the tunnel. The periodic probe of the tunnel talks
 * to the engine directly, so it cannot see a failure on the way between the app and the engine (the TUN, the
 * bridge, name lookups, UDP); these counters can. Pure, unit-tested in `TunnelFlowTest`.
 */
internal object TunnelFlow {
    /** Fewer packets than this sent into the tunnel in one window is "idle": there is nothing to judge. */
    const val MIN_TX_PACKETS = 20L

    /** A window that brought back at least this many bytes is never called one-way, whatever was sent. */
    const val ENOUGH_RX_BYTES = 24_000L

    /** One-way also needs the app to have sent clearly more than came back (a lone ping and its reply is balanced). */
    const val TX_TO_RX_RATIO = 3L

    /** Consecutive one-way windows before the tunnel switches strategy (three 10 s windows). */
    const val WINDOWS_BEFORE_ROTATE = 3

    /**
     * Judges the traffic between two readings. A reading lower than the previous one means the bridge was
     * restarted (its counters start from zero again), which counts as idle.
     */
    fun judge(previous: TunnelCounters, current: TunnelCounters): FlowVerdict {
        val txPackets = current.txPackets - previous.txPackets
        if (txPackets < MIN_TX_PACKETS) return FlowVerdict.IDLE
        val txBytes = current.txBytes - previous.txBytes
        val rxBytes = current.rxBytes - previous.rxBytes
        if (txBytes < 0L || rxBytes < 0L) return FlowVerdict.IDLE
        val oneWay = rxBytes < ENOUGH_RX_BYTES && rxBytes * TX_TO_RX_RATIO < txBytes
        return if (oneWay) FlowVerdict.ONE_WAY else FlowVerdict.FLOWING
    }

    /** The run of consecutive one-way windows after [verdict]: anything but one-way ends the run. */
    fun streakAfter(streak: Int, verdict: FlowVerdict): Int =
        if (verdict == FlowVerdict.ONE_WAY) streak + 1 else 0

    fun shouldRotate(streak: Int): Boolean = streak >= WINDOWS_BEFORE_ROTATE

    /** Patch 46: a window that brought back at least this many bytes is real data, not a ping and its answer. */
    const val PROOF_RX_BYTES = 64_000L

    /**
     * Patch 46: whether the window between two readings shows the strategy carrying real data to the YouTube app: the
     * app sent traffic, it was not one-way, and at least [PROOF_RX_BYTES] came back. This is what the app remembers
     * as "this strategy really carried traffic" (see `StrategyMemory`).
     */
    fun proves(previous: TunnelCounters, current: TunnelCounters): Boolean =
        judge(previous, current) == FlowVerdict.FLOWING && current.rxBytes - previous.rxBytes >= PROOF_RX_BYTES
}

/** Patch 45: what the Home dashboard shows about the running tunnel (published by `BypassVpnService`). */
internal data class TunnelTraffic(
    /** The verdict of the last 10 s window (see [TunnelFlow.judge]). */
    val verdict: FlowVerdict = FlowVerdict.IDLE,
    /** The 1-based place of the strategy in use within the chain, or 0 when it is not known. */
    val position: Int = 0,
    val total: Int = 0
)
