package com.kinescope.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The Logs screen (five taps on the Settings icon) and its copy/share actions.
// Patch 40: moved out of MainActivity.kt unchanged.

@Composable
internal fun LogsScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(AppLog.readLines()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Patch 34: Copy report / Share put the report header plus the newest lines on the clipboard.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { copyReport(context) }) {
                Text(stringResource(R.string.copy_report))
            }
            OutlinedButton(onClick = { shareLogs(context) }) {
                Text(stringResource(R.string.share_logs))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { lines = AppLog.readLines() }) {
                Text(stringResource(R.string.refresh))
            }
            OutlinedButton(onClick = {
                AppLog.clear()
                lines = emptyList()
            }) {
                Text(stringResource(R.string.clear_logs))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.logs_privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/** Patch 34: header (build, device, network, strategies, legend) plus the newest lines, on the clipboard. */
private fun copyReport(context: Context) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Kinescope report", DiagnosticReport.build(context)))
    Toast.makeText(context, R.string.report_copied, Toast.LENGTH_SHORT).show()
}

private fun shareLogs(context: Context) {
    val payload = DiagnosticReport.build(context)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, payload)
        putExtra(Intent.EXTRA_TITLE, context.getString(R.string.logs_title))
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_logs)))
    }.onFailure { AppLog.e("Logs", "Could not open share sheet", it) }
}
