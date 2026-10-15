package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 44: what the traffic of the YouTube app through the tunnel looks like in the bridge's counters. */
class TunnelFlowTest {
    private fun counters(txPackets: Long, txBytes: Long, rxPackets: Long, rxBytes: Long) =
        TunnelCounters(txPackets, txBytes, rxPackets, rxBytes)

    @Test
    fun statsArrayMapsToCountersInTheBridgesOrder() {
        assertEquals(counters(1, 2, 3, 4), TunnelCounters.fromStats(longArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun anUnusableStatsArrayGivesNoCounters() {
        assertNull(TunnelCounters.fromStats(null))
        assertNull(TunnelCounters.fromStats(longArrayOf(1, 2, 3)))
    }

    @Test
    fun aQuietWindowIsIdleEvenWhenNothingCameBack() {
        val verdict = TunnelFlow.judge(TunnelCounters.ZERO, counters(5, 4_000, 5, 300))
        assertEquals(FlowVerdict.IDLE, verdict)
    }

    @Test
    fun aHealthyPageLoadIsFlowing() {
        // A few requests out, a lot of data back.
        val verdict = TunnelFlow.judge(TunnelCounters.ZERO, counters(400, 60_000, 900, 1_200_000))
        assertEquals(FlowVerdict.FLOWING, verdict)
    }

    @Test
    fun anAppThatKeepsSendingWhileOnlyAcknowledgementsComeBackIsOneWay() {
        // 40 packets out (hellos and retransmits, about 600 bytes each), 40 bare acknowledgements back.
        val verdict = TunnelFlow.judge(TunnelCounters.ZERO, counters(40, 24_000, 40, 2_100))
        assertEquals(FlowVerdict.ONE_WAY, verdict)
    }

    @Test
    fun aBalancedPingAndReplyIsNotOneWay() {
        val verdict = TunnelFlow.judge(TunnelCounters.ZERO, counters(24, 7_200, 24, 7_000))
        assertEquals(FlowVerdict.FLOWING, verdict)
    }

    @Test
    fun enoughBytesBackAlwaysMeansFlowingNoMatterHowMuchWasSent() {
        val verdict = TunnelFlow.judge(TunnelCounters.ZERO, counters(500, 900_000, 300, 24_000))
        assertEquals(FlowVerdict.FLOWING, verdict)
    }

    @Test
    fun onlyTheChangeBetweenTwoReadingsIsJudged() {
        val before = counters(1_000, 500_000, 2_000, 4_000_000)
        val after = counters(1_040, 524_000, 2_040, 4_002_100)
        assertEquals(FlowVerdict.ONE_WAY, TunnelFlow.judge(before, after))
    }

    @Test
    fun aRestartedBridgeStartsFromZeroAndIsIdle() {
        val before = counters(1_000, 500_000, 2_000, 4_000_000)
        val afterRestart = counters(30, 18_000, 30, 1_800)
        assertEquals(FlowVerdict.IDLE, TunnelFlow.judge(before, afterRestart))
    }

    @Test
    fun theRotationNeedsThreeOneWayWindowsInARow() {
        var streak = 0
        streak = TunnelFlow.streakAfter(streak, FlowVerdict.ONE_WAY)
        assertFalse(TunnelFlow.shouldRotate(streak))
        streak = TunnelFlow.streakAfter(streak, FlowVerdict.ONE_WAY)
        assertFalse(TunnelFlow.shouldRotate(streak))
        streak = TunnelFlow.streakAfter(streak, FlowVerdict.ONE_WAY)
        assertTrue(TunnelFlow.shouldRotate(streak))
    }

    @Test
    fun aFlowingOrIdleWindowEndsTheRun() {
        assertEquals(0, TunnelFlow.streakAfter(2, FlowVerdict.FLOWING))
        assertEquals(0, TunnelFlow.streakAfter(2, FlowVerdict.IDLE))
    }
}
