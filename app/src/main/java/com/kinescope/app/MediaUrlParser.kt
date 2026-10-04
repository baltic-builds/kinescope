package com.kinescope.app

/**
 * Patch 37: the single entry point for typed, pasted and shared links. It delegates to the strict
 * per-site parsers ([YouTubeUrlParser], [InstagramUrlParser]) and returns one shape, so enqueue,
 * the share intent and the clipboard all accept exactly the same set of links.
 */
object MediaUrlParser {
    data class Parsed(
        val source: MediaSource,
        /** De-duplication key: the bare YouTube video id, or `ig:<shortcode>`. */
        val mediaId: String,
        val canonicalUrl: String
    )

    fun parse(raw: String): Parsed? {
        YouTubeUrlParser.parse(raw).parsed?.let {
            return Parsed(MediaSource.YOUTUBE, it.videoId, it.canonicalUrl)
        }
        InstagramUrlParser.parse(raw).parsed?.let {
            return Parsed(MediaSource.INSTAGRAM, it.mediaId, it.canonicalUrl)
        }
        return null
    }

    /** The first supported link inside free text (a share payload, the clipboard). */
    fun firstFromText(raw: String): Parsed? {
        YouTubeUrlParser.firstFromText(raw)?.let {
            return Parsed(MediaSource.YOUTUBE, it.videoId, it.canonicalUrl)
        }
        InstagramUrlParser.firstFromText(raw)?.let {
            return Parsed(MediaSource.INSTAGRAM, it.mediaId, it.canonicalUrl)
        }
        return null
    }
}
