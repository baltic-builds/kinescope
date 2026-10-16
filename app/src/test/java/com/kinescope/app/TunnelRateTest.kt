package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 45: the tunnel's speed between two readings of the bridge's counters. */
class TunnelRateTest {
    private fun counters(txBytes: Long, rxBytes: Long) = TunnelCounters(0L, txBytes, 0L, rxBytes)

    @Test
    fun theSpeedIsTheDifferenceOverTheElapsedTime() {
        // 3 MB came back and 4 KB went out in two seconds.
        val rate = TunnelRate.between(counters(1_000, 500_000), counters(5_000, 3_500_000), 2_000L)
        assertEquals(TunnelRate(downBytesPerSec = 1_500_000L, upBytesPerSec = 2_000L), rate)
    }

    @Test
    fun noTimeOrNoTrafficIsNoSpeed() {
        val same = counters(10_000, 20_000)
        assertEquals(TunnelRate.ZERO, TunnelRate.between(same, same, 2_000L))
        assertEquals(TunnelRate.ZERO, TunnelRate.between(counters(0, 0), counters(500, 500), 0L))
        assertEquals(TunnelRate.ZERO, TunnelRate.between(counters(0, 0), counters(500, 500), -1L))
    }

    @Test
    fun aRestartedBridgeCountsFromZeroAgainAndIsNotNegativeSpeed() {
        // The reading dropped below the previous one: the bridge was restarted.
        val rate = TunnelRate.between(counters(9_000, 90_000), counters(100, 200), 2_000L)
        assertEquals(TunnelRate.ZERO, rate)
    }

    @Test
    fun aFreshTrafficSnapshotIsQuiet() {
        val fresh = TunnelTraffic()
        assertEquals(TunnelRate.ZERO, fresh.rate)
        assertEquals(FlowVerdict.IDLE, fresh.verdict)
        assertEquals(0, fresh.position)
    }
}
