package com.kinescope.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The Logs screen (Settings -> Diagnostics; five taps on the Settings icon still open it) and its copy/share actions.
// Patch 40: moved out of MainActivity.kt unchanged. Patch 41: one row of four buttons when the window has room,
// and on a TV every log line was a focus stop. Patch 48: the buttons wrap onto a second line instead of being
// squeezed (a 600dp tablet or a landscape phone has room for about three of them), Top and Bottom jump through a
// long journal, and the whole list is ONE focus stop on every device: a D-pad (or a hardware keyboard) scrolls it
// a page at a time with Up and Down and moves on at either end. Up to 300 focusable lines were 300 presses.

/** Fraction of the visible list one D-pad press scrolls: a little less than a page, so the eye keeps its place. */
private const val PAGE_SCROLL_FRACTION = 0.8f

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var lines by remember { mutableStateOf(AppLog.readLines()) }

    Column(modifier = Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Patch 34: Copy report / Share put the report header plus the newest lines on the clipboard.
            FocusRing {
                Button(onClick = { copyReport(context) }) { Text(stringResource(R.string.copy_report)) }
            }
            FocusRing {
                OutlinedButton(onClick = { shareLogs(context) }) { Text(stringResource(R.string.share_logs)) }
            }
            FocusRing {
                OutlinedButton(onClick = { lines = AppLog.readLines() }) { Text(stringResource(R.string.refresh)) }
            }
            FocusRing {
                OutlinedButton(
                    onClick = {
                        AppLog.clear()
                        lines = emptyList()
                    }
                ) { Text(stringResource(R.string.clear_logs)) }
            }
            FocusRing {
                TextButton(
                    enabled = lines.isNotEmpty(),
                    onClick = { scope.launch { listState.scrollToItem(0) } }
                ) { Text(stringResource(R.string.logs_jump_top)) }
            }
            FocusRing {
                TextButton(
                    enabled = lines.isNotEmpty(),
                    onClick = { scope.launch { listState.scrollToItem((lines.size - 1).coerceAtLeast(0)) } }
                ) { Text(stringResource(R.string.logs_jump_bottom)) }
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
            FocusRing(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.small) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onKeyEvent { event -> scrollPageOnDpad(event, listState, scope) }
                        .focusable()
                ) {
                    items(lines) { line ->
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
}

/**
 * D-pad Up/Down on the focused list scroll it by a page and consume the key. At the top or the bottom the key is
 * not consumed, so focus moves on to the buttons above (or the navigation) like everywhere else.
 */
private fun scrollPageOnDpad(event: KeyEvent, listState: LazyListState, scope: CoroutineScope): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val page = listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION
    return when (event.key) {
        Key.DirectionDown -> listState.canScrollForward.also { if (it) scope.launch { listState.animateScrollBy(page) } }
        Key.DirectionUp -> listState.canScrollBackward.also { if (it) scope.launch { listState.animateScrollBy(-page) } }
        else -> false
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
