package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 57: the coverage of real hosts feeds the score and the ranking, and the search wires the probe in only where it belongs. */
class CoverageScoreTest {
    private class Session(override val port: Int) : BypassSession {
        override fun close() {}
    }

    private fun passing(
        line: String,
        coveragePassed: Int = 0,
        coverageTotal: Int = 0,
        loadPassed: Int = 6,
        deepOk: Boolean = true
    ) = StrategyResult(
        line, started = true, passed = 3, total = 3, requiredPassed = true, deepOk = deepOk, throughputKbps = 800,
        loadPassed = loadPassed, loadTotal = 6, handshakeMs = 300,
        coveragePassed = coveragePassed, coverageTotal = coverageTotal
    )

    @Test
    fun aResultWithNoCoverageScoresExactlyAsBefore() {
        assertEquals(100, passing("a").score())
        assertEquals(0, passing("a").coveragePercent)
    }

    @Test
    fun coverageOfRealHostsMovesTheHostsShareOfTheScore() {
        val full = passing("a", coveragePassed = 30, coverageTotal = 30).score()
        val half = passing("a", coveragePassed = 15, coverageTotal = 30).score()
        val none = passing("a", coveragePassed = 0, coverageTotal = 30).score()
        assertEquals(100, full)
        assertTrue("$full > $half", full > half)
        assertTrue("$half > $none", half > none)
        assertEquals(100 - 25 + 25 * 3 / 33, none)
    }

    @Test
    fun aStrategyThatReachesTheVideoNodesOutranksOneThatDoesNotWhenTheRestIsEqual() {
        val ranked = DpiStrategySearch.ranked(
            listOf(
                passing("thumbnails-only", coveragePassed = 4, coverageTotal = 30),
                passing("reaches-video", coveragePassed = 26, coverageTotal = 30)
            )
        )
        assertEquals(listOf("reaches-video", "thumbnails-only"), ranked.map { it.line })
    }

    @Test
    fun coverageDoesNotOverruleHoldingUnderLoad() {
        val ranked = DpiStrategySearch.ranked(
            listOf(
                passing("wide-but-fragile", coveragePassed = 30, coverageTotal = 30, loadPassed = 2),
                passing("narrow-but-solid", coveragePassed = 10, coverageTotal = 30, loadPassed = 6)
            )
        )
        assertEquals("narrow-but-solid", ranked.first().line)
    }

    @Test
    fun searchRunsTheCoverageProbeOnlyForAStrategyWhoseRealTransferWorked() {
        val calls = mutableListOf<Int>()
        val search = DpiStrategySearch(
            startEngine = { Session(7) },
            probeHost = { _, _ -> true },
            deepProbe = { port -> if (port == 7) 500 else null },
            loadProbe = { LoadResult(total = 6, passed = 6, medianHandshakeMs = 200, kbps = 900) },
            coverageProbe = { port ->
                calls += port
                CoverageResult(total = 30, passed = 24, edgeTotal = 19, edgePassed = 14, failures = "tls=4,timeout=2")
            }
        )
        val result = search.run(listOf("-d1"), isCancelled = { false }, onProgress = {}).single()
        assertEquals(listOf(7), calls)
        assertEquals(24, result.coveragePassed)
        assertEquals(30, result.coverageTotal)
        assertEquals(14, result.edgePassed)
        assertEquals(19, result.edgeTotal)
        assertEquals("tls=4,timeout=2", result.coverageWhy)
        assertEquals(80, result.coveragePercent)
        assertTrue(result.works)

        val noTransfer = DpiStrategySearch(
            startEngine = { Session(8) },
            probeHost = { _, _ -> true },
            deepProbe = { null },
            coverageProbe = { error("must not run without a working transfer") }
        )
        val skipped = noTransfer.run(listOf("-d1"), isCancelled = { false }, onProgress = {}).single()
        assertEquals(0, skipped.coverageTotal)
        assertFalse(skipped.works)
    }

    @Test
    fun aFailingCoverageProbeLeavesTheResultUnmeasuredInsteadOfFailingTheStrategy() {
        val search = DpiStrategySearch(
            startEngine = { Session(9) },
            probeHost = { _, _ -> true },
            deepProbe = { 400 },
            coverageProbe = { throw IllegalStateException("boom") }
        )
        val result = search.run(listOf("-d1"), isCancelled = { false }, onProgress = {}).single()
        assertTrue(result.works)
        assertEquals(0, result.coverageTotal)
    }
}
