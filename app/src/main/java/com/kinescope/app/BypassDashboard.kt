package com.kinescope.app

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Patch 45: the Home hero card, a dashboard instead of one YouTube button. Patch 51: redrawn for the new brief.
//
// - Header: the state of the YouTube bypass (on / off), or the first-run message "Please wait while we update the
//   strategies." (no Stop: that check is mandatory), a visible check, or "not set up yet".
// - YouTube bypass: an on/off switch for the YouTube-only tunnel (its live speed and strategy position while it runs),
//   and the "Unblock YouTube" button, which starts the tunnel if needed and opens the app chosen in Settings.
// - Download speed: the speed of the download that is running, only while one is.
// - Strategy: the 15 most successful strategies by name (a colour, `StrategyNames`) with 1-5 stars for how effective each
//   was in the last test; one tap uses it (the same as "Use" in Settings: pinned, tried first).
//
// The downloads-through-the-bypass switch left the card for Settings (on by default). What is shown and which controls are
// enabled is decided in `BypassDashboardModel.kt` (unit-tested); this file maps the real state into it, draws the result and
// forwards taps. Every control sits in a `FocusRing` (TV).

/** The result messages of a finished search that still make sense on a card that says "not set up". */
private val SETUP_MESSAGES = setOf(
    R.string.bypass_search_none,
    R.string.bypass_search_stopped,
    R.string.bypass_operation_failed
)

/** Buttons keep the 48dp height and the width cap of the old Home button (design.md, patch 36). */
private val ActionMaxWidth = 360.dp

/** The preferences the dashboard reads; they are plain SharedPreferences, not a flow. */
private class DashPrefs(
    val waysReady: Int,
    val verifiedAt: Long,
    val current: String?,
    val pinned: String?,
    val top: List<DashStrategy>,
    val names: StrategyNameBook
)

private data class DashPalette(val container: Color, val primary: Color, val secondary: Color, val divider: Color)

private fun starsText(stars: Int): String = "★".repeat(stars) + "☆".repeat((5 - stars).coerceAtLeast(0))

@Composable
internal fun BypassDashboard(
    tunnel: BypassVpnUiState,
    jobs: List<DownloadJobStatus>,
    onToggleTunnel: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val search by DpiSearchController.state.collectAsState()
    val traffic by BypassVpnController.traffic.collectAsState()

    val running = jobs.filter {
        it.state == JobState.PREPARING || it.state == JobState.RUNNING ||
            it.state == JobState.PROCESSING || it.state == JobState.SAVING
    }
    val activeDownloads = running.size
    val downloadKbps = jobs.firstOrNull { it.state == JobState.RUNNING && it.speedKbps > 0 }?.speedKbps ?: 0

    // Read again whenever something that can change them happens: a search starting or finishing, the tunnel coming
    // up or going down (an "Update" from its notification can change the chain), or a pick on this card.
    var prefsVersion by remember { mutableIntStateOf(0) }
    val prefs = remember(search.running, search.resultMessageRes, tunnel.active, prefsVersion) {
        val chain = DpiStrategyStore.verifiedChain(context)
        DashPrefs(
            waysReady = chain.size,
            verifiedAt = DpiPrefs.verifiedAt(context),
            current = DpiStrategyStore.chain(context).firstOrNull(),
            pinned = DpiPrefs.pinned(context),
            top = topStrategies(DpiResultsStore.load(context), DpiStrategyStore.candidates(context)),
            names = DpiStrategyStore.names(context)
        )
    }

    val model = bypassDashboardModel(
        BypassDashInputs(
            waysReady = prefs.waysReady,
            tunnel = when {
                tunnel.active -> TunnelPhase.ACTIVE
                tunnel.starting -> TunnelPhase.STARTING
                else -> TunnelPhase.OFF
            },
            flow = traffic.verdict,
            searching = search.running,
            searchBackground = search.background,
            searchMandatory = search.mandatory,
            activeDownloads = activeDownloads,
            downloadKbps = downloadKbps
        )
    )

    val startCheck: () -> Unit = {
        AppLog.i("BypassDashboard", "home check start ways=${prefs.waysReady}")
        // The search runs in a foreground service: its notification needs the permission first (as in Settings).
        requestNotifications()
        DpiSearchService.start(context)
    }
    val pick: (String) -> Unit = { line ->
        DpiPrefs.pin(context, line)
        prefsVersion++
        AppLog.i("BypassDashboard", "home pick jobs=$activeDownloads tunnel=${tunnel.active}")
        Toast.makeText(context, R.string.strategy_pinned_done, Toast.LENGTH_SHORT).show()
    }
    val auto: () -> Unit = {
        DpiPrefs.unpin(context)
        prefsVersion++
        AppLog.i("BypassDashboard", "home pick auto")
        Toast.makeText(context, R.string.strategy_unpinned, Toast.LENGTH_SHORT).show()
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
                        DashHeadline.FIRST_RUN -> R.string.dash_headline_first_run
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
                    DashHeadline.FIRST_RUN -> stringResource(R.string.dash_sub_first_run)
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
                    stoppable = model.searchStoppable,
                    palette = palette,
                    onStop = { DpiSearchService.stop(context) }
                )
            }

            if (model.showSetupAction) {
                Spacer(modifier = Modifier.height(12.dp))
                FocusRing(modifier = Modifier.widthIn(max = ActionMaxWidth).fillMaxWidth()) {
                    ActionButton(
                        text = stringResource(R.string.dash_check_ways),
                        onClick = startCheck,
                        enabled = model.checkEnabled
                    )
                }
                CheckHint(model.checkHint, palette)
            }

            if (model.showSpeed) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (model.downloadKbps > 0) {
                        stringResource(R.string.dash_speed, speedText(model.downloadKbps))
                    } else {
                        stringResource(R.string.dash_speed_waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.primary
                )
            }

            if (model.showControls) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = palette.divider)
                YouTubeChannel(
                    model = model,
                    traffic = traffic,
                    errorRes = tunnel.errorRes,
                    palette = palette,
                    onToggle = onToggleTunnel,
                    onOpen = onOpenApp
                )
                HorizontalDivider(color = palette.divider)
                StrategySection(
                    prefs = prefs,
                    model = model,
                    palette = palette,
                    onPick = pick,
                    onAuto = auto
                )
            }
        }
    }
}

