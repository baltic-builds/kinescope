#!/usr/bin/env python3
"""
Patch 03 — ROADMAP.md Step 6.1, 6.2, 6.3, 6.6 (design system v2:
tokens, dark theme, typography, icon safe-zone fix).

Run this from the ROOT of the yt-offline repo, AFTER patches 01 and
02 have already been applied and committed.

What this does:

6.1 [light tokens] Adds three tokens Material3's baseline ColorScheme
    has no slot for -- `surfaceRaised`, `warning`, `errorContainer` --
    via a small CompositionLocal-backed `YtOfflineExtras` object that
    mirrors how `MaterialTheme.colorScheme` itself is accessed
    (needed because, after 6.2, there are now light *and* dark
    variants of these, so a bare top-level `val` like the old
    `SuccessGreen` no longer works -- call sites need whichever one
    matches the active theme).

6.2 [dark theme] Adds a real `darkColorScheme(...)`, selected via
    `isSystemInDarkTheme()` -- previously the app only had a light
    scheme, full stop.

6.3 [typography] Fills in the two roles the existing type scale was
    missing an explicit override for (`displaySmall`, `labelSmall`),
    and updates `titleSmall` from SemiBold to Medium per the Step 6.3
    spec table (tightens what was already there, not a fresh design).

6.6 [icon safe-zone] Narrows `ic_launcher_foreground.xml`'s tray
    shape from `34...74` to `40...68` so its bottom corners land
    inside the adaptive-icon safe-zone circle instead of being
    silently clipped on circular-mask launchers/OEM skins.

Also does the one mechanical follow-on change needed to keep
`MainActivity.kt` compiling against the new Theme.kt API: the single
`SuccessGreen` reference becomes `YtOfflineExtras.colors.success`.

Updates ROADMAP.md's checkboxes for every 6.1/6.2/6.3/6.6 item and the
matching Appendix row (#15, the icon safe-zone finding).

Deliberately NOT done in this patch (coming in patch 04, which is
substantially bigger):
  - Step 6.5's component patterns: queue-row thumbnail placeholders,
    the accent progress bar, the empty-queue icon state, Library's
    delete/share overflow menu (a genuinely new feature, not just
    theming), Settings' sectioned layout, the connectivity-loss
    dismissible banner, and the composer bar's focus border.
  - Step 6.7 (optional monochrome adaptive-icon layer).
  - Step 7 (Kinescope rename) and Step 5 (device testing) -- you've
    asked for Step 6 to finish first; those come after.

Safe to re-run: every edit is guarded by an exact-match check.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


class PatchError(RuntimeError):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise PatchError(f"Expected file not found: {path}\n"
                          f"Are you running this from the repo root?")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = read(path)
    count = text.count(old)
    if count == 0:
        if new in text:
            print(f"  [skip] {label}: already applied, leaving as-is.")
            return
        raise PatchError(
            f"{label}: expected text not found in {path}.\n"
            f"The file may have changed since this patch was written.\n"
            f"--- expected snippet ---\n{old}\n------------------------"
        )
    if count > 1:
        raise PatchError(
            f"{label}: expected text found {count} times in {path}, "
            f"need exactly 1. Refusing to guess which one to replace."
        )
    write(path, text.replace(old, new, 1))
    print(f"  [ok] {label}")


def overwrite(path: pathlib.Path, content: str, label: str) -> None:
    write(path, content)
    print(f"  [ok] {label} (full file rewrite)")


def require_prior_patches() -> None:
    ds = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadService.kt"
    text = read(ds)
    if "jobIdTag" not in text:
        raise PatchError(
            "DownloadService.kt doesn't have patch 02's fixes yet "
            "(no 'jobIdTag' found). Run patch_01_steps_1_2_3.py and "
            "patch_02_step_4.py first, in order."
        )


# ---------------------------------------------------------------------------
# Theme.kt — full rewrite (dark scheme, light additions, typography)
# ---------------------------------------------------------------------------

NEW_THEME_KT = '''package com.baltic.ytoffline

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
'''


def fix_theme() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "Theme.kt"
    current = read(path)
    if "YtOfflineExtras" in current:
        print("  [skip] Theme.kt: already patched.")
        return
    overwrite(path, NEW_THEME_KT, "Theme.kt (dark scheme, surfaceRaised/warning/errorContainer, full type scale)")


# ---------------------------------------------------------------------------
# MainActivity.kt — mechanical follow-on: SuccessGreen -> YtOfflineExtras
# ---------------------------------------------------------------------------

def fix_main_activity() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MainActivity.kt"
    replace_once(
        path,
        "                                        JobState.DONE -> SuccessGreen\n",
        "                                        JobState.DONE -> YtOfflineExtras.colors.success\n",
        "MainActivity.kt: SuccessGreen -> YtOfflineExtras.colors.success",
    )


# ---------------------------------------------------------------------------
# ic_launcher_foreground.xml — Step 6.6 safe-zone fix
# ---------------------------------------------------------------------------

def fix_icon_safe_zone() -> None:
    path = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
    old = (
        '    <!-- Tray / base line -->\n'
        '    <path\n'
        '        android:fillColor="#FAF9F5"\n'
        '        android:pathData="M34,79 L74,79 L74,87 L34,87 Z" />\n'
    )
    new = (
        '    <!-- Tray / base line -->\n'
        '    <!-- ROADMAP.md Step 6.6 [LOW, fixed]: narrowed from 34/74 to\n'
        '         40/68 so the bottom corners land inside the adaptive-icon\n'
        '         safe-zone circle (33dp radius from center) -- the wider\n'
        '         version was getting silently clipped on circular-mask\n'
        '         launchers/OEM skins. -->\n'
        '    <path\n'
        '        android:fillColor="#FAF9F5"\n'
        '        android:pathData="M40,79 L68,79 L68,87 L40,87 Z" />\n'
    )
    replace_once(path, old, new, "ic_launcher_foreground.xml: tray safe-zone fix (Step 6.6)")


# ---------------------------------------------------------------------------
# ROADMAP.md bookkeeping
# ---------------------------------------------------------------------------

def update_roadmap() -> None:
    path = ROOT / "ROADMAP.md"

    checkbox_fixes = [
        (
            "- [ ] Add `surfaceRaised` — white surface + soft shadow (`alpha 0.04`",
            "- [x] Add `surfaceRaised` — white surface + soft shadow (`alpha 0.04`",
        ),
        (
            "- [ ] Add `warning` (`#B8862E`) — new middle state between success/error,",
            "- [x] Add `warning` (`#B8862E`) — new middle state between success/error,",
        ),
        (
            "- [ ] Add `errorContainer` (`#F9DEDC`) — for error banners and empty-state",
            "- [x] Add `errorContainer` (`#F9DEDC`) — for error banners and empty-state",
        ),
        (
            "- [ ] Implement a `darkColorScheme(...)` alongside the existing",
            "- [x] Implement a `darkColorScheme(...)` alongside the existing",
        ),
        (
            "- [ ] `displaySmall` — Lora SemiBold — app title, true empty/first-run state only",
            "- [x] `displaySmall` — Lora SemiBold — app title, true empty/first-run state only "
            "(Fixed — patch 03)",
        ),
        (
            "- [ ] `headlineSmall` — Lora SemiBold — screen-level headers, for if more screens are added",
            "- [x] `headlineSmall` — Lora SemiBold — screen-level headers, for if more screens are added "
            "(Fixed — patch 03)",
        ),
        (
            '- [ ] `titleMedium` — Inter SemiBold — section headers ("Queue", "Library")',
            '- [x] `titleMedium` — Inter SemiBold — section headers ("Queue", "Library") (Fixed — patch 03)',
        ),
        (
            "- [ ] `titleSmall` — Inter Medium — row titles (video name)",
            "- [x] `titleSmall` — Inter Medium — row titles (video name) (Fixed — patch 03)",
        ),
        (
            "- [ ] `bodyMedium` — Inter Regular — status lines, settings descriptions",
            "- [x] `bodyMedium` — Inter Regular — status lines, settings descriptions (Fixed — patch 03)",
        ),
        (
            "- [ ] `labelLarge` — Inter Medium — button text",
            "- [x] `labelLarge` — Inter Medium — button text (Fixed — patch 03)",
        ),
        (
            "- [ ] `labelSmall` — Inter Medium — chips, timestamps, byte counts",
            "- [x] `labelSmall` — Inter Medium — chips, timestamps, byte counts (Fixed — patch 03)",
        ),
        (
            "- [ ] **[LOW] Fix `ic_launcher_foreground.xml`'s tray shape clipping",
            "- [x] **[LOW, fixed — patch 03] Fix `ic_launcher_foreground.xml`'s tray shape clipping",
        ),
    ]

    for old, new in checkbox_fixes:
        replace_once(path, old, new, f"ROADMAP.md checkbox: {old[:60]}...")

    appendix_fixes = [
        (
            "| 15 | Low | Adaptive icon tray clips outside safe zone on circular masks | `ic_launcher_foreground.xml` | Open — Step 6.6 |",
            "| 15 | Low | Adaptive icon tray clips outside safe zone on circular masks | `ic_launcher_foreground.xml` | ✅ Fixed — patch 03 |",
        ),
    ]

    for old, new in appendix_fixes:
        replace_once(path, old, new, f"ROADMAP.md appendix row: {old[:60]}...")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 03 (ROADMAP.md Step 6.1, 6.2, 6.3, 6.6)...\n")
    try:
        require_prior_patches()

        print("Kotlin/XML source changes:")
        fix_theme()
        fix_main_activity()
        fix_icon_safe_zone()

        print("\nROADMAP.md bookkeeping:")
        update_roadmap()
    except PatchError as exc:
        print(f"\nPATCH FAILED: {exc}", file=sys.stderr)
        return 1

    print("\nAll changes applied successfully.")
    print("Next: ./gradlew assembleDebug  (fix any remaining compile errors top-down)")
    print("Step 6.5 (component patterns: thumbnails, progress bar, delete/share, settings")
    print("sections, error banner) is a bigger patch and comes next.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
