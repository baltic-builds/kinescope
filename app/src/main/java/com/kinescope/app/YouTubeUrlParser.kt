package com.kinescope.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Strict, shared parser for typed, pasted and shared YouTube video URLs. */
object YouTubeUrlParser {
    const val MAX_INPUT_LENGTH = 4_096

    data class Parsed(
        val videoId: String,
        val canonicalUrl: String
    )

    enum class Error {
        EMPTY,
        TOO_LONG,
        NO_URL,
        UNSUPPORTED_SCHEME,
        UNSUPPORTED_HOST,
        USERINFO_NOT_ALLOWED,
        UNEXPECTED_PORT,
        NOT_A_VIDEO_URL,
        INVALID_VIDEO_ID
    }

    data class Result(val parsed: Parsed? = null, val error: Error? = null) {
        val isSuccess: Boolean get() = parsed != null
    }

    fun parse(raw: String): Result {
        val input = raw.trim()
        if (input.isEmpty()) return Result(error = Error.EMPTY)
        if (input.length > MAX_INPUT_LENGTH) return Result(error = Error.TOO_LONG)

        val candidate = extractCandidates(input).firstOrNull() ?: return Result(error = Error.NO_URL)
        return parseCandidate(candidate)
    }

    fun firstFromText(raw: String): Parsed? {
        if (raw.length > MAX_INPUT_LENGTH) return null
        return extractCandidates(raw).asSequence()
            .map(::parseCandidate)
            .firstOrNull { it.isSuccess }
            ?.parsed
    }

    private fun parseCandidate(rawCandidate: String): Result {
        val candidate = rawCandidate.trimTrailingPunctuation()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return Result(error = Error.NO_URL)
        val scheme = uri.scheme?.lowercase() ?: return Result(error = Error.UNSUPPORTED_SCHEME)
        if (scheme !in setOf("http", "https")) return Result(error = Error.UNSUPPORTED_SCHEME)
        if (uri.userInfo != null) return Result(error = Error.USERINFO_NOT_ALLOWED)

        val port = uri.port
        if (port != -1 && !((scheme == "http" && port == 80) || (scheme == "https" && port == 443))) {
            return Result(error = Error.UNEXPECTED_PORT)
        }

        val host = uri.host?.lowercase()?.trimEnd('.') ?: return Result(error = Error.UNSUPPORTED_HOST)
        if (host !in YOUTUBE_HOSTS) return Result(error = Error.UNSUPPORTED_HOST)

        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> pathSegments.firstOrNull()
            pathSegments.firstOrNull()?.lowercase() == "watch" -> queryParameter(uri.rawQuery, "v")
            pathSegments.firstOrNull()?.lowercase() in DIRECT_VIDEO_PATHS -> pathSegments.getOrNull(1)
            else -> null
        }?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

        if (id == null) return Result(error = Error.NOT_A_VIDEO_URL)
        if (!VIDEO_ID.matches(id)) return Result(error = Error.INVALID_VIDEO_ID)

        return Result(
            parsed = Parsed(
                videoId = id,
                canonicalUrl = "https://www.youtube.com/watch?v=$id"
            )
        )
    }

    private fun queryParameter(rawQuery: String?, key: String): String? = rawQuery
        ?.split('&')
        ?.asSequence()
        ?.mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) null else pair.substring(0, separator) to pair.substring(separator + 1)
        }
        ?.firstOrNull { it.first == key }
        ?.second

    private fun extractCandidates(text: String): List<String> =
        URL_REGEX.findAll(text).map { it.value }.toList()

    private fun String.trimTrailingPunctuation(): String =
        trimEnd { it in TRAILING_PUNCTUATION }

    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")
    private val DIRECT_VIDEO_PATHS = setOf("shorts", "live", "embed")
    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be"
    )
    private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
}
