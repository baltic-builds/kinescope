package com.kinescope.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 45, redrawn in patch 51: what the Home bypass dashboard shows and which controls it offers. */
class BypassDashboardTest {
    private fun inputs(
        ways: Int = 4,
        tunnel: TunnelPhase = TunnelPhase.OFF,
        flow: FlowVerdict = FlowVerdict.IDLE,
        searching: Boolean = false,
        background: Boolean = false,
        mandatory: Boolean = false,
        downloads: Int = 0,
        kbps: Int = 0
    ) = BypassDashInputs(
        waysReady = ways,
        tunnel = tunnel,
        flow = flow,
        searching = searching,
        searchBackground = background,
        searchMandatory = mandatory,
        activeDownloads = downloads,
        downloadKbps = kbps
    )

    @Test
    fun aFreshInstallWithNothingVerifiedOffersOnlyTheCheck() {
        val model = bypassDashboardModel(inputs(ways = 0))
        assertEquals(DashHeadline.SETUP, model.headline)
        assertTrue(model.showSetupAction)
        assertTrue(model.checkEnabled)
        assertFalse(model.showControls)
        assertFalse(model.tunnelSwitchEnabled)
        assertFalse(model.showSearchStrip)
    }

    @Test
    fun theFirstRunCheckSaysWaitAndCannotBeStopped() {
        val model = bypassDashboardModel(inputs(ways = 0, searching = true, mandatory = true))
        assertEquals(DashHeadline.FIRST_RUN, model.headline)
        assertTrue(model.showSearchStrip)
        assertFalse(model.searchStoppable)
        assertFalse(model.showControls)
        assertFalse(model.showSetupAction)
        assertFalse(model.checkEnabled)
    }

    @Test
    fun theFirstRunCheckStaysMandatoryEvenWhenWaysAlreadyExist() {
        // A search that already stored passes (for example after a partial earlier run) is still the mandatory one.
        val model = bypassDashboardModel(inputs(ways = 3, searching = true, mandatory = true))
        assertEquals(DashHeadline.FIRST_RUN, model.headline)
        assertFalse(model.searchStoppable)
        assertFalse(model.tunnelSwitchEnabled)
        assertFalse(model.openEnabled)
    }

    @Test
    fun aVisibleCheckShowsProgressAndHidesTheControlsButCanBeStopped() {
        val model = bypassDashboardModel(inputs(ways = 0, searching = true))
        assertEquals(DashHeadline.CHECKING, model.headline)
        assertTrue(model.showSearchStrip)
        assertTrue(model.searchStoppable)
        assertFalse(model.showControls)
        assertFalse(model.showSetupAction)
        assertFalse(model.checkEnabled)
    }

    @Test
    fun aQuietBackgroundRecheckKeepsTheControlsButNotTheStrategyPicks() {
        val model = bypassDashboardModel(inputs(searching = true, background = true))
        assertEquals(DashHeadline.OFF, model.headline)
        assertTrue(model.showSearchStrip)
        assertTrue(model.searchStoppable)
        assertTrue(model.showControls)
        assertTrue(model.tunnelSwitchEnabled)
        // The results are about to change, so a pick would race the check.
        assertFalse(model.strategiesEnabled)
    }

    @Test
    fun readyAndIdleIsOffWithEveryControlUsable() {
        val model = bypassDashboardModel(inputs())
        assertEquals(DashHeadline.OFF, model.headline)
        assertEquals(DashTunnelState.OFF, model.tunnel)
        assertFalse(model.tunnelSwitchOn)
        assertTrue(model.tunnelSwitchEnabled)
        assertTrue(model.openEnabled)
        assertTrue(model.strategiesEnabled)
        assertEquals(DashStrategyNote.NONE, model.strategyNote)
        assertFalse(model.showSpeed)
    }

    @Test
    fun aRunningTunnelIsOnAndTheSwitchShowsIt() {
        val model = bypassDashboardModel(inputs(tunnel = TunnelPhase.ACTIVE))
        assertEquals(DashHeadline.ON, model.headline)
        assertTrue(model.tunnelSwitchOn)
        assertTrue(model.tunnelSwitchEnabled)
        assertEquals(DashStrategyNote.APPLIES_NEXT, model.strategyNote)
    }

    @Test
    fun aStartingTunnelIsNotInterrupted() {
        val model = bypassDashboardModel(inputs(tunnel = TunnelPhase.STARTING))
        assertEquals(DashHeadline.ON, model.headline)
        assertTrue(model.tunnelSwitchOn)
        assertFalse(model.tunnelSwitchEnabled)
        assertFalse(model.openEnabled)
    }

    @Test
    fun aRunningDownloadShowsTheSpeedAndSaysWhenAStrategyChangeApplies() {
        val model = bypassDashboardModel(inputs(downloads = 1, kbps = 2_048))
        assertTrue(model.showSpeed)
        assertEquals(2_048, model.downloadKbps)
        assertEquals(DashStrategyNote.APPLIES_NEXT, model.strategyNote)
        // The pick itself is still allowed: it only writes a preference, the running download keeps its route.
        assertTrue(model.strategiesEnabled)
    }

