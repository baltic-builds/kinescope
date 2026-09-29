package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 33: connection-level failures are recognised, and real YouTube answers are left alone. */
class DownloadTransportFailureTest {
    @Test
    fun recognisesProxyAndSocketLevelFailures() {
        val samples = listOf(
            "<urlopen error [Errno 4] Host unreachable> (caused by ProxyError('x'))",
            "Connection refused",
            "The read operation timed out",
            "Remote end closed connection without response",
            "bypass transport failure: no data from YouTube for a long time"
        )
        for (sample in samples) {
            assertTrue(sample, DownloadErrorClassifier.isTransportFailure(sample))
            assertEquals(sample, FailureKind.CONNECTION_BLOCKED, DownloadErrorClassifier.classify(sample))
            // Retried through the profile chain when no bypass ladder ran; see DownloadService.
            assertTrue(sample, DownloadErrorClassifier.isRecoverableYoutubeBlock(sample))
        }
    }

    @Test
    fun doesNotSwallowRealYoutubeAnswers() {
        assertEquals(FailureKind.PRIVATE_VIDEO, DownloadErrorClassifier.classify("Private video"))
        assertEquals(
            FailureKind.YOUTUBE_VERIFICATION,
            DownloadErrorClassifier.classify("Sign in to confirm you\u2019re not a bot")
        )
        assertEquals(FailureKind.NO_INTERNET, DownloadErrorClassifier.classify("Unable to resolve host youtube.com"))
        assertFalse(DownloadErrorClassifier.isTransportFailure("Video unavailable"))
        assertFalse(DownloadErrorClassifier.isTransportFailure(null))
    }
}
