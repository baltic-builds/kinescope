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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The app shell pieces around the screens: the frame (top bar plus bottom bar, or header plus navigation rail),
// the responsive content column, the navigation and the strategy-refresh notice. Patch 40: moved out of
// MainActivity.kt unchanged. Patch 41: the frame and the rail are new; the rest adapts to LocalAdaptive.

/**
 * Places screen content inside the frame's insets.
 *
 * - Width: centered and capped (640dp on a phone in portrait, wider on tablets and TVs, see
 *   `AdaptiveSpec`), so landscape, tablets and unfolded foldables do not stretch lists and cards into one
 *   unreadably wide column. (`widthIn` must come before `fillMaxSize`: the other order forces the full width
 *   first and the cap is ignored.)
 * - Keyboard: edge-to-edge windows are not resized for the keyboard, so `imePadding` lifts the
 *   content above it. `consumeWindowInsets(innerPadding)` first marks the space the Scaffold
 *   already reserved (top bar, bottom bar and system bars) as used, so only the remainder of the
 *   keyboard height is added instead of the whole of it. The rail frame applies the safe-drawing insets
 *   (which include the keyboard) itself and passes zero padding, so nothing is added twice.
 */
@Composable
internal fun ResponsiveContent(innerPadding: PaddingValues, content: @Composable BoxScope.() -> Unit) {
    val spec = LocalAdaptive.current
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
                .widthIn(max = spec.contentMaxWidthDp.dp)
                .fillMaxSize()
                .padding(start = spec.contentStartPaddingDp.dp, end = spec.contentEndPaddingDp.dp),
            content = content
        )
    }
}

/**
 * Patch 41: the frame of every screen. A phone in portrait keeps the top bar and the floating bottom bar it has
 * always had. Medium and expanded windows, short landscape windows and TVs get a floating navigation rail on
 * the left and a plain header (a Material top bar is sized for a phone), inside the TV overscan margins where
 * needed. [content] receives the padding to apply; the rail layout hands over zero because it already applied
 * the insets.
 *
 * Patch 42: ONE structure for every layout. The screen content is composed from a single call site, so turning
 * the phone (portrait -> landscape switches to the rail) or resizing a window moves the same composition
 * instead of rebuilding it. Two separate branches (a Scaffold here, a Row there) threw away every screen's
 * state on rotation, including a sign-in page that was half filled in.
 */
@Composable
internal fun AppFrame(
    section: AppSection,
    title: String,
    showBack: Boolean,
    showNavigation: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onQuickAdd: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val spec = LocalAdaptive.current
    val rail = spec.useRail
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The rail layout applies the safe-drawing insets itself (below), so the Scaffold must not add them too.
        contentWindowInsets = if (rail) WindowInsets(0.dp) else ScaffoldDefaults.contentWindowInsets,
        topBar = { if (!rail) CompactTopBar(title = title, showBack = showBack, onBack = onBack) },
        bottomBar = {
            if (!rail && showNavigation) {
                GlassBottomBar(
                    section = section,
                    onHome = onHome,
                    onQuickAdd = onQuickAdd,
                    onSettings = onSettings
                )
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (rail) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = spec.safeHorizontalDp.dp, vertical = spec.safeVerticalDp.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            if (rail && showNavigation) {
                GlassNavRail(section = section, onHome = onHome, onQuickAdd = onQuickAdd, onSettings = onSettings)
                Spacer(modifier = Modifier.width(spec.railGapDp.dp))
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (rail) ScreenHeader(title = title, showBack = showBack, onBack = onBack)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content(if (rail) PaddingValues(0.dp) else innerPadding)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTopBar(title: String, showBack: Boolean, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            }
        },
        // Patch 31: the small top-bar bypass action was easy to miss (a plain
        // TextButton tucked in the corner). It moved to a large, centered card at the
        // top of HomeScreen (BypassHomeCard) instead -- see CHANGELOG.md.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun ScreenHeader(title: String, showBack: Boolean, onBack: () -> Unit) {
    val spec = LocalAdaptive.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (spec.isTv) 72.dp else 64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            FocusRing {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
                        progress = { progress.index.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            FocusRing {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.strategy_refresh_ok)) }
            }
        }
    )
}

/**
 * Patch 41: the bottom bar's counterpart for wide windows: the same floating glass strip, standing on the
 * left edge. Touch layouts keep it icon-only like the bottom bar; on TV every item has a label, because a
 * remote control has no long-press hint and the icons alone are not enough from across the room.
 */
@Composable
internal fun GlassNavRail(
    section: AppSection,
    onHome: () -> Unit,
    onQuickAdd: () -> Unit,
    onSettings: () -> Unit
) {
    val spec = LocalAdaptive.current
    Box(
        modifier = Modifier
            .width(spec.railWidthDp.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (spec.isTv) 14.dp else 10.dp)
            ) {
                RailItem(
                    selected = section == AppSection.HOME,
                    primary = false,
                    icon = Icons.Default.Home,
                    labelRes = R.string.nav_home,
                    descriptionRes = R.string.cd_home,
                    onClick = onHome
                )
                RailItem(
                    selected = section == AppSection.QUICK_ADD,
                    primary = true,
                    icon = Icons.Default.Add,
                    labelRes = R.string.nav_add,
                    descriptionRes = R.string.cd_quick_add,
                    onClick = onQuickAdd
                )
                RailItem(
                    selected = section == AppSection.SETTINGS || section == AppSection.LOGS,
                    primary = false,
                    icon = Icons.Default.Settings,
                    labelRes = R.string.nav_settings,
                    descriptionRes = R.string.cd_settings,
                    onClick = onSettings
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    selected: Boolean,
    primary: Boolean,
    icon: ImageVector,
    labelRes: Int,
    descriptionRes: Int,
    onClick: () -> Unit
) {
    val spec = LocalAdaptive.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusRing {
            Box(
                modifier = Modifier
                    .size(if (spec.isTv) 64.dp else 56.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
                            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                            else -> Color.Transparent
                        }
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(descriptionRes),
                    tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(if (spec.isTv) 28.dp else 24.dp)
                )
            }
        }
        if (spec.isTv) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
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
