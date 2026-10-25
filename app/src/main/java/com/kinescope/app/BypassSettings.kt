package com.kinescope.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BypassBusy { NONE, UPDATING }

@Composable
fun BypassSettingsSection(requestNotifications: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val vpnState by BypassVpnController.state.collectAsState()
    val queueJobs by DownloadQueueBus.jobs.collectAsState()
    // The strategy search runs in DpiSearchService (a foreground service), not in this screen's
    // own coroutine scope, so it keeps testing every candidate when the user leaves this screen
    // or backgrounds the app instead of being cancelled (see CHANGELOG.md, patch 30).
    val searchState by DpiSearchController.state.collectAsState()
    val searching = searchState.running

    // Patch 53: the strategies are those of the connection the phone is on (Wi-Fi or mobile data).
    val netClass by NetClassWatcher.state.collectAsState()
    val cls = netClass ?: NetClass.WIFI
    val initialVerified = remember { DpiStrategyStore.chain(context, NetClassWatcher.current(context)).isNotEmpty() }
    var enabled by remember { mutableStateOf(DpiPrefs.isEnabled(context) && initialVerified) }
    var verified by remember { mutableStateOf(initialVerified) }
    var verifiedAt by remember { mutableStateOf(DpiPrefs.verifiedAt(context, cls)) }
    var listUpdatedAt by remember { mutableStateOf(DpiPrefs.listUpdatedAt(context)) }
    var busy by remember { mutableStateOf(BypassBusy.NONE) }
    var message by remember { mutableStateOf<String?>(null) }

    // Patch 51: the downloads switch is ON by default; until a strategy is verified it simply has nothing to do, so it is no
    // longer switched off here (that would have turned the default off the first time Settings was opened).
    DisposableEffect(busy, searching) {
        view.keepScreenOn = busy != BypassBusy.NONE || searching
        onDispose { view.keepScreenOn = false }
    }
    // Refreshes the on-screen state once the service-run search finishes -- whether that
    // happens while this screen is open or the result is just being picked up on return to it.
    LaunchedEffect(searchState) {
        // Read once into a local val: searchState is itself a delegated property
        // (by ...collectAsState()), so Kotlin cannot smart-cast searchState.resultMessageRes
        // directly (same reason vpnErrorRes is extracted from vpnState.errorRes above).
        val resultMessageRes = searchState.resultMessageRes
        if (!searching && resultMessageRes != null) {
            verified = DpiStrategyStore.chain(context, cls).isNotEmpty()
            verifiedAt = DpiPrefs.verifiedAt(context, cls)
            enabled = DpiPrefs.isEnabled(context)
            message = context.getString(resultMessageRes)
        }
    }
    // Patch 53: another connection has its own chain and its own time of the last test.
    LaunchedEffect(cls) {
        verified = DpiStrategyStore.chain(context, cls).isNotEmpty()
        verifiedAt = DpiPrefs.verifiedAt(context, cls)
    }

    val downloadActive = queueJobs.any {
        it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)
    }
    val controlsEnabled = busy == BypassBusy.NONE && !searching && !vpnState.starting && !vpnState.active && !downloadActive

    val startSearch: () -> Unit = {
        message = null
        requestNotifications()
        DpiSearchService.start(context)
    }

    // Patch 36: automatic first-run/update searches are owned by MainActivity. Settings only
    // starts a search from an explicit user action, avoiding a second lifecycle owner and an
    // early initial_search_done write before a search has actually finished.

    val runUpdate: () -> Unit = {
        busy = BypassBusy.UPDATING
        message = null
        scope.launch {
            try {
                message = when (val result = withContext(Dispatchers.IO) { DpiStrategyStore.update(context) }) {
                    is StrategyListUpdate.Updated -> context.getString(R.string.bypass_update_done, result.added, result.total)
                    is StrategyListUpdate.Unchanged -> context.getString(R.string.bypass_update_same, result.total)
                    is StrategyListUpdate.Failed -> context.getString(R.string.bypass_update_failed, result.reason)
                }
                listUpdatedAt = DpiPrefs.listUpdatedAt(context)
            } catch (e: Exception) {
                AppLog.e("BypassSettings", "Strategy list update failed", e)
                message = context.getString(R.string.bypass_operation_failed)
            } finally {
                busy = BypassBusy.NONE
            }
        }
    }

    Text(stringResource(R.string.bypass_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.bypass_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (verified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (verified) R.string.bypass_ready else R.string.bypass_not_ready),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (verified && verifiedAt > 0L) {
                    stringResource(R.string.bypass_verified_at, formatBypassTimestamp(verifiedAt))
                } else {
                    stringResource(R.string.bypass_test_first_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val vpnErrorRes = vpnState.errorRes
            when {
                vpnState.active -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Patch 49: the text takes what the Stop button leaves and wraps; unweighted it took the whole row
                    // and squeezed the button (the Russian label is about 190dp, Stop another 100dp, in a 290dp card).
                    Text(
                        stringResource(R.string.bypass_youtube_active),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    FocusRing {
                        TextButton(onClick = { BypassVpnService.stop(context) }) { Text(stringResource(R.string.stop)) }
                    }
                }
                vpnState.starting -> Text(
                    stringResource(R.string.bypass_notification_starting),
                    style = MaterialTheme.typography.bodySmall
                )
                vpnErrorRes != null -> Text(
                    stringResource(vpnErrorRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    if (searching) {
        val current = searchState.progress
        Text(
            stringResource(
                R.string.bypass_find_progress,
                ((current?.index ?: 0) + 1).coerceAtMost((current?.total ?: 1).coerceAtLeast(1)),
                current?.total ?: 0
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (current == null || current.total == 0) 0f else current.index.toFloat() / current.total },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        if (searchState.mandatory) {
            // Patch 51: the first-run check cannot be stopped.
            Text(
                text = stringResource(R.string.bypass_first_run_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FocusRing(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { DpiSearchService.stop(context) }) {
                    Text(stringResource(R.string.bypass_stop_search))
                }
            }
        }
    } else {
        FocusRing(modifier = Modifier.fillMaxWidth()) {
            Button(modifier = Modifier.fillMaxWidth(), enabled = controlsEnabled, onClick = startSearch) {
                Text(stringResource(R.string.bypass_find))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.bypass_enable), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FocusRing(shape = MaterialTheme.shapes.extraLarge) {
            KinescopeSwitch(
                checked = enabled,
                enabled = verified && controlsEnabled,
                onCheckedChange = {
                    enabled = it
                    DpiPrefs.setEnabled(context, it)
                }
            )
        }
    }
    if (downloadActive) {
        Text(
            stringResource(R.string.bypass_wait_download),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (!verified) {
        Text(
            stringResource(R.string.bypass_enable_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    FocusRing(modifier = Modifier.fillMaxWidth()) {
        TextButton(modifier = Modifier.fillMaxWidth(), enabled = controlsEnabled, onClick = runUpdate) {
            Text(stringResource(if (busy == BypassBusy.UPDATING) R.string.bypass_updating else R.string.bypass_update))
        }
    }

    message?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        if (listUpdatedAt == 0L) stringResource(R.string.bypass_list_builtin)
        else stringResource(R.string.bypass_list_updated, formatBypassTimestamp(listUpdatedAt)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )


}

private fun formatBypassTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

/** The last section of Settings, shown only when auto mode was disabled on Home. */
@Composable
internal fun ManualStrategySettings() {
    val context = LocalContext.current
    val netClass by NetClassWatcher.state.collectAsState()
    val cls = netClass ?: NetworkState.currentClass(context)
    val search by DpiSearchController.state.collectAsState()
    val vpn by BypassVpnController.state.collectAsState()
    val jobs by DownloadQueueBus.jobs.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    // A manual pin is the per-network-class indication that auto mode is off.
    val pinned = remember(cls, refresh) { DpiPrefs.pinned(context, cls) }
    if (pinned == null) return
    val downloadBusy = jobs.any {
        it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)
    }
    val canEdit = !search.running && !vpn.active && !vpn.starting && !downloadBusy
    SettingsManualHeading()
    StrategyPicker(
        enabled = canEdit,
        refreshKey = search.resultMessageRes to refresh,
        onMessage = { message = it },
        onChanged = { refresh++ }
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SettingsManualHeading() {
    Text(stringResource(R.string.dash_strategy_auto_off), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
