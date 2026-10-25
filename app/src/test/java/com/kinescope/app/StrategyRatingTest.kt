package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 53: one rating from the last test and from real use. */
class StrategyRatingTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val t0 = 500L * day

    private fun test(line: String, score: Int, passed: Boolean = true, at: Long = t0) =
        StoredResult(line, if (passed) score else 0, passed, 0, 0, 0, 0, at)

    private fun real(vararg outcomes: StrategyOutcome, at: Long = t0): LearnedStrategy? {
        var memory = emptyMap<String, LearnedStrategy>()
        for (outcome in outcomes) memory = StrategyMemory.record(memory, "a", outcome, at)
        return memory["a"]
    }

    @Test
    fun aStrategyNothingIsRecordedAboutHasNoRating() {
        assertNull(StrategyRating.rate(null, null, t0))
    }

    @Test
    fun withNoRealUseTheRatingIsTheFreshTest() {
        assertEquals(90, StrategyRating.rate(test("a", 90), null, t0))
        assertEquals(0, StrategyRating.rate(test("a", 0, passed = false), null, t0))
    }

    @Test
    fun aTestFadesTowardsUnknownWithAHalfLifeOfTwoWeeks() {
        assertEquals(70, StrategyRating.rate(test("a", 90), null, t0 + 14 * day))
        assertEquals(30, StrategyRating.rate(test("a", 10), null, t0 + 14 * day))
        val old = StrategyRating.rate(test("a", 100), null, t0 + 365 * day)!!
        assertTrue(old in 50..51)
    }

    @Test
    fun aFailedTestOnlyCountsAgainstAStrategyWhileItIsFresh() {
        val old = StrategyRating.rate(test("a", 0, passed = false), null, t0 + 140 * day)!!
        assertTrue(old in 49..50)
    }

    @Test
    fun realUseWithoutATestStartsNearUnknownAndRisesWithEvidence() {
        val once = StrategyRating.rate(null, real(StrategyOutcome.REAL_OK), t0)!!
        val thrice = StrategyRating.rate(null, real(StrategyOutcome.REAL_OK, StrategyOutcome.REAL_OK, StrategyOutcome.REAL_OK), t0)!!
        assertEquals(54, once)
        assertEquals(65, thrice)
        assertTrue(once >= StrategyRating.ELIGIBLE)
    }

    @Test
    fun aFailureWithoutATestPutsAStrategyBelowTheLine() {
        val failed = StrategyRating.rate(null, real(StrategyOutcome.REAL_BAD), t0)!!
        assertTrue(failed < StrategyRating.ELIGIBLE)
    }

    @Test
    fun aGoodTestIsPulledDownByRepeatedRealFailures() {
        val fresh = test("a", 90)
        val one = StrategyRating.rate(fresh, real(StrategyOutcome.REAL_BAD), t0)!!
        val two = StrategyRating.rate(fresh, real(StrategyOutcome.REAL_BAD, StrategyOutcome.REAL_BAD), t0)!!
        val four = StrategyRating.rate(fresh, real(*Array(4) { StrategyOutcome.REAL_BAD }), t0)!!
        assertEquals(74, one)
        assertEquals(61, two)
        assertTrue(one > two && two > four)
        assertTrue(four < StrategyRating.ELIGIBLE)
    }

    @Test
    fun aPoorTestIsLiftedByRealSuccesses() {
        val weak = test("a", 40)
        val none = StrategyRating.rate(weak, null, t0)!!
        val many = StrategyRating.rate(weak, real(*Array(9) { StrategyOutcome.REAL_OK }), t0)!!
        assertEquals(40, none)
        assertTrue(many > 70)
    }

    @Test
    fun realUseWeighsAsMuchAsTheTestAtThreeUnitsOfEvidence() {
        // test 100, real: three successes -> real part 80; equal weights -> 90.
        val rating = StrategyRating.rate(test("a", 100), real(*Array(3) { StrategyOutcome.REAL_OK }), t0)
        assertEquals(90, rating)
    }

    @Test
    fun ratingsCoverEveryStrategyWithATestOrARecordAndEligibleIsBestFirst() {
        val tests = listOf(test("a", 90), test("b", 40), test("c", 70)).associateBy { it.line }
        var memory = emptyMap<String, LearnedStrategy>()
        memory = StrategyMemory.record(memory, "d", StrategyOutcome.REAL_OK, t0)
        val ratings = StrategyRating.ratings(tests, memory, t0)
        assertEquals(setOf("a", "b", "c", "d"), ratings.keys)
        assertEquals(listOf("a", "c", "d"), StrategyRating.eligible(ratings))
    }
}
