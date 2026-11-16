package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogRetentionTest {
    private val minute = 60_000L
    private val now = 10_000L * minute

    @Test
    fun aStoredLineRoundTrips() {
        val stored = LogRetention.encode(123L, "18:42:40.8 I vpn stall ignored")
        assertEquals(123L to "18:42:40.8 I vpn stall ignored", LogRetention.decode(stored))
    }

    @Test
    fun linesWithoutATimeAreNotLines() {
        assertNull(LogRetention.decode("--- log rotated ---"))
        assertNull(LogRetention.decode("18:42:40.8 I vpn x"))
        assertNull(LogRetention.decode("abc\ttext"))
    }

    @Test
    fun onlyTheLast240MinutesStay() {
        val stored = listOf(
            LogRetention.encode(now - 241 * minute, "old"),
            LogRetention.encode(now - 240 * minute, "edge"),
            LogRetention.encode(now - 10 * minute, "recent"),
            "junk without a time"
        )
        assertEquals(listOf("edge", "recent"), LogRetention.visible(stored, now, 100))
    }

    @Test
    fun theLimitKeepsTheNewestLines() {
        val stored = (1..10).map { LogRetention.encode(now - (11 - it) * minute, "line$it") }
        assertEquals(listOf("line9", "line10"), LogRetention.visible(stored, now, 2))
    }

    @Test
    fun aClockMovedBackKeepsItsLines() {
        val stored = listOf(LogRetention.encode(now + 5 * minute, "future"))
        assertEquals(listOf("future"), LogRetention.visible(stored, now, 10))
    }
}
