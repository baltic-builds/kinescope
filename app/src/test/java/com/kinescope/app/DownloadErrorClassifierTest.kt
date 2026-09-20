package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorClassifierTest {
    @Test
    fun classifiesVerificationFailures() {
        assertEquals(FailureKind.YOUTUBE_VERIFICATION, DownloadErrorClassifier.classify("Sign in to confirm you’re not a bot"))
        assertEquals(FailureKind.YOUTUBE_VERIFICATION, DownloadErrorClassifier.classify("HTTP Error 403: Forbidden"))
        assertTrue(DownloadErrorClassifier.isRecoverableYoutubeBlock("HTTP Error 429: Too Many Requests"))
    }

    @Test
    fun classifiesKnownTerminalAndNetworkFailures() {
        assertEquals(FailureKind.PRIVATE_VIDEO, DownloadErrorClassifier.classify("Private video"))
        assertEquals(FailureKind.AGE_RESTRICTED, DownloadErrorClassifier.classify("Age restricted: confirm your age"))
        assertEquals(FailureKind.UNAVAILABLE, DownloadErrorClassifier.classify("Video unavailable"))
        assertEquals(FailureKind.NO_INTERNET, DownloadErrorClassifier.classify("Unable to resolve host youtube.com"))
    }
}
