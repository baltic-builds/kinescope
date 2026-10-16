package com.kinescope.app

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// Patch 45: the Home hero card, redesigned from one YouTube button into a dashboard.
//
// - Header: the overall state (on / off / checking / not set up) and how many verified ways there are.
// - Downloads channel: ONE switch for "downloads go through the bypass" (the same preference as Settings), so the
//   bypass no longer needs a trip into Settings. It is locked while the YouTube tunnel runs, because
//   `DpiBypass.activeChain` uses the bypass then whatever the preference says.
// - YouTube app channel: start (and open) or stop the YouTube-only tunnel, with its live speed, the strategy in use
//   and a warning when the app sends but nothing comes back.
// - Load indicators: a progress strip for a running strategy search, the tunnel's speed.
//
// What is shown and which controls are offered is decided in `BypassDashboardModel.kt` (unit-tested); this file only
// maps the real state into it, draws the result and forwards taps. Every control sits in a `FocusRing` (TV).

/** The result messages of a finished search that still make sense on a card that says "not set up". */
private val SETUP_MESSAGES = setOf(
    R.string.bypass_search_none,
    R.string.bypass_search_stopped,
    R.string.bypass_operation_failed
)

/** Buttons keep the 48dp height and the width cap of the old Home button (design.md, patch 36). */
private val ActionMaxWidth = 360.dp

/** The preferences the dashboard reads; they are plain SharedPreferences, not a flow. */
private data class DashPrefs(val waysReady: Int, val downloadsEnabled: Boolean, val verifiedAt: Long)

private data class DashPalette(val container: Color, val primary: Color, val secondary: Color, val divider: Color)

