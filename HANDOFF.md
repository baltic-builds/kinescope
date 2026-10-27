# Handoff Snapshot

The current state of Kinescope for anyone (human or AI agent) resuming work. It is rewritten in place, not
appended to: per-patch history lives in `CHANGELOG.md`, open work in `ROADMAP.md`, process rules in `AGENTS.md`,
product constraints in `CLAUDE.md`. Where this file and the code disagree, the code wins; fix this file.

State as of patch 53, with the October 2026 v.3 verification fixes (2026-10-25).

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
`/share/` resolution and error texts (patches 37-38), patch 39 (the strategy-search hold fix, the shared
sign-in screen, the cleanup), the file split of patch 40, and the tablet and TV layouts of patch 41 (audited and
hardened in patch 42), the build and packaging changes of patch 43, the tunnel traffic check of patch 44, the Home bypass dashboard of patch 45, the learned strategies with the
25-day upkeep of patch 46, the first implementation of the outside tablet/TV UI audit (patch 48), and the overflow
sweep with the TV-detection fix of patch 49, and the explicit app theme of patch 50 (no native action bar), and the Home dashboard sprint of patch 51 (named
strategies, the YouTube bypass switch, the app behind "Unblock YouTube", the power-saving prompt, the mandatory
first check), and the Home/Queue rework of patch 52 (one YouTube switch, the queue as a destination, the five-entry navigation whose `+`
downloads the clipboard link), and the strategy router of patch 53 (separate Wi-Fi and
mobile-data records, one rating from the test and real use, borrowing and switching with the connection; `STRATEGY_ROUTER.md`), and the
out-of-band October changes (the recovery path that does not accept a route that fails its health probe, the TUN closed at once on stop,
Home with one tunnel toggle, an automatic toggle and "Watch YouTube", manual choice at the bottom of Settings, the orange/gray
`KinescopeSwitch`; `CHANGELOG.md`), and patch 54 (the reachability verdict, the IPv4-only fallback, the on-demand search at tunnel start), and
patch 55 (checks that stop at 5 working / 3 perfect, one check per connection class and app version, the two-a-day random check, the
ranking with its frozen bottom 15, the heartbeat line, the hints panel, the theme tokens). The exact
checklist is `ROADMAP.md`; do not tick a box without the user's report.
**CI:** the release workflow was red at patch 42 (`AdaptiveLayoutTest` still referenced the removed
`WidthClass`). Patch 43 fixes it, pins the NDK/CMake, and restructures the workflow (validate, tests + lint,
then the signed build; reports uploaded on failure). It counts as green only after the user reports a run.
**Open outside the code:** the Play Protect "App blocked" dialog on install (`RELEASE.md`), and `youtubedl-android`
not yet being 16 KB page-size compatible (upstream).

## Architecture in one page

- **Journal first.** `DownloadJobStore` (an `AtomicFile` JSON journal) is the source of truth across process
  death; a job is persisted before the foreground service is dispatched. `DownloadQueueBus` is only the live
  `StateFlow` projection for the UI. After process death a non-terminal job returns as `INTERRUPTED` and needs an
  explicit Resume; nothing restarts a transfer by itself. The site of a job is derived from its canonical URL
  (`StoredDownloadJob.mediaSource`), so the journal has no site field; the journal key is `mediaId` (the bare
  YouTube id, `ig:<shortcode>` or `igs:<token>`).
- **One worker.** `DownloadService` runs a single serialized worker with per-job workspaces; it owns the service lifecycle and the order of a job's steps, and delegates to `DownloadNotifier`, `JobLifecycle`, `RecoveryChain` -> `RouteLadder` -> `YtDlpRun`, and `DownloadPublisher` (each a `ContextWrapper` around the service). Pause keeps partial
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
  `releaseDownload`, released in a `finally`). The tunnel also watches the YouTube app's own traffic through the
  bridge counters (`TunnelFlow`, patch 44): three 10 s windows in a row in which the app sends and almost nothing
  comes back switch the strategy, because the 30 s engine probe cannot see a failure between the app and the engine.
- **Diagnostics.** `AppLog` is a rotating private journal: one line, `key=value` tokens, under 220 characters, no
  URLs, no cookies; `DiagnosticSanitizer` redacts URLs, YouTube and Instagram cookie values and private paths;
  `DiagnosticReport` adds the header for Settings -> Copy report. Backup is disabled.
