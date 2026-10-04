package com.kinescope.app

import java.net.URI

/**
 * Patch 37: which site a download job comes from.
 *
 * It is derived from the job's canonical URL (always produced by [MediaUrlParser]) instead of
 * being stored as a new journal field, so the persisted job format and every existing journal
 * stay valid and nothing needs migrating.
 */
enum class MediaSource {
    YOUTUBE,
    INSTAGRAM;

    companion object {
        fun fromCanonicalUrl(url: String): MediaSource {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase().orEmpty()
            return if (host == "instagram.com" || host.endsWith(".instagram.com")) INSTAGRAM else YOUTUBE
        }
    }
}

/**
 * Patch 37: yt-dlp format selectors for Instagram.
 *
 * A Reel has one or two renditions, not a YouTube-style ladder, so the quality chips only act as
 * an upper height bound. `<=?` keeps a format whose height yt-dlp could not determine, and the
 * final `/b` guarantees that some format is always selected instead of failing with
 * "Requested format is not available".
 */
object InstagramFormats {
    fun selector(maxHeight: Int): String =
        "bv*[height<=?$maxHeight]+ba/b[height<=?$maxHeight]/b"
}
