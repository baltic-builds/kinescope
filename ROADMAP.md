# Roadmap

State after patch 58 (2026-10-31). This file tracks only what is still open: device verification gates,
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
- **Patch 46 (2026-10-17) changes when the bypass upkeep runs (the strategy re-check part was replaced by patch 55).** The strategy re-check no longer follows an app
  update; the app remembers which strategies really carried traffic and keeps them in the fallback chain, ordered by
  how well they worked; the yt-dlp update and the strategy re-check now run every 25 days. None of it is confirmed on a
  device yet (see "Learned strategies and the 25-day upkeep" below).
- **Patch 47 (2026-10-17) is the audit of patch 46.** The quiet re-check waits for an unmetered network; a refused
  foreground start of the search no longer leaves it "running" forever; the chain, the quarantine and the learned
  record take turns when several threads change them; the chain size has one definition; stale comments are fixed.
- **Patch 48 (2026-10-21) starts the implementation of the outside tablet and TV UI audit.** Buttons grow with their
  label instead of clipping it; the rail is for medium or wider windows; the TV edge margin is a setting; one
  navigation component with labels on the rail; the log journal is a visible Settings entry and one focus stop; the
  strategy list pages; the screen, the link and the quality survive a dark-mode change. The audit's larger items are
  declined or postponed with reasons (`CHANGELOG.md`, Patch 48; "Technical debt" below). None of it is confirmed on a
  screen yet.
- **Patch 49 (2026-10-21) finishes the overflow sweep and fixes the TV detection.** Three more rows stop squeezing a button
  (the YouTube-active row in Settings, the Library header, the sign-in buttons), and a PC-type device without touch is
  no longer taken for a TV. Not confirmed on a screen either.
- **Patch 50 (2026-10-22) removes the black bar with the app name.** Reported on a Redmi Pad: the manifest set no theme, so
  the platform drew its native dark action bar above the Compose screens. The app now has `Theme.Kinescope` (no action
  bar, window colour = the Compose background). Not confirmed on a screen yet.
- **Patch 51 (2026-10-23) is the Home dashboard sprint.** Strategies have colour names; Home shows the 15 best with stars, the
  YouTube bypass switch and the download speed; the downloads switch moved to Settings (on by default); Settings chooses the app
  behind "Unblock YouTube" and has a power-saving section (plus a pop-up on the second launch); the queue is compact; the
  library is its own screen; the first-run check is mandatory and an app update refreshes yt-dlp and the list and shows a
  notice. The strategy list mirrors its source again (12 built-in + 60 = 72, not 76). Not confirmed on a device.
- **Patch 52 (2026-10-24) reshapes Home and the navigation.** The dashboard card has one YouTube switch and "Unblock YouTube" is
  a button below it; strategies are a drop-down (default: the one in use) with a switch for choosing automatically; the queue
  is its own screen; the navigation is Home, Queue, "+", Library, Settings, and "+" downloads the clipboard link with the default
  settings. Not confirmed on a device; the Compose code was not compiled in the session that wrote it.
- **Patch 53 (2026-10-25) is the strategy router.** Strategies are rated separately for Wi-Fi and for mobile data, each rating blends
  the last test with real use, a class with no chain borrows the other's, a failure moves a strategy down at once, and a running
  tunnel follows the phone from one connection to the other. The mechanism is `STRATEGY_ROUTER.md`. Not confirmed on a device; the
  preference-backed code and the tunnel could not be run in the session that wrote it.
- **Everything below needs the user's phone or a real GitHub Actions run.** Do not tick a box from code review, a
  local compile or a green build alone (`AGENTS.md`, "What done means").

- **Out-of-band October changes (recorded in `CHANGELOG.md`).** Home has one tunnel toggle, an automatic toggle and "Watch YouTube"; the strategy
  drop-down, colour names and stars left Home; manual choice is the last section of Settings; a route that fails its health probe is never
  accepted; the TUN is closed at once on stop. Patch 58 supersedes only the manual-choice part: a five-entry menu returns while automatic is off; stars stay gone.
  Older entries below that name the drop-down, the stars or "Unblock YouTube" describe what existed before.