- **UI.** Home (bypass dashboard, its "Watch YouTube" button, banners, the hints panel), Queue (its own screen since patch 52), Add (`+` opens it only when
  the clipboard holds no link; a shared link lands there too), Library (its own screen since patch 51), Settings, Logs (Settings -> Diagnostics; five taps on the
  Settings icon still open it), sign-in screens. English default, Russian in `values-ru`; every string exists in both.
- **Adaptive layout (patch 41).** One APK for phones, tablets and Android TV. `AdaptiveRoot` classifies the window
  (`adaptiveSpecFor`, Material breakpoints 600/840dp) and provides `LocalAdaptive`; screens read it. Phone in
  portrait: bottom bar, one column. Medium/expanded windows (patch 48: never a compact-width one, however short): a
  navigation rail with labels and, when 700dp of content fits, two panes (Settings: general |
  bypass; since patch 52 Home is one centred column of at most 640dp). TV (device report or Settings -> Interface override): rail with labels, an edge margin of 0-5% of the
  window (a setting, default 5% = 48dp x 27dp on 960x540dp), text x1.15, a `FocusRing` around every control, no
  touch-only gestures. Heights of the controls the layout sizes itself are tokens (`controlHeightDp`, `headerHeightDp`)
  used as MINIMUMS: never give a button with text a fixed `height`, and put a group of buttons in a `FlowRow`, not a
  `Row`. The bottom bar and the rail are one component (`NavEntry`, `NavButton`, `GlassStrip` in `AppChrome.kt`). Automatic TV detection is the pure `looksLikeTelevision` (TV UI
  mode, leanback, or no touch screen unless the device is PC-type, patch 49). A new composable that can be focused must be wrapped in `FocusRing`; layout decisions go
  through `AdaptiveSpec`, not through ad-hoc `if (isTv)` checks. `AppFrame` is ONE structure for every layout
  (a Scaffold with empty bars in the rail layout, a Row, a Column, a single call of the screen content): a
  second branch around the content would rebuild every screen when a phone is rotated.
- **Home dashboard (patch 45, redrawn in patches 51, 52, the out-of-band October change and patch 55).** The hero card on Home
  (`BypassDashboard`) shows the state of the YouTube bypass with ONE on/off switch (the YouTube-only tunnel: start / stop, the strategy
  position, `TunnelTraffic.verdict` from `BypassVpnController.traffic`) and, under it, a **heartbeat line** (`HeartbeatLine`, `DashPulse`):
  a green pulse that travels along the line while the bypass carries traffic, a red flat line when the connection is lost (the tunnel is up
  and nothing comes back, or it stopped on an error), a muted flat line when it is off or starting. There is NO speed on the dashboard
  (patch 55 removed the download speed and the tunnel's rate and everything that measured them), no strategy picker and no stars (the
  drop-down went with the October change; the manual choice is the last section of Settings, shown when the automatic switch is off for the
  connected class). Under the tunnel row: the automatic switch (on = no pin; switching it off pins the strategy in use, `DpiPrefs.pin`; on =
  `DpiPrefs.unpin`). Below the card: **Watch YouTube** (`onOpenApp`) and, after the banners, the **hints panel** (`HintsPanel`: seven short
  semi-transparent lines about how the router works and what `+` does; it has no close button by design). The queue is not on Home.
  `+` in the navigation (`addFromClipboard` in `MainActivity.kt`) reads the clipboard on the tap and enqueues a valid link at the
  default quality; with no link it opens the Add screen. The DOWNLOADS switch (`DpiPrefs.enabled`, ON by default since patch 51) is in Settings only. Every
  rule lives in the pure `bypassDashboardModel`; the composable maps state in and draws. Preferences are read when a watched
  state changes, not through a live observer. The heartbeat is the one continuous animation in the app and is tied to a MEASURED state; it holds still
  when the system's animations are off (`design.md`, rule 6).
