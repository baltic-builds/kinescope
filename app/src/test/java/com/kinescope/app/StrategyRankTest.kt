package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 55: the order of a connection class's strategies and the 15 lowest ones that are left alone for 75 days. */
class StrategyRankTest {
    private val day = MaintenanceSchedule.DAY_MS
    private val now = 500L * day

    private fun stored(line: String, passed: Boolean, ageDays: Long) =
        StoredResult(line, if (passed) 80 else 0, passed, 0, 0, 0, 0, now - ageDays * day)

    @Test
    fun goodOnesFirstThenNeverTestedThenTheOnesThatDidBadly() {
        val candidates = listOf("-a", "-b", "-c", "-d", "-e")
        val ratings = mapOf("-b" to 90, "-d" to 70, "-c" to 0)
        val tested = mapOf("-c" to stored("-c", false, 3))
        assertEquals(listOf("-b", "-d", "-a", "-e", "-c"), StrategyRank.order(candidates, ratings, tested))
    }

    @Test
    fun theOneThatFailedLastSitsAtTheVeryBottom() {
        val candidates = listOf("-a", "-b", "-c")
        val ratings = mapOf("-a" to 0, "-b" to 0, "-c" to 0)
        val tested = mapOf(
            "-a" to stored("-a", false, 10),
            "-b" to stored("-b", false, 1),
            "-c" to stored("-c", false, 5)
        )
        assertEquals(listOf("-a", "-c", "-b"), StrategyRank.order(candidates, ratings, tested))
    }

    @Test
    fun aFailedCheckMovesAWorkingStrategyToTheBottom() {
        val candidates = listOf("-a", "-b", "-c")
        val before = StrategyRank.order(candidates, mapOf("-a" to 90, "-b" to 80, "-c" to 0), mapOf("-c" to stored("-c", false, 2)))
        assertEquals(listOf("-a", "-b", "-c"), before)
        // -a is checked again and fails: its rating drops and its test is the newest on record.
        val after = StrategyRank.order(
            candidates,
            mapOf("-a" to 0, "-b" to 80, "-c" to 0),
            mapOf("-a" to stored("-a", false, 0), "-c" to stored("-c", false, 2))
        )
        assertEquals(listOf("-b", "-c", "-a"), after)
    }

    @Test
    fun homeManualChoiceIsExactlyTheFiveBestOfTheClassRanking() {
        val candidates = listOf("-a", "-b", "-c", "-d", "-e", "-f", "-g")
        val ratings = mapOf("-f" to 95, "-b" to 85, "-d" to 75, "-a" to 65, "-g" to 55, "-c" to 20)
        val tested = mapOf("-c" to stored("-c", false, 2))
        assertEquals(
            listOf("-f", "-b", "-d", "-a", "-g"),
            StrategyRank.manualChoices(candidates, ratings, tested)
        )
    }

    @Test
    fun homeManualChoiceUsesOnlyTheEvidenceOfTheClassItWasGiven() {
        val candidates = listOf("-a", "-b", "-c", "-d", "-e")
        val wifi = mapOf("-a" to 90, "-b" to 80, "-c" to 70, "-d" to 60, "-e" to 50)
        val mobile = mapOf("-e" to 90, "-d" to 80, "-c" to 70, "-b" to 60, "-a" to 50)
        assertEquals(candidates, StrategyRank.manualChoices(candidates, wifi, emptyMap()))
        assertEquals(candidates.reversed(), StrategyRank.manualChoices(candidates, mobile, emptyMap()))
    }

    @Test
    fun duplicatesAreCollapsed() {
        assertEquals(listOf("-a", "-b"), StrategyRank.order(listOf("-a", "-b", "-a"), emptyMap(), emptyMap()))
    }

    private fun failed(count: Int, ageOf: (Int) -> Long): Triple<List<String>, Map<String, Int>, Map<String, StoredResult>> {
        val lines = (1..count).map { "-f$it" }
        val ratings = lines.associateWith { 0 }
        val tested = lines.mapIndexed { i, line -> line to stored(line, false, ageOf(i)) }.toMap()
        return Triple(lines, ratings, tested)
    }

    @Test
    fun theFifteenLowestAreFrozenForSeventyFiveDays() {
        // 20 failures, the first the oldest (30 days) ... the last the newest (11 days): the 15 at the bottom are frozen.
        val (lines, ratings, tested) = failed(20) { 30L - it }
        val order = StrategyRank.order(lines, ratings, tested)
        assertEquals(lines, order)
        val frozen = StrategyRank.frozen(order, ratings, tested, now)
        assertEquals(15, frozen.size)
        assertEquals(lines.takeLast(15).toSet(), frozen)
        assertFalse("-f1" in frozen)
    }

    @Test
    fun aFrozenOneIsFreeAfterSeventyFiveDays() {
        val lines = listOf("-a", "-b")
        val ratings = mapOf("-a" to 0, "-b" to 0)
        val tested = mapOf("-a" to stored("-a", false, 75), "-b" to stored("-b", false, 74))
        val frozen = StrategyRank.frozen(StrategyRank.order(lines, ratings, tested), ratings, tested, now)
        assertEquals(setOf("-b"), frozen)
    }

    @Test
    fun fewerThanFifteenFailuresAreAllFrozenButNeverTestedOnesAreNot() {
        val candidates = listOf("-new", "-bad1", "-bad2")
        val ratings = mapOf("-bad1" to 0, "-bad2" to 0)
        val tested = mapOf("-bad1" to stored("-bad1", false, 4), "-bad2" to stored("-bad2", false, 9))
        val order = StrategyRank.order(candidates, ratings, tested)
        assertEquals(listOf("-new", "-bad2", "-bad1"), order)
        assertEquals(setOf("-bad1", "-bad2"), StrategyRank.frozen(order, ratings, tested, now))
    }

    @Test
    fun aStrategyThatFailedOnlyInRealUseHasNoTestTimeAndIsNotFrozen() {
        val ratings = mapOf("-real" to 10)
        val order = StrategyRank.order(listOf("-real", "-x"), ratings, emptyMap())
        assertTrue(StrategyRank.frozen(order, ratings, emptyMap(), now).isEmpty())
    }
}
