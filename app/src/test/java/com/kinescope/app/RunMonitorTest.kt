package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 35: the run monitor, fed with lines shaped like real yt-dlp output and a fake clock. */
class RunMonitorTest {
    private var now = 0L
    private val limits = MonitorLimits(startMs = 60_000, jsMs = 180_000, idleMs = 40_000, minKbps = 0)

    private fun monitor(l: MonitorLimits = limits) = RunMonitor(l) { now }

    private fun progress(percent: String, speed: String) =
        "[download]  $percent of  145.67MiB at  $speed ETA 01:23"

    @Test
    fun parsesPercentAndSpeedFromRealLines() {
        val fast = YtdlpLine.parse(progress("12.3%", "1.23MiB/s"))
        assertEquals(12.3f, fast.percent!!, 0.001f)
        assertEquals(1259, fast.kbps)
        assertEquals(512, YtdlpLine.parse(progress("1.0%", "512.00KiB/s")).kbps)
        val unknown = YtdlpLine.parse("[download]   0.0% of ~ 145.67MiB at Unknown B/s ETA Unknown (frag 0/10)")
        assertEquals(0.0f, unknown.percent!!, 0.001f)
        assertEquals(null, unknown.kbps)
        assertEquals(null, YtdlpLine.parse("[youtube] O5e9YrptKDk: Downloading webpage").percent)
    }

    @Test
    fun silenceDuringExtractionStallsOnlyAfterTheStartLimit() {
        val m = monitor()
        m.onLine("[youtube] Extracting URL: https://www.youtube.com/watch?v=x")
        now = 50_000
        assertEquals(RunVerdict.Ok, m.verdict())
        now = 65_000
        val verdict = m.verdict()
        assertTrue(verdict.toString(), verdict is RunVerdict.Stalled && verdict.phase == "start")
    }

    @Test
    fun networkBytesKeepASilentRunAlive() {
        val m = monitor()
        m.onLine("[youtube] Extracting URL: https://www.youtube.com/watch?v=x")
        now = 50_000
        m.onNetworkBytes(200_000)
        now = 90_000
        assertEquals(RunVerdict.Ok, m.verdict())
        m.onNetworkBytes(100)
        now = 130_000
        assertTrue(m.verdict() is RunVerdict.Stalled)
    }

    @Test
    fun theJavaScriptChallengeGetsItsOwnLongerLimit() {
        val m = monitor()
        m.onLine("[youtube] [jsc:quickjs] Solving JS challenges using quickjs")
        now = 150_000
        assertEquals(RunVerdict.Ok, m.verdict())
        now = 200_000
        val verdict = m.verdict()
        assertTrue(verdict.toString(), verdict is RunVerdict.Stalled && verdict.phase == "js")
    }

    @Test
    fun mergingIsNeverJudged() {
        val m = monitor()
        m.onLine(progress("100.0%", "2.00MiB/s"))
        m.onLine("[Merger] Merging formats into \"video.mp4\"")
        now = 900_000
        assertEquals(RunVerdict.Ok, m.verdict())
    }

    @Test
    fun aDownloadThatStopsPrintingStallsAfterTheIdleLimit() {
        val m = monitor()
        m.onLine(progress("10.0%", "900.00KiB/s"))
        now = 30_000
        assertEquals(RunVerdict.Ok, m.verdict())
        now = 45_000
        val verdict = m.verdict()
        assertTrue(verdict.toString(), verdict is RunVerdict.Stalled && verdict.phase == "dl")
    }

    @Test
    fun aRouteUnderTheMinimumSpeedIsSlowOnlyAfterTheWindow() {
        val m = monitor(limits.copy(minKbps = 80, slowWindowMs = 25_000))
        for (i in 0..5) {
            now = i * 5_000L
            m.onLine(progress("${i}.0%", "30.00KiB/s"))
        }
        val verdict = m.verdict()
        assertTrue(verdict.toString(), verdict is RunVerdict.Slow)
        assertEquals(30, (verdict as RunVerdict.Slow).kbps)
    }

    @Test
    fun aFastRouteIsNotSlowAndSpeedIsNotJudgedWithoutALimit() {
        val fast = monitor(limits.copy(minKbps = 80, slowWindowMs = 25_000))
        val unjudged = monitor()
        for (i in 0..6) {
            now = i * 5_000L
            fast.onLine(progress("${i}.0%", "700.00KiB/s"))
            unjudged.onLine(progress("${i}.0%", "5.00KiB/s"))
        }
        assertEquals(RunVerdict.Ok, fast.verdict())
        assertEquals(RunVerdict.Ok, unjudged.verdict())
    }

    @Test
    fun theSnapshotDescribesTheRun() {
        val m = monitor()
        m.onLine("[youtube] Extracting URL: x")
        m.onLine(progress("42.0%", "300.00KiB/s"))
        val s = m.snapshot()
        assertEquals(RunMonitor.Phase.DOWNLOAD, s.phase)
        assertEquals(2, s.lines)
        assertEquals(42.0f, s.percent, 0.001f)
        assertEquals(300, s.kbps)
        assertNotNull(s.lastLine)
    }
}
