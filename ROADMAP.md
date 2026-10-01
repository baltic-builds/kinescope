# Roadmap

## Status after patch 35 (2026-09-30)

Confirmed on a device: builds, the strategy search and its ranking, the compact log and Copy report.
Reported broken and addressed by patch 35 (not yet confirmed): downloads that never visibly start,
a very slow YouTube app through the tunnel, downloads under a third-party VPN. Reported and not fixable
from code: the Play Protect install block (see `RELEASE.md`). The full picture, including how to read
the next report, is the "Session snapshot" at the top of `HANDOFF.md`.

**Current state after patch 25:** the original implementation roadmap and the later S0-S11 audit have been consumed into the codebase. Patch 24 delivered the first feature sprint; patch 25 closes the remaining autonomous reliability, persistence, privacy, storage, test and release-hardening work that can be completed in Codespaces without a physical device.

Patch 24's local build is explicitly confirmed by the user (`BUILD SUCCESSFUL`, commit `91169f5`). Patch 25c is the delivery hotfix for the single AAPT string-resource blocker found by Patch 25b's mandatory Gradle gate; Patch 25c itself is guarded by `testDebugUnitTest`, `lintDebug` and `assembleDebug` before its delivery scripts are removed. The remaining items below are therefore **verification gates**, not unimplemented feature work.

**Patch 27** adds new, user-requested feature scope on top of the verification-gates state above: an in-app network bypass (a bundled MIT-licensed ByeDPI engine, the same engine the third-party ByeByeDPI app wraps) that can help when a network blocks YouTube by inspecting connection headers rather than by DNS or IP filtering. It is off by default. Details in `CHANGELOG.md`; the integration research and design record lives in the project's memory as `INTEGRATION_PLAN.md`.

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
- [x] Release CI now runs unit tests + lint before assembly, verifies signature, zip alignment, package/version metadata and arm64-only ABI contents, then publishes the APK to GitHub Releases.
- [x] Android command-line tools are pinned by official URL + SHA-256 in `.devcontainer/setup.sh` instead of being silently scraped from a changing webpage.
- [x] Light-theme status/action contrast tightened without changing the Kinescope palette; `design.md` is the source of truth.
- [x] Third-party dependency/licensing inventory added in `THIRD_PARTY_NOTICES.md`.

---

## Remaining verification gates

### Patch 36 CJM/UX stabilization (2026-10-01)

Source-of-truth baseline from the user: **build succeeds; downloads work; DPI bypass works.** Patch 36
preserves that path and fixes UX/lifecycle friction around it. Full detail in `CHANGELOG.md`.

- [ ] Fresh install: automatic strategy search starts and the RU device shows the Russian popup; a
  non-Russian locale shows English. Keep the app open until it completes.
- [ ] Install a newer build over the current one: first launch starts the background re-check and shows
  the same popup while the previously verified strategy remains usable.
- [ ] During a download the queue row stays the same height; progress updates smoothly and no raw/jumpy
  ETA causes the whole list to move.
- [ ] At the end, the row changes from Downloading/Finishing to Processing while yt-dlp merges/remuxes,
  then Saving while MediaStore publishes. It never sits at "Downloading 100%" during conversion.
- [ ] After MediaStore commit the completed row disappears from Queue and the new file appears in Library
  without a manual refresh.
- [ ] Tap the active download notification while Kinescope is backgrounded or on Settings: the existing
  singleTask activity comes to the foreground on Home. Repeat for the strategy-search notification.
- [ ] Bottom navigation has three balanced 56dp targets and clears gesture/three-button system navigation;
  the Home YouTube-bypass action no longer visually dominates it.
