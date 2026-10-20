package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 47: the record and the chain cap under awkward numbers and clocks. */
class StrategyMemoryRobustnessTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val t0 = 500L * day

    @Test
    fun veryLargeAndVerySmallNumbersSurviveTheTextForm() {
        val memory = mapOf(
            "tiny" to LearnedStrategy(3.2e-8, 1.0e-12, 5L, 7L),
            "huge" to LearnedStrategy(1.5e12, 2.5e9, 5L, 7L)
        )
        assertEquals(memory, StrategyMemory.parse(StrategyMemory.serialize(memory)))
    }

    @Test
    fun aClockThatJumpsBackNeverProducesAnInvalidScore() {
        val memory = StrategyMemory.record(emptyMap(), "a", StrategyOutcome.REAL_OK, t0)
        val back = StrategyMemory.score(memory["a"], t0 - 30 * day)
        assertTrue(back.isFinite() && back > StrategyMemory.NEUTRAL && back <= 1.0)
        assertEquals(StrategyMemory.score(memory["a"], t0), back, 1e-9)
        val again = StrategyMemory.record(memory, "a", StrategyOutcome.REAL_OK, t0 - 30 * day)
        assertTrue(StrategyMemory.score(again["a"], t0 - 30 * day).isFinite())
    }

    @Test
    fun aClockThatJumpsFarForwardFadesEverythingToNeutralAndStaysFinite() {
        val memory = StrategyMemory.record(emptyMap(), "a", StrategyOutcome.REAL_OK, t0)
        val far = t0 + 36_500L * day
        assertEquals(StrategyMemory.NEUTRAL, StrategyMemory.score(memory["a"], far), 1e-9)
        assertTrue(StrategyMemory.good(memory, far).isEmpty())
        val next = StrategyMemory.record(memory, "b", StrategyOutcome.REAL_OK, far)
        assertFalse("a" in next)
        assertTrue("b" in next)
    }

    @Test
    fun theChainIsNeverLongerThanTheSingleCap() {
        val ranked = (1..10).map { "r$it" }
        val learned = (1..10).map { "l$it" }
        val merged = ChainPlanner.merge(
            ranked, emptySet(), null, null, false, ChainPlanner.MAX_CHAIN,
            fill = (1..10).map { "f$it" }, learned = learned
        )
        assertEquals(ChainPlanner.MAX_CHAIN, merged.size)
        assertEquals(6, ChainPlanner.MAX_CHAIN)
    }
}
