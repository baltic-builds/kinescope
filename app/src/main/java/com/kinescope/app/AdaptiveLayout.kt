package com.kinescope.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Patch 41: one layout model for phones, tablets and TVs.
//
// The window is classified once (`adaptiveSpecFor`, plain Kotlin, unit-tested) and every screen reads the
// result from `LocalAdaptive` instead of measuring things itself. Breakpoints are Material 3's window size
// classes (compact < 600dp, medium < 840dp, expanded from 840dp); they are computed from the window's own
// dp size, so split screen, freeform windows and folding screens follow the window, not the display.
//
// Patch 48 (audit of the tablet/TV UI): the rail now needs a medium-or-wider window (a narrow window that is also
// short keeps the bottom bar: a 240dp-wide rail layout left no room for a button), the TV overscan margin is a
// setting (0-5% of the window instead of a fixed 48x27dp) and the control and header heights are tokens that
// follow the interface (touch 48/64dp, TV 56/72dp), so a fixed height no longer clips a label that wraps.

/**
 * Which interface Kinescope draws. [AUTO] decides from the device; [TV] and [TOUCH] are the user's override
 * (Settings -> Interface), for set-top boxes that do not declare themselves as TVs and for previewing the TV
 * layout on a phone.
 */
internal enum class InterfaceMode(val persistedValue: String) {
    AUTO("auto"),
    TV("tv"),
    TOUCH("touch");

    companion object {
        fun fromPersisted(value: String?): InterfaceMode = entries.firstOrNull { it.persistedValue == value } ?: AUTO
    }
}

/**
 * The result of classifying a window. Every size is a plain `Int` in dp so the rules can be unit-tested on the
 * JVM without any Compose type.
 *
 * - [useRail]: a navigation rail on the left instead of the floating bottom bar (medium and expanded windows and
 *   every TV; never a compact-width window, however short).
 * - [twoPane]: Home and Settings show two lists side by side.
 * - [safeHorizontalDp] / [safeVerticalDp]: TV overscan margins, the chosen percentage of the window (5% of a
 *   960x540dp screen is 48x27dp).
 * - [controlHeightDp] / [headerHeightDp]: the minimum height of a button the layout sizes itself and of the
 *   screen header. They are minimums: a label that wraps makes the control taller instead of being clipped.
 */
internal data class AdaptiveSpec(
    val isTv: Boolean,
    val useRail: Boolean,
    val twoPane: Boolean,
    val contentMaxWidthDp: Int,
    val contentStartPaddingDp: Int,
    val contentEndPaddingDp: Int,
    val safeHorizontalDp: Int,
    val safeVerticalDp: Int,
    val railWidthDp: Int,
    val railGapDp: Int,
    val paneGapDp: Int,
    val typographyScale: Float,
    val controlHeightDp: Int,
    val headerHeightDp: Int
)

private const val MEDIUM_MIN_WIDTH_DP = 600
private const val EXPANDED_MIN_WIDTH_DP = 840
private const val TWO_PANE_MIN_CONTENT_DP = 700
private const val TV_TYPOGRAPHY_SCALE = 1.15f
private const val TOUCH_CONTROL_HEIGHT_DP = 48
private const val TV_CONTROL_HEIGHT_DP = 56
private const val TOUCH_HEADER_HEIGHT_DP = 64
private const val TV_HEADER_HEIGHT_DP = 72

/** Settings -> Interface offers 0..5% of the window as the TV edge margin; 5% is what the layout always used. */
internal const val OVERSCAN_MAX_PERCENT = 5
internal const val DEFAULT_OVERSCAN_PERCENT = 5

/** A margin of [percent] of [sizeDp], rounded to whole dp (5% of 960 is 48, of 540 is 27). */
private fun marginDp(sizeDp: Int, percent: Int): Int = (sizeDp * percent + 50) / 100

/**
 * Classifies a window of [widthDp] x [heightDp]. A phone in portrait keeps exactly the layout it had before
 * patch 41 (bottom bar, 20dp margins, 640dp cap); every medium or wider window and every TV gets the rail.
 * [overscanPercent] is the TV edge margin (0..[OVERSCAN_MAX_PERCENT]); it changes nothing off TV.
 */
