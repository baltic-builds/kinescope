package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlParserTest {
    private val ytId = "dQw4w9WgXcQ"
    private val igCode = "DXaBcDeFg_H"

    @Test
    fun routesEachSiteToItsOwnParser() {
        val yt = MediaUrlParser.parse("https://youtu.be/$ytId?t=3")
        assertEquals(MediaSource.YOUTUBE, yt?.source)
        assertEquals(ytId, yt?.mediaId)
        assertEquals("https://www.youtube.com/watch?v=$ytId", yt?.canonicalUrl)

        val ig = MediaUrlParser.parse("https://www.instagram.com/reel/$igCode/?igsh=x")
        assertEquals(MediaSource.INSTAGRAM, ig?.source)
        assertEquals("ig:$igCode", ig?.mediaId)
        assertEquals("https://www.instagram.com/reel/$igCode/", ig?.canonicalUrl)
    }

    @Test
    fun rejectsEverythingElse() {
        assertNull(MediaUrlParser.parse("https://example.com/watch?v=$ytId"))
        assertNull(MediaUrlParser.parse("https://www.instagram.com/some.user/"))
        assertNull(MediaUrlParser.parse("https://www.youtube.com/playlist?list=PL123"))
        assertNull(MediaUrlParser.firstFromText("no links here"))
    }

    @Test
    fun findsEitherSiteInSharedText() {
        assertNotNull(MediaUrlParser.firstFromText("see https://youtu.be/$ytId ok"))
        assertEquals(
            MediaSource.INSTAGRAM,
            MediaUrlParser.firstFromText("reel: https://www.instagram.com/reel/$igCode/?igsh=x")?.source
        )
    }

    @Test
    fun shareLinksGetTheirOwnDeduplicationKey() {
        val share = MediaUrlParser.parse("https://www.instagram.com/share/reel/_69O6RoGd")
        assertEquals(MediaSource.INSTAGRAM, share?.source)
        assertEquals("igs:_69O6RoGd", share?.mediaId)
    }

    @Test
    fun instagramAndYoutubeIdsNeverCollide() {
        // An 11-character Instagram shortcode could equal a YouTube id; the "ig:" prefix prevents
        // the duplicate check from treating the two as the same job.
        val same = "dQw4w9WgXcQ"
        assertEquals(same, MediaUrlParser.parse("https://youtu.be/$same")?.mediaId)
        assertEquals("ig:$same", MediaUrlParser.parse("https://www.instagram.com/reel/$same/")?.mediaId)
    }

    @Test
    fun sourceIsDerivedFromTheCanonicalUrl() {
        assertEquals(MediaSource.YOUTUBE, MediaSource.fromCanonicalUrl("https://www.youtube.com/watch?v=$ytId"))
        assertEquals(MediaSource.INSTAGRAM, MediaSource.fromCanonicalUrl("https://www.instagram.com/reel/$igCode/"))
        assertEquals(MediaSource.YOUTUBE, MediaSource.fromCanonicalUrl("not a url"))
    }

    @Test
    fun instagramFormatSelectorAlwaysEndsInAnUnconditionalFallback() {
        val selector = InstagramFormats.selector(720)
        assertEquals("b", selector.substringAfterLast('/'))
    }

    @Test
    fun instagramFormatSelectorPrefersH264BeforeAnythingElse() {
        // Regression for the black-picture Reel: the first two alternatives may only ever select
        // H.264 (explicit avc1) or a muxed file that is not VP9/AV1/HEVC; plain "any video" comes
        // after them. The exact string was checked against yt-dlp 2026.08.19 on Instagram-like
        // format lists (VP9 DASH + unlabelled progressive, VP9 only, avc DASH, progressive only).
        val parts = InstagramFormats.selector(720).split('/')
        assertEquals("bv*[height<=?720][vcodec^=avc]+ba[acodec^=mp4a]", parts[0])
        assertEquals(
            "b[height<=?720][vcodec!^=?vp][vcodec!^=?av0][vcodec!^=?hev][vcodec!^=?hvc]",
            parts[1]
        )
        assertEquals(listOf("bv*[height<=?720]+ba", "b[height<=?720]", "b"), parts.drop(2))
        assertEquals("bv*[height<=?480][vcodec^=avc]+ba[acodec^=mp4a]", InstagramFormats.selector(480).split('/')[0])
    }
}
