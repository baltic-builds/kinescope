# design.md — Visual design system

## Honesty check, up front

This is a **best-effort visual approximation** of the Claude Android
app's aesthetic, not an extraction of Anthropic's real internal
design spec. Claude (me, writing this) doesn't have access to
Anthropic's actual design-token files — what follows is built from:

- General familiarity with the public look of Claude's apps (warm,
  calm, terracotta accent on a cream background, minimal chrome).
- A handful of independent third-party "brand color scraper" sites
  that reverse-engineer colors from Anthropic's public web assets.
  Several of them converge on the same values, which is why the
  numbers below are the ones used — but "several unofficial sites
  agree" is not the same as "this is Anthropic's real spec."

Two things follow from that:

1. **Compare against the real app once it's on your phone** and
   nudge hex values if something looks off. Treat everything below as
   a strong starting point, not gospel.
2. **Fonts are not copied.** Anthropic's actual brand typefaces
   (Styrene for UI text, a custom serif for display text) are
   commercial, licensed fonts — bundling them into a personal app
   without a license isn't something to do. Kinescope instead requests
   the independently licensed Google Fonts Inter (UI/body) and Lora
   (display/headline) through Android's downloadable-font provider,
   with system-font fallback if the provider is unavailable.

**Also: don't use the name "Claude" or Anthropic's logo anywhere in
this app.** Borrowing a similar visual *feel* for your own,
unaffiliated personal project is fine; borrowing the actual brand
name/mark would incorrectly suggest Anthropic made or endorses this
app. The app is named **Kinescope** -- a name with no connection to Anthropic or Claude.

## Design principles

- **Warm, not clinical.** Cream/parchment backgrounds instead of
  stark white or cold gray — the thing Claude's brand is most visibly
  known for versus other AI products' blues and grays.
- **One accent color, used sparingly.** Terracotta orange for
  primary actions and selected states only. Everything else stays
  near-neutral so the accent actually reads as an accent.
- **Calm typography.** A serif for the one or two moments that want
  a bit of character (the app title), clean sans for everything
  read in a hurry (buttons, lists, status text).
- **Soft geometry.** Generously rounded corners, no hard edges, no
  heavy drop shadows — flat, calm surfaces separated by color and
  spacing rather than borders.

## Color tokens

| Token | Hex | Used for |
|---|---|---|
| `background` | `#FAF9F5` | Screen background (warm cream, not white) |
| `surface` | `#FFFFFF` | Cards/sheets that sit above the background |
| `surfaceVariant` | `#F0EEE6` | Queue/library row backgrounds, unselected chips |
| `onBackground` / `onSurface` | `#141413` | Primary text (warm near-black, not pure black) |
| `onSurfaceVariant` | `#73726C` | Secondary/meta text (status lines, captions) |
| `outline` | `#E3E1D9` | Dividers, subtle borders |
| `primary` | `#D97757` | Buttons, selected chip, links, the app's one accent |
| `onPrimary` | `#3D1A0E` | Text/icons on top of `primary`; warm-dark value keeps contrast above the body-text target on terracotta |
| `primaryContainer` | `#F3DDD2` | Selected chip background (a light tint of `primary`) |
| `onPrimaryContainer` | `#6B3520` | Text on `primaryContainer` |
| `success` (custom, not a Material3 slot) | `#56683F` | "Done" status text in the queue list; darkened in patch 25 for stronger light-theme contrast |
| `warning` (custom, not a Material3 slot) | `#7A5A18` | Paused / interrupted recoverable state text with stronger light-theme contrast |
| `error` | `#BA1A1A` | Failed status text. Kept as a standard, unambiguous red rather than a brand-adjacent tone — a failure needs to read as a failure at a glance more than it needs to be on-brand. |

A dark theme was added later (patch 03); `Theme.kt` is the source of truth for its exact tokens. The light tokens above remain the visual baseline.

## Typography

