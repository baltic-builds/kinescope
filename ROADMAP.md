# Roadmap

State after patch 46 (2026-10-17). This file tracks only what is still open: device verification gates,
technical debt and what is out of scope. How each item was closed lives in `CHANGELOG.md`; the state a new
session needs is in `HANDOFF.md`.

## Where things stand

- **Confirmed on the user's device:** the build succeeds; downloads work; the DPI bypass strategy search and its
  ranking work; the compact log and Copy report work; Instagram Reels download and play (reported working after
  patch 38, 2026-10-04). Earlier install, share/paste, queue, offline playback and background persistence are also
  confirmed.
- **Reported and not fixable from code:** the Play Protect "App blocked" dialog on install (see `RELEASE.md`).
- **Reported after patches 41-43 (2026-10-14):** the bypass "stopped working". The report holds no failure: the
  YouTube tunnel starts and stays up, and no check looked at the YouTube app's own traffic. Patch 44 adds that
  measurement (`tun win` lines) and a reaction to it; the cause is not established (`CHANGELOG.md`, Patch 44).
- **The release workflow was red at patch 42** (a unit test that no longer compiled); patch 43 fixes that and
  restructures the workflow. Nothing behind the failing task had run, so it is green only once the user reports a
  run (see "Release pipeline").
- **Patch 46 (2026-10-17) changes when the bypass upkeep runs.** The strategy re-check no longer follows an app
  update; the app remembers which strategies really carried traffic and keeps them in the fallback chain, ordered by
  how well they worked; the yt-dlp update and the strategy re-check now run every 25 days. None of it is confirmed on a
  device yet (see "Learned strategies and the 25-day upkeep" below).
- **Everything below needs the user's phone or a real GitHub Actions run.** Do not tick a box from code review, a
  local compile or a green build alone (`AGENTS.md`, "What done means").

## Decisions that remain unchanged

- `applicationId` / namespace: `com.kinescope.app`.
- Personal sideload distribution only; no Google Play requirement, no backend, no required paid service.
- All extraction stays inside `yt-dlp` through `youtubedl-android`; no custom signature deciphering, BotGuard,
  PO-token generation, Instagram scraping or anti-bot bypass.
- English is the default UI; Russian devices use `values-ru`. Every new string is added to both.
- The product flow is single-video download (YouTube video, Shorts, Live; Instagram Reel). Playlist expansion is
  not part of the roadmap.
- Optional YouTube and Instagram web sessions (embedded browser) are intended features; the older audit's
  "no authentication / no embedded browser" assumption stays superseded.

## Verification gates (open)

### Learned strategies and the 25-day upkeep (patch 46)
- [ ] Install this build over the previous one and open it: no "Updating strategies" popup, no search notification,
  and downloads and the YouTube tunnel work at once with the strategies already stored. Copy report shows
  `search_age=0d` (the 25 days count from the first launch of this build) and no `srch` search lines.
- [ ] After a download finishes through the bypass, Copy report shows a `#learn` line for the strategy it went through
  with `ok=1.00` and `learned=` in the `#state` line is at least 1. After a download that had to fall back to another
  route, that route has its own `#learn` line.
- [ ] A strategy that fails while a later route finishes the download is quarantined as before and its `#learn` line
  shows `bad=1.00`; in the `#s` list it now sits behind the strategies with a better record.
- [ ] YouTube tunnel: after the YouTube app has loaded a video for about a minute the report holds one
  `tun proven s=N/M` line and a `#learn` line for that strategy with a real-use success.
- [ ] The 25-day upkeep, tested by moving the phone's clock forward 26 days (automatic date and time off) and opening
  the app: Home shows "Re-checking in the background" with no popup, the report holds `list updated|same|failed ...`
  and the `DpiSearch done ...` summary, `search_age` returns to `0d`, and a `upd` line shows the yt-dlp update. A
  download started during that re-check begins at once. Set the clock back afterwards; stored times that lie in the
  future count as due, so one more upkeep runs.
