package com.kinescope.app

import java.util.Locale

// Patch 45: the rules behind the Home bypass dashboard, as plain data so they can be unit-tested on the JVM
// (`BypassDashboardTest`). `BypassDashboard.kt` only maps the real states into [BypassDashInputs], draws the
// [BypassDashboardModel] it gets back and forwards taps; it decides nothing itself.
//
// Two different things are called "bypass" in this app, and the dashboard keeps them apart:
// - DOWNLOADS: Kinescope's own downloads go through a verified ByeDPI strategy when `DpiPrefs.isEnabled` is on.
//   `DpiBypass.activeChain` also returns the chain while the YouTube tunnel is active, whatever the preference says,
//   so while the tunnel runs the preference has no effect: the dashboard shows the switch as on and locked.
// - YOUTUBE APP: the YouTube-only `VpnService` tunnel (`BypassVpnService`), started and stopped on its own.

/** The state of the YouTube-only tunnel, reduced to what the dashboard needs. */
internal enum class TunnelPhase { OFF, STARTING, ACTIVE }

/** Everything the dashboard reacts to, as primitives. [waysReady] is the size of the verified strategy chain. */
internal data class BypassDashInputs(
    val waysReady: Int,
    val downloadsEnabled: Boolean,
    val tunnel: TunnelPhase,
    /** The verdict of the tunnel's last traffic window; only meaningful while the tunnel is active. */
    val flow: FlowVerdict,
    val searching: Boolean,
    /** The quiet 25-day re-check: downloads keep using the stored ways and the switch stays usable. */
    val searchBackground: Boolean,
    val activeDownloads: Int
)

internal enum class DashHeadline {
    /** A strategy search the user can see is running. */
    CHECKING,

    /** Nothing is verified yet: the only thing to do is to check the ways. */
    SETUP,
    ON,
    OFF
}

internal enum class DashDownloadsNote { NONE, APPLIES_NEXT, TUNNEL_KEEPS_ON }

internal enum class DashTunnelState {
    OFF,
    STARTING,
    ON,

    /** The tunnel is up and the YouTube app keeps sending, but almost nothing comes back (`FlowVerdict.ONE_WAY`). */
    NO_ANSWER
}

internal enum class DashCheckHint { NONE, WAIT_DOWNLOAD }

internal data class BypassDashboardModel(
    val headline: DashHeadline,
    /** Whether downloads really go through the bypass right now (the switch position). */
    val downloadsViaBypass: Boolean,
    val switchEnabled: Boolean,
    val downloadsNote: DashDownloadsNote,
    val tunnel: DashTunnelState,
    /** The two channel rows (downloads, YouTube app) with their controls. */
    val showChannels: Boolean,
    /** The primary "Check the ways" button of the not-set-up state. */
    val showSetupAction: Boolean,
    /** The progress strip of a running search. */
    val showSearchStrip: Boolean,
    /** Whether a new check may start now (the same rule as the Test button in Settings). */
    val checkEnabled: Boolean,
    val checkHint: DashCheckHint
)

internal fun bypassDashboardModel(inputs: BypassDashInputs): BypassDashboardModel {
    val ready = inputs.waysReady > 0
    val tunnelUp = inputs.tunnel == TunnelPhase.ACTIVE
    val tunnelOff = inputs.tunnel == TunnelPhase.OFF
    val visibleCheck = inputs.searching && !inputs.searchBackground
    val downloadsBusy = inputs.activeDownloads > 0

    val headline = when {
        visibleCheck -> DashHeadline.CHECKING
        !ready -> DashHeadline.SETUP
        inputs.downloadsEnabled || !tunnelOff -> DashHeadline.ON
        else -> DashHeadline.OFF
    }
    val channels = headline == DashHeadline.ON || headline == DashHeadline.OFF

    // A new check needs the engine to itself: no other check, no tunnel, no running download (as in Settings).
    val checkIdle = !inputs.searching && tunnelOff
    val checkEnabled = checkIdle && !downloadsBusy

    return BypassDashboardModel(
        headline = headline,
        // A preference left on without a verified strategy does nothing: the engine has nothing to start.
        downloadsViaBypass = ready && (inputs.downloadsEnabled || tunnelUp),
        // Like Settings, not while the tunnel starts or runs; unlike Settings, a running download does not lock it:
        // the switch only writes a preference, a download that is already running keeps its route.
        switchEnabled = ready && channels && tunnelOff,
        downloadsNote = when {
            tunnelUp -> DashDownloadsNote.TUNNEL_KEEPS_ON
            downloadsBusy -> DashDownloadsNote.APPLIES_NEXT
            else -> DashDownloadsNote.NONE
        },
        tunnel = when (inputs.tunnel) {
            TunnelPhase.OFF -> DashTunnelState.OFF
            TunnelPhase.STARTING -> DashTunnelState.STARTING
            TunnelPhase.ACTIVE ->
                if (inputs.flow == FlowVerdict.ONE_WAY) DashTunnelState.NO_ANSWER else DashTunnelState.ON
        },
        showChannels = channels,
        showSetupAction = headline == DashHeadline.SETUP && !inputs.searching,
        showSearchStrip = inputs.searching,
        checkEnabled = checkEnabled,
        checkHint = if (checkIdle && downloadsBusy) DashCheckHint.WAIT_DOWNLOAD else DashCheckHint.NONE
    )
}

/**
 * A transfer speed for the dashboard. Units stay in English abbreviations (`KB/s`, `MB/s`), like the speeds yt-dlp
 * prints in the queue rows; the decimal separator follows [locale].
 */
internal fun formatTransferRate(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String {
    val bytes = bytesPerSecond.coerceAtLeast(0L)
    return when {
        bytes == 0L -> "0 KB/s"
        bytes < 1_000L -> "<1 KB/s"
        // Just below one megabyte per second still rounds to a whole number of KB, never to "1000 KB/s".
        bytes < 999_500L -> "${(bytes + 500L) / 1_000L} KB/s"
        else -> String.format(locale, "%.1f MB/s", bytes / 1_000_000.0)
    }
}