@Composable
private fun speedText(kbps: Int): String =
    if (kbps >= 1024) stringResource(R.string.speed_mb, kbps / 1024.0) else stringResource(R.string.speed_kb, kbps)

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

/**
 * Progress of a running strategy search: how many ways were tried, a bar and, unless the check is mandatory (the first
 * run), Stop.
 */
@Composable
private fun SearchStrip(
    progress: SearchProgress?,
    background: Boolean,
    stoppable: Boolean,
    palette: DashPalette,
    onStop: () -> Unit
) {
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
        if (stoppable) {
            FocusRing {
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
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
 * The YouTube bypass: the whole row is the on/off switch (a `toggleable` row with a read-only [Switch] inside, so a screen
 * reader and a D-pad both meet one control that has a name), under it the live state and the "Unblock YouTube" button.
 */
@Composable
private fun YouTubeChannel(
    model: BypassDashboardModel,
    traffic: TunnelTraffic,
    errorRes: Int?,
    palette: DashPalette,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
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

    FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = model.tunnelSwitchOn,
                    enabled = model.tunnelSwitchEnabled,
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
            Switch(
                checked = model.tunnelSwitchOn,
                onCheckedChange = null,
                enabled = model.tunnelSwitchEnabled
            )
        }
    }
    Row(
        modifier = Modifier.widthIn(max = ActionMaxWidth).fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusRing(modifier = Modifier.weight(1f)) {
            ActionButton(
                text = stringResource(
                    if (state == DashTunnelState.STARTING) R.string.bypass_starting_short else R.string.bypass_quick_action
                ),
                onClick = onOpen,
                enabled = model.openEnabled
            )
        }
    }
}

/**
 * The strategies: the one in use and, as chips, the most successful ones by colour name with their stars. A chip uses
 * the strategy; "Choose automatically" lifts a pin. Collapsible, so a phone can have the room back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrategySection(
    prefs: DashPrefs,
    model: BypassDashboardModel,
    palette: DashPalette,
    onPick: (String) -> Unit,
    onAuto: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val currentName = prefs.current?.let { prefs.names.nameOf(it) }.orEmpty()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dash_strategy_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primary
                )
                if (currentName.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dash_strategy_current, currentName),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondary
                    )
                }
            }
            FocusRing {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.strategy_hide else R.string.strategy_show))
                }
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            if (prefs.top.isEmpty()) {
                Text(
                    text = stringResource(R.string.dash_strategies_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (row in prefs.top) {
                        val name = prefs.names.nameOf(row.line)
                        val description = stringResource(R.string.dash_strategy_cd, name, row.stars)
                        FocusRing(shape = MaterialTheme.shapes.small) {
                            FilterChip(
                                selected = row.line == prefs.current,
                                enabled = model.strategiesEnabled,
                                onClick = { onPick(row.line) },
                                label = {
                                    Text(
                                        text = "$name  ${starsText(row.stars)}",
                                        modifier = Modifier.semantics { contentDescription = description }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            if (prefs.pinned != null) {
                FocusRing {
                    TextButton(onClick = onAuto, enabled = model.strategiesEnabled) {
                        Text(stringResource(R.string.strategy_auto))
                    }
                }
            }
            if (model.strategyNote == DashStrategyNote.APPLIES_NEXT) {
                Text(
                    text = stringResource(R.string.dash_strategy_applies_next),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Patch 48: the height is a minimum (a control token: 48dp touch, 56dp TV), not a fixed height. A fixed 48dp
 * clipped the second line of "Unblock YouTube" (longer in Russian) at a large font size or in a narrow pane; now the
 * button grows with its label.
 */
@Composable
private fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalAdaptive.current.controlHeightDp.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
