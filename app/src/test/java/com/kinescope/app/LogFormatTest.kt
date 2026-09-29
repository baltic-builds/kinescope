package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 34: the compact AI-first log format, fed with lines from the real device logs. */
class LogFormatTest {
    @Test
    fun shortensJobIdsAndCommonKeys() {
        val out = LogFormat.compact(
            "Attempt 1/4 failed job=698d8415-2de4-49b6-af86-ac450cbba6fe profile=DEFAULT recoverable=true"
        )
        assertEquals("Attempt 1/4 failed j=698d8415 p=DEFAULT recoverable=true", out)
    }

    @Test
    fun reducesTheRealDnsFailureToOneLine() {
        val raw = "WARNING: [youtube] [Errno 7] No address associated with hostname. Retrying (1/3)...\n" +
            "WARNING: [youtube] [Errno 7] No address associated with hostname. Retrying (2/3)...\n" +
            "WARNING: [youtube] [Errno 7] No address associated with hostname. Retrying (3/3)...\n" +
            "WARNING: [youtube] O5e9YrptKDk: Unable to download webpage: [Errno 7] No address associated " +
            "with hostname (caused by TransportError('[Errno 7] No address associated with hostname')). " +
            "Giving up after 3 retries\n" +
            "ERROR: [youtube] O5e9YrptKDk: Unable to download API page: [Errno 7] No address associated " +
            "with hostname (caused by TransportError('[Errno 7] No address associated with hostname'))"
        val out = LogFormat.ytdlpSummary(raw)
        assertTrue(out, out.startsWith("err=\"Unable to download API page: [Errno 7] No address associated with hostname\""))
        assertTrue(out, out.contains("retries=3"))
        assertFalse(out, out.contains("caused by"))
        assertFalse(out, out.contains("\n"))
        assertTrue(out.length < 260)
    }

    @Test
    fun reducesTheRealBotCheckToTheErrorAndOneWarning() {
        val raw = "WARNING: [youtube] No title found in player responses; falling back to title from initial data. " +
            "Other metadata may also be missing\n" +
            "ERROR: [youtube] O5e9YrptKDk: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or " +
            "--cookies for the authentication. See  <url>  for how to manually pass cookies. Also see  <url>  for tips"
        val out = LogFormat.ytdlpSummary(raw)
        assertTrue(out, out.startsWith("err=\"Sign in to confirm you\u2019re not a bot\""))
        assertTrue(out, out.contains("warn=\"No title found"))
        assertFalse(out, out.contains("--cookies"))
    }

    @Test
    fun hostUnreachableKeepsItsReason() {
        val raw = "WARNING: [youtube] <urlopen error [Errno 4] Host unreachable>. Retrying (1/3)...\n" +
            "ERROR: [youtube] UUW0kUEjAcU: Unable to download API page: <urlopen error [Errno 4] Host " +
            "unreachable> (caused by ProxyError('<urlopen error [Errno 4] Host unreachable>'))"
        val out = LogFormat.ytdlpSummary(raw)
        assertTrue(out, out.contains("Host unreachable"))
        assertTrue(out, out.contains("retries=1"))
    }

    @Test
    fun capsLengthAndFlattensWhitespace() {
        val out = LogFormat.oneLine("a\n\n  b\t" + "x".repeat(500), 50)
        assertEquals(50, out.length)
        assertTrue(out.startsWith("a b xxx"))
        assertTrue(out.endsWith("\u2026"))
        assertEquals("", LogFormat.oneLine("   \n "))
    }

    @Test
    fun shortReasonNamesTheCommonCauses() {
        assertEquals("timeout", LogFormat.shortReason("SocketTimeoutException: SSL handshake timed out"))
        assertEquals("reset", LogFormat.shortReason("SocketException: Connection reset"))
        assertEquals("refused", LogFormat.shortReason("Connection refused"))
        assertEquals("?", LogFormat.shortReason(null))
    }

    @Test
    fun tagsAreShortAndStable() {
        assertEquals("dl", LogFormat.shortTag("DownloadService"))
        assertEquals("srch", LogFormat.shortTag("DpiSearch"))
        assertEquals("newthi", LogFormat.shortTag("NewThing"))
    }
}
