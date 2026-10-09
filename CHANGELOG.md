# Changelog

All notable changes to Kinescope are recorded here, one entry per
patch, newest first. This file exists so `ROADMAP.md` can stay focused
on what's still open: as of patch 16, a `ROADMAP.md` item that gets
checked off also gets its detailed checklist text removed from that
file and replaced with a one-line pointer here. `HANDOFF.md` remains
the deeper narrative/architectural snapshot for resuming work in a new
session; this file is the terse "what shipped, in which patch" record.

Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.


## Patch 38 — Instagram codec fix (a saved Reel played as a black picture)

Requires patch 37. Reported after the first device run of patch 37: downloads work, but one Reel plays as a black
picture with sound.

### Cause
- The save step is not involved: `MediaStorage.publish` copies the finished file byte for byte and takes the MIME
  type from the extension. The file was already unplayable when yt-dlp produced it.
- Patch 37 selected Instagram formats with `bv*[height<=?N]+ba/b[height<=?N]/b`. yt-dlp's default sort ranks a
  labelled video codec above an unlabelled one. For Instagram only the DASH video streams are labelled (VP9);
  the plain H.264 downloads carry no codec at all (yt-dlp issue 12394, still how the 2026.08.19 extractor builds
  its formats: the codec comes from an optional `video_codec` field). So whenever a Reel is offered as VP9 DASH,
  yt-dlp downloaded the VP9 video plus the audio and merged them into an MP4. A phone that cannot decode that
  stream shows sound and no picture.
- Reproduced, not assumed: yt-dlp 2026.08.19 run on Instagram-like format lists took `dash-…vd(vp09)+dash-…ad`
  with the old selector and the unlabelled progressive file (or an avc1 DASH video, when one exists) with the new
  one. The user's actual file was not inspected, so the VP9 explanation is an inference from the evidence above.

### Fix
- `InstagramFormats.selector`: (1) an explicit avc1 video with AAC audio, (2) a muxed progressive file that is not
  VP9/AV1/HEVC (`[vcodec!^=?vp]` keeps an unlabelled one), (3) then any video plus audio, any muxed file, and the
  unconditional `b`, so a VP9-only Reel still downloads instead of failing.
- `DownloadService` logs the format ids of every Instagram run (`fmt j=… dash-…vd+dash-…ad` means the VP9 route).
- `MediaUrlParserTest` now pins the codec order.

### Trade-off and limit
- The progressive file can be smaller than the best DASH rendition, so a 1080p chip may deliver less than 1080p.
  Playing at all was judged more important than the last step of resolution.
- A Reel offered only as VP9 is still saved as VP9 and may still not play. Re-encoding on the phone would fix it
  but depends on the bundled ffmpeg having an H.264 encoder, which was not verified.

### Verification status
- Sandbox: the selector was run through yt-dlp 2026.08.19's own format selection (five Instagram-like cases); the
  patched tree compiles (non-Compose sources) and the unit tests pass on a minimal JUnit-compatible runner. Not
  verified: the real Gradle build and any real Reel on the device. See `ROADMAP.md` "Patch 38".

## Patch 37 — Instagram Reels by link (yt-dlp + an optional signed-in session)

Requires patch 36. Built from the 2026-10-03 source-of-truth repomix. Adds Instagram Reels as a second
source next to YouTube, at the user's request ("download by link, not only from YouTube, minimal GUI
change"). Extraction stays entirely inside yt-dlp: Kinescope recognizes the link, supplies a cookie file
the user created by signing in, and explains failures.

### Research behind the design (public sources read on 2026-10-03; none of it re-run on a device)
- Public Telegram bots and self-hosted downloaders almost all do the same thing: yt-dlp plus the cookies
  of a throwaway signed-in account. The elaborate ones add fallbacks (a private-API library, a paid hosted
  API, a link-preview rewrite) that need a server, a paid key or custom extraction, which `CLAUDE.md` forbids.
- yt-dlp's Instagram extractor changed repeatedly in 2026: stable 2026.06.09 answered anonymous requests
  with "empty media response"; from the 2026.06.28 nightly the anonymous path requires browser TLS
  impersonation (curl_cffi); stable 2026.07.04 reworked the extractor and detects invalidated cookies; stable
  2026.08.19 fixed logged-in extraction. (Taken from yt-dlp's issue tracker and release notes.)
- `youtubedl-android` (pinned 0.18.1) does not bundle curl_cffi (the upstream request is still open); the one
  prebuilt bundle found that does is a paid fork. So the anonymous path is out of reach under the zero-cost
  rule, and a signed-in session is the supported route.

### Checked against the real yt-dlp 2026.08.19 (desktop Python build installed in the sandbox, not the on-device one)
- Logged-in extraction reads the `sessionid` cookie and calls Instagram's media-info API; impersonation is used only
  when available, so a session works without curl_cffi. Logged-out extraction needs impersonation for its GraphQL
  step and otherwise falls back to parsing the post page, so anonymous Reels may or may not work.
- A dead session produces the warning "account cookies are no longer valid" followed by "empty media response".
- The canonical links this patch produces match the extractor's URL pattern; `/share/` and `/reels/audio/` do not.
- A cookie file in exactly the format `InstagramAuth` writes is loaded by yt-dlp (session visible for www and i
  hosts) and re-saved in the same format.
- The Reel output template and the height-capping format selector give the intended file names and picks on fake
  format lists (DASH pair, progressive only, unknown height, cap below the only format).

### Links
- New `InstagramUrlParser` (strict: exact host allowlist, http/https only, no userinfo/port, bounded input,
  tracking parameters dropped) and `MediaUrlParser`, the single entry point used by enqueue, the share intent
  and the clipboard. Accepted: `/reel/`, `/reels/`, `/p/`, `/tv/` (also `/<user>/reel/...`) and
  `/share/reel/<token>`. Rejected: profiles, stories, explore, `/reels/audio/`.
- `InstagramShareResolver`: the Instagram app's "Copy link" often yields `/share/reel/<token>`, which redirects to
  the real Reel and which yt-dlp's Instagram extractor still rejects on purpose (its URL pattern excludes
  `/share/`, read in yt-dlp 2026.08.19; issue 11630). The worker follows that redirect (HTTP only, no body read, no cookie sent, at most 4 hops, 8 s
  timeouts, every hop passed through `InstagramUrlParser`). On any failure the share link goes to yt-dlp unchanged.
- The journal key for a job is `mediaId`: the bare YouTube video id (unchanged, existing journals still
  de-duplicate), `ig:<shortcode>`, or `igs:<token>` for a share link. The job format did not change; the source is
  derived from the canonical URL (`MediaSource`).

### Download
- Instagram runs use their own recovery chain: the plain run, then at most one retry after a nightly yt-dlp
  refresh, and only when `DownloadErrorClassifier.isRecoverableInstagram` says a retry can help (not for a rate
  limit, an unavailable post, or a login failure without a saved session). YouTube's chain is untouched.
- Format: the quality chips only cap the height (`bv*[height<=?N]+ba/b[height<=?N]/b`, always ending in an
  unconditional `b`); the audio chip still extracts MP3. `--playlist-items 1` keeps a carousel post to one item.
  Files are named `<uploader> - <id>.<ext>` because Reels rarely have a real title.
- The existing bypass route ladder, watchdog and publication path are shared unchanged.

### Session (the only visible addition: one Settings row and a Home banner)
- `InstagramAuth`: WebView sign-in on instagram.com, the cookies are written once to an app-private Netscape
  file and passed with `--cookies`; a capture without a `sessionid` cookie changes nothing. Sign-out deletes
  the file and expires only the instagram.com cookies, never the YouTube ones. No password is seen.
- A Reel that needs a login is parked as a resumable Pause (`FailureKind.INSTAGRAM_LOGIN`); a Home banner offers
  the sign-in, and saving a session resumes those jobs.
- `classifyInstagram` keys on broad markers and the last `ERROR:` line only, because 2026 wording is unstable.

### Privacy
- `DiagnosticSanitizer` redacts `sessionid`, `csrftoken`, `ds_user_id`, `ig_did` and related Instagram cookie names;
  `InstagramAuth` logs counts only. App backup stays disabled.

### Strings
- Seven existing strings that only named YouTube were reworded (EN + RU); eleven Instagram strings were added.

### Verification status
- Sandbox only, plus the yt-dlp checks listed above. The script applies to a copy of the repository and is idempotent; the non-Compose sources and all
  unit tests compile against the API 35 `android.jar` with stubs for androidx and youtubedl-android; the tests ran
  on a minimal JUnit-compatible runner, not under Gradle. `MainActivity.kt` (Compose) was checked by brace
  balance and review only. `./gradlew testDebugUnitTest lintDebug assembleDebug` is run by the patch script itself
  before it commits.
- Not verified, needs the user's device: that Instagram's WebView sign-in completes (2FA, checkpoint, and
  whether Instagram accepts the stock WebView user agent, which this screen deliberately leaves unmodified), that the bundled yt-dlp downloads a real Reel with that session, that a `/share/` link
  resolves from this app, the real yt-dlp wording for login/rate-limit failures, and that the Android 13 device
  accepts the new Settings row layout. See `ROADMAP.md` "Patch 37".

## Patch 36 — CJM/UX stabilization, truthful download phases, balanced navigation

Requires patch 35. Built from the 2026-10-01 source-of-truth repomix. The user now confirms that
**downloads and DPI bypass work on-device**; this patch preserves that network path and focuses on
friction around it.

### Download lifecycle / stability
- The visible queue is now a work queue: after MediaStore commit succeeds, a completed job is removed
  immediately and a separate completion version tells Home to refresh Library. The durable journal was
  already removed at success; UI and persistence now agree.
- yt-dlp post-processing is detected from its merger/remux/extract/move output while the process is
  still running. The row and foreground notification switch to **Processing** instead of sitting at
  "Downloading 100%"; the final MediaStore copy remains **Saving**. A short **Finishing the download**
  state covers the hand-off at ~100% before yt-dlp emits a post-processing line.
- Progress projection is throttled to 750 ms. Speed is preferred over noisy ETA; ETA is rounded to
  five-second buckets when it is the only useful signal. Queue status is single-line and active rows
  reserve progress-bar space, preventing the list from changing height on every phase transition.

### Notifications / navigation
- Download and strategy notifications share one explicit `AppIntents.pendingOpenHome()` intent with
  `NEW_TASK | CLEAR_TOP | SINGLE_TOP`. `MainActivity.onNewIntent()` converts it into a Compose state
  event, so tapping a notification visibly brings Kinescope to Home even when an existing singleTask
  activity was previously on Settings.
- Download notifications now use Kinescope's own small-TV status icon and report Preparing /
  Downloading / Finishing / Processing / Saving truthfully.

### Automatic strategy refresh
- Fresh install and first launch after an app update both show the same localized explanation (Russian
  for `values-ru`, English fallback for every other locale) before the automatic background refresh.
- Removed the stale second auto-search owner from `BypassSettings`: it could set
  `initial_search_done=true` before a search actually finished. `MainActivity` is now the single owner
  of automatic refresh; Settings only starts a search after an explicit user action.

### UI / design-system pass
- Bottom navigation uses three equal 56dp touch targets, symmetric spacing, safe navigation-bar insets
  and a responsive 280-380dp pill.
- The Home YouTube-bypass action is intentionally smaller (48dp high, max 280dp) so it no longer
  overpowers navigation. Queue status text is bounded to one line.
- Settings ends with the requested `powered by ephedrine` watermark.
- No broad architecture rewrite: the working bypass/download engine path is untouched. The code audit
  fixed the concrete duplicate lifecycle owner and duplicated notification-intent construction; larger
  `DownloadService` decomposition is intentionally deferred under KISS/YAGNI until behavior requires it.