@Composable
internal fun BypassDashboard(
    tunnel: BypassVpnUiState,
    activeDownloads: Int,
    onOpenYouTube: () -> Unit,
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val search by DpiSearchController.state.collectAsState()
    val traffic by BypassVpnController.traffic.collectAsState()

    // Read again whenever something that can change them happens: a search starting or finishing, the tunnel coming
    // up or going down (an "Update" from its notification can change the chain), or this card's own switch.
    var prefsVersion by remember { mutableIntStateOf(0) }
    val prefs = remember(search.running, search.resultMessageRes, tunnel.active, prefsVersion) {
        DashPrefs(
            waysReady = DpiStrategyStore.verifiedChain(context).size,
            downloadsEnabled = DpiPrefs.isEnabled(context),
            verifiedAt = DpiPrefs.verifiedAt(context)
        )
    }

    val model = bypassDashboardModel(
        BypassDashInputs(
            waysReady = prefs.waysReady,
            downloadsEnabled = prefs.downloadsEnabled,
            tunnel = when {
                tunnel.active -> TunnelPhase.ACTIVE
                tunnel.starting -> TunnelPhase.STARTING
                else -> TunnelPhase.OFF
            },
            flow = traffic.verdict,
            searching = search.running,
            searchBackground = search.background,
            activeDownloads = activeDownloads
        )
    )

    val startCheck: () -> Unit = {
        AppLog.i("BypassDashboard", "home check start ways=${prefs.waysReady}")
        // The search runs in a foreground service: its notification needs the permission first (as in Settings).
        requestNotifications()
        DpiSearchService.start(context)
    }
    val toggleDownloads: (Boolean) -> Unit = { enabled ->
        DpiPrefs.setEnabled(context, enabled)
        prefsVersion++
        AppLog.i(
            "BypassDashboard",
            "home bypass=${if (enabled) "on" else "off"} ways=${prefs.waysReady} jobs=$activeDownloads"
        )
    }
    val stopTunnel: () -> Unit = {
        AppLog.i("BypassDashboard", "home tunnel stop")
        BypassVpnService.stop(context)
    }

    val scheme = MaterialTheme.colorScheme
    val isOn = model.headline == DashHeadline.ON
    val palette = DashPalette(
        container = if (isOn) scheme.primaryContainer else scheme.surfaceVariant,
        primary = if (isOn) scheme.onPrimaryContainer else scheme.onSurface,
        secondary = if (isOn) scheme.onPrimaryContainer.copy(alpha = 0.85f) else scheme.onSurfaceVariant,
        divider = scheme.outline
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val setupMessage = search.resultMessageRes?.takeIf { it in SETUP_MESSAGES }
            Text(
                text = stringResource(
                    when (model.headline) {
                        DashHeadline.CHECKING -> R.string.dash_headline_checking
                        DashHeadline.SETUP -> R.string.dash_headline_setup
                        DashHeadline.ON -> R.string.dash_headline_on
                        DashHeadline.OFF -> R.string.dash_headline_off
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color = palette.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when (model.headline) {
                    DashHeadline.CHECKING -> stringResource(R.string.dash_sub_checking)
                    DashHeadline.SETUP -> stringResource(setupMessage ?: R.string.dash_sub_setup)
                    DashHeadline.ON, DashHeadline.OFF -> waysReadyText(prefs)
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary
            )

            if (model.showSearchStrip) {
                Spacer(modifier = Modifier.height(10.dp))
                SearchStrip(
                    progress = search.progress,
                    background = search.background,
                    palette = palette,
                    onStop = { DpiSearchService.stop(context) }
                )
            }

            if (model.showSetupAction) {
                Spacer(modifier = Modifier.height(12.dp))
                FocusRing(modifier = Modifier.widthIn(max = ActionMaxWidth).fillMaxWidth()) {
                    Button(
                        onClick = startCheck,
                        enabled = model.checkEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(text = stringResource(R.string.dash_check_ways), style = MaterialTheme.typography.titleMedium)
                    }
                }
                CheckHint(model.checkHint, palette)
            }

            if (model.showChannels) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = palette.divider)
                DownloadsChannel(model = model, palette = palette, onToggle = toggleDownloads)
                HorizontalDivider(color = palette.divider)
                YouTubeChannel(
                    model = model,
                    traffic = traffic,
                    errorRes = tunnel.errorRes,
                    palette = palette,
                    onOpen = onOpenYouTube,
                    onStop = stopTunnel
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) { CheckHint(model.checkHint, palette) }
                    FocusRing {
                        TextButton(onClick = startCheck, enabled = model.checkEnabled) {
                            Text(stringResource(R.string.dash_check_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun waysReadyText(prefs: DashPrefs): String {
    if (prefs.verifiedAt <= 0L) return stringResource(R.string.dash_ways_ready, prefs.waysReady)
    val now = System.currentTimeMillis()
    val age = if (now - prefs.verifiedAt < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.dash_just_now)
    } else {
        // Localised by the framework ("3 hr. ago" / "3 ч. назад").
        DateUtils.getRelativeTimeSpanString(
            prefs.verifiedAt,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    return stringResource(R.string.dash_ways_ready_checked, prefs.waysReady, age)
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CheckHint(hint: DashCheckHint, palette: DashPalette) {
    if (hint == DashCheckHint.WAIT_DOWNLOAD) {
        Text(
            text = stringResource(R.string.dash_wait_download_check),
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Progress of a running strategy search: how many ways were tried, a bar and Stop. */
@Composable
private fun SearchStrip(progress: SearchProgress?, background: Boolean, palette: DashPalette, onStop: () -> Unit) {
    val total = progress?.total ?: 0
    val done = (progress?.index ?: 0).coerceIn(0, total)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (background) R.string.dash_recheck else R.string.dash_check_progress,
                done,
                total
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondary,
            modifier = Modifier.weight(1f)
        )
        FocusRing {
            TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
        }
    }
    if (progress != null && total > 0) {
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
    }
}

/**
 * The downloads channel. The whole row is the switch (a `toggleable` row with a read-only [Switch] inside), so a
 * screen reader and a D-pad both meet one control that has a name.
 */
@Composable
private fun DownloadsChannel(model: BypassDashboardModel, palette: DashPalette, onToggle: (Boolean) -> Unit) {
    val dot = if (model.downloadsViaBypass) YtOfflineExtras.colors.success else palette.secondary
    FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = model.downloadsViaBypass,
                    enabled = model.switchEnabled,
                    role = Role.Switch,
                    onValueChange = onToggle
                )
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusDot(dot)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dash_downloads_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primary
                )
                Text(
                    text = stringResource(
                        if (model.downloadsViaBypass) R.string.dash_downloads_bypass else R.string.dash_downloads_direct
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary
                )
            }
            Switch(
                checked = model.downloadsViaBypass,
                onCheckedChange = null,
                enabled = model.switchEnabled
            )
        }
    }
    val noteRes = when (model.downloadsNote) {
        DashDownloadsNote.NONE -> null
        DashDownloadsNote.APPLIES_NEXT -> R.string.dash_applies_next
        DashDownloadsNote.TUNNEL_KEEPS_ON -> R.string.dash_tunnel_keeps_on
    }
    if (noteRes != null) {
        Text(
            text = stringResource(noteRes),
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondary,
            // Under the text of the row: the dot (10dp) and the gap (12dp) come first.
            modifier = Modifier.padding(start = 22.dp, bottom = 10.dp)
        )
    }
}

/** The YouTube app channel: state, live speed and strategy, and the start / open / stop controls. */
@Composable
private fun YouTubeChannel(
    model: BypassDashboardModel,
    traffic: TunnelTraffic,
    errorRes: Int?,
    palette: DashPalette,
    onOpen: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val state = model.tunnel
    val dot = when (state) {
        DashTunnelState.ON -> YtOfflineExtras.colors.success
        DashTunnelState.NO_ANSWER -> YtOfflineExtras.colors.warning
        DashTunnelState.STARTING -> scheme.primary
        DashTunnelState.OFF -> if (errorRes != null) scheme.error else palette.secondary
    }
    val statusColor = when {
        state == DashTunnelState.NO_ANSWER -> YtOfflineExtras.colors.warning
        state == DashTunnelState.OFF && errorRes != null -> scheme.error
        else -> palette.secondary
    }
    val statusText = when (state) {
        DashTunnelState.OFF -> stringResource(errorRes ?: R.string.bypass_home_hint)
        DashTunnelState.STARTING -> stringResource(R.string.bypass_starting_short)
        DashTunnelState.ON -> stringResource(R.string.dash_youtube_on)
        DashTunnelState.NO_ANSWER -> stringResource(R.string.dash_youtube_no_answer)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusDot(dot)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dash_youtube_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primary
                )
                Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                if (state == DashTunnelState.ON || state == DashTunnelState.NO_ANSWER) {
                    val speed = if (traffic.rate == TunnelRate.ZERO) {
                        stringResource(R.string.dash_youtube_idle)
                    } else {
                        stringResource(
                            R.string.dash_rate,
                            formatTransferRate(traffic.rate.downBytesPerSec),
                            formatTransferRate(traffic.rate.upBytesPerSec)
                        )
                    }
                    val way = if (traffic.position > 0) {
                        stringResource(R.string.dash_way, traffic.position, traffic.total)
                    } else {
                        null
                    }
                    Text(
                        text = listOfNotNull(speed, way).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.widthIn(max = ActionMaxWidth).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                DashTunnelState.OFF -> FocusRing(modifier = Modifier.weight(1f)) {
                    ActionButton(text = stringResource(R.string.bypass_quick_action), onClick = onOpen)
                }
                DashTunnelState.STARTING -> FocusRing(modifier = Modifier.weight(1f)) {
                    ActionButton(text = stringResource(R.string.bypass_starting_short), onClick = {}, enabled = false)
                }
                DashTunnelState.ON, DashTunnelState.NO_ANSWER -> {
                    FocusRing(modifier = Modifier.weight(1f)) {
                        ActionButton(text = stringResource(R.string.dash_open_youtube), onClick = onOpen)
                    }
                    FocusRing {
                        OutlinedButton(onClick = onStop, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.stop))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
