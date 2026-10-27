package com.kinescope.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Home: the bypass dashboard slot and the banners.
// Patch 40: moved out of MainActivity.kt unchanged. Patch 41: two panes on wide windows, focus rings for TV, an
// empty-state text that names the control the screen really has.
// Patch 51: the library left Home (it has its own screen, `LibraryScreen.kt`); the queue became compact; a notice
// recommends re-checking the strategies after an app update.
// Patch 52: the queue left Home too (it is the second destination of the navigation, `QueueScreen.kt`). Home is now
// the dashboard, its "Unblock YouTube" button and the banners in ONE column on every window: the right-hand pane that
// held the queue is gone, and a card of one-line rows would only be stretched by a second, empty pane.
// Patch 55: the "app updated" notice is gone (an update now checks the strategies by itself) and the hints panel closes the
// column (`HintsPanel.kt`).

/** The widest Home column; on a tablet or TV the column is centred instead of stretching the dashboard across. */
private val HomeMaxWidth = 640.dp

@Composable
internal fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    bypassState: BypassVpnUiState,
    onToggleTunnel: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    requestNotifications: () -> Unit,
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // `widthIn` first: after `fillMaxSize` the cap would change nothing (HANDOFF.md, patch 48).
        LazyColumn(
            modifier = Modifier.widthIn(max = HomeMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                // Patch 45: the hero card is a dashboard (see BypassDashboard.kt); patch 52: its button sits below it.
                BypassDashboard(
                    tunnel = bypassState,
                    jobs = jobs,
                    onToggleTunnel = onToggleTunnel,
                    onOpenApp = onOpenApp,
                    requestNotifications = requestNotifications
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                // Patch 55: the hints, below everything else, in a panel that stays.
                HintsPanel()
            }
        }
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
