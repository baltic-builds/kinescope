package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 62: the strategy ledger (long memory of tests, real use and usage time per connection class). */
class StrategyLedgerTest {
    private val day = 86_400_000L
    private val dateOf: (Long) -> String = { if (it <= 0L) "-" else "d" + it / day }

    private fun tests(vararg pairs: Pair<Boolean, Int>) = pairs.map { LedgerTest("-d1", it.first, it.second) }

    @Test
    fun testsAddUpAndAFailedTestCountsZeroInTheMean() {
        var ledger = StrategyLedger.recordTests(emptyMap(), "wifi", tests(true to 100), 1 * day)
        ledger = StrategyLedger.recordTests(ledger, "wifi", tests(true to 80), 2 * day)
        ledger = StrategyLedger.recordTests(ledger, "wifi", tests(false to 0), 3 * day)
        val entry = ledger.getValue(StrategyLedger.key("wifi", "-d1"))
        assertEquals(3, entry.tests)
        assertEquals(2, entry.testOk)
        assertEquals(60, entry.avgScore)
        assertEquals(100, entry.bestScore)
        assertEquals(0, entry.lastScore)
        assertEquals(1 * day, entry.firstAt)
        assertEquals(3 * day, entry.lastUsedAt)
        assertEquals(66, entry.ratePercent)
    }

    @Test
    fun theTwoClassesKeepSeparateRecords() {
        var ledger = StrategyLedger.recordTests(emptyMap(), "wifi", tests(true to 90), day)
        ledger = StrategyLedger.recordTests(ledger, "mobile", tests(false to 0), day)
        assertEquals(2, ledger.size)
        assertEquals(1, ledger.getValue(StrategyLedger.key("wifi", "-d1")).testOk)
        assertEquals(0, ledger.getValue(StrategyLedger.key("mobile", "-d1")).testOk)
    }

    @Test
    fun realOutcomesAndUsageTimeAreRecordedAndAShortRunIsNot() {
        var ledger = StrategyLedger.recordReal(emptyMap(), "wifi", "-d1", LedgerReal.OK, day)
        ledger = StrategyLedger.recordReal(ledger, "wifi", "-d1", LedgerReal.BAD, day)
        ledger = StrategyLedger.recordReal(ledger, "wifi", "-d1", LedgerReal.SOFT, day)
        ledger = StrategyLedger.recordUse(ledger, "wifi", "-d1", 90_000L, 2 * day)
        val unchanged = StrategyLedger.recordUse(ledger, "wifi", "-d1", 500L, 3 * day)
        assertTrue(unchanged === ledger)
        val entry = ledger.getValue(StrategyLedger.key("wifi", "-d1"))
        assertEquals(1, entry.realOk)
        assertEquals(1, entry.realBad)
        assertEquals(1, entry.softBad)
        assertEquals(90_000L, entry.usedMs)
        assertEquals(2 * day, entry.lastUsedAt)
        assertEquals(33, entry.ratePercent)
    }

    @Test
    fun aLineThatCannotBeStoredIsIgnored() {
        val ledger = StrategyLedger.recordReal(emptyMap(), "wifi", "a\tb", LedgerReal.OK, day)
        assertTrue(ledger.isEmpty())
        assertTrue(StrategyLedger.recordTests(emptyMap(), "", tests(true to 90), day).isEmpty())
    }

    @Test
    fun theTextFormRoundTripsAndDropsBrokenRows() {
        var ledger = StrategyLedger.recordTests(emptyMap(), "wifi", tests(true to 90), day)
        ledger = StrategyLedger.recordUse(ledger, "wifi", "-d1", 120_000L, 2 * day)
        val text = StrategyLedger.serialize(ledger.values)
        assertEquals(ledger, StrategyLedger.parse(text))
        val broken = text + "\nwifi\t1\t1\n" + "wifi\t-1\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t-x"
        assertEquals(ledger, StrategyLedger.parse(broken))
        assertTrue(StrategyLedger.parse(null).isEmpty())
    }

    @Test
    fun aGoodRecordOutranksAnUntestedStrategyAndAWeakFailureCostsLessThanARealOne() {
        val proven = LedgerEntry("wifi", "a", tests = 4, testOk = 4, scoreSum = 360, realOk = 3)
        val unknown = LedgerEntry("wifi", "b")
        val stalled = proven.copy(softBad = 1)
        val failed = proven.copy(realBad = 1)
        assertTrue(StrategyLedger.history(proven) > StrategyLedger.history(unknown))
        assertTrue(StrategyLedger.history(stalled) > StrategyLedger.history(failed))
        assertTrue(StrategyLedger.history(proven) in 0..100)
    }

