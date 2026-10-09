# Handoff Snapshot

The current state of Kinescope for anyone (human or AI agent) resuming work. It is rewritten in place, not
appended to: per-patch history lives in `CHANGELOG.md`, open work in `ROADMAP.md`, process rules in `AGENTS.md`,
product constraints in `CLAUDE.md`. Where this file and the code disagree, the code wins; fix this file.

State as of patch 39 (2026-10-04).

## What Kinescope is

A personal Android app (`com.kinescope.app`, Kotlin, Jetpack Compose Material3) for downloading single YouTube
videos and Instagram Reels at home so they can be watched offline during work trips to a network-restricted
region. Sideload-only: no Google Play, no backend, no required paid service. Every download goes through
`yt-dlp` via `youtubedl-android 0.18.1`; the app is a UI and orchestration layer and never contains its own
extraction logic. Videos are fetched at home on a normal residential connection; an optional bundled DPI-bypass
engine (ByeDPI) helps when the current network blocks YouTube.

## State

**Confirmed on the user's device:** builds succeed (local and GitHub Actions); install, share/paste, queue,
download, offline playback and background persistence; the DPI bypass (strategy search, ranking, download through
the bypass); the compact log and Copy report; Instagram Reels download and play (after patch 38).
**Implemented but not yet confirmed on a device:** patch 36 UX/lifecycle changes, the Instagram sign-in,
`/share/` resolution and error texts (patches 37-38), and patch 39 (the strategy-search hold fix, the shared
sign-in screen, the cleanup). The exact checklist is `ROADMAP.md`; do not tick a box without the user's report.
**Open outside the code:** the Play Protect "App blocked" dialog on install (`RELEASE.md`), and `youtubedl-android`
not yet being 16 KB page-size compatible (upstream).

## Architecture in one page

- **Journal first.** `DownloadJobStore` (an `AtomicFile` JSON journal) is the source of truth across process
  death; a job is persisted before the foreground service is dispatched. `DownloadQueueBus` is only the live
  `StateFlow` projection for the UI. After process death a non-terminal job returns as `INTERRUPTED` and needs an
  explicit Resume; nothing restarts a transfer by itself. The site of a job is derived from its canonical URL
  (`StoredDownloadJob.mediaSource`), so the journal has no site field; the journal key is `mediaId` (the bare
  YouTube id, `ig:<shortcode>` or `igs:<token>`).
- **One worker.** `DownloadService` runs a single serialized worker with per-job workspaces. Pause keeps partial
  files, Resume reuses them, Stop deletes the workspace. `EngineController` is the one lock around yt-dlp/ffmpeg
  initialization, execution and self-update. Publication is two-phase through `MediaStorage` (`IS_PENDING`
  committed before the private source is deleted; the byte copy is verbatim, so a bad video was already bad when
  yt-dlp produced it). Queue lifecycle shown to the user: Queued, Preparing, Downloading, Finishing, Processing,
  Saving, then the file appears in Library (`CJM.md`, "Patch 36").
- **Links.** `MediaUrlParser` is the single entry point for typed, pasted and shared text; it delegates to the
  strict `YouTubeUrlParser` and `InstagramUrlParser` (exact host allowlists, canonical form, tracking parameters
  dropped, playlists/profiles/stories rejected). `InstagramShareResolver` follows only the HTTP redirect of
  `instagram.com/share/...` links (no body read, no cookie sent, every hop re-validated).
- **Recovery, YouTube.** A bounded chain: plain run, nightly yt-dlp refresh, web_safari over IPv4, android_vr
  logged out; failures that only a sign-in can fix park the job as a resumable Pause. The route ladder
  (`BypassRoutes`, `ChainPlanner`, `RouteScoreboard`) moves between verified bypass strategies and the direct route;
  `RunMonitor` judges a run from yt-dlp's own output and received bytes (stall, slow route).
- **Recovery, Instagram.** The plain run, then at most one retry after a nightly refresh, only when
  `DownloadErrorClassifier.isRecoverableInstagram` says it can help. A login-required Reel parks as PAUSED
  (`FailureKind.INSTAGRAM_LOGIN`) and signing in resumes it. Format: H.264 first (`InstagramFormats`), because
  yt-dlp ranks the VP9 DASH streams (the only labelled ones) above the unlabelled H.264 files and a VP9 MP4 can
  play as sound with a black picture. Output name `<uploader> - <id>`.
- **Sessions.** Optional signed-in WebView sessions for YouTube and Instagram. `WebSessionLoginScreen` (one screen,
  driven by a `WebSessionSite`) captures the WebView cookies; `CookieJarFile` writes the Netscape file yt-dlp reads
  with `--cookies`; `YouTubeAuth` / `InstagramAuth` own the files and flags. No password is seen or stored. YouTube
  uses a user agent without the `; wv` marker (Google rejects it); Instagram uses the stock one.