- [ ] Settings ends with `powered by ephedrine`.


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
- [x] **Network bypass core (patch 27):** user-confirmed after this handoff snapshot: GitHub Actions builds successfully and ByeDPI works on the target device/network. Do not reopen the old NDK/device-engine gate unless a regression appears.
- [ ] **Patch 28 YouTube split tunnel:** on the restricted network, test a strategy, tap Home -> ByeDPI, grant Android VPN consent on first use, confirm the official YouTube app opens and plays through the local route, Share a video to Kinescope, and confirm the resulting download completes through the isolated `:dpi` download engine while the `:dpi_vpn` YouTube session remains active. Verify notification Stop and confirm other apps are not routed.
- [ ] **Patch 28 queue gesture:** swipe queued, paused and running rows end-to-start and confirm each disappears, durable state/workspace is removed, and later queued jobs continue. Confirm SAVING cannot be swiped away.
- [ ] Verify Russian locale, non-Russian English fallback, Home/Add/Settings navigation, Back-to-Home behavior, five-tap Logs access, delete confirmation, and the new launcher/themed icon on-device.
- [ ] Verify notification permission timing, notification Stop action, tap-to-open, progress throttling and completion notification.

### Release pipeline

- [ ] Run `.github/workflows/build-release.yml` with a fresh semantic version and confirm `testDebugUnitTest`, `lintDebug`, signature/zip/package/version/ABI checks all pass.
- [ ] Confirm the resulting GitHub Release contains the signed arm64 APK and no `.sha256` file (removed in patch 33), and that the run summary shows the signing certificate fingerprint.
- [ ] Install that signed release over the previous signed build and confirm settings, YouTube session and recoverable job journal survive the app update as expected.

### Platform limitation to re-check upstream

- [ ] **16 KB page-size devices:** Android 15 supports devices with 16 KB memory pages, but the currently pinned `youtubedl-android 0.18.1` still has an open upstream issue reporting a bundled ffmpeg/libwebp payload that remains 4 KB-aligned. Do not claim Kinescope is 16 KB-compatible until the wrapper publishes a verified fix; re-evaluate when upgrading that dependency.

### Patch 27 network bypass: verified baseline

- [x] The user reports that GitHub Actions now completes successfully and ByeDPI works on the target device/network. This supersedes the old sandbox-only warning for the Patch-27 engine path.
- [ ] Patch 28 adds a new layer on top of that verified engine: the YouTube-only Android `VpnService` + TUN-to-SOCKS bridge. Its first-run permission/session lifecycle still needs the focused device check above.

### Patch 29 bypass reliability, fallback strategies, plain-language UI

Not yet device-verified. Full detail in `CHANGELOG.md`.

- [x] Fixed a multi-process init race: the `:dpi` / `:dpi_vpn` engine processes were re-running queue recovery, MediaStore cleanup and the yt-dlp updater on every spawn because `Application.onCreate()` runs in every process and had no process guard.
- [x] The strategy search now runs automatically the first time Settings is opened, and turns the download-bypass switch on by itself once a strategy verifies -- previously nothing triggered the first search, so the switch stayed permanently disabled.
- [x] The search now keeps looking for up to 4 working strategies (primary + up to 3 fallbacks) instead of stopping at the first one; both the download bypass and the YouTube VPN tunnel try them in order and use the first one that actually starts.
- [x] One-time migration of the pre-rename `YTOffline` folder name to `Kinescope` for devices that still had the old value persisted.
- [x] Removed ByeDPI/DNS/TCP/TLS/HTTP/"engine" jargon from the bypass UI copy, EN and RU.
- [x] Video quality presets now prefer H.264 + AAC (falling back to the old unconstrained selector) to fix completed-but-unplayable (black screen, no sound) downloads caused by yt-dlp picking VP9/Opus inside an `.mp4` container.
- [x] **Superseded by Patch 30 below**, which fixes a researched root cause for the search
  finding nothing rather than simply re-testing the same behavior.

### Patch 30 persistent strategy search + required-host relaxation (bypass root-cause fix)

Not yet device-verified. Full detail in `CHANGELOG.md`.

Two later-numbered scripts (`patch_030_persistent_search_service.py`,
`patch_031_docs_investigation.py`) already existed in the repository root but had never actually
been run against this codebase -- none of their target code changes were present, and these docs
still only reflected patch 29. This patch supersedes and deletes both stale scripts with a single
consolidated delivery.