- **Strategy names (patch 51) -- THE RULE.** Every strategy has a colour name from `R.array.strategy_colors` (EN and RU, 100
  colours, most common first). A strategy takes the FIRST palette colour no other known strategy has, in the order strategies
  are first met (built-ins, then the community list); the assignment is stored (`DpiPrefs.nameBook`, `StrategyNames`) and never
  changes while the strategy stays in the list; a strategy that leaves the list (and is not in the chain or pinned) frees its
  colour; the palette is append-only (never reorder or remove, add at the end in both languages with equal counts; the unit test
  and lint check it); beyond the palette a number is added ("Red 2"). Names are shown in the manual picker (Settings) and the pin toast.
- **The strategy list mirrors its source (patch 51).** `DpiStrategyStore.candidates` = built-ins + (the downloaded community list
  if there is one, else the bundled snapshot), never both (`mergeStrategyLists`). Source today: 60 strategies; built-ins: 12.
- **First install and updates (patches 51 and 55).** `AppUpdate.onProcessStart` (in `YtOfflineApp`) compares the version code with the last
  seen one: a fresh install and an update are treated alike. yt-dlp is refreshed at once (`YtOfflineApp`); the community strategy list is
  refreshed once per app version by `AppUpdate.refreshStrategyListOncePerVersion`, called by `DpiSearchService` just before a check; and the
  strategies are checked ONE CONNECTION CLASS AT A TIME, each class once per app version (`ClassCheckPolicy`, `DpiPrefs.checkedVersion`): the
  class the phone is on at the first launch, the other the first time the phone is on it with the app open (`MainActivity` watches
  `NetClassWatcher.state`). A check ends at `SearchGoal.STANDARD` (5 working strategies, at least 3 perfect) or when the list runs out. Only the
  very first check of a fresh install runs in `initial` mode: mandatory, no Stop anywhere, Home says "Please wait while we update the
  strategies."; after an update it runs in `check` mode, quietly, and a download that starts meanwhile uses what is stored. The 25-day upkeep
  of patch 46 and the "app updated" notice of patch 51 no longer exist; `MaintenanceSchedule` keeps only the yt-dlp calendar.
- **The app behind "Unblock YouTube" (patch 51).** `Settings.getYoutubeApp` (default the official YouTube app) is opened by
  `YoutubeApps.open` and is the one app `BypassVpnService.buildAppTunnel` lets through; Settings lists the apps that open youtube.com
  links (manifest `<queries>`). A browser opens the YouTube site; its whole traffic goes through the tunnel while it runs.
- **Power saving (patch 51).** `BatteryOptimization`: the system exemption dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, sideload-only), then
  the list of apps' settings, then the app's system page. A pop-up on the second launch (once) and a Settings section.
- **Learned strategies (patch 46).** `StrategyMemory` (pure) keeps, per strategy, faded sums of
  good and bad evidence: a finished download or a `tun proven` window counts 1.0 good, a failure while another route finished the job 1.0 bad (it weighs double in the score), and all
  evidence halves every week (patch 53: only real use is recorded here; what a search found is the test half of the rating). `DpiPrefs.promoteVerified` (success) and `DpiPrefs.quarantine` (failure) feed it,
  `DpiBypass.applySearchResults` adds the search's own findings, and `ChainPlanner.merge` / `promote` put the
  learned-good strategies into the chain and order the pool by score, so the fallbacks are in order of how well they
  have worked (the chain holds six). The record is one preference string per connection class (`DpiPrefs.learned`; patch 53).
  `DpiPrefs` makes the chain, quarantine and record changes take turns with one lock; the chain size is `ChainPlanner.MAX_CHAIN`.
  Patch 55 retired the 25-day quiet re-check of this patch (`MaintenanceSchedule.isDue`, `MODE_BACKGROUND`, the metered-network wait).

