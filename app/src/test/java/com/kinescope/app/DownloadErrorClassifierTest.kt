package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun doesNotMisclassifyGenericNetworkErrorsAsAgeRestricted() {
        // Patch 31 regression: a real device log showed a bypass-proxy "Host unreachable"
        // failure misclassified as AGE_RESTRICTED, because "webpage" contains "age" and
        // yt-dlp's generic "...Confirm you are on the latest version..." trailer contains
        // "confirm". Real age-gate messages use much more specific phrasing.
        val hostUnreachable = "WARNING: [youtube] pvEJquxYlIo: Unable to download webpage: " +
            "<urlopen error [Errno 4] Host unreachable> (caused by ProxyError(...)); please " +
            "report this issue on <url>, filling out the appropriate issue template. Confirm " +
            "you are on the latest version using yt-dlp -U."
        assertEquals(FailureKind.OTHER, DownloadErrorClassifier.classify(hostUnreachable))
        assertTrue(DownloadErrorClassifier.isRecoverableYoutubeBlock(hostUnreachable))

        assertEquals(
            FailureKind.AGE_RESTRICTED,
            DownloadErrorClassifier.classify(
                "This video is age-restricted; some formats may be missing without authentication."
            )
        )
        assertEquals(
            FailureKind.YOUTUBE_VERIFICATION,
            DownloadErrorClassifier.classify(
                "Sign in to confirm your age. This video may be inappropriate for some users."
            )
        )

        assertFalse(DownloadErrorClassifier.isRecoverableYoutubeBlock("Private video"))
        assertFalse(DownloadErrorClassifier.isRecoverableYoutubeBlock("Video unavailable"))
    }
}
