package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 53: what the router makes of a search, a success, a failure and a connection that has no chain. */
class StrategyRouterTest {
    private val ranked = listOf("r1", "r2", "r3")

    @Test
    fun aSearchChainIsOrderedByTheConnectionsRatingsNotOnlyBySearchRank() {
        // r3 has been carrying real traffic on this connection and is rated higher than the search ranked it.
        val ratings = mapOf("r1" to 80, "r2" to 70, "r3" to 90)
        val chain = StrategyRouter.afterSearch(ranked, emptySet(), ratings, null, null, false, emptyList(), 6)
        assertEquals(listOf("r3", "r1", "r2"), chain)
    }

    @Test
    fun aStrategyRatedWellOnThisConnectionJoinsEvenWhenTheSearchDidNotRankIt() {
        val ratings = mapOf("r1" to 80, "old" to 85)
        val chain = StrategyRouter.afterSearch(ranked, emptySet(), ratings, null, null, false, emptyList(), 6)
        assertEquals(listOf("old", "r1", "r2", "r3"), chain)
    }

    @Test
    fun aStrategyThatFailedThisSearchStaysOutWhateverItsOlderRating() {
        val ratings = mapOf("r1" to 80, "old" to 85)
        val chain = StrategyRouter.afterSearch(ranked, setOf("old"), ratings, null, null, false, emptyList(), 6)
        assertEquals(ranked, chain)
    }

    @Test
    fun aStrategyRatedBelowTheLineIsNotAddedFromTheRecord() {
        val ratings = mapOf("r1" to 80, "weak" to 30)
        val chain = StrategyRouter.afterSearch(ranked, emptySet(), ratings, null, null, false, emptyList(), 6)
        assertEquals(ranked, chain)
    }

    @Test
    fun theWinnerLeadsAndTheOthersFollowByRating() {
        val chain = listOf("a", "b", "c", "d")
        val ratings = mapOf("a" to 60, "b" to 70, "c" to 65, "d" to 90)
        assertEquals(listOf("c", "d", "b", "a"), StrategyRouter.afterSuccess(chain, "c", ratings, 6))
    }

    @Test
    fun aFailureMovesTheLoserBelowTheStrategiesRatedHigherThanItNow() {
        val chain = listOf("a", "b", "c")
        // a had tested best but has just failed for real: its rating fell to 66, between b (80) and c (60).
        val ratings = mapOf("a" to 66, "b" to 80, "c" to 60)
        assertEquals(listOf("b", "a", "c"), StrategyRouter.afterFailure(chain, "a", ratings))
    }

    @Test
    fun aConnectionWithoutAChainStartsFromTheOthersAndOwnChainIsNeverReplaced() {
        val other = listOf("w1", "w2", "w3", "w4", "w5", "w6", "w7")
        assertEquals(other.take(6), StrategyRouter.seed(emptyList(), other, 6))
        assertEquals(listOf("m1"), StrategyRouter.seed(listOf("m1"), other, 6))
        assertEquals(emptyList<String>(), StrategyRouter.seed(emptyList(), emptyList(), 6))
    }

    @Test
    fun aStrategyWithNoRatingSitsInTheMiddle() {
        val score = StrategyRouter.scorer(mapOf("a" to 100))
        assertEquals(1.0, score("a"), 1e-9)
        assertEquals(0.5, score("unknown"), 1e-9)
    }
}
