package com.kinescope.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 45: what the Home bypass dashboard shows and which controls it offers. */
class BypassDashboardTest {
    private fun inputs(
        ways: Int = 4,
        enabled: Boolean = false,
        tunnel: TunnelPhase = TunnelPhase.OFF,
        flow: FlowVerdict = FlowVerdict.IDLE,
        searching: Boolean = false,
        background: Boolean = false,
        downloads: Int = 0
    ) = BypassDashInputs(
        waysReady = ways,
        downloadsEnabled = enabled,
        tunnel = tunnel,
        flow = flow,
        searching = searching,
        searchBackground = background,
        activeDownloads = downloads
    )

    @Test
    fun aFreshInstallOffersOnlyTheCheck() {
        val model = bypassDashboardModel(inputs(ways = 0))
        assertEquals(DashHeadline.SETUP, model.headline)
        assertTrue(model.showSetupAction)
        assertTrue(model.checkEnabled)
        assertFalse(model.showChannels)
        assertFalse(model.switchEnabled)
        assertFalse(model.showSearchStrip)
    }

    @Test
    fun aPreferenceLeftOnWithoutVerifiedWaysDoesNothing() {
        val model = bypassDashboardModel(inputs(ways = 0, enabled = true))
        assertEquals(DashHeadline.SETUP, model.headline)
        assertFalse(model.downloadsViaBypass)
    }

    @Test
    fun aVisibleCheckShowsProgressAndHidesTheControls() {
        val model = bypassDashboardModel(inputs(ways = 0, searching = true))
        assertEquals(DashHeadline.CHECKING, model.headline)
        assertTrue(model.showSearchStrip)
        assertFalse(model.showChannels)
        assertFalse(model.showSetupAction)
        assertFalse(model.checkEnabled)
    }

    @Test
    fun aManualRecheckOfAReadyBypassStillShowsTheCheck() {
        val model = bypassDashboardModel(inputs(enabled = true, searching = true))
        assertEquals(DashHeadline.CHECKING, model.headline)
        assertFalse(model.switchEnabled)
    }

    @Test
    fun aQuietBackgroundRecheckKeepsTheControlsAndShowsTheStrip() {
        val model = bypassDashboardModel(inputs(enabled = true, searching = true, background = true))
        assertEquals(DashHeadline.ON, model.headline)
        assertTrue(model.showChannels)
        assertTrue(model.showSearchStrip)
        assertTrue(model.switchEnabled)
        assertFalse(model.checkEnabled)
    }

    @Test
    fun readyAndSwitchedOffIsOff() {
        val model = bypassDashboardModel(inputs())
        assertEquals(DashHeadline.OFF, model.headline)
        assertFalse(model.downloadsViaBypass)
        assertTrue(model.switchEnabled)
        assertTrue(model.checkEnabled)
        assertEquals(DashDownloadsNote.NONE, model.downloadsNote)
    }

    @Test
    fun theSwitchMeansDownloadsGoThroughTheBypass() {
        val model = bypassDashboardModel(inputs(enabled = true))
        assertEquals(DashHeadline.ON, model.headline)
        assertTrue(model.downloadsViaBypass)
        assertTrue(model.switchEnabled)
    }

    @Test
    fun aRunningTunnelKeepsDownloadsOnAndLocksTheSwitch() {
        // DpiBypass.activeChain uses the bypass while the tunnel is active, whatever the preference says.
        val model = bypassDashboardModel(inputs(enabled = false, tunnel = TunnelPhase.ACTIVE))
        assertEquals(DashHeadline.ON, model.headline)
        assertTrue(model.downloadsViaBypass)
        assertFalse(model.switchEnabled)
        assertEquals(DashDownloadsNote.TUNNEL_KEEPS_ON, model.downloadsNote)
        assertFalse(model.checkEnabled)
        assertEquals(DashCheckHint.NONE, model.checkHint)
    }

    @Test
    fun aStartingTunnelCountsAsOnButDoesNotChangeDownloadsYet() {
        val model = bypassDashboardModel(inputs(enabled = false, tunnel = TunnelPhase.STARTING))
        assertEquals(DashHeadline.ON, model.headline)
        assertFalse(model.downloadsViaBypass)
        assertFalse(model.switchEnabled)
        assertEquals(DashTunnelState.STARTING, model.tunnel)
    }

    @Test
    fun aRunningDownloadDoesNotLockTheSwitchButSaysWhenItApplies() {
        val model = bypassDashboardModel(inputs(enabled = true, downloads = 2))
        assertTrue(model.switchEnabled)
        assertEquals(DashDownloadsNote.APPLIES_NEXT, model.downloadsNote)
    }

    @Test
    fun aNewCheckWaitsForRunningDownloadsAndSaysSo() {
        val ready = bypassDashboardModel(inputs(downloads = 1))
        assertFalse(ready.checkEnabled)
        assertEquals(DashCheckHint.WAIT_DOWNLOAD, ready.checkHint)

        val fresh = bypassDashboardModel(inputs(ways = 0, downloads = 1))
        assertTrue(fresh.showSetupAction)
        assertFalse(fresh.checkEnabled)
        assertEquals(DashCheckHint.WAIT_DOWNLOAD, fresh.checkHint)
    }

    @Test
    fun theTunnelStateFollowsThePhaseAndTheLastTrafficWindow() {
        fun state(tunnel: TunnelPhase, flow: FlowVerdict) = bypassDashboardModel(inputs(tunnel = tunnel, flow = flow)).tunnel
        assertEquals(DashTunnelState.OFF, state(TunnelPhase.OFF, FlowVerdict.IDLE))
        assertEquals(DashTunnelState.STARTING, state(TunnelPhase.STARTING, FlowVerdict.IDLE))
        assertEquals(DashTunnelState.ON, state(TunnelPhase.ACTIVE, FlowVerdict.IDLE))
        assertEquals(DashTunnelState.ON, state(TunnelPhase.ACTIVE, FlowVerdict.FLOWING))
        assertEquals(DashTunnelState.NO_ANSWER, state(TunnelPhase.ACTIVE, FlowVerdict.ONE_WAY))
        // A verdict left over from an earlier session means nothing while the tunnel is off.
        assertEquals(DashTunnelState.OFF, state(TunnelPhase.OFF, FlowVerdict.ONE_WAY))
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