| Role | Family | Weight | Used for |
|---|---|---|---|
| `displaySmall` / `headlineSmall` | Lora via Google Fonts provider | SemiBold | Character/display moments including the Kinescope title |
| `titleMedium` | Inter via Google Fonts provider | SemiBold | Section headers |
| `titleSmall` / `labelLarge` / `labelSmall` | Inter via Google Fonts provider | Medium | Row titles, actions and compact labels |
| body roles | Inter via Google Fonts provider | Regular | Body text, list rows, status/meta text |

The font files are not bundled in the APK. Android requests Inter and Lora through Google Play Services' downloadable-font provider; the API falls back to a system font when the provider cannot supply them. This replaced the original system-serif/system-sans prototype and is already implemented in `Theme.kt`.

## Shape scale

| Token | Radius | Used for |
|---|---|---|
| `extraSmall` | 6dp | Small elements |
| `small` | 10dp | Text field, chips |
| `medium` | 14dp | Queue/library row cards |
| `large` | 20dp | Larger containers |
| `extraLarge` | 28dp | Reserved for anything closer to full-bleed/pill shapes |

## Where this lands in the existing screen

- **App title** ("Kinescope") → `headlineSmall` (serif).
- **Section headers** ("Queue", "Library", settings labels) →
  `titleMedium`/`titleSmall` (sans, semibold).
- **Quality chips** → already `FilterChip` from Phase 2; theming
  alone (via `MaterialTheme.colorScheme`) makes the selected chip
  pick up `primaryContainer`/`onPrimaryContainer` and unselected
  chips pick up `surfaceVariant` — no chip-specific code changes
  needed beyond applying the theme.
- **Queue rows / library rows** → wrapped in a `Surface` using
  `shapes.medium` + `colorScheme.surfaceVariant`, turning plain text
  rows into soft rounded cards. "Done" status uses the custom
  `success` green; "Failed" uses `error` red.
- **Primary button** ("Add to download queue") → already
  `Button()`; theming alone gives it the terracotta `primary` fill.
- **App icon** → patch 24 replaces the original download-arrow mark with an original retro television: warm cream/pale background, terracotta shell, raised cream glass and warm-dark controls. It is intentionally only *inspired by* pre-flat skeuomorphic mobile TV icons, not a copy of YouTube artwork. Android 13+ also gets a monochrome adaptive layer.

## Implementation sprint

Concrete, in order — see the actual code for the first six, already
applied in this pass:

1. `Theme.kt` — color tokens, typography, shape scale, and a
   `YtOfflineTheme(content)` composable wrapping Material3's
   `MaterialTheme`.
2. `MainActivity.kt` — swap the bare `MaterialTheme { ... }` for
   `YtOfflineTheme { ... }`.
3. Queue and library rows restyled as rounded `surfaceVariant` cards;
   "Done"/"Failed" status text colored via the new `success`/`error`
   tokens.
4. Adaptive launcher icon (`ic_launcher_background.xml`,
   `ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml` +
   `ic_launcher_round.xml`), referenced from `AndroidManifest.xml`.
   No legacy raster mipmaps needed — `minSdk` is already 29, well
   above the API 26 adaptive-icon floor.
5. Build and actually look at it on a device — spot-check contrast
   (dark text on cream, warm-dark text/icons on terracotta actions) and
   compare against the intended Kinescope visual direction for anything
   that reads obviously off.
6. Note anything that needs adjusting back in this file so the next
   round starts from an updated baseline instead of the same guesses.

Later passes already completed the downloaded-font and dark-theme items from this original list. A dedicated splash/launch theme remains optional only if startup ever shows a visible flash on the real device; it is not active roadmap work.

## "Maximally similar" pass (post-initial design system)

Pushed further toward Claude's actual app layout, not just its color
tokens:

