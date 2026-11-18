package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 59: how often the tunnel may switch strategy on its own. */
class RotationLimitsTest {
    private val minute = 60_000L

    @Test
    fun theFirstSwitchIsAllowed() {
        assertEquals(RotationDecision.GO, RotationLimits.decide(emptyList(), 100 * minute, chainSize = 6, alternatives = 5))
    }

    @Test
    fun aSwitchRightAfterTheLastOneWaits() {
        val now = 100 * minute
        assertEquals(RotationDecision.TOO_SOON, RotationLimits.decide(listOf(now - 1_000L), now, 6, 5))
        assertEquals(RotationDecision.GO, RotationLimits.decide(listOf(now - 5_000L), now, 6, 5))
    }

    @Test
    fun aWholeChainCanBeWalkedOnceInTheWindow() {
        val now = 100 * minute
        // Six strategies: five switches reach the sixth, and the window allows six.
        val five = List(5) { now - (it + 1) * 20_000L }
        assertEquals(RotationDecision.GO, RotationLimits.decide(five.reversed(), now, 6, 1))
        val six = List(6) { now - (it + 1) * 20_000L }
        assertEquals(RotationDecision.EXHAUSTED, RotationLimits.decide(six.reversed(), now, 6, 1))
    }

    @Test
    fun aShortChainStillAllowsThreeSwitches() {
        assertEquals(3, RotationLimits.allowed(2))
        assertEquals(3, RotationLimits.allowed(0))
        assertEquals(6, RotationLimits.allowed(6))
    }

    @Test
    fun oldSwitchesLeaveTheWindow() {
        val now = 100 * minute
        val old = List(6) { now - 11 * minute - it * 1_000L }
        assertEquals(RotationDecision.GO, RotationLimits.decide(old.reversed(), now, 6, 5))
    }

    @Test
    fun nothingToSwitchToIsExhaustedAtOnce() {
        assertEquals(RotationDecision.EXHAUSTED, RotationLimits.decide(emptyList(), 100 * minute, 1, 0))
    }
}
