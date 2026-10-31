package com.kinescope.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The home surface shows live tunnel status, automatic selection and the Watch YouTube action.
// Patch 58: automatic off reveals five manual choices for the current class; Settings keeps the full list.
// Patch 56: a refresh button in the card's top-right corner (five random ways are checked, the working ones join the router's list),
// the router's last change under "Way N of M", a soft glow on the card in both states, a pill-shaped main button. The two strategy
// groups are drawn by their own card (`StrategyGroupsCard.kt`).

/** The result messages of a finished search that still make sense on a card that says "not set up". */
private val SETUP_MESSAGES = setOf(
    R.string.bypass_search_none,
    R.string.bypass_search_no_route,
    R.string.bypass_search_stopped,
    R.string.bypass_operation_failed
)

/** Buttons keep the 48dp height and the width cap of the old Home button (design.md, patch 36). */
private val ActionMaxWidth = 360.dp

/** The preferences the dashboard reads; they are plain SharedPreferences, not a flow. */
private class DashPrefs(
    /** The connection the strategies below are for (Wi-Fi or mobile data), patch 53. */
    val cls: NetClass,
    val waysReady: Int,
    val verifiedAt: Long,
    /** The strategy the user pinned; null while the choice is automatic. */
    val pinned: String?,
    /** The pinned one, else the tunnel's actual route when known, else the chain head. */
    val selected: String?,
    /** Patch 58: this class's five best manual choices and the ratings their labels show. */
    val choices: List<String>,
    val ratings: Map<String, Int>
)

private data class DashPalette(val container: Color, val primary: Color, val secondary: Color, val divider: Color)

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
    val activeLine by BypassVpnController.activeLine.collectAsState()
    val routerEvent by BypassVpnController.lastEvent.collectAsState()
    // Patch 53: the card shows the strategies of the connection the phone is on, and follows a change from Wi-Fi to mobile data.
    val netClass by NetClassWatcher.state.collectAsState()
    val cls = netClass ?: NetClass.WIFI

    val running = jobs.filter {
        it.state == JobState.PREPARING || it.state == JobState.RUNNING ||
            it.state == JobState.PROCESSING || it.state == JobState.SAVING
    }
    val activeDownloads = running.size

    // Read again whenever something that can change them happens: a search starting or finishing, the tunnel coming
    // up or going down (an "Update" from its notification can change the chain), or a pick on this card.
    var prefsVersion by remember { mutableIntStateOf(0) }
    val prefs = remember(
        search.running, search.resultMessageRes, search.randomResult, tunnel.active,
        activeLine, routerEvent, prefsVersion, cls
    ) {
        // chain() first: a connection that was never tested borrows the other one's chain here.
        val chain = DpiStrategyStore.chain(context, cls)
        val pinned = DpiPrefs.pinned(context, cls)
        // Automatic off must pin the route that is really carrying the tunnel, not a stale chain head.
        val selected = pinned ?: activeLine?.takeIf { it in chain } ?: chain.firstOrNull()
        val ratings = if (pinned == null) emptyMap() else DpiPrefs.ratings(context, cls)
        val choices = if (pinned == null) {
            emptyList()
        } else {
            StrategyRank.manualChoices(DpiStrategyStore.candidates(context), ratings, DpiResultsStore.load(context, cls))
        }
        DashPrefs(
            cls = cls,
            waysReady = chain.size,
            verifiedAt = DpiPrefs.verifiedAt(context, cls),
            pinned = pinned,
            selected = selected,
            choices = choices,
            ratings = ratings
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
            // Patch 56: a random check is as quiet as the check after an update: the controls stay, only the strip shows it.
            searchBackground = search.background || search.random,
            searchMandatory = search.mandatory,
            activeDownloads = activeDownloads,
            tunnelError = tunnel.errorRes != null
        )
    )

    val startCheck: () -> Unit = {
        AppLog.i("BypassDashboard", "home check start ways=${prefs.waysReady}")
        // The search runs in a foreground service: its notification needs the permission first (as in Settings).
        requestNotifications()
        DpiSearchService.start(context)
    }
    val startRefresh: () -> Unit = {
        AppLog.i("BypassDashboard", "home refresh start net=${prefs.cls.key}")
        // Like the check, it runs in a foreground service whose notification needs the permission.
        requestNotifications()
        DpiSearchService.startRandom(context)
    }
    val setAuto: (Boolean) -> Unit = setAuto@{ on ->
        if (on) {
            DpiPrefs.unpin(context, prefs.cls)
        } else {
            DpiPrefs.pin(context, prefs.cls, prefs.selected ?: return@setAuto)
        }
        prefsVersion++
        AppLog.i("BypassDashboard", "home auto=${if (on) "on" else "off"}")
        Toast.makeText(
            context,
            if (on) R.string.strategy_unpinned else R.string.strategy_pinned_done,
            Toast.LENGTH_SHORT
        ).show()
    }

    val selectStrategy: (String) -> Unit = { line ->
        DpiPrefs.pin(context, prefs.cls, line)
        prefsVersion++
        AppLog.i("BypassDashboard", "home manual net=${prefs.cls.key}")
        Toast.makeText(context, R.string.strategy_pinned_done, Toast.LENGTH_SHORT).show()
    }

    val scheme = MaterialTheme.colorScheme
    val isOn = model.headline == DashHeadline.ON
    val palette = DashPalette(
        container = if (isOn) scheme.primaryContainer else scheme.surface,
        primary = if (isOn) scheme.onPrimaryContainer else scheme.onSurface,
        secondary = if (isOn) scheme.onPrimaryContainer.copy(alpha = 0.85f) else scheme.onSurfaceVariant,
        divider = scheme.outline
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = palette.container,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // Patch 55: while the bypass is on, a soft wash from the card's orange towards Google's pale blue, the
                        // glow of Gemini's "Neural Expressive" look. Patch 56: off, the same glow in a fainter, cooler form (the
                        // surface towards the pale blue), so the card is never a flat grey slab.
                        Modifier.background(
                            Brush.verticalGradient(
                                if (isOn) {
                                    listOf(
                                        scheme.primaryContainer,
                                        lerp(scheme.primaryContainer, scheme.tertiaryContainer, 0.45f)
                                    )
                                } else {
                                    listOf(scheme.surface, lerp(scheme.surface, scheme.tertiaryContainer, 0.30f))
                                }
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                val setupMessage = search.resultMessageRes?.takeIf { it in SETUP_MESSAGES }
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
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
                            style = MaterialTheme.typography.headlineMedium,
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
                    }
                    // Patch 56: the refresh button, top right: it checks five random ways now.
                    if (model.showRefresh) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RefreshButton(enabled = model.refreshEnabled, palette = palette, onClick = startRefresh)
                    }
                }
                // What the last refresh (or the twice-a-day check) found, until the next search starts.
                val randomResult = search.randomResult
                if (model.showControls && !search.running && randomResult != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (randomResult.tested > 0) {
                            stringResource(
                                R.string.dash_refresh_result,
                                randomResult.tested,
                                randomResult.works,
                                randomResult.added,
                                randomResult.removed
                            )
                        } else {
                            stringResource(search.resultMessageRes ?: R.string.dash_refresh_nothing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondary
                    )
                }

                if (model.showSearchStrip) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SearchStrip(
                        progress = search.progress,
                        background = search.background,
                        random = search.random,
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

                if (model.showControls) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = palette.divider)
                    YouTubeChannel(
                        model = model,
                        traffic = traffic,
                        event = routerEvent,
                        errorRes = tunnel.errorRes,
                        palette = palette,
                        onToggle = onToggleTunnel
                    )
                    HorizontalDivider(color = palette.divider)
                    AutomaticStrategySection(
                        prefs = prefs,
                        model = model,
                        palette = palette,
                        onAuto = setAuto,
                        onSelect = selectStrategy
                    )
                }
            }
        }

        if (model.showControls) {
            Spacer(modifier = Modifier.height(12.dp))
            FocusRing(modifier = Modifier.fillMaxWidth()) {
                ActionButton(
                    text = stringResource(
                        if (model.tunnel == DashTunnelState.STARTING) {
                            R.string.bypass_starting_short
                        } else {
                            R.string.bypass_quick_action
                        }
                    ),
                    onClick = onOpenApp,
                    enabled = model.openEnabled
                )
            }
        }
    }
}