- **Patch 54 (2026-10-26) tells an unreachable route from a blocked strategy.** On mobile data every strategy failed at CONNECT "unreach": the engine
  could not reach the host, which says nothing about the strategy. The app now retries with the engine's IPv4-only option, remembers it per class,
  or says there is no route; such failures are no longer stored as failed tests. Cause on the reporting phone not confirmed (a hypothesis: IPv6).
- **Patch 55 (2026-10-26) makes checks small and per connection.** A check stops at 5 working strategies, 3 of them perfect; Wi-Fi and mobile data
  are checked on their own, once per app version (first launch and updates; the other class when the phone is on it); the first time the bypass is
  switched on in each half day two random strategies are tested, a failure sinks to the bottom of the class's ranking and the 15 lowest wait 75
  days; the 25-day full re-check and the update notice are gone; Home has a heartbeat line instead of a speed, a hints panel and tonal theme tokens.
  Patch 54's background job was replaced by the random check. Nothing of patches 54-55 is confirmed on a device.
- **Patch 56 (2026-10-29) answers the first device report of patch 55** (a Redmi Pad, build 0.12.2 (10032)). The tunnel no longer blames a strategy for a
  stop the user asked for or for a first transport death; the random check also runs when the app is opened and while the tunnel stays up, and its outcome
  is shown; Home has a refresh button (five random ways), a strategy groups card, the router's last change, and on a wide window two panes so the hints are
  not below the fold; dead code is removed. Nothing of it is confirmed on a device; the cause of the 3-second transport death in the report is not established.
- **Patch 57 (2026-10-30) brings the bypass closer to ByeByeDPI, which works on the user's phone.** Compared first (the engine commit, the strategy list and the tunnel library are the same or newer), then only the differences were changed: a strategy is also
  judged by how many of YouTube's real hosts, video-server nodes included, arrive in full through it (ranking only, never a gate), the YouTube tunnel is IPv4-only, and it adds no DNS server when the phone has a Private DNS host name. No GUI change.
  Nothing of it is confirmed on a device; the Android-bound edits (`BypassVpnService`, `DpiBypass`, `DiagnosticReport`) were not compiled in the session that wrote them.
- **Patch 58 (2026-10-31) restores manual choice on Home and closes a rotation loophole.** Automatic off now reveals exactly five manual choices from the current class's canonical ranking;
  turning it off while the tunnel runs pins the actual active line, not a stale chain head. A failure-driven restart excludes the just-failed line even when every route is quarantined.
  The full Settings list, pins, ratings, probes and rotation limits are unchanged. Device confirmation is open below.

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

Patches 54 and 55 are applied together and share the first gate. Gates below that name things patch 54/55 or the October out-of-band change
removed (the Home drop-down, stars, the speed line, the update notice, the 25-day upkeep, "Unblock YouTube") are superseded by the first two
sections; tick or delete them against the new behaviour, not the old.

### Home manual choice and failure-driven rotation (patch 58)
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green.
- [ ] On Wi-Fi, switch automatic off: a drop-down appears on Home with exactly five entries from the Wi-Fi ranking, with colour names and ratings. Choose one: the pin toast appears and the same choice is visible in Settings.
- [ ] Repeat on mobile data (or a metered hotspot): the mobile pin and five choices are independent of Wi-Fi. Switch automatic on: the Home menu disappears; the full Settings list remains available only while that class is pinned.
- [ ] While the tunnel runs on an automatic fallback, switch automatic off: the newly pinned line is the one named by the live route state, not the route that failed before it.
- [ ] Make the current route fail while at least one alternative exists (airplane mode is only a negative test, not proof of a blocked strategy): after the existing probe/one-way thresholds, `rotate ... why=` is followed by `tun up` on a DIFFERENT line. A pinned or already quarantined failed line is not selected by its own recovery restart.
- [ ] Check the menu in English and Russian, light and dark, at 200% font and with a TV D-pad. The selected pin may be shown above a five-entry menu even when it is currently outside that top five; choosing a menu entry replaces it.

