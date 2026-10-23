package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 51: when the power-saving pop-up is shown. */
class BatteryOptimizationTest {
    @Test
    fun theFirstLaunchNeverShowsIt() {
        assertFalse(BatteryOptimization.shouldPrompt(launchCount = 0, alreadyAsked = false, ignoring = false))
        assertFalse(BatteryOptimization.shouldPrompt(launchCount = 1, alreadyAsked = false, ignoring = false))
    }

    @Test
    fun theSecondLaunchShowsItOnce() {
        assertEquals(2, BatteryOptimization.PROMPT_LAUNCH)
        assertTrue(BatteryOptimization.shouldPrompt(launchCount = 2, alreadyAsked = false, ignoring = false))
        assertFalse(BatteryOptimization.shouldPrompt(launchCount = 2, alreadyAsked = true, ignoring = false))
        assertFalse(BatteryOptimization.shouldPrompt(launchCount = 7, alreadyAsked = true, ignoring = false))
    }

    @Test
    fun aLaterLaunchStillAsksIfTheSecondOneCouldNot() {
        // The second launch can be taken by the first-run check; the pop-up then waits for the next one.
        assertTrue(BatteryOptimization.shouldPrompt(launchCount = 3, alreadyAsked = false, ignoring = false))
    }

    @Test
    fun anAppThatAlreadyRunsFreelyIsNotAsked() {
        assertFalse(BatteryOptimization.shouldPrompt(launchCount = 2, alreadyAsked = false, ignoring = true))
    }
}
