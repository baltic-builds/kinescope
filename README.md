# Kinescope

A personal Android app for downloading YouTube videos at home, for
offline viewing during work trips to a network-restricted region.

**Status:** the first successful `./gradlew assembleDebug` was achieved
after patches 01-14. Not yet installed on a real device -- Step 5
(device install + manual testing) is next. See `HANDOFF.md` for the
current session-handoff snapshot and `ROADMAP.md` for the full,
actively-tracked implementation plan.

## What this is

Share a YouTube link into the app (or paste one directly), pick a
quality preset, and it downloads in the background via a foreground
service. The finished file lands in the device's Downloads folder,
ready for offline playback in any video player -- no connectivity
needed once it's downloaded.

## What this explicitly is not

- Not distributed via Google Play -- **sideload only** (install the
  debug or signed APK directly).
- No backend, no account system, no cross-device sync.
- No custom YouTube extraction logic. All extraction goes through
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), via the
  [youtubedl-android](https://github.com/yausername/youtubedl-android)
  wrapper library -- never reverse-engineered independently.
- No required paid services.
- Built for one person's own use, not for general distribution.

## Building

This project is developed in GitHub Codespaces.

1. Open a Codespace on this repo. `.devcontainer/devcontainer.json`
   requests a JDK and installs Gradle automatically on container
   creation via `postCreateCommand`.
2. If `gradlew` isn't present yet (a fresh Codespace, or the
   `postCreateCommand` didn't finish), run `bash .devcontainer/setup.sh`
   manually. It installs the Android SDK command-line tools, accepts
   licenses, installs `platform-tools`/`platform 35`/`build-tools`,
   generates the Gradle wrapper, and pins Gradle to a JDK version it
   actually supports -- see `HANDOFF.md`'s "Key learnings" section for
   why a Codespace can have multiple JDKs and why that last step
   matters.
3. `./gradlew assembleDebug`
4. Install the resulting APK on a device (`adb install
   app/build/outputs/apk/debug/app-debug.apk`, or transfer the file and
   tap it).

## Documentation map

- **`CLAUDE.md`** -- ground rules for this project (no custom
  extraction, English-only code/docs, no paid services, no Anthropic
  branding).
- **`ROADMAP.md`** -- the current, active implementation plan (Claude
  Fable 5.1's original 9-step review, plus an ongoing patch-by-patch
  traceability Appendix). Work happens here first.
- **`roadmap.md`** (lowercase) -- a second, separate sprint-based
  audit/plan (S0-S11) from GPT Astra. Deliberately queued for **after**
  `ROADMAP.md` above is fully done -- see the note near the top of
  `ROADMAP.md` for why, and don't start it early.
- **`HANDOFF.md`** -- cross-session snapshot: what's done, what's next,
  full patch history, key learnings. Read this first when resuming work
  in a new conversation.
- **`design.md`** -- the visual design system (colors, typography,
  icon), including an explicit section on what it approximates and what
  it deliberately avoids.
- **`RELEASE.md`** -- signing-key generation and the signed-release
  process (Step 9 -- not started yet).

## Tech stack

Kotlin, Jetpack Compose (Material3), `youtubedl-android` 0.18.1 (a
yt-dlp wrapper), Gradle 8.10.2. `compileSdk`/`targetSdk` 35, `minSdk`
29.
