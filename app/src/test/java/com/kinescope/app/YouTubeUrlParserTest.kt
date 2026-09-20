package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlParserTest {
    private val id = "dQw4w9WgXcQ"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test
    fun canonicalizesSupportedVideoShapes() {
        val inputs = listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtu.be/$id?t=42",
            "https://m.youtube.com/shorts/$id",
            "https://youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id"
        )
        inputs.forEach { input ->
            val result = YouTubeUrlParser.parse(input)
            assertTrue(input, result.isSuccess)
            assertEquals(canonical, result.parsed?.canonicalUrl)
        }
    }

    @Test
    fun extractsVideoFromSharedTextAndTrailingPunctuation() {
        val parsed = YouTubeUrlParser.firstFromText("Watch this: https://youtu.be/$id). Thanks")
        assertNotNull(parsed)
        assertEquals(canonical, parsed?.canonicalUrl)
    }

    @Test
    fun rejectsPlaylistOnlyAndLookalikeHosts() {
        assertFalse(YouTubeUrlParser.parse("https://www.youtube.com/playlist?list=PL123").isSuccess)
        assertFalse(YouTubeUrlParser.parse("https://youtube.com.evil.example/watch?v=$id").isSuccess)
        assertFalse(YouTubeUrlParser.parse("https://youtube.com:444/watch?v=$id").isSuccess)
    }

    @Test
    fun rejectsOversizedInput() {
        val input = "https://youtu.be/$id" + "x".repeat(YouTubeUrlParser.MAX_INPUT_LENGTH)
        assertEquals(YouTubeUrlParser.Error.TOO_LONG, YouTubeUrlParser.parse(input).error)
    }
}
