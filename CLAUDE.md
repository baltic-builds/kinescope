# CLAUDE.md — Project Instructions

Read this file first in any new session working on this repo.

## What this project is
A personal Android app that downloads YouTube videos for offline
viewing. Built by Baltic, who travels for work to a region where
YouTube is network-restricted. Videos are downloaded at home in the
Netherlands (normal residential IP) and watched offline on the phone
during trips.

## Ground rules (do not deviate without being asked)
- **Personal use only.** No Google Play distribution. Distribution is
  via a sideloaded APK (e.g. attached to a private GitHub Release).
- **Language:** all code, comments, commit messages, and this
  documentation are written in English, regardless of the language
  the user and Claude converse in elsewhere.
- **No custom extractor.** Never implement YouTube signature-cipher
  decryption, bot-detection bypass, or any reverse-engineered
  extraction logic from scratch. All extraction goes through the
  actively-maintained `yt-dlp` project (via a Kotlin/Android wrapper
  library). This app is a UI + orchestration layer on top of that
  dependency — nothing more.
- **Zero required cost.** No paid services. A home-hosted backend is
  optional future scope, never required for the app to work.
- **Build target:** the app must build headlessly via
  `./gradlew assembleDebug` inside a GitHub Codespace — there is no
  Android Studio GUI and no emulator available there. Don't add build
  steps that assume an interactive IDE or a connected device/emulator
  (manual install/testing on a real phone afterwards is fine and
  expected).

## Current state
See `ROADMAP.md` for phase status and `HANDOFF.md` for the latest
session snapshot. **Read `HANDOFF.md` first** if you're starting a
fresh conversation with no prior context on this repo.

## User instruction log
(Append new instructions from the user here, most recent last, so
future sessions don't lose them.)

- Write all code and documentation in English.
- Keep writing phases back-to-back without pausing for a build check
  after each one — the user will build and test everything together
  once, later. Still flag unverified/risky assumptions in code
  comments and ROADMAP.md as they come up; just don't stop and wait.
- Historical decision (patch 16, **superseded by patch 24**): debug APKs were built via `.github/workflows/build-debug.yml`. That workflow no longer exists; release-only CI is the current rule below.
- Process, starting patch 16: work through `ROADMAP.md` in order. When
  an item is completed, remove its detailed checklist text from
  `ROADMAP.md` (collapse to a one-line pointer) and log what changed in
  `CHANGELOG.md` instead. Deliver a Python patch script per stage, as
  before.
- **Decision (patch 19): Step 9 (signed release) starts now**, ahead of
  Step 5's manual on-device checklist being individually gone through
  and reported back. This is deliberate, not an oversight — a future
  session should act on it, not re-litigate it or refuse citing
  `ROADMAP.md`'s original Step 9 gate. That gate's underlying point
  still holds, though: a signed release is built from the same
  unverified code as the debug build, so don't assume something Step
  5's checklist would have caught is actually fine just because Step 9
  was authorized to start.
- Work in sprints across conversations: continue autonomously through
  a batch of `ROADMAP.md` work without stopping between individual
  steps, then deliver one Python patch script at the end covering the
  whole sprint (not one patch per tiny change) — the user installs
  several sprints' patches together.
- **Decision (patch 20): release builds (Step 9) are produced via
  GitHub Actions** (`.github/workflows/build-release.yml`), not a
  local `./gradlew assembleRelease` run in the Codespace. The local build path in `RELEASE.md`
  Option A is kept as a working fallback, not removed.
- **Decision (patch 24): the debug GitHub Actions workflow is removed.** CI distribution is release-only through `.github/workflows/build-release.yml`, which publishes the signed APK and checksum to GitHub Releases. `./gradlew assembleDebug` remains the local Codespace sanity build required by `AGENTS.md`.
- **Decision (patch 24): UI localization is English + Russian.** Keep English as the default resource set; `values-ru` is used automatically for Russian locale. New user-visible strings must be added to both locales.
- **Decision (patch 24): YouTube recovery remains yt-dlp-only.** Nightly updates, cookies, retries and yt-dlp player-client fallbacks are allowed; custom BotGuard/PO-token generation, signature deciphering, or anti-bot bypass code remains forbidden.
- **Decision (patch 24): first-sprint navigation is Home / Add / Settings.** Five rapid taps on Settings opens the diagnostic log journal. Android Back from Settings/Add returns Home.

- **Decision (patch 25): the durable download journal is the source of truth across process death.** Persist accepted jobs before foreground-service dispatch; never silently auto-restart an interrupted network transfer after process recreation. Surface it as recoverable `INTERRUPTED` and require an explicit Resume.
- **Decision (patch 25): single-video URL semantics are intentional.** Use the shared strict `YouTubeUrlParser`; canonicalize accepted video links, reject playlist-only/lookalike/non-HTTP(S) input, and keep `--no-playlist`. Playlist expansion is not an active roadmap item.
- **Decision (patch 25): privacy precedes diagnostic detail.** App backup stays disabled while local YouTube cookies, logs, and the job journal exist. Logs must redact URLs, cookie values and app-private paths before both persistence and Logcat.
- **Decision (patch 25): the former lowercase `roadmap.md` audit is consumed and remains deleted.** Its still-relevant engineering findings were implemented or converted to verification gates in `ROADMAP.md`. Historical audit assumptions forbidding authentication / embedded browser are superseded by the user's explicit patch-24 optional YouTube-session requirement and must not be restored.
- **Decision (patch 25): release verification includes tests and lint.** Local patch verification uses `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`; release CI runs unit tests + lint before the signed build and verifies signature, zip alignment, package/version metadata and ABI contents before publishing.
- **Known upstream limitation (patch 25): do not claim 16 KB page-size compatibility yet.** Android 15 can run on 16 KB page-size devices, while the currently pinned `youtubedl-android 0.18.1` has an open upstream native-payload compatibility issue. Re-check this when a newer wrapper release is considered; do not paper over it locally with custom native binaries without an explicit user decision.