## Patch 35 — Adaptive fallback, load-ranked strategies, manual choice, background re-check, run heartbeat

Requires patch 34. Not device-verified. The last patch of this series: it also writes the session
snapshot at the top of `HANDOFF.md` and the status block at the top of `ROADMAP.md`.

### What the 2026-09-29 report shows
The report (header `fmt=2`, OnePlus 5, Android 10, yt-dlp nightly 2026.09.27) covers two searches and
two resumed downloads of the same job.
- **The second search was cut short and its result thrown away.** `srch done n=21 pass=2 ms=82682`: only
  21 of the 72 strategies were tested, so it was stopped (Stop button or notification). A stopped search
  never applied its passes, so the chain stayed the old one; the report header at 23:42:43 still lists the
  old `#s1..#s4`, and the later download used them. The two passes it had found (one with 3/3 hosts and
  a 1421 KB/s transfer) were lost. Patch 35 keeps the passes of a stopped search.
- **The download ran and neither failed nor stalled.** Run 1: `chk ok` (304 ms), `run` at 23:39:33, the user
  paused at 23:39:56. Run 2: `run` at 23:41:49, the log ends at 23:42:43. Neither produced a `stall`,
  an error, or a progress fact. Patch 34's watchdog only saw silence, and yt-dlp's stdout lines reset it.
  So the run was alive in some phase; which one is unknown.
- **Why the row looked frozen ("ETA -1"), verified in the wrapper source.** `youtubedl-android` 0.18.1
  updates its progress value only for a line matching `[download] xx.x% ... ETA mm:ss`. yt-dlp prints
  `ETA Unknown` while it has no speed, so the wrapper keeps reporting progress -1 and ETA -1 while the
  download runs at a crawl. Patch 34 turned that into "Preparing the download…", which is just as wrong
  for a running download. Patch 35 parses the line itself.
- **A silent phase that is not the network.** The wrapper adds `--js-runtimes quickjs:<path>`, so the JS
  challenge is solved on the phone by QuickJS: CPU-bound, silent, and slow on a 2017 phone. A watchdog that
  reads silence as a dead route would kill it. Patch 35 gives that phase its own limit.
- **YouTube slow in the app although the strategy "passed everything".** The primary (`-o1 -f-1 -r-5+se
  -a1`) passed the light probe and a single 200 KB transfer at 1249 KB/s, but the YouTube app opens many
  connections at once. A single connection says little about that. Not proven to be the cause; patch 35
  measures simultaneous connections and re-checks the running tunnel.

### Downloads
- **Run monitor** (`RunMonitor`, pure and unit-tested). Judges a run by yt-dlp's stdout and by bytes the app
  received (`TrafficStats.getUidRxBytes`, our uid, the engine process included). Phases: extraction (60 s
  of silence), the QuickJS challenge (180 s), downloading (40 s), and merging/remuxing (not judged). A
  route is stalled only when both the output and the network are idle.
- **Slow routes.** With a speed limit (80 KB/s, averaged over 25 s of the download, at least three samples)
  a route that stays under it is abandoned for the next one. At most two such hops per attempt: if two
  routes are equally slow the cause is not the route, so the better of them runs to the end without a limit.
  The download resumes from the part file (`--continue`).
- **Ladder.** Recent route first (ten minutes, in memory), then the chain (pinned first, quarantined
  last), a strategy that fails its live check is deferred and tried for real later with tighter limits,
  the direct route last and only if it can resolve YouTube, first under a third-party VPN. The route that
  finishes becomes the proven strategy; routes that failed hard before it are quarantined for ten minutes.
- **Progress text** now comes from the line: `12% · 340 KB/s`, the ETA when yt-dlp has one, or the percent
  alone. "Preparing the download…" is shown only before the first percent.
- **Heartbeat.** Every 15 s a run logs `hb j= ph= pct= kbps= rx= lines= idle= last="..."` and at the end
  `end j= res=ok|stall|slow|err|cancel dur= ...`. This is the evidence the previous reports lacked.

### Strategy search
- **Load test.** A strategy that passes the light probe and the single transfer is then tried with six
  simultaneous connections (two each to www.youtube.com, i.ytimg.com and redirector.googlevideo.com,
  up to 100 KB each, 6 s). Recorded: connections completed, median TLS handshake time, combined speed.
- **Ranking:** load OK (at least 4 of 6) first, then a real single transfer, then a 0-100 score (hosts 25,
  transfer 15, load 45, handshake latency 15), then single-transfer speed; list order breaks ties.
- **Results are kept** (`DpiResultsStore`, per strategy) for Settings and for the report (`#top` lines).
- **A stopped search keeps what it found** (only if something passed), and a test of one strategy from
  Settings never changes the chain.
- **Order of candidates:** the current chain first, so a re-check confirms or replaces it quickly.

### Manual choice (Settings)
"Choose strategy manually" shows every strategy with its last result (score, KB/s, connections). Test runs
that one strategy; Use pins it: it goes first, automatic searches keep it first and only rebuild the
fallbacks behind it. "Choose automatically" unpins. A pinned strategy that fails in real use is
quarantined like any other for ten minutes, so the app still switches away from a dead choice.

### Background re-check after an update
The first launch of a new build starts a full check in the background (small-TV notification, no popup),
once per build. The previous verified strategies keep serving downloads and the tunnel meanwhile. The
check steps aside between two strategies when a download starts (the download waits for the strategy in
progress, at most 30 s), and a strategy that carried a real download in the last 14 days keeps the lead
unless this check tested it and it failed. A check started by hand replaces the primary by ranking.

### YouTube tunnel
Every 30 s, three simultaneous connections through the tunnel's engine. Two failed probes in a row (fewer
than two of three completing) quarantine the current strategy and reconnect on the next one. At most one
switch per 90 s and three per ten minutes, so a network that is simply down cannot make the tunnel thrash.
The log shows `probe ok` (on change and every tenth time), `probe fail` and `rotate`.

### Report
The header gains `pin`, `proven`, `quarantined`, `search_ver` and up to three `#top` lines (score, KB/s,
connections, handshake, strategy).

### Verification status
- Sandbox only: the script applies cleanly to a copy of the repository and is idempotent; the non-Compose
  Kotlin sources compile against the API 35 `android.jar` with stubs for androidx and youtubedl-android;
  the unit tests pass, including new ones for `RunMonitor` (fed lines shaped like real yt-dlp output),
  `ChainPlanner`, `DpiResultsStore` and the ranking. `BypassSettings.kt` and `MainActivity.kt` (Compose)
  were checked by brace balance and review only, and follow the patterns already used in those files.
- Not verified: everything on a real device. The limits (60/180/40 s, 80 KB/s over 25 s, 30 s probes) were
  chosen by judgement, not measured. Whether a slow YouTube app is a strategy problem at all is not proven;
  the probes and the heartbeat exist to find out.

## Patch 34 — Fallback ladder without a hard gate, DNS-aware routes, VPN awareness, compact AI-first log, Play Protect hardening

Requires patch 33. Not device-verified.

### Confirmed on device (user report, 2026-09-29)
- The patch 33 build succeeds.
- The strategy search runs to the end and keeps four ranked strategies (log line: `Search kept 4
  strategies; primary transfer=true speed=1249KB/s`).
- Still reported broken: downloads do not start (four attempts in a circle), the same with a
  third-party VPN on, the install is still blocked by Play Protect, and the log is too verbose.
  The popup, the notification icon, the navbar and the missing `.sha256` asset were not mentioned,
  so they are not confirmed.

