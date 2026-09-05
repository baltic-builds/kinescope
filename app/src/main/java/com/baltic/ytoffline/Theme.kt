package com.baltic.ytoffline

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 */

// Not a standard Material3 ColorScheme slot — used directly at call
// sites for the queue list's "Done" status text.
val SuccessGreen = Color(0xFF788C5D)

private val YtOfflineColorScheme = lightColorScheme(
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
    onError = Color(0xFFFFFFFF)
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

private val YtOfflineTypography = baseTypography.copy(
    headlineSmall = baseTypography.headlineSmall.copy(
        fontFamily = LoraFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium)
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
    MaterialTheme(
        colorScheme = YtOfflineColorScheme,
        typography = YtOfflineTypography,
        shapes = YtOfflineShapes,
        content = content
    )
}
