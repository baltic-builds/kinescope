package com.kinescope.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Patch 52: the download queue on its own screen, the second entry of the navigation. It used to be the lower half of
// Home (three rows and "Show all"); the user asked for it to leave Home. Here every job is listed, with the controls a
// job can use: Pause while it downloads, Resume when it is paused, interrupted or failed, and Stop (remove) on every row
// that is not being saved. The rows are the same `QueueRow`s as before, with the Stop button always drawn (before,
// only a TV had it and a touch screen had the swipe alone, which nothing on the row hints at); the swipe still works.
// Patch 64: the default download format is chosen at the top of the queue (the same setting as in Settings and on the Add screen), and
// every row has a small button with its format: choosing another one restarts that download in it.

@Composable
internal fun QueueScreen(
    jobs: List<DownloadJobStatus>,
    defaultQuality: Int,
    onDefaultQuality: (Int) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
    onFormat: (String, QualityId) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item { DefaultFormatRow(defaultQuality, onDefaultQuality) }
        if (jobs.isEmpty()) {
            item { EmptyQueueState() }
        } else {
            items(jobs, key = { it.id }) { job ->
                QueueRow(job, onPause, onResume, onStop, onFormat)
            }
        }
    }
}

/** Patch 64: the format new downloads use, as a row of chips (1080p, 720p, 480p, audio only). */
@Composable
private fun DefaultFormatRow(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.queue_default_format),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualityPresets.forEachIndexed { index, preset ->
                FocusRing(shape = MaterialTheme.shapes.small) {
                    FilterChip(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        label = { Text(stringResource(preset.labelRes)) }
                    )
                }
            }
        }
    }
}

/** One line, no picture. The text names the control the screen really has (the Add button, the rail, a TV's menu). */
@Composable
private fun EmptyQueueState() {
    val spec = LocalAdaptive.current
    Text(
        // The phone text points at "the + button below"; the rail layouts have no bar below, and a TV has no share
        // sheet to share a link from.
        text = stringResource(
            when {
                spec.isTv -> R.string.empty_queue_tv
                spec.useRail -> R.string.empty_queue_side
                else -> R.string.empty_queue
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}
