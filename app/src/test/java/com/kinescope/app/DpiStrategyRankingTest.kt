package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 33: strategies are ranked by a real transfer, not by their position in the list. */
class DpiStrategyRankingTest {
    private class Session(override val port: Int) : BypassSession {
        override fun close() {}
    }

    private fun result(
        line: String,
        deepOk: Boolean = false,
        passed: Int = 3,
        kbps: Int = 0,
        requiredPassed: Boolean = true
    ) = StrategyResult(
        line,
        started = true,
        passed = passed,
        total = 3,
        requiredPassed = requiredPassed,
        deepOk = deepOk,
        throughputKbps = kbps
    )

    @Test
    fun realTransferBeatsListOrderThenHostsThenSpeed() {
        val ranked = DpiStrategySearch.ranked(
            listOf(
                result("first-in-list-no-transfer"),
                result("slow", deepOk = true, kbps = 100),
                result("fast", deepOk = true, kbps = 900),
                result("fewer-hosts", deepOk = true, passed = 2, kbps = 5_000),
                result("did-not-pass", requiredPassed = false)
            )
        )
        assertEquals(
            listOf("fast", "slow", "fewer-hosts", "first-in-list-no-transfer"),
            ranked.map { it.line }
        )
        assertEquals("fast", ranked.first().line)
        assertFalse(ranked.any { it.line == "did-not-pass" })
    }

    @Test
    fun withoutMeasurementsTheSearchOrderIsKept() {
        val ranked = DpiStrategySearch.ranked(listOf(result("a"), result("b"), result("c")))
        assertEquals(listOf("a", "b", "c"), ranked.map { it.line })
    }

    @Test
    fun searchRunsTheRealTransferOnlyForStrategiesThatPassedTheProbe() {
        val deepCalls = mutableListOf<Int>()
        val search = DpiStrategySearch(
            startEngine = { args -> Session(if (args.first() == "-d1") 1 else 2) },
            probeHost = { port, _ -> port == 2 },
            hosts = listOf("a.example", "b.example"),
            deepProbe = { port ->
                deepCalls += port
                500
            }
        )
        val results = search.run(listOf("-d1", "-o1"), { false }, {}, stopAfterFullPasses = Int.MAX_VALUE)
        assertEquals(listOf(2), deepCalls)
        assertFalse(results[0].deepOk)
        assertTrue(results[1].deepOk)
        assertEquals(500, results[1].throughputKbps)
        assertEquals(listOf("-o1"), DpiStrategySearch.ranked(results).map { it.line })
    }

    @Test
    fun aStrategyWhoseTransferFailedIsKeptButRankedBelowOneThatWorked() {
        val search = DpiStrategySearch(
            startEngine = { args -> Session(if (args.first() == "-d1") 1 else 2) },
            probeHost = { _, _ -> true },
            hosts = listOf("a.example", "b.example"),
            deepProbe = { port -> if (port == 1) null else 300 }
        )
        val results = search.run(listOf("-d1", "-o1"), { false }, {}, stopAfterFullPasses = Int.MAX_VALUE)
        assertEquals(listOf("-o1", "-d1"), DpiStrategySearch.ranked(results).map { it.line })
    }
}
