package com.kinescope.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 55: the two-a-day random check and the per-connection check after an install or an update. */
class RandomCheckPolicyTest {
    private val hour = 60L * 60L * 1000L
    private val now = 1_000_000L * hour

    @Test
    fun theCheckIsDueTwelveHoursAfterTheLastOneSoItRunsTwiceADayAtMost() {
        assertEquals(12L * hour, RandomCheckPolicy.INTERVAL_MS)
        assertTrue(RandomCheckPolicy.isDue(0L, now))
        assertFalse(RandomCheckPolicy.isDue(now - 11 * hour, now))
        assertTrue(RandomCheckPolicy.isDue(now - 12 * hour, now))
    }

    @Test
    fun aClockThatMovedBackCountsAsDue() {
        assertTrue(RandomCheckPolicy.isDue(now + hour, now))
    }

    @Test
    fun theFrozenOnesAndTheStrategyCarryingTheTunnelAreNeverPicked() {
        val pool = RandomCheckPolicy.pool(listOf("-a", "-b", "-c", "-d", "-a"), frozen = setOf("-b"), active = "-c")
        assertEquals(listOf("-a", "-d"), pool)
    }

    @Test
    fun twoDifferentStrategiesArePickedFromThePool() {
        val pool = (1..30).map { "-d$it" }
        val picks = RandomCheckPolicy.pick(pool, RandomCheckPolicy.PICKS, Random(7))
        assertEquals(2, picks.size)
        assertEquals(2, picks.toSet().size)
        assertTrue(pool.containsAll(picks))
        // The same seed gives the same picks; another seed (almost always) another pair.
        assertEquals(picks, RandomCheckPolicy.pick(pool, 2, Random(7)))
    }

    @Test
    fun aSmallPoolGivesWhatItHas() {
        assertEquals(listOf("-a"), RandomCheckPolicy.pick(listOf("-a"), 2, Random(1)))
        assertEquals(emptyList<String>(), RandomCheckPolicy.pick(emptyList(), 2, Random(1)))
    }

    @Test
    fun aClassIsCheckedOncePerVersionOfTheApp() {
        // Never checked: needed.
        assertTrue(ClassCheckPolicy.needed(checkedVersion = 0L, currentVersion = 10032L, tryAt = 0L, now = now))
        // Checked for this version: not needed.
        assertFalse(ClassCheckPolicy.needed(10032L, 10032L, 0L, now))
        // Checked for an older version (an update): needed again.
        assertTrue(ClassCheckPolicy.needed(10031L, 10032L, 0L, now))
        // An unknown running version asks for nothing.
        assertFalse(ClassCheckPolicy.needed(0L, 0L, 0L, now))
    }

    @Test
    fun aCheckThatDidNotFinishWaitsSixHoursBeforeTheNextTry() {
        assertFalse(ClassCheckPolicy.needed(0L, 10032L, now - 5 * hour, now))
        assertTrue(ClassCheckPolicy.needed(0L, 10032L, now - 6 * hour, now))
        // A try stamped in the future (the clock moved back) does not block the check.
        assertTrue(ClassCheckPolicy.needed(0L, 10032L, now + hour, now))
    }
}
