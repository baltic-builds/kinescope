package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 51: how a start of the app is told apart: a fresh install, an update, or the same build again. */
class AppUpdateTest {
    @Test
    fun aFreshInstallHasNeverSeenAVersionAndNeverFinishedTheFirstCheck() {
        assertEquals(LaunchKind.FIRST_INSTALL, AppUpdate.classify(seenVersion = 0, currentVersion = 10_050, initialSearchDone = false))
    }

    @Test
    fun theSameBuildAgainIsNothing() {
        assertEquals(LaunchKind.SAME, AppUpdate.classify(10_050, 10_050, initialSearchDone = true))
        assertEquals(LaunchKind.SAME, AppUpdate.classify(10_050, 10_050, initialSearchDone = false))
    }

    @Test
    fun aNewerBuildIsAnUpdate() {
        assertEquals(LaunchKind.UPDATED, AppUpdate.classify(10_050, 10_051, initialSearchDone = true))
        // An update that arrives before the first check ever finished is still an update of a seen version.
        assertEquals(LaunchKind.UPDATED, AppUpdate.classify(10_050, 10_051, initialSearchDone = false))
    }

    @Test
    fun aBuildFromBeforeThisLogicThatFinishedItsFirstCheckCountsAsUpdated() {
        // No version was recorded before patch 51, but the old first-check marker says this is not a fresh install.
        assertEquals(LaunchKind.UPDATED, AppUpdate.classify(seenVersion = 0, currentVersion = 10_051, initialSearchDone = true))
    }

    @Test
    fun anUnknownVersionDoesNothing() {
        assertEquals(LaunchKind.SAME, AppUpdate.classify(10_050, 0, initialSearchDone = true))
        assertEquals(LaunchKind.SAME, AppUpdate.classify(0, 0, initialSearchDone = false))
    }
}