- [ ] After the re-check the `#s` list holds up to six strategies: the best of the search and the learned ones,
  ordered by their record, not only by the search ranking.

### Home bypass dashboard (patch 45)
- [ ] Home shows the new card: header, the Downloads and YouTube app rows, "Check again". On a fresh install (or after
  clearing the app's data) it says "Not set up yet" with one "Check the ways" button; the first-run check shows
  "Checked N of M" with a bar and Stop, and Home returns to the normal card when it ends.
- [ ] The Downloads switch on Home: off and on again survives leaving and reopening the app, and Settings -> Network
  bypass shows the same position. A download started with it on shows `ladder` and `route ... ok` lines in the report;
  with it off the report shows a direct run (no `ladder` line).
- [ ] With a download running the switch still works, the line "Running downloads keep their current route..." is
  visible and the running download is not interrupted.
- [ ] Tap Unblock YouTube: Android's VPN consent (first time), the YouTube app opens, the card shows "Tunnel is on" with
  a speed (down and up) that moves while a video loads, and "Way N of M". The Downloads row is on and locked ("Stays on
  while the YouTube tunnel is running"). Stop on the card ends the tunnel and unlocks the switch.
- [ ] If the YouTube app sends but nothing comes back, the row turns to the warning text within about 10 s (it follows
  the `tun win` verdict); a later `flowing` or idle window clears it.
- [ ] The strategy "Update" from the tunnel notification and the quiet 25-day background re-check (patch 46) leave Home
  usable: the card keeps its controls and shows "Re-checking in the background" with a bar.
- [ ] Russian locale: no clipped or overlapping text at 360dp wide and at the largest font size; in dark mode the dots
  and the secondary text are readable on both card colors. Tablet and TV: the card sits at the top of the left pane or
  the column, and every control is reachable with the D-pad and shows the focus ring.
- [ ] Copy report shows `ui home bypass=on|off ...`, `ui home check start ...` and `ui home tunnel stop` lines for the
  taps above, and no URL or cookie.

### YouTube tunnel flow (patch 44)
- [ ] Home -> Unblock YouTube, open the YouTube app, load a video for about a minute, return and Settings -> Copy
  report. The report has `tun up ...`, then `tun win=10s v=...` lines. Record the verdicts and the numbers:
  `flowing` while the video plays means the tunnel carries it; three `one_way` windows in a row are followed by
  `rotate quarantined=1`; no `tun win` line with traffic means the YouTube app's packets do not reach the bridge.
  Whichever it is, it is the first real evidence about why the YouTube app does not work through the tunnel.
- [ ] Stop from the notification: the log ends with `tun end up=...s tx=...p/...B rx=...p/...B`.
- [ ] If the verdict is `one_way`: test the suspects one at a time (DNS through the tunnel, UDP/QUIC, IPv6; see
  `CHANGELOG.md`, Patch 44) rather than all at once, so the answer stays attributable.

### Instagram Reels (patches 37-39)
- [ ] Settings -> Instagram account -> Sign in: a normal login completes, also with 2FA or a checkpoint prompt.
  If Instagram refuses the embedded browser, record the exact behaviour; do not work around it with custom code.
- [ ] The Instagram app's "Copy link" (`/share/reel/...`) and Share -> Kinescope from the Instagram app both
  download. This is the one place where the app itself contacts instagram.com (`InstagramShareResolver`).
- [ ] Without a session a Reel either downloads or parks as a resumable Pause with the sign-in banner on Home;
  signing in resumes it by itself.
- [ ] Capture the verbatim yt-dlp text for: no session, expired session, a deleted Reel, a private account;
  compare with `DownloadErrorClassifier.classifyInstagram` and add the lines to `InstagramErrorClassifierTest`.
- [ ] Sign out removes the Instagram cookie file and the instagram.com WebView cookies; a saved YouTube session
  is untouched.
- [ ] Copy report shows yt-dlp 2026.08.19 or newer, `run ... src=ig auth=true` and `fmt j=...` (a plain id or an
  `avc1` pair is the intended outcome, `dash-...vd+dash-...ad` means the VP9 route), and no cookie value or URL.
- [ ] Two or three more Reels play with picture and sound; note any noticeably below the chosen height.

### Tablet and TV layouts (patch 41)
- [ ] A phone in portrait looks and behaves as before: bottom bar, one column, Home/Add/Settings/Logs.
- [ ] A tablet in portrait (about 800dp wide): navigation rail on the left, one wider column. In landscape (840dp or
  more): Home shows the queue and the library side by side, Settings shows two columns; both lists scroll on their
  own. Rotating keeps the typed link. Split screen or a resized window switches the layout without a restart.
- [ ] A phone in landscape: the rail replaces the bottom bar; two panes appear only when the window is wide enough.
  Rotating back and forth keeps the screen: unsaved text in Settings, the list position, and a half filled-in
  sign-in page (patch 42 made the frame one structure; this is the check that it worked).
- [ ] Settings -> Interface: Automatic / TV / Phone and tablet applies at once and survives a restart. Forcing TV on a
  phone shows the TV layout (rail with labels, larger text, margins); with a keyboard or D-pad the focus ring is
  visible on every control. Record which devices Automatic gets right and wrong.
- [ ] Android TV / Google TV / a set-top box (sideload with `adb install`): the app appears in the launcher with the
  banner. The D-pad reaches every control on Home, Add, Settings (both columns, the bypass section, the strategy
  list), Logs and the dialogs; the focus ring is always visible; nothing important is cut off at the screen edges;
  text is readable from the couch; Back behaves as on the phone.
- [ ] TV: a queue row shows Pause/Resume and a remove button (no swipe); a library row plays with OK and offers Share and
  Delete; the delete confirmation and the strategy notice can be answered with the remote.
- [ ] TV: the Add screen. Does focusing the link field with the D-pad open the on-screen keyboard, can focus leave the
  field, and does the keyboard's Go key start the download? A phone's keyboard Go key does the same.
- [ ] TV: Play on a box without a video player shows the "install a player" hint. Record whether the target box has
  a player; if it has none, decide on an in-app player (technical debt).
- [ ] TV: record whether the sign-in screens are usable with a D-pad (focus can enter and leave the WebView) and
  that a box without WebView shows the message instead of crashing.
- [ ] TV: record what Home -> Unblock YouTube does when only the TV edition of the YouTube app is installed.

### After the file split (patch 40)
- [ ] One pass through the app behaves exactly as before: Home (queue, library, banners, bypass card), Add (paste,
  clipboard prefill, quality chips), Settings (every section), Logs (five taps), both sign-in screens, and a YouTube and
  an Instagram download from enqueue to the library, including Pause/Resume/Stop and the completion notification.
- [ ] Copy report after those downloads still shows the usual tags (`dl`, `byp`, `srch`, `job`, `auth`...); a changed or
  missing tag would mean a moved log statement was altered.

### Sign-in screens (patch 39)
- [ ] The YouTube and the Instagram sign-in screens (now one shared screen) open on their own start pages, Back
  steps through the page history before it leaves, and "Use this session" saves the session and returns.

### Download UX (patch 36)
- [ ] During a download the queue row keeps its height and progress moves smoothly; at the end the row goes
  Downloading/Finishing -> Processing -> Saving and never sits at "Downloading 100%" while merging.
- [ ] After the MediaStore commit the row leaves the Queue and the file appears in Library without a refresh.
- [ ] Tapping the download or strategy-search notification brings the app to Home.
- [ ] The bottom navigation has three balanced 56dp targets and clears gesture and three-button navigation.
- [ ] A fresh install shows the strategy-check popup in the device language (Russian on a Russian device, English
  otherwise). Since patch 46 an app update no longer starts a re-check or shows the popup (see the patch 46 gates).
- [ ] Settings ends with `powered by ephedrine`.

### Network bypass and strategy search (patches 28-35)
- [ ] Download under bypass: a download completes, the report shows `route ... ok`; with every strategy dead the row
  ends with "Couldn't reach YouTube" after one pass, with no four-attempt loop.
- [ ] A route under 80 KB/s for 25 s is abandoned for the next one (at most two hops) and the download resumes
  rather than restarts; a later download starts on the route that worked.
- [ ] A strategy search that starts after a download is not held back (patch 39 fix of a never-released hold).
- [ ] The search keeps running while the app is in the background; its notification Stop button works; a search
  stopped early keeps its passes ("What it found so far is saved").
- [ ] Settings -> Choose strategy manually: Test runs one strategy, Use pins it, Choose automatically unpins.
- [ ] YouTube tunnel: tap Home -> Unblock YouTube, grant the Android VPN consent, the official YouTube app plays;
  Share to Kinescope downloads through the isolated `:dpi` engine while `:dpi_vpn` stays active; Stop from the
  notification; other apps are not routed. With a strategy that stops carrying traffic the log shows `probe fail`
  twice and then `rotate`.
- [ ] Third-party VPN on: the direct route is tried first; if YouTube asks for a bot check the row shows the VPN
  message. Report whether a later profile (web_safari IPv4, android_vr) got through.
- [ ] Layout: the bottom bar clears three-button navigation, the keyboard does not cover the link field, rotation
  keeps the screen and the typed link, landscape is centred, the bypass notification returns after a swipe.
- [ ] Settings -> Copy report puts a header plus at most 150 lines on the clipboard.

### Real-device flow
- [ ] Several ordinary YouTube downloads, including links that used to ask "Sign in to confirm you're not a bot":
  the bounded recovery chain completes or parks the job as a resumable Pause, never an endless loop.
- [ ] Optional YouTube session capture. If Google refuses embedded sign-in, record it as a platform limitation;
  do not weaken the no-custom-bypass rule. Once signed in, a real age-restricted video downloads.
- [ ] Pause a download, kill and reopen the app, Resume: partial data is reused and the job shows as `INTERRUPTED`
  after process death.
- [ ] Stop a queued and a running job: the workspace is removed and later entries continue. Swipe a queued, paused
  and running row end-to-start: each disappears; a row in SAVING cannot be swiped away.
- [ ] Queue 3-4 videos within seconds: every accepted job starts; resubmitting the same video is rejected visibly.
- [ ] Private, age-restricted and unavailable videos give the typed error messages.
- [ ] Queue in airplane mode: the job becomes recoverable `INTERRUPTED` and resumes when connectivity returns.
- [ ] One large or slow download and one audio-only download: file type, name and library metadata are right and no
  pending MediaStore ghost remains.
- [ ] Russian locale and English fallback, Home/Add/Settings navigation, Back-to-Home, five-tap Logs, delete
  confirmation, launcher and themed icon, notification permission timing and Stop action.

### Release pipeline
- [ ] Patch 43: run the workflow once. The step "Run unit tests and lint" passes (every unit test, then lint), then
  the signed build. In the log: no `android:extractNativeLibs` warning, no `llvm-strip` error lines, no NDK or CMake
  download during the Gradle step, no deprecation warnings from `AppChrome.kt`, `BypassSettings.kt` or
  `QueueAndLibrary.kt`. Record any new failure with its first error line. On a failed run the `build-reports`
  artifact holds the test and lint reports (not yet seen).
- [ ] Patch 43: install that release over the previous one: the yt-dlp engine starts and a download completes. The
  native-library extraction moved from the manifest attribute to the packaging DSL and the NDK is now pinned (to
  the version that was used all along); both should change nothing, and this is where that is shown. The APK size
  stays about the same and `unzip -l` lists only `lib/arm64-v8a/`.
- [ ] Run `.github/workflows/build-release.yml` with a fresh semantic version: unit tests, lint and the signature,
  zip, package, version and ABI checks pass; the run summary lists the signing certificate fingerprint and says
  "Play Protect high-risk declarations: none"; the Release holds the signed arm64 APK and no `.sha256` file.
- [ ] Install that release over the previous signed build: settings, sessions and the job journal survive.
- [ ] Install on the affected phone and report the exact Play Protect dialog text and whether "More details" /
  "Install anyway" is offered (`RELEASE.md`, "If the install is still blocked").

### Platform limitation to re-check upstream
- [ ] **16 KB page-size devices:** `youtubedl-android 0.18.1` still has an open upstream report of a bundled
  ffmpeg/libwebp payload that is 4 KB-aligned. Do not call Kinescope 16 KB-compatible until a wrapper update is
  verified.

## Technical debt

- **The learned record is global, not per network.** It does not know on which network a success or a failure
  happened; the weekly halving of old evidence is the only protection against a record made at home steering a
  strategy choice on a hotel network. A per-network key (the Wi-Fi name needs a location permission; the network type
  and the provider would not) is a design of its own; build it only if a device report shows the record misleading.
- **The upkeep runs when the app is opened, not on a timer.** There is no WorkManager dependency, and Android 12+ does
  not let a background job start the strategy search, which is a foreground service. If the app is not opened for 25
  days, the upkeep waits for the next launch. A truly unattended re-check needs a different design, not a timer.
- **A tunnel rotation during a network outage counts against the strategy in use.** `DpiPrefs.quarantine` records the
  failure for both of its callers, and the tunnel cannot tell a dead strategy from a dead network (the download ladder
  can: it blames a route only when a later one finished). The damage is bounded (a failure is one weighted entry that
  halves every week and the next success outweighs it), but if a report shows a good strategy sitting low after an
  outage, give the tunnel's call a weaker outcome than the ladder's.
- **The yt-dlp startup update is every 25 days now (it was every 12 hours).** A YouTube change inside that window is
  healed by the recovery chain's nightly refresh on the first download that fails. If reports show a failed first try
  after each YouTube change, give yt-dlp its own, shorter interval in `YtOfflineApp` (the constant is shared today).
- **The quiet re-check is still a full scan.** Only its trigger and its visibility changed (every 25 days, no popup,
  downloads are not held back). If it proves too long or too heavy on battery, bound it with `stopAfterFullPasses`
  in `DpiBypass.search` (the candidates already start with the stored chain); the cost is that a new strategy from
  the refreshed list may then not be reached.
- **The chain holds six strategies now (it held four).** In the worst case, when the network is down and every route
  fails, the route ladder tries up to six routes before giving up. Lower `MAX_VERIFIED_STRATEGIES` and `MAX_FALLBACKS`
  together if that wait is noticeable.
- **The Home dashboard reads the bypass preferences when something it watches changes**, not through a live observer:
  a search starting or finishing, the tunnel coming up or going down, or its own switch. A change made elsewhere
  without one of those (none is known today) would show on the next one. If that ever matters, `DpiPrefs` needs a
  `StateFlow`.
- **Settings and Home disagree on one rule.** Settings still locks the "Use the bypass for downloads" switch while a
  download runs (and says so); Home does not, because the switch only writes a preference and a running download keeps
  its route. Align Settings (and its hint text) after a device report on the Home behavior.
- **The tunnel's speed is sampled every 2 s from the bridge's counters** and shown in fixed English units (`KB/s`,
  `MB/s`, as yt-dlp prints them in the queue rows). Nothing here was read against a real tunnel yet; the first report
  with the card on shows whether 2 s is a readable rate.
- **No in-app player.** Playback hands the file to another app (`ACTION_VIEW`). A TV box without a video player
  cannot play a download. A `VideoView`/`MediaPlayer` screen would need no new dependency; build it only after a
  device report says the target box has no player.
- **The YouTube-only tunnel is tied to the phone YouTube app** (`BypassVpnService.YOUTUBE_PACKAGE`). The TV edition
  of the YouTube app is a different package (name to be verified before any change); it is not tunnelled. The
  download bypass does not depend on it.
- **TV input details are unverified:** the on-screen keyboard and focus around the link field, and focus inside the
  sign-in WebView, under a D-pad.
- **`KinescopeApp` is about 300 lines** (navigation state, permissions, the bypass launch flow, enqueue handling).
  Split it (a state holder, the bypass launcher) only after patches 40-42 are confirmed on a device.
- **The two link parsers repeat about 25 lines** of scheme/user-info/port/host checks. A shared helper is a candidate,
  but they are security-critical and tested: do it with a device-confirmed baseline and the existing tests as the net.
- **Layout thresholds are first guesses** (700dp for two panes, 480dp for a short window, text x1.15 on TV). Tune
  them from real screenshots, in `adaptiveSpecFor` and `AdaptiveLayoutTest` together.
- **Instagram without a session** needs curl_cffi impersonation in the bundled Python. Revisit only if
  `youtubedl-android` ships it (the upstream request is open) or a free, maintained build appears; check the bundled
  Python version first (curl_cffi's Android wheel targets CPython 3.13, not verified here).
- **VP9/AV1-only Instagram posts** cannot be fixed by format selection; a re-encode would, but it depends on the
  bundled ffmpeg having an H.264 encoder (not checked). The Instagram quality chips cap the height of the H.264 or
  progressive choice, which can sit below the DASH VP9 rendition.
- **The strategy search probes YouTube hosts only**, so a strategy verified for YouTube does not prove Instagram
  or its CDN is reachable on a restricted network. Add Instagram probe hosts only if a real failure shows it.
- **YouTube sign-out clears the whole WebView cookie jar**, including the Instagram WebView cookies. The saved
  Instagram cookie file is untouched, so Instagram downloads keep working; only a later "Refresh session" needs a
  new sign-in.
- **Cookie rotation:** yt-dlp rewrites the Instagram cookie file when Instagram rotates a cookie and nothing
  re-reads it from the WebView, so an expired session needs a manual re-sign-in.
- **The native toolchain pin is a decision, not a requirement.** NDK 27.0.12077973 (the AGP 8.7.2 default, used by
  every build so far; the workflow and `setup.sh` installed 27.2 until patch 43, but it was never used) and CMake
  3.22.1 are named in
  `app/build.gradle.kts`, the release workflow and `.devcontainer/setup.sh`. Keep the three equal. A newer NDK only
  with a device check of the bypass engine.
- **A 32-bit TV box cannot install the APK.** The release is arm64-v8a only (the vendored `hev-socks5-tunnel`
  library is arm64 only). Many Android TV boxes run a 32-bit userland: `adb shell getprop ro.product.cpu.abilist` on
  the box shows `armeabi-v7a` first or only. If the target box is one of them, the fix is a second ABI in the same
  APK (it needs a 32-bit `hev-socks5-tunnel` build and a larger APK), not a second workflow. Check the box first.
- **Workflow action versions** (`actions/checkout`, `setup-java`, `upload-artifact` v4; `android-actions/setup-android`
  v4) are Node 20-era majors. GitHub forces Node 24 onto them since 2026-06-02 and they still run. Newer majors exist
  (checkout 6-7, setup-java 5, upload-artifact 7 appear in dependency-update PRs; not verified against this
  workflow). Bump them in one deliberate patch, reading each release note, with a workflow run as the check.
- **Leftovers kept on purpose:** `StageResult.replyCode` (SOCKS reply code of a failed check) is recorded but no
  longer read after the unused `Verdict` classifier was removed; the watchdog's internal log text still says
  "YouTube".

## Deliberately out of scope

- Playlist or batch URL semantics beyond the explicit queue of individual videos; Instagram profiles, stories,
  highlights and carousels beyond the first item.
- Cross-device queue sync or a required backend.
- Custom YouTube or Instagram extraction, anti-bot bypass, BotGuard or PO-token implementation, a paid
  `youtubedl-android` fork, a private-API library or a hosted downloader API.
- Automatic restart of interrupted downloads after process death. Recovery is explicit so the app never starts
  network work unexpectedly after Android recreates the process.
- A second roadmap file. The former lowercase `roadmap.md` audit was consumed in patch 25 and stays deleted; add
  new work here.
