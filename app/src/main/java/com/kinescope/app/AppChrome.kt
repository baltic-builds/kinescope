package com.kinescope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The app shell pieces around the screens: the responsive content column, the bottom navigation and the
// strategy-refresh notice. Patch 40: moved out of MainActivity.kt unchanged.

/**
 * Places screen content inside the Scaffold's insets with the usual 20dp side margins.
 *
 * - Width: centered and capped at 640dp, so landscape, tablets and unfolded foldables do not
 *   stretch lists and cards into one unreadably wide column. On a phone in portrait the cap is
 *   never reached, so nothing changes there. (`widthIn` must come before `fillMaxSize`: the
 *   other order forces the full width first and the cap is ignored.)
 * - Keyboard: edge-to-edge windows are not resized for the keyboard, so `imePadding` lifts the
 *   content above it. `consumeWindowInsets(innerPadding)` first marks the space the Scaffold
 *   already reserved (top bar, bottom bar and system bars) as used, so only the remainder of the
 *   keyboard height is added instead of the whole of it.
 */
@Composable
internal fun ResponsiveContent(innerPadding: PaddingValues, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            content = content
        )
    }
}

/**
 * Patch 36: explains automatic strategy work on a fresh install and after an app update. Android
 * selects values-ru only for a Russian locale; every other locale falls back to English values/.
 */
@Composable
internal fun StrategyRefreshNoticeDialog(progress: SearchProgress?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.strategy_refresh_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.strategy_refresh_body))
                if (progress != null && progress.total > 0) {
                    Text(
                        stringResource(
                            R.string.strategy_refresh_progress,
                            (progress.index + 1).coerceAtMost(progress.total),
                            progress.total
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = progress.index.toFloat() / progress.total,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.strategy_refresh_ok)) }
        }
    )
}

@Composable
internal fun GlassBottomBar(
    section: AppSection,
    onHome: () -> Unit,
    onQuickAdd: () -> Unit,
    onSettings: () -> Unit
) {
    // Patch 32: on Android 15+ (targetSdk 35) the system draws this app edge-to-edge, so a
    // hand-built composable like this one -- unlike Material3's NavigationBar/BottomAppBar,
    // which reserve their own insets -- must reserve space above the navigation bar itself, or
    // the classic three-button bar (opaque, ~48dp) is drawn over it. Gesture navigation's bar is
    // a thin transparent strip, which is why this only showed with gestures turned off.
    Surface(
        color = Color.Transparent,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Patch 36: all three actions share the same 56dp touch target and are distributed
            // symmetrically. The bar stays compact on phones and caps its width on larger screens.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 280.dp, max = 380.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.36f),
                        shape = MaterialTheme.shapes.extraLarge
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassNavIcon(selected = section == AppSection.HOME, onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.cd_home))
                    }
                    Surface(
                        modifier = Modifier.size(56.dp).clickable(onClick = onQuickAdd),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.96f),
                        tonalElevation = 2.dp,
                        shadowElevation = 3.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_quick_add),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    GlassNavIcon(
                        selected = section == AppSection.SETTINGS || section == AppSection.LOGS,
                        onClick = onSettings
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassNavIcon(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon()
        }
    }
}