### ByeByeDPI parity: real-host coverage, IPv4-only tunnel, Private DNS (patch 57)
`Copy report` shows `tun up ... v6=0 dns=`, `#state ... pdns=`, and `cov= edge= cw=[]` on the `pass` lines (`HANDOFF.md`, "How to read the next report", item 6).
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green. (`BypassVpnService.kt`, `DpiBypass.kt` and `DiagnosticReport.kt` were edited without the Android SDK: expect to fix an import or a signature.)
- [ ] Settings -> Test strategies (or the first check): the `pass` lines carry `cov=A/B edge=C/D`; B is 30 (11 site names + 19 nodes). A search of five working strategies takes visibly longer than before (up to about six seconds more per working strategy) and
  uses a little more data; report both numbers.
- [ ] Compare the top-ranked strategy of a check with the one that works best in ByeByeDPI on the same network: do they agree, or does the better one have the higher `cov=`? If every strategy shows the same low `edge=` with `cw=[conn_r4=...]` or `tls=...`, the node names do not answer
  from this network and the signal is noise there; report it.
- [ ] Switch the bypass on, open the YouTube app, play a video: it plays, the log says `tun up mtu=1500 v6=0 dns=1.1.1.1` (or `dns=system` with a Private DNS host name set in Android settings and `pdns=strict` in `#state`), and `tun win ... v=flowing` follows. No IPv6 address appears in the tunnel.
- [ ] With Private DNS set to a provider host name: names still resolve in the YouTube app while the bypass is on. If they do not, set it back to Automatic and report it (the rule can be reverted in `PrivateDns.tunnelDnsServer`).
- [ ] On an IPv6-only mobile network (if one is available): the app still plays (the engine reaches IPv4 destinations through the carrier's translation); report it either way.

### Router fixes, refresh button, groups card, tablet layout (patch 56)
`Copy report` shows `transport stopped unexpectedly engine= bridge= run= deaths= fate=`, `rotate ... why=` and the `#group` lines (`HANDOFF.md`, "How to read the next report").
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green. (The Compose files were not compiled in the sandbox: expect to fix an import or a signature.)
- [ ] Switch the bypass on, then off. The report shows no `rotate` line and no `transport stopped unexpectedly` line for that stop, and `quar=` did not grow.
- [ ] If the transport dies by itself: the line says `fate=restart_same`, the bypass comes back on the same way, Home says "Reconnected ... the connection dropped", and `#rate`
  shows no new `bad=` for that way. A second early death of the same way says `fate=rotate` and Home names the reason.
- [ ] Open the app with the bypass off, twelve hours after the last random check: a quiet check runs (strip "Refreshing · tried N of 2"), `srch rand ... n=2` follows and the
  groups card shows "Random check ... N of 2 work". Keep the bypass on for more than twelve hours: the next check runs by itself (`rand=` resets).
- [ ] The refresh button (top-right of the card): it tests five random ways (`srch rand ... n=5`), the strip counts them, the card says "Refresh: M of 5 ways work · A added to the
  list · R dropped", and `chain=` / `#rate` agree. It is greyed while a download or another search runs, and works while the bypass is on without testing the way in use.
- [ ] The groups card lists Wi-Fi and mobile data, the current one first with "Now"; the mobile group says "not checked yet" until the phone is on it (or on a hotspot that Android
  marks as metered) with the app open, then shows its ways and `#group net=mobile chain=` is above 0.
- [ ] Tablet in landscape: two panes, the dashboard and banners on the left, the groups and the hints on the right, both scroll on their own. Portrait and phone: one column, the hints
  at the end. The hints panel is readable in light and dark, and its refresh line is true.
- [ ] Home in both themes: the glow on the card (on and off), the larger headline, the pill "Watch YouTube", the round refresh button, nothing clipped at 200% font or in Russian.

### Checks, heartbeat and hints (patch 55)
Needs a phone that can switch between Wi-Fi and mobile data. `Copy report` shows `chk=`, `rand=`, `v4only=` and the `srch` / `byp` lines (`HANDOFF.md`, "How to read the next report").
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green for patches 54 and 55 together. (The Compose files were not compiled in the sandbox:
  expect to fix an import or a signature here.)
- [ ] Fresh install on Wi-Fi: the mandatory check ends by itself well before the whole list (`srch done n=` far below 72), Home's strip said "checked N · M of 5
  good ones found", the report says `chk=ok` for Wi-Fi. Then open the app on mobile data: a quiet check runs (notification, no popup) and `chk=ok` for mobile follows.
- [ ] Install over the previous version: `upd launch kind=updated`, yt-dlp updates, `AppUpdate list ...` is logged once, the current class is checked quietly,
  and there is no "app updated" banner.
- [ ] Settings -> Test strategies stops when 5 work and 3 of them are perfect (`srch done`), on Wi-Fi and on mobile data.
- [ ] Switch the bypass on, off and on again within an hour: ONE random check runs (`srch rand ... n=2`), `rand=` in the report counts hours; twelve hours later
  the next switch-on runs another. A chain strategy that failed it is gone from the chain (`chain=` smaller) and rated low in `#rate`; one that passed joined it.
- [ ] The random check does not delay switching the bypass on, never tests the strategy in use, and gives way when the bypass is switched off meanwhile.
- [ ] Heartbeat: green pulse travelling along the line while the bypass is on; the line turns red and flat when the traffic verdict is one-way (airplane mode while
  YouTube plays) or the tunnel stopped on an error; muted flat when it is off or starting. With "Animator duration scale" off in developer options the pulse holds still.
- [ ] No speed anywhere on Home, with a download running and with the tunnel on.
- [ ] The hints panel sits below the dashboard and the banners, is semi-transparent, readable in light and dark, has no way to close it, fits Russian at the largest
  font, and is skipped by the TV D-pad. The sentence about `+` is true: with a link in the clipboard `+` starts the download at the default quality.
- [ ] Theme: dialogs, menus, text fields and the navigation indicator no longer show the lavender of the Material3 baseline (light and dark); the ON card has its soft wash.

### Reachability and mobile data (patch 54)
- [ ] On the phone that failed (mobile data, Wi-Fi off), switch the bypass on. Record which of three happened: it came up and the report says `v4only=1` (the IPv6
  hypothesis holds); the message says there is no route and the log holds `reach net=mobile none ... dns= a4= a6= tcp4= tcp6=` (send it: the next fix follows it); or
  it came up after "start search" found strategies (the borrowed ones were blocked).
- [ ] After a no-route result the ratings are unchanged (`#rate` before and after) and nothing is quarantined.
- [ ] Wi-Fi is unaffected: the bypass comes up as before, `v4only=0`.
- [ ] With `v4only=1`, a download on mobile data starts through the bypass.

### Strategy router: Wi-Fi and mobile data (patch 53)
Needs a phone with a mobile data connection; the Wi-Fi checks can be done on the corporate network. `Copy report` (Settings) shows what
the app decided (`cls=`, `seeded=`, `#rate`, `#other`; see `STRATEGY_ROUTER.md` section 9): paste it with any failure.
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green (the Android-dependent code of patch 53 was only type-checked).
- [ ] Upgrade over a patch 52 install on Wi-Fi: the strategies, the pin and the stars are still there (they are the Wi-Fi record); Home says
  "Strategy · Wi-Fi"; the report shows `cls=wifi`.
- [ ] Switch the phone to mobile data only (Wi-Fi off): within a few seconds Home says "Strategy · mobile data", the drop-down lists the
  borrowed strategies without stars and a line says the connection has not been tested; the report shows `cls=mobile seeded=1`.
- [ ] On mobile data, with the tunnel on: it comes up on a strategy of the mobile chain; if the YouTube app does not load, the tunnel moves
  to another strategy by itself within about two minutes (log: `rotate net=mobile`), and `#rate` lines appear for what failed. Say how long it took
  and whether the app recovered.
- [ ] On mobile data, run Settings -> Test strategies: the title of the list says mobile data, the stars on Home change to the new ratings,
  the line about the untested connection goes away, and the Wi-Fi ratings are unchanged after going back to Wi-Fi. Note how much data it used.
- [ ] A download on mobile data finishes (or fails) through the mobile chain: the report shows `ladder ... net=mobile`; after it finishes the
  strategy that carried it leads the mobile chain and is not demoted on Wi-Fi.
- [ ] With the tunnel running, switch from Wi-Fi to mobile data and back: the log shows `net change wifi->mobile` about two seconds after the
  switch, the tunnel reconnects on the other chain, and the YouTube app keeps working (or recovers). A short loss of Wi-Fi that returns
  does not restart the tunnel.
- [ ] Pin a strategy on Wi-Fi, go to mobile data: the mobile list is not pinned (the switch reads "automatic"); back on Wi-Fi the pin is still there.
- [ ] A phone hotspot (Wi-Fi marked as metered by Android) is treated as mobile data: record whether Home says so.
- [ ] Open question for the user: is a borrowed chain on first use of mobile data acceptable, or should the app offer the test at once?

### Home, Queue and the five-entry navigation (patch 52)
Patch 52 replaced the strategy chips by a drop-down, moved the queue to its own screen and made the navigation five entries;
where the boxes below (patch 51 and older) name chips, "Show all (N)", a "Choose automatically" button, a four-entry bar, the queue or
the library on Home, or the Add screen filling itself from the clipboard, this list is the one to check.
- [ ] `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` is green (the Compose code of patch 52 has never been compiled).
- [ ] The bottom bar shows five round buttons in this order: Home, Queue, + (accent), Library, Settings. All five fit, clear of the
  system bar, on a narrow phone (320-360dp) and in Russian. In a tablet's or TV's rail the same five have labels, and the rail scrolls if
  the window is short.
- [ ] Home: the card has exactly one switch for the YouTube bypass, "Unblock YouTube" is a separate button BELOW the card (it starts
  the tunnel when needed and opens the chosen app); no queue and no library on Home; the banners still appear there.
- [ ] Strategy drop-down: it shows the strategy in use; the menu lists up to 15 (plus the one in use when it is not among them) with
  colour names and stars; choosing another one pins it, shows the toast and turns the automatic switch off; choosing the shown one does
  nothing; the menu opens and closes with a D-pad on a TV.
- [ ] Automatic switch: on in a fresh install; off pins the strategy shown; on lifts the pin. Note what the field shows right after
  switching it on (it keeps the pinned strategy until a search or a finished download reorders the chain) and say whether that is
  acceptable.
- [ ] "+" with a YouTube or Instagram link copied: the download starts at once at the default quality, a toast says so, the Queue tab
  shows the row. The same link again: the duplicate toast. Nothing copied: a toast and the Add screen. A link shared from another app
  still opens the Add screen with the link filled in. Android 12+ shows its own "pasted from clipboard" notice: record it.
- [ ] Queue tab: lists every job; Pause while it downloads, Resume when paused, interrupted or failed, Stop (trash) on every row that
  is not being saved, the swipe still removes a row; the empty text mentions the + button.
- [ ] Tablet / TV: Home is one centred column (about 640dp) with no empty pane; Queue and Library are single lists; every control has a
  focus ring; on a TV with an empty clipboard "+" opens the Add screen.

### Home dashboard, names, power saving, YouTube app, first run (patch 51)
- [ ] Fresh install (clear the app's data): Home shows "Please wait while we update the strategies." with a progress bar
  and NO Stop button; the notification has no Stop either; Settings shows "This first check cannot be stopped."; when it ends
  Home shows the controls and the 15 strategies. Closing and reopening the app during it resumes the check.
- [ ] Home dashboard: the switch turns the YouTube bypass on and off (VPN consent the first time); "Unblock YouTube" starts it
  when needed and opens the chosen app; the strategy chips show a colour name and 1-5 stars; a tap uses the strategy (the
  toast says so, "In use" follows it); "Choose automatically" lifts a pin; the speed line appears only while a download runs.
- [ ] Names: the same strategy keeps its colour after a list update, an app update and a restart; no two strategies share a
  name; the picker in Settings shows the names; the language of the names follows the device language.
- [ ] Strategy count: the picker lists 72 (12 + 60); after "Update" it is still 72, not 76.
- [ ] Settings: the downloads switch is on by default on a fresh install (and does nothing until a strategy is verified).
- [ ] Power saving: the pop-up appears once on the second launch (not the first, not again); "Open settings" opens Android's
  "always run in the background?" dialog (on Xiaomi/HyperOS also check the app's system page for a battery or autostart
  switch); Settings > Background work shows the state and updates it after returning; a download survives the screen
  being off for 10 minutes once the exemption is granted.
- [ ] YouTube app: Settings lists the YouTube app first and other apps that open youtube.com links (check that an
  alternative client such as NewPipe or ReVanced is listed if installed: the manifest query may match host-restricted apps
  differently on some Android versions); with a browser chosen "Unblock YouTube" opens youtube.com in it and the tunnel
  carries that browser; a change applies at the next tunnel start.
- [ ] Update path: install this build over patch 50: yt-dlp and the list refresh in the background and Home shows the notice
  once; "Open Settings" and dismiss both clear it; it does not come back after a restart.
- [ ] Queue and Library: the queue is small (three rows and "Show all (N)" on a phone); the Library tab lists, plays, shares and
  deletes downloads; on a tablet Home is dashboard | queue. Check the four-entry bottom bar and rail in Russian.

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
- [ ] Patch 47: with the 25 days due and Wi-Fi off (mobile data only), opening the app starts no re-check: Home shows
  no "Re-checking" strip and the report has no new `list ...` line. With Wi-Fi on, the next launch starts it.

### Home bypass dashboard (patch 45)
Patch 51 redrew this card (no Downloads row, no "Check again", a strategy list and a speed line); the boxes below that name
those are replaced by the patch 51 list above. The tunnel rows, the search strip and the traffic verdict stay as written.
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

### App theme (patch 50)
- [ ] Tablet (the Redmi Pad), portrait and landscape: no black bar with the app name above the screens; the rail (or the
  top bar) starts below the status bar, not under it, on Home, Add, Settings, Logs and both sign-in screens.
- [ ] Phone: the same look at the top edge (one bar, not two); the status-bar icons are readable in light and in dark
  mode. Record whether the phone showed the black bar before this patch.
- [ ] Dark mode: starting the app, rotating it and opening the keyboard show the dark brown window, with no white flash.

### Adaptive UI audit (patch 48)
- [ ] Tablet in portrait (about 800dp): every rail item has a label under its icon and none is cut (the Russian
  label of Settings, the longest, included).
- [ ] A 600-700dp tablet, or a phone in landscape: Logs shows its six buttons on two lines when they do not fit, nothing
  squeezed; Settings -> Accounts the same. Home -> the dashboard buttons stay whole.
- [ ] A phone at the largest font size, in Russian: the Unblock YouTube button, and the Open YouTube button
  next to Stop, grow to two lines instead of losing the second.
- [ ] A narrow, short window (a phone in split screen, about 360x400dp): the bottom bar stays, no rail. A 500-599dp
  window (a folded screen, a freeform window): the bottom bar is at most 380dp wide.
- [ ] Switch the system to dark mode (or change the language) while on Add with a typed link, or on Settings with a typed
  folder: the same screen, the link, the quality and the folder text are still there. Share a link to the app, then
  change dark mode: the app does not jump back to Add.
- [ ] Settings -> Diagnostics -> Open the log; Back returns to Settings. Five quick taps on the Settings icon still open
  it. Copy report, Share, Refresh, Clear, Top and Bottom all work.
- [ ] A D-pad or keyboard on Logs: one ring around the list, Up and Down scroll it by a page, and at either end the
  focus moves on to the buttons or the navigation.
- [ ] Settings -> the strategy list: 12 candidates, "Show more (N left)" adds 12, hiding the list and opening it again
  starts at 12; Test and Use still work on every row.
- [ ] Patch 49: at the largest font size, in Russian: while the YouTube tunnel runs, Settings shows its "active" label
  wrapped and a whole Stop button; the Home Library header keeps a whole Refresh button; on both sign-in screens the two
  buttons drop to a second line instead of squeezing.
- [ ] Patch 49: on a Chromebook or a desktop-mode device without touch (if one is at hand), Automatic picks the touch
  layout; a TV or a set-top box is still detected.
- [ ] TV: Settings -> Interface shows "Screen edge margin" 0-5%. A value applies at once and survives a restart; at 0% on a
  960x540dp TV the edges are used and Home still has two panes; at 5% the layout is what patch 41 had.

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

- **ByeByeDPI features not ported (patch 57), each needs a GUI or a product decision.** An all-apps or per-app tunnel (today the tunnel carries exactly one app, `addAllowedApplication`); start at boot / auto-connect; a Pause action in the
  notification; a configurable fake SNI (`{sni}` is always `google.com`); `-H` host lists (the parser forbids file paths on purpose); the user's domain lists (the coverage hosts are fixed in `ProbeSites`). Revisit when a device report shows one is needed.
- **The coverage hosts are fixed names (patch 57).** The video-server nodes are cache nodes of particular providers; from some networks many do not answer, which costs every strategy the same and only blurs the ranking. If a report shows that, choose the
  nodes at run time (e.g. keep the ones that answer with no strategy at all, or that answered in earlier searches) instead of a fixed list.

- **Patch 51 open points.** (a) The manifest `<queries>` for "apps that open YouTube links" is the documented browser query plus a
  youtube.com one; whether Android matches host-restricted clients (NewPipe, ReVanced) the same way on every version is unverified,
  see the gate above. (b) The first-run check blocks nothing else: a download waits for it only when it needs the bypass; if a
  fully blocking first run is wanted, add a scrim over `AppFrame` while `DpiSearchUiState.mandatory`. (c) The update notice of this
  patch no longer exists (patch 55: an update checks the strategies by itself). (d) The 25-day quiet re-check of patch 46 is gone too
  (patch 55); `MaintenanceSchedule` keeps only the yt-dlp calendar. (e) Chosen-browser tunnel: ALL of that browser's traffic goes through the bypass, not only YouTube.
- **The outside UI audit's larger items are open on purpose (patch 48).** (1) A TV-native Home (top tabs, a hero, horizontal
  rows) instead of the patch 41 two-pane lists: wait for the TV checklist above to be reported on a real TV, then redraw
  from what it shows. (2) `material3-adaptive` / `window-core`: the same breakpoints, unverifiable offline here, no foldable
  posture needed. (3) An in-app Media3 player: only if the target box has no video player. (4) `focusRestorer` /
  `focusGroup`: little to restore while screens are torn down on a section change. (5) The tunnel serves the phone edition
  of the YouTube app only (`BypassVpnService.YOUTUBE_PACKAGE`); the TV edition is a network-configuration change.
- **The Settings screen is one long scrolling column on a TV.** The D-pad crosses it control by control (the strategy list is
  paged since patch 48, the rest is about forty controls). Collapsible sections would shorten the walk; add them only if the
  TV check calls it a problem.
- **The check of a class runs on mobile data without asking (patch 55).** The brief asks for it; a check that stops at the goal costs a few megabytes
  (a pass is about 0.8 MB, a failure a few kilobytes), the random check at most two strategies. If that is too much for a metered plan, gate
  `MainActivity`'s class-check effect and `RandomCheck.runIfDue` on `NetClass.WIFI` (the removed `NetworkState.isMetered` did it).
- **The learned record is global, not per network.** It does not know on which network a success or a failure
  happened; the weekly halving of old evidence is the only protection against a record made at home steering a
  strategy choice on a hotel network. A per-network key (the Wi-Fi name needs a location permission; the network type
  and the provider would not) is a design of its own; build it only if a device report shows the record misleading.
- **Checks run when the app is opened or the bypass is on, not on a timer (patch 55, widened in patch 56).** The class check starts from `MainActivity` when the
  connection class has an unchecked app version; the random check starts after the tunnel is up, when the app is opened and no class check is needed, and every ten
  minutes while the tunnel runs (it asks the calendar, which allows one run in twelve hours). Patch 54's JobScheduler job (the app minimised) was
  removed because patch 55 gives the random check a different trigger; Android 12+ does not let a background job start the search service, so a truly
  unattended check needs its own design (a job that runs the goal-bound search itself, no foreground service).
- **A tunnel rotation during a network outage counts against the strategy in use (narrowed in patch 56).** A stop the user asked for and a first transport death no longer
  count (`TransportRecovery`); a probe or one-way rotation still does. `DpiPrefs.quarantine` records the
  failure for both of its callers, and the tunnel cannot tell a dead strategy from a dead network (the download ladder
  can: it blames a route only when a later one finished). The damage is bounded (a failure is one weighted entry that
  halves every week and the next success outweighs it), but if a report shows a good strategy sitting low after an
  outage, give the tunnel's call a weaker outcome than the ladder's.
- **The yt-dlp startup update is every 25 days now (it was every 12 hours).** A YouTube change inside that window is
  healed by the recovery chain's nightly refresh on the first download that fails. If reports show a failed first try
  after each YouTube change, give yt-dlp its own, shorter interval in `YtOfflineApp` (the constant is shared today).
- **"Perfect" is defined here, not given (patch 55).** `StrategyResult.perfect`: every probed host, the transfer and every simultaneous connection completed;
  not "score 100" (which also needs handshakes under 500 ms). If a network never yields three of them, the check ends by running the list out; tune
  `SearchGoal.STANDARD` or the definition then.
- **The bottom-15 rule is read literally (patch 55).** The 15 lowest-ranked that did badly and were tested less than 75 days ago are frozen; with more
  than 15 failures the older ones are free to be tested again and a failure moves a strategy to the very bottom by being the newest test. If the intent
  was "every failure waits 75 days", freeze all failures instead (`StrategyRank.frozen`).
- **The IPv4-only mark is sticky (patch 54).** Once learned for a class (`DpiPrefs.ipv4Only`) it stays until the preferences are cleared; a network that
  later gets working IPv6 loses nothing (YouTube works over IPv4), but nothing re-tests it. The `-X` retry is one strategy, one probe.
- **The chain holds six strategies now (it held four).** In the worst case, when the network is down and every route
  fails, the route ladder tries up to six routes before giving up. Lower `MAX_VERIFIED_STRATEGIES` and `MAX_FALLBACKS`
  together if that wait is noticeable.
- **The Home dashboard still reads bypass preferences as snapshots (patch 58 narrowed the staleness).** Searches, tunnel state, its own switch, connection class, `activeLine` and `lastEvent`
  now invalidate the snapshot, so a rotation and the actual route are observed. Another preference write without one of those signals would still appear only at the next invalidation;
  replace the remaining reads with a `DpiPrefs` flow only if such a writer appears.
- **Settings and Home disagree on one rule.** Settings still locks the "Use the bypass for downloads" switch while a
  download runs (and says so); Home does not, because the switch only writes a preference and a running download keeps
  its route. Align Settings (and its hint text) after a device report on the Home behavior.
- **The hints are static text (patch 55).** Eight lines in `strings.xml` since patch 56; they must be kept in step with the router by hand (`HintsPanel.kt` lists the ids).
  Anything the router does that a user should know goes there; anything removed must come out of it.
- **The 3-second transport death is unexplained (patch 56).** One report shows the tunnel's transport stopping three seconds after it came up, once, on a way that
  had just passed its live check. The patch reconnects without blame and logs which side died; if `engine=down` repeats, look at the `:dpi_vpn` process (a native crash or
  the system killing it); if `bridge=down`, at the bridge's configuration and the first burst of UDP. Do not guess before the next report.
- **The mobile group cannot be tested from Wi-Fi (patch 56).** Its check starts when the phone is on it with the app open; a device with no SIM only reaches it through
  a hotspot that Android marks as metered. Making the engine use the cellular network while Wi-Fi is up (binding sockets to a `Network`) is a design of its own.
- **The heartbeat is the app's only continuous animation (patch 55).** It draws a polyline every frame while the bypass is on; if a device report shows
  battery or jank cost, lower the frame rate or draw once per beat. Its colours (`pulseAlive`, `pulseLost`) are a design choice, not measured contrast on every card state.
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
- **Leftovers kept on purpose:** the watchdog's internal log text still says "YouTube". (`StageResult.replyCode` is read again since patch 54:
  `isReachFault`.)

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
