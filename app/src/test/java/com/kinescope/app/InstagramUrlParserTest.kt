package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramUrlParserTest {
    private val code = "DXaBcDeFg_H"
    private val reel = "https://www.instagram.com/reel/$code/"

    @Test
    fun canonicalizesReelShapesAndDropsTrackingParameters() {
        val inputs = listOf(
            "https://www.instagram.com/reel/$code/?igsh=MXx1aGJ4",
            "https://instagram.com/reel/$code",
            "https://instagram.com/reels/$code/",
            "https://m.instagram.com/reel/$code/?utm_source=ig_web_copy_link",
            "https://www.instagram.com/some.user/reel/$code/",
            "HTTPS://WWW.INSTAGRAM.COM/reel/$code/"
        )
        inputs.forEach { input ->
            val result = InstagramUrlParser.parse(input)
            assertTrue(input, result.isSuccess)
            assertEquals(input, reel, result.parsed?.canonicalUrl)
            assertEquals(input, "ig:$code", result.parsed?.mediaId)
        }
    }

    @Test
    fun keepsPostAndTvKinds() {
        assertEquals(
            "https://www.instagram.com/p/$code/",
            InstagramUrlParser.parse("https://www.instagram.com/p/$code").parsed?.canonicalUrl
        )
        assertEquals(
            "https://www.instagram.com/tv/$code/",
            InstagramUrlParser.parse("https://www.instagram.com/tv/$code/").parsed?.canonicalUrl
        )
    }

    @Test
    fun extractsLinkFromSharedTextAndTrailingPunctuation() {
        val parsed = InstagramUrlParser.firstFromText("Look at this reel: $reel?igsh=abc). Wow")
        assertNotNull(parsed)
        assertEquals(reel, parsed?.canonicalUrl)
    }

    @Test
    fun rejectsLookalikeHostsPortsUserinfoAndSchemes() {
        listOf(
            "https://instagram.com.evil.example/reel/$code/",
            "https://notinstagram.com/reel/$code/",
            "https://www.instagram.com:444/reel/$code/",
            "https://user@www.instagram.com/reel/$code/",
            "ftp://www.instagram.com/reel/$code/"
        ).forEach { assertFalse(it, InstagramUrlParser.parse(it).isSuccess) }
        assertEquals(
            InstagramUrlParser.Error.UNSUPPORTED_HOST,
            InstagramUrlParser.parse("https://instagram.com.evil.example/reel/$code/").error
        )
    }

    @Test
    fun rejectsNonVideoPages() {
        listOf(
            "https://www.instagram.com/some.user/",
            "https://www.instagram.com/stories/some.user/3412345678901234567/",
            "https://www.instagram.com/explore/tags/cats/",
            "https://www.instagram.com/reel/",
            "https://www.instagram.com/reels/",
            "https://www.instagram.com/reels/audio/123456789/"
        ).forEach {
            assertEquals(it, InstagramUrlParser.Error.NOT_A_VIDEO_URL, InstagramUrlParser.parse(it).error)
        }
    }

    @Test
    fun acceptsShareLinksAndFlagsThem() {
        val result = InstagramUrlParser.parse("https://www.instagram.com/share/reel/_69O6RoGd?igsh=x")
        assertTrue(result.isSuccess)
        assertTrue(result.parsed?.isShare == true)
        assertEquals("https://www.instagram.com/share/reel/_69O6RoGd/", result.parsed?.canonicalUrl)
        assertEquals("igs:_69O6RoGd", result.parsed?.mediaId)

        val bare = InstagramUrlParser.parse("https://instagram.com/share/_69O6RoGd")
        assertEquals("https://www.instagram.com/share/_69O6RoGd/", bare.parsed?.canonicalUrl)
        assertFalse(InstagramUrlParser.parse("$reel").parsed?.isShare == true)
    }

    @Test
    fun rejectsMalformedShareLinksAndBadShortcodes() {
        assertEquals(
            InstagramUrlParser.Error.NOT_A_VIDEO_URL,
            InstagramUrlParser.parse("https://www.instagram.com/share/").error
        )
        assertEquals(
            InstagramUrlParser.Error.NOT_A_VIDEO_URL,
            InstagramUrlParser.parse("https://www.instagram.com/share/stories/a/b").error
        )
        assertEquals(
            InstagramUrlParser.Error.INVALID_SHORTCODE,
            InstagramUrlParser.parse("https://www.instagram.com/share/reel/ab/").error
        )
        assertEquals(
            InstagramUrlParser.Error.INVALID_SHORTCODE,
            InstagramUrlParser.parse("https://www.instagram.com/reel/ab/").error
        )
    }

    @Test
    fun rejectsOversizedAndEmptyInput() {
        assertEquals(InstagramUrlParser.Error.EMPTY, InstagramUrlParser.parse("  ").error)
        val input = reel + "x".repeat(InstagramUrlParser.MAX_INPUT_LENGTH)
        assertEquals(InstagramUrlParser.Error.TOO_LONG, InstagramUrlParser.parse(input).error)
    }
}
