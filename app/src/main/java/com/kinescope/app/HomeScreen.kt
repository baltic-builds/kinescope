package com.kinescope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp

// Home: the bypass dashboard slot, the queue and library lists, the empty state and the banners.
// Patch 40: moved out of MainActivity.kt unchanged. Patch 41: two panes on wide windows (queue left, library
// right), focus rings for TV, an empty-state text that names the control the screen really has.

@Composable
internal fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    library: List<LibraryItem>,
    bypassState: BypassVpnUiState,
    onOpenBypass: () -> Unit,
    requestNotifications: () -> Unit,
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
    val spec = LocalAdaptive.current
    // Patch 45: the dashboard says when a bypass change applies and when a new check must wait; these are the
    // same states Settings uses for its own controls.
    val activeDownloads = jobs.count {
        it.state == JobState.PREPARING || it.state == JobState.RUNNING ||
            it.state == JobState.PROCESSING || it.state == JobState.SAVING
    }
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

    // The three blocks of Home. One column shows them one after another (a phone, a narrow window); two panes
    // put the first two on the left and the library on the right, each scrolling on its own.
    val topItems: LazyListScope.() -> Unit = {
        item {
            // Patch 45: the hero card is a dashboard (see BypassDashboard.kt). Before, it held one YouTube
            // button (patch 31) and the bypass switch needed a trip into Settings.
            BypassDashboard(
                tunnel = bypassState,
                activeDownloads = activeDownloads,
                onOpenYouTube = onOpenBypass,
                requestNotifications = requestNotifications
            )
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
    }

    val queueItems: LazyListScope.() -> Unit = {
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
    }

    val libraryItems: LazyListScope.() -> Unit = {
        item {
            if (!spec.twoPane) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.library_title), style = MaterialTheme.typography.titleMedium)
                FocusRing {
                    TextButton(onClick = onRefreshLibrary) { Text(stringResource(R.string.refresh)) }
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

    if (spec.twoPane) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spec.paneGapDp.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                topItems()
                queueItems()
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                libraryItems()
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            topItems()
            queueItems()
            libraryItems()
        }
    }
}

@Composable
private fun EmptyQueueState() {
    val spec = LocalAdaptive.current
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
            // The phone text points at "the center button below"; the rail layouts have no such button,
            // and a TV has no share sheet to share a link from.
            text = stringResource(
                when {
                    spec.isTv -> R.string.empty_queue_tv
                    spec.useRail -> R.string.empty_queue_side
                    else -> R.string.empty_queue
                }
            ),
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
            DismissButton(onDismiss)
        }
    }
}

@Composable
private fun DismissButton(onDismiss: () -> Unit) {
    FocusRing {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_dismiss))
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
                DismissButton(onDismiss)
            }
            FocusRing {
                TextButton(onClick = onSignIn) { Text(stringResource(actionRes)) }
            }
        }
    }
}
