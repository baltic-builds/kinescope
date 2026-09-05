# Handoff Snapshot

Paste this file's contents (plus CLAUDE.md, ROADMAP.md, and
RELEASE.md if the new conversation doesn't already have repo access)
at the start of a new conversation to resume work with minimal
re-explaining.

## Last updated
**All 7 roadmap phases are written, plus a post-roadmap design pass**
(see `design.md`) applied while the user's first Codespace build was
running. None of it has been build-verified — not even once.
Everything was written in a sandboxed environment with no Android
SDK, at the user's explicit, repeated request to keep going and test
everything together at the end, rather than pausing after any
individual phase. `./gradlew assembleDebug` / `assembleRelease` have
never actually been run. This is now the single most important next
step, full stop.

## What exists right now
Full app: URL input (or share a link in via ACTION_SEND), quality
picker, queue with live status, MediaStore-backed library with a Play
button, settings (default quality, Downloads subfolder), automatic +
manual yt-dlp updates, friendly error messages, and a documented
signed-release process (`RELEASE.md`) that deliberately requires the
user to generate their own keystore rather than Claude generating one
(a signing key is a secret).

File map:
- `MainActivity.kt` — main screen + `SettingsPanel`.
- `DownloadService.kt` — foreground service, queue processing,
  connectivity check, friendly errors, notification.
- `DownloadQueueBus.kt` — shared `StateFlow` between service and UI.
- `QualityPresets.kt` — the four quality/format options.
- `YtOfflineApp.kt` — yt-dlp/ffmpeg init + startup update check.
- `YtDlpUpdater.kt` — wraps the library's self-update call.
- `MediaStorage.kt` — MediaStore publish/list, configurable subfolder.
- `Settings.kt` — SharedPreferences wrapper.
- `RELEASE.md` — signing key generation, signed build, GitHub Release
  upload, phone install instructions.
- `design.md` — visual design system (colors, typography, shapes),
  with an explicit honesty section on how approximate it is and why
  Anthropic's real fonts/logo are deliberately not used.
- `Theme.kt` — the design.md tokens as actual Compose Material3
  theming (`YtOfflineTheme`), applied in `MainActivity.kt`.
- New adaptive app icon (`drawable/ic_launcher_*.xml`,
  `mipmap-anydpi-v26/ic_launcher*.xml`), referenced from the manifest.
- `.devcontainer/` — installs JDK, Gradle, Android SDK cmdline-tools
  automatically on Codespace creation.

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7,
`versionName` "1.0.0".

## Known risks (see ROADMAP.md "Notes / open risks" for full detail — 9 items)
The two biggest classes of risk, in order of how early they'd surface:
1. **Compile-time**: unverified `youtubedl-android` import paths and
   `addOption` overloads (Phase 1–2). If the build fails, start here
   — everything else depends on these compiling.
2. **Runtime**: assumed output filename/extension, assumed
   `updateYoutubeDL()` signature, `friendlyError()` string matching
   based on secondhand error text, foreground service type. These
   will only surface once the app actually runs and downloads
   something on a device — Codespaces has no emulator, so this needs
   a real phone.

Full list of all 9 flagged assumptions is in ROADMAP.md; not
repeating all of them here to keep this snapshot short.

## Immediate next step for the user
1. Push this project to a (private) GitHub repo, or update the
   existing one.
2. Open/reopen it in a Codespace, let `postCreateCommand` finish.
3. `./gradlew assembleDebug` first (faster, no signing needed) — fix
   any compile errors top-down.
4. Install the debug APK on a real phone, actually try downloading a
   video. This is where the runtime assumptions get tested.
5. Only once that works end-to-end: follow `RELEASE.md` for a signed
   build.
6. Report back what broke, if anything — paste this file plus the
   exact error into a new conversation.

## Immediate next step for Claude
There is no Phase 8. Once the user reports back build/runtime
results, the work becomes: fix whatever's actually broken (using the
known-risks list above as a first-guess index, not a guarantee it's
exhaustive), then take feature requests as they come. Don't invent
new "phases" — ask what the user actually wants next once the app is
confirmed working.
