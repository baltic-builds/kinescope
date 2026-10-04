package com.kinescope.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSanitizerTest {
    @Test
    fun redactsInstagramSessionCookies() {
        val raw = "sessionid=abc123%3Adef csrftoken=tok456 ds_user_id=789 ig_did=AAAA-BBBB ok=1"
        val safe = DiagnosticSanitizer.sanitize(raw)

        listOf("abc123", "tok456", "789", "AAAA-BBBB").forEach { assertFalse(it, safe.contains(it)) }
        assertTrue(safe.contains("sessionid=<redacted>"))
        assertTrue(safe.contains("csrftoken=<redacted>"))
        assertTrue(safe.contains("ok=1"))
    }
}
