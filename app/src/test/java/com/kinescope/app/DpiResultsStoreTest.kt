package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 35: stored strategy results survive a round trip, merge by strategy, and reject garbage. */
class DpiResultsStoreTest {
    private fun entry(line: String, score: Int, at: Long = 1L) =
        StoredResult(line, score, score > 0, score * 10, 6, 6, 400, at)

    @Test
    fun roundTripKeepsEveryField() {
        val original = listOf(entry("-d1 -s2", 90), entry("-o1 -r-5+se", 0))
        val parsed = DpiResultsStore.parse(DpiResultsStore.serialize(original))
        assertEquals(original.associateBy { it.line }, parsed)
    }

    @Test
    fun aNewerResultReplacesTheOldOneAndMovesToTheEnd() {
        val old = DpiResultsStore.parse(DpiResultsStore.serialize(listOf(entry("a", 10), entry("b", 20))))
        val merged = DpiResultsStore.merge(old, listOf(entry("a", 70, at = 5)))
        assertEquals(listOf("b", "a"), merged.keys.toList())
        assertEquals(70, merged["a"]!!.score)
    }

    @Test
    fun theStoreIsCapped() {
        val many = (1..250).map { entry("s$it", 1) }
        val merged = DpiResultsStore.merge(emptyMap(), many)
        assertEquals(200, merged.size)
        assertFalse("s1" in merged)
        assertTrue("s250" in merged)
    }

    @Test
    fun garbageRowsAreSkipped() {
        val parsed = DpiResultsStore.parse("nonsense\n1\t1\tx\t1\t1\t1\t1\tline\n50\t1\t500\t6\t6\t300\t9\t-d1")
        assertEquals(listOf("-d1"), parsed.keys.toList())
        assertEquals(emptyMap<String, StoredResult>(), DpiResultsStore.parse(null))
    }
}