- **Bypass.** A vendored MIT ByeDPI C engine (`app/src/main/cpp/byedpi/`, unmodified) behind Kinescope's own JNI
  glue. Its C globals force one engine per process: `:dpi` (searches and downloads, one at a time) and `:dpi_vpn`
  (the YouTube-only `VpnService` tunnel through `hev-socks5-tunnel`). `DpiStrategyParser` is an allowlist (no listen
  address, file path or connect target); never weaken it. `DpiSearchService` runs the strategy search as a
  foreground service and steps aside while a download holds the engine (`DpiSearchController.holdForDownload` /
  `releaseDownload`, released in a `finally`).
- **Diagnostics.** `AppLog` is a rotating private journal: one line, `key=value` tokens, under 220 characters, no
  URLs, no cookies; `DiagnosticSanitizer` redacts URLs, YouTube and Instagram cookie values and private paths;
  `DiagnosticReport` adds the header for Settings -> Copy report. Backup is disabled.
- **UI.** Home (queue + library, bypass card), Add, Settings, Logs (five taps on the Settings icon), sign-in
  screens. English default, Russian in `values-ru`; every string exists in both.

## File map

All runtime Kotlin lives in `app/src/main/java/com/kinescope/app/`. `DocumentationTest` fails the unit-test run if
this list misses a source file; keep it complete.

Downloads and queue
- `DownloadService.kt` -- foreground single-worker queue, recovery chains, route ladder, watchdog, notifications, publication, Android 15 timeout.
- `DownloadJobStore.kt` -- durable journal, process-death normalization, workspaces.
- `DownloadQueueBus.kt` -- live job/progress projection; `JobState`, `FailureKind`.
- `DownloadErrorClassifier.kt` -- pure classification of yt-dlp failures (YouTube and Instagram).
- `QualityPresets.kt` -- stable quality ids and bounded yt-dlp format selectors (H.264 + AAC first).
- `RunMonitor.kt` -- judges a running yt-dlp process (phase, speed, stall).
- `EngineController.kt`, `YtDlpUpdater.kt` -- the yt-dlp/ffmpeg lock; nightly update wrapper.
- `MediaStorage.kt` -- MediaStore publish/list/delete, MIME from the real output.
- `Settings.kt` -- SharedPreferences for quality, storage folder history, update timestamp.
- `AppIntents.kt` -- notification tap target (brings the single-task activity to Home).
- `YtOfflineApp.kt` -- `Application`: journal recovery, engine readiness, update cadence, crash log (main process only).

Links and sites
- `MediaSource.kt` -- `MediaSource`, `StoredDownloadJob.mediaSource`, `InstagramFormats` (selector).
- `MediaUrlParser.kt`, `YouTubeUrlParser.kt`, `InstagramUrlParser.kt`, `InstagramShareResolver.kt` -- link handling.
- `CookieJarFile.kt`, `YouTubeAuth.kt`, `InstagramAuth.kt` -- cookie parsing/writing and the two sessions.
- `WebSessionLogin.kt` -- the shared WebView sign-in screen and the two `WebSessionSite` definitions.

UI
- `MainActivity.kt` -- navigation, Home/Add/Settings/Logs screens, queue rows, banners, library actions.
- `BypassSettings.kt`, `StrategyPicker.kt` -- the bypass section of Settings and the manual strategy list.
- `Theme.kt` -- Material3 tokens, Inter/Lora via the Google Fonts provider, shapes (`design.md`).

Network bypass
- `DpiNative.kt`, `DpiEngineService.kt`, `DpiEngine.kt` -- JNI binding, the `:dpi` host service, the bind/start/close client.
- `DpiStrategies.kt` -- allowlist parser and built-in strategies; `DpiStrategyStore.kt` -- bypass preferences (`DpiPrefs`) and the verified chain.
- `DpiSearch.kt`, `DpiSearchService.kt` -- the strategy search and its foreground service/controller; `DpiResultsStore.kt` -- last test results.
- `DpiBypass.kt` -- glue used by downloads and Settings (active chain, health check, applying results).
- `ChainPlanner.kt`, `BypassRoutes.kt`, `RouteScoreboard.kt` -- pure ordering rules for strategies and routes.
- `NetworkCheck.kt`, `NetworkState.kt` -- layered reachability probe through the engine; third-party VPN detection.
- `BypassVpnService.kt` -- `BypassVpnController` and the YouTube-only `VpnService` (`:dpi_vpn`).
- `app/src/main/java/hev/htproxy/TProxyService.java` -- JNI contract of the vendored `hev-socks5-tunnel`; `app/src/main/cpp/` -- ByeDPI sources, `dpi_jni.c`, `CMakeLists.txt`; `app/src/main/jniLibs/` -- the tunnel library with its license and provenance.

Diagnostics
- `AppLog.kt`, `LogFormat.kt`, `DiagnosticReport.kt`, `DiagnosticSanitizer.kt`.

Tests (`app/src/test/java/com/kinescope/app/`, pure JVM, run by `testDebugUnitTest`) cover link parsing, error
classification, the cookie file, the route and chain ordering, the run monitor, the log format, the strategy
parser/search/ranking/store, the network probe and the privacy redaction. Build and release: `.devcontainer/setup.sh`
(pinned Android command-line tools), `.github/workflows/build-release.yml` (the only CI, manual, signed release),
`RELEASE.md`.