### What the two supplied logs show
- **Log A (no third-party VPN, Kinescope's own YouTube tunnel active since 11:02:14).** The search
  finished at 11:02:04 and its primary strategy had just passed a real transfer at 1249 KB/s. At
  11:03:06 a download started: all four strategies started ("Engine ready") but every one failed
  patch 33's live check, #1 and #2 after 5.0 s (TLS handshake timeout) and #3 and #4 within about
  35 ms. The ladder then fell back to the direct route, which failed with
  `[Errno 7] No address associated with hostname`: this network does not resolve YouTube for the
  device, which is exactly what the bypass exists for. That error was not recognised as a
  connection failure, so it counted as recoverable and the whole ladder ran again for each of the
  four recovery profiles, with a yt-dlp nightly refresh in between. That is the "four attempts in a
  circle".
- **Root cause in patch 33's own design.** The live check was a hard gate. One failed 5 s check
  removed a strategy from use, and when all four failed the only route left was the direct one that
  cannot resolve YouTube. yt-dlp itself never got to try any strategy.
- **Why the checks failed is not proven.** The same strategies had passed the light probe and a
  ~200 KB transfer about a minute earlier. Unconfirmed candidates: per-connection flakiness of
  fake-packet / out-of-band strategies (the search log shows the same host either passing in about
  0.6 s or timing out, nothing in between), residual blocking after failed handshakes (the two
  instant failures right after two timeouts), or the tunnel's engine running at the same time. This
  patch does not guess: every check now logs its stage, reason and duration.
- **Log B (third-party VPN, inferred from content).** Strategy #1 failed its check in 0.5 s, #2
  passed, and the download reached YouTube, which answered "Sign in to confirm you're not a bot".
  The recovery chain kept going and the log ends during attempt 3. YouTube scores the IP address and
  shared VPN exit addresses are commonly flagged (yt-dlp issues #10128 and #16221, the latter from
  March 2026, where even the `android_vr` client got LOGIN_REQUIRED on a VPS). Routing cannot fix
  that, and here routing was working: the request arrived and got an answer.

### Downloads
- **Ladder without a hard gate** (`DownloadService.executeWithBypassFallback`, `runRoute`). The
  live check now only decides ORDER. A strategy that fails it is deferred, not dropped, and after
  every strategy that passed it is tried for real by yt-dlp with a shorter watchdog. The check
  retries once (`DpiBypass.checkHealth`, two tries, 6 s per stage) and reports the failed stage and
  reason. A route that finished a download is remembered per job, tried first on the next attempt
  without another check, and promoted to primary.
- **Direct route only when it can work.** Last, and only if `DpiBypass.directConnectionWorks()`;
  first when a third-party VPN is on (`NetworkState.systemVpnActive`, `BypassRoutes.order`).
- **DNS failures are connection failures** (`No address associated with hostname`, `Name or service
  not known`, `Temporary failure in name resolution`, ...; they still classify as `NO_INTERNET` where
  patch 31 already did). When every route failed, the recovery chain ends at once
  (`BypassTransportException`) instead of repeating the whole ladder for each of the four profiles
  with a nightly refresh in between: a different yt-dlp client profile cannot fix a connection that
  does not work. The row shows the plain message and Retry is manual. Without a bypass ladder (bypass
  off, nothing verified) a connection failure is still retried through the profile chain, as before.
- **Watchdog** limits are 35 s before the first progress and 45 s after it (20 s / 20 s for a
  deferred strategy), measured as "no stdout line from yt-dlp". Patch 33 counted only real
  progress lines; that was too strict because `youtubedl-android` 0.18.1 reports the extraction
  steps (`[youtube] ... Downloading webpage`) on stdout while the retry warnings go to stderr and
  never reach the callback, so a stdout line does mean yt-dlp is alive. The row shows "Preparing
  the download…" instead of "-1% (ETA -1s)" until real progress arrives.
- **Bot check under a VPN** gets its own message (turn the VPN off and use the built-in bypass,
  or sign in), instead of the generic sign-in text.

### Log format (AI-first)
- One line per event: `HH:mm:ss.d L tag message | err="..."`. Job ids shortened to 8 hex digits,
  `job=`/`profile=`/`quality=` shortened to `j=`/`p=`/`q=`, whitespace collapsed, 220-character cap.
- Exceptions become a one-line summary (no stack); a failed yt-dlp run becomes its one error plus the
  retry count and the last unrelated warning. Details in `LogFormat`.
- The strategy search logs one summary line (totals and the most common failures as
  `host:stage:reason=count`) plus one line per fully passing strategy, instead of about 200 probe
  lines. Strategy argument strings appear once, in the report header, as `#s1`..`#s4`.
- **Copy report** (Settings journal) and Share put a header (build, yt-dlp version, Android,
  device, language, network, VPN, bypass state, strategy list, legend) plus the newest 150 lines
  on the clipboard. `DiagnosticReport`.
- Old log lines stay in the old format until the file rotates; the new lines have a different shape.

### Install (Play Protect)
- Google's Play Protect guidance (last updated 2026-08-18) describes "App blocked to protect your
  device" only for internet-sideloaded apps that declare `RECEIVE_SMS`, `READ_SMS`, a notification
  listener or an accessibility service, in select markets. The manifest declares none of them, and
  the libraries' own manifests are empty (checked for `youtubedl-android` 0.18.1).
- Guard added: the release workflow fails if the finished APK declares any of `RECEIVE_SMS`,
  `READ_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE` or `BIND_ACCESSIBILITY_SERVICE` (permission list and
  manifest tree, so a library cannot add one unnoticed), and prints "Play Protect high-risk
  declarations: none" in the run summary. The manifest itself is unchanged.
- The dialog wording "unknown developer" is not one of the strings on Google's page. Google's
  "Send app for security check" and "App scan recommended" dialogs are normal for a new app and only
  ask to send it for a scan. So this cannot be fixed by the APK; `RELEASE.md` now lists each dialog,
  what it means and what to do, including the appeal form and the adb route. The exact dialog text,
  and whether it has "More details" / "Install anyway", is still needed.

### Verification status
- Sandbox only: the patch script applies cleanly to a copy of the repository and is idempotent; the
  non-Compose Kotlin sources compile against the API 35 `android.jar` with stubs for the androidx and
  youtubedl-android APIs; the unit tests pass, including new ones for `LogFormat` (fed the two real
  logs) and `BypassRoutes`. `MainActivity.kt` (Compose) was checked by brace balance and review only.
- Not verified: everything on a real device, in particular whether a deferred strategy that failed its
  check can still carry a real download, and the watchdog limits (chosen by judgement, not measured).

## Patch 33 — First-launch check, ranked bypass with a real fallback ladder, compact navbar, no checksum file

Requires patches 30-32. Not device-verified.

### Downloads that never started ("ETA -1", no error) and a bypass that got worse
- **What the supplied log shows.** It is dated 2026-09-24, so it predates patches 31 and 32. A
  strategy chosen by the search (`-o1 -r-5+se -a1`) had passed the search's light probe, yet every
  yt-dlp connection through it failed with the local proxy's SOCKS5 reply "Host unreachable"
  (`ProxyError`), three retries per page, and the failure was then shown as age-restricted (that
  misclassification is the one patch 31 fixed). Earlier the same day a different strategy had
  completed a download through the bypass. The log ends mid-line and holds no example of the
  "ETA -1" state itself.
- **Likely causes, inferred from the code and that log (not device-confirmed):**
  1. The search kept the *first* four strategies that passed, in list order, not the four best. A
     bare TLS handshake plus one HEAD request cannot tell a strategy that carries real traffic
     from one that dies after the first packets.
  2. Nothing re-checked a strategy right before a download or a tunnel start, so a strategy that
     merely *started* was used even when it no longer got through.
  3. A run whose route accepted the connection but carried no data left yt-dlp retrying silently
     for minutes. **The `ETA -1` is explained** (checked against the `youtubedl-android` 0.18.1
     source): its `StreamProcessExtractor` calls the progress callback for every stdout line and
     reports progress `-1` / ETA `-1` until the first real `[download] xx.x% ... ETA` line, and the
     queue printed those values verbatim ("-1% (ETA -1s)"). A run that never gets past the
     extraction step therefore looked like a running download with no error. Now the row says
     "Preparing the download…" until real progress arrives.
  4. A connection-level failure had no plain error; it fell through to the raw yt-dlp line.
- **Search.** `NetworkCheck.throughputViaBypass` downloads up to ~200 KB of a real page through the
  engine (8 s budget). For every strategy that passes the light probe the result (`deepOk`, KB/s)
  is stored in `StrategyResult`; `DpiStrategySearch.ranked` orders strategies by real transfer,
  then hosts passed, then speed, with the search order breaking ties. A strategy whose transfer
  failed stays in the list but ranks below one that worked. `DpiBypass.applySearchResults` keeps
  the best four (primary + three fallbacks) and is used by both the search service and the
  tunnel's Update. The first launch scans all 72 candidates; later manual searches stop after 12
  passes.
- **Download ladder** (`DownloadService.executeWithBypassFallback`). Each verified strategy, best
  first, is started, checked with one live connection (`DpiBypass.isHealthy`), and used; one that
  does not start, fails the check, fails at connection level or stalls hands over to the next, and
  a direct connection is tried last. A strategy that finishes a download is promoted to primary
  (`DpiPrefs.promoteVerified`). Errors that are not about the connection are rethrown untouched, so
  patch 31's recovery chain for real YouTube answers is unchanged. If everything fails the row ends
  with the new plain message instead of hanging.
- **Watchdog.** With the bypass in use, a yt-dlp run that shows no real download progress for 90 s
  (before the first progress line) or 60 s (afterwards) is stopped and the ladder moves on. Only
  real progress counts as activity, because the `-1` lines above would otherwise keep a dead run
  looking alive. The clock is paused while nothing needs the network (`[Merger]`, `[Fixup`,
  `[ExtractAudio]`, `[VideoRemuxer]`, `[Metadata]`), so a long merge is not mistaken for a stall. `--socket-timeout 15` makes a dead route
  fail sooner on its own.
- **First launch.** A download queued while the first check is still running on a blocked network
  waits for it ("Waiting for the network check to finish…") instead of starting without a bypass.
- **Errors.** New `FailureKind.CONNECTION_BLOCKED` with a plain EN/RU message. It stays retryable
  through patch 31's profile chain when no bypass ladder ran (a plain connection dropping is
  transient); when the ladder ran and every strategy plus the direct route failed, the chain is
  ended at once (`BypassTransportException`), because a different client profile cannot fix a
  connection that does not work. The patch 31 regression test that pinned the "Host unreachable"
  text to `OTHER` now expects `CONNECTION_BLOCKED` (still recoverable).
- **YouTube-app tunnel.** `BypassVpnService.startTunnel` also live-checks each candidate before
  building the tunnel on it, and falls back to the first strategy that started if none passes.

### First-launch popup and automatic check
- On a fresh install `MainActivity` starts the check of all bundled strategies by itself and shows
  a popup at once (once per install, tracked by `first_run_notice_shown`). The check counts as done
  only when a search finishes, so an interrupted first check restarts on the next launch without
  repeating the popup.
- **One language only.** The popup text lives in `values/strings.xml` (English) and
  `values-ru/strings.xml` (Russian); Android picks one by the device language. The earlier idea of
  a Russian + Portuguese popup is dropped.
- The search and bypass notifications use the new `ic_stat_kinescope` (the small old TV, as a
  single-colour status-bar silhouette) instead of the download arrow. Real download notifications
  keep the download glyph.

### Navigation bar
- The pill now wraps its three buttons (fixed 330 dp width removed, 14 dp spacing), so the empty
  gap between them is gone. The three-button-bar inset handling from patch 32 is unchanged.

### Release pipeline and installing
- The workflow no longer builds, uploads or publishes a `.sha256` file. It prints the signing
  certificate's SHA-256 fingerprint in the run summary instead (that fingerprint is what Android
  developer verification asks for; the APK checksum was not).
- **The "unknown developer / app blocked" install dialog is not something the app can change.**
  Google documents the "App blocked" variant only for SMS / notification-listener / accessibility
  permissions, which Kinescope does not declare. Google's developer-verification pages say that
  from 2026-09-30 unregistered package names cannot be installed on certified devices in Brazil,
  Indonesia, Singapore and Thailand (global in 2027) and that a free limited-distribution account
  (no government ID, up to 20 devices) exists for hobbyists. `RELEASE.md` now has the steps. Whether
  that is what the user's phone shows is unconfirmed.

### Verification status
- Sandbox only: the patch script applies cleanly to a copy of the repository, is idempotent, and
  the touched non-Compose Kotlin sources compile against the API 35 `android.jar` with stubs for the
  androidx/youtubedl-android APIs; the new and updated unit tests pass. `MainActivity.kt` and other
  Compose code were checked by brace balance and review, not compiled.
- Not verified: everything on a real device, including `throughputViaBypass` against real YouTube
  and the watchdog's 90 s / 60 s limits (chosen by judgement, not measured).

<!-- moved to the top of CHANGELOG.md by patch 32 -->
## Patch 32 — Responsive layout, bundled strategies, persistent bypass notification

Requires patches 30 and 31.

### Navigation bar overlap and responsive UI
- **Root cause of the covered bottom bar.** `targetSdk = 35`: Android 15+ enforces edge-to-edge
  (per the Android developer documentation, the system draws the app under the system bars and the
  app must handle insets). Material3's `Scaffold`, `TopAppBar` and `NavigationBar` reserve their
  insets automatically; `GlassBottomBar` is hand-built and reserved none, so the opaque
  three-button bar was drawn over it. Gesture navigation's bar is a thin transparent strip, hence
  "only when gesture navigation is off". `GlassBottomBar` now applies
  `WindowInsets.navigationBars` padding; `Scaffold` measures the bar, so the content padding
  grows to match without double counting.
- **`enableEdgeToEdge()`** in `MainActivity.onCreate`: one model on every supported Android
  (minSdk 29). The manifest sets no theme, so on Android 15+ status-bar icon colours would not
  follow the app's light/dark theme (which follows the system dark mode); the default
  `SystemBarStyle.auto` does.
- **Keyboard.** Edge-to-edge windows are not resized for the keyboard. Content now uses
  `consumeWindowInsets(innerPadding).imePadding()` (only the part of the keyboard the Scaffold has
  not already covered is added) and the activity declares `windowSoftInputMode="adjustResize"`,
  which Android documents as required for IME insets on API 29.
- **Rotation / resize.** No `configChanges` were declared, so every rotation or window resize
  recreated the activity and reset all `remember` state (open screen, typed link, the sign-in
  WebView page). It now handles `orientation|screenSize|smallestScreenSize|screenLayout|keyboard|
  keyboardHidden` itself. Locale, dark mode and font size deliberately still recreate it.
- **`ResponsiveContent`.** Content is centered and capped at 640dp wide, so landscape, tablets
  and unfolded foldables do not stretch lists and cards into one unreadable column. Identical on
  a phone in portrait. (`widthIn` is applied before `fillMaxSize`; the reverse order would force
  the full width first and ignore the cap.)

