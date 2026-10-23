package com.kinescope.app

import java.util.Locale

// Patch 45, redrawn in patch 51: the rules behind the Home bypass dashboard, as plain data so they can be unit-tested
// on the JVM (`BypassDashboardTest`). `BypassDashboard.kt` only maps the real states into [BypassDashInputs], draws the
// [BypassDashboardModel] it gets back and forwards taps; it decides nothing itself.
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
    /** The quiet 25-day re-check: downloads keep using the stored ways and the controls stay usable. */
    val searchBackground: Boolean,
    /** The first-run check: it cannot be stopped and the screen says to wait for it. */
    val searchMandatory: Boolean,
    val activeDownloads: Int,
    /** Speed of the download that is running, in KiB/s as yt-dlp prints it; 0 when none is known yet. */
    val downloadKbps: Int
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

internal enum class DashStrategyNote { NONE, APPLIES_NEXT }

internal data class BypassDashboardModel(
    val headline: DashHeadline,
    val tunnel: DashTunnelState,
    /** The on/off switch of the YouTube bypass: its position and whether it can be used right now. */
    val tunnelSwitchOn: Boolean,
    val tunnelSwitchEnabled: Boolean,
    /** The "Unblock YouTube" / "Open" button (starts the tunnel when it is off, then opens the chosen app). */
    val openEnabled: Boolean,
    /** The YouTube switch, the open button and the strategy list. */
    val showControls: Boolean,
    /** Choosing a strategy: not while a search is changing the results. */
    val strategiesEnabled: Boolean,
    val strategyNote: DashStrategyNote,
    /** The progress strip of a running search, and whether the user may stop it (the first-run check cannot be). */
    val showSearchStrip: Boolean,
    val searchStoppable: Boolean,
    /** The primary "Check the ways" button of the not-set-up state. */
    val showSetupAction: Boolean,
    /** Whether a new check may start now (the same rule as the Test button in Settings). */
    val checkEnabled: Boolean,
    val checkHint: DashCheckHint,
    /** The download speed line: shown while a download is active; [downloadKbps] is 0 until a speed is known. */
    val showSpeed: Boolean,
    val downloadKbps: Int
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

    return BypassDashboardModel(
        headline = headline,
        tunnel = when (inputs.tunnel) {
            TunnelPhase.OFF -> DashTunnelState.OFF
            TunnelPhase.STARTING -> DashTunnelState.STARTING
            TunnelPhase.ACTIVE ->
                if (inputs.flow == FlowVerdict.ONE_WAY) DashTunnelState.NO_ANSWER else DashTunnelState.ON
        },
        tunnelSwitchOn = !tunnelOff,
        // Starting a tunnel needs a verified strategy; a tunnel that is still starting is not interrupted.
        tunnelSwitchEnabled = controls && !tunnelStarting,
        openEnabled = controls && !tunnelStarting,
        showControls = controls,
        strategiesEnabled = controls && !inputs.searching,
        strategyNote = if (!tunnelOff || downloadsBusy) DashStrategyNote.APPLIES_NEXT else DashStrategyNote.NONE,
        showSearchStrip = inputs.searching,
        searchStoppable = !inputs.searchMandatory,
        showSetupAction = headline == DashHeadline.SETUP && !inputs.searching,
        checkEnabled = checkEnabled,
        checkHint = if (checkIdle && downloadsBusy) DashCheckHint.WAIT_DOWNLOAD else DashCheckHint.NONE,
        showSpeed = downloadsBusy,
        downloadKbps = inputs.downloadKbps.coerceAtLeast(0)
    )
}

/**
 * Patch 51: the strategy's effectiveness as the 1-5 stars the dashboard draws. The app measures it as a percentage
 * (`StrategyResult.score`, 0-100); the thresholds are the user's: 100 -> 5, 85-99 -> 4, 75-84 -> 3, 60-74 -> 2,
 * anything below -> 1.
 */
internal fun strategyStars(percent: Int): Int = when {
    percent >= 100 -> 5
    percent >= 85 -> 4
    percent >= 75 -> 3
    percent >= 60 -> 2
    else -> 1
}

/** One row of the dashboard's strategy list. */
internal data class DashStrategy(val line: String, val percent: Int, val stars: Int)

/** How many strategies the dashboard lists. */
internal const val DASH_STRATEGY_LIMIT = 15

/**
 * Patch 51: the most successful strategies for the dashboard: the ones that passed their last test and are still in
 * the list ([candidates]; a line the list dropped, such as a flag-fixed variant, is not offered), best score first,
 * then faster, then in list order. At most [limit].
 */
internal fun topStrategies(
    results: Map<String, StoredResult>,
    candidates: List<String>,
    limit: Int = DASH_STRATEGY_LIMIT
): List<DashStrategy> {
    val order = candidates.withIndex().associate { it.value to it.index }
    return results.values
        .filter { it.passed && it.line in order }
        .sortedWith(
            compareByDescending<StoredResult> { it.score }
                .thenByDescending { it.kbps }
                .thenBy { order.getValue(it.line) }
        )
        .take(limit)
        .map { DashStrategy(it.line, it.score, strategyStars(it.score)) }
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