- **Strategy router (patch 53).** Full account: `STRATEGY_ROUTER.md`. Everything about a strategy is kept per connection class
  (`NetClass`: Wi-Fi/Ethernet, or mobile data and metered Wi-Fi): the verified chain, the pin, the proven strategy, the quarantine, the
  learned record and the last test results. The Wi-Fi class uses the pre-patch-53 preference keys (no migration); mobile has `_mobile`
  keys. `StrategyRating` blends the last test (fading, 14-day half-life) with real use (`StrategyMemory`) into one 0-100 rating; the
  order of every chain and of the class's whole ranking come from it. `StrategyRouter` decides the chain after a search, a success
  (`afterSuccess`: the winner leads), a failure (`afterFailure`: the loser moves down at once) and for a class with no chain (`seed`:
  it borrows the other class's, marked `seeded`, time 0). `NetClassWatcher` follows the default network; `BypassVpnService`
  reconnects on the new class's chain after the class has differed for four 500 ms ticks (`NetSwitch`), without counting it as a
  failure; `RouteLadder` and `RouteScoreboard` work per class. A search whose connection changed class is dropped
  (`DpiBypass.recordSearch`). Not confirmed on a device.
- **Strategy router, patches 54 and 55.** Full account: `STRATEGY_ROUTER.md`. (1) *Reach verdict* (`DpiBypass.reach`, `Reach`): a failure at the
  CONNECT stage with SOCKS reply 0x03/0x04 (`StageResult.isReachFault`) means the engine could not reach the host, which says nothing about the
  strategy; the same strategy is retried with the engine's `-X` (IPv4 only), a pass is remembered per class (`DpiPrefs.ipv4Only`, applied by
  `DpiEngine`), a second failure is NO_ROUTE (a message, a direct DNS/TCP line per IP family in the log, nothing stored or blamed). Results made
  only of such faults are not stored (`StrategyResult.connectFault`, `DpiBypass.withoutReachFaults`). (2) *Goal-bound searches*
  (`SearchGoal`: `STANDARD` 5 working + 3 perfect for the first check, the check after an update and Settings -> Test; `QUICK` 2 working for a
  tunnel that is starting and has nothing that works): `DpiBypass.search` walks `searchOrder`, the current chain and then `StrategyRank`
  order. (3) *Ranking* (`StrategyRank`, derived from ratings and test times, not stored): good evidence, never tested, did badly with the
  last failure at the very bottom; the last 15 of the ones that did badly and were tested less than 75 days ago are frozen and come last.
  (4) *Per-class checks* (`ClassCheckPolicy`): once per app version and class. (5) *Two-a-day random check* (`RandomCheckPolicy`, `RandomCheck`,
  started by `BypassVpnService` once the tunnel is up): two random strategies of the class, not the one in use, not frozen; a pass joins the
  chain by rating, a failure leaves it (`DpiPrefs.removeFromChain`) and sinks to the bottom of the ranking. Not confirmed on a device.

- **Recreation (patch 48).** `MainActivity` keeps `uiMode` out of `configChanges` on purpose (the system-bar icon colours
  are set once in `onCreate`), so a dark-mode or language change recreates the activity. The current screen, the typed
  link, the quality, the folder text and the strategy list's open state are `rememberSaveable`; the launching intent is
  handled only when `savedInstanceState == null`. State that should survive that goes in `rememberSaveable`, not `remember`.

## File map

All runtime Kotlin lives in `app/src/main/java/com/kinescope/app/`. `DocumentationTest` fails the unit-test run if
this list misses a source file; keep it complete.

Downloads and queue
- `DownloadService.kt` -- the foreground single-worker queue: Android service lifecycle, command handlers, worker thread, the order of one job's steps (`runJob`), the public companion API, Android 15 timeout.
- `JobLifecycle.kt` -- `JobLifecycle` (journal/queue transitions, honored Pause/Stop), `JobControls`, `ControlAction`, `ControlledStop`, `AlreadyHandledFailure`.
- `RecoveryChain.kt` -- `RecoveryChain` (the bounded attempt chain) and `RecoveryProfile`.
- `RouteLadder.kt` -- `RouteLadder` (bypass route ladder, wait for a strategy search).
- `YtDlpRun.kt` -- `YtDlpRun` (one yt-dlp execution with the stall/slow watchdog) and the `Bypass*Exception` types.
- `DownloadPublisher.kt` -- output selection and the two-phase MediaStore commit.
- `DownloadNotifier.kt` -- foreground and completion notifications.
- `DownloadErrors.kt` -- `friendlyDownloadError`, the user-facing failure text.
- `DownloadJobStore.kt` -- durable journal, process-death normalization, workspaces.
- `DownloadQueueBus.kt` -- live job/progress projection; `JobState`, `FailureKind`.
- `DownloadErrorClassifier.kt` -- pure classification of yt-dlp failures (YouTube and Instagram).
- `QualityPresets.kt` -- stable quality ids and bounded yt-dlp format selectors (H.264 + AAC first).
- `RunMonitor.kt` -- judges a running yt-dlp process (phase, speed, stall).
- `EngineController.kt`, `YtDlpUpdater.kt` -- the yt-dlp/ffmpeg lock; nightly update wrapper.
- `MediaStorage.kt` -- MediaStore publish/list/delete, MIME from the real output.
- `Settings.kt` -- SharedPreferences for quality, storage folder history, update timestamp, interface mode.
- `AppIntents.kt` -- notification tap target (brings the single-task activity to Home).
- `YtOfflineApp.kt` -- `Application`: journal recovery, engine readiness, update cadence, crash log (main process only).

