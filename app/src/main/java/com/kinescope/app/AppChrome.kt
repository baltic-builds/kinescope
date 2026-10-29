package com.kinescope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The app shell pieces around the screens: the frame (top bar plus bottom bar, or header plus navigation rail),
// the responsive content column and the navigation. Patch 40: moved out of
// MainActivity.kt unchanged. Patch 41: the frame and the rail are new; the rest adapts to LocalAdaptive.
// Patch 48: the bottom bar and the rail are one component (`NavEntry` + `NavButton` + `GlassStrip`) instead of two
// copies, the rail carries labels on every device, the bottom bar's width cap works, the header height is a token.
// Patch 52: five entries (Home, Queue, Add, Library, Settings); the bottom bar's buttons are 48dp so five fit a 320dp
// window; the Add entry no longer opens a screen, it downloads the link from the clipboard (MainActivity).

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
    onQueue: () -> Unit,
    onQuickAdd: () -> Unit,
    onLibrary: () -> Unit,
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
                    onQueue = onQueue,
                    onQuickAdd = onQuickAdd,
                    onLibrary = onLibrary,
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
                GlassNavRail(
                    section = section,
                    onHome = onHome,
                    onQueue = onQueue,
                    onQuickAdd = onQuickAdd,
                    onLibrary = onLibrary,
                    onSettings = onSettings
                )
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
                // Patch 50: a ring too, for the TV interface forced on a window too narrow for the rail.
                FocusRing {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            }
        },
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
            .heightIn(min = spec.headerHeightDp.dp),
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** One destination of the navigation: how it looks, whether it is the current one and what a tap does. */
private data class NavEntry(
    val selected: Boolean,
    val primary: Boolean,
    /** A painter, so an icon from the Material set and a drawable resource of the app are drawn the same way. */
    val icon: @Composable () -> Painter,
    val labelRes: Int,
    val descriptionRes: Int,
    val onClick: () -> Unit
)

/**
 * Home, Queue, Add (the accent button), Library and Settings, in this order; Logs counts as Settings. The only list of
 * destinations. Patch 51: the library left Home and became a destination. Patch 52: the queue left Home the same way and
 * is the second destination; Add is a button that starts the clipboard link, not a screen (its screen only opens when
 * the clipboard holds no link).
 */
private fun navEntries(
    section: AppSection,
    onHome: () -> Unit,
    onQueue: () -> Unit,
    onQuickAdd: () -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit
): List<NavEntry> = listOf(
    NavEntry(
        selected = section == AppSection.HOME,
        primary = false,
        icon = { rememberVectorPainter(Icons.Default.Home) },
        labelRes = R.string.nav_home,
        descriptionRes = R.string.cd_home,
        onClick = onHome
    ),
    NavEntry(
        selected = section == AppSection.QUEUE,
        primary = false,
        icon = { painterResource(R.drawable.ic_queue) },
        labelRes = R.string.nav_queue,
        descriptionRes = R.string.cd_queue,
        onClick = onQueue
    ),
    NavEntry(
        selected = section == AppSection.QUICK_ADD,
        primary = true,
        icon = { rememberVectorPainter(Icons.Default.Add) },
        labelRes = R.string.nav_add,
        descriptionRes = R.string.cd_quick_add,
        onClick = onQuickAdd
    ),
    NavEntry(
        selected = section == AppSection.LIBRARY,
        primary = false,
        icon = { rememberVectorPainter(Icons.Default.PlayArrow) },
        labelRes = R.string.nav_library,
        descriptionRes = R.string.cd_library,
        onClick = onLibrary
    ),
    NavEntry(
        selected = section == AppSection.SETTINGS || section == AppSection.LOGS,
        primary = false,
        icon = { rememberVectorPainter(Icons.Default.Settings) },
        labelRes = R.string.nav_settings,
        descriptionRes = R.string.cd_settings,
        onClick = onSettings
    )
)

/** The floating glass surface both navigation layouts stand on (border, shape, tint and elevation in one place). */
@Composable
private fun GlassStrip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.36f),
            shape = MaterialTheme.shapes.extraLarge
        ),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 5.dp,
        content = content
    )
}

/**
 * One navigation button: a round touch target ([size]: 56dp, 64dp on a TV rail, 48dp in the bottom bar) with the
 * icon ([iconSize]: 24dp, 28dp on a TV rail), and under it, in the rail, a label. The circle is the control (it
 * carries the name, the selected state and the focus ring); the label is drawn only, so a screen reader reads each
 * destination once. A remote has no long-press hint, so the rail shows the label everywhere, not only on TV.
 */
@Composable
private fun NavButton(entry: NavEntry, showLabel: Boolean, size: Dp, iconSize: Dp) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusRing {
            Box(
                modifier = Modifier
                    .size(size)
                    .then(if (entry.primary) Modifier.shadow(3.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(
                        when {
                            entry.primary -> colors.primary.copy(alpha = 0.96f)
                            entry.selected -> colors.primaryContainer.copy(alpha = 0.78f)
                            else -> Color.Transparent
                        }
                    )
                    .selectable(selected = entry.selected, role = Role.Tab, onClick = entry.onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = entry.icon(),
                    contentDescription = stringResource(entry.descriptionRes),
                    tint = if (entry.primary) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        if (showLabel) {
            Text(
                text = stringResource(entry.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.selected) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics { }
            )
        }
    }
}

/**
 * Patch 41: the bottom bar's counterpart for wide windows: the same floating glass strip, standing on the
 * left edge. Patch 48: every rail item has a label (before, only on TV); the column scrolls if a very short
 * window cannot show all of them (five since patch 52).
 */
@Composable
internal fun GlassNavRail(
    section: AppSection,
    onHome: () -> Unit,
    onQueue: () -> Unit,
    onQuickAdd: () -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit
) {
    val spec = LocalAdaptive.current
    Box(
        modifier = Modifier
            .width(spec.railWidthDp.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        GlassStrip(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (spec.isTv) 14.dp else 10.dp)
            ) {
                val buttonSize = if (spec.isTv) 64.dp else 56.dp
                val iconSize = if (spec.isTv) 28.dp else 24.dp
                navEntries(section, onHome, onQueue, onQuickAdd, onLibrary, onSettings)
                    .forEach { NavButton(it, showLabel = true, size = buttonSize, iconSize = iconSize) }
            }
        }
    }
}

@Composable
internal fun GlassBottomBar(
    section: AppSection,
    onHome: () -> Unit,
    onQueue: () -> Unit,
    onQuickAdd: () -> Unit,
    onLibrary: () -> Unit,
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
            // Patch 36: all actions share the same touch target and are distributed symmetrically; the bar stays
            // compact and caps its width. Patch 52: five entries at 48dp (the Material minimum) take 240dp plus the
            // 24dp of padding, so they fit the 280dp minimum of the strip and a 320dp-wide phone; five at 56dp
            // (280dp) would not, and a TV preview on a phone would have been wider still. Patch 48: the cap comes BEFORE
            // fillMaxWidth. `widthIn` only narrows what the parent allows, so after `fillMaxWidth` (which makes
            // the width exactly the parent's) it changed nothing and the bar spanned a 500dp window.
            GlassStrip(modifier = Modifier.widthIn(min = 280.dp, max = 380.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    navEntries(section, onHome, onQueue, onQuickAdd, onLibrary, onSettings)
                        .forEach { NavButton(it, showLabel = false, size = 48.dp, iconSize = 24.dp) }
                }
            }
        }
    }
}
