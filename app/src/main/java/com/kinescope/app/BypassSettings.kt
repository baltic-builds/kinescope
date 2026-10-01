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

    val initialStrategy = remember { DpiStrategyStore.selected(context) }
    val initialVerified = remember { DpiPrefs.isStrategyVerified(context, initialStrategy) }
    var enabled by remember { mutableStateOf(DpiPrefs.isEnabled(context) && initialVerified) }
    var verified by remember { mutableStateOf(initialVerified) }
    var verifiedAt by remember { mutableStateOf(DpiPrefs.verifiedAt(context)) }
    var listUpdatedAt by remember { mutableStateOf(DpiPrefs.listUpdatedAt(context)) }
    var busy by remember { mutableStateOf(BypassBusy.NONE) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (DpiPrefs.isEnabled(context) && !initialVerified) DpiPrefs.setEnabled(context, false)
    }
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
            verified = DpiPrefs.isStrategyVerified(context, DpiStrategyStore.selected(context))
            verifiedAt = DpiPrefs.verifiedAt(context)
            enabled = DpiPrefs.isEnabled(context)
            message = context.getString(resultMessageRes)
        }
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

    // Runs the strategy search by itself, once, the first time this screen is opened on a
    // device -- so a fresh install/update does not require the user to know to press
    // "Test strategies" before the bypass (or the Home-screen YouTube action) can do anything.
    // The flag is only consumed once the search actually starts: if the screen first opens
    // while blocked (a download is active), the one automatic attempt is not silently wasted --
    // it simply waits for a later visit where controlsEnabled is true.
    LaunchedEffect(Unit) {
        if (!DpiPrefs.hasRunInitialSearch(context) && !initialVerified && controlsEnabled) {
            DpiPrefs.setHasRunInitialSearch(context, true)
            startSearch()
        }
    }

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
                    Text(stringResource(R.string.bypass_youtube_active), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { BypassVpnService.stop(context) }) { Text(stringResource(R.string.stop)) }
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
            progress = if (current == null || current.total == 0) 0f else current.index.toFloat() / current.total,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { DpiSearchService.stop(context) }) {
            Text(stringResource(R.string.bypass_stop_search))
        }
    } else {
        Button(modifier = Modifier.fillMaxWidth(), enabled = controlsEnabled, onClick = startSearch) {
            Text(stringResource(R.string.bypass_find))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.bypass_enable), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            enabled = verified && controlsEnabled,
            onCheckedChange = {
                enabled = it
                DpiPrefs.setEnabled(context, it)
            }
        )
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

    TextButton(modifier = Modifier.fillMaxWidth(), enabled = controlsEnabled, onClick = runUpdate) {
        Text(stringResource(if (busy == BypassBusy.UPDATING) R.string.bypass_updating else R.string.bypass_update))
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

    // Patch 35: test one strategy, or pin one, like the strategy list in ByeByeDPI.
    StrategyPicker(
        enabled = controlsEnabled,
        refreshKey = searchState.resultMessageRes,
        onMessage = { message = it },
        onChanged = {
            verified = DpiPrefs.isStrategyVerified(context, DpiStrategyStore.selected(context))
            verifiedAt = DpiPrefs.verifiedAt(context)
            enabled = DpiPrefs.isEnabled(context)
        }
    )
}

private fun formatBypassTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
