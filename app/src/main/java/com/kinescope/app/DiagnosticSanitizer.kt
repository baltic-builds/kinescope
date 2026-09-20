package com.kinescope.app

/** Pure privacy filter shared by persistent diagnostics and JVM tests. */
object DiagnosticSanitizer {
    private const val MAX_LENGTH = 2_000

    private val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val cookieRegex = Regex(
        """(?i)(SAPISID|APISID|SID|SSID|LOGIN_INFO|__Secure-[A-Za-z0-9_-]+)=([^;\s]+)"""
    )
    private val appPathRegex = Regex("""/(data|storage)/[^\s]+/com\.kinescope\.app/[^\s]*""")

    fun sanitize(value: String): String = value
        .replace(urlRegex, "<url>")
        .replace(cookieRegex) { "${it.groupValues[1]}=<redacted>" }
        .replace(appPathRegex, "<app-path>")
        .replace('\u0000', ' ')
        .take(MAX_LENGTH)
}
