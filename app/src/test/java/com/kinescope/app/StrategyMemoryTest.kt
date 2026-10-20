package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 46: the memory of which strategies really carried traffic. */
class StrategyMemoryTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val t0 = 500L * day

    private fun record(memory: Map<String, LearnedStrategy>, line: String, outcome: StrategyOutcome, now: Long = t0) =
        StrategyMemory.record(memory, line, outcome, now)

    @Test
    fun aStrategyNothingIsKnownAboutIsNeutral() {
        assertEquals(StrategyMemory.NEUTRAL, StrategyMemory.score(null, t0), 1e-9)
    }

    @Test
    fun realSuccessesRaiseTheScoreAndFailuresLowerIt() {
        var memory = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        assertEquals(2.0 / 3.0, StrategyMemory.score(memory["a"], t0), 1e-9)
        memory = record(memory, "a", StrategyOutcome.REAL_OK)
        assertEquals(0.75, StrategyMemory.score(memory["a"], t0), 1e-9)
        memory = record(memory, "a", StrategyOutcome.REAL_BAD)
        assertEquals(0.5, StrategyMemory.score(memory["a"], t0), 1e-9)
    }

    @Test
    fun oneFailureOutweighsOneSuccess() {
        var memory = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        memory = record(memory, "a", StrategyOutcome.REAL_BAD)
        assertEquals(0.4, StrategyMemory.score(memory["a"], t0), 1e-9)
    }

    @Test
    fun aSearchProbeIsWeakerEvidenceThanRealUse() {
        val probe = record(emptyMap(), "a", StrategyOutcome.PROBE_OK)
        val real = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        assertTrue(StrategyMemory.score(probe["a"], t0) > StrategyMemory.NEUTRAL)
        assertTrue(StrategyMemory.score(real["a"], t0) > StrategyMemory.score(probe["a"], t0))
    }

    @Test
    fun evidenceHalvesEveryWeek() {
        val memory = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        // one success faded to half a success: (0.5 + 1) / (0.5 + 2)
        assertEquals(0.6, StrategyMemory.score(memory["a"], t0 + 7 * day), 1e-9)
        assertTrue(StrategyMemory.score(memory["a"], t0 + 70 * day) < 0.5 + 0.001)
    }

    @Test
    fun onlyRealSuccessSetsTheLastRealSuccessTime() {
        var memory = record(emptyMap(), "a", StrategyOutcome.PROBE_OK, t0)
        assertEquals(0L, memory["a"]!!.lastRealOkAt)
        memory = record(memory, "a", StrategyOutcome.REAL_OK, t0 + day)
        assertEquals(t0 + day, memory["a"]!!.lastRealOkAt)
        memory = record(memory, "a", StrategyOutcome.REAL_BAD, t0 + 2 * day)
        assertEquals(t0 + day, memory["a"]!!.lastRealOkAt)
    }

    @Test
    fun aStrategyThatIsNotKnownIsLeftAloneWhenOnlyKnownOnesAreWanted() {
        val unknown = StrategyMemory.record(emptyMap(), "a", StrategyOutcome.PROBE_BAD, t0, onlyIfKnown = true)
        assertTrue(unknown.isEmpty())
        val known = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        val after = StrategyMemory.record(known, "a", StrategyOutcome.PROBE_BAD, t0, onlyIfKnown = true)
        assertTrue(StrategyMemory.score(after["a"], t0) < StrategyMemory.score(known["a"], t0))
    }

    @Test
    fun aLineThatCannotBeStoredAsTextIsIgnored() {
        assertTrue(record(emptyMap(), "a\tb", StrategyOutcome.REAL_OK).isEmpty())
        assertTrue(record(emptyMap(), "a\nb", StrategyOutcome.REAL_OK).isEmpty())
        assertTrue(record(emptyMap(), "  ", StrategyOutcome.REAL_OK).isEmpty())
    }

    @Test
    fun goodListsTheStrategiesWithRealEvidenceBestFirst() {
        var memory = emptyMap<String, LearnedStrategy>()
        repeat(3) { memory = record(memory, "strong", StrategyOutcome.REAL_OK) }
        memory = record(memory, "once", StrategyOutcome.REAL_OK)
        memory = record(memory, "mixed", StrategyOutcome.REAL_OK)
        memory = record(memory, "mixed", StrategyOutcome.REAL_BAD)
        memory = record(memory, "failing", StrategyOutcome.REAL_BAD)
        assertEquals(listOf("strong", "once"), StrategyMemory.good(memory, t0))
    }

    @Test
    fun goodDropsEvidenceThatHasFadedAway() {
        val memory = record(emptyMap(), "a", StrategyOutcome.REAL_OK)
        assertEquals(listOf("a"), StrategyMemory.good(memory, t0 + 7 * day))
        assertTrue(StrategyMemory.good(memory, t0 + 21 * day).isEmpty())
    }

    @Test
    fun theWeakestEntriesAreForgottenPastTheCapAndTheNewestIsKept() {
        var memory = emptyMap<String, LearnedStrategy>()
        repeat(3) { memory = record(memory, "strong", StrategyOutcome.REAL_OK) }
        for (i in 1..45) memory = record(memory, "weak$i", StrategyOutcome.REAL_BAD, t0 + i)
        assertEquals(StrategyMemory.MAX_ENTRIES, memory.size)
        assertTrue("strong" in memory)
        assertTrue("weak45" in memory)
    }

    @Test
    fun entriesWhoseEvidenceFadedToNothingAreDroppedOnTheNextRecord() {
        var memory = record(emptyMap(), "old", StrategyOutcome.PROBE_BAD)
        memory = record(memory, "new", StrategyOutcome.REAL_OK, t0 + 120 * day)
        assertFalse("old" in memory)
        assertTrue("new" in memory)
    }

    @Test
    fun theMemorySurvivesARoundTripAndGarbageRowsAreDropped() {
        var memory = record(emptyMap(), "-d1 -s1+s", StrategyOutcome.REAL_OK)
        memory = record(memory, "-f-1 -t8", StrategyOutcome.REAL_BAD, t0 + 5)
        val text = StrategyMemory.serialize(memory)
        assertEquals(memory, StrategyMemory.parse(text))
        val dirty = text + "\nnot a row\nx\t1\t2\t3\nx\tNaN\t0\t1\t1\ny\t-1\t0\t1\t1\nz\t1\t0\tnope\t1"
        assertEquals(memory, StrategyMemory.parse(dirty))
        assertTrue(StrategyMemory.parse(null).isEmpty())
        assertNull(StrategyMemory.parse("").get("a"))
    }
}