    @Test
    fun theBestForAColdStartAreTheSuccessfulOnesOfThatClassStillOnOffer() {
        fun entry(net: String, line: String, ok: Int, bad: Int, score: Int, seen: Long) =
            LedgerEntry(net, line, tests = ok + bad, testOk = ok, scoreSum = ok * score, realOk = 0, lastUsedAt = seen)
        val ledger = listOf(
            entry("wifi", "top", 5, 0, 95, 5),
            entry("wifi", "good", 4, 1, 85, 4),
            entry("wifi", "poor", 1, 4, 60, 9),
            entry("wifi", "dropped", 5, 0, 95, 9),
            entry("wifi", "never", 0, 3, 0, 9),
            entry("mobile", "other", 5, 0, 95, 9)
        ).associateBy { StrategyLedger.key(it.net, it.line) }
        val best = StrategyLedger.best(ledger, "wifi", 6, offered = setOf("top", "good", "poor", "never", "other"))
        assertEquals(listOf("top", "good"), best)
        assertEquals(listOf("top"), StrategyLedger.best(ledger, "wifi", 1, setOf("top", "good")))
        assertTrue(StrategyLedger.best(ledger, "mobile", 6, setOf("top")).isEmpty())
    }

    @Test
    fun theLedgerKeepsAtMostFourHundredEntriesAndNeverDropsTheOneJustRecorded() {
        var ledger: Map<String, LedgerEntry> = emptyMap()
        for (i in 0 until StrategyLedger.MAX_ENTRIES + 25) {
            ledger = StrategyLedger.recordTests(ledger, "wifi", listOf(LedgerTest("-s$i", i % 2 == 0, if (i % 2 == 0) 90 else 0)), day + i)
        }
        assertEquals(StrategyLedger.MAX_ENTRIES, ledger.size)
        assertTrue(StrategyLedger.key("wifi", "-s${StrategyLedger.MAX_ENTRIES + 24}") in ledger)
    }

    @Test
    fun theReportListsEveryStrategyBestFirstWithTheWholeLine() {
        var ledger = StrategyLedger.recordTests(emptyMap(), "wifi", listOf(LedgerTest("-d1 -s1+s -a1", true, 100)), 2 * day)
        ledger = StrategyLedger.recordTests(ledger, "wifi", listOf(LedgerTest("-f-1 -Qr", false, 0)), 2 * day)
        ledger = StrategyLedger.recordReal(ledger, "wifi", "-d1 -s1+s -a1", LedgerReal.OK, 3 * day)
        ledger = StrategyLedger.recordUse(ledger, "wifi", "-d1 -s1+s -a1", 3_900_000L, 3 * day)
        val text = StrategyLedger.report("#kinescope-strategies fmt=1 dev=test", ledger, dateOf)
        val lines = text.trimEnd().lines()
        assertEquals("#kinescope-strategies fmt=1 dev=test", lines[0])
        assertTrue(lines[1].startsWith("#legend "))
        assertEquals("#summary strategies=2 successful=1 tests=2 real_ok=1 real_bad=0 used=1h05m", lines[2])
        assertEquals(
            "net=wifi rate=100% test=1/1 real=1/0 stall=0 avg=100 best=100 last=100 used=1h05m first=d2 seen=d3 s=-d1 -s1+s -a1",
            lines[3]
        )
        assertTrue(lines[4].startsWith("net=wifi rate=0% test=0/1 "))
        assertTrue(lines[4].endsWith(" s=-f-1 -Qr"))
    }

    @Test
    fun anEmptyReportSaysSoAndDurationsReadWell() {
        assertTrue(StrategyLedger.report("#h", emptyMap(), dateOf).contains("#empty"))
        assertEquals("0m", StrategyLedger.duration(0L))
        assertEquals("<1m", StrategyLedger.duration(30_000L))
        assertEquals("12m", StrategyLedger.duration(12 * 60_000L))
        assertEquals("2h00m", StrategyLedger.duration(120 * 60_000L))
        assertFalse(StrategyLedger.LEGEND.contains('\n'))
    }
}
