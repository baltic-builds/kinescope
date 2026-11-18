package com.kinescope.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 55: which strategies a random check may test and how it picks them. Patch 65: the calendar and the class-check tests are gone with their code. */
class RandomCheckPolicyTest {
    @Test
    fun aPickNeverHasDuplicatesAndAPoolSmallerThanTheAskGivesWhatThereIs() {
        val pool = (1..40).map { "-d$it" }
        val picks = RandomCheckPolicy.pick(pool, 5, Random(11))
        assertEquals(5, picks.size)
        assertEquals(5, picks.toSet().size)
        assertEquals(3, RandomCheckPolicy.pick(listOf("-a", "-b", "-c"), 5, Random(1)).size)
    }

    @Test
    fun theRefreshTakesEveryCandidateInRandomOrder() {
        // Patch 64/65: the refresh asks for as many picks as there are candidates, so the whole pool comes back, shuffled.
        val pool = (1..30).map { "-d$it" }
        val picks = RandomCheckPolicy.pick(pool, Int.MAX_VALUE, Random(3))
        assertEquals(pool.toSet(), picks.toSet())
        assertEquals(30, picks.size)
        assertEquals(picks, RandomCheckPolicy.pick(pool, Int.MAX_VALUE, Random(3)))
    }

    @Test
    fun aCheckAddsOnlyTheWorkingWaysThatWereNotInTheListBeforeAndAreInItNow() {
        val before = listOf("-a", "-b", "-c")
        val after = listOf("-a", "-n1", "-b", "-n2", "-c")
        // -a worked but was already there; -n1 and -n2 are new and in; -x worked but did not make the list.
        assertEquals(2, RandomCheckPolicy.added(before, after, listOf("-a", "-n1", "-n2", "-x")))
        assertEquals(0, RandomCheckPolicy.added(before, before, listOf("-a", "-x")))
        assertEquals(0, RandomCheckPolicy.added(before, after, emptyList()))
        // The same way reported twice counts once.
        assertEquals(1, RandomCheckPolicy.added(before, after, listOf("-n1", "-n1")))
    }

    @Test
    fun theFrozenOnesAndTheStrategyCarryingTheTunnelAreNeverPicked() {
        val pool = RandomCheckPolicy.pool(listOf("-a", "-b", "-c", "-d", "-a"), frozen = setOf("-b"), active = "-c")
        assertEquals(listOf("-a", "-d"), pool)
    }

    @Test
    fun twoDifferentStrategiesArePickedFromThePool() {
        val pool = (1..30).map { "-d$it" }
        val picks = RandomCheckPolicy.pick(pool, 2, Random(7))
        assertEquals(2, picks.size)
        assertEquals(2, picks.toSet().size)
        assertTrue(pool.containsAll(picks))
        // The same seed gives the same picks.
        assertEquals(picks, RandomCheckPolicy.pick(pool, 2, Random(7)))
    }

    @Test
    fun aSmallPoolGivesWhatItHas() {
        assertEquals(listOf("-a"), RandomCheckPolicy.pick(listOf("-a"), 2, Random(1)))
        assertEquals(emptyList<String>(), RandomCheckPolicy.pick(emptyList(), 2, Random(1)))
    }
}
