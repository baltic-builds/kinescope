package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch 61: the strategy that really carried the YouTube app's traffic leads the chain, and weak signals do not sink a good strategy.
 * The first test is the report of 2026-11-02 in miniature: the proven strategy was last, behind strategies with older and better test scores.
 */
class ProvenLeadTest {
    private val chain = listOf("s1", "s2", "s3", "s4", "s5", "s6")

    // The ratings of that report: stale synthetic tests rate the first four high, the proven one low.
    private val ratings = mapOf("s1" to 78, "s2" to 77, "s3" to 66, "s4" to 64, "s5" to 55, "s6" to 52)
    private val score: (String) -> Double = { (ratings[it] ?: 50) / 100.0 }

    @Test
    fun theProvenStrategyStaysInFrontWhenTheChainIsReorderedByRating() {
        val result = ChainPlanner.reorder(chain, score, pinned = null, proven = "s6", quarantined = emptySet())
        assertEquals(listOf("s6", "s1", "s2", "s3", "s4", "s5"), result)
    }

    @Test
    fun withoutProofTheRatingDecidesAsBefore() {
        val result = ChainPlanner.reorder(chain, score, pinned = null, proven = null, quarantined = emptySet())
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), result)
    }

    @Test
    fun aPinLeadsAndTheProvenStrategyIsSecond() {
        val result = ChainPlanner.reorder(chain, score, pinned = "s3", proven = "s6", quarantined = emptySet())
        assertEquals(listOf("s3", "s6", "s1", "s2", "s4", "s5"), result)
    }

    @Test
    fun aQuarantinedProvenStrategyDoesNotLead() {
        val result = ChainPlanner.reorder(chain, score, pinned = null, proven = "s6", quarantined = setOf("s6"))
        assertEquals("s1", result.first())
    }

    @Test
    fun aProvenStrategyThatIsNotInTheChainChangesNothing() {
        val result = ChainPlanner.reorder(chain, score, pinned = null, proven = "gone", quarantined = emptySet())
        assertEquals(chain, result)
    }

    @Test
    fun theChainTheTunnelStartsFromLeadsWithTheProvenStrategy() {
        assertEquals(listOf("s6", "s1", "s2", "s3", "s4", "s5"), ChainPlanner.order(chain, null, emptySet(), proven = "s6"))
        assertEquals(listOf("s3", "s6", "s1", "s2", "s4", "s5"), ChainPlanner.order(chain, "s3", emptySet(), proven = "s6"))
        // Quarantine still wins: a strategy that failed in the last ten minutes goes last, proven or not.
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), ChainPlanner.order(chain, null, setOf("s6"), proven = "s6"))
        // Without a proven strategy the old behaviour is unchanged.
        assertEquals(chain, ChainPlanner.order(chain, null, emptySet()))
    }

    @Test
    fun theRandomCheckNeverPicksTheProvenOrTheActiveStrategy() {
        val pool = RandomCheckPolicy.pool(chain, frozen = setOf("s5"), active = "s1", keep = "s6")
        assertEquals(listOf("s2", "s3", "s4"), pool)
        assertEquals(chain.size - 1, RandomCheckPolicy.pool(chain, emptySet(), "s1").size)
    }

    @Test
    fun aSoftFailureWeighsLessThanHalfOfARealOne() {
        assertTrue(StrategyOutcome.SOFT_BAD.weight < StrategyOutcome.REAL_BAD.weight / 2.0 + 0.001)
        assertFalse(StrategyOutcome.SOFT_BAD.good)
    }

    @Test
    fun oneStallCannotTurnAStrategyThatCarriedTrafficIntoABadOne() {
        val now = 1_000_000_000L
        var memory = StrategyMemory.record(emptyMap(), "a", StrategyOutcome.REAL_OK, now)
        memory = StrategyMemory.record(memory, "a", StrategyOutcome.REAL_OK, now)
        val afterStall = StrategyMemory.record(memory, "a", StrategyOutcome.SOFT_BAD, now)
        val afterRealFailure = StrategyMemory.record(memory, "a", StrategyOutcome.REAL_BAD, now)
        val stallScore = StrategyMemory.score(afterStall["a"], now)
        val realScore = StrategyMemory.score(afterRealFailure["a"], now)
        assertTrue("a stall must leave it above the middle: $stallScore", stallScore > StrategyMemory.NEUTRAL)
        assertTrue("a real failure weighs more: $stallScore vs $realScore", stallScore > realScore)
    }
}
