package com.kinescope.app

import kotlin.math.exp

// Patch 45, redrawn in patch 51: the rules behind the Home bypass dashboard, as plain data so they can be unit-tested
// on the JVM (`BypassDashboardTest`). `BypassDashboard.kt` only maps the real states into [BypassDashInputs], draws the
// [BypassDashboardModel] it gets back and forwards taps; it decides nothing itself.
//
// Patch 52: the card has ONE switch for the YouTube bypass (the "Watch YouTube" button, once "Unblock YouTube", moved below the
// card and the two no longer repeat each other), and the strategies were a drop-down with an "automatic" switch under it.
// Patch 54 removed the drop-down and stars; patch 58 restores only a compact five-entry menu while automatic is off.
// The full manual choice remains at the bottom of Settings; stars and the old dashboard model code stay gone.
// Patch 55: no speed on the card any more (neither the download's nor the tunnel's); a heartbeat line says whether the bypass
// carries traffic: a green pulse while it does, a red flat line when the connection is lost (`DashPulse`, `HeartbeatLine`).
// Patch 56: a refresh button in the card's top-right corner (`showRefresh`, `refreshEnabled`); a random check counts as a quiet
// check for the card (`searchBackground`), so the controls stay while it runs.
//
// Two different things are called "bypass" in this app:
// - DOWNLOADS: Kinescope's own downloads go through a verified ByeDPI strategy when `DpiPrefs.isEnabled` is on (the
//   default). Since patch 51 that switch lives in Settings only; the dashboard does not offer it.
// - YOUTUBE APP: the YouTube-only `VpnService` tunnel (`BypassVpnService`). The dashboard has its on/off switch.

/** The state of the YouTube-only tunnel, reduced to what the dashboard needs. */
internal enum class TunnelPhase { OFF, STARTING, ACTIVE }

/** Everything the dashboard reacts to, as primitives. [waysReady] is the size of the verified strategy chain. */
internal data class BypassDashInputs(
    val waysReady: Int,
    val tunnel: TunnelPhase,
    /** The verdict of the tunnel's last traffic window; only meaningful while the tunnel is active. */
    val flow: FlowVerdict,
    val searching: Boolean,
    /** The quiet check after an install or an update: downloads keep using the stored ways and the controls stay usable. */
    val searchBackground: Boolean,
    /** The first-run check: it cannot be stopped and the screen says to wait for it. */
    val searchMandatory: Boolean,
    val activeDownloads: Int,
    /** The tunnel stopped on an error (no route, engine failed, ...) and says so; only meaningful while it is off. */
    val tunnelError: Boolean = false
)

internal enum class DashHeadline {
    /** The first-run check: "Please wait while we update the strategies." */
    FIRST_RUN,

    /** A strategy search the user started and can see. */
    CHECKING,

    /** Nothing is verified yet and nothing is running: the only thing to do is to check the ways. */
    SETUP,
    ON,
    OFF
}

internal enum class DashTunnelState {
    OFF,
    STARTING,
    ON,

    /** The tunnel is up and the YouTube app keeps sending, but almost nothing comes back (`FlowVerdict.ONE_WAY`). */
    NO_ANSWER
}

internal enum class DashCheckHint { NONE, WAIT_DOWNLOAD }

/**
 * Patch 55: what the heartbeat line shows. [ALIVE]: the bypass carries traffic (a green pulse). [LOST]: the connection is lost
 * (a red flat line): the tunnel is up but nothing comes back, or it stopped on an error. [IDLE]: nothing to report, the bypass is
 * off by choice or still starting (a muted flat line, so a switch that was turned off on purpose does not look like an alarm).
 */
internal enum class DashPulse { ALIVE, LOST, IDLE }

internal data class BypassDashboardModel(
    val headline: DashHeadline,
    val tunnel: DashTunnelState,
    /** The on/off switch of the YouTube bypass: its position and whether it can be used right now. */
    val tunnelSwitchOn: Boolean,
    val tunnelSwitchEnabled: Boolean,
    /** The "Watch YouTube" button below the card (starts the tunnel when it is off, then opens the chosen app). */
    val openEnabled: Boolean,
    /** The YouTube switch, the automatic-strategy switch and the button below the card. */
    val showControls: Boolean,
    /** Choosing a strategy: not while a search is changing the results. */
    val strategiesEnabled: Boolean,
    /** The progress strip of a running search, and whether the user may stop it (the first-run check cannot be). */
    val showSearchStrip: Boolean,
    val searchStoppable: Boolean,
    /** The primary "Check the ways" button of the not-set-up state. */
    val showSetupAction: Boolean,
    /** Whether a new check may start now (the same rule as the Test button in Settings). */
    val checkEnabled: Boolean,
    val checkHint: DashCheckHint,
    /** Patch 55: the heartbeat line under the YouTube switch. */
    val pulse: DashPulse,
    /** Patch 56: the refresh button in the card's top-right corner (it checks five random ways) and whether it can be pressed now. */
    val showRefresh: Boolean,
    val refreshEnabled: Boolean
)

