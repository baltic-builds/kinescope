package com.kinescope.app

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

// Home: the bypass dashboard, the strategy groups, the banners and the hints.
// Patch 40: moved out of MainActivity.kt unchanged. Patch 41: two panes on wide windows, focus rings for TV, an
// empty-state text that names the control the screen really has.
// Patch 51/52: the library and the queue left Home (their own destinations); Home is the dashboard, its main button and the
// banners. Patch 55: the hints panel closes the column.
// Patch 56: the strategy groups card joins Home, and the wide layout is back, for a reason of its own: on a tablet in landscape
// the hints sat below the dashboard, out of sight until the column was scrolled. A wide window (`AdaptiveSpec.twoPane`) now shows
// the dashboard and the banners on the left and the groups and the hints on the right, so nothing the screen explains is hidden
// below the fold; every other window keeps one column in the order dashboard, banners, groups, hints.

/** The widest Home column of the single-column layout; on a phone or a narrow tablet it is centred instead of stretching the dashboard across. */
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
    val spec = LocalAdaptive.current
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

    val dashboard: @Composable () -> Unit = {
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
    val banners: @Composable () -> Unit = {
        if (networkFailure && !networkBannerDismissed) {
            ErrorBanner(
                text = stringResource(R.string.banner_no_internet),
                onDismiss = { networkBannerDismissed = true }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (verificationFailure && !verificationBannerDismissed) {
            VerificationBanner(
                onDismiss = { verificationBannerDismissed = true },
                onSignIn = onSignIn
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (instagramFailure && !instagramBannerDismissed) {
            VerificationBanner(
                onDismiss = { instagramBannerDismissed = true },
                onSignIn = onSignInInstagram,
                messageRes = R.string.banner_instagram_login,
                actionRes = R.string.instagram_sign_in_action
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                item { Column { dashboard(); banners() } }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    StrategyGroupsCard()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                item { HintsPanel() }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // `widthIn` first: after `fillMaxSize` the cap would change nothing (HANDOFF.md, patch 48).
            LazyColumn(
                modifier = Modifier.widthIn(max = HomeMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { Column { dashboard(); banners() } }
                item {
                    StrategyGroupsCard()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                item {
                    // Patch 55: the hints, below everything else, in a panel that stays.
                    HintsPanel()
                }
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
