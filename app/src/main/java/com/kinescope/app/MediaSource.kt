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
 * yt-dlp format selectors for Instagram (patch 37, codec rule added in patch 38).
 *
 * A Reel has one or two renditions, not a YouTube-style ladder, so the quality chips only act as
 * an upper height bound. `<=?` keeps a format whose height yt-dlp could not determine.
 *
 * Codec rule: a saved Reel must play on the phone, and patch 37's plain `bv*+ba` did not. yt-dlp
 * sorts a labelled video codec above an unlabelled one, and for Instagram only the VP9 DASH
 * streams carry a label (the H.264 downloads have none, yt-dlp issue 12394), so VP9 won and the
 * saved MP4 played as a black picture with sound. The order below is therefore:
 *   1. an explicit H.264 (avc1) DASH video merged with AAC audio;
 *   2. a muxed progressive file whose codec is not VP9/AV1/HEVC (`!^=?` keeps an unlabelled one);
 *   3. any video plus audio, then any muxed file, then the unconditional `b`, so a Reel that is
 *      only offered as VP9 still downloads (it may then not play; see ROADMAP, patch 38).
 */
object InstagramFormats {
    private const val NOT_VP9_AV1_HEVC =
        "[vcodec!^=?vp][vcodec!^=?av0][vcodec!^=?hev][vcodec!^=?hvc]"

    fun selector(maxHeight: Int): String {
        val cap = "[height<=?$maxHeight]"
        return "bv*$cap[vcodec^=avc]+ba[acodec^=mp4a]" +
            "/b$cap$NOT_VP9_AV1_HEVC" +
            "/bv*$cap+ba" +
            "/b$cap" +
            "/b"
    }
}