@Composable
private fun waysReadyText(prefs: DashPrefs): String {
    if (prefs.verifiedAt <= 0L) return stringResource(R.string.dash_ways_ready, prefs.waysReady)
    return stringResource(R.string.dash_ways_ready_checked, prefs.waysReady, agoText(prefs.verifiedAt))
}

/** Patch 56: the refresh button of the card: a round, tonal touch target (48dp) with the refresh arrow; it checks five random ways. */
@Composable
private fun RefreshButton(enabled: Boolean, palette: DashPalette, onClick: () -> Unit) {
    FocusRing {
        Surface(shape = CircleShape, color = palette.primary.copy(alpha = 0.10f)) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.dash_refresh_cd),
                    tint = if (enabled) palette.primary else palette.secondary.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/** Patch 56: the router's last change in a line: when, and why (see [RouterEventKind]). */
@Composable
private fun routerEventText(event: RouterEvent): String {
    val ago = agoText(event.at)
    val reason = when (event.kind) {
        RouterEventKind.RECONNECTED -> return stringResource(R.string.dash_event_reconnected, ago)
        RouterEventKind.PROBE -> R.string.switch_reason_probe
        RouterEventKind.ONE_WAY -> R.string.switch_reason_oneway
        RouterEventKind.TRANSPORT -> R.string.switch_reason_transport
        RouterEventKind.NETWORK -> R.string.switch_reason_network
        RouterEventKind.UPDATE -> R.string.switch_reason_update
    }
    return stringResource(R.string.dash_event_switched, ago, stringResource(reason))
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
    random: Boolean,
    stoppable: Boolean,
    palette: DashPalette,
    onStop: () -> Unit
) {
    val total = progress?.total ?: 0
    val done = (progress?.index ?: 0).coerceIn(0, total)
    // Patch 55: a check ends when it has found enough, not when it has tried everything, so it reports what it found.
    val goal = SearchGoal.STANDARD.working
    val working = (progress?.working ?: 0).coerceAtMost(goal)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (random) {
                // Patch 56: a random check reports how many of its few ways it has tried and how many of those work.
                stringResource(R.string.dash_refresh_progress, done, total, progress?.working ?: 0)
            } else {
                stringResource(
                    if (background) R.string.dash_recheck else R.string.dash_check_progress,
                    done,
                    working,
                    goal
                )
            },
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
            progress = { maxOf(done.toFloat() / total, working.toFloat() / goal).coerceIn(0f, 1f) },
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
 * reader and a D-pad both meet one control that has a name) with the live state under its name. Patch 52: it is the only
 * switch for the tunnel on the card; the "Watch YouTube" button is below the card.
 */
@Composable
private fun YouTubeChannel(
    model: BypassDashboardModel,
    traffic: TunnelTraffic,
    event: RouterEvent?,
    errorRes: Int?,
    palette: DashPalette,
    onToggle: (Boolean) -> Unit
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

    Column {
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
                    if ((state == DashTunnelState.ON || state == DashTunnelState.NO_ANSWER) && traffic.position > 0) {
                        // Patch 55: the way in use (no speed any more; the heartbeat line below says whether traffic flows).
                        Text(
                            text = stringResource(R.string.dash_way, traffic.position, traffic.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondary
                        )
                    }
                    if ((state == DashTunnelState.ON || state == DashTunnelState.NO_ANSWER) && event != null) {
                        // Patch 56: the router's last change, so a switch is seen and not only logged.
                        Text(
                            text = routerEventText(event),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondary
                        )
                    }
                }
                KinescopeSwitch(
                    checked = model.tunnelSwitchOn,
                    onCheckedChange = null,
                    enabled = model.tunnelSwitchEnabled
                )
            }
        }
        // Patch 55: the heartbeat line: a green pulse while the bypass carries traffic, a red flat line when the connection is lost.
        HeartbeatLine(
            pulse = model.pulse,
            description = stringResource(
                when (model.pulse) {
                    DashPulse.ALIVE -> R.string.dash_pulse_alive
                    DashPulse.LOST -> R.string.dash_pulse_lost
                    DashPulse.IDLE -> R.string.dash_pulse_idle
                }
            )
        )
    }
}