Links and sites
- `MediaSource.kt` -- `MediaSource`, `StoredDownloadJob.mediaSource`, `InstagramFormats` (selector).
- `MediaUrlParser.kt`, `YouTubeUrlParser.kt`, `InstagramUrlParser.kt`, `InstagramShareResolver.kt` -- link handling.
- `CookieJarFile.kt`, `YouTubeAuth.kt`, `InstagramAuth.kt` -- cookie parsing/writing and the two sessions.
- `WebSessionLogin.kt` -- the shared WebView sign-in screen and the two `WebSessionSite` definitions.

UI
- `MainActivity.kt` -- the activity, `AppSection`, `KinescopeApp` (state, navigation, handlers).
- `AdaptiveLayout.kt` -- window classification (`AdaptiveSpec`, `adaptiveSpecFor`), TV detection, `InterfaceMode`, `LocalAdaptive`, `AdaptiveRoot`, `FocusRing`.
- `AppChrome.kt` -- `AppFrame` (top bar + bottom bar, or header + navigation rail; five destinations since patch 52: Home, Queue, `+`, Library, Settings), responsive column.
- `HomeScreen.kt` -- Home: the bypass dashboard slot, the banners (errors, sign-in) and the hints panel; one column since patch 52.
- `QueueScreen.kt` -- the download queue as its own screen (patch 52): the full list, Pause / Resume / Stop per row, the empty text.
- `BypassDashboard.kt` -- the Home hero: bypass and automatic-selection switches, network class, tunnel health/traffic, and "Watch YouTube". Manual strategy selection is in Settings.
- `LibraryScreen.kt` -- the library of finished downloads as its own screen (patch 51).
- `BypassDashboardModel.kt` -- pure rules of that card (`bypassDashboardModel`, `DashPulse`) and the shape of one heartbeat (`heartbeatSample`).
- `HeartbeatLine.kt` -- the heartbeat line under the YouTube switch (patch 55): Canvas drawing, driven by `withFrameNanos`, still when the system's animations are off.
- `HintsPanel.kt` -- the semi-transparent hints panel at the bottom of Home (patch 55).
- `QueueAndLibrary.kt` -- the queue-job row (a Stop button on every device since patch 52), the library-file row and the library file actions.
- `QuickAddScreen.kt`, `SettingsScreen.kt`, `LogsScreen.kt` -- the Add, Settings and Logs screens.
- `BypassSettings.kt`, `StrategyPicker.kt` -- the bypass section of Settings and the manual strategy list.
- `YoutubeApps.kt`, `YoutubeAppSettings.kt` -- the app behind "Watch YouTube": the candidates, the choice, opening it; its Settings section (patch 51).
- `BatteryOptimization.kt` -- the power-saving exemption: intents, the pop-up rule, the dialog and the Settings section (patch 51).
- `StrategyNames.kt` -- the colour names of the strategies (pure rule + the name book) (patch 51).
- `AppUpdate.kt` -- fresh install / update classification (patch 51) and the once-per-version community-list refresh (patch 55).
- `KinescopeSwitch.kt` -- shared Material 3 switch with explicit orange-on and gray-off tokens in both themes.
- `Theme.kt` -- Material3 tokens, Inter/Lora via the Google Fonts provider, shapes (`design.md`).

