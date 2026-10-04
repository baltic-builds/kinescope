package com.kinescope.app

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Patch 37: turns an `instagram.com/share/reel/<token>` link into the real Reel URL.
 *
 * This is deliberately NOT extraction: it follows HTTP redirects and nothing else. No response
 * body is read, no cookie is sent, no media data is parsed; all of that stays inside yt-dlp.
 * Every hop is validated by [InstagramUrlParser] (exact Instagram host allowlist, http/https
 * only), at most [MAX_HOPS] hops are followed, and every request has a short timeout. Anything
 * unexpected yields null and the caller falls back to handing the share link to yt-dlp as is.
 *
 * Unverified on a device: whether Instagram answers an anonymous request from this app's default
 * HTTP user agent with a redirect (the curl behaviour reported in yt-dlp issue 11630). The
 * pure part ([target]) is unit-tested; the network part is logged so a report shows what happened.
 */
object InstagramShareResolver {
    private const val MAX_HOPS = 4
    private const val TIMEOUT_MS = 8_000
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun isShareUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return (host == "instagram.com" || host.endsWith(".instagram.com")) &&
            uri.path.orEmpty().startsWith("/share/")
    }

    /**
     * Resolves [shareUrl] to a canonical, non-share Reel link, or null. Blocking; call it from
     * the download worker, never from the main thread.
     */
    fun resolve(shareUrl: String): InstagramUrlParser.Parsed? {
        var current = shareUrl
        repeat(MAX_HOPS) {
            val location = redirectLocation(current) ?: return null
            val next = target(current, location) ?: return null
            val parsed = InstagramUrlParser.parse(next).parsed ?: return null
            if (!parsed.isShare) return parsed
            current = parsed.canonicalUrl
        }
        return null
    }

    /**
     * Pure: the absolute URL a `Location` header points at, resolved against [base]. An anonymous
     * visitor is sometimes bounced to `/accounts/login/?next=<real path>`; in that case the
     * `next` value is the target. Returns null when it cannot be resolved.
     */
    internal fun target(base: String, location: String): String? {
        val baseUri = runCatching { URI(base) }.getOrNull() ?: return null
        val resolved = runCatching { baseUri.resolve(location) }.getOrNull() ?: return null
        if (resolved.path.orEmpty().startsWith("/accounts/login")) {
            val next = queryParameter(resolved.rawQuery, "next") ?: return null
            val decoded = runCatching { URLDecoder.decode(next, StandardCharsets.UTF_8.name()) }
                .getOrNull() ?: return null
            return runCatching { baseUri.resolve(decoded).toString() }.getOrNull()
        }
        return resolved.toString()
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

    private fun redirectLocation(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code in REDIRECT_CODES && !location.isNullOrBlank()) location else {
                AppLog.w("DownloadService", "share resolve hop code=$code location=${if (location.isNullOrBlank()) 0 else 1}")
                null
            }
        } catch (e: Exception) {
            AppLog.w("DownloadService", "share resolve failed why=${e.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