internal fun bypassDashboardModel(inputs: BypassDashInputs): BypassDashboardModel {
    val ready = inputs.waysReady > 0
    val tunnelOff = inputs.tunnel == TunnelPhase.OFF
    val tunnelStarting = inputs.tunnel == TunnelPhase.STARTING
    val mandatory = inputs.searching && inputs.searchMandatory
    val visibleCheck = inputs.searching && !inputs.searchBackground && !mandatory
    val downloadsBusy = inputs.activeDownloads > 0
    val busyWithCheck = mandatory || visibleCheck

    val headline = when {
        mandatory -> DashHeadline.FIRST_RUN
        visibleCheck -> DashHeadline.CHECKING
        !ready -> DashHeadline.SETUP
        tunnelOff -> DashHeadline.OFF
        else -> DashHeadline.ON
    }
    val controls = ready && !busyWithCheck

    // A new check needs the engine to itself: no other check, no tunnel, no running download (as in Settings).
    val checkIdle = !inputs.searching && tunnelOff
    val checkEnabled = checkIdle && !downloadsBusy

    val tunnelState = when (inputs.tunnel) {
        TunnelPhase.OFF -> DashTunnelState.OFF
        TunnelPhase.STARTING -> DashTunnelState.STARTING
        TunnelPhase.ACTIVE ->
            if (inputs.flow == FlowVerdict.ONE_WAY) DashTunnelState.NO_ANSWER else DashTunnelState.ON
    }

    return BypassDashboardModel(
        headline = headline,
        tunnel = tunnelState,
        tunnelSwitchOn = !tunnelOff,
        // Starting a tunnel needs a verified strategy; a tunnel that is still starting is not interrupted.
        tunnelSwitchEnabled = controls && !tunnelStarting,
        openEnabled = controls && !tunnelStarting,
        showControls = controls,
        strategiesEnabled = controls && !inputs.searching,
        showSearchStrip = inputs.searching,
        searchStoppable = !inputs.searchMandatory,
        showSetupAction = headline == DashHeadline.SETUP && !inputs.searching,
        checkEnabled = checkEnabled,
        checkHint = if (checkIdle && downloadsBusy) DashCheckHint.WAIT_DOWNLOAD else DashCheckHint.NONE,
        pulse = when (tunnelState) {
            DashTunnelState.ON -> DashPulse.ALIVE
            DashTunnelState.NO_ANSWER -> DashPulse.LOST
            DashTunnelState.STARTING -> DashPulse.IDLE
            DashTunnelState.OFF -> if (inputs.tunnelError) DashPulse.LOST else DashPulse.IDLE
        },
        // Patch 56: the refresh sits with the controls (not on a "not set up" card, which has its own check button, nor during the
        // first-run check). It runs beside the tunnel (the strategy in use is never tested), but needs the engine free of another
        // search and of a running download, the rule of the Test button in Settings.
        showRefresh = controls,
        refreshEnabled = controls && !inputs.searching && !downloadsBusy
    )
}

/**
 * The shape of one heartbeat, as a height from -0.32 (the dip after the spike) to 1.0 (the spike) for a position [t] within the
 * beat (0 to 1): a small rise (the P wave), a dip, the spike (the R wave), a dip and a broad low swell (the T wave), the rest
 * lying on the baseline. Plain maths so the shape is unit-tested (`HeartbeatShapeTest`); it lives with the dashboard's model so the drawing code (`HeartbeatLine`) has nothing to test.
 */
internal fun heartbeatSample(t: Float): Float {
    fun bump(center: Float, width: Float, height: Float): Float {
        val d = (t - center) / width
        return height * exp(-0.5f * d * d)
    }
    return bump(0.16f, 0.035f, 0.14f) +
        bump(0.36f, 0.012f, -0.20f) +
        bump(0.40f, 0.014f, 1.00f) +
        bump(0.44f, 0.012f, -0.32f) +
        bump(0.66f, 0.05f, 0.26f)
}
