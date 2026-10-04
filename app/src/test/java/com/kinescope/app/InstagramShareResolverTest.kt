package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramShareResolverTest {
    private val share = "https://www.instagram.com/share/reel/_69O6RoGd/"

    @Test
    fun recognizesShareUrlsOnlyOnInstagram() {
        assertTrue(InstagramShareResolver.isShareUrl(share))
        assertFalse(InstagramShareResolver.isShareUrl("https://www.instagram.com/reel/DB0YWyzPdcX/"))
        assertFalse(InstagramShareResolver.isShareUrl("https://evil.example/share/reel/_69O6RoGd/"))
        assertFalse(InstagramShareResolver.isShareUrl("not a url"))
    }

    @Test
    fun resolvesAbsoluteAndRelativeLocations() {
        assertEquals(
            "https://www.instagram.com/reel/DB0YWyzPdcX/",
            InstagramShareResolver.target(share, "https://www.instagram.com/reel/DB0YWyzPdcX/")
        )
        assertEquals(
            "https://www.instagram.com/reel/DB0YWyzPdcX/",
            InstagramShareResolver.target(share, "/reel/DB0YWyzPdcX/")
        )
    }

    @Test
    fun unwrapsTheLoginBounceNextParameter() {
        val bounce = "https://www.instagram.com/accounts/login/?next=%2Freel%2FDB0YWyzPdcX%2F&source=share"
        assertEquals(
            "https://www.instagram.com/reel/DB0YWyzPdcX/",
            InstagramShareResolver.target(share, bounce)
        )
        assertNull(InstagramShareResolver.target(share, "/accounts/login/"))
    }

    @Test
    fun targetsAreStillSubjectToTheStrictParser() {
        // target() only resolves; resolve() runs every hop through InstagramUrlParser, which
        // rejects other hosts, so a redirect off Instagram can never be followed or downloaded.
        val offSite = InstagramShareResolver.target(share, "https://evil.example/reel/DB0YWyzPdcX/")
        assertEquals("https://evil.example/reel/DB0YWyzPdcX/", offSite)
        assertFalse(InstagramUrlParser.parse(offSite!!).isSuccess)
        val loginNext = InstagramShareResolver.target(share, "/accounts/login/?next=https%3A%2F%2Fevil.example%2Freel%2FDB0YWyzPdcX%2F")
        assertFalse(InstagramUrlParser.parse(loginNext!!).isSuccess)
    }
}