### All 72 strategies from the first launch
The list `Update` downloads (ByeByeDPI's `proxytest_strategies.list`) was fetched and run through
the real `DpiStrategyParser`: 60 lines, all accepted, zero overlap with the 12 built-ins -- exactly
the 72 seen after updating. A snapshot ships as `assets/dpi_strategies_bundled.txt` (with source and
capture date in its header) and `DpiStrategyStore.candidates()` is now built-ins, then the bundled
snapshot, then anything an Update downloaded, de-duplicated. `bundled()` re-validates every line with
the same strict parser; the allowlist is untouched. `DpiBundledStrategiesTest` fails if any bundled
line stops parsing (the parser drops bad lines silently) or the total stops being 72. Note for
`ROADMAP.md`: the first automatic search can now walk up to 72 candidates on a network where nothing
works; it stops after 4 working ones and runs in the foreground service.

### Persistent bypass notification with Update
`BypassVpnService`'s notification was ongoing, but Android 14+ lets users dismiss foreground
service notifications, and it only had "Stop".
- A delete-intent (`ACTION_REPOST`) re-posts it immediately when dismissed, and the tunnel monitor
  checks every 3s that it exists and re-posts it if not.
- **Update** action: re-tests the strategies inside the service (already a foreground service, so
  no second service has to be started from a notification), shows "Testing N of M" in the
  notification, and only if the new verified chain differs from the current one restarts the
  tunnel with it. Nothing found, or the same chain: the connection is untouched. It is skipped
  while a Settings search is already running.
- The monitor knows about the reconnect (`restarting`), so it is not reported as a failure; only
  one monitor is ever queued (`monitorRunning`); `stopTunnelInternal(publishStopped)` avoids a
  visible "stopped" flash during a reconnect.
- New string `bypass_notification_update` (EN/RU).

### Verification status
The bundled list was checked with the real `DpiStrategyParser` under kotlinc 2.1.0 (60/60
accepted, 72 total). `DpiStrategyStore.bundled`/`candidates` and `BypassVpnService`'s changed
logic were compiled with kotlinc against minimal same-signature stubs. The patch was applied three
times to a clean copy after patches 30 and 31: idempotent, diffs reviewed, XML well-formed, EN/RU
string parity kept. **Not verified on a device:** insets with three-button vs gesture navigation,
keyboard, rotation, landscape, and all notification behaviour (`ROADMAP.md` -> Patch 32).

<!-- moved to the top of CHANGELOG.md by patch 31 -->
## Patch 31 — Age-restricted download fix, prominent bypass button, direct YouTube sign-in

Device confirmation on Patch 30: the strategy search now finds a working strategy on the
restricted network and a bypass-enabled download completes (log evidence: `DpiBypass: Engine
ready; strategy="-d1 -Atorst -r1+s"` and later a longer fallback strategy, each followed by a
completed job). Also reported from that same log and a follow-up message: a video download
failed with the app reporting "age restricted" despite the user never having signed in to
Google/YouTube; the bypass-enable control is not obvious/prominent; and tapping "sign in to
YouTube" opens the YouTube homepage rather than the sign-in form.

### Why the "age restricted" failure was not actually age restriction
The attached device log shows the real failure: a bypass-proxy `<urlopen error [Errno 4] Host
unreachable>` (a `ProxyError`), immediately followed by yt-dlp's generic boilerplate trailer:
"...please report this issue on <url>, filling out the appropriate issue template. Confirm you
are on the latest version using yt-dlp -U." `DownloadErrorClassifier`'s AGE_RESTRICTED match was
`lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict"))` -- and
"Unable to download **webpage**" contains "age", while that generic trailer contains "confirm".
Any ordinary failure mentioning "webpage" (an extremely common yt-dlp phrase) that also hits this
boilerplate trailer was silently mislabeled as age-restricted. Verified against yt-dlp's own
GitHub issue tracker that the real, current age-gate message is "Sign in to confirm your age.
This video may be inappropriate for some users." (already matched by the existing, unrelated
"sign in to confirm" branch above it) and a newer, separate warning: "This video is
age-restricted; some formats may be missing without authentication."

### Fixed
- **Precise age-restriction matching.** `DownloadErrorClassifier`'s AGE_RESTRICTED branch now
  matches `"age-restricted"`, `"inappropriate for some users"`, or `"confirm your age"` instead
  of the loose `"age"` + (`"confirm"`/`"restrict"`) combination. All 2 existing
  `DownloadErrorClassifierTest` cases pass unchanged (the existing age-restriction test text,
  "Age restricted: confirm your age", still matches via "confirm your age"); a new test,
  `doesNotMisclassifyGenericNetworkErrorsAsAgeRestricted`, encodes the exact device-log
  regression plus the two real yt-dlp phrases above.
- **Wider retry recoverability.** `isRecoverableYoutubeBlock` previously only returned true for
  `FailureKind.YOUTUBE_VERIFICATION`, so `executeWithRecovery`'s 4-profile chain
  (`DEFAULT` -> `DEFAULT_AFTER_REFRESH` -> `WEB_SAFARI_IPV4` -> `ANDROID_VR_LOGGED_OUT`) broke
  out after the very first failed attempt for every other failure kind -- including the
  misclassified case above, and including any genuine age-restriction error. `AGE_RESTRICTED` and
  `OTHER` are now also in the recoverable set: `PRIVATE_VIDEO`, `UNAVAILABLE` and `NO_INTERNET`
  stay excluded because none of them depend on which client profile yt-dlp uses, but a real
  age-gate now gets a real chance at `ANDROID_VR_LOGGED_OUT` (a known yt-dlp technique that
  sometimes reaches age-restricted videos without a login) and a transient bypass/network hiccup
  gets a real chance at a later attempt, possibly through a different verified strategy.
- **YouTube sign-in WebView user-agent fix.** The sign-in `WebView` now strips the "; wv" token
  from its user agent (`settings.userAgentString`) before loading anything. Verified against
  multiple independent, current sources (an Adobe Express Embed SDK guide, a real Kotlin PR doing
  the identical fix, and Google Account Community threads) that Google's sign-in explicitly
  detects the stock Android WebView's "; wv" marker and blocks it with "This browser or app may
  not be secure" / `disallowed_useragent` -- a real, separate, previously-unaddressed reason
  sign-in could fail no matter which URL was loaded.
- **Direct sign-in URL.** `YouTubeLoginScreen` now loads
  `https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F`
  instead of `https://www.youtube.com/`. Verified against real, current references (a captured
  YouTube "Sign in" button URL and a matching Brave Community troubleshooting post) that this is
  the same `ServiceLogin` entry point youtube.com's own "Sign in" button itself navigates to; the
  ytdl-org/youtube-dl project's own historical login code used the identical `/ServiceLogin`
  entry point for the same purpose.
- **Prominent Home-screen bypass control.** The bypass action moved from a small `TextButton` in
  the top app bar's corner to a new `BypassHomeCard` -- a large, centered card at the top of the
  Home screen showing the current state (idle / starting / active / error) with one prominent
  button, reusing `BypassVpnController`'s existing state. Researched a Tauri-based DPI-bypass GUI
  ("Zapret GUI", Tauri v2 with automatic strategy selection) per the user's own suggestion: its
  README describes exactly this UX pattern ("one-click launch...or trust auto-selection", "see
  the current state on the Home screen") -- an always-visible, one-tap main-screen control, not a
  toggle tucked into a corner. Reworded `bypass_quick_action` from the bare jargon "Bypass" /
  "Обход" to "Unblock YouTube" / "Разблокировать YouTube", consistent with patch 29's
  plain-language rule; added a new idle-state caption string, `bypass_home_hint`.

### Verification status
`DownloadErrorClassifier.kt` was compiled with a real `kotlinc 2.1.0` against the real
`FailureKind` enum and exercised against a harness covering both existing
`DownloadErrorClassifierTest` cases (unchanged results) plus new regression cases built from the
actual device-log text and the real, current yt-dlp age-gate phrasing (verified against yt-dlp's
own GitHub issue tracker, not guessed). `MainActivity.kt`'s Compose edits (`BypassHomeCard`, the
`HomeScreen`/top-bar wiring, the WebView user-agent and URL change) were reviewed by hand and by
brace-balance check; the WebView user-agent workaround and the sign-in URL are each verified
against multiple independent, current sources rather than assumed. **None of this is
device-confirmed yet** -- see `ROADMAP.md`'s Patch 31 section for the exact checklist, including
what was and was not established by the Patch 30 device report.

<!-- moved to the top of CHANGELOG.md by patch 30 -->
## Patch 30 — Persistent strategy search + required-host relaxation (bypass root-cause fix)

Two later-numbered scripts, `patch_030_persistent_search_service.py` and
`patch_031_docs_investigation.py`, already existed in the repository root, but neither had
actually been run against this codebase: none of their target code changes were present in any
file they were meant to touch, and `ROADMAP.md` / `HANDOFF.md` still only reflected patch 29.
Per `AGENTS.md`'s "read the actual current file content ... never assume memory from earlier in
the conversation is still accurate," this patch treats the repository's real content as ground
truth rather than any narrative about patches 30/31 already having been applied or device-tested.
Both stale scripts are superseded and deleted here with a single consolidated delivery covering
what they were meant to do, plus a researched fix for the root cause patch 31 was investigating.

### Why the strategy search found zero working strategies
`DpiStrategySearch.fullPass` (`DpiSearch.kt`) required every one of the three probe hosts --
`www.youtube.com`, `i.ytimg.com`, and `redirector.googlevideo.com` -- to fully pass a bare,
hand-rolled TLS handshake plus one legacy HTTP/1.1 `HEAD` request, with no ALPN/H2 negotiation and
no session state. `redirector.googlevideo.com` is a CDN redirector, not a page or image host, and
is a plausible poor fit for exactly that kind of synthetic probe regardless of whether the DPI
bypass strategy itself works for real YouTube/yt-dlp traffic. Kinescope's built-in strategy list
was separately checked against `hufrea/byedpi`'s own documented reference examples
(`--disorder 1 --auto=torst --tlsrec 1+s` and `--fake -1 --ttl 8`) and already matches them
verbatim -- the list itself was not the problem.

