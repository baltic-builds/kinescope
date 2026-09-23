package com.kinescope.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BypassBusy { NONE, SEARCHING, TESTING, UPDATING }

/**
 * Settings block for the network bypass: switch, strategy search and selection, connection test
 * and strategy-list update. All blocking work runs on Dispatchers.IO; the screen is kept awake
 * while a search or test is running because both need the app in the foreground.
 */
@Composable
fun BypassSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var enabled by remember { mutableStateOf(DpiPrefs.isEnabled(context)) }
    var strategy by remember { mutableStateOf(DpiStrategyStore.selected(context)) }
    var listUpdatedAt by remember { mutableStateOf(DpiPrefs.listUpdatedAt(context)) }
    var busy by remember { mutableStateOf(BypassBusy.NONE) }

    var searchJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<SearchProgress?>(null) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<StrategyResult>>(emptyList()) }
    var diagnosis by remember { mutableStateOf<BypassDiagnosis?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(busy) {
        view.keepScreenOn = busy != BypassBusy.NONE
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }

    val startSearch: () -> Unit = {
        busy = BypassBusy.SEARCHING
        searchMessage = null
        searchResults = emptyList()
        progress = null
        searchJob = scope.launch {
            try {
                val directWorks = withContext(Dispatchers.IO) { DpiBypass.directConnectionWorks() }
                if (directWorks) {
                    searchMessage = context.getString(R.string.bypass_search_not_needed)
                } else {
                    val results = withContext(Dispatchers.IO) {
                        DpiBypass.search(context, isCancelled = { !isActive }, onProgress = { progress = it })
                    }
                    searchResults = results.filter { it.started && it.passed > 0 }.sortedByDescending { it.passed }
                    val best = DpiStrategySearch.best(results)
                    if (best != null) {
                        DpiPrefs.setStrategy(context, best.line)
                        strategy = best.line
                        searchMessage = context.getString(R.string.bypass_search_found)
                    } else {
                        searchMessage = context.getString(R.string.bypass_search_none)
                    }
                }
            } catch (e: CancellationException) {
                searchMessage = context.getString(R.string.bypass_search_stopped)
                throw e
            } finally {
                busy = BypassBusy.NONE
                progress = null
                searchJob = null
            }
        }
    }

    val runTest: () -> Unit = {
        busy = BypassBusy.TESTING
        diagnosis = null
        scope.launch {
            try {
                diagnosis = withContext(Dispatchers.IO) { DpiBypass.diagnose(context) }
            } finally {
                busy = BypassBusy.NONE
            }
        }
    }

    val runUpdate: () -> Unit = {
        busy = BypassBusy.UPDATING
        updateMessage = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { DpiStrategyStore.update(context) }
                updateMessage = when (result) {
                    is StrategyListUpdate.Updated -> context.getString(R.string.bypass_update_done, result.added, result.total)
                    is StrategyListUpdate.Unchanged -> context.getString(R.string.bypass_update_same, result.total)
                    is StrategyListUpdate.Failed -> context.getString(R.string.bypass_update_failed, result.reason)
                }
                listUpdatedAt = DpiPrefs.listUpdatedAt(context)
            } finally {
                busy = BypassBusy.NONE
            }
        }
    }

    Text(text = stringResource(R.string.bypass_title), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.bypass_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.bypass_enable),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                DpiPrefs.setEnabled(context, it)
            }
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.bypass_strategy_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(text = strategy, style = MaterialTheme.typography.bodySmall)
    Spacer(modifier = Modifier.height(12.dp))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy == BypassBusy.SEARCHING) {
            val current = progress
            Text(
                text = stringResource(R.string.bypass_find_progress, ((current?.index ?: 0) + 1).coerceAtMost(current?.total ?: 1), current?.total ?: 0),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = if (current == null || current.total == 0) 0f else current.index.toFloat() / current.total,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
            OutlinedButton(onClick = { searchJob?.cancel() }) {
                Text(stringResource(R.string.bypass_stop_search))
            }
        } else {
            Button(enabled = busy == BypassBusy.NONE, onClick = startSearch) {
                Text(stringResource(R.string.bypass_find))
            }
        }
        OutlinedButton(enabled = busy == BypassBusy.NONE, onClick = runTest) {
            Text(stringResource(if (busy == BypassBusy.TESTING) R.string.bypass_testing else R.string.bypass_test))
        }
        OutlinedButton(enabled = busy == BypassBusy.NONE, onClick = runUpdate) {
            Text(stringResource(if (busy == BypassBusy.UPDATING) R.string.bypass_updating else R.string.bypass_update))
        }
    }

    searchMessage?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    if (searchResults.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        searchResults.take(MAX_RESULT_ROWS).forEach { result ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bypass_result_hosts, result.passed, result.total),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = result.line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    enabled = result.line != strategy,
                    onClick = {
                        DpiPrefs.setStrategy(context, result.line)
                        strategy = result.line
                    }
                ) { Text(stringResource(R.string.bypass_use)) }
            }
        }
    }
    updateMessage?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (listUpdatedAt == 0L) {
            stringResource(R.string.bypass_list_builtin)
        } else {
            stringResource(R.string.bypass_list_updated, formatBypassTimestamp(listUpdatedAt))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.bypass_engine_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    diagnosis?.let { result ->
        AlertDialog(
            onDismissRequest = { diagnosis = null },
            title = { Text(stringResource(R.string.bypass_test_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!result.engineStarted) {
                        Text(stringResource(R.string.bypass_engine_failed), color = MaterialTheme.colorScheme.error)
                    }
                    result.reports.forEach { report ->
                        Text(report.host, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.bypass_test_direct, describePath(context, report.direct)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        report.bypassed?.let {
                            Text(
                                stringResource(R.string.bypass_test_via, describePath(context, it)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    result.reports.firstOrNull()?.let { first ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(verdictText(context, verdictOf(first)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { diagnosis = null }) { Text(stringResource(R.string.bypass_close)) }
            }
        )
    }
}

private const val MAX_RESULT_ROWS = 6

private fun formatBypassTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun describePath(context: Context, path: PathResult): String {
    val failed = path.failed ?: return context.getString(R.string.bypass_test_ok)
    return context.getString(R.string.bypass_test_failed_at, context.getString(stageLabel(failed.stage)))
}

private fun stageLabel(stage: CheckStage): Int = when (stage) {
    CheckStage.DNS -> R.string.bypass_stage_dns
    CheckStage.TCP -> R.string.bypass_stage_tcp
    CheckStage.PROXY -> R.string.bypass_stage_proxy
    CheckStage.CONNECT -> R.string.bypass_stage_connect
    CheckStage.TLS -> R.string.bypass_stage_tls
    CheckStage.HTTP -> R.string.bypass_stage_http
}

private fun verdictText(context: Context, verdict: Verdict): String = context.getString(
    when (verdict) {
        Verdict.DIRECT_OK -> R.string.bypass_verdict_direct_ok
        Verdict.DNS_BLOCKED -> R.string.bypass_verdict_dns_blocked
        Verdict.DNS_BLOCKS_BYPASS -> R.string.bypass_verdict_dns_blocks_bypass
        Verdict.TCP_BLOCKED -> R.string.bypass_verdict_tcp_blocked
        Verdict.TLS_INTERFERENCE -> R.string.bypass_verdict_tls_interference
        Verdict.BYPASS_FIXES -> R.string.bypass_verdict_bypass_fixes
        Verdict.BYPASS_ALSO_FAILS -> R.string.bypass_verdict_bypass_also_fails
        Verdict.BYPASS_UNAVAILABLE -> R.string.bypass_verdict_bypass_unavailable
    }
)
