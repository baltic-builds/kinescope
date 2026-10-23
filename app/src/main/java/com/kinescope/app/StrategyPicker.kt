package com.kinescope.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class PickerRow(val number: Int, val line: String, val result: StoredResult?)

/**
 * Patch 48: how many candidates are drawn at first and per "Show more". The list can hold 270 of them; drawing all
 * of them put hundreds of cards (and, with a D-pad, hundreds of focus stops) inside Settings' scrolling column.
 * The best ones (pinned, then passed, then by score) come first, so the first page is the useful one.
 */
private const val PICKER_PAGE_SIZE = 12

/**
 * Patch 35: manual strategy choice, like the strategy list in ByeByeDPI. Every candidate is listed
 * with what the last test found (score, speed, simultaneous connections). Test runs one strategy
 * without changing the chain; Use pins it, so it goes first and automatic searches keep it there.
 * A plain Column (no lazy list) because this sits inside the settings screen's own scrolling; patch 48 keeps it
 * short by showing a page of candidates at a time.
 */
@Composable
internal fun StrategyPicker(
    enabled: Boolean,
    refreshKey: Any?,
    onMessage: (String) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(PICKER_PAGE_SIZE) }
    var version by remember { mutableStateOf(0) }
    val results = remember(version, refreshKey) { DpiResultsStore.load(context) }
    val candidates = remember(version, refreshKey) { DpiStrategyStore.candidates(context) }
    val pinned = remember(version, refreshKey) { DpiPrefs.pinned(context) }
    // Patch 51: every strategy is shown by its colour name instead of a position number.
    val names = remember(version, refreshKey) { DpiStrategyStore.names(context) }
    val rows = remember(candidates, results, pinned) {
        candidates.mapIndexed { index, line -> PickerRow(index + 1, line, results[line]) }
            .sortedWith(
                compareByDescending<PickerRow> { it.line == pinned }
                    .thenByDescending { it.result?.passed == true }
                    .thenByDescending { it.result?.score ?: -1 }
                    .thenBy { it.number }
            )
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.strategy_picker_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        FocusRing {
            TextButton(
                onClick = {
                    expanded = !expanded
                    if (!expanded) shown = PICKER_PAGE_SIZE
                }
            ) {
                Text(stringResource(if (expanded) R.string.strategy_hide else R.string.strategy_show))
            }
        }
    }
    if (!expanded) return

    Text(
        stringResource(R.string.strategy_picker_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (pinned != null) {
        FocusRing {
            TextButton(
                enabled = enabled,
                onClick = {
                    DpiPrefs.unpin(context)
                    version++
                    onChanged()
                    onMessage(context.getString(R.string.strategy_unpinned))
                }
            ) { Text(stringResource(R.string.strategy_auto)) }
        }
    }
    Spacer(Modifier.height(4.dp))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows.take(shown)) {
            val isPinned = row.line == pinned
            val result = row.result
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (isPinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (isPinned) names.nameOf(row.line) + " · " + stringResource(R.string.strategy_pinned) else names.nameOf(row.line),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            FocusRing {
                                TextButton(
                                    enabled = enabled,
                                    onClick = {
                                        onMessage(context.getString(R.string.strategy_testing))
                                        DpiSearchService.startTest(context, row.line)
                                    }
                                ) { Text(stringResource(R.string.strategy_test)) }
                            }
                            FocusRing {
                                TextButton(
                                    enabled = enabled && !isPinned,
                                    onClick = {
                                        DpiPrefs.pin(context, row.line)
                                        version++
                                        onChanged()
                                        onMessage(context.getString(R.string.strategy_pinned_done))
                                    }
                                ) { Text(stringResource(R.string.strategy_use)) }
                            }
                        }
                    }
                    Text(
                        when {
                            result == null -> stringResource(R.string.strategy_untested)
                            !result.passed -> stringResource(R.string.strategy_result_failed)
                            result.loadTotal > 0 -> stringResource(
                                R.string.strategy_result_ok, result.score, result.kbps, result.loadPassed, result.loadTotal
                            )
                            else -> stringResource(R.string.strategy_result_basic, result.score, result.kbps)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        row.line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (rows.size > shown) {
            FocusRing(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { shown += PICKER_PAGE_SIZE }
                ) {
                    Text(stringResource(R.string.strategy_show_more, rows.size - shown))
                }
            }
        }
    }
}