    @Test
    fun theSpeedLineWaitsForAFirstReading() {
        val model = bypassDashboardModel(inputs(downloads = 1, kbps = 0))
        assertTrue(model.showSpeed)
        assertEquals(0, model.downloadKbps)
        assertEquals(0, bypassDashboardModel(inputs(downloads = 1, kbps = -5)).downloadKbps)
    }

    @Test
    fun aNewCheckWaitsForRunningDownloadsAndSaysSo() {
        val model = bypassDashboardModel(inputs(ways = 0, downloads = 1))
        assertEquals(DashHeadline.SETUP, model.headline)
        assertFalse(model.checkEnabled)
        assertEquals(DashCheckHint.WAIT_DOWNLOAD, model.checkHint)
        assertEquals(DashCheckHint.NONE, bypassDashboardModel(inputs(ways = 0)).checkHint)
    }

    @Test
    fun theTunnelStateFollowsThePhaseAndTheLastTrafficWindow() {
        fun state(tunnel: TunnelPhase, flow: FlowVerdict) = bypassDashboardModel(inputs(tunnel = tunnel, flow = flow)).tunnel
        assertEquals(DashTunnelState.STARTING, state(TunnelPhase.STARTING, FlowVerdict.IDLE))
        assertEquals(DashTunnelState.ON, state(TunnelPhase.ACTIVE, FlowVerdict.IDLE))
        assertEquals(DashTunnelState.ON, state(TunnelPhase.ACTIVE, FlowVerdict.FLOWING))
        assertEquals(DashTunnelState.NO_ANSWER, state(TunnelPhase.ACTIVE, FlowVerdict.ONE_WAY))
        // A verdict left over from an earlier session means nothing while the tunnel is off.
        assertEquals(DashTunnelState.OFF, state(TunnelPhase.OFF, FlowVerdict.ONE_WAY))
    }

    @Test
    fun starsFollowTheThresholdsOfTheBrief() {
        assertEquals(5, strategyStars(100))
        assertEquals(4, strategyStars(99))
        assertEquals(4, strategyStars(85))
        assertEquals(3, strategyStars(84))
        assertEquals(3, strategyStars(75))
        assertEquals(2, strategyStars(74))
        assertEquals(2, strategyStars(60))
        assertEquals(1, strategyStars(59))
        assertEquals(1, strategyStars(0))
        assertEquals(1, strategyStars(-3))
    }

    private fun result(line: String, score: Int, passed: Boolean = true, kbps: Int = 0) =
        StoredResult(line, score, passed, kbps, 0, 0, 0, 0L)

    @Test
    fun theTopListHoldsOnlyPassedStrategiesThatAreStillInTheList() {
        val candidates = listOf("a", "b", "c", "d")
        val results = listOf(
            result("a", 70),
            result("b", 90),
            result("c", 0, passed = false),
            result("gone", 99)
        ).associateBy { it.line }
        val top = topStrategies(results, candidates)
        assertEquals(listOf("b", "a"), top.map { it.line })
        assertEquals(listOf(4, 2), top.map { it.stars })
    }

    @Test
    fun theTopListIsOrderedByScoreThenSpeedThenListPosition() {
        val candidates = listOf("a", "b", "c", "d")
        val results = listOf(
            result("a", 80, kbps = 100),
            result("b", 80, kbps = 300),
            result("c", 80, kbps = 100),
            result("d", 95)
        ).associateBy { it.line }
        assertEquals(listOf("d", "b", "a", "c"), topStrategies(results, candidates).map { it.line })
    }

    @Test
    fun theTopListHoldsAtMostFifteen() {
        val candidates = (1..40).map { "s$it" }
        val results = candidates.mapIndexed { index, line -> result(line, 50 + index) }.associateBy { it.line }
        val top = topStrategies(results, candidates)
        assertEquals(DASH_STRATEGY_LIMIT, top.size)
        assertEquals(15, DASH_STRATEGY_LIMIT)
        assertEquals("s40", top.first().line)
        assertEquals(topStrategies(results, candidates, limit = 3).size, 3)
    }

    @Test
    fun speedsAreRoundedAndNeverReachAThousandKilobytes() {
        val us = Locale.US
        assertEquals("0 KB/s", formatTransferRate(0, us))
        assertEquals("0 KB/s", formatTransferRate(-5, us))
        assertEquals("<1 KB/s", formatTransferRate(640, us))
        assertEquals("1 KB/s", formatTransferRate(1_000, us))
        assertEquals("2 KB/s", formatTransferRate(1_500, us))
        assertEquals("123 KB/s", formatTransferRate(123_456, us))
        assertEquals("999 KB/s", formatTransferRate(999_499, us))
        assertEquals("1.0 MB/s", formatTransferRate(999_500, us))
        assertEquals("1.2 MB/s", formatTransferRate(1_234_567, us))
        assertEquals("12.3 MB/s", formatTransferRate(12_345_678, us))
    }

    @Test
    fun theDecimalSeparatorFollowsTheLocale() {
        assertEquals("1,2 MB/s", formatTransferRate(1_234_567, Locale.forLanguageTag("ru")))
    }

    @Test
    fun theDashboardLogsUnderTheUiTag() {
        assertEquals("ui", LogFormat.shortTag("BypassDashboard"))
    }
}
