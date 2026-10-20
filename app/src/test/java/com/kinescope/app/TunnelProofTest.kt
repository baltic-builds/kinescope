package com.kinescope.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 46: when a 10 s window of the YouTube app's traffic counts as proof that the strategy carries real data. */
class TunnelProofTest {
    private fun window(txPackets: Long, txBytes: Long, rxPackets: Long, rxBytes: Long) =
        TunnelFlow.proves(TunnelCounters.ZERO, TunnelCounters(txPackets, txBytes, rxPackets, rxBytes))

    @Test
    fun aWindowThatBroughtBackRealDataIsProof() {
        assertTrue(window(200, 50_000, 150, 200_000))
        assertTrue(window(200, 50_000, 150, TunnelFlow.PROOF_RX_BYTES))
    }

    @Test
    fun aBalancedTrickleIsFlowingButNotProof() {
        assertFalse(window(200, 20_000, 150, 30_000))
        assertFalse(window(200, 50_000, 150, TunnelFlow.PROOF_RX_BYTES - 1))
    }

    @Test
    fun aQuietWindowIsNotProof() {
        assertFalse(window(5, 2_000, 5, 900_000))
    }

    @Test
    fun aOneWayWindowIsNotProof() {
        assertFalse(window(200, 100_000, 3, 1_000))
    }

    @Test
    fun aRestartedBridgeIsNotProof() {
        val before = TunnelCounters(100, 50_000, 100, 500_000)
        val after = TunnelCounters(30, 10_000, 30, 100_000)
        assertFalse(TunnelFlow.proves(before, after))
    }
}
