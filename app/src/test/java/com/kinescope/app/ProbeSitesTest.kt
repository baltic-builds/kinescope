package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 57: the coverage hosts are real, distinct, valid names, split into site names and video-server nodes. */
class ProbeSitesTest {
    private val name = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    @Test
    fun everyHostIsAValidDistinctLowerCaseName() {
        assertEquals(ProbeSites.ALL.size, ProbeSites.ALL.toSet().size)
        for (host in ProbeSites.ALL) {
            assertTrue("bad host name: $host", name.matches(host))
            assertTrue("too long: $host", host.length <= 253)
        }
    }

    @Test
    fun videoNodesAreGooglevideoSubdomainsAndSiteNamesAreNot() {
        assertTrue(ProbeSites.VIDEO_EDGE_HOSTS.size >= 15)
        for (host in ProbeSites.VIDEO_EDGE_HOSTS) {
            assertTrue(host, host.endsWith(".googlevideo.com"))
            assertTrue(host, Regex("^rr\\d+---sn-[a-z0-9-]+\\.googlevideo\\.com$").matches(host))
            assertTrue(ProbeSites.isEdge(host))
        }
        for (host in ProbeSites.SITE_HOSTS) assertFalse(host, ProbeSites.isEdge(host))
        assertEquals(ProbeSites.SITE_HOSTS.size + ProbeSites.VIDEO_EDGE_HOSTS.size, ProbeSites.ALL.size)
    }

    @Test
    fun theSiteNamesAreYoutubesOwnAndNoBareApexOfAGoogleDomain() {
        assertTrue("youtube.com" in ProbeSites.SITE_HOSTS)
        assertTrue("manifest.googlevideo.com" in ProbeSites.SITE_HOSTS)
        assertFalse("googleapis.com" in ProbeSites.ALL)
        assertFalse("googleusercontent.com" in ProbeSites.ALL)
    }
}
