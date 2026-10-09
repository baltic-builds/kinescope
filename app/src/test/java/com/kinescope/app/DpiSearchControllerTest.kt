package com.kinescope.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiSearchControllerTest {
    @Test
    fun aDownloadHoldIsReleasedAndNeverGoesNegative() {
        // Regression: patch 35 took the hold for every download and nothing released it, so a
        // strategy search that started after the first download waited for ever.
        while (DpiSearchController.isHeld()) DpiSearchController.releaseDownload()
        DpiSearchController.holdForDownload()
        assertTrue(DpiSearchController.isHeld())
        DpiSearchController.releaseDownload()
        assertFalse(DpiSearchController.isHeld())
        DpiSearchController.releaseDownload()
        assertFalse(DpiSearchController.isHeld())
        DpiSearchController.holdForDownload()
        DpiSearchController.holdForDownload()
        DpiSearchController.releaseDownload()
        assertTrue(DpiSearchController.isHeld())
        DpiSearchController.releaseDownload()
        assertFalse(DpiSearchController.isHeld())
    }
}
