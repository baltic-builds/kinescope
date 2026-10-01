package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 35: strategies are ranked by simultaneous connections first, and the load probe is wired in. */
class DpiLoadRankingTest {
    private class Session(override val port: Int) : BypassSession {
        override fun close() {}
    }

    private fun result(
        line: String,
        deepOk: Boolean = true,
        kbps: Int = 500,
        loadPassed: Int = 0,
        loadTotal: Int = 0,
        handshakeMs: Int = 0
    ) = StrategyResult(
        line, started = true, passed = 3, total = 3, requiredPassed = true, deepOk = deepOk,
        throughputKbps = kbps, loadPassed = loadPassed, loadTotal = loadTotal, handshakeMs = handshakeMs
    )

    @Test
    fun aStrategyThatHoldsUnderLoadBeatsAFasterSingleTransfer() {
        val ranked = DpiStrategySearch.ranked(
            listOf(
                result("fast-alone", kbps = 5_000, loadPassed = 2, loadTotal = 6, handshakeMs = 3_000),
                result("steady", kbps = 400, loadPassed = 6, loadTotal = 6, handshakeMs = 450),
                result("untested-load", kbps = 9_000)
            )
        )
        assertEquals(listOf("steady", "fast-alone", "untested-load"), ranked.map { it.line })
        assertTrue(ranked.first().loadOk)
        assertFalse(ranked.first { it.line == "fast-alone" }.loadOk)
    }

    @Test
    fun lowerHandshakeLatencyRanksHigherAmongEqualLoads() {
        val ranked = DpiStrategySearch.ranked(
            listOf(
                result("slow-hs", loadPassed = 6, loadTotal = 6, handshakeMs = 2_500),
                result("quick-hs", loadPassed = 6, loadTotal = 6, handshakeMs = 300)
            )
        )
        assertEquals(listOf("quick-hs", "slow-hs"), ranked.map { it.line })
    }

    @Test
    fun theScoreIsZeroWithoutAFullPassAndBoundedOtherwise() {
        val failed = StrategyResult("x", started = true, passed = 1, total = 3, requiredPassed = false)
        assertEquals(0, failed.score())
        val perfect = result("p", loadPassed = 6, loadTotal = 6, handshakeMs = 100)
        assertEquals(100, perfect.score())
        val noLoad = result("n")
        assertTrue(noLoad.score() in 1..99)
    }

    @Test
    fun theLoadProbeRunsOnlyForStrategiesWhoseSingleTransferWorked() {
        val loadCalls = mutableListOf<Int>()
        val search = DpiStrategySearch(
            startEngine = { args -> Session(if (args.first() == "-d1") 1 else if (args.first() == "-o1") 2 else 3) },
            probeHost = { _, _ -> true },
            hosts = listOf("a.example", "b.example"),
            deepProbe = { port -> if (port == 2) null else 300 },
            loadProbe = { port ->
                loadCalls += port
                LoadResult(total = 6, passed = if (port == 1) 6 else 3, medianHandshakeMs = 400, kbps = 900)
            }
        )
        val results = search.run(listOf("-d1", "-o1", "-s1"), { false }, {}, stopAfterFullPasses = Int.MAX_VALUE)
        assertEquals(listOf(1, 3), loadCalls)
        assertTrue(results[0].loadOk)
        assertFalse(results[1].deepOk)
        assertEquals(0, results[1].loadTotal)
        assertFalse(results[2].loadOk)
        assertEquals(listOf("-d1", "-s1", "-o1"), DpiStrategySearch.ranked(results).map { it.line })
    }

    @Test
    fun theSearchWaitsForItsTurnBeforeEachStrategy() {
        var waits = 0
        val search = DpiStrategySearch(
            startEngine = { Session(1) },
            probeHost = { _, _ -> true },
            hosts = listOf("a.example")
        )
        search.run(listOf("-d1", "-o1"), { false }, {}, stopAfterFullPasses = Int.MAX_VALUE, waitIfPaused = { waits++ })
        assertEquals(2, waits)
    }
}
