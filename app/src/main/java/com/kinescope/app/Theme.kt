package com.kinescope.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.unit.dp

/**
 * Design tokens. See design.md for the reasoning and honesty caveats
 * (this is a best-effort approximation of the Claude app's look, not
 * an extraction of Anthropic's actual internal design spec — and it
 * deliberately avoids Anthropic's actual licensed fonts, Styrene and
 * a custom serif, in favor of free Google Fonts with a similar
 * character: Inter for UI/body text, Lora for the one serif
 * headline. Also deliberately avoids the Claude name/logo.)
 *
 * NOTE on risk: the downloadable-fonts setup below
 * (GoogleFont.Provider + font_certs.xml) is standard, widely
 * documented Android API, and font_certs.xml was fetched verbatim
 * from Google's own official sample repo rather than hand-typed —
 * see the comment at the top of that file. Still genuinely
 * untested here (no Android SDK in this sandbox). If it fails for
 * any reason, the downloadable-fonts API is designed to fail soft:
 * it falls back to the system default font rather than crashing, so
 * worst case this degrades to the previous system-font look, not a
 * broken build or a broken app.
 *
 * ROADMAP.md Step 6: adds a real dark theme (6.2), three light-mode
 * tokens Material3's baseline ColorScheme has no slot for --
 * `success`, `warning`, `surfaceRaised` (6.1) -- via a small
 * CompositionLocal-backed extension (see YtOfflineExtras below,
 * mirroring how `MaterialTheme.colorScheme` itself is accessed), and
 * fills in the rest of the typography scale (6.3).
 */

// ROADMAP.md Step 6.1/6.2: colors Material3's baseline ColorScheme
// has no slot for. Exposed via YtOfflineExtras (below) rather than as
// bare top-level vals, since there are now light *and* dark variants
// and call sites need whichever one matches the active theme -- the
// same problem `MaterialTheme.colorScheme` itself solves for the
// standard slots.
data class YtOfflineExtendedColors(
    val success: Color,
    val warning: Color,
    val surfaceRaised: Color
)

private val LightExtendedColors = YtOfflineExtendedColors(
    success = Color(0xFF788C5D),
    // Contrast note: ~3.2:1 as plain text against the light
    // background/surfaceVariant -- short status words read fine at
    // this size, but it's below WCAG AA's 4.5:1 body-text threshold.
    // Matches the hex ROADMAP.md Step 6.1 specifies as-is; worth a
    // look once this is on an actual screen (see design.md's
    // "compare against a real device" note).
    warning = Color(0xFFB8862E),
    surfaceRaised = Color(0xFFFFFFFF)
)

private val DarkExtendedColors = YtOfflineExtendedColors(
    success = Color(0xFF9AB07C),
    warning = Color(0xFFD6A24E),
    // One step lighter than surfaceVariant (#302E28), continuing dark
    // Material3's convention of a lighter tint (not a shadow) to
    // suggest elevation.
    surfaceRaised = Color(0xFF3A382E)
)

private val LocalYtOfflineExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** Access point for the extended colors, mirroring `MaterialTheme.colorScheme`. */
object YtOfflineExtras {
    val colors: YtOfflineExtendedColors
        @Composable get() = LocalYtOfflineExtendedColors.current
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DDD2),
    onPrimaryContainer = Color(0xFF6B3520),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFF0EEE6),
    onSurfaceVariant = Color(0xFF73726C),
    outline = Color(0xFFE3E1D9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    // ROADMAP.md Step 6.1: for error banners and empty-state
    // backgrounds, distinct from the existing per-row `error` text
    // color. onErrorContainer reuses Material3's own baseline pairing
    // for this exact errorContainer tone (#F9DEDC / #410E0B ships as
    // a verified-accessible M3 default pair, not a guess here).
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

// ROADMAP.md Step 6.2: dark scheme, previously missing entirely.
// Selected via isSystemInDarkTheme() in YtOfflineTheme() below --
// standard Material3 pattern, no new architectural risk. Token names
// here follow MaterialTheme's actual ColorScheme slot names
// (primary/onPrimary/...); design.md's dark-mode table calls this
// role "accent" in plain English since that's its conceptual job (the
// app's one accent color, same job `primary` already does in the
// light scheme) -- Compose Material3 has no separate "accent" slot.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE08D6D),
    onPrimary = Color(0xFF3D1A0E),
    primaryContainer = Color(0xFF5C3423),
    onPrimaryContainer = Color(0xFFF3DDD2),
    background = Color(0xFF1B1A17),
    onBackground = Color(0xFFF5F3EC),
    surface = Color(0xFF252420),
    onSurface = Color(0xFFF5F3EC),
    surfaceVariant = Color(0xFF302E28),
    onSurfaceVariant = Color(0xFFB8B6AC),
    outline = Color(0xFF3D3B34),
    // Material3-standard dark-scheme error red, with its standard
    // paired onError -- see ROADMAP.md Step 6.2's note on `error`.
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C0F0C),
    // Reuses the light scheme's errorContainer tone as light-on-dark
    // text here -- comfortably high contrast against #5C0F0C, and
    // echoes the same hue across both themes.
    onErrorContainer = Color(0xFFF9DEDC)
)

@OptIn(ExperimentalTextApi::class)
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

@OptIn(ExperimentalTextApi::class)
private val InterFontFamily = FontFamily(
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold)
)

@OptIn(ExperimentalTextApi::class)
private val LoraFontFamily = FontFamily(
    Font(googleFont = GoogleFont("Lora"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold)
)

private val baseTypography = Typography()

// ROADMAP.md Step 6.3: every role the design system names now has an
// explicit override, not just the ones already in use elsewhere in
// the app -- `displaySmall` and `labelSmall` were the two gaps.
// `titleSmall` moves from SemiBold to Medium per the Step 6.3 table.
private val YtOfflineTypography = baseTypography.copy(
    displaySmall = baseTypography.displaySmall.copy(
        fontFamily = LoraFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = baseTypography.headlineSmall.copy(
        fontFamily = LoraFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium)
)

private val YtOfflineShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun YtOfflineTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) DarkColors else LightColors
    val extendedColors = if (dark) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalYtOfflineExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YtOfflineTypography,
            shapes = YtOfflineShapes,
            content = content
        )
    }
}
