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
    val surfaceRaised: Color,
    // Patch 55: the heartbeat line of the Home dashboard (`HeartbeatLine`): green while the bypass carries traffic, red when
    // the connection is lost. Google's green and red tones, so the two read at a glance and clear 3:1 on the card.
    val pulseAlive: Color,
    val pulseLost: Color
)

private val LightExtendedColors = YtOfflineExtendedColors(
    success = Color(0xFF56683F),
    // Darkened in patch 25 so status text clears 4.5:1 against the
    // light surfaceVariant used by queue rows.
    warning = Color(0xFF7A5A18),
    surfaceRaised = Color(0xFFFFFFFF),
    pulseAlive = Color(0xFF1E8E3E),
    pulseLost = Color(0xFFD93025)
)

private val DarkExtendedColors = YtOfflineExtendedColors(
    success = Color(0xFF9AB07C),
    warning = Color(0xFFD6A24E),
    // One step lighter than surfaceVariant (#302E28), continuing dark
    // Material3's convention of a lighter tint (not a shadow) to
    // suggest elevation.
    surfaceRaised = Color(0xFF3A382E),
    pulseAlive = Color(0xFF81C995),
    pulseLost = Color(0xFFF28B82)
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
    // Patch 55: the tonal layers and the second and third accents are set here instead of left to Material3's baseline, whose
    // lavender tints showed in dialogs, menus, text fields and the navigation indicator next to the orange and the cool grays.
    // The layers step from white to a light gray; the second accent is a warm neutral, the third Google's blue (the
    // "Neural Expressive" glow tone, used for the dashboard's aura and the hints, never for an action).
    outlineVariant = Color(0xFFD5DAE0),
    surfaceDim = Color(0xFFD8DCE1),
    surfaceBright = Color(0xFFF8F9FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEFF3),
    surfaceContainerHigh = Color(0xFFE6E9EE),
    surfaceContainerHighest = Color(0xFFE0E4E9),
    inverseSurface = Color(0xFF2D3238),
    inverseOnSurface = Color(0xFFF1F3F6),
    inversePrimary = Color(0xFFFFA276),
    secondary = Color(0xFF6B5A4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBEBE2),
    onSecondaryContainer = Color(0xFF3D2A20),
    tertiary = Color(0xFF0B57D0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E3FD),
    onTertiaryContainer = Color(0xFF041E49),
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
    // Patch 55: see the light scheme. The layers climb from the background to a mid gray (a lighter tone, not a shadow, is how
    // dark Material3 shows height).
    outlineVariant = Color(0xFF3E4650),
    surfaceDim = Color(0xFF101317),
    surfaceBright = Color(0xFF343B44),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF161B20),
    surfaceContainer = Color(0xFF1B2026),
    surfaceContainerHigh = Color(0xFF232930),
    surfaceContainerHighest = Color(0xFF2A3038),
    inverseSurface = Color(0xFFE3E6EA),
    inverseOnSurface = Color(0xFF2B3036),
    inversePrimary = Color(0xFFC74B12),
    secondary = Color(0xFFD7C3B8),
    onSecondary = Color(0xFF3B2A20),
    secondaryContainer = Color(0xFF4B3B33),
    onSecondaryContainer = Color(0xFFF2DFD3),
    tertiary = Color(0xFFA8C7FA),
    onTertiary = Color(0xFF062E6F),
    tertiaryContainer = Color(0xFF0842A0),
    onTertiaryContainer = Color(0xFFD3E3FD),
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
    // Patch 56: the dashboard headline is one size up (Gemini's large, calm headings); the same family and weight.
    headlineMedium = baseTypography.headlineMedium.copy(
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