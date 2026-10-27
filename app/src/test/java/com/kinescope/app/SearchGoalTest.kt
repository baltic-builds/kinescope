package com.kinescope.app

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 55: a check stops at five working strategies, three of them perfect. */
class SearchGoalTest {
    private fun result(line: String, hosts: Int = 3, passed: Int = 3, deep: Boolean = true, load: Int = 6, loadTotal: Int = 6) =
        StrategyResult(
            line,
            started = true,
            passed = passed,
            total = hosts,
            requiredPassed = passed == hosts,
            deepOk = deep,
            throughputKbps = if (deep) 900 else 0,
            loadPassed = load,
            loadTotal = loadTotal
        )

    @Test
    fun aStrategyWorksWhenItCarriesARealTransfer() {
        assertTrue(result("-d1").works)
        assertFalse(result("-d1", deep = false).works)
        assertFalse(result("-d1", passed = 2).works)
    }

    @Test
    fun perfectMeansEveryHostAndEveryConnectionCompleted() {
        assertTrue(result("-d1").perfect)
        assertFalse(result("-d1", load = 5).perfect)
        assertFalse(result("-d1", loadTotal = 0, load = 0).perfect)
        assertFalse(result("-d1", deep = false).perfect)
    }

    @Test
    fun theStandardGoalNeedsFiveWorkingAndThreePerfect() {
        val goal = SearchGoal.STANDARD
        assertEquals(5, goal.working)
        assertEquals(3, goal.perfect)
        val three = List(3) { result("-p$it") }
        val twoLame = List(2) { result("-l$it", load = 5) }
        assertTrue(goal.reached(three + twoLame))
        // Five working but only two of them perfect: not yet.
        assertFalse(goal.reached(List(2) { result("-p$it") } + List(3) { result("-l$it", load = 5) }))
        // Three perfect but only three working: not yet.
        assertFalse(goal.reached(three))
        // A strategy that fails does not count.
        assertFalse(goal.reached(three + twoLame.take(1) + result("-x", passed = 0)))
    }

    @Test
    fun theQuickGoalOnlyNeedsTwoWorkingStrategies() {
        assertFalse(SearchGoal.QUICK.reached(listOf(result("-l", load = 5))))
        assertTrue(SearchGoal.QUICK.reached(listOf(result("-l", load = 5), result("-m", load = 4))))
    }

    private class Session(override val port: Int) : BypassSession {
        override fun close() = Unit
    }

    /** A search over [lines] where every host passes; [perfect] names the lines whose load test completes in full. */
    private fun search(perfect: Set<String>, started: CopyOnWriteArrayList<String>): DpiStrategySearch {
        var next = 41000
        val byPort = HashMap<Int, String>()
        return DpiStrategySearch(
            startEngine = { args ->
                val port = next++
                byPort[port] = args.joinToString(" ")
                started += args.joinToString(" ")
                Session(port)
            },
            probeHost = { _, _ -> true },
            hosts = listOf("a.example", "b.example", "c.example"),
            deepProbe = { 900 },
            loadProbe = { port ->
                LoadResult(total = 6, passed = if (byPort[port] in perfect) 6 else 5, medianHandshakeMs = 100, kbps = 900)
            }
        )
    }

    @Test
    fun theSearchStopsTheMomentTheGoalIsReached() {
        val started = CopyOnWriteArrayList<String>()
        val lines = (1..9).map { "-d$it" }
        val results = search(perfect = setOf("-d1", "-d2", "-d3"), started = started)
            .run(lines, { false }, {}, stopAfterFullPasses = Int.MAX_VALUE, goal = SearchGoal.STANDARD)
        assertEquals(lines.take(5), results.map { it.line })
        assertEquals(lines.take(5), started.toList())
    }

    @Test
    fun theSearchKeepsGoingWhileTheThirdPerfectOneIsMissing() {
        val started = CopyOnWriteArrayList<String>()
        val lines = (1..9).map { "-d$it" }
        // The perfect ones come late: 5 working strategies exist after the 5th, the third perfect one only at the 8th.
        val results = search(perfect = setOf("-d6", "-d7", "-d8"), started = started)
            .run(lines, { false }, {}, stopAfterFullPasses = Int.MAX_VALUE, goal = SearchGoal.STANDARD)
        assertEquals(lines.take(8), results.map { it.line })
    }

    @Test
    fun aListThatRunsOutEndsTheSearchWithWhatItFound() {
        val started = CopyOnWriteArrayList<String>()
        val results = search(perfect = emptySet(), started = started)
            .run(listOf("-d1", "-d2"), { false }, {}, stopAfterFullPasses = Int.MAX_VALUE, goal = SearchGoal.STANDARD)
        assertEquals(2, results.size)
        assertFalse(SearchGoal.STANDARD.reached(results))
    }

    @Test
    fun withoutAGoalTheOldFirstPassStopStillApplies() {
        val started = CopyOnWriteArrayList<String>()
        val results = search(perfect = emptySet(), started = started).run(listOf("-d1", "-d2", "-d3"), { false }, {})
        assertEquals(listOf("-d1"), results.map { it.line })
    }
}
