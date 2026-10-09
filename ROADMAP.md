# Roadmap

State after patch 39 (2026-10-04). This file tracks only what is still open: device verification gates,
technical debt and what is out of scope. How each item was closed lives in `CHANGELOG.md`; the state a new
session needs is in `HANDOFF.md`.

## Where things stand

- **Confirmed on the user's device:** the build succeeds; downloads work; the DPI bypass strategy search and its
  ranking work; the compact log and Copy report work; Instagram Reels download and play (reported working after
  patch 38, 2026-10-04). Earlier install, share/paste, queue, offline playback and background persistence are also
  confirmed.
- **Reported and not fixable from code:** the Play Protect "App blocked" dialog on install (see `RELEASE.md`).
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
  otherwise); after an update the first launch re-checks in the background and shows it again while the previous
  strategies stay usable.
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
- **Two large files.** `DownloadService.kt` (about 1,250 lines: queue, recovery ladder, notifications, publication)
  and `MainActivity.kt` (about 1,600 lines: every screen) each hold several responsibilities. They were left whole
  on purpose: a move-only split cannot be verified without a device, and the protected baseline matters more.
  Split by responsibility (notifications, recovery ladder; Home, Add, Settings, Logs) only together with the
  device gates above.
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
