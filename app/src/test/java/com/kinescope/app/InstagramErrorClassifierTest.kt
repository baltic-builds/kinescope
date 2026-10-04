package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The yt-dlp texts below are representative shapes of Instagram failures, not a verbatim capture
 * of the current extractor (which changed twice in 2026). The classifier therefore keys on broad,
 * stable markers and judges the last `ERROR:` line only; a real device log should extend these.
 */
class InstagramErrorClassifierTest {
    @Test
    fun loginAndSessionFailuresAskForSignIn() {
        listOf(
            "ERROR: [Instagram] DXabc: Instagram sent an empty media response. Check if this post is " +
                "accessible in your browser without being logged-in. If it is not, then use " +
                "--cookies-from-browser or --cookies for the authentication.",
            "ERROR: [Instagram] DXabc: Requested content is not available, rate-limit reached or login required",
            "ERROR: [Instagram] DXabc: This content is only available for registered users",
            "ERROR: [Instagram] DXabc: Instagram cookies have been invalidated"
        ).forEach {
            assertEquals(it, FailureKind.INSTAGRAM_LOGIN, DownloadErrorClassifier.classifyInstagram(it))
        }
    }

    @Test
    fun combinedLoginAndRateLimitTextIsALoginFailure() {
        val text = "ERROR: [Instagram] X: Requested content is not available, rate-limit reached or login required"
        assertFalse(DownloadErrorClassifier.isInstagramRateLimited(text))
    }

    @Test
    fun plainRateLimitIsNotALoginFailure() {
        val text = "WARNING: [Instagram] unable to extract shared data\n" +
            "ERROR: [Instagram] X: HTTP Error 429: Too Many Requests"
        assertEquals(FailureKind.OTHER, DownloadErrorClassifier.classifyInstagram(text))
        assertTrue(DownloadErrorClassifier.isInstagramRateLimited(text))
    }

    @Test
    fun onlyTheLastErrorLineDecides() {
        // The warning mentions a login, but the failure is a dead connection.
        val text = "WARNING: [Instagram] Main webpage is locked behind the login page.\n" +
            "ERROR: [Instagram] X: Unable to download webpage: <urlopen error timed out>"
        assertEquals(FailureKind.CONNECTION_BLOCKED, DownloadErrorClassifier.classifyInstagram(text))
    }

    @Test
    fun classifiesUnavailableOfflineAndUnknown() {
        assertEquals(
            FailureKind.UNAVAILABLE,
            DownloadErrorClassifier.classifyInstagram("ERROR: [Instagram] X: Video unavailable")
        )
        assertEquals(
            FailureKind.NO_INTERNET,
            DownloadErrorClassifier.classifyInstagram("ERROR: Unable to resolve host www.instagram.com")
        )
        assertEquals(FailureKind.OTHER, DownloadErrorClassifier.classifyInstagram("ERROR: something new"))
        assertEquals(FailureKind.OTHER, DownloadErrorClassifier.classifyInstagram(null))
    }

    @Test
    fun youtubeClassificationIsUnchanged() {
        assertEquals(FailureKind.YOUTUBE_VERIFICATION, DownloadErrorClassifier.classify("HTTP Error 429: Too Many Requests"))
    }

    @Test
    fun retriesOnlyWhenARetryCanHelp() {
        val login = "ERROR: [Instagram] X: Instagram sent an empty media response."
        assertTrue(DownloadErrorClassifier.isRecoverableInstagram(login, hasSession = true))
        assertFalse(DownloadErrorClassifier.isRecoverableInstagram(login, hasSession = false))
        assertFalse(
            DownloadErrorClassifier.isRecoverableInstagram("ERROR: [Instagram] X: HTTP Error 429: Too Many Requests", true)
        )
        assertFalse(DownloadErrorClassifier.isRecoverableInstagram("ERROR: [Instagram] X: Video unavailable", true))
        assertTrue(DownloadErrorClassifier.isRecoverableInstagram("ERROR: something new", false))

        val expired = "WARNING: [Instagram] X: The provided Instagram account cookies are no longer valid\n" + login
        assertEquals(FailureKind.INSTAGRAM_LOGIN, DownloadErrorClassifier.classifyInstagram(expired))
        assertFalse(DownloadErrorClassifier.isRecoverableInstagram(expired, hasSession = true))
    }
}