Network bypass
- `DpiNative.kt`, `DpiEngineService.kt`, `DpiEngine.kt` -- JNI binding, the `:dpi` host service, the bind/start/close client.
- `DpiStrategies.kt` -- allowlist parser and built-in strategies; `DpiStrategyStore.kt` -- bypass preferences (`DpiPrefs`: per connection class the verified chain, pin, quarantine and learned record; the shared upkeep calendar).
- `DpiSearch.kt`, `DpiSearchService.kt` -- the strategy search (`SearchGoal`, `StrategyResult.works` / `perfect` / `connectFault`) and its foreground service/controller (modes manual, initial, check, test; `RandomCheck`, patch 55); `DpiResultsStore.kt` -- last test results.
- `DpiBypass.kt` -- glue used by downloads and Settings (active chain, health check, `reach` verdict, `searchOrder`, applying results).
- `ChainPlanner.kt`, `BypassRoutes.kt`, `RouteScoreboard.kt` -- pure ordering rules for strategies and routes.
- `NetClass.kt`, `NetClassWatcher.kt` -- the connection class (Wi-Fi / mobile data), the rule for when the tunnel follows a change, and the flow that follows the default network (patch 53).
- `StrategyRating.kt`, `StrategyRouter.kt` -- the pure rating (last test + real use) and the router policy (the chain, `StrategyRank`, `RandomCheckPolicy`, `ClassCheckPolicy`); see `STRATEGY_ROUTER.md` (patches 53-55).
- `StrategyMemory.kt` -- pure record of which strategies really carried traffic (real outcomes only since patch 53; fading score, text form); `MaintenanceSchedule.kt` -- pure calendar of the yt-dlp update (25 days) and the day length the other calendars count in.
- `NetworkCheck.kt`, `NetworkState.kt` -- layered reachability probe through the engine; third-party VPN detection; the connection class (patch 53).
- `TunnelStartupPolicy.kt` -- checks verified route health before activation and only learns failed candidates after a successful fallback.
- `BypassVpnService.kt` -- `BypassVpnController` and the YouTube-only `VpnService` (`:dpi_vpn`).
- `TunnelFlow.kt` -- pure judge of the YouTube app's traffic through the tunnel (idle / flowing / one-way, from the bridge counters) and the snapshot the dashboard reads (`TunnelTraffic`: verdict and place in the chain; no speed since patch 55).
- `app/src/main/java/hev/htproxy/TProxyService.java` -- JNI contract of the vendored `hev-socks5-tunnel`; `app/src/main/cpp/` -- ByeDPI sources, `dpi_jni.c`, `CMakeLists.txt`; `app/src/main/jniLibs/` -- the tunnel library with its license and provenance.

Diagnostics
- `AppLog.kt`, `LogFormat.kt`, `DiagnosticReport.kt`, `DiagnosticSanitizer.kt`.

Tests (`app/src/test/java/com/kinescope/app/`, pure JVM, run by `testDebugUnitTest`) cover link parsing, error
classification, the cookie file, the route and chain ordering, the run monitor, the log format, the strategy
parser/search/ranking/store, the network probe, the privacy redaction, the window classification and the Home dashboard model. Build and release: `.devcontainer/setup.sh`
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
- `DpiBypass.activeChain` returns the verified chain when `DpiPrefs.isEnabled` is on OR the YouTube tunnel is active, so
  while the tunnel runs the downloads preference has no effect (Settings locks the switch then; the Home dashboard
  shows it on and locked). `RouteLadder` reads the chain once per attempt, so changing the preference never affects a
  download that is already running.
- Log format: `HH:mm:ss.d L tag message | err="..."`, legend in every report header. Tags: dl, byp, srch, vpn,
  job, eng, upd, auth, ui, lib, media. Keys: `j` job (8 hex), `p` yt-dlp profile, `src` site (`yt`/`ig`), `fmt`
  chosen format ids, `r` route (`s2/4` = strategy 2 of 4), `chk` live check, `hb` heartbeat, `end` run summary,
  `rotate` tunnel strategy switch. Patch 46 adds `tun proven`, the header tokens `learned=` and `search_age=`, and
  `#learn` lines (place in the chain, ok/bad weights, score). Patch 53 replaces `learned=` and `#learn` with `cls=`, `seeded=`, `rated=`, `#rate`
  and `#other` (`STRATEGY_ROUTER.md` section 9) and adds `net=` to the route, tunnel and ladder lines.