### Fixed
- **Required-host relaxation.** `DpiStrategySearch` gained a `requiredHosts` parameter (default:
  every host, so all 5 existing unit tests are unchanged); `StrategyResult` gained a
  `requiredPassed` field and `fullPass` now checks that instead of "every probed host passed."
  `DpiBypass.search()` passes the two core hosts (`www.youtube.com`, `i.ytimg.com`) as
  `DpiStrategySearch.REQUIRED_HOSTS`; the CDN redirector host stays probed (it still counts
  toward the `passed`/`total` shown in the UI and toward `DpiStrategySearch.best()`'s ranking)
  but no longer blocks a strategy from verifying.
- **More generous search timeouts.** The search's `NetworkCheck` per-stage socket timeout went
  from 2.5s to 4s (`DpiBypass.SEARCH_STAGE_TIMEOUT_MS`) to reduce false timeouts from a working
  desync strategy's added round-trip latency. The one-off "Test selected" connection check is
  unaffected (still `NetworkCheck()`'s own 3s default).
- **Real diagnostic evidence, not just "0 passed."** `DpiBypass.search()`'s probe callback now
  logs the host and the exact `CheckStage` (DNS/TCP/PROXY/CONNECT/TLS/HTTP) each probe passed or
  failed at, via `AppLog` (the existing privacy-redacted, five-taps-on-Settings log journal). If
  the required-host relaxation above is not sufficient by itself, the next device report can be
  diagnosed from real per-host, per-stage evidence instead of another guess.
- **Persistent strategy search (`DpiSearchService`).** The search now runs in a new foreground
  service instead of `BypassSettingsSection`'s own `rememberCoroutineScope()`, so it survives
  navigating away from Settings or backgrounding the app -- previously an explicit
  `DisposableEffect(Unit) { onDispose { searchJob?.cancel() } }` cancelled it the instant that
  screen left composition. Its state (`running`, current `SearchProgress`, final result) is
  exposed through a new `DpiSearchController` `StateFlow`, mirroring the existing
  `BypassVpnController` pattern; `BypassSettingsSection` now observes it via `collectAsState()`
  instead of owning a `Job`. The service's own notification carries a Stop action, so the search
  can be stopped from the notification shade as well as from Settings.
- **`POST_NOTIFICATIONS` requested earlier.** Previously only requested on the first accepted
  download (patch 25), so `DpiSearchService`'s notification -- and `BypassVpnService`'s, for
  anyone who tries the YouTube bypass before ever downloading anything -- could silently never
  display even though the underlying foreground service runs correctly either way
  (`startForeground()` does not require the notification permission to succeed). Both the
  Settings strategy-search button and the Home-screen YouTube bypass action now request the
  permission (if not already granted) before starting their respective service.
- Fixed the patch-29 auto-run flag being consumed even when the search never actually started
  (blocked by an active download): now only consumed once `startSearch()` is actually called.
- Confirmed unchanged (`DpiEngine.kt`): a search running concurrently with a bypass-enabled
  download is already race-free without new locking -- `DpiEngine`'s existing `regularGate`
  semaphore makes a second concurrent `DpiEngine.start()` return `null` immediately rather than
  colliding with the native engine's process-wide C state.

### Verification status
This session re-read every touched file's actual current content before writing any edit (per
`AGENTS.md`), rather than trusting the stale scripts' own anchors or any prior session's claims
about them. The modified `DpiStrategySearch`/`StrategyResult` (`DpiSearch.kt`) was compiled with
a real `kotlinc 2.1.0` (matching this project's pinned Kotlin version) and exercised against a
harness reproducing all 5 existing `DpiStrategySearchTest` scenarios verbatim (identical results)
plus 4 new checks of the `requiredHosts` relaxation and its all-hosts-required default. The
modified `DpiBypass.kt` was compiled with `kotlinc` against the real, unmodified
`NetworkCheck.kt`/`DpiSearch.kt`/`DpiStrategies.kt` plus minimal same-signature stubs for the
purely-Android-context pieces. `DpiSearchService.kt` reuses a design previously reported compiled
clean against a real API 35 `android.jar`; `BypassSettings.kt`/`MainActivity.kt`'s Compose edits
were reviewed by hand and by brace-balance check, reusing the `X by Y.state.collectAsState()`
pattern already working in the same file for `BypassVpnController`. **Not yet confirmed on a real
device** -- the required-host relaxation in particular is a reasoned hypothesis, not a verified
fix; see `ROADMAP.md` -> Patch 30 for the exact checklist, including what to check first if the
search still finds nothing.

<!-- moved to the top of CHANGELOG.md by patch 28 -->
## Patch 28 — YouTube split tunnel, verified bypass UX and queue cleanup

- Closed the stale Patch-27 verification wording using the user's current status: GitHub Actions builds successfully and the bundled ByeDPI path works on-device.
- Added a YouTube-only Android `VpnService` path. Kinescope starts its existing local ByeDPI SOCKS5 engine, sends the official YouTube app through an on-device TUN-to-SOCKS bridge, and leaves all other apps outside the tunnel. This is local Android VPN routing, not a remote VPN service.
- Vendored the MIT `hev-socks5-tunnel` Android arm64 library from tag `2.17.1` / commit `9a06bc6` as the transport bridge. ByeByeDPI application code remains unimported; its documented architecture was used only as a reference.
- Changed the bypass UX to test-first: a strategy must pass every probe before either the download switch or the Home-screen YouTube flow can use it. Search no longer auto-accepts partial strategies, raw command lines are hidden from the normal UI, failures are caught, and strategy-list timestamps are committed only after a successful write.
- Isolated the long-lived YouTube route in a dedicated `:dpi_vpn` ByeDPI process. Strategy tests and downloads keep using the short-lived `:dpi` process, so two native runtimes never share ByeDPI's process-global C state. While the YouTube session is active, Share -> Kinescope automatically uses the same verified strategy in the isolated download engine.
- Fixed the queue invariant behind stale stopped rows: STOP now removes the in-memory projection after deleting durable state/workspace. Queue cards support end-to-start swipe removal; persistent stop buttons were removed from the row to reduce chrome.
- Tightened the existing glass navigation rather than replacing the design system: narrower max width, smaller controls, lower elevation/shadow, and a compact Home `ByeDPI` action.
- Updated `HANDOFF.md`, `README.md`, `CJM.md`, `design.md`, `ROADMAP.md`, and `THIRD_PARTY_NOTICES.md` with the new architecture and verification boundary.

## Patch 29 — Bypass reliability, fallback strategies, plain-language UI

Reported after patch 28: build succeeds and the app launches, but the bypass did not do
anything when switched on, the background YouTube VPN mode did not work either, the
download folder still showed the pre-rename name on-device, and a downloaded video one
patch back played as a black screen with no sound.

### Fixed
- **Multi-process init race.** `Application.onCreate()` runs in every Android process; the
  `:dpi` / `:dpi_vpn` engine-host processes had no guard, so every time one spawned it also
  ran `DownloadJobStore.restoreToBus()`, `EngineController.ensureReady()` and the yt-dlp
  updater in parallel with the real download worker in the main process, racing the shared
  job journal. `YtOfflineApp.onCreate()` now returns immediately when
  `Application.getProcessName() != packageName`.
- **The bypass switch looked broken because nothing ever verified a strategy for it.** The
  "Use the bypass for downloads" switch can only be turned on once a strategy has passed
  its connection test, but no code path ever ran that test automatically -- so on a fresh
  install/update the switch stayed disabled no matter how many times it was tapped. Settings
  now runs the strategy search by itself the first time the screen opens
  (`DpiPrefs.hasRunInitialSearch`), and any successful search (automatic or manual) also
  turns the switch on, instead of leaving that as a separate step.
- **No runtime fallback strategy.** Only a single verified strategy was ever stored.
  `DpiStrategySearch.run()` gained a `stopAfterFullPasses` parameter (default 1, so every
  existing unit test is unchanged) and the orchestrated search now uses 4, collecting a
  primary plus up to 3 fallbacks. `DpiPrefs.markStrategiesVerified()` stores all of them;
  `DpiStrategyStore.verifiedChain()` exposes them in order. `DpiBypass.startIfEnabled()` and
  `BypassVpnService`'s tunnel start both now try each verified strategy in turn and use the
  first one whose engine actually starts, instead of giving up after a single attempt.
- **Download folder still named `YTOffline` on-device.** The in-code default was already
  `Kinescope`; devices that had the old name explicitly persisted from before the rename
  kept it. `Settings.getDownloadSubfolder()` now migrates that one specific legacy value to
  `Kinescope` the next time it is read; a folder the user deliberately renamed to something
  else is left alone.
- **Downloaded videos playing as a black screen with no sound.** yt-dlp's `bv*+ba` selector
  often picks VP9 video / Opus audio for YouTube even when `--merge-output-format mp4` is
  set, which many Android stock video players cannot decode despite the file being a valid,
  complete `.mp4`. The three video quality presets now ask for H.264 (`vcodec^=avc`) video
  and AAC (`acodec^=mp4a`) audio first, falling back to the previous unconstrained selector
  when a video has no such formats.
- **Plain-language bypass UI.** Removed "ByeDPI", "DNS", "TCP", "TLS", "HTTP", "engine" and
  "packets" from user-facing bypass strings in both `values/strings.xml` and
  `values-ru/strings.xml`; the strings keep their existing names/placeholders, only the
  wording changed. `bypass_test_first_hint` now describes the automatic first-run test.

### Verification status
Code-reviewed, unit-tested (`DpiStrategySearchTest` unchanged and passing) and locally
built; **not yet confirmed on a real device.** See `ROADMAP.md` -> Patch 29 for the specific
device checks still needed.

## Patch 27 — In-app network bypass (bundled ByeDPI engine)

User-requested feature: the user's corporate Wi-Fi is believed to restrict YouTube by inspecting connection headers (DPI), and asked for the approach used by [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) / [ByeDPI](https://github.com/hufrea/byedpi) to be built directly into Kinescope, with Settings controls to turn it on, search for and choose a strategy, and update. Full research and design record: `INTEGRATION_PLAN.md` in the project's memory.

### What was verified before writing this patch (sandbox, not a real device)
- Cloned upstream `hufrea/byedpi` at commit `ba532298`; it is a ~6.6k LOC MIT-licensed C SOCKS5 server implementing "desync" strategies (split/disorder/fake first packets) as CLI flags. It is not a VPN and does not encrypt traffic or hide the IP.
- Vendored those C sources unmodified into `app/src/main/cpp/byedpi/` (win_service.* excluded, Windows-only) and wrote Kinescope's own JNI glue (`dpi_jni.c`) from scratch -- no code from ByeByeDPI's own (GPL-3.0) Kotlin/Java/native-lib.c was used or derived from.
- Built the vendored engine + glue as a host `.so` (glibc, JNI headers fetched from the OpenJDK mirror) and drove it with a JVM test harness: started/stopped the real engine through every built-in strategy, relayed real bytes through it via a hand-written SOCKS5 client to both a plain TCP echo server and a local TLS+HTTP server (full CONNECT -> TLS handshake -> HTTP request path). All built-ins passed both harnesses; a concurrent second engine start is correctly refused.
- Wrote `DpiStrategyParser`, an allowlist-only parser for the engine's desync CLI syntax: it accepts the documented split/disorder/oob/fake/tlsrec/auto/etc. options and their attached-argument and `--long-form` spellings, substitutes the `{sni}` placeholder ByeByeDPI's own list uses, and rejects everything that could change the listen address, read/write a file, or connect elsewhere (`--ip`, `--port`, `--hosts`, `--cache-file`, `--connect-to`, `--daemon`, ...). Verified against the real upstream `proxytest_strategies.list` (all 60 current lines accepted, including the upstream `-e/--oob-data` byte option) and ~30 hand-written hostile inputs (path/host/port overrides, shell metacharacters, and getopt long-option-abbreviation collisions such as `-daemon`, `-de`, `-nno-domain`). 18 JVM unit tests across the parser, `NetworkCheck` (extended for the bypass path), and the strategy search.

### Added
- `app/src/main/cpp/byedpi/` -- vendored unmodified ByeDPI C sources (MIT license, provenance in `KINESCOPE_VENDOR.txt`).
- `app/src/main/cpp/dpi_jni.c` + `CMakeLists.txt` -- Kinescope's own JNI glue; 16 KB-page-aligned link flags (`-Wl,-z,max-page-size=16384`).
- `DpiNative.kt` -- JNI declarations (`nativeRun`/`nativeStop`), loads `libkinescope_dpi.so`.
- `DpiEngineService.kt` -- hosts the engine in its own `:dpi` process (declared in `AndroidManifest.xml`), killed after every run so the engine's process-wide C globals never carry state between strategies or downloads.
- `DpiEngine.kt` -- binds/unbinds the service, waits for the SOCKS5 port to answer (bounded, polling), exposes an `AutoCloseable` `BypassSession`.
- `NetworkCheck.kt` -- layered direct-vs-bypassed reachability probe (DNS/TCP/proxy/CONNECT/TLS/HTTP stages) with a `Verdict` classifying which layer, if any, is blocked and whether the bypass gets past it.
- `DpiStrategies.kt` -- `DpiStrategyParser` (above) and a small offline `DpiBuiltInStrategies` list so the feature works before any list update and without network access.
- `DpiStrategyStore.kt` -- `SharedPreferences` for the enabled flag and selected strategy, plus an HTTPS-only (size- and count-bounded) download of ByeByeDPI's own community strategy list, re-validated through the same parser before it is ever used.
- `DpiSearch.kt` (`DpiStrategySearch`) -- tries candidate strategies in turn against three real probe hosts (`www.youtube.com`, `i.ytimg.com`, `redirector.googlevideo.com`); stops at the first strategy that gets all three through, otherwise keeps the one with the most hosts through.
- `DpiBypass.kt` -- glue used by both the download path and the Settings UI: starts the engine for a download when enabled, and runs `NetworkCheck` to diagnose direct-vs-bypassed reachability per host.
- `BypassSettingsSection()` in `BypassSettings.kt`, wired into `SettingsScreen`: on/off switch, "Find a working strategy" (progress + Stop, per-result "Use" buttons), "Test the connection" (direct vs. bypassed stage-by-stage result + plain-language verdict), "Update the strategy list".
- `DownloadService.executeAttempt()`: when the bypass is enabled, starts the engine for the duration of that attempt and points yt-dlp's `--proxy` at `socks5h://127.0.0.1:<port>` (the `h` matters: the engine resolves the host name itself, so DNS filtering of the device does not by itself defeat it); the session is always closed in a `finally`, including on exception.
- New JVM tests: `DpiStrategyParserTest`, `NetworkCheckTest` (bypass-path SOCKS5 fixtures, `Verdict.DNS_BLOCKS_BYPASS`), `DpiStrategySearchTest`.
- `.devcontainer/setup.sh` and `.github/workflows/build-release.yml` now also install `ndk;27.2.12479018`, required to compile the new native code.
- `THIRD_PARTY_NOTICES.md`, `README.md` updated for the new dependency and feature.

### Explicitly not done in this patch
- Not verified on a real device or a real GitHub Actions run (new `ROADMAP.md` gate). In particular, whether CMake cross-compiles cleanly for arm64-v8a in that exact toolchain is unproven -- only a host (glibc, x86_64) build was tested.
- Not on by default; the user must switch it on in Settings.
- Does not claim to fix DNS- or IP-level blocking; the Verdict text says so explicitly (`DNS_BLOCKED`, `DNS_BLOCKS_BYPASS`) rather than implying the bypass is a universal fix.
- Does not touch `DownloadErrorClassifier`; that stays unrelated to whether a bypass is active.

## Patch 25c — Android string-resource compile hotfix

Patch 25b applied the full Patch-25 roadmap-completion tree, but its local
verification correctly stopped at `:app:mergeDebugResources`: the English
`error_control_cleanup_failed` resource used the ASCII apostrophe in
`Couldn't` without Android string-resource escaping. The XML was well-formed,
so the earlier generic XML/static validation did not catch the AAPT-specific
string grammar.

### Fixed
- Escaped the apostrophe as `Couldn\'t`, unblocking AAPT resource compilation.
- Extended the delivery-time static verification to reject unescaped ASCII
  apostrophes in string-resource text while still allowing XML entities and
  escaped apostrophes.
- Re-ran the full Patch-25 local gate: `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug` must all succeed before this recovery patch records itself as
  complete and removes the superseded delivery scripts.

## Patch 25 — Reliability roadmap completion and release hardening

Patch 24 was successfully applied, locally built and pushed by the user (`91169f5`; `BUILD SUCCESSFUL`). Patch 25 consumes the still-relevant engineering work from the former lowercase S0-S11 audit and closes everything that can be completed autonomously in Codespaces. Real-device and real-release verification stays open in `ROADMAP.md` and is deliberately not marked done here.

### Durable queue and lifecycle
- Added `DownloadJobStore`, an `AtomicFile`-backed journal written **before** foreground-service dispatch. Accepted jobs therefore survive process death as explicit recoverable `INTERRUPTED` rows instead of disappearing from the in-memory `StateFlow`.
- Journal restoration cleans stale `MediaStore` pending rows, normalizes jobs that died while executing, merges safely with any very-fast fresh enqueue, and removes orphaned per-job workspaces.
- Reworked the queue around stable `QualityId` values rather than list indexes; old index preferences migrate transparently.
- Closed two queue-control races found during the final patch-25 review: a Pause arriving after `queue.poll()` but before `runJob()` can no longer be cleared/ignored, and an idle worker can no longer call an unconditional `stopSelf()` after a newer start has already arrived. Idle shutdown now uses the service `startId` / `stopSelfResult()` contract and hands queued work to a successor worker safely.
- Serialized yt-dlp/ffmpeg initialization, execution and self-update behind `EngineController`, preventing an updater from replacing the executable while a download uses it.
- Added Android 15 `dataSync` foreground-service timeout handling. An active job is persisted as recoverable before the service stops.

### Input, output and storage correctness
- Added a shared strict `YouTubeUrlParser`: only supported YouTube video URL forms are canonicalized; playlist-only links, lookalike hosts, userinfo, non-standard ports, bad schemes and oversized shared input are rejected. Active duplicates are rejected by video ID.
- Added pure `DownloadErrorClassifier` so yt-dlp text matching is typed/testable rather than mixed into localized UI copy.
- Each job now owns `cacheDir/jobs/<id>` and resumes inside that workspace. Final-output lookup is job-local, orphan cleanup is deterministic, and `--no-playlist` is explicit.
- Video fallback selectors are bounded to the selected maximum resolution instead of allowing `/b` to silently exceed it.
- MediaStore publication is a real commit boundary: actual output extension drives MIME detection where possible, `IS_PENDING=0` must succeed before the private source is deleted, and an interrupted pending URI is journaled for cleanup/retry.
- Library queries include the current and historically used Kinescope subfolders, exclude pending rows, expose local size/date/type metadata, and run off the Compose/UI thread. Deletion now requires confirmation.

### UX, notifications, privacy and diagnostics
- Expanded job states to preparing/running/processing/saving/paused/interrupted/done/failed/stopped, with Resume available for recoverable states.
- Added notification tap/Stop actions, throttled progress notifications and a completion notification. Runtime notification permission is requested only when the user actually accepts a download, not on cold start.
- Disabled Android app backup while cookies/logs/job journal exist. `AppLog` now redacts URLs, known YouTube cookie values and app-private paths before persistent logging **and** Logcat; throwable output is bounded to the error type/message plus a short sanitized stack prefix.
- Updated light-theme action/status colors for stronger contrast while preserving the existing Kinescope palette.

### Tests, environment and release
- Added JVM unit tests for YouTube URL parsing, yt-dlp error classification and diagnostic privacy redaction; added the Android coroutines runtime explicitly.
- Release Actions now run `testDebugUnitTest` + `lintDebug` before assembly, serialize concurrent release runs, and verify signature, zip alignment, package name, requested version and arm64-only native contents before publishing the APK + SHA-256.
- `.devcontainer/setup.sh` now pins the official Android command-line tools Linux archive and verifies its SHA-256 instead of scraping a mutable webpage during Codespace creation.
- Added `THIRD_PARTY_NOTICES.md` and refreshed `README.md`, `ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `AGENTS.md`, `RELEASE.md` and `design.md` for the post-roadmap architecture. The handoff also removes a stale pre-first-install note: `com.kinescope.app` is now an established package on the user's Android 13 device and should be treated as fixed.

### Deliberate non-changes / upstream blockers
- The older audit's "no authentication / no embedded browser" assumption is not restored: the user's later explicit Sprint-1 requirement for optional YouTube WebView session capture supersedes it.
- Playlist expansion, cross-device sync/backend and custom YouTube extraction remain out of scope.
- The app stays on published `youtubedl-android 0.18.1`. Current upstream has an open 16 KB page-size native-payload issue, so Kinescope does **not** claim 16 KB-device compatibility until upstream ships a verified fix.

## Patch 24 — Sprint 1: resilient downloads, account session, localization, navigation and release publishing

First feature sprint after the original roadmap reached steady state. The implementation is complete in code but remains **pending real-device / real-GitHub verification**; per `AGENTS.md`, none of the device-dependent behavior below is considered confirmed until the user reports it working.

### Download reliability and control
- Switched yt-dlp self-update from `STABLE` to `NIGHTLY`. This follows current upstream guidance: stable can lag behind site changes, while nightly is the recommended channel for regular users.
- Added a bounded self-healing chain for YouTube verification / 403 / 429 failures: normal request (with saved YouTube cookies when available) -> refresh yt-dlp nightly -> `web_safari` + IPv4 fallback -> logged-out `android_vr` fallback. Each attempt retains yt-dlp's own extractor and uses modest retries / randomized delay rather than adding custom extraction or bot-bypass logic. If YouTube still refuses the request, the job is parked as resumable `PAUSED` rather than terminal `FAILED`; a successfully captured account session automatically resumes those verification-paused jobs.
- Added optional YouTube WebView session capture. Authenticated YouTube cookies are written in Netscape format to app-private storage and passed to yt-dlp together with the captured WebView User-Agent. No OAuth token/backend is introduced. Google may still reject embedded sign-in on some devices, and an authenticated session cannot guarantee recovery from an IP-level YouTube block.
- Added Pause / Resume / Stop controls. Running jobs are cancelled through youtubedl-android's `destroyProcessById(processId)` API; Pause retains temp fragments and Resume requeues the same job with `--continue`; Stop removes temp fragments.
- Added an extractor-readiness check in `DownloadService`, closing the old race where a very fast first download could theoretically beat `Application`'s background yt-dlp initialization.

### UI, localization and diagnostics
- Externalized the application UI into resources and added `values-ru/strings.xml`. Android automatically uses Russian when the device/application locale is Russian and the existing English resources otherwise.
- Replaced the old settings toggle / bottom composer with a three-action bottom navigation surface: Home, prominent center Add, Settings. The treatment is a translucent, elevated, softly bordered glass-style surface built from the existing design tokens.
- Android Back from Settings / Add now returns Home; the YouTube browser and hidden log journal return to Settings.
- Added a private rotating application log (state transitions and errors; no cookie values). Five quick taps on the Settings navbar icon open an in-app log journal with refresh, clear and explicit share actions.

### Build and visual identity
- Removed `.github/workflows/build-debug.yml`. The release workflow now validates inputs/secrets, uses Gradle caching, performs a clean signed build, verifies the APK with `apksigner`, writes a SHA-256 checksum, keeps a workflow artifact backup and publishes both files into a versioned GitHub Release.
- Reworked the launcher icon into an original retro-TV motif using Kinescope's cream / terracotta / warm-dark palette, with layered glass-like highlights. Added an Android 13+ monochrome layer for themed icons.
- Updated `README.md`, `ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `AGENTS.md`, `RELEASE.md`, and `design.md` to describe the new behavior and the still-required verification pass.
- Sprint delivery remains a single exact-match/hash-guarded Python patch. It runs static checks plus `./gradlew --no-daemon assembleDebug`; only after that build succeeds does the patch script delete itself from the repo.

## Patch 23 — Documentation overhaul: Step 5 confirmed working, project reaches steady state

The user confirmed patch 22's fix: the app now runs correctly end to
end on a real device (Android 13) — the startup crash is gone, and
the core install/share-or-paste/queue/download/play loop works. This
patch is documentation only (no app code changes).

### Changed
- `README.md` — fully rewritten: build-status badges for both GitHub
  Actions workflows, an accurate tech-stack table (Kotlin 2.1.0,
  Compose BOM `2024.11.00`/Material3 1.3.1, `youtubedl-android`
  0.18.1, Gradle 8.10.2/AGP 8.7.2, `minSdk` 29/`compileSdk`/`targetSdk`
  35, `arm64-v8a`-only), and a "Status" section reflecting the app's
  actual working state instead of the pre-first-build snapshot patch
  15 originally wrote.
- `ROADMAP.md` — consolidated. The original 9-step plan is done or
  reduced to specific, named technical debt; collapsed from a 363-line
  step-by-step checklist to a short "Current state" summary plus a
  single "Technical debt" section (the one remaining Step 9
  device-confirm item, Step 5's not-yet-individually-confirmed
  adversarial checks, the Backlog items, and the still-queued
  `roadmap.md` lowercase audit). Full step-by-step history stays in
  `CHANGELOG.md`/`HANDOFF.md`, which this file now points to rather
  than duplicating.
- `HANDOFF.md` — "Project identity", "What's actually done vs. still
  open", and "Immediate next step" rewritten for the new steady state:
  Step 5's crash-fix is confirmed by the user; basic on-device
  functionality (install, permissions, share/paste, download, play,
  background persistence) is confirmed working; the adversarial edge
  cases (race-condition stress test, `friendlyError()` against a real
  broken video, airplane mode, a very large download) remain open,
  unconfirmed technical debt, not assumed to have passed just because
  the app runs now. The generic "how to work in this repo" checklist
  that used to live in "Immediate next step" has moved to the new
  `AGENTS.md` instead of staying duplicated across two files. Also
  fixed two stale lines caught while reviewing this file: the file map
  still described `RELEASE.md` as using old `yt-offline` naming
  (patch 20 already renamed it) and described `ROADMAP.md` as a
  "checkbox-tracked implementation plan" (no longer accurate after
  this patch). Patch-by-patch narrative (patches 01–22) is unchanged.
- `.gitignore` — added `*.jks.b64` and `*.jks.base64.txt`. Found while
  reviewing the repo for this patch: `kinescope-release.jks.b64` (an
  empty, 0-byte placeholder, currently harmless) is tracked in git and
  wasn't covered by the existing `*.jks`/`*.keystore` rules — if that
  filename were ever reused to actually hold a base64-encoded
  keystore (e.g. following `RELEASE.md`'s manual base64 steps but
  writing the output inside the repo folder by habit), it would
  commit the signing key straight into git history. Not a live leak
  today, but a real landmine for next time.

### Added
- `AGENTS.md` — a process/workflow file for AI coding agents working
  on this repo (source-of-truth reading order, verification
  discipline, patch-script delivery/testing conventions, scope
  discipline), following the emerging AGENTS.md convention. Complements
  rather than duplicates `CLAUDE.md` (project ground rules/constraints)
  and `HANDOFF.md` (state snapshot).

### Findings (closed)
- **`kinescope-release.jks.b64` is git-tracked and not gitignored.**
  Currently empty and harmless, but the filename invites exactly the
  kind of accidental-secret-commit `RELEASE.md`'s own base64 step
  warns about. Fixed via `.gitignore` (see above). Recommended,
  not automated by this patch: `git rm --cached kinescope-release.jks.b64`
  to stop tracking the empty file too (see delivery commands).

## Patch 22 — Fixed a startup crash found on the first real-device launch (Step 5)

The user's first real-device install (Android 13) crashed immediately.
`EmptyQueueState` is what a fresh install shows first (queue empty, no
downloads yet) — that's the exact composable that crashed, on its
`painterResource()` call.

### Changed
- `app/src/main/java/com/kinescope/app/MainActivity.kt` —
  `EmptyQueueState`'s icon no longer loads
  `android.R.drawable.stat_sys_download` via `painterResource()`. That
  framework resource is an `AnimatedVectorDrawable` on real devices
  (it's the system's own animated download-in-progress notification
  glyph); Compose's `painterResource()` only supports a plain static
  `VectorDrawable` or a rasterized image (PNG/JPG/WEBP), not an
  API-driven XML type like an animated-vector — confirmed against
  `painterResource()`'s own documentation, which states this
  restriction explicitly ("API based xml Drawables are not supported
  here"). Loading one this way raises `IllegalArgumentException` at
  runtime, every time — which for this composable means immediately,
  on a fresh install.
- Added `app/src/main/res/drawable/ic_download.xml` — a small,
  hand-authored static vector (arrow + tray), reusing the same visual
  motif as `ic_launcher_foreground.xml` for consistency. Deliberately
  not `material-icons-extended` (still avoided for one glyph, per
  `CLAUDE.md`'s zero-required-cost/no-bloat spirit) and not the
  framework resource that just crashed.

### Findings (closed)
- **`android.R.drawable.stat_sys_download` cannot be loaded via
  Compose's `painterResource()`.** It's an `AnimatedVectorDrawable` on
  real devices, not a static `VectorDrawable` or a raster image —
  `painterResource()`'s own documentation explicitly scopes support to
  those two types only. `DownloadService.buildNotification()`'s
  `setSmallIcon()` use of the same resource is unaffected and
  deliberately left as-is: Android notifications accept any drawable
  resource id directly (no Compose involved), which is exactly what
  this animated icon is designed for.
- **`./gradlew assembleDebug` succeeding does not catch this class of
  bug.** Nothing about this crash shows up at compile time — it's a
  resource-type mismatch that only manifests when the composable
  actually runs on a device, which is exactly the gap Step 5's
  real-device checklist exists to catch.

## Patch 21 — APK size trim + keystore-password fix

Follow-up from actually running patch 20's CI signing workflow for the
first time; both findings below came from that real run, not review.

### Changed
- `app/build.gradle.kts` -- `ndk.abiFilters` trimmed from all four ABIs
  down to `arm64-v8a` only. `youtubedl-android`'s bundled Python
  runtime, ffmpeg, ffprobe, and QuickJS native libraries (duplicated
  per ABI) are what made the first real signed build ~200MB; arm64-v8a
  covers the overwhelming majority of real phones since ~2019. Add
  `armeabi-v7a` back if an older 32-bit device needs to install this.
- `RELEASE.md`'s Step 1 -- keytool command is now non-interactive
  (`-storepass`/`-keypass` both set from one generated variable)
  instead of relying on typing the same password twice at separate
  interactive prompts, and now states plainly that store/key passwords
  **must** be identical for a PKCS12 keystore, not merely "can be the
  same."

### Findings (closed)
- **PKCS12 requires identical store/key passwords.** Java's PKCS12
  keystore implementation doesn't support a separate per-key password;
  giving `keytool` two different ones makes it silently keep only the
  store password for the real encryption. The keystore then fails
  later with `KeytoolException: ... Given final block not properly
  padded` when Gradle tries to read the key with the (wrong, ignored)
  key password -- a decryption-with-the-wrong-password error that
  gives no hint the actual cause was two mismatched passwords entered
  at generation time. Cost several regeneration cycles during Step 9
  setup before the actual cause was found.
- **The default Codespaces `gh` CLI token can't write repo secrets.**
  `gh secret set` fails with `HTTP 403: Must have admin rights to
  Repository` using the ambient auth a Codespace provides by default --
  a known `gh`/Codespaces limitation, not specific to this project.
  Needs a separate Personal Access Token (classic, `repo` scope)
  supplied via `GH_TOKEN=<pat> gh secret set ...` to actually write
  repository-level Actions secrets from inside a Codespace.

## Patch 20 — Step 9: CI-based signed release build

### Added
- `.github/workflows/build-release.yml` -- manual-trigger workflow that
  builds a signed release APK. Assembles `keystore.properties` at
  runtime from four repo secrets (`KEYSTORE_BASE64` decoded to a
  runner-local temp file, plus `KEYSTORE_PASSWORD`/`KEY_ALIAS`/
  `KEY_PASSWORD`), runs `assembleRelease` against the existing signing
  config in `app/build.gradle.kts` (unchanged by this patch), then
  deletes both the decoded keystore and `keystore.properties` in an
  `if: always()` step. Mirrors `build-debug.yml`'s manual
  `workflow_dispatch` trigger and run-number-derived `versionCode`.
- `RELEASE.md`'s "CI build (GitHub Actions)" section, documenting the
  one-time secrets setup and how to trigger the new workflow. Kept the
  existing local-build path (`assembleRelease` in the Codespace) as
  Option A alongside it.

### Changed
- `RELEASE.md` -- renamed leftover `yt-offline` keystore
  filename/alias and GitHub release title to `kinescope` (flagged as
  cosmetic debt in patch 19).
- `README.md` -- build section mentions the new release workflow; the
  `RELEASE.md` doc-map entry no longer says "not started yet".
- `ROADMAP.md`, `HANDOFF.md` -- Step 9 status split into what this
  patch delivered (the CI workflow itself) vs. what still needs the
  user's own action before Step 9 counts as done: generating a
  keystore, adding the four repo secrets, and confirming one real
  signed run actually installs on a device. Not marking this done
  without that confirmation follows the same discipline
  `build-debug.yml` was held to (not confirmed until patch 18's real
  Actions run succeeded).
- `CLAUDE.md` -- instruction log: recorded the CI-for-release decision.

## Patch 19 — Documentation/handoff update, pivot to Step 9

No app code changes; patch 17/18's own scripts needed a small fix (see
below).

### Changed
- `ROADMAP.md` — recorded that patch 18's CI fix is confirmed by a
  real, successful GitHub Actions run (not just sandbox simulation),
  and that the user has downloaded a debug build via it. Recorded an
  explicit decision: Step 9 (signed release) starts next, ahead of
  Step 5's manual checklist being individually confirmed — updated the
  execution order, Step 5's closing note, and Step 9's gate language
  accordingly (the original caution stays as context, not deleted).
  Step 8 marked done in the execution order (CJM.md, patch 17).
- `HANDOFF.md` — milestone paragraph, "what's done/not done" section,
  patch history, and "Immediate next step" all updated for the above;
  the latter now also states the sprint-based delivery convention
  (finish a batch of work, then one patch script per sprint rather than
  per tiny step) and a brief note on APK size (debug builds are
  expected to be larger; release won't shrink dramatically either,
  since `isMinifyEnabled = false` is deliberate — see Backlog's
  `ndk.abiFilters` item if size ever needs addressing).
- `CLAUDE.md` — instruction log: recorded the Step 9-starts-now
  decision as a standing directive (don't re-litigate in a new
  session), and the sprint-based patch-delivery convention.
- `patch16_ci_workflow_and_changelog.py`,
  `patch17_customer_journey_map.py`, and
  `patch18_fix_ci_jdk_pin_leak.py` — the now-familiar pattern, two
  levels deep this time: `patch_claude_md()` (patch 16),
  `patch_roadmap()` (patch 17), and `patch_changelog()`/`patch_handoff()`
  (patch 18) each gained a `superseded_marker` for this patch's direct
  edits to those files. Then, since patch 17 also patches patch 16's
  *script file* (`patch_patch16_script()`, added back in patch 17 to
  fix the ROADMAP.md/HANDOFF.md collision described in that patch's own
  entry), and this patch's fix to patch 16's script (the
  `patch_claude_md()` guard above) changes that same script file
  *again*, patch 17's check on it needed a `superseded_marker` too —
  caught only on a second full-chain regression run after the first
  round of fixes above, since the first round's fixes had to actually
  exist before this second-order collision could even surface.

## Patch 18 — Fixed the GitHub Actions build failure

### Fixed
- **The `Build Debug APK` workflow (patch 16) failed on its first real
  run**: `Value '/usr/local/sdkman/candidates/java/21.0.10-ms' given
  for org.gradle.java.home Gradle property is invalid`. Root cause:
  patch 12 wrote this JDK pin directly into the project's own
  **committed** `gradle.properties`, which is correct for this one
  Codespace (that exact path exists there) but wrong for literally
  every other environment that clones the repo — including the GitHub
  Actions runner added four patches later in patch 16, where that path
  doesn't exist and Gradle refuses to start at all.
- Fixed by moving the pin out of the committed, project-level
  `gradle.properties` and into the user-level
  `$HOME/.gradle/gradle.properties` instead — Gradle already gives
  user-level `gradle.properties` higher precedence than the
  project-level one (confirmed against Gradle's own build-environment
  documentation), and that file lives outside the repo entirely, so it
  never gets committed or shipped anywhere. `.devcontainer/setup.sh`'s
  JDK-detection logic (unchanged) now writes there instead of into the
  tracked file. The stale, invalid line removed from the committed
  `gradle.properties`, which is what immediately unblocks the GitHub
  Actions build.
- Verified: simulated both the Codespace path (fake SDKMAN JDK
  directories, confirmed the pin lands in `$HOME/.gradle/gradle.properties`
  and the project file stays untouched, across two runs for
  idempotency) and confirmed the committed `gradle.properties` no
  longer contains any machine-specific path.

## Patch 17 — Customer Journey Map

### Added
- `CJM.md` — the five-stage Customer Journey Map (prep at home → queue
  & download → departure/loses access → watch offline in-region →
  return & refresh library) originally produced during the 4-part
  review, written up as a standalone living document rather than left
  implicit in `ROADMAP.md`'s prioritization. States the key finding
  explicitly: stage 1 → stage 3 is a one-way door with no retry once
  internet access is lost, which is why every crash/race/silent-failure
  fix in Steps 1/3/4 was ranked Critical/High regardless of how narrow
  the trigger condition looked on paper.

### Changed
- `ROADMAP.md` — Step 8's `CJM.md` checkbox marked done.
- `patch16_ci_workflow_and_changelog.py` — `whole_file_guarded_replace()`
  gained an optional `superseded_marker` parameter, and its
  `patch_roadmap()` **and** `patch_handoff()` calls now use it (patch
  17 modifies both files after patch 16 already did). Caught by this
  patch's own multi-pass full-chain regression test: since this patch
  further modifies `ROADMAP.md` and `HANDOFF.md` after patch 16 already
  did, re-running the full chain from a clean copy made patch 16's own
  idempotency check fail on its second pass for both files (the file no
  longer matched either patch 16's "old" or "new" expected content,
  because patch 17 had since changed it again) — the same "later patch
  breaks an earlier patch's idempotency check" failure mode as patch
  16's own fix for patches 07/13/14/15, recurring one layer deeper.
  This is expected to keep recurring for any future patch that touches
  `ROADMAP.md` or `HANDOFF.md` again; each one should budget time to
  vaccinate its immediate predecessor the same way.

## Patch 16 — CI build workflow, changelog process

### Added
- `.github/workflows/build-debug.yml` — manual-only (`workflow_dispatch`)
  GitHub Actions workflow that builds a debug APK and uploads it as a
  run artifact, so the phone-sideload path no longer depends on adb or
  downloading a local Codespace build through the browser. Version name
  is supplied by hand on each run; `versionCode` is derived from the
  Actions run number so it always increases (avoids
  `INSTALL_FAILED_VERSION_DOWNGRADE` when reinstalling over an older
  build on the same device). No automatic trigger — always run by
  hand, one version per run.
- `CHANGELOG.md` (this file) and the process it establishes.

### Changed
- `app/build.gradle.kts` — `versionCode`/`versionName` now read
  optional Gradle properties `appVersionCode`/`appVersionName` (set by
  the new workflow via `-P`), falling back to the existing hardcoded
  `7` / `"1.0.0"` when absent — local Codespace builds
  (`./gradlew assembleDebug` with no `-P` flags) are unaffected.
- `ROADMAP.md` — Steps 1, 2, 3, 4, 6 (6.1-6.6), and 7 collapsed to a
  one-line "done, see CHANGELOG.md" pointer each; their closed Appendix
  findings (originally rows 1-15, 18, 21-32, 34-35) removed from the
  Appendix table for the same reason, with a note explaining where they
  went. Step 8's README checkbox corrected to `[x]` — the work was
  actually done in patch 15 but the checkbox was never flipped. The
  optional, never-done `ndk.abiFilters` trim moved from Step 1 into
  Backlog, since it was never actually blocking anything.
- `CLAUDE.md` — instruction log: build via GitHub Actions (manual
  trigger, hand-assigned version) instead of a local Codespace
  `./gradlew` + manual download; completed `ROADMAP.md` items get
  removed from that file and logged here on completion.
- `README.md` — Building section now mentions the Actions-based build
  path alongside the local Codespace one.
- `HANDOFF.md` — patch history extended with Patch 15 (missing until
  now) and this entry; file map mentions `CHANGELOG.md` and the new
  workflow file.
- `patch07_fix_setup_and_verify_imports.py`,
  `patch13_fix_invalid_xml_comment.py`,
  `patch14_fix_updatechannel_import.py`,
  `patch15_docs_after_first_build.py` — each got a short-circuit guard
  added to its `patch_roadmap()` function: if patch 16's Appendix-trim
  marker is present, skip that patch's ROADMAP.md edit entirely instead
  of either failing loudly (its anchor rows/text are gone) or, worse,
  silently resurrecting content patch 16 intentionally removed (row 34
  and 35's insertion anchors survive patch 16 untouched, so without
  this guard a repeated full-chain run would have quietly re-added
  them). Caught by this patch's own multi-pass full-chain regression
  test — the exact failure mode `HANDOFF.md`'s "Key learnings" already
  documents from patches 11/12 and 07/14's earlier collision, now
  recurring between 07/13/14/15 and this patch.

## Patch 15 — Documentation consolidation after the first successful build

### Added
- `README.md` fully rewritten (previously a bare "# kinescope" title):
  what the app is/isn't, build instructions, documentation map.

### Changed
- `HANDOFF.md` refreshed: patch history through patch 14, "what's
  done" summary updated for the successful build, new "Key learnings"
  section.
- `ROADMAP.md` top status block updated; Step 2's import-path checkbox
  marked done (confirmed by a real compile, not just pre-verified);
  the two scattered Step 5 environment notes from patches 07 and 12
  consolidated into one.

## Patch 14 — Fixed the final compile error; first successful build

### Fixed
- `UpdateChannel` is a nested class of `YoutubeDL`
  (`com.yausername.youtubedl_android.YoutubeDL.UpdateChannel`), not
  top-level as patch 07 concluded from a README comment that dropped
  the qualifying prefix. Confirmed by `git clone`-ing the actual
  library at the pinned `0.18.1` tag and reading the real source
  directly. Appendix finding #11 (now closed, see above).
- **After this patch, `./gradlew assembleDebug` succeeded for the
  first time in this project's history.**

## Patch 13 — Fixed the first real compile error

### Fixed
- `ic_launcher_foreground.xml` had `--` inside an XML comment body,
  which the XML spec forbids anywhere except the closing `-->`.
  Confirmed via a regex scan that this was the only occurrence in the
  repo.

## Patch 12 — Fixed the JDK/Gradle mismatch

### Fixed
- Broadened patch 11's JDK search from "exactly 17" to any JDK Gradle
  8.10.2 can run on (17-23, per Gradle's own 8.10 release notes).
  Diagnostics confirmed the real cause: this Codespace's `java`/`javac`
  on `PATH` resolve to a Codespace-provided JDK 25.0.2, separate from
  and taking priority over the devcontainer's SDKMAN-managed install
  (which only has `21.0.10-ms` and `25.0.2-ms`, no 17.x, despite
  `devcontainer.json` requesting 17). Pinned Gradle to the
  already-installed `21.0.10-ms` via `org.gradle.java.home` in
  `gradle.properties`. **This is what actually fixed the JDK
  mismatch** — the next build got past environment setup for the
  first time.

## Patch 11 — First JDK/Gradle mismatch fix attempt

### Added
- Auto-detection of an installed JDK 17 via SDKMAN, pinned through
  `org.gradle.java.home` if found. Didn't fix anything yet — this
  Codespace has no JDK 17 at all (see patch 12).

## Patch 10 — `setup.sh` re-run safety

### Fixed
- The Android cmdline-tools download/extract/`mv` block failed on a
  second run ("Directory not empty"). The `.bashrc` export block also
  duplicated itself on every run. Both guarded to skip/no-op when
  already done.

## Patch 09 — Fixed a stale ROADMAP.md cross-reference

### Fixed
- Step 5's section referenced "Step 7 (signed release)" from before
  the Kinescope-rename step was inserted as its own Step 7; signed
  release has been Step 9 since patch 06. Documentation only.

## Patch 08 — Documented the two-roadmap situation

### Added
- Recorded, in both `ROADMAP.md` and `HANDOFF.md`, that this repo has
  two separate roadmap files (`ROADMAP.md` by Claude Fable 5.1,
  `roadmap.md` by GPT Astra) and the required execution order: finish
  `ROADMAP.md` through Step 9 first, then `roadmap.md`, then delete
  both. Documentation only.

## Patch 07 — Pre-Step-5 environment fix

### Fixed
- `.devcontainer/setup.sh`'s `pipefail` + `yes | sdkmanager --licenses`
  combination could silently abort setup before the Gradle wrapper was
  ever generated (SIGPIPE). Fixed by disabling `pipefail` around just
  that pipeline and checking `sdkmanager`'s real exit status.

### Verified
- Pre-verified `youtubedl-android`/`ffmpeg` import paths and API
  shapes against the library's README and sample app. **This
  pre-verification turned out to be incomplete** — see patch 14.

## Patch 06 — Kinescope rename

### Changed
- `namespace`/`applicationId` → `com.kinescope.app`,
  `rootProject.name` → `kinescope`. Every Kotlin source file moved
  from `com/baltic/ytoffline/` to `com/kinescope/app/` with matching
  `package` declarations. `app_name` in `strings.xml` → "Kinescope",
  plus the notification title and `TopAppBar` title so the rebrand
  isn't half-done on screen. `design.md`'s "YT Offline" mentions
  updated. Internal-only identifiers (`YtOfflineApp`, `YtOfflineTheme`,
  `YtOfflineExtras`, etc.) deliberately left unchanged — not
  user-visible, not in Step 7's scope.

## Patch 05 — Documentation only

### Added
- `HANDOFF.md` and the `ROADMAP.md` status section, established in the
  previous session.

## Patch 04 — Step 6.5: component patterns per screen

### Added
- Queue-row thumbnail placeholders, a real `LinearProgressIndicator`
  (added `progressFraction: Float?` to `DownloadJobStatus`), an
  empty-queue illustration, a Library overflow menu with working Share
  (`Intent.ACTION_SEND`) and Delete (`ContentResolver.delete()`), a
  sectioned Settings screen with a persisted yt-dlp-last-updated
  timestamp, a dismissible connectivity-loss banner, a composer-bar
  focus-border fix.

## Patch 03 — Step 6.1/6.2/6.3/6.6: design system tokens

### Added
- A real dark `ColorScheme` (previously light-only), `surfaceRaised` /
  `warning` / `errorContainer` tokens via a `CompositionLocal`-backed
  `YtOfflineExtras` object, a completed typography scale.

### Fixed
- Adaptive-icon safe-zone clipping.

## Patch 02 — Step 4: product-quality fixes

### Fixed
- Filename humanization (yt-dlp's own title template, with a bracketed
  job-id tag for finding the output file afterward), job-id-tag-based
  output file scanning (replacing an exact-filename assumption),
  `MediaStore.RELATIVE_PATH`'s trailing-slash mismatch between insert
  and query, atomic `DownloadQueueBus` updates
  (`MutableStateFlow.update {}`), Downloads-subfolder-name
  sanitization, YouTube-host validation on shared/pasted URLs, an
  `ActivityNotFoundException` guard on the video-player launch intent.

## Patch 01 — Steps 1-3: compile blockers, critical runtime fixes

### Fixed
- Fabricated Compose BOM version replaced with a real one
  (`2024.11.00`). `execute()`'s progress-callback arity confirmed
  3-parameter against the library's own sample-app source.
  `updateYoutubeDL()`'s required `UpdateChannel` argument added. The
  `DownloadService` worker race condition fixed (blocking consumer
  loop + lock-guarded state transition, tighter than the roadmap's own
  sample fix). A catch-all exception handler added so one bad download
  can no longer crash the whole process. Dead
  `requestLegacyExternalStorage="true"` removed.

