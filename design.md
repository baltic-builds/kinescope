# Kinescope visual design — October 2026

## Reference and scope
Google's Gemini **Neural Expressive** redesign was announced in May 2026, and is a design reference, not a published transferable component library or a mandate to copy the Gemini UI. Google Material 3/Material 3 Expressive is the implementable Android standard. This project uses a Kinescope-specific system instead of Google identity/assets.

Sources (checked October 25, 2026):
- https://blog.google/innovation-and-ai/technology/ai/google-io-2026-all-our-announcements/
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes

## Rules in this patch

1. **Hierarchy:** one meaningful home hero (VPN status, one tunnel switch, the heartbeat line), one automated-selection control, then the primary Watch YouTube action, then the hints panel. Patch 58: only while automatic selection is off, a compact drop-down on Home offers the current class's five best strategies; the full expert list remains last in Settings.
2. **Color:** bright but grounded Kinescope orange is reserved for checked switches, the action button and positive focus. The off track/thumb are neutral grays with clear outlines in both light and dark themes. Never encode status by color alone: show readable text.
3. **Shape and space:** larger 28dp hero corners, raised surface, 20dp horizontal / 22dp vertical interior spacing, comfortable touch target (>=48dp) and clear typography scale.
4. **Type:** Inter for readable UI and headlines; avoid faux branded serif/gemini fonts and keep the system fallback. Headlines have one dominant scale, supporting text uses title/body/label M3 roles.
5. **Adaptive:** window width, not device name, determines bottom navigation vs rail and single vs two-pane Settings. Preserve existing compact/medium/expanded breakpoints, fold/multitasking and TV D-pad focus rings; avoid fixed-height labels and allow larger fonts.
6. **Motion:** express state changes with intrinsic M3 control state changes, but avoid continuous distracting animations on network controls. Respect device animation preferences; no decorative light-show implying the VPN is healthy. **One exception (patch 55): the heartbeat line** under the tunnel switch is a continuous animation, allowed because it is tied to a MEASURED state, not to decoration: a green pulse travels along it only while the tunnel's traffic verdict says the bypass carries traffic (`DashPulse.ALIVE`), it turns red and flat when the connection is lost (`LOST`) and muted and flat when the bypass is off or starting (`IDLE`); it never starts because an engine merely started. With the system's animations off (animator duration scale 0) it holds still (one beat, drawn once). It has a text alternative for screen readers.
7. **Accessibility:** explicit switch semantics, labels and descriptive live status, contrast-aware on/off tokens, minimum targets, and actual RTL/large-font/device verification as a release gate.
8. **Bilingual:** UI strings in English and Russian; all newly modified labels exist in both resources. Manual selection is connection-class-specific.
9. **Safety:** turning the VPN off frees Kinescope's TUN immediately, followed by engine and bridge cleanup. Never imply control over another app's VPN, and never claim success from engine startup alone.
10. **Hints (patch 55):** one panel below the dashboard and the banners, semi-transparent (patch 56: panel 80% opaque with a hairline `outlineVariant` border, on `surfaceContainer`; text 88%; kept above 4.5:1 in both themes; it was 55% and borderless and read as missing on a tablet), no close button, a few words per line, each line about one thing the router does; the last says what `+` does. It is part of the screen, not a banner: it is not focusable on TV.
11. **Surfaces (patch 55):** the tonal layers (`surfaceContainerLowest` .. `Highest`, `surfaceDim`, `surfaceBright`, `outlineVariant`) are set explicitly so no Material3 baseline lavender shows in dialogs, menus, text fields or indicators; the third accent (`tertiary`, Google's blue) is the "glow" tone of the ON card's soft wash and the hints' bullets and is never an action colour (orange stays the only action colour).
12. **Dashboard actions and the groups (patch 56):** the refresh button is a round tonal touch target (48dp) in the card's top-right corner, an icon with a content description, greyed (not hidden) while it cannot run; the main button is a pill; the card's glow is present in both states (ON: the orange wash; off: a fainter pale-blue one) and never lowers text contrast below 4.5:1; the headline is `headlineMedium`. The strategy groups card is information, not a control (not focusable on TV): a title, then one tonal row per group with the connection the phone is on first and a "Now" pill. On a wide window (`twoPane`) Home is two panes, dashboard and banners left, groups and hints right, so nothing the screen explains sits below the fold.

## Design tokens (source of truth: `Theme.kt`)

| Role | Light | Dark |
| --- | --- | --- |
| Primary / checked switch | `#C74B12` | `#FFA276` |
| On-primary | `#FFFFFF` | `#301508` |
| Primary container | `#FFE4D5` | `#67371F` |
| On-primary container | `#742B0C` | `#FFE4D5` |
| Background | `#F8F9FB` | `#101317` |
| Surface | `#FFFFFF` | `#1B2026` |
| Surface variant / switch off | `#E7E9ED` | `#2A3038` |
| Foreground | `#1E2329` | `#F3F5F8` |
| Secondary text | `#565D65` | `#C3C9D2` |
| Outline | `#B9C0C9` | `#67717E` |
| Outline variant (patch 55) | `#D5DAE0` | `#3E4650` |
| Surface container lowest / low / mid / high / highest (patch 55) | `#FFFFFF` / `#F2F4F7` / `#ECEFF3` / `#E6E9EE` / `#E0E4E9` | `#0B0E11` / `#161B20` / `#1B2026` / `#232930` / `#2A3038` |
| Secondary container (a warm neutral) / on it | `#FBEBE2` / `#3D2A20` | `#4B3B33` / `#F2DFD3` |
| Tertiary (Google blue) / container | `#0B57D0` / `#D3E3FD` | `#A8C7FA` / `#0842A0` |
| Heartbeat alive / lost (`YtOfflineExtras`) | `#1E8E3E` / `#D93025` | `#81C995` / `#F28B82` |

Custom success/warning colors and the heartbeat colors remain in `YtOfflineExtras`. Use the correct `on*` tokens for text; checked switch colors are explicit in `KinescopeSwitch.kt`, not inferred from default M3 tokens. Contrast target: WCAG AA 4.5:1 for normal text; light orange `#C74B12` with white is approximately 4.73:1.

## Responsive specifications

- <600dp: compact, one column + bottom navigation, 20dp side padding.
- 600–839dp: navigation rail, usually one content pane.
- 840dp and above: rail, multi-pane only when 700dp of content width remains.
- Android TV: TV typography x1.15, 56dp minimum controls, 72dp header, configurable overscan 0–5%, accessible D-pad focus ring.
- Media controls wrap, scroll and use intrinsic min-height rather than fixed text boxes.

## Verification and acceptance criteria

- Both switch positions: visible orange `on` and neutral gray `off` in both themes, with clear textual labels; disabled remains distinguishable.
- Home displays status / tunnel / automatic mode / Watch YouTube; when auto is off for the present network, Home shows its five-entry manual menu (colour names and ratings, no stars). The full manual list remains at Settings bottom.
- Verify screens at 320, 360, 600, 840, 1280dp, with 200% font scale, landscape, split-screen, screen reader, TV D-pad, English/Russian.
- Verify real-device Wi-Fi and cellular transitions and no-stuck-TUN behavior on STOP and revoke; integration cannot be certified by unit tests.
- No Gemini/Google branding, no proprietary visual assets, and no claim of reproducing confidential Google design specs.
- Patch 56 checks: the refresh button in light and dark, enabled, disabled and while its check runs; the groups card with one group not yet checked; the two-pane Home on a tablet in landscape and one column in portrait; the hints panel visible at 200% font and in Russian.
- Patch 55 checks: the heartbeat in all three states and with animations off; the hints panel at 200% font, in Russian and in dark; no lavender in dialogs, menus, text fields; the ON card's wash does not lower the contrast of its text below 4.5:1.

## Window background consistency (v.3)

The Android window is visible before Compose draws and during transitions. Keep `kinescope_window_background` in `values/colors.xml` and `values-night/colors.xml` equal to `LightColors.background` and `DarkColors.background` in `Theme.kt` respectively; do not choose a separate splash color. `ThemeResourcesTest` enforces the match.
