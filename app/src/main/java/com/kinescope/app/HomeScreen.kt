package com.kinescope.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Home: the bypass dashboard slot, the banners and the download queue.
// Patch 40: moved out of MainActivity.kt unchanged. Patch 41: two panes on wide windows, focus rings for TV, an
// empty-state text that names the control the screen really has.
// Patch 51: the library left Home (it has its own screen, `LibraryScreen.kt`); the queue is compact (three rows, a
// one-line empty state) so the dashboard has the screen; two panes are now the dashboard (left) and the queue (right);
// a notice recommends re-checking the strategies after an app update.

/** How many queue rows a single-column Home shows before "Show all". */
private const val QUEUE_COLLAPSED_ROWS = 3

@Composable
internal fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    bypassState: BypassVpnUiState,
    onToggleTunnel: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    requestNotifications: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignInInstagram: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val spec = LocalAdaptive.current
    val updateNotice by UpdateNotice.pending.collectAsState()
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
    var showAllQueue by rememberSaveable { mutableStateOf(false) }

    // The two blocks of Home. One column shows them one after another (a phone, a narrow window); two panes put the
    // dashboard and the banners on the left and the queue on the right, each scrolling on its own.
    val topItems: LazyListScope.() -> Unit = {
        item {
            // Patch 45: the hero card is a dashboard (see BypassDashboard.kt).
            BypassDashboard(
                tunnel = bypassState,
                jobs = jobs,
                onToggleTunnel = onToggleTunnel,
                onOpenApp = onOpenApp,
                requestNotifications = requestNotifications
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (updateNotice) {
            item {
                UpdateNoticeBanner(
                    onDismiss = { UpdateNotice.dismiss(context) },
                    onOpenSettings = {
                        UpdateNotice.dismiss(context)
                        onOpenSettings()
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
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
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (jobs.isEmpty()) {
            item { EmptyQueueState() }
        } else {
            val collapsible = !spec.twoPane && jobs.size > QUEUE_COLLAPSED_ROWS
            val visible = if (collapsible && !showAllQueue) jobs.take(QUEUE_COLLAPSED_ROWS) else jobs
            items(visible, key = { it.id }) { job ->
                QueueRow(job, onPause, onResume, onStop)
            }
            if (collapsible) {
                item {
                    FocusRing {
                        TextButton(onClick = { showAllQueue = !showAllQueue }) {
                            Text(
                                if (showAllQueue) {
                                    stringResource(R.string.queue_show_fewer)
                                } else {
                                    stringResource(R.string.queue_show_all, jobs.size)
                                }
                            )
                        }
                    }
                }
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
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                queueItems()
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            topItems()
            queueItems()
        }
    }
}

/** One line, no picture: the empty queue used to take a whole screen section (patch 51). */
@Composable
private fun EmptyQueueState() {
    val spec = LocalAdaptive.current
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
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
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

/** Patch 51: after an app update yt-dlp and the strategy list refresh themselves; this recommends the re-check. */
@Composable
private fun UpdateNoticeBanner(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.banner_updated),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                DismissButton(onDismiss)
            }
            FocusRing {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.banner_updated_action)) }
            }
        }
    }
}
