package com.kinescope.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Patch 51: the library of finished downloads, on its own screen. It used to be the lower half of Home; the user asked
// for it to leave Home, and removing the list outright would have left no way to play or delete a download inside the
// app, so it is a destination of its own (the navigation's fourth entry). The rows are the same `LibraryRow`s.

@Composable
internal fun LibraryScreen(
    library: List<LibraryItem>,
    onRefresh: () -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onShare: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Weighted, so a large font size wraps the title instead of squeezing Refresh (patch 49).
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FocusRing {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                }
            }
        }
        if (library.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(library, key = { it.uri.toString() }) { item ->
                LibraryRow(
                    item = item,
                    onPlay = { onPlay(item) },
                    onShare = { onShare(item) },
                    onDelete = { onDelete(item) }
                )
            }
        }
    }
}
