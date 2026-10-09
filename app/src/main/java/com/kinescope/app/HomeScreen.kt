package com.kinescope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Home: the bypass card, the queue and library lists, the empty state and the banners.
// Patch 40: moved out of MainActivity.kt unchanged.

@Composable
private fun BypassHomeCard(state: BypassVpnUiState, onClick: () -> Unit) {
    val errorRes = state.errorRes
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (state.active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    if (state.active) R.string.bypass_youtube_active else R.string.bypass_home_hint
                ),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = if (state.active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (errorRes != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClick,
                enabled = !state.starting,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .widthIn(min = 180.dp, max = 280.dp)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.active -> R.string.bypass_open_youtube
                            state.starting -> R.string.bypass_starting_short
                            else -> R.string.bypass_quick_action
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
internal fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    library: List<LibraryItem>,
    bypassState: BypassVpnUiState,
    onOpenBypass: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onShare: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignInInstagram: () -> Unit
) {
    val networkFailure = jobs.any {
        it.failureKind == FailureKind.NO_INTERNET && it.state in setOf(JobState.INTERRUPTED, JobState.FAILED)
    }
    val verificationFailure = jobs.any {
        it.failureKind == FailureKind.YOUTUBE_VERIFICATION &&
            (it.state == JobState.PAUSED || it.state == JobState.FAILED)
    }
    var networkBannerDismissed by remember { mutableStateOf(false) }
    var verificationBannerDismissed by remember { mutableStateOf(false) }
    val instagramFailure = jobs.any {
        it.failureKind == FailureKind.INSTAGRAM_LOGIN &&
            (it.state == JobState.PAUSED || it.state == JobState.FAILED)
    }
    var instagramBannerDismissed by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            // Patch 31: a large, obvious, centered card -- previously the only bypass control
            // on Home was a small TextButton tucked into the top bar's corner.
            BypassHomeCard(state = bypassState, onClick = onOpenBypass)
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (networkFailure && !networkBannerDismissed) {
            item {
                ErrorBanner(
                    text = stringResource(R.string.banner_no_internet),
                    onDismiss = { networkBannerDismissed = true }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (verificationFailure && !verificationBannerDismissed) {
            item {
                VerificationBanner(
                    onDismiss = { verificationBannerDismissed = true },
                    onSignIn = onSignIn
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (instagramFailure && !instagramBannerDismissed) {
            item {
                VerificationBanner(
                    onDismiss = { instagramBannerDismissed = true },
                    onSignIn = onSignInInstagram,
                    messageRes = R.string.banner_instagram_login,
                    actionRes = R.string.instagram_sign_in_action
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item {
            Text(text = stringResource(R.string.queue_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (jobs.isEmpty()) {
            item { EmptyQueueState() }
        } else {
            items(jobs, key = { it.id }) { job ->
                QueueRow(job, onPause, onResume, onStop)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.library_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefreshLibrary) { Text(stringResource(R.string.refresh)) }
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

@Composable
private fun EmptyQueueState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_queue),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_dismiss))
            }
        }
    }
}

@Composable
private fun VerificationBanner(
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    messageRes: Int = R.string.banner_youtube_verification,
    actionRes: Int = R.string.youtube_sign_in_action
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_dismiss))
                }
            }
            TextButton(onClick = onSignIn) { Text(stringResource(actionRes)) }
        }
    }
}
