package com.kinescope.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Patch 37: strict parser for a single Instagram Reel (or video post) link.
 *
 * Same rules as [YouTubeUrlParser]: http/https only, no userinfo, no unusual port, an exact host
 * allowlist (lookalike hosts are rejected), bounded input, and a canonical form that drops every
 * tracking parameter (`igsh`, `utm_*`). Profiles, stories and highlights are rejected on purpose.
 *
 * `/share/reel/<token>` links (what the Instagram app's "Copy link" often produces) are accepted
 * but flagged [Parsed.isShare]: the token is not a shortcode, and the real Reel URL is only found
 * by following Instagram's redirect, which [InstagramShareResolver] does before yt-dlp runs.
 */
object InstagramUrlParser {
    const val MAX_INPUT_LENGTH = YouTubeUrlParser.MAX_INPUT_LENGTH

    data class Parsed(
        /** The Reel shortcode, or the share token when [isShare]. */
        val shortcode: String,
        val canonicalUrl: String,
        val isShare: Boolean = false
    ) {
        /** Journal and de-duplication key. The prefix keeps it from ever equalling a YouTube id. */
        val mediaId: String get() = if (isShare) "igs:$shortcode" else "ig:$shortcode"
    }

    enum class Error {
        EMPTY,
        TOO_LONG,
        NO_URL,
        UNSUPPORTED_SCHEME,
        UNSUPPORTED_HOST,
        USERINFO_NOT_ALLOWED,
        UNEXPECTED_PORT,
        NOT_A_VIDEO_URL,
        INVALID_SHORTCODE
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
        if (host !in INSTAGRAM_HOSTS) return Result(error = Error.UNSUPPORTED_HOST)

        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull()?.lowercase()
        if (first == "share") return parseShare(segments)

        // /<kind>/<shortcode>   or   /<username>/<kind>/<shortcode>
        val kindIndex = when {
            first != null && first in KINDS -> 0
            segments.size >= 3 &&
                first != null && first !in RESERVED_FIRST_SEGMENTS &&
                USERNAME.matches(segments[0]) &&
                segments[1].lowercase() in KINDS -> 1
            else -> -1
        }
        if (kindIndex < 0) return Result(error = Error.NOT_A_VIDEO_URL)

        val kind = segments[kindIndex].lowercase()
        val rawCode = segments.getOrNull(kindIndex + 1) ?: return Result(error = Error.NOT_A_VIDEO_URL)
        // /reels/audio/<id>/ is the page of a sound, not of a video.
        if (kind == "reels" && rawCode.lowercase() == "audio") return Result(error = Error.NOT_A_VIDEO_URL)

        val code = runCatching { URLDecoder.decode(rawCode, StandardCharsets.UTF_8.name()) }
            .getOrNull() ?: return Result(error = Error.INVALID_SHORTCODE)
        if (!SHORTCODE.matches(code)) return Result(error = Error.INVALID_SHORTCODE)

        val canonicalKind = if (kind == "reels") "reel" else kind
        return Result(
            parsed = Parsed(
                shortcode = code,
                canonicalUrl = "https://www.instagram.com/$canonicalKind/$code/"
            )
        )
    }

    /** `/share/<token>` or `/share/<reel|p|tv>/<token>`. */
    private fun parseShare(segments: List<String>): Result {
        val kind = segments.getOrNull(1)?.lowercase()
        val (kindPart, rawToken) = when {
            segments.size == 2 -> "" to segments[1]
            segments.size == 3 && kind in SHARE_KINDS -> "$kind/" to segments[2]
            else -> return Result(error = Error.NOT_A_VIDEO_URL)
        }
        val token = runCatching { URLDecoder.decode(rawToken, StandardCharsets.UTF_8.name()) }
            .getOrNull() ?: return Result(error = Error.INVALID_SHORTCODE)
        if (!SHORTCODE.matches(token)) return Result(error = Error.INVALID_SHORTCODE)
        return Result(
            parsed = Parsed(
                shortcode = token,
                canonicalUrl = "https://www.instagram.com/share/$kindPart$token/",
                isShare = true
            )
        )
    }

    private fun extractCandidates(text: String): List<String> =
        URL_REGEX.findAll(text).map { it.value }.toList()

    private fun String.trimTrailingPunctuation(): String =
        trimEnd { it in TRAILING_PUNCTUATION }

    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val SHORTCODE = Regex("^[A-Za-z0-9_-]{5,30}$")
    private val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")
    private val KINDS = setOf("reel", "reels", "p", "tv")
    private val SHARE_KINDS = setOf("reel", "p", "tv")
    private val RESERVED_FIRST_SEGMENTS = setOf(
        "share", "stories", "explore", "accounts", "direct", "web", "about", "developer", "legal", "api",
        "reel", "reels", "p", "tv"
    )
    private val INSTAGRAM_HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
}
