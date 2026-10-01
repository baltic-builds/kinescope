package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 35: chain ordering (pin, quarantine) and the merge a finished search stores. */
class ChainPlannerTest {
    private val stored = listOf("a", "b", "c", "d")

    @Test
    fun theStoredOrderStandsWithoutAPinOrAQuarantine() {
        assertEquals(stored, ChainPlanner.order(stored, null, emptySet()))
    }

    @Test
    fun aPinnedStrategyGoesFirstWithoutDuplicates() {
        assertEquals(listOf("c", "a", "b", "d"), ChainPlanner.order(stored, "c", emptySet()))
        assertEquals(listOf("z", "a", "b", "c", "d"), ChainPlanner.order(stored, "z", emptySet()))
    }

    @Test
    fun quarantinedStrategiesGoLastAndKeepTheirOrder() {
        assertEquals(listOf("b", "d", "a", "c"), ChainPlanner.order(stored, null, setOf("a", "c")))
    }

    @Test
    fun aQuarantinedPinGoesLastToo() {
        assertEquals(listOf("a", "b", "c", "d", "p").let { listOf("a", "b", "c", "d", "p") },
            ChainPlanner.order(stored, "p", setOf("p")))
    }

    @Test
    fun ifEverythingIsQuarantinedTheStoredOrderStands() {
        assertEquals(stored, ChainPlanner.order(stored, null, stored.toSet()))
    }

    @Test
    fun mergeUsesTheRankingAndCapsTheChain() {
        assertEquals(
            listOf("r1", "r2", "r3", "r4"),
            ChainPlanner.merge(listOf("r1", "r2", "r3", "r4", "r5"), emptySet(), null, null, false, 4)
        )
    }

    @Test
    fun aPinnedStrategyAlwaysLeadsTheMerge() {
        assertEquals(
            listOf("p", "r1", "r2", "r3"),
            ChainPlanner.merge(listOf("r1", "r2", "r3", "r4"), emptySet(), "old", "p", true, 4)
        )
    }

    @Test
    fun aProvenStrategyKeepsTheLeadInABackgroundCheckUnlessItFailed() {
        val ranked = listOf("r1", "r2", "r3")
        assertEquals(listOf("old", "r1", "r2", "r3"), ChainPlanner.merge(ranked, emptySet(), "old", null, true, 4))
        assertEquals(ranked, ChainPlanner.merge(ranked, setOf("old"), "old", null, true, 4))
        assertEquals(ranked, ChainPlanner.merge(ranked, emptySet(), "old", null, false, 4))
    }

    @Test
    fun aProvenStrategyThatIsAlsoRankedIsNotDuplicated() {
        assertEquals(
            listOf("r2", "r1", "r3"),
            ChainPlanner.merge(listOf("r1", "r2", "r3"), emptySet(), "r2", null, true, 4)
        )
    }

    @Test
    fun aStoppedSearchKeepsThePreviousChainBehindWhatItFound() {
        assertEquals(
            listOf("n1", "old1", "old2", "old3"),
            ChainPlanner.merge(listOf("n1"), setOf("old4"), null, null, false, 4, fill = listOf("old1", "old2", "old4", "old3"))
        )
        assertEquals(
            listOf("n1", "old2"),
            ChainPlanner.merge(listOf("n1"), setOf("old1"), null, null, false, 4, fill = listOf("old1", "old2"))
        )
    }

    @Test
    fun quarantineEntriesExpireAndSurviveARoundTrip() {
        val added = QuarantineList.add(QuarantineList.add(emptyMap(), "a b", 500L), "c", 900L)
        val text = QuarantineList.serialize(added)
        assertEquals(mapOf("a b" to 500L, "c" to 900L), QuarantineList.parse(text, now = 100L))
        assertEquals(mapOf("c" to 900L), QuarantineList.parse(text, now = 600L))
        assertEquals(emptyMap<String, Long>(), QuarantineList.parse("garbage\nx\tnope", now = 0L))
    }

    @Test
    fun aRepeatedQuarantineMovesToTheEndAndTheListIsCapped() {
        val start = (1..40).fold(emptyMap<String, Long>()) { acc, i -> QuarantineList.add(acc, "s$i", 1_000L + i) }
        assertEquals(30, start.size)
        assertEquals("s40", start.keys.last())
        assertEquals("s11", start.keys.first())
        val again = QuarantineList.add(start, "s20", 5_000L)
        assertEquals("s20", again.keys.last())
        assertEquals(30, again.size)
    }
}