- [x] The strategy search now runs in a new foreground `DpiSearchService` instead of the Settings
  screen's own coroutine scope, so it keeps running when the user leaves Settings or backgrounds
  the app -- previously an explicit `onDispose` cancelled it the moment the screen left
  composition. Its own notification has a Stop button, a second control surface beyond Settings.
- [x] **Root cause for "the search finds zero working strategies":** `DpiStrategySearch.fullPass`
  required ALL THREE probe hosts -- including `redirector.googlevideo.com`, a CDN redirector --
  to fully pass a bare, hand-rolled TLS+HTTP/1.1 probe with no ALPN/H2 negotiation. That host is a
  plausible false-negative source independent of whether a strategy genuinely works.
  `www.youtube.com` and `i.ytimg.com` are now the only hosts required for a strategy to verify;
  the CDN redirector host stays probed for the diagnostic/ranking count but no longer gates
  pass/fail. (Kinescope's built-in strategy list itself was separately checked and already
  matches `hufrea/byedpi`'s own documented reference examples verbatim -- it was not the suspect.)
- [x] The search's per-stage socket timeout is more generous (2.5s -> 4s) to reduce false
  timeouts from a working strategy's added desync latency.
- [x] Every probe now logs its host and the exact stage (DNS/TCP/PROXY/CONNECT/TLS/HTTP) it
  passed or failed at to the hidden diagnostic log journal, so a real run leaves actual evidence
  behind instead of only a bare "0 passed" count if this relaxation is not sufficient by itself.
- [x] `POST_NOTIFICATIONS` is now requested (if not already granted) when a strategy search
  starts and when the Home-screen YouTube bypass action starts the VPN tunnel, not only on the
  first accepted download -- the most likely reason a foreground service's own notification/Stop
  button would go unseen even though the service itself still runs.
- [x] **Confirmed on device (2026-09-27):** the strategy search finds a working strategy on the
  restricted network, and a bypass-enabled download completes end to end (device log shows
  `DpiBypass: Engine ready; strategy=...` followed by a completed job). The search surviving
  backgrounding, the notification's Stop button, and the YouTube-app VPN tunnel specifically were
  not exercised in that report, so they stay open.
- [ ] **Still needs a real device to confirm:** the search surviving minimizing the app /
  switching screens; the notification appearing with a working Stop button; the YouTube-app VPN
  tunnel working end to end (as opposed to the download path, which is confirmed above).

### Patch 31 age-restricted download fix, prominent bypass button, direct YouTube sign-in

Not yet device-verified. Full detail in `CHANGELOG.md`.

- [x] **Root cause found for the "age restricted" failure the user hit:** it was not actually an
  age-restricted video. `DownloadErrorClassifier`'s AGE_RESTRICTED match
  (`contains("age") && (contains("confirm") || contains("restrict"))`) is a substring collision:
  the ordinary phrase "Unable to download webpage" contains "age", and yt-dlp's generic "...
  Confirm you are on the latest version..." trailer (printed on many unrelated errors) contains
  "confirm" -- so a plain bypass-proxy "Host unreachable" failure got mislabeled as
  age-restricted. Tightened to match the real yt-dlp phrases only ("age-restricted",
  "inappropriate for some users", "confirm your age").
- [x] The 4-profile download recovery chain (DEFAULT -> DEFAULT_AFTER_REFRESH -> WEB_SAFARI_IPV4
  -> ANDROID_VR_LOGGED_OUT) previously only kept trying another profile for the narrow
  YOUTUBE_VERIFICATION case; every other failure kind broke out after just the first attempt --
  exactly what turned the misclassified error above into a hard failure instead of a retry.
  AGE_RESTRICTED and OTHER are now recoverable too, so a genuinely age-restricted video gets a
  real chance on `ANDROID_VR_LOGGED_OUT` (a known yt-dlp technique that sometimes works without
  login) and a transient bypass/network hiccup gets a real chance on a later attempt.
- [x] Real fix for downloading age-restricted videos once signed in: the YouTube sign-in WebView
  now strips the "; wv" marker from its user agent. Google's sign-in explicitly detects and
  blocks that marker with "This browser or app may not be secure" (`disallowed_useragent`), a
  well-documented, separate reason sign-in could fail no matter which URL was loaded.
- [x] The sign-in screen now opens directly on Google's sign-in form (the same
  `accounts.google.com/ServiceLogin` entry point youtube.com's own "Sign in" button uses) instead
  of the YouTube homepage.
- [x] The bypass control moved from a small top-bar text button to a large, centered card at the
  top of the Home screen showing the current state and one prominent button. Researched a
  Tauri-based DPI-bypass GUI ("Zapret GUI") per the user's suggestion for reference; its pattern
  is exactly this: an always-visible, one-tap main-screen control rather than a tucked-away
  toggle.
- [ ] **Needs a real device to confirm:** the Home screen's new card is visible and centered in
  all three states (idle, starting, active/error); tapping it behaves the same as the old button
  did; the YouTube sign-in screen opens on the actual sign-in form and signing in succeeds
  (previously untested whether Google's WebView block was even the reason sign-in wasn't
  completing); a real age-restricted video downloads successfully once signed in; a previously
  misclassified transient bypass error now actually retries and can succeed instead of failing
  outright.

### Patch 35 adaptive fallback, load-ranked strategies, manual choice, background re-check

Not yet device-verified. Full detail in `CHANGELOG.md`. Requires patch 34.
- [ ] Copy report after a failed or slow download: the report shows `run`, `hb` (phase, percent, kbps, rx, last line) and `end` lines, and `slow` / `stall` / `route ... ok` lines when the ladder moved.
- [ ] A running download shows "12% · 340 KB/s" (or the ETA when yt-dlp has one), never "-1% (ETA -1s)" and never "Preparing" once real progress exists.
- [ ] A route that stays under 80 KB/s for 25 s is abandoned for the next one (at most two hops); the download resumes, it does not restart; a later download starts on the route that worked.
- [ ] Settings: "Choose strategy manually" lists the strategies with their last results; Test runs one strategy; Use pins it (it goes first, automatic searches keep it first); "Choose automatically" unpins.
- [ ] A search stopped early keeps its passes (message "What it found so far is saved"), and the report header shows the new chain.
- [ ] After the update, the first launch starts a background check, shows the Patch-36 explanatory popup and keeps previous strategies usable while the refresh runs.
- [ ] YouTube tunnel: with a strategy that stops carrying traffic, the log shows `probe fail` twice and then `rotate`, and playback recovers on the next strategy.

### Patch 34 fallback ladder without a hard gate, VPN awareness, compact log

Not yet device-verified. Full detail in `CHANGELOG.md`. Requires patch 33.
- [ ] No VPN, bypass on: a download starts and completes. The copied report shows a `route ... ok` line; if a strategy failed its check the report shows the stage and reason (`chk ... ok=false st=... why=...`) followed by a real attempt on it.
- [ ] No VPN, every strategy dead: the row ends with the plain "Couldn't reach YouTube" message after one pass over the routes (no four-attempt loop, no nightly refresh in between).
- [ ] Third-party VPN on: the direct route is tried first. If YouTube asks for a bot check the row shows the VPN message; report whether any later profile (web_safari IPv4, android_vr) got through.
- [ ] The queue row shows "Preparing the download…" (never "-1% (ETA -1s)") before real progress starts.
- [ ] Settings journal: **Copy report** puts a header plus at most 150 lines on the clipboard; a full strategy search adds about a dozen lines, not about two hundred.
- [ ] Release run: the summary says "Play Protect high-risk declarations: none".
- [ ] Install on the affected phone: report the exact dialog text (screenshot) and whether "More details" / "Install anyway" is offered. See `RELEASE.md`, "If the install is still blocked".

### Patch 33 first-launch check, ranked bypass with fallback ladder, compact navbar

Partly device-verified on 2026-09-29. Full detail in `CHANGELOG.md`. Requires patches 30-32.
- [x] Build succeeds, and the strategy search finishes and keeps four ranked strategies (device log: `Search kept 4 strategies; primary transfer=true speed=1249KB/s`).
- Everything below is still unconfirmed: the popup, the notification icon, the navbar and the missing `.sha256` asset were not reported on. The download items are superseded by Patch 34.
- [ ] Fresh install: the popup appears at once, in one language only (Russian on a Russian device, English otherwise), and the check of all 72 strategies runs without opening Settings; the status-bar notification shows the small-TV icon (not an arrow) and its Stop button works.
- [ ] Share a video while that first check is still running on a blocked network: the row says it is waiting for the check, then downloads through the bypass.
- [ ] With the bypass on, a download completes; the log journal shows which strategy carried it, and that strategy becomes the primary. Force a bad primary (or change networks) and confirm the row moves to "Trying another way", then either finishes or fails with the plain "Couldn't reach YouTube" message instead of sitting on ETA -1.
- [ ] YouTube-app tunnel: starts on the best strategy and playback is no worse than before patch 31.
- [ ] Bottom navbar is compact and centred, and still clears the three-button Android navigation bar.
- [ ] Release run: no `.sha256` asset on the GitHub Release; the run summary lists the signing certificate SHA-256.
- [ ] Install on the affected phone. If Play Protect still blocks it as an unknown developer, follow the developer-verification steps in `RELEASE.md` (free limited distribution account) and report the exact dialog text.

### Patch 32 responsive layout, bundled strategies, persistent bypass notification

Not yet device-verified. Full detail in `CHANGELOG.md`. Requires patches 30 and 31.

- [x] **Navigation bar overlap fixed at the root:** `targetSdk = 35` makes Android 15+ enforce
  edge-to-edge; `Scaffold`/`TopAppBar` reserve insets themselves but the hand-built
  `GlassBottomBar` did not, so the opaque three-button bar was drawn over it. It now applies
  `WindowInsets.navigationBars` padding.
- [x] Responsive UI: `enableEdgeToEdge()` (same model on every supported Android, correct
  system-bar icon contrast), keyboard-aware content (`imePadding` + `adjustResize`), no more state
  loss on rotation/window resize (`configChanges` declared; locale/dark mode/font size still
  recreate deliberately), and screen content centered and capped at 640dp wide.
- [x] All 72 strategies at cold start: a snapshot of the community list (60 lines, all accepted by
  the strict parser, zero overlap with the 12 built-ins) ships as an APK asset; "Update" still
  downloads fresh ones on top. A JVM test guards the asset (every line parses, total stays 72).
- [x] The bypass notification stays up while the tunnel runs (re-posted immediately if dismissed,
  self-healing check every 3s) and has an "Update" action that re-tests the strategies and
  reconnects only if a different verified chain is found.
- [ ] **Needs a real device to confirm:** with three-button navigation the bottom bar sits fully
  above the system bar, and with gesture navigation it still looks right; the keyboard does not
  cover the link field; rotating the phone keeps the current screen and typed link; landscape is
  centered; a fresh install shows 72 candidates before any Update; the notification returns after
  being swiped away; "Update" runs, shows progress, and either reconnects or leaves the tunnel
  running; YouTube keeps working after an Update-triggered reconnect. Caveat to check: while an
  Update search runs, a download that needs the bypass falls back to the direct connection (only
  one strategy-test engine can run at a time).

---

## Deliberately out of scope

- Playlist/batch URL semantics beyond the existing explicit queue of individual videos.
- Cross-device queue sync or a required backend.
- Custom YouTube extraction, anti-bot bypass, BotGuard or PO-token implementation.
- Automatic restart of interrupted downloads after process death. Recovery is intentionally explicit so the app never begins network work unexpectedly after Android recreates the process.

The former lowercase `roadmap.md` audit has been consumed by patch 25 and remains deleted. Its still-relevant findings are either implemented above, converted into a verification gate, or explicitly superseded by later user decisions. Future work should be added to this single `ROADMAP.md` only.