- Patch 54, read from the vendored ByeDPI source (`proxy.c`, `main.c`): `resolve()` calls `getaddrinfo` with `AF_UNSPEC` (or `AF_INET` when
  IPv6 is off) and `AI_ADDRCONFIG`, and copies only the FIRST address it returns; a failed lookup is reply 0x04 (`S_ER_HOST`); a connect error maps
  to 0x05 (refused), 0x04 (`EHOSTUNREACH`, `ETIMEDOUT`) or 0x03 (`ENETUNREACH`). `-X` / `--no-ipv6` sets `params.ipv6 = 0` (the option string is built
  from the option table, so it is accepted). The tunnel's `hev-socks5-tunnel` sends IP literals, so only by-name connects (the health check,
  yt-dlp's `socks5h`) go through that lookup.
- Patch 55, read from the androidx source (main): `lightColorScheme` / `darkColorScheme` take `surfaceContainerLowest` .. `surfaceContainerHighest`,
  `surfaceDim`, `surfaceBright`, `outlineVariant`, `inverseSurface`, `inverseOnSurface`, `inversePrimary` and the `secondary*` / `tertiary*` roles;
  material3 exposes `animation` as an api dependency and `foundation` (`Canvas`) with it, but the heartbeat uses only `withFrameNanos` from the
  runtime, so it does not depend on that.
- Patch 48, read from the androidx source (main): `FlowRow` is still `@ExperimentalLayoutApi` (opt in with
  `@OptIn(ExperimentalLayoutApi::class)`); `widthIn` uses `enforceIncoming`, so it must come BEFORE `fillMaxWidth`
  (after it the cap changes nothing); `Modifier.selectable(selected, enabled, role, onClick)`, `animateScrollBy`,
  `LazyListState.canScrollForward` / `canScrollBackward`, `layoutInfo.viewportSize`, `Key.DirectionDown`,
  `KeyEvent.key` / `.type` and `Modifier.onKeyEvent` exist with the shapes used. Put `onKeyEvent` BEFORE `focusable()`
  in the chain so it sees the keys of the focused node below it.
- Patch 50: an activity with no `android:theme` gets `Theme.DeviceDefault.Light.DarkActionBar` for a modern target SDK
  (a dark native action bar with the activity label); `Theme.DeviceDefault.Light.NoActionBar` exists in the framework.
  The app's theme is `Theme.Kinescope` (`res/values/themes.xml`); its window colour must equal the Compose background
  (`ThemeResourcesTest`). A new activity needs no theme of its own: the application theme applies.

## How to read the next report

1. `srch done ... pass=N` and the `#top` lines: did the search finish, how good are the best three?
2. For one job: `run` (`src`, `auth`), `ladder`, `chk`, `route`, `slow`, `stall`, `hb`, `end`, and for Instagram
   `fmt`. `ph=js` with a long idle means the QuickJS challenge, not the network. A `fmt` id of the form
   `dash-...vd+dash-...ad` means the VP9 route was taken.
3. `vpn probe` / `vpn rotate`: is the tunnel's strategy holding under load? `#state vpn=1`: a third-party VPN was on.
4. Patches 54-55: `#state ... v4only=1` means the engine runs IPv4-only on this class (learned); `chk=due` means this class has not been
   checked for the running app version; `rand=Nh` is the hours since its last random check. `vpn chk ... rc=4` is the SOCKS reply of a failed
   CONNECT; `byp reach net=... none ... dns=ok a4=N a6=N tcp4=... tcp6=...` is the no-route evidence (which IP family connects); `reach ... v4only=learned`
   is the IPv6 fallback working; `srch rand net=... works= fault=` is a random check; `byp start search net= found=` is the start-up search.

## Key learnings

- **Never write machine-specific config into a tracked file.** An absolute JDK path committed to
  `gradle.properties` worked in one Codespace and broke the first CI run; such settings belong in
  `$HOME/.gradle/gradle.properties`.
- **`applicationId` is fixed** (`com.kinescope.app`); changing it after the first device install is effectively
  irreversible.
