# Roadmap

**Current state after patch 25:** the original implementation roadmap and the later S0-S11 audit have been consumed into the codebase. Patch 24 delivered the first feature sprint; patch 25 closes the remaining autonomous reliability, persistence, privacy, storage, test and release-hardening work that can be completed in Codespaces without a physical device.

Patch 24's local build is explicitly confirmed by the user (`BUILD SUCCESSFUL`, commit `91169f5`). Patch 25c is the delivery hotfix for the single AAPT string-resource blocker found by Patch 25b's mandatory Gradle gate; Patch 25c itself is guarded by `testDebugUnitTest`, `lintDebug` and `assembleDebug` before its delivery scripts are removed. The remaining items below are therefore **verification gates**, not unimplemented feature work.

Decisions that remain unchanged:

- `applicationId` / namespace: `com.kinescope.app`.
- Personal sideload distribution only; no Google Play requirement.
- All extraction remains inside `yt-dlp` through `youtubedl-android`; no custom signature deciphering, BotGuard, PO-token generation or anti-bot bypass.
- English is the default UI; Russian devices use `values-ru`.
- The current product flow is single-video download. Playlist expansion and a backend are not part of the active roadmap.
- Patch 24's explicit optional YouTube-session / embedded-browser requirement supersedes the older audit draft's historical "no authentication / no embedded browser" assumption.

---

## What patch 25 closes

The detailed implementation record lives in `CHANGELOG.md`; this file only tracks state.

- [x] Durable download journal. A job is persisted before the foreground service is dispatched. Process death or service interruption is surfaced as recoverable `INTERRUPTED` instead of silently losing the queue.
- [x] Stable quality IDs and bounded format fallbacks, so persisted jobs survive list reordering and a fallback cannot silently exceed the selected video resolution.
- [x] Strict YouTube video URL parsing and canonicalization. Playlist-only links, lookalike hosts, non-HTTP(S) schemes, userinfo, non-standard ports and oversized shared payloads are rejected before enqueue.
- [x] Active-job deduplication by YouTube video ID.
- [x] Queue-control / idle-shutdown race hardening: a Pause that races `queue.poll()` is honored before network work begins, and an old worker uses `stopSelfResult(startId)` so it cannot stop a newer service start.
- [x] One engine synchronization boundary for yt-dlp / ffmpeg initialization, execution and self-update; an update cannot replace the executable while another thread is downloading.
- [x] Per-job workspaces, orphan cleanup and resumable partial files. Jobs no longer scan a shared cache namespace for their output.
- [x] Explicit queue states for preparing, running, processing, saving, paused, interrupted, failed and stopped; publication is not reported as done until MediaStore commit succeeds.
- [x] MediaStore two-phase publication hardening. MIME type is derived from the actual output where possible; `IS_PENDING` is committed before the source file is deleted; incomplete pending rows are cleaned on recovery.
- [x] Library queries cover the current and historical Kinescope download folders, exclude pending rows, expose basic local metadata, and run off the UI thread.
- [x] Destructive library deletion requires confirmation.
- [x] Android 15 `dataSync` foreground-service timeout handling via `Service.onTimeout()`, with the active job returned to a recoverable state before the service stops.
- [x] Notification tap/stop actions, throttled progress updates and completion notification.
- [x] Notification permission request moved to the first accepted download instead of app startup.
- [x] Privacy-hardened diagnostics: URL/cookie/private-path redaction, bounded stack traces, app backup disabled so cookies/logs/job journal do not enter normal Android backup.
- [x] Pure JVM tests for URL parsing, yt-dlp error classification and diagnostic privacy redaction.
- [x] Release CI now runs unit tests + lint before assembly, verifies signature, zip alignment, package/version metadata and arm64-only ABI contents, then publishes APK + SHA-256 to GitHub Releases.
- [x] Android command-line tools are pinned by official URL + SHA-256 in `.devcontainer/setup.sh` instead of being silently scraped from a changing webpage.
- [x] Light-theme status/action contrast tightened without changing the Kinescope palette; `design.md` is the source of truth.
- [x] Third-party dependency/licensing inventory added in `THIRD_PARTY_NOTICES.md`.

---

## Remaining verification gates

These require the user's phone or a real GitHub Actions run. Do not mark them complete from code review or a local compile alone.

### Real-device flow

- [ ] Run several ordinary YouTube downloads, including links that previously intermittently produced "Sign in to confirm you're not a bot". Confirm the bounded recovery chain either completes or parks the job as resumable Pause without an infinite loop.
- [ ] Test optional YouTube session capture. If Google refuses embedded-browser sign-in on the device, record that as an upstream/platform limitation; do not weaken the no-custom-bypass rule.
- [ ] Pause a running download, kill/reopen the app, then Resume it. Confirm partial data is reused and the job remains visible as `INTERRUPTED` after process death.
- [ ] Stop both a queued and a running job. Confirm its workspace is removed and later queue entries continue.
- [ ] Queue 3-4 videos within a few seconds and confirm every accepted job eventually starts; also confirm duplicate submission of the same video is rejected visibly.
- [ ] Test private, age-restricted and unavailable videos and confirm the localized typed error states still match current yt-dlp output.
- [ ] Queue while airplane mode is enabled; confirm the job becomes recoverable `INTERRUPTED`, then resumes normally after connectivity returns.
- [ ] Complete at least one large/slow download and one audio-only download. Confirm the final file type/name/library metadata are correct and no pending MediaStore ghost remains after success/failure.
- [ ] Verify Russian locale, non-Russian English fallback, Home/Add/Settings navigation, Back-to-Home behavior, five-tap Logs access, delete confirmation, and the new launcher/themed icon on-device.
- [ ] Verify notification permission timing, notification Stop action, tap-to-open, progress throttling and completion notification.

### Release pipeline

- [ ] Run `.github/workflows/build-release.yml` with a fresh semantic version and confirm `testDebugUnitTest`, `lintDebug`, signature/zip/package/version/ABI checks all pass.
- [ ] Confirm the resulting GitHub Release contains the signed arm64 APK and matching `.sha256` file.
- [ ] Install that signed release over the previous signed build and confirm settings, YouTube session and recoverable job journal survive the app update as expected.

### Platform limitation to re-check upstream

- [ ] **16 KB page-size devices:** Android 15 supports devices with 16 KB memory pages, but the currently pinned `youtubedl-android 0.18.1` still has an open upstream issue reporting a bundled ffmpeg/libwebp payload that remains 4 KB-aligned. Do not claim Kinescope is 16 KB-compatible until the wrapper publishes a verified fix; re-evaluate when upgrading that dependency.

---

## Deliberately out of scope

- Playlist/batch URL semantics beyond the existing explicit queue of individual videos.
- Cross-device queue sync or a required backend.
- Custom YouTube extraction, anti-bot bypass, BotGuard or PO-token implementation.
- Automatic restart of interrupted downloads after process death. Recovery is intentionally explicit so the app never begins network work unexpectedly after Android recreates the process.

The former lowercase `roadmap.md` audit has been consumed by patch 25 and remains deleted. Its still-relevant findings are either implemented above, converted into a verification gate, or explicitly superseded by later user decisions. Future work should be added to this single `ROADMAP.md` only.
