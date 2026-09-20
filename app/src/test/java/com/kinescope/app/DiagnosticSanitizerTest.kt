package com.kinescope.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun redactsUrlsCookiesAndPrivatePaths() {
        val raw = "url=https://youtu.be/dQw4w9WgXcQ SAPISID=secret " +
            "path=/data/user/0/com.kinescope.app/files/youtube-cookies.txt"
        val safe = DiagnosticSanitizer.sanitize(raw)

        assertFalse(safe.contains("dQw4w9WgXcQ"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("/data/user/0/com.kinescope.app"))
        assertTrue(safe.contains("<url>"))
        assertTrue(safe.contains("SAPISID=<redacted>"))
        assertTrue(safe.contains("<app-path>"))
    }

    @Test
    fun boundsDiagnosticLength() {
        val safe = DiagnosticSanitizer.sanitize("x".repeat(5_000))
        assertTrue(safe.length <= 2_000)
    }
}