- **Verify library APIs against the pinned version's own tagged source**, not a README snippet or a newer
  doc: a Compose BOM version that did not exist, a wrong nested-class assumption (`YoutubeDL.UpdateChannel`) and a
  `painterResource()` crash on an animated framework drawable were each caught only by primary sources. The Compose
  BOM 2024.11.00 pulls Material3 1.3.1; recheck call sites if it is bumped (1.3.1 deprecates
  `LinearProgressIndicator(progress = Float)`; the call sites use the lambda overload since patch 43).
- **A Codespace can carry several JDKs and Gradle has a hard JDK ceiling per version**, failing with a terse
  error; check the release notes early. A GitHub Actions runner is a different, clean environment.
- **XML comments cannot contain `--`**, and an unescaped ASCII apostrophe in a string resource breaks AAPT; the
  patch scripts check both.
- **Application.onCreate runs in every process** (`:dpi`, `:dpi_vpn`); guard process-wide work by process name.
- **Hold/release pairs need a `finally`.** The strategy-search hold taken per download was never released until
  patch 39; a counter test pins the semantics, the `finally` at the call site is what fixes the leak.
- **Move-only refactors are verifiable when the moves are mechanical.** Cut members out by anchor and keep the bodies verbatim (a `ContextWrapper` around the service lets code that calls `getString` or passes `this` as a Context move unchanged), audit the result line by line, and compile before and after. For Compose files without the Compose libraries, compile with name-resolving stubs for every imported symbol and compare the error profiles: a missing import or a `private`-in-file reference shows up as a difference.
- **A layout switch must move the content, not rebuild it.** Compose keeps state by call site: if the screen content
  is composed in two different branches (`if (rail) A { content() } else B { content() }`), crossing the breakpoint
  (a phone turned to landscape, a resized window) discards the state of every screen below, including a WebView
  sign-in. Keep one call site and vary only the siblings around it (patch 42).
- **Focus on TV is not free in Compose.** `Modifier.onFocusChanged` on a parent reports the combined state of the
  focus targets below it: `isFocused` when the nearest one is itself focused, `hasFocus` (without `isFocused`) when
  a descendant is. `FocusRing` relies on that. It is a wrapper composable on purpose: a `@Composable` or
  `composed` modifier factory may draw Compose lint findings (severity not checked) and `lintDebug` is a gate. Read
  from the androidx source (main), not from the pinned tag.
- **Format selection is not neutral.** yt-dlp's default sort prefers a labelled codec; always state the codec you
  want when a site labels only some streams.
- **A failure before the strategy acts is not the strategy's failure (patch 54).** Where a failure happens decides what it says: CONNECT
  with "unreachable" is the route, TLS or HTTP is the strategy. Storing the first as the second rated every strategy 0 on one bad network.
- **A check needs a goal, not a list (patch 55).** Scanning 72 strategies costs minutes and data to prove what five working ones already show;
  most of the list fails fast and cheaply, so the cost is in the passes (about 0.8 MB each), which the goal caps.
- **A gap in the brief goes in the changelog, not in a question (patch 55).** The patch states how each loose phrase was read ("working",
  "100%", "the very bottom", "first toggle of a launch") so the user can correct one rule without redoing the work.
- **A later patch can silently break an earlier patch's idempotency check** when it rewrites text the earlier one
  looked for; run patch chains several times from a clean copy (`AGENTS.md`).
- **`assembleDebug` does not compile the unit tests.** Patch 42 shipped a test that no longer compiled and only
  the release workflow (`testDebugUnitTest`) noticed. The local gate is the full one (`AGENTS.md`).
- **A pinned toolchain must be pinned in the build file.** The NDK was "required" in three docs and installed in
  two scripts, but `ndkVersion` was unset, so AGP's default was what actually built the native code until patch 43.
- `pipefail` plus `yes |` raises SIGPIPE in setup scripts; dot-directories are skipped by some transfer tools
  (`git add -A` from a terminal keeps them).

## How to resume in a new conversation

1. Export a fresh repomix XML of the repository and attach it; it is the source of truth.
2. Read `CLAUDE.md`, this file, `ROADMAP.md`, `AGENTS.md`; `CHANGELOG.md` for the history of any area you touch.
3. Check the user's latest device or Actions report before changing any `[ ]` in `ROADMAP.md`.
4. If a device gate failed, start there; otherwise do not invent a new implementation phase.
