# Kinescope

[![Build Debug APK](https://github.com/baltic-builds/kinescope/actions/workflows/build-debug.yml/badge.svg)](https://github.com/baltic-builds/kinescope/actions/workflows/build-debug.yml)
[![Build Signed Release APK](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml/badge.svg)](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-29-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![License](https://img.shields.io/badge/license-personal--use--only-lightgrey)

A personal Android app for downloading YouTube videos at home, for
offline viewing during work trips to a network-restricted region.
Sideload-only — no Google Play, no backend, no required cost.

**Status: working.** Confirmed on a real device (Android 13): install,
share/paste a link, queue a download, play it back offline, and
background persistence all work. Signed release builds succeed via
CI; installing that signed build on a device is the one remaining
open item — see [Technical debt](#technical-debt).

## What this is

Share a YouTube link into the app (or paste one directly), pick a
quality preset, and it downloads in the background via a foreground
service. The finished file lands in the device's Downloads folder,
ready for offline playback in any video player — no connectivity
needed once it's downloaded.

## What this explicitly is not

- Not distributed via Google Play — **sideload only** (install the
  debug or signed APK directly).
- No backend, no account system, no cross-device sync.
- No custom YouTube extraction logic. All extraction goes through
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), via the
  [youtubedl-android](https://github.com/yausername/youtubedl-android)
  wrapper library — never reverse-engineered independently.
- No required paid services.
- Built for one person's own use, not for general distribution.

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.1.0 |
| UI | Jetpack Compose, Material3 (Compose BOM `2024.11.00` → Material3 1.3.1) |
| Extraction | [`youtubedl-android`](https://github.com/yausername/youtubedl-android) `0.18.1` (wraps `yt-dlp` + bundled ffmpeg/ffprobe) |
| Build | Gradle `8.10.2`, AGP `8.7.2`, JDK 21 |
| `minSdk` / `compileSdk` / `targetSdk` | 29 / 35 / 35 |
| ABI | `arm64-v8a` only (kept the debug/release APK small — see [Technical debt](#technical-debt) if you need another) |
| Distribution | Sideloaded APK (debug or signed release), built locally or via GitHub Actions |

## Building

This project is developed in GitHub Codespaces — there's no Android
Studio GUI or emulator in that environment, so every build has to
succeed headlessly.

1. Open a Codespace on this repo. `.devcontainer/devcontainer.json`
   requests a JDK and installs Gradle automatically on container
   creation via `postCreateCommand`.
2. If `gradlew` isn't present yet (a fresh Codespace, or the
   `postCreateCommand` didn't finish), run `bash .devcontainer/setup.sh`
   manually. It installs the Android SDK command-line tools, accepts
   licenses, installs `platform-tools`/`platform 35`/`build-tools`,
   generates the Gradle wrapper, and pins Gradle to a JDK version it
   actually supports — see `HANDOFF.md`'s "Key learnings" section for
   why a Codespace can have multiple JDKs and why that last step
   matters.
3. `./gradlew assembleDebug`
4. Install the resulting APK on a device (`adb install
   app/build/outputs/apk/debug/app-debug.apk`, or transfer the file and
   tap it).

**Alternative: build via GitHub Actions.** If adb isn't available, or
downloading the APK through the Codespace browser UI is inconvenient,
use the *Build Debug APK* workflow under this repo's Actions tab
(`.github/workflows/build-debug.yml`) instead of steps 3–4 above — run
it by hand, supply a version name, and download the resulting APK from
the run's Artifacts. No automatic trigger; each run is a deliberate,
manually-versioned build.

**Signed release builds** work the same way via the *Build Signed
Release APK* workflow (`.github/workflows/build-release.yml`), backed
by four repo secrets (a base64-encoded keystore plus its
passwords/alias) — see `RELEASE.md` for the one-time setup, and for
the local-build alternative (`./gradlew assembleRelease`).

## Documentation map

- **`CLAUDE.md`** — ground rules for this project (no custom
  extraction, English-only code/docs, no paid services, no Anthropic
  branding).
- **`AGENTS.md`** — process/workflow conventions for an AI coding
  agent working on this repo (source-of-truth reading order,
  verification discipline, patch-delivery and testing conventions).
- **`ROADMAP.md`** — current status and open technical debt. The
  original 9-step implementation plan is done; this file tracks what's
  left, not what already shipped.
- **`roadmap.md`** (lowercase) — a second, separate sprint-based
  audit/plan (S0–S11) from GPT Astra. Queued for **after** every item
  in `ROADMAP.md` above is closed — see the note near its top for why,
  and don't start it early.
- **`HANDOFF.md`** — cross-session snapshot: what's done, what's next,
  full patch history, key learnings. Read this first when resuming
  work in a new conversation.
- **`CHANGELOG.md`** — one entry per patch, newest first: exactly what
  changed and why.
- **`CJM.md`** — the Customer Journey Map behind every priority
  decision in this project's history.
- **`design.md`** — the visual design system (colors, typography,
  icon), including an explicit section on what it approximates and
  what it deliberately avoids.
- **`RELEASE.md`** — signing-key generation and the signed-release
  process.

## Technical debt

See `ROADMAP.md` for the full, current list: confirming the signed
release build on a real device, a handful of adversarial Step 5 checks
not yet individually run (race-condition stress test, error-text
matching against a real broken video, airplane mode, a very large
download), and an optional, unscheduled backlog. Nothing here blocks
normal use of the app.

## License

Personal project, not distributed publicly and not intended for
reuse. No license is granted.
