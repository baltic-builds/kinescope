package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 62: the check that ends at three good strategies, and the cold-start order that uses the ledger. */
class CheckGoalsTest {
    /** A strategy that carries real traffic; [load] of 6 connections complete, which sets the score (6 -> 100, 4 -> 85, 3 -> 77). */
    private fun result(line: String, load: Int = 6, works: Boolean = true) = StrategyResult(
        line = line, started = true, passed = 3, total = 3, requiredPassed = works, deepOk = works,
        throughputKbps = 800, loadPassed = load, loadTotal = 6, handshakeMs = 100
    )

    @Test
    fun theScoresTheTestsRelyOn() {
        assertEquals(100, result("a", 6).score())
        assertEquals(85, result("a", 4).score())
        assertEquals(77, result("a", 3).score())
    }

    @Test
    fun theGoodGoalNeedsThreeWorkingStrategiesThatScoreEightyOrMore() {
        val goal = SearchGoal.GOOD
        assertEquals(3, goal.working)
        assertEquals(80, SearchGoal.GOOD_SCORE)
        val twoGood = listOf(result("a", 6), result("b", 4))
        assertFalse(goal.reached(twoGood))
        assertTrue(goal.reached(twoGood + result("c", 5)))
        // A strategy that works but scores 77 does not count as a good one.
        assertFalse(goal.reached(twoGood + result("d", 3)))
        // One that fails does not count either.
        assertFalse(goal.reached(twoGood + result("e", 6, works = false)))
    }

    @Test
    fun theOldGoalsKeepTheirMeaning() {
        assertEquals(0, SearchGoal.STANDARD.minScore)
        assertEquals(0, SearchGoal.QUICK.minScore)
        assertTrue(SearchGoal.QUICK.reached(listOf(result("a", 3), result("b", 3))))
    }

    @Test
    fun theProgressCountsTheGoodOnes() {
        val progress = SearchProgress(3, 10, "x", listOf(result("a", 6), result("b", 3), result("c", 6, works = false)))
        assertEquals(2, progress.working)
        assertEquals(1, progress.good)
    }

    @Test
    fun aSearchWithTheGoodGoalStopsAtTheThirdGoodStrategy() {
        var next = 41000
        val byPort = HashMap<Int, String>()
        val lines = listOf("-d1", "-d2", "-d3", "-d4", "-d5")
        val search = DpiStrategySearch(
            startEngine = { args ->
                val port = next++
                byPort[port] = args.joinToString(" ")
                object : BypassSession {
                    override val port = port
                    override fun close() = Unit
                }
            },
            probeHost = { _, _ -> true },
            deepProbe = { 800 },
            loadProbe = { port -> LoadResult(6, if (byPort[port]!!.contains("-d2")) 3 else 6, 100, 700) }
        )
        val results = search.run(lines, { false }, {}, stopAfterFullPasses = Int.MAX_VALUE, goal = SearchGoal.GOOD)
        // "-d2" scores 77 and does not count, so the third good one is the fourth strategy tried.
        assertEquals(4, results.size)
        assertEquals(3, results.count { it.works && it.score() >= SearchGoal.GOOD_SCORE })
    }

    @Test
    fun theRankingBreaksTiesAndOrdersTheUntestedByTheirLongRecord() {
        val candidates = listOf("a", "b", "c", "d", "e")
        val ratings = mapOf("a" to 70, "b" to 70)
        val history = mapOf("b" to 90, "a" to 60, "d" to 80, "e" to 65)
        val order = StrategyRank.order(candidates, ratings, emptyMap(), emptyList(), history)
        // Equal ratings: the better record first. Never tested here: the better record first, then the list order.
        assertEquals(listOf("b", "a", "d", "e", "c"), order)
        // Without a record nothing changes.
        assertEquals(listOf("a", "b", "c", "d", "e"), StrategyRank.order(candidates, ratings, emptyMap(), emptyList()))
    }

    @Test
    fun aClassWithoutAChainStartsFromItsOwnBestThenFromTheOtherClass() {
        assertEquals(listOf("x", "y", "z"), StrategyRouter.seed(emptyList(), listOf("y", "z"), 6, learned = listOf("x", "y")))
        assertEquals(listOf("x", "y"), StrategyRouter.seed(emptyList(), listOf("y", "z"), 2, learned = listOf("x")))
        assertEquals(listOf("y", "z"), StrategyRouter.seed(emptyList(), listOf("y", "z"), 6))
        assertEquals(listOf("own"), StrategyRouter.seed(listOf("own"), listOf("y"), 6, learned = listOf("x")))
    }
}
