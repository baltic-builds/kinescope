package com.kinescope.app

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
        error: Boolean = false
    ) = BypassDashInputs(
        waysReady = ways,
        tunnel = tunnel,
        flow = flow,
        searching = searching,
        searchBackground = background,
        searchMandatory = mandatory,
        activeDownloads = downloads,
        tunnelError = error
    )

    @Test
    fun theRefreshButtonSitsWithTheControlsAndCanBePressedWhenNothingElseNeedsTheEngine() {
        val idle = bypassDashboardModel(inputs())
        assertTrue(idle.showRefresh)
        assertTrue(idle.refreshEnabled)
        // It runs beside the tunnel: the strategy in use is never tested.
        val withTunnel = bypassDashboardModel(inputs(tunnel = TunnelPhase.ACTIVE, flow = FlowVerdict.FLOWING))
        assertTrue(withTunnel.showRefresh)
        assertTrue(withTunnel.refreshEnabled)
    }

    @Test
    fun theRefreshButtonIsOnlyAbsentDuringTheFirstRunCheck() {
        // Patch 64: it is there on a card that is not set up and during a check the user can see; only the check that cannot be stopped hides it.
        assertTrue(bypassDashboardModel(inputs(ways = 0)).showRefresh)
        assertTrue(bypassDashboardModel(inputs(searching = true)).showRefresh)
        assertFalse(bypassDashboardModel(inputs(ways = 3, searching = true, mandatory = true)).showRefresh)
    }

    @Test
    fun theRefreshButtonCanAlwaysBePressed() {
        // Patch 64: a running check, a running download, a running tunnel: the press stops them, refreshes and starts them again.
        assertTrue(bypassDashboardModel(inputs(searching = true, background = true)).refreshEnabled)
        assertTrue(bypassDashboardModel(inputs(downloads = 1)).refreshEnabled)
        assertTrue(bypassDashboardModel(inputs(tunnel = TunnelPhase.STARTING)).refreshEnabled)
        val quiet = bypassDashboardModel(inputs(searching = true, background = true))
        assertTrue(quiet.showControls)
        assertTrue(quiet.showSearchStrip)
    }

    @Test
    fun aFreshInstallWithNothingVerifiedOffersOnlyTheCheck() {
        val model = bypassDashboardModel(inputs(ways = 0))
        assertEquals(DashHeadline.SETUP, model.headline)
        assertTrue(model.showSetupAction)
        assertTrue(model.checkEnabled)
        assertFalse(model.showControls)
        assertFalse(model.openEnabled)
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
        assertTrue(model.openEnabled)
        // The results are about to change, so a pick would race the check.
        assertFalse(model.strategiesEnabled)
    }

    @Test
    fun readyAndIdleIsOffWithEveryControlUsable() {
        val model = bypassDashboardModel(inputs())
        assertEquals(DashHeadline.OFF, model.headline)
        assertEquals(DashTunnelState.OFF, model.tunnel)
        assertTrue(model.openEnabled)
        assertTrue(model.strategiesEnabled)
    }

    @Test
    fun aRunningTunnelIsOnAndWatchYouTubeStaysUsable() {
        val model = bypassDashboardModel(inputs(tunnel = TunnelPhase.ACTIVE))
        assertEquals(DashHeadline.ON, model.headline)
        assertEquals(DashTunnelState.ON, model.tunnel)
        assertTrue(model.openEnabled)
    }

    @Test
    fun aStartingTunnelIsNotInterrupted() {
        val model = bypassDashboardModel(inputs(tunnel = TunnelPhase.STARTING))
        assertEquals(DashHeadline.ON, model.headline)
        assertEquals(DashTunnelState.STARTING, model.tunnel)
        assertFalse(model.openEnabled)
    }

    @Test
    fun aRunningDownloadDoesNotLockTheStrategyControls() {
        // The pick itself is still allowed: it only writes a preference, the running download keeps its route.
        assertTrue(bypassDashboardModel(inputs(downloads = 1)).strategiesEnabled)
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
    fun theDashboardLogsUnderTheUiTag() {
        assertEquals("ui", LogFormat.shortTag("BypassDashboard"))
    }

    @Test
    fun theHeartbeatPulsesGreenOnlyWhileTrafficIsMeasuredToFlow() {
        fun pulse(tunnel: TunnelPhase, flow: FlowVerdict, error: Boolean = false) =
            bypassDashboardModel(inputs(tunnel = tunnel, flow = flow, error = error)).pulse
        assertEquals(DashPulse.ALIVE, pulse(TunnelPhase.ACTIVE, FlowVerdict.FLOWING))
        // A tunnel that is up and has seen no traffic yet is still the bypass working.
        assertEquals(DashPulse.ALIVE, pulse(TunnelPhase.ACTIVE, FlowVerdict.IDLE))
        // Sending with nothing coming back is a lost connection.
        assertEquals(DashPulse.LOST, pulse(TunnelPhase.ACTIVE, FlowVerdict.ONE_WAY))
    }

    @Test
    fun theHeartbeatIsFlatRedWhenTheBypassStoppedOnAnErrorAndMutedWhenItIsSimplyOff() {
        fun pulse(tunnel: TunnelPhase, error: Boolean) = bypassDashboardModel(inputs(tunnel = tunnel, error = error)).pulse
        assertEquals(DashPulse.LOST, pulse(TunnelPhase.OFF, error = true))
        assertEquals(DashPulse.IDLE, pulse(TunnelPhase.OFF, error = false))
        assertEquals(DashPulse.IDLE, pulse(TunnelPhase.STARTING, error = false))
    }
}