- **Real downloadable fonts.** Inter (sans, UI/body text) and Lora
  (serif, the one headline) via Compose's Google Fonts provider,
  replacing the system Serif/Default families from the first pass.
  No font files bundled in the APK — fetched on-device via Google
  Play Services the first time they're needed, and the API is
  designed to fail soft (falls back to the system font) if that ever
  doesn't work, rather than crashing.
- **Bottom composer bar.** The URL input moved from an inline field
  near the top to a rounded, filled pill anchored to the bottom of
  the screen with a send icon inside it — the single most
  recognizable layout element of Claude's own app. Quality chips sit
  just above it.
- **Icon-only top bar actions.** "Update" and "Settings" text buttons
  became icon buttons in a proper `TopAppBar`, matching the sparse,
  icon-driven chrome of Claude's app rather than a row of labeled
  buttons.
- **Icon-only Play button** in the library list instead of a text
  button, for the same reason.

Still not done, still Claude's actual name/logo/licensed fonts are
intentionally never used — see the honesty section above, which
still applies in full.
## Patch 24 — navigation and icon extension

The first post-roadmap sprint extends this system without changing its core palette or typography:

- **Bottom navigation / glass treatment.** Home and Settings sit in a rounded translucent `surface` container with a subtle `outline` border, tonal elevation and soft shadow. The center Add action floats as a stronger circular `primary` element. This is the project's "glassmorphism 2.0" interpretation: layered translucency and depth using native Compose/Material primitives, not a fake screenshot blur or a new rendering dependency.
- **Information architecture.** Home = queue + library. Center Add = focused URL/quality flow with clipboard prefill. Settings = defaults/storage/extractor/YouTube session. Icon-only navigation keeps chrome compact.
- **Launcher icon.** Original retro-TV silhouette; `#FAF9F5` background, `#D97757` shell, `#F3DDD2` glass, `#141413` controls, translucent white highlight. Geometry stays inside the adaptive safe zone and a dedicated monochrome layer supports themed icons.
- **Localization.** Visual layouts must tolerate both English and Russian resources; avoid fixed text widths.

## Patch 25 — accessibility and state semantics

Patch 25 keeps the established palette but tightens the light-theme combinations that carry actionable/status meaning:

- `onPrimary` is now `#3D1A0E` on terracotta `primary` rather than white.
- `success` is `#56683F` and `warning` is `#7A5A18` for stronger contrast on warm light surfaces.
- Queue state color is semantic: active states use the primary-container foreground, recoverable Pause/Interrupted uses `warning`, Done uses `success`, and terminal Failed uses Material `error`.
- Library rows expose secondary type/size/date metadata without adding another accent color.
- Destructive Delete requires confirmation instead of relying on color alone.

These are token/interaction corrections, not a visual redesign. The glass bottom navigation and retro-TV icon introduced in patch 24 remain the current direction.
## Patch 28 — lighter chrome and gesture cleanup

Patch 28 modernizes the existing system without changing its identity:

- Keep the warm cream/terracotta palette, Inter/Lora typography, native Material shapes and the glass navigation concept.
- Bottom navigation is intentionally narrower (`maxWidth 330dp`), with 48dp touch targets and a 48dp Add action, lower tonal elevation and softer shadow. It should read as a floating control strip rather than a full-width dock.
- Home gets one compact text action, `ByeDPI` / `YouTube`, instead of another card or persistent banner. State detail belongs in Settings and the foreground notification.
- ByeDPI Settings are progressive-disclosure: status -> test -> optional enable. Raw strategy command lines are implementation detail and no longer occupy the normal settings surface.
- The YouTube tunnel and Kinescope download/test engine use separate Android processes (`:dpi_vpn` and `:dpi`), preserving the existing process-isolation rule for ByeDPI's native global state.
- Download rows expose destructive removal by end-to-start swipe. The red destructive surface appears only during the gesture; pause/resume remains the only persistent per-row control. `SAVING` cannot be swiped away.
- Preserve whitespace. Do not compensate for the smaller navbar by adding new labels, borders, gradients, floating badges or decorative surfaces elsewhere.
