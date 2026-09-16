# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also attach a fresh repomix export
(or paste `CLAUDE.md`, `ROADMAP.md`, `CHANGELOG.md`, `CJM.md`,
`roadmap.md`, `design.md`, and `RELEASE.md` directly).

## Project identity

Personal Android app for downloading YouTube videos at home for
offline viewing during work trips to a network-restricted region.
Sideload-distributed only -- no Google Play, no backend, no required
cost. The rename to **Kinescope** is done: the codebase and package
are `com.kinescope.app` / "Kinescope" (previously
`com.baltic.ytoffline` / "YT Offline").

**Milestone: the first successful `./gradlew assembleDebug` in this
project's history was achieved via patches 01-14.** Nothing has touched
a real device yet -- that's the immediate next step (see below).

## How this codebase got here

Written by Claude across 7 phases with no intermediate compilation
(explicit user instruction at the time: "keep going, test everything
at the end"). A deep 4-part review by Claude Fable 5.1 then produced
`ROADMAP.md` -- a sequenced, prioritized fix list with a
findings-traceability Appendix (35 rows total across the review and
patches 07-14's own discoveries; as of patch 16 the Appendix only
lists the 5 still-open/informational rows -- closed ones moved to
`CHANGELOG.md`, see its Patch 16 entry). All patches below were
delivered as self-contained Python scripts for GitHub Codespaces, each
one extracted into a working copy and dry-run-verified (diffs +
bracket balance + idempotency, usually across 2-5 full-chain passes)
before being handed over -- never delivered untested:

- **Patch 01** -- ROADMAP Steps 1-3: the Compose BOM version (was a
  fabricated future release that doesn't exist), `execute()`'s
  progress-callback arity (confirmed 3-parameter against the
  youtubedl-android library's own sample-app source, not guessed),
  `updateYoutubeDL()`'s required `UpdateChannel` argument, the
  `DownloadService` worker race condition (fixed more thoroughly than
  the roadmap's own sample fix actually closes -- see the doc comment
  above `startWorkerLocked()` for why), and a catch-all exception
  handler so one bad download can no longer crash the whole process.
- **Patch 02** -- ROADMAP Step 4: filename humanization (yt-dlp writes
  the real title via its own output template; a bracketed job-id tag
  makes the resulting file findable afterward, then gets stripped back
  out), job-id-tag-based output file scanning (replacing an
  exact-filename assumption that a humanized title also broke),
  `MediaStore.RELATIVE_PATH`'s trailing-slash mismatch between insert
  and query, atomic `DownloadQueueBus` updates
  (`MutableStateFlow.update {}` instead of a racy read-then-write),
  Downloads-subfolder-name sanitization, YouTube-host validation on
  shared/pasted URLs, and an `ActivityNotFoundException` guard on the
  video-player launch intent.
- **Patch 03** -- ROADMAP Step 6.1/6.2/6.3/6.6: a real dark
  `ColorScheme` (there was only ever a light one), three tokens
  Material3's baseline `ColorScheme` has no slot for (`surfaceRaised`,
  `warning`, `errorContainer`) exposed via a `CompositionLocal`-backed
  `YtOfflineExtras` object (mirrors how `MaterialTheme.colorScheme`
  itself is accessed), a completed typography scale, and the
  adaptive-icon safe-zone clipping fix.
- **Patch 04** -- ROADMAP Step 6.5: queue-row thumbnail placeholders
  and a real `LinearProgressIndicator` (required adding a
  `progressFraction: Float?` field to `DownloadJobStatus` -- previously
  there was only a formatted string like "45% (ETA 12s)"), an
  empty-queue illustration, a Library overflow menu with **working**
  Share (`Intent.ACTION_SEND`) and Delete (`ContentResolver.delete()`
  -- genuinely new functionality, not just a UI affordance), a
  sectioned Settings screen (with a persisted yt-dlp-last-updated
  timestamp, new), a dismissible connectivity-loss banner, and a
  composer-bar focus-border fix.
- **Patch 06** -- ROADMAP Step 7 (Kinescope rename): `namespace` /
  `applicationId` in `app/build.gradle.kts` and `rootProject.name` in
  `settings.gradle.kts` renamed to `com.kinescope.app` / `kinescope`;
  every Kotlin source file physically moved from
  `app/src/main/java/com/baltic/ytoffline/` to
  `app/src/main/java/com/kinescope/app/` with its `package`
  declaration updated to match; `app_name` in `strings.xml` changed to
  "Kinescope"; the two other user-visible leftover strings
  (`DownloadService`'s notification title, `MainActivity`'s
  `TopAppBar` title) updated so the rebrand doesn't look half-done on
  screen; `design.md`'s three "YT Offline" mentions updated. Internal
  Kotlin identifiers containing "YtOffline" (the `YtOfflineApp` class,
  `YtOfflineTheme`, `YtOfflineExtras`, etc.) were deliberately left
  unchanged -- not user-visible, not in ROADMAP.md's Step 7 checklist,
  and renaming them would add risk for no user-facing benefit.
  (Patch 05 isn't listed here as a numbered accomplishment -- it was
  the documentation-only patch that produced this file and the
  ROADMAP.md status section, in the previous conversation.)
- **Patch 07** -- Pre-Step-5 environment fix: `.devcontainer/setup.sh`
  had a `pipefail` bug (`yes | sdkmanager --licenses`) that could
  silently abort setup before the Gradle wrapper was ever generated --
  fixed by disabling `pipefail` around just that one pipeline and
  checking `sdkmanager`'s real exit status explicitly. Also
  pre-verified the `youtubedl-android`/`ffmpeg` import paths and API
  shapes against the library's README and sample app -- **this
  pre-verification turned out to be incomplete**; see patch 14.
- **Patch 08** -- Documentation only: recorded the two-roadmap situation
  (this `ROADMAP.md`, by Claude Fable 5.1, vs. the separate `roadmap.md`
  by GPT Astra, added later) and the required execution order -- finish
  `ROADMAP.md` through Step 9 first, then `roadmap.md`, then delete
  both files -- in both `ROADMAP.md` and this file, so a fresh session
  doesn't have to re-discover or re-litigate it.
- **Patch 09** -- Fixed a stale cross-reference in `ROADMAP.md`'s Step 5
  section ("Do not move to Step 7 (signed release)...") left over from
  before the Kinescope-rename step was inserted as its own Step 7;
  signed release has been Step 9 since patch 06. Documentation only.
- **Patch 10** -- Made `.devcontainer/setup.sh` safely re-runnable: the
  Android cmdline-tools download/extract/`mv` block failed outright on
  a second run ("Directory not empty") once already installed once;
  the `.bashrc` export block also duplicated itself on every run. Both
  guarded to skip/no-op when already done.
- **Patch 11** -- First attempt at the real `./gradlew assembleDebug`
  failure (a bare, cryptic `25.0.2` error with no other text).
  Hypothesis: a JDK too new for Gradle 8.10.2. Added auto-detection of
  an installed JDK 17 via SDKMAN, pinned via `org.gradle.java.home` in
  `gradle.properties` if found. **Didn't fix anything yet** -- this
  Codespace has no JDK 17 at all (see patch 12).
- **Patch 12** -- Broadened patch 11's JDK search to accept any JDK
  Gradle 8.10.2 can actually run on (17-23 inclusive, per Gradle's own
  8.10 release notes: "Gradle now supports running on Java 23") instead
  of requiring exactly 17. A live diagnostic confirmed the root cause
  precisely: `java`/`javac` on `PATH` resolve to a Codespace-provided
  JDK 25.0.2 at `/home/codespace/java/current`, entirely separate from
  -- and taking priority over -- the devcontainer Java feature's
  SDKMAN-managed install (which itself only has `21.0.10-ms` and
  `25.0.2-ms`, no 17.x, despite `devcontainer.json` requesting version
  17). Patch 12 picked up the already-installed `21.0.10-ms` and pinned
  it. **This is what actually fixed the JDK mismatch** -- the next real
  build got past environment setup entirely for the first time.
- **Patch 13** -- Fixed the first real compile-time error, hit right
  after the JDK fix: `ic_launcher_foreground.xml` had a comment
  containing `--`, which the XML spec forbids anywhere in a comment
  body (only valid as part of the closing `-->`). Confirmed via a
  regex scan of every XML comment in the repo that this was the only
  occurrence.
- **Patch 14** -- Fixed the real, final compile error and the actual
  substance of Appendix finding #11: `UpdateChannel` is a **nested
  class of `YoutubeDL`**
  (`com.yausername.youtubedl_android.YoutubeDL.UpdateChannel`), not
  top-level in `com.yausername.youtubedl_android` as patch 07 had
  concluded from a README comment that dropped the qualifying prefix
  for brevity. Confirmed this time by `git clone`-ing the actual
  library at the exact tagged version (`0.18.1`) pinned in
  `app/build.gradle.kts` and reading the real source directly, rather
  than inferring from secondary sources. Everything else patch 07
  checked (`YoutubeDL`/`YoutubeDLRequest`/`YoutubeDLException`/`FFmpeg`
  locations, the 3-parameter `execute()` callback,
  `updateYoutubeDL()`'s signature) was re-confirmed correct against
  this same real checkout. **After this patch, `./gradlew assembleDebug`
  succeeded -- the first successful build in the project's history.**
- **Patch 15** -- Documentation consolidation after the first
  successful build: `README.md` rewritten from scratch (the draft
  `ROADMAP.md` referenced could no longer be located in the repo),
  this file refreshed end to end, and `ROADMAP.md`'s top status block
  and Step 2/Step 5 notes updated to match. (This bullet itself was
  missing from this list until patch 16 caught it -- self-referential
  doc patches are easy to under-describe.)
- **Patch 16** -- Added `.github/workflows/build-debug.yml`: a
  manual-only (`workflow_dispatch`) GitHub Actions workflow that builds
  a debug APK and uploads it as a run artifact, so getting a build onto
  a phone no longer depends on adb or a Codespace-browser download.
  Version name is supplied by hand each run; `versionCode` is derived
  from the Actions run number so it always increases. `app/build.gradle.kts`
  updated to read optional `appVersionCode`/`appVersionName` Gradle
  properties (falls back to the existing hardcoded `7`/`"1.0.0"` for
  local builds). Also introduced `CHANGELOG.md` and the process behind
  it: from this patch on, a completed `ROADMAP.md` item gets its
  detailed checklist collapsed to a one-line pointer there, with the
  actual change log recorded in `CHANGELOG.md` instead -- `ROADMAP.md`
  shrank from 634 to well under 300 lines as a result. Also fixed a
  stale, never-flipped checkbox in `ROADMAP.md`'s Step 8 (the README
  rewrite was actually done in patch 15, but the checkbox said
  otherwise). **Multi-pass full-chain testing caught the exact
  "later patch breaks an earlier patch's own idempotency check" failure
  mode already documented below** -- collapsing the Appendix removed
  the anchor rows patches 07/13/14 depend on, and rewriting the top
  status block/Step 2 broke patch 15's checks too. Fixed by adding a
  short-circuit guard to each of those four scripts' `patch_roadmap()`:
  if patch 16's Appendix-trim marker is present, skip that patch's
  ROADMAP.md edit entirely (nothing left for it to do; patch 16's
  rewrite already incorporates it). Without this, a repeated full-chain
  run would have either failed loudly or, for patch 07's row 34,
  silently resurrected content patch 16 had intentionally removed.
- **Patch 17** -- Added `CJM.md`: the five-stage Customer Journey Map
  (prep at home -> queue & download -> departure/loses access -> watch
  offline in-region -> return & refresh library) as a standalone living
  document, closing Step 8's last concrete checkbox. States explicitly
  why stage 1 (queuing several videos at home, the night before a trip)
  is the highest-risk moment: it's the last point of full internet
  access, and nothing between it and departure is recoverable if it
  goes wrong -- the actual reason every crash/race/silent-failure fix
  in Steps 1/3/4 was ranked Critical/High. This was the one remaining
  fully autonomous item -- everything else still open (Step 5's device
  install/testing, and Step 9's signed release, which `ROADMAP.md`
  itself explicitly gates on Step 5 being confirmed on a real device)
  needs the user's actual phone and can't be advanced further from
  here without that. **Also patched patch 16's own script, twice**:
  since this patch further modifies both `ROADMAP.md` and `HANDOFF.md`
  after patch 16 already did, patch 16's idempotency check broke on a
  repeated full-chain run for both files, for the same reason patch 16
  itself had to fix patches 07/13/14/15 -- caught by this patch's own
  multi-pass regression test. Fixed by giving
  `whole_file_guarded_replace()` an optional `superseded_marker`
  parameter, used by both patch 16's `patch_roadmap()` and
  `patch_handoff()` calls. Expect this to recur for any future patch
  touching either file again -- budget time to vaccinate the immediate
  predecessor each time.
- **Patch 18** -- Fixed the `Build Debug APK` workflow's first real
  failure, reported by the user directly from an Actions run: `Value
  '/usr/local/sdkman/candidates/java/21.0.10-ms' given for
  org.gradle.java.home Gradle property is invalid`. Root cause: patch
  12 pinned Gradle's JDK by writing directly into the project's
  **committed** `gradle.properties` -- correct for the one Codespace
  where that exact SDKMAN path exists, wrong for every other
  environment cloning the repo, including the Actions runner four
  patches later. Fixed by redirecting `.devcontainer/setup.sh`'s
  (unchanged) JDK-detection logic to write the pin into the user-level
  `$HOME/.gradle/gradle.properties` instead, which Gradle already
  prioritizes over the project-level file and which never leaves the
  machine; removed the stale invalid line from the committed file,
  which is what actually unblocks CI. Verified by simulating both
  environments (fake SDKMAN dirs for the Codespace path, confirmed
  project file stays clean either way) rather than just reasoning
  about it, since this project has been burned before by assuming
  environment behavior instead of checking it.

**Version-compatibility note worth remembering:** Compose BOM
2024.11.00 (fixed in patch 01) pulls in Material3 **1.3.1**. Some APIs
changed shape in *later* Material3 versions than that -- e.g.
`LinearProgressIndicator`'s lambda-based `progress: () -> Float`
overload wasn't added until 1.5.0-alpha17, so patch 04 deliberately
uses the older plain-`Float` overload (confirmed still valid, not even
deprecated, at 1.3.1 -- verified against the actual androidx API
surface, not assumed; the successful build's compiler output shows it
as merely *deprecated*, not broken, confirming this was the right call
for now). **If a future change bumps the Compose BOM, recheck call
sites like this one against whatever Material3 version the new BOM
actually pulls in.**

## What's actually done vs. still open

Read `ROADMAP.md`'s top section first -- it has the authoritative,
up-to-date status summary and the correct execution order (which does
**not** match the document's own Step numbering). As of this snapshot:

**Done:** Steps 1, 2 (including Appendix finding #11, now fully
confirmed by the actual successful compile -- not just pre-verified),
3, 4, 6 (6.1-6.6; 6.7 -- an optional monochrome adaptive-icon layer for
Android 13+ themed icons -- is still skipped, opt-in only, not
required), 7 (Kinescope rename), and the environment/build-setup work
that had to happen before Step 5 could even start (patches 07-14: a
working, re-runnable `setup.sh`, a Gradle/JDK pin that actually works
in this Codespace, and every compile error fixed). **`./gradlew
assembleDebug` now succeeds**, both locally and via the
`.github/workflows/build-debug.yml` GitHub Actions workflow (patch 16).
`CJM.md` (patch 17) closes Step 8's last concrete checkbox.

**Not done, in the order to actually do them -- and, as of patch 17,
this is also the point where autonomous progress stops:** everything
below needs the user's actual phone, either directly (Step 5) or
because `ROADMAP.md` itself explicitly gates it on Step 5 being
confirmed there first (Step 9). There is no further roadmap work a new
session can usefully do without that -- don't invent busywork or skip
ahead to Step 9 to look productive; wait for Step 5 results instead.

1. **Step 5 -- Device install + manual testing.** The compile is done;
   nothing has touched a real device yet. `ROADMAP.md`'s Step 5 section
   has the full manual test checklist, including explicitly
   stress-testing the `DownloadService` race-condition fix from patch
   01 (queue several videos in quick succession). No compile errors are
   expected at this point, but can't be fully ruled out -- if
   `./gradlew assembleDebug` somehow needs to run again for any reason,
   the environment fixes (patches 07-12) are already in place, so this
   should be a normal build, not another environment debugging session.
   Getting the APK onto the phone no longer requires adb: the
   `Build Debug APK` GitHub Actions workflow (patch 16) builds and
   uploads it as a downloadable run artifact instead.
2. **Step 8 -- Documentation.** `README.md` (patch 15) and `CJM.md`
   (patch 17) are both done. The one remaining item, "keep `ROADMAP.md`
   itself current," is ongoing by nature, not a one-time task -- it's
   not something to ever check off, just a practice to keep following.
3. **Step 9 -- Signed release**, per `RELEASE.md`, only once every item
   in Step 5 is confirmed working on a real device. Note: `RELEASE.md`
   still uses the old `yt-offline` name for the keystore filename/alias
   and the GitHub release title -- cosmetic, worth a quick pass (or not)
   when Step 9 actually happens.
4. **`roadmap.md` (lowercase) -- GPT Astra's audit/plan.** A separate,
   newer sprint-based document (S0-S11, findings F01-F42). Explicitly
   queued for **after** Step 9 above is fully done -- don't start it
   early or merge it into this `ROADMAP.md`. Once both roadmaps are
   fully executed, delete both files.

**Backlog (optional, unscheduled -- see `ROADMAP.md`'s Backlog section
for the full list with reasoning):** persisting queue state across a
process kill, orphaned-temp-file cleanup on service start, migrating
remaining `Thread`/`Handler` usage to coroutines for consistency with
`DownloadService`'s own fix, externalizing hardcoded UI strings to
`strings.xml`, `collectAsState()` -> `collectAsStateWithLifecycle()`,
playlist batch-queueing, a self-hosted sync backend (explicitly never
required, per `CLAUDE.md`). In-app delete is fully done as of patch 04.

## File map (current, post-Kinescope-rename -- package `com.kinescope.app`)

All files below live under
`app/src/main/java/com/kinescope/app/` (was
`app/src/main/java/com/baltic/ytoffline/` before patch 06).

- `MainActivity.kt` -- screen composables: `DownloadScreen`,
  `QueueRow`, `EmptyQueueState`, `ConnectivityBanner`, `LibraryRow`,
  `ComposerBar`, `SettingsPanel`/`SettingsSectionHeader`. Also
  `playItem()`/`shareItem()` (Intent-based, both guard
  `ActivityNotFoundException`) and `isYouTubeUrl()`/`extractUrl()`
  (host allowlist). `TopAppBar` title now reads "Kinescope".
- `DownloadService.kt` -- foreground service; a single background
  worker `Thread` draining a `LinkedBlockingQueue`, restarted on
  demand (see the `startWorkerLocked()` doc comment for the
  race-condition reasoning); `runJob()` does the actual yt-dlp
  `execute()` call, job-id-tag file scanning, and `friendlyError()`
  mapping. Notification title now reads "Kinescope";
  `ACTION_ENQUEUE` now `com.kinescope.app.ACTION_ENQUEUE`.
- `DownloadQueueBus.kt` -- shared
  `MutableStateFlow<List<DownloadJobStatus>>` between the service
  (producer) and UI (consumer); `NO_INTERNET_MESSAGE` constant shared
  with `DownloadService` so the connectivity banner can't drift out of
  sync with a hand-typed string duplicated in two files.
- `QualityPresets.kt` -- the four quality/format options
  (`label`/`mimeType`/`apply: YoutubeDLRequest.() -> Unit`).
- `MediaStorage.kt` -- `publish()`, `listPublished()`, `delete()`
  against `MediaStore.Downloads`.
- `Settings.kt` -- `SharedPreferences` wrapper: default quality index,
  sanitized Downloads subfolder name, last-yt-dlp-update timestamp.
- `YtDlpUpdater.kt` -- wraps the library's self-update call, records
  the last-update timestamp on success. `UpdateChannel` import fixed
  in patch 14 (nested class of `YoutubeDL`).
- `YtOfflineApp.kt` -- yt-dlp/ffmpeg init + startup update check.
  Class name kept as `YtOfflineApp` (internal identifier, not
  user-visible, not part of the Step 7 rename scope) despite living in
  `com.kinescope.app` now.
- `Theme.kt` -- `YtOfflineTheme` (light + dark `ColorScheme`),
  `YtOfflineExtras` (the `success`/`warning`/`surfaceRaised` extension
  colors), the full typography scale, downloadable Google Fonts
  (Inter/Lora) via `font_certs.xml`. Same note on identifier names as
  above.
- `ic_launcher_foreground.xml` / `ic_launcher_background.xml` /
  `mipmap-anydpi-v26/ic_launcher*.xml` -- adaptive icon, safe-zone
  fixed in patch 03; an invalid `--` inside a comment fixed in patch
  13.
- `RELEASE.md` -- signing key generation, signed build, install
  instructions; still uses the old `yt-offline` name in places
  (Step 9 scope, not touched by patch 06). `design.md` -- the visual
  design system, with a section on what it approximates and what it
  deliberately avoids (Anthropic's actual fonts/logo/name); its "YT
  Offline" mentions are now "Kinescope". `CLAUDE.md` -- project ground
  rules. `ROADMAP.md` -- the living, checkbox-tracked implementation
  plan (read its top section first; as of patch 16 it only carries
  detail for what's still open -- completed Steps point to
  `CHANGELOG.md`). `CHANGELOG.md` -- terse per-patch "what shipped"
  record, newest first (patch 17). `CJM.md` -- the five-stage Customer
  Journey Map behind Steps 1/3/4's priority ordering (patch 17).
  `roadmap.md` -- GPT Astra's sprint-based plan, queued for after
  `ROADMAP.md`. `.github/workflows/build-debug.yml` -- manual GitHub
  Actions workflow that builds and uploads a versioned debug APK
  (patch 16).

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7 (default;
overridable via `-PappVersionCode`, see patch 16), `versionName`
"1.0.0" (default; overridable via `-PappVersionName`), Compose BOM
`2024.11.00` (Material3 1.3.1), `youtubedl-android` 0.18.1, Gradle
`8.10.2`. `applicationId`/`namespace`: `com.kinescope.app`. App name:
"Kinescope".

## Key learnings & principles

- **Machine-specific config must never be written into a committed,
  shared file.** Patch 12 pinned Gradle's JDK by writing an absolute
  path into the project's own tracked `gradle.properties` -- it worked
  perfectly in the one Codespace that path existed in, and broke
  silently (well, not silently -- loudly, but only once someone else's
  environment actually tried to build) for every other environment
  that cloned the repo, surfacing four patches later the first time
  the new GitHub Actions workflow (patch 16) actually ran (patch 18).
  The fix -- writing to `$HOME/.gradle/gradle.properties` instead,
  which Gradle prioritizes over the project file and which never
  leaves the machine -- was available the whole time; the bug was
  writing to the wrong file, not the detection logic itself, which was
  correct from patch 12 onward. General principle: anything derived
  from *this specific machine's* filesystem layout belongs in a
  user-level/local config location, never in a file that gets `git
  add`ed.
- **ApplicationId timing:** changing `applicationId` after first device
  install is effectively irreversible on Android -- the rename to
  `com.kinescope.app` was deliberately completed before any device
  install (still true; no device install has happened yet).
- **BOM/API version discipline:** current docs/tutorials default to
  showing the latest API, which won't necessarily compile against an
  older pinned BOM/library version. Always check the actual resolved
  version's own API surface, not what's currently documented as
  default.
- **A Codespace can have more than one JDK, from more than one
  source, and they can disagree.** This project's Codespace has a
  Codespace-provided default JDK at `/home/codespace/java/current`
  (currently 25.0.2) *and* a separate, SDKMAN-managed install from the
  devcontainer's Java feature at
  `/usr/local/sdkman/candidates/java/` -- and the Codespace-provided one
  wins on `PATH`/`JAVA_HOME` regardless of what `devcontainer.json`
  requested. Don't assume a `"version": "17"` feature request is what's
  actually active -- check `ls /usr/local/sdkman/candidates/java/`
  directly, and don't assume that directory even contains what was
  requested, either.
- **Gradle has a hard JDK ceiling per version, documented in that
  version's own release notes** (e.g. "Gradle 8.10 now supports running
  on Java 23") -- a JDK newer than the ceiling fails with a bare,
  low-information error (in this project's case, literally just the
  version number `25.0.2` and nothing else) rather than a clear
  "unsupported Java version" message. Worth checking the release notes
  early if a Gradle build fails with an unexplained, terse error.
- **XML comments can never contain `--` anywhere in the body** (only
  valid as part of the closing `-->`) -- easy to introduce by accident
  via a parenthetical dash in a comment.
- **A library's own tagged source is more reliable than its README's
  example snippets or an older sample app**, both of which can drop
  qualifying context (like an outer class name) in ways that look like
  -- but aren't -- evidence a class is top-level rather than nested.
  When a real compile error contradicts an earlier "pre-verified"
  claim, re-verify against the actual tagged source (`git clone` +
  checkout the exact pinned version/tag) rather than re-reading the
  same secondary sources that produced the wrong conclusion the first
  time.
- **Patches that rewrite text produced by an earlier patch can silently
  break that earlier patch's own idempotency check**, if the earlier
  patch's "already applied" detection doesn't also recognize the later
  patch's marker. This happened twice in one earlier session (patch 12
  rewriting patch 11's setup.sh block; patch 14 rewriting patch 07's
  Appendix row), then twice more later (patch 16 rewriting patches
  07/13/14/15's ROADMAP.md anchors; patch 17 then breaking patch 16's
  own check the same way, one layer deeper) -- each time only caught by
  running the **full patch chain 3-5+ times from a clean copy**, not a
  single dry run. This isn't a one-off risk to remember, it's a
  standing expectation for this project: any future patch that touches
  `ROADMAP.md` (or any other file several patches already edit) should
  budget time to also patch its immediate predecessor's idempotency
  check, and multi-pass full-chain testing is the only reliable way to
  catch when that's needed.
- **SIGPIPE in setup scripts:** `pipefail` combined with `yes |` piped
  to a process that closes stdin early produces SIGPIPE errors; license
  acceptance in `sdkmanager` requires a more robust approach (fixed in
  patch 07).
- **Hidden directories:** dot-prefixed folders (e.g. `.devcontainer/`)
  are silently skipped by some file transfer tools and GUI managers --
  `git add -A` from the terminal is required to capture them.
- **A GitHub Actions runner is not the same environment as this
  Codespace.** The JDK-pinning workaround from patches 11-12 exists
  because *this specific Codespace* has a competing ambient JDK 25 on
  `PATH` ahead of the one actually wanted. A GitHub Actions runner
  (`.github/workflows/build-debug.yml`, patch 16) is a clean, single-JDK
  environment where `actions/setup-java` is the only JDK present -- so
  the workflow doesn't need (and doesn't include) that same pin. Don't
  assume every environment inherits every fix a previous environment
  needed; re-derive from first principles per environment.

## How to resume in a new conversation

1. Export a fresh repomix XML of the repo (it should reflect patches
   01-16 if they were applied and committed -- confirm with `git log`).
2. Paste it plus this file. `CLAUDE.md`/`ROADMAP.md`/`CHANGELOG.md`/
   `roadmap.md`/`design.md` are nice-to-have if not already covered by
   the repomix export, but this file's "What's actually done vs. still
   open" section above should be enough to know where to pick up.
3. State which of the "not done" items above to work on next -- they're
   meant to happen in that order, but say so explicitly, since a new
   conversation has no memory of *why* that order matters otherwise.

## Immediate next step for Claude (in a new conversation)

**Step 5 (device install + manual testing) is next, and it's a hard
wall for autonomous progress** -- as of patch 18, every other item
that could be done without the user's actual phone (Steps 1-4, 6, 7,
8, plus fixing the `Build Debug APK` workflow's first real failure --
a Codespace-only JDK path had leaked into the committed
`gradle.properties`, patch 18) is done. Step 9 is explicitly gated by
`ROADMAP.md`'s own text on Step 5 being confirmed on a real device
first ("do not skip ahead to save time"). Don't invent busywork or
start Step 9/Backlog items to look productive while waiting -- if
there's nothing left that doesn't need the phone, say so plainly and
wait for Step 5's results instead.

The compile is done -- `./gradlew assembleDebug` succeeds, either
locally or via the `Build Debug APK` GitHub Actions workflow
(`.github/workflows/build-debug.yml`, patch 16 -- manual trigger, hand
-assigned version, no adb required). `ROADMAP.md`'s Step 5 section has
the full manual test checklist, including explicitly stress-testing the
`DownloadService` race-condition fix from patch 01 (queue several
videos in quick succession). Once real-device results come back,
continue the established pattern for this project:

- Read the actual current file content before editing -- don't assume
  memory of it is accurate; things have changed across 18 patches.
- Verify uncertain library/API claims against a real source -- ideally
  the actual tagged source via `git clone` (github.com is reachable
  from the sandbox), not just a README or an old sample app, both of
  which have already produced a wrong conclusion once in this project
  (see patch 14).
- Deliver changes as a self-contained Python patch script for GitHub
  Codespaces. Extract the repomix into a local working copy first,
  dry-run the script against it, verify diffs and bracket balance and
  idempotency -- and for any patch chain longer than a couple of
  patches, run the **full chain multiple times from a clean copy**,
  since a later patch can silently break an earlier patch's own
  idempotency check (see "Key learnings" above, and patch 16's own
  entry for a fresh example of this exact failure mode recurring).
- When a `ROADMAP.md` item is completed, collapse its checklist to a
  one-line pointer in that file and record what actually changed in
  `CHANGELOG.md` instead (process established patch 16) -- do this in
  the same patch as the code change it corresponds to.
- Write all code, code comments, commit messages, and documentation in
  English, regardless of what language the conversation itself is in.
