package com.kinescope.app

/**
 * Patch 34: helpers for the compact diagnostic log.
 *
 * The log is meant to be pasted into an AI chat, so every line is one line of short
 * `key=value` tokens, ids are shortened, and raw yt-dlp output (a wall of retry warnings) is
 * reduced to the one error that matters. Pure functions, unit-tested in LogFormatTest.
 */
internal object LogFormat {
    const val MAX_MESSAGE = 220

    private val uuid = Regex("""\b([0-9a-fA-F]{8})-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val spaces = Regex("""\s+""")
    private val extractorPrefix = Regex("""^\[[^\]]+]\s+(?:[\w-]{6,20}:\s*)?""")
    private val causedBy = Regex("""\s*\(caused by .*$""")
    private val keyAliases = listOf(
        Regex("""(^|\s)job=""") to "$1j=",
        Regex("""(^|\s)profile=""") to "$1p=",
        Regex("""(^|\s)quality=""") to "$1q="
    )
    private val tags = mapOf(
        "DownloadService" to "dl",
        "DpiBypass" to "byp",
        "DpiSearch" to "srch",
        "DpiSearchService" to "srch",
        "BypassVpnService" to "vpn",
        "BypassSettings" to "set",
        "JobStore" to "job",
        "Engine" to "eng",
        "Updater" to "upd",
        "YouTubeAuth" to "auth",
        "MediaStorage" to "media",
        "Library" to "lib",
        "MainActivity" to "ui",
        "App" to "app",
        "Logs" to "log",
        "Crash" to "crash"
    )

    /** A short, stable tag for a component name; unknown names become a lower-case prefix. */
    fun shortTag(component: String): String =
        tags[component] ?: component.lowercase().filter { it.isLetterOrDigit() }.take(6).ifEmpty { "?" }

    /** Collapses whitespace and newlines into single spaces and caps the length. */
    fun oneLine(text: String, max: Int = MAX_MESSAGE): String {
        val flat = spaces.replace(text, " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }

    /** Shortens job ids (UUID to its first 8 hex digits) and common keys, then flattens. */
    fun compact(message: String, max: Int = MAX_MESSAGE): String {
        var text = uuid.replace(message) { it.groupValues[1] }
        for ((pattern, replacement) in keyAliases) text = pattern.replace(text, replacement)
        return oneLine(text, max)
    }

    /**
     * Reduces a yt-dlp failure text to `err="..."`, plus the number of `Retrying` lines and the
     * last unrelated warning. The extractor prefix (`[youtube] <id>:`) and the trailing help text
     * ("Use --cookies ... See <url>") are dropped.
     */
    fun ytdlpSummary(raw: String?): String {
        val lines = raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return "err=\"\""
        val retries = lines.count { it.contains("Retrying (") }
        val error = lines.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")
        val warning = lines.lastOrNull { it.startsWith("WARNING:") && !it.contains("Retrying (") }
            ?.removePrefix("WARNING:")
        val main = error ?: lines.last().removePrefix("WARNING:")
        val builder = StringBuilder("err=\"").append(oneLine(cleanYtdlp(main), 140)).append('"')
        if (retries > 0) builder.append(" retries=").append(retries)
        if (warning != null && error != null) {
            builder.append(" warn=\"").append(oneLine(cleanYtdlp(warning), 80)).append('"')
        }
        return builder.toString()
    }

    /** One-line summary of any throwable: the yt-dlp summary, or `Class: message @File.kt:line`. */
    fun errorSummary(error: Throwable): String {
        val name = error.javaClass.simpleName
        if (name == "YoutubeDLException") return ytdlpSummary(error.message)
        val where = error.stackTrace.firstOrNull { it.className.startsWith("com.kinescope") }
            ?.let { " @${it.fileName ?: "?"}:${it.lineNumber}" }.orEmpty()
        return "err=\"$name: ${oneLine(error.message.orEmpty(), 140)}\"$where"
    }

    /** A few-character reason for a failed probe, so failures can be counted and compared. */
    fun shortReason(detail: String?): String {
        val text = detail.orEmpty().lowercase()
        return when {
            text.isBlank() -> "?"
            "timed out" in text || "timeout" in text -> "timeout"
            "reset" in text -> "reset"
            "refused" in text -> "refused"
            "unreachable" in text -> "unreach"
            "closed" in text || "eof" in text -> "eof"
            "certificate" in text || "trust anchor" in text -> "cert"
            else -> oneLine(text, 24)
        }
    }

    private fun cleanYtdlp(text: String): String =
        causedBy.replace(extractorPrefix.replace(text.trim(), ""), "")
            .substringBefore(". Use --cookies")
            .substringBefore(" See ")
            .trim()
}