internal fun adaptiveSpecFor(
    widthDp: Int,
    heightDp: Int,
    tv: Boolean,
    overscanPercent: Int = DEFAULT_OVERSCAN_PERCENT
): AdaptiveSpec {
    val medium = widthDp >= MEDIUM_MIN_WIDTH_DP
    val expanded = widthDp >= EXPANDED_MIN_WIDTH_DP
    val useRail = tv || medium
    if (!useRail) {
        return AdaptiveSpec(
            isTv = false,
            useRail = false,
            twoPane = false,
            contentMaxWidthDp = 640,
            contentStartPaddingDp = 20,
            contentEndPaddingDp = 20,
            safeHorizontalDp = 0,
            safeVerticalDp = 0,
            railWidthDp = 0,
            railGapDp = 0,
            paneGapDp = 0,
            typographyScale = 1f,
            controlHeightDp = TOUCH_CONTROL_HEIGHT_DP,
            headerHeightDp = TOUCH_HEADER_HEIGHT_DP
        )
    }
    val overscan = overscanPercent.coerceIn(0, OVERSCAN_MAX_PERCENT)
    val safeHorizontal = if (tv) marginDp(widthDp, overscan) else 0
    val safeVertical = if (tv) marginDp(heightDp, overscan) else 0
    val railWidth = if (tv) 96 else 80
    val railGap = if (tv) 24 else 16
    val endPadding = when {
        tv -> 0
        expanded -> 32
        else -> 24
    }
    val maxWidth = when {
        tv -> 1280
        expanded -> 1200
        else -> 720
    }
    val available = widthDp - 2 * safeHorizontal - railWidth - railGap - endPadding
    return AdaptiveSpec(
        isTv = tv,
        useRail = true,
        twoPane = minOf(available, maxWidth) >= TWO_PANE_MIN_CONTENT_DP,
        contentMaxWidthDp = maxWidth,
        contentStartPaddingDp = 0,
        contentEndPaddingDp = endPadding,
        safeHorizontalDp = safeHorizontal,
        safeVerticalDp = safeVertical,
        railWidthDp = railWidth,
        railGapDp = railGap,
        paneGapDp = if (tv) 32 else 24,
        typographyScale = if (tv) TV_TYPOGRAPHY_SCALE else 1f,
        controlHeightDp = if (tv) TV_CONTROL_HEIGHT_DP else TOUCH_CONTROL_HEIGHT_DP,
        headerHeightDp = if (tv) TV_HEADER_HEIGHT_DP else TOUCH_HEADER_HEIGHT_DP
    )
}

/** The user's override wins; [autoDetected] only matters for [InterfaceMode.AUTO]. */
internal fun resolveTelevision(mode: InterfaceMode, autoDetected: Boolean): Boolean = when (mode) {
    InterfaceMode.TV -> true
    InterfaceMode.TOUCH -> false
    InterfaceMode.AUTO -> autoDetected
}

/**
 * The rule behind [isTelevisionDevice], on plain booleans so it can be tested. A device that reports the TV UI mode
 * or the leanback feature is a TV. One that declares no touch screen is taken for a set-top box, except a PC-type
 * device (a Chromebook or a desktop-mode tablet without touch has a keyboard and a pointer, not a remote).
 */
internal fun looksLikeTelevision(tvUiMode: Boolean, leanback: Boolean, touchscreen: Boolean, pc: Boolean): Boolean =
    tvUiMode || leanback || (!touchscreen && !pc)

/**
 * True on Android TV / Google TV (the TV UI mode or the leanback feature) and on set-top boxes that declare no
 * touch screen at all. A box that declares neither is handled by the Settings override.
 */
internal fun isTelevisionDevice(context: Context): Boolean {
    val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val packageManager = context.packageManager
    return looksLikeTelevision(
        tvUiMode = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION,
        leanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        touchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        pc = packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
    )
}

internal val LocalAdaptive = staticCompositionLocalOf { adaptiveSpecFor(widthDp = 360, heightDp = 800, tv = false) }

/** Provides [LocalAdaptive] for the current window; it must sit above the theme (the theme scales text on TV). */
@Composable
internal fun AdaptiveRoot(mode: InterfaceMode, overscanPercent: Int, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val detected = remember(context) { isTelevisionDevice(context) }
    val tv = resolveTelevision(mode, detected)
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val spec = remember(widthDp, heightDp, tv, overscanPercent) {
        adaptiveSpecFor(widthDp, heightDp, tv, overscanPercent)
    }
    CompositionLocalProvider(LocalAdaptive provides spec) { content() }
}

private val FocusRingWidth = 3.dp

/**
 * TV focus indicator. D-pad users need to see which control is selected from across the room, and
 * Material 3's state layer on focus is too faint for that, so on TV the wrapped control gets a 3dp ring in
 * the text color (it contrasts with both the cream surface and the terracotta accent).
 *
 * The ring space is reserved all the time on TV (a transparent border while unfocused), so focus never moves
 * the layout. Off TV this is a plain `Box` and changes nothing. `isFocused` is true only when the wrapped
 * control itself has focus: a row that contains its own buttons keeps its ring while the row is selected and
 * hands it to a button once that button is.
 *
 * Put layout modifiers that belong to the control (`weight`, `fillMaxWidth`) on this wrapper's [modifier].
 */
@Composable
internal fun FocusRing(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit
) {
    val tv = LocalAdaptive.current.isTv
    var focused by remember { mutableStateOf(false) }
    val ringColor = if (tv && focused) MaterialTheme.colorScheme.onBackground else Color.Transparent
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (tv) Modifier.border(FocusRingWidth, ringColor, shape).padding(FocusRingWidth) else Modifier)
    ) {
        content()
    }
}
