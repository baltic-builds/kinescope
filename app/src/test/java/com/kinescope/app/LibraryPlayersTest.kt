package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 64: the pure parts of the library player choice. */
class LibraryPlayersTest {
    @Test
    fun theKindOfAFileComesFromItsMimeType() {
        assertEquals(MediaKind.AUDIO, MediaKind.of("audio/mp4"))
        assertEquals(MediaKind.AUDIO, MediaKind.of("AUDIO/mpeg"))
        assertEquals(MediaKind.VIDEO, MediaKind.of("video/mp4"))
        // Anything that is not audio is opened as video, as before.
        assertEquals(MediaKind.VIDEO, MediaKind.of("application/octet-stream"))
    }

    @Test
    fun theListHasNoDuplicatesNorTheAppItselfAndIsSortedByName() {
        val found = listOf(
            PlayerApp("org.videolan.vlc", "VLC"),
            PlayerApp("com.kinescope.app", "Kinescope"),
            PlayerApp("com.google.android.apps.photos", "Photos"),
            PlayerApp("org.videolan.vlc", "VLC"),
            PlayerApp("com.mxtech.videoplayer.ad", "mx player")
        )
        val ordered = LibraryPlayers.order(found, exclude = "com.kinescope.app")
        assertEquals(listOf("mx player", "Photos", "VLC"), ordered.map { it.label })
    }
}
