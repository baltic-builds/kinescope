package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 46: the chain a search stores and a download promotes, ordered by what each strategy did in real use. */
class ChainPlannerLearningTest {
    private val ranked = listOf("r1", "r2", "r3")
    private fun scores(vararg pairs: Pair<String, Double>): (String) -> Double {
        val map = pairs.toMap()
        return { line -> map[line] ?: StrategyMemory.NEUTRAL }
    }

    @Test
    fun theWholePoolIsOrderedByScoreAndTheSearchRankingBreaksTies() {
        val merged = ChainPlanner.merge(ranked, emptySet(), null, null, false, 6, score = scores("r3" to 0.9, "r2" to 0.2))
        assertEquals(listOf("r3", "r1", "r2"), merged)
    }

    @Test
    fun aLearnedStrategyJoinsTheChainEvenWhenTheSearchDidNotRankIt() {
        val merged = ChainPlanner.merge(
            ranked, emptySet(), null, null, false, 6,
            learned = listOf("old"), score = scores("old" to 0.8)
        )
        assertEquals(listOf("old", "r1", "r2", "r3"), merged)
    }

    @Test
    fun aLearnedStrategyThatTheSearchTestedAndFailedStaysOut() {
        val merged = ChainPlanner.merge(
            ranked, setOf("old"), null, null, false, 6,
            learned = listOf("old"), score = scores("old" to 0.8)
        )
        assertEquals(ranked, merged)
    }

    @Test
    fun aStrategyWithAFailingHistoryDropsBelowOnesNothingIsKnownAbout() {
        val merged = ChainPlannerWithBad.merge()
        assertEquals(listOf("r2", "r3", "r1"), merged)
    }

    @Test
    fun aPinnedStrategyStillLeadsAndTheChainIsStillCapped() {
        val merged = ChainPlanner.merge(
            ranked, emptySet(), null, "p", false, 3,
            learned = listOf("old"), score = scores("old" to 0.8)
        )
        assertEquals(listOf("p", "old", "r1"), merged)
    }

    @Test
    fun theProvenLeadAndTheStoppedSearchFillBehaveAsBefore() {
        val merged = ChainPlanner.merge(
            listOf("n1"), setOf("old2"), "proven", null, true, 6,
            fill = listOf("old1", "old2"), learned = listOf("l1"), score = scores("l1" to 0.7)
        )
        assertEquals(listOf("proven", "l1", "n1", "old1"), merged)
    }

    @Test
    fun aWinnerLeadsAndTheOthersFollowByScore() {
        val chain = listOf("a", "b", "c", "d")
        val promoted = ChainPlanner.promote(chain, "c", scores("d" to 0.9, "a" to 0.3), 6)
        assertEquals(listOf("c", "d", "b", "a"), promoted)
    }

    @Test
    fun aPromotionKeepsThePreviousOrderForEqualScoresAndRespectsTheCap() {
        val chain = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b"), ChainPlanner.promote(chain, "c", scores(), 3))
    }

    @Test
    fun aWinnerThatIsNotInTheChainChangesNothing() {
        val chain = listOf("a", "b")
        assertEquals(chain, ChainPlanner.promote(chain, "z", scores("b" to 0.9), 6))
    }

    /** Keeps the "bad history" case readable: r1 failed in real use, the others are unknown. */
    private object ChainPlannerWithBad {
        fun merge(): List<String> = ChainPlanner.merge(
            listOf("r1", "r2", "r3"), emptySet(), null, null, false, 6,
            score = { line -> if (line == "r1") 0.2 else StrategyMemory.NEUTRAL }
        )
    }
}
