# CLAUDE.md — Project Instructions

Read this file first in any new session working on this repo.

## What this project is
A personal Android app that downloads YouTube videos and Instagram
Reels for offline viewing. Built by Baltic, who travels for work to a region where
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
See `ROADMAP.md` for what is still open and `HANDOFF.md` for the
current state, architecture and file map. **Read `HANDOFF.md` first** if you're starting a
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
- **Decision (patch 24): the debug GitHub Actions workflow is removed.** CI distribution is release-only through `.github/workflows/build-release.yml`, which publishes the signed APK to GitHub Releases. `./gradlew assembleDebug` remains the local Codespace sanity build required by `AGENTS.md`.
- **Decision (patch 24): UI localization is English + Russian.** Keep English as the default resource set; `values-ru` is used automatically for Russian locale. New user-visible strings must be added to both locales.
- **Decision (patch 24): YouTube recovery remains yt-dlp-only.** Nightly updates, cookies, retries and yt-dlp player-client fallbacks are allowed; custom BotGuard/PO-token generation, signature deciphering, or anti-bot bypass code remains forbidden.
- **Decision (patch 24): first-sprint navigation is Home / Add / Settings.** Five rapid taps on Settings opens the diagnostic log journal. Android Back from Settings/Add returns Home.

- **Decision (patch 25): the durable download journal is the source of truth across process death.** Persist accepted jobs before foreground-service dispatch; never silently auto-restart an interrupted network transfer after process recreation. Surface it as recoverable `INTERRUPTED` and require an explicit Resume.
- **Decision (patch 25): single-video URL semantics are intentional.** Use the shared strict `YouTubeUrlParser`; canonicalize accepted video links, reject playlist-only/lookalike/non-HTTP(S) input, and keep `--no-playlist`. Playlist expansion is not an active roadmap item.
- **Decision (patch 25): privacy precedes diagnostic detail.** App backup stays disabled while local YouTube cookies, logs, and the job journal exist. Logs must redact URLs, cookie values and app-private paths before both persistence and Logcat.
- **Decision (patch 25): the former lowercase `roadmap.md` audit is consumed and remains deleted.** Its still-relevant engineering findings were implemented or converted to verification gates in `ROADMAP.md`. Historical audit assumptions forbidding authentication / embedded browser are superseded by the user's explicit patch-24 optional YouTube-session requirement and must not be restored.
- **Decision (patch 25): release verification includes tests and lint.** Local patch verification uses `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`; release CI runs unit tests + lint before the signed build and verifies signature, zip alignment, package/version metadata and ABI contents before publishing.
- **Known upstream limitation (patch 25): do not claim 16 KB page-size compatibility yet.** Android 15 can run on 16 KB page-size devices, while the currently pinned `youtubedl-android 0.18.1` has an open upstream native-payload compatibility issue. Re-check this when a newer wrapper release is considered; do not paper over it locally with custom native binaries without an explicit user decision.
- **Decision (patch 27): an optional in-app network bypass is now part of Kinescope, at the user's explicit request.** It vendors the MIT `hufrea/byedpi` C engine (not ByeByeDPI's own GPL Kotlin/Java code) behind Kinescope's own JNI glue, runs it in a dedicated `:dpi` process torn down after every use, and is off by default. Settings expose enable/disable, a strategy search against real YouTube-related hosts, manual strategy selection, a direct-vs-bypassed connection test (reusing patch 26's `NetworkCheck`), and a strategy-list update (plain HTTPS GET, no backend). It does not claim to fix DNS- or IP-level blocking, only DPI/header-based blocking. Do not remove or weaken the `DpiStrategyParser` allowlist (no listen-address, file-path, or connect-target options) to make a strategy "just work" -- that allowlist is the boundary that keeps a downloaded strategy list from ever controlling anything beyond the desync behavior itself.
- **Decision (patch 37): Instagram Reels are a second supported source, at the user's explicit request** ("download by link, not only from YouTube, minimal GUI change"). Extraction stays entirely inside yt-dlp. Anonymous Instagram access needs browser TLS impersonation (curl_cffi) that the pinned `youtubedl-android 0.18.1` does not ship, and the only prebuilt bundle found with it is a paid fork, so Reels use an optional signed-in WebView session (`InstagramAuth`), preferably from a spare account, passed to yt-dlp with `--cookies`. Do not add a paid fork, a private-API library (aiograpi/instagrapi), a hosted downloader API or any hand-written Instagram scraping. `InstagramShareResolver` follows the HTTP redirect of `instagram.com/share/...` links and nothing else (no body is read, no cookie is sent, every hop must pass `InstagramUrlParser`); keep it that narrow. Profiles, stories and playlists stay out of scope.
- **Request (patch 39): a final production review** (DRY, KISS, YAGNI, SOLID; remove dead code and repository leftovers; bring the documents, GUI and UI texts to the current state). Result: `CHANGELOG.md`, "Patch 39". Large structural refactors that cannot be verified without a device were deliberately not done; they are listed in `ROADMAP.md`.
- **Request (patch 42): audit patch 41 and the codebase (KISS, YAGNI, DRY, SOLID, security, reliability), fix what is
  found, update the documents, check, push, and remove old scripts and other junk.** Decisions: patches 41 and 42
  are applied together and verified by one Gradle run; the audit fixes what is safe without a device (a state-losing
  frame, unused fields, one duplicate, an explicit WebView hardening) and records bigger refactors (`KinescopeApp`,
  the parser skeleton) as debt until a device confirms patches 40-42; patch scripts live outside the repository or
  are ignored by git, and a successful run deletes them. Result: `CHANGELOG.md`, "Patch 42".
