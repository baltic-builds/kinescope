# Kinescope visual design — October 2026

## Reference and scope
Google's Gemini **Neural Expressive** redesign was announced in May 2026, and is a design reference, not a published transferable component library or a mandate to copy the Gemini UI. Google Material 3/Material 3 Expressive is the implementable Android standard. This project uses a Kinescope-specific system instead of Google identity/assets.

Sources (checked October 25, 2026):
- https://blog.google/innovation-and-ai/technology/ai/google-io-2026-all-our-announcements/
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes

## Rules in this patch

1. **Hierarchy:** one meaningful home hero (VPN status, one tunnel switch), one automated-selection control, then the primary Watch YouTube action. No strategy picker on Home; expert controls last in Settings.
2. **Color:** bright but grounded Kinescope orange is reserved for checked switches, the action button and positive focus. The off track/thumb are neutral grays with clear outlines in both light and dark themes. Never encode status by color alone: show readable text.
3. **Shape and space:** larger 28dp hero corners, raised surface, 20dp horizontal / 22dp vertical interior spacing, comfortable touch target (>=48dp) and clear typography scale.
4. **Type:** Inter for readable UI and headlines; avoid faux branded serif/gemini fonts and keep the system fallback. Headlines have one dominant scale, supporting text uses title/body/label M3 roles.
5. **Adaptive:** window width, not device name, determines bottom navigation vs rail and single vs two-pane Settings. Preserve existing compact/medium/expanded breakpoints, fold/multitasking and TV D-pad focus rings; avoid fixed-height labels and allow larger fonts.
6. **Motion:** express state changes with intrinsic M3 control state changes, but avoid continuous distracting animations on network controls. Respect device animation preferences; no decorative light-show implying the VPN is healthy.
7. **Accessibility:** explicit switch semantics, labels and descriptive live status, contrast-aware on/off tokens, minimum targets, and actual RTL/large-font/device verification as a release gate.
8. **Bilingual:** UI strings in English and Russian; all newly modified labels exist in both resources. Manual selection is connection-class-specific.
9. **Safety:** turning the VPN off frees Kinescope's TUN immediately, followed by engine and bridge cleanup. Never imply control over another app's VPN, and never claim success from engine startup alone.

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

Custom success/warning colors remain in `YtOfflineExtras`. Use the correct `on*` tokens for text; checked switch colors are explicit in `KinescopeSwitch.kt`, not inferred from default M3 tokens. Contrast target: WCAG AA 4.5:1 for normal text; light orange `#C74B12` with white is approximately 4.73:1.

## Responsive specifications

- <600dp: compact, one column + bottom navigation, 20dp side padding.
- 600–839dp: navigation rail, usually one content pane.
- 840dp and above: rail, multi-pane only when 700dp of content width remains.
- Android TV: TV typography x1.15, 56dp minimum controls, 72dp header, configurable overscan 0–5%, accessible D-pad focus ring.
- Media controls wrap, scroll and use intrinsic min-height rather than fixed text boxes.

## Verification and acceptance criteria

- Both switch positions: visible orange `on` and neutral gray `off` in both themes, with clear textual labels; disabled remains distinguishable.
- Home displays status / tunnel / automatic mode / Watch YouTube; manual selection only at Settings bottom when auto is off for the present network.
- Verify screens at 320, 360, 600, 840, 1280dp, with 200% font scale, landscape, split-screen, screen reader, TV D-pad, English/Russian.
- Verify real-device Wi-Fi and cellular transitions and no-stuck-TUN behavior on STOP and revoke; integration cannot be certified by unit tests.
- No Gemini/Google branding, no proprietary visual assets, and no claim of reproducing confidential Google design specs.

## Window background consistency (v.3)

The Android window is visible before Compose draws and during transitions. Keep `kinescope_window_background` in `values/colors.xml` and `values-night/colors.xml` equal to `LightColors.background` and `DarkColors.background` in `Theme.kt` respectively; do not choose a separate splash color. `ThemeResourcesTest` enforces the match.
