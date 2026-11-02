package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 59: the seven-second rule, driven with the counters a real tunnel shows (see `TunnelStall`). */
class TunnelStallTest {
    private fun counters(txPackets: Long, txBytes: Long, rxPackets: Long, rxBytes: Long) =
        TunnelCounters(txPackets, txBytes, rxPackets, rxBytes)

    /** Feeds cumulative [readings] one per second (the first is the reading at t = 1 s, the one before it is all zeros). */
    private fun run(readings: List<TunnelCounters>): List<StallStep> {
        var state = StallState()
        var previous = TunnelCounters.ZERO
        var previousAt = 0L
        val steps = ArrayList<StallStep>()
        for ((index, current) in readings.withIndex()) {
            val now = (index + 1) * TunnelStall.SAMPLE_MS
            val step = TunnelStall.step(state, previous, current, previousAt, now)
            steps += step
            state = step.state
            previous = current
            previousAt = now
        }
        return steps
    }

    @Test
    fun aBlockedConnectionIsGivenUpAfterSevenSeconds() {
        // The app opens ten connections in the first second (hello packets out, a few small acknowledgements back), then waits.
        val burst = counters(30, 18_000, 6, 400)
        val steps = run(List(8) { burst })
        assertEquals(StallVerdict.WATCHING, steps[0].verdict)
        assertTrue(steps.subList(0, 6).all { it.verdict == StallVerdict.WATCHING })
        assertEquals(StallVerdict.STALLED, steps[6].verdict)
        val report = steps[6].report
        assertNotNull(report)
        assertEquals(7_000L, report!!.ms)
        assertEquals(30L, report.txPackets)
        assertEquals(18_000L, report.txBytes)
        assertEquals(400L, report.rxBytes)
        // The episode is closed after a verdict: the next sample starts from nothing.
        assertFalse(steps[6].state.open)
    }

    @Test
    fun theWaitIsNotShorterThanSevenSeconds() {
        val burst = counters(30, 18_000, 6, 400)
        val steps = run(List(6) { burst })
        assertTrue(steps.none { it.verdict == StallVerdict.STALLED })
    }

    @Test
    fun aHealthyPageLoadClosesTheEpisodeAsWorking() {
        // The request goes out at the end of one sample, the answer arrives in the next ones.
        val steps = run(
            listOf(
                counters(12, 7_000, 3, 200),
                counters(20, 9_000, 120, 150_000),
                counters(25, 11_000, 300, 400_000)
            )
        )
        assertEquals(StallVerdict.WATCHING, steps[0].verdict)
        assertEquals(StallVerdict.WORKING, steps[1].verdict)
        assertFalse(steps[1].state.open)
        assertEquals(StallVerdict.NONE, steps[2].verdict)
    }

    @Test
    fun aSlowTrickleThatAddsUpIsWorkingNotStalled() {
        // The first second brings back next to nothing (so an episode opens), then 5 KB a second: slow, but it is data.
        val readings = (1..8).map { counters(it * 6L, it * 4_000L, it * 5L, (it - 1) * 5_000L + 500L) }
        val steps = run(readings)
        assertTrue(steps.none { it.verdict == StallVerdict.STALLED })
        assertEquals(StallVerdict.WATCHING, steps[0].verdict)
        assertEquals(StallVerdict.WORKING, steps[4].verdict)
    }

    @Test
    fun keepAlivesAndTheirRepliesNeverOpenAnEpisode() {
        val readings = (1..12).map { counters(it * 2L, it * 600L, it * 2L, it * 500L) }
        val steps = run(readings)
        assertTrue(steps.all { it.verdict == StallVerdict.NONE })
    }

    @Test
    fun aSampleThatBringsEnoughBackDoesNotOpenAnEpisode() {
        val steps = run(listOf(counters(10, 6_000, 12, 3_000)))
        assertEquals(StallVerdict.NONE, steps[0].verdict)
        assertFalse(steps[0].state.open)
    }

    @Test
    fun anAppThatSentOnlyAFewPacketsAndGaveUpIsNotBlamedOnTheStrategy() {
        // Three packets, then silence for ever: nothing proves the strategy is at fault, the episode closes quietly.
        val steps = run(List(9) { counters(3, 1_500, 1, 60) })
        assertEquals(StallVerdict.WATCHING, steps[0].verdict)
        assertTrue(steps.none { it.verdict == StallVerdict.STALLED })
        assertEquals(StallVerdict.NONE, steps[6].verdict)
        assertFalse(steps[6].state.open)
    }

    @Test
    fun aBridgeThatRestartedEndsTheEpisode() {
        val steps = run(
            listOf(
                counters(30, 18_000, 6, 400),
                counters(30, 18_000, 6, 400),
                counters(2, 900, 1, 60)
            )
        )
        assertEquals(StallVerdict.WATCHING, steps[1].verdict)
        assertEquals(StallVerdict.NONE, steps[2].verdict)
        assertFalse(steps[2].state.open)
    }

    @Test
    fun retriesDuringTheWaitAreCountedNotJustTheFirstBurst() {
        // A single hello at first, then a retry every second: eight packets by the seventh second is enough to be sure.
        val readings = (1..8).map { counters(it * 2L, it * 1_200L, it.toLong(), it * 60L) }
        val steps = run(readings)
        assertEquals(StallVerdict.STALLED, steps[6].verdict)
        assertNull(steps[5].report)
    }

    @Test
    fun theLimitsAreTheDocumentedOnes() {
        assertEquals(7_000L, TunnelStall.STALL_MS)
        assertEquals(1_000L, TunnelStall.SAMPLE_MS)
    }
}