## Facts verified against sources (do not re-derive)

- `youtubedl-android` 0.18.1 calls the progress callback for stdout lines only; yt-dlp's retry warnings and
  errors go to stderr. Its progress regex needs `ETA mm:ss`, otherwise progress and ETA stay -1. It adds
  `--js-runtimes quickjs:<path>` itself, so YouTube's JS challenge is solved on the device, CPU-bound and silent.
  `UpdateStatus` has the values `DONE` and `ALREADY_UP_TO_DATE` (read in the library's master source).
- The strategy engine keeps C globals, so each engine needs its own process; a search and a download conflict,
  which is why a background search yields to a download.
- Play Protect's "App blocked" dialog is documented only for internet-sideloaded apps that declare `RECEIVE_SMS`,
  `READ_SMS`, a notification listener or an accessibility service. Kinescope declares none and CI fails if that
  changes. Android developer verification is the other candidate (`RELEASE.md`).
- yt-dlp 2026.08.19 (Instagram): logged-in extraction reads the `sessionid` cookie and the media-info API and
  needs no impersonation; logged-out extraction wants curl_cffi, which `youtubedl-android` does not bundle (the
  only prebuilt bundle found is a paid fork). Its URL pattern excludes `/share/` and `/reels/audio/`. A dead
  session logs "account cookies are no longer valid" and then "empty media response". DASH video streams are
  labelled VP9; the progressive H.264 files have no codec label (yt-dlp issue 12394).
- Log format: `HH:mm:ss.d L tag message | err="..."`, legend in every report header. Tags: dl, byp, srch, vpn,
  job, eng, upd, auth, ui, lib, media. Keys: `j` job (8 hex), `p` yt-dlp profile, `src` site (`yt`/`ig`), `fmt`
  chosen format ids, `r` route (`s2/4` = strategy 2 of 4), `chk` live check, `hb` heartbeat, `end` run summary,
  `rotate` tunnel strategy switch.

## How to read the next report

1. `srch done ... pass=N` and the `#top` lines: did the search finish, how good are the best three?
2. For one job: `run` (`src`, `auth`), `ladder`, `chk`, `route`, `slow`, `stall`, `hb`, `end`, and for Instagram
   `fmt`. `ph=js` with a long idle means the QuickJS challenge, not the network. A `fmt` id of the form
   `dash-...vd+dash-...ad` means the VP9 route was taken.
3. `vpn probe` / `vpn rotate`: is the tunnel's strategy holding under load? `#state vpn=1`: a third-party VPN was on.

## Key learnings

- **Never write machine-specific config into a tracked file.** An absolute JDK path committed to
  `gradle.properties` worked in one Codespace and broke the first CI run; such settings belong in
  `$HOME/.gradle/gradle.properties`.
- **`applicationId` is fixed** (`com.kinescope.app`); changing it after the first device install is effectively
  irreversible.
- **Verify library APIs against the pinned version's own tagged source**, not a README snippet or a newer
  doc: a Compose BOM version that did not exist, a wrong nested-class assumption (`YoutubeDL.UpdateChannel`) and a
  `painterResource()` crash on an animated framework drawable were each caught only by primary sources. The Compose
  BOM 2024.11.00 pulls Material3 1.3.1; recheck call sites such as `LinearProgressIndicator(progress = Float)` if it
  is bumped.
- **A Codespace can carry several JDKs and Gradle has a hard JDK ceiling per version**, failing with a terse
  error; check the release notes early. A GitHub Actions runner is a different, clean environment.
- **XML comments cannot contain `--`**, and an unescaped ASCII apostrophe in a string resource breaks AAPT; the
  patch scripts check both.
- **Application.onCreate runs in every process** (`:dpi`, `:dpi_vpn`); guard process-wide work by process name.
- **Hold/release pairs need a `finally`.** The strategy-search hold taken per download was never released until
  patch 39; a counter test pins the semantics, the `finally` at the call site is what fixes the leak.
- **Format selection is not neutral.** yt-dlp's default sort prefers a labelled codec; always state the codec you
  want when a site labels only some streams.
- **A later patch can silently break an earlier patch's idempotency check** when it rewrites text the earlier one
  looked for; run patch chains several times from a clean copy (`AGENTS.md`).
- `pipefail` plus `yes |` raises SIGPIPE in setup scripts; dot-directories are skipped by some transfer tools
  (`git add -A` from a terminal keeps them).

## How to resume in a new conversation

1. Export a fresh repomix XML of the repository and attach it; it is the source of truth.
2. Read `CLAUDE.md`, this file, `ROADMAP.md`, `AGENTS.md`; `CHANGELOG.md` for the history of any area you touch.
3. Check the user's latest device or Actions report before changing any `[ ]` in `ROADMAP.md`.
4. If a device gate failed, start there; otherwise do not invent a new implementation phase.
