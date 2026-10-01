package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadQueueBusTest {
    @Test
    fun completionRemovesTheQueueRowAndSignalsLibraryRefresh() {
        DownloadQueueBus.replace(emptyList())
        val before = DownloadQueueBus.completionVersion.value
        DownloadQueueBus.upsert(
            DownloadJobStatus(
                id = "job-1",
                url = "https://www.youtube.com/watch?v=abcdefghijk",
                qualityLabel = "1080p",
                state = JobState.SAVING,
                progressText = "Saving"
            )
        )

        DownloadQueueBus.complete("job-1")

        assertNull(DownloadQueueBus.find("job-1"))
        assertEquals(before + 1L, DownloadQueueBus.completionVersion.value)
    }
}