/** Patch 58: the automatic switch and, while it is off, the current class's five best manual choices. */
@Composable
private fun AutomaticStrategySection(
    prefs: DashPrefs,
    model: BypassDashboardModel,
    palette: DashPalette,
    onAuto: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    val auto = prefs.pinned == null
    FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = auto,
                enabled = model.strategiesEnabled,
                role = Role.Switch,
                onValueChange = onAuto
            ).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dash_strategy_auto_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primary
                )
                Text(
                    text = stringResource(if (auto) R.string.dash_strategy_auto_on else R.string.dash_strategy_auto_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary
                )
                Text(
                    text = stringResource(prefs.cls.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondary
                )
            }
            KinescopeSwitch(checked = auto, onCheckedChange = null, enabled = model.strategiesEnabled)
        }
    }
    if (!auto && prefs.choices.isNotEmpty()) {
        ManualStrategyDropdown(prefs, palette, model.strategiesEnabled, onSelect)
    }
}

@Composable
private fun ManualStrategyDropdown(
    prefs: DashPrefs,
    palette: DashPalette,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val selected = prefs.pinned ?: return
    val context = LocalContext.current
    var expanded by remember(prefs.cls) { mutableStateOf(false) }
    val names = remember(prefs.choices, prefs.pinned) { DpiStrategyStore.names(context) }
    @Composable
    fun label(line: String): String {
        val name = names.nameOf(line).ifBlank { line }
        val rating = prefs.ratings[line]
        return if (rating == null) name else name + " · " + stringResource(R.string.strategy_rating, rating)
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.dash_strategy_manual),
        style = MaterialTheme.typography.labelSmall,
        color = palette.secondary
    )
    FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { expanded = true }
                ),
                shape = MaterialTheme.shapes.medium,
                color = palette.primary.copy(alpha = 0.08f)
            ) {
                Text(
                    text = label(selected),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (line in prefs.choices) {
                    FocusRing {
                        DropdownMenuItem(
                            text = { Text(label(line)) },
                            enabled = enabled,
                            onClick = {
                                expanded = false
                                if (line != selected) onSelect(line)
                            }
                        )
                    }
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.dash_strategy_manual_hint),
        style = MaterialTheme.typography.bodySmall,
        color = palette.secondary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * Patch 48: the height is a minimum (a control token: 48dp touch, 56dp TV), not a fixed height. A fixed 48dp
 * clipped the second line of "Watch YouTube" (longer in Russian) at a large font size or in a narrow pane; now the
 * button grows with its label.
 */
@Composable
private fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // Patch 56: a fully rounded (pill) button, as in Google's current design.
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalAdaptive.current.controlHeightDp.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
