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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/** Kinescope Material 3 / Neural Expressive-inspired color, shape and type tokens. See design.md. */

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
    success = Color(0xFF56683F),
    // Darkened in patch 25 so status text clears 4.5:1 against the
    // light surfaceVariant used by queue rows.
    warning = Color(0xFF7A5A18),
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
    primary = Color(0xFFC74B12),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE4D5),
    onPrimaryContainer = Color(0xFF742B0C),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1E2329),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E2329),
    surfaceVariant = Color(0xFFE7E9ED),
    onSurfaceVariant = Color(0xFF565D65),
    outline = Color(0xFFB9C0C9),
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
    primary = Color(0xFFFFA276),
    onPrimary = Color(0xFF301508),
    primaryContainer = Color(0xFF67371F),
    onPrimaryContainer = Color(0xFFFFE4D5),
    background = Color(0xFF101317),
    onBackground = Color(0xFFF3F5F8),
    surface = Color(0xFF1B2026),
    onSurface = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFC3C9D2),
    outline = Color(0xFF67717E),
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

private val baseTypography = Typography()

// ROADMAP.md Step 6.3: every role the design system names now has an
// explicit override, not just the ones already in use elsewhere in
// the app -- `displaySmall` and `labelSmall` were the two gaps.
// `titleSmall` moves from SemiBold to Medium per the Step 6.3 table.
private val YtOfflineTypography = baseTypography.copy(
    displaySmall = baseTypography.displaySmall.copy(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = baseTypography.headlineSmall.copy(
        fontFamily = InterFontFamily,
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

// Patch 41: a TV is read from across the room, so every text style grows by the same factor (the layout
// spec decides it; 1 on phones and tablets). Sizes and line heights scale together.
private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
)

private fun Typography.scaled(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaled(factor),
    displayMedium = displayMedium.scaled(factor),
    displaySmall = displaySmall.scaled(factor),
    headlineLarge = headlineLarge.scaled(factor),
    headlineMedium = headlineMedium.scaled(factor),
    headlineSmall = headlineSmall.scaled(factor),
    titleLarge = titleLarge.scaled(factor),
    titleMedium = titleMedium.scaled(factor),
    titleSmall = titleSmall.scaled(factor),
    bodyLarge = bodyLarge.scaled(factor),
    bodyMedium = bodyMedium.scaled(factor),
    bodySmall = bodySmall.scaled(factor),
    labelLarge = labelLarge.scaled(factor),
    labelMedium = labelMedium.scaled(factor),
    labelSmall = labelSmall.scaled(factor)
)

@Composable
fun YtOfflineTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) DarkColors else LightColors
    val extendedColors = if (dark) DarkExtendedColors else LightExtendedColors
    val typographyScale = LocalAdaptive.current.typographyScale
    val typography = remember(typographyScale) {
        if (typographyScale == 1f) YtOfflineTypography else YtOfflineTypography.scaled(typographyScale)
    }
    CompositionLocalProvider(LocalYtOfflineExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = YtOfflineShapes,
            content = content
        )
    }
}