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
   without a license isn't something to do. This system uses Android's
   built-in system font families (`FontFamily.Serif` /
   `FontFamily.Default`) instead, which get the same *character*
   (serif headline, clean sans body) without touching anyone's
   licensed type.

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
| `onPrimary` | `#FFFFFF` | Text/icons on top of `primary` |
| `primaryContainer` | `#F3DDD2` | Selected chip background (a light tint of `primary`) |
| `onPrimaryContainer` | `#6B3520` | Text on `primaryContainer` |
| `success` (custom, not a Material3 slot) | `#788C5D` | "Done" status text in the queue list |
| `error` | `#BA1A1A` | Failed status text. Kept as a standard, unambiguous red rather than a brand-adjacent tone — a failure needs to read as a failure at a glance more than it needs to be on-brand. |

A dark theme was added later (patch 03); `Theme.kt` is the source of truth for its exact tokens. The light tokens above remain the visual baseline.

## Typography

| Role | Family | Weight | Used for |
|---|---|---|---|
| `headlineSmall` | `FontFamily.Serif` (system serif, e.g. Noto Serif) | SemiBold | The "Kinescope" title only |
| `titleMedium` / `titleSmall` | `FontFamily.Default` (system sans, e.g. Roboto) | SemiBold | Section headers: "Queue", "Library", settings labels |
| `labelLarge` | `FontFamily.Default` | Medium | Button text |
| everything else | `FontFamily.Default` | Regular | Body text, list rows, status lines |

Using the *system* serif/sans families (rather than a specific
downloaded font like Inter or Lora) was a deliberate scope call: it
gets the intended serif-headline / sans-body pairing with zero new
dependencies and zero new build risk, on a project that already has
a long list of unverified assumptions (see ROADMAP.md). Swapping in
an actual downloaded font (e.g. via Compose's Google Fonts provider)
is a safe, optional follow-up once the app builds and runs —
deliberately not bundled now to avoid stacking a 10th unverified
assumption on top of the existing nine.

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
   (dark text on cream, white text on terracotta button) and compare
   against the real Claude app for anything that reads obviously off.
6. Note anything that needs adjusting back in this file so the next
   round starts from an updated baseline instead of the same guesses.

Not done in this pass, worth queuing up only if it turns out to
matter:

7. Swap system serif/sans for actual downloaded fonts (e.g. Inter +
   Lora via Compose's Google Fonts provider) if the system-font
   version doesn't feel close enough once seen on a real screen.
8. Dark theme token set.
9. A proper splash/launch theme (currently: default system background
   until Compose content loads — fine at this app's size, but worth
   revisiting if startup ever feels like it flashes).

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