- **Request (patch 41): an Android TV (and set-top box) version and a tablet version, both adaptive and responsive.**
  Decisions: one APK and one code base (no product flavor, no TV-only module); no new dependency (`androidx.tv` and
  the window-size-class artifact cannot be verified offline, the Material breakpoints are computed from
  `LocalConfiguration`); the app stays touch-first and a phone in portrait keeps its layout; TV is detected from the
  device and can be overridden in Settings -> Interface (the override also previews the TV layout on a phone);
  extraction, downloads, the bypass and the tunnel are untouched. Result: `CHANGELOG.md`, "Patch 41"; the device
  checklist is in `ROADMAP.md`.
- **Request (patch 40): split `DownloadService.kt` and `MainActivity.kt` by responsibility**, then apply patches 39 and 40 together and test on the phone. Result: `CHANGELOG.md`, "Patch 40". Behavior must not change; the post-split smoke test is in `ROADMAP.md`.
- **Request (patch 44): the DPI bypass "stopped working" after patches 41-43; analyse the report, find the cause, fix it.**
  Result: `CHANGELOG.md`, "Patch 44". The report held no failure, so the cause was not established; the patch
  adds the missing measurement (the YouTube app's own traffic through the tunnel) and a reaction to it. Do not change
  the tunnel's DNS, IPv6 or MTU on a guess: the next report's `tun win` lines decide.

- **Request (patch 45): the bypass can be switched on and off from Home; the Home hero card becomes a dashboard**
  (an on/off control plus state and load indicators; "think it through and implement it"). Decisions: the card
  controls the two things the app calls "bypass" separately (the downloads switch is the same preference as in
  Settings; the YouTube tunnel keeps its start / open / stop), nothing about extraction, the engine, the strategy
  parser or the tunnel's network configuration changes, and the only service change is publishing numbers the tunnel
  already measured. Result: `CHANGELOG.md`, "Patch 45"; the device checklist is in `ROADMAP.md`.
- **Decision (patch 48): the outside tablet/TV UI audit is implemented in part, and the log journal becomes a visible Settings
  entry.** At the user's request the audit was read against the code first; what it got right was fixed, what it overstated
  was left, and the large items (a TV-native Home, `material3-adaptive`, Media3, `focusRestorer`) were postponed with reasons
  (`CHANGELOG.md`, "Patch 48"). The hidden five-tap route to the log (patch 24) stays, but a remote cannot make five taps in
  650ms, so Settings now also has a plain Diagnostics button; revert that button if the log should be hidden again.
- **Decision (patch 51): strategies are named after colours, the list mirrors its source, the first check is mandatory.**
  (1) Every bypass strategy has a colour name, never a bare number. THE RULE: the palette (`R.array.strategy_colors`, EN and RU,
  most common colours first, append-only) is assigned in order, a strategy takes the first colour no other known strategy has,
  the assignment is stored and does not change while the strategy is in the list (`StrategyNames.kt`; `HANDOFF.md`). (2) The
  community list REPLACES the bundled snapshot when downloaded; it is never stacked on it, so the app shows built-ins + the
  source's list (12 + 60 = 72 at 2026-10-22). (3) Strategy updates are forced only on the first install (mandatory, no Stop);
  after an app update yt-dlp and the list refresh themselves and the user is only advised to re-test. (4) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  is declared on purpose: Google Play restricts it, this app is sideload-only (see "no Google Play" above). (5) The library left
  Home at the user's request but is kept as its own screen so downloads can still be played; delete the entry to drop it.
- **Decision (patch 52): the queue is a destination, Home is the dashboard, "+" downloads the clipboard link.** (1) The navigation has five
  entries in this order: Home, Queue, "+", Library, Settings; the bottom-bar buttons are 48dp so that five fit a 320dp window. (2) "+"
  reads the clipboard only on that tap, starts a valid YouTube or Instagram link with the default quality and folder from Settings, and opens
  the Add screen only when the clipboard holds no such link (the Add screen is also where a shared link lands). (3) The dashboard card keeps ONE
  switch for the YouTube bypass; "Unblock YouTube" is a button below the card. (4) Strategies are a drop-down (default: the one in use) with a
  switch for choosing automatically: picking from the menu or switching the automatic choice off pins (`DpiPrefs.pin`), switching it on lifts
  the pin. Do not bring back the chips, a second tunnel switch inside the card or the queue on Home without being asked.
  **(3)-(4) were changed by the user's own October 2026 edit and confirmed in the patch 54 request: the Home drop-down is gone on purpose;
  see the entry below.**
- **Decision (patch 53): strategies are rated per connection, from the test and from real use.** (1) Everything the app learns about a
  strategy (the last test, real use, the chain, the pin, the quarantine, the route that carried the last download) is kept per
  connection class: Wi-Fi/Ethernet, or mobile data (and a metered Wi-Fi, i.e. a hotspot). The Wi-Fi record uses the pre-patch-53 keys. (2) One rating
  (0-100) is made of the last test and real use; stars, drop-downs and chain order come from it; a search's findings are not also recorded as
  real use. (3) `StrategyRouter` decides the chain after a search, a success, a failure and for a class with no chain (it borrows the other's);
  a running tunnel follows a change of connection class after two seconds and a change of class is never counted as a failure. (4) ~~No full
  search on a metered connection without the user starting it~~ (superseded by patch 55: nothing scans the whole list any more; checks stop at a goal).
  The mechanism is documented in `STRATEGY_ROUTER.md`; keep that file in step with the code.
- **Decision (October 2026, the user's own edit, confirmed 2026-10-25): Home has one tunnel toggle, an automatic toggle and "Watch YouTube"; the
  strategy drop-down, colour names on Home and the stars are gone on purpose.** Manual choice is a two-step flow: switch automatic off on Home (this pins
  the strategy in use), then pick one at the bottom of Settings (shown only while a pin exists for the connected class). Do not bring the drop-down
  back without being asked. The docs were brought in step in patch 55; `kinescope_patch.py` (the external script that made the edit) was removed.
- **Request (patch 54): the bypass did not start on mobile data (every strategy failed at CONNECT "unreach").** Decisions: a failure before the strategy
  acts is the route's, not the strategy's (not stored, not quarantined); the engine's own `-X` (IPv4 only) is tried and remembered per class, and a
  connection that reaches nothing says so; the tunnel runs a short search when its borrowed strategies are blocked. The IPv6 cause is a hypothesis
  until a device report confirms it. Result: `CHANGELOG.md`, "Patch 54"; `STRATEGY_ROUTER.md` section 8.
- **Request (patch 55): small, per-connection checks, a two-a-day random check, a heartbeat instead of a speed, hints, a Google-style GUI.** Decisions:
  (1) a check stops at 5 working strategies, 3 of them perfect (`SearchGoal.STANDARD`), never scans the whole list; (2) Wi-Fi and mobile data are
  two groups with their own ranking and checks, each checked once per app version (fresh install and update alike); yt-dlp and the community list refresh at
  the same moments; (3) the first time the bypass is switched on in each twelve hours, two random strategies of the connection class are tested, a failure
  sinks to the very bottom of the ranking and the 15 lowest wait 75 days; (4) the dashboard shows no speed, a heartbeat line instead (green pulse = the
  bypass carries traffic, red flat = connection lost, muted = off or starting); (5) a semi-transparent hints panel that cannot be closed closes Home;
  (6) the reference is Gemini's "Neural Expressive" redesign (May 2026; no October 2023 redesign exists), applied as tonal theme tokens and a soft
  wash on the ON card, not as copied assets. Where the brief was loose, `CHANGELOG.md` ("Patch 55", "Reading of the brief") says how it was read.
  Patch 54's background job was replaced by the random check. Result: `CHANGELOG.md`, "Patch 55".
- **Request (patch 56): the router, the twice-a-day check and the groups must be visible and work; a refresh button; the Gemini-style UI and the tablet hints must show.**
  Decisions: (1) a stop the user asked for is never a failure, and a first transport death is reconnected on the same strategy without blame (a second one blames it);
  (2) the random check keeps its 12-hour calendar but also runs when the app is opened and while the tunnel stays up, and its outcome is shown and stored per class;
  (3) a refresh button in the top-right of the dashboard tests five random strategies of the current connection, working ones join the router's list by rating;
  (4) a strategy groups card shows Wi-Fi and mobile data; (5) a wide window gets two panes so the hints are not below the fold; (6) dead code and stale comments removed.
  Where the brief was loose, `CHANGELOG.md` ("Patch 56", "Reading of the brief") says how it was read. Result: `CHANGELOG.md`, "Patch 56".
- **Request (patch 57): make the bypass work as well as ByeByeDPI does on the user's phone ("as close to ByeByeDPI in its functionality as possible", the best techniques and
  practices, no GUI change for now).** Decisions: (1) ByeByeDPI's code was read and compared before anything was written; its GPL Kotlin/Java code is NOT used,
  the mechanisms are reimplemented (the engine stays the MIT `hufrea/byedpi`). (2) The comparison found the engine commit, the strategy list and the tunnel library at parity or newer;
  the differences were in what a strategy is judged by and in how the tunnel is configured, and only those changed. (3) A strategy is also judged by how many of YouTube's real hosts,
  video-server nodes included, arrive IN FULL through it (`ProbeSites`, `HttpBodyJudge`); it ranks strategies and is never a gate. (4) The tunnel is IPv4-only and adds no DNS server
  when the phone has a Private DNS host name, as ByeByeDPI's defaults do; this supersedes patch 44's "do not change the tunnel's DNS or IPv6 on a guess" (the reference is a working
  app on the user's own phone, not a guess; MTU is unchanged). (5) Not ported on purpose, because each needs a GUI or a product decision: an all-apps / per-app tunnel, start at boot,
  a pause action, a configurable fake SNI. Result: `CHANGELOG.md`, "Patch 57".
- **Request (patch 58): restore manual strategy selection on the Dashboard and make the router switch away from a strategy that stops working.**
  Decisions: (1) this explicitly supersedes the October 2026 no-Home-drop-down decision; only while automatic is off, Home shows exactly the five best strategies
  of the CURRENT connection class (`StrategyRank.manualChoices`), while Settings keeps the full paged list; (2) automatic off while the tunnel runs pins the actual active line,
  not a stale preference snapshot; (3) a failure-driven restart excludes the just-failed line for that restart even when it is pinned or every route is quarantined;
  (4) pins, ratings, health checks, quarantine duration and rotation limits otherwise stay unchanged; (5) manual selection applies at the next tunnel start and never disables later failure recovery.
  Result: `CHANGELOG.md`, "Patch 58"; `STRATEGY_ROUTER.md` and `ROADMAP.md` carry the mechanism and device gates.
