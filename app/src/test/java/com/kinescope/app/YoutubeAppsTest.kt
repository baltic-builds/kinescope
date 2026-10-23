package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 51: the list of apps that "Unblock YouTube" can open. */
class YoutubeAppsTest {
    private val youtube = YoutubeApp(YoutubeApps.DEFAULT_PACKAGE, "YouTube")

    @Test
    fun theOfficialAppIsTheDefaultPackage() {
        assertEquals("com.google.android.youtube", YoutubeApps.DEFAULT_PACKAGE)
    }

    @Test
    fun theDefaultComesFirstAndTheRestByName() {
        val found = listOf(
            YoutubeApp("org.mozilla.firefox", "Firefox"),
            youtube,
            YoutubeApp("app.example.client", "alpha client"),
            YoutubeApp("com.android.chrome", "Chrome")
        )
        val ordered = YoutubeApps.order(found, YoutubeApps.DEFAULT_PACKAGE).map { it.label }
        assertEquals(listOf("YouTube", "alpha client", "Chrome", "Firefox"), ordered)
    }

    @Test
    fun anAppFoundTwiceIsListedOnce() {
        val found = listOf(youtube, YoutubeApp("com.android.chrome", "Chrome"), YoutubeApp("com.android.chrome", "Chrome"))
        assertEquals(2, YoutubeApps.order(found, YoutubeApps.DEFAULT_PACKAGE).size)
    }

    @Test
    fun kinescopeItselfIsNeverOffered() {
        val found = listOf(youtube, YoutubeApp("com.kinescope.app", "Kinescope"))
        val ordered = YoutubeApps.order(found, YoutubeApps.DEFAULT_PACKAGE, exclude = "com.kinescope.app")
        assertEquals(listOf("YouTube"), ordered.map { it.label })
    }

    @Test
    fun withoutTheOfficialAppTheOthersStillSort() {
        val found = listOf(YoutubeApp("b", "Beta"), YoutubeApp("a", "alpha"))
        assertEquals(listOf("alpha", "Beta"), YoutubeApps.order(found, YoutubeApps.DEFAULT_PACKAGE).map { it.label })
    }
}
