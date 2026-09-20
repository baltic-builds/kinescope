# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also attach a fresh repomix export
(or paste `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md`, `CHANGELOG.md`,
`CJM.md`, `roadmap.md`, `design.md`, and `RELEASE.md` directly).

## Project identity

Personal Android app for downloading YouTube videos at home for
offline viewing during work trips to a network-restricted region.
Sideload-distributed only -- no Google Play, no backend, no required
cost. The rename to **Kinescope** is done: the codebase and package
are `com.kinescope.app` / "Kinescope" (previously
`com.baltic.ytoffline` / "YT Offline").

**Milestone: the first successful `./gradlew assembleDebug` in this
project's history was achieved via patches 01-14.** The
`.github/workflows/build-debug.yml` CI path (patch 16) failed on its
first real run due to a leaked Codespace-only JDK path; patch 18 fixed
it, and the user has since confirmed a successful GitHub Actions build
and downloaded a debug APK. **Patch 20 delivered Step 9's CI workflow**
(`.github/workflows/build-release.yml`), and **patch 21 confirmed it
actually works**: a real signed release build succeeded via GitHub
Actions after fixing two real gotchas hit along the way (PKCS12's
same-password requirement, and the default Codespaces `gh` token
lacking rights to write repo secrets -- both in `CHANGELOG.md`'s Patch
21 entry) and trimming the ~200MB APK down via `ndk.abiFilters`. **Patch
22** fixed a startup crash hit on the user's first real-device launch
attempt (a Compose icon-loading bug in `EmptyQueueState`, unrelated to
signing/CI), and **patch 23 (this one) confirms the fix**: the user
reports the app now runs correctly end to end on a real device (Android
13) -- install, share/paste a link, queue and complete a download, play
it back, background persistence. **The original 9-step plan is now done
or reduced to specific, named technical debt** -- see `ROADMAP.md`'s
"Technical debt" section for the current, complete list (the signed-APK
device-confirm is the one Step 9 item still open; a handful of more
adversarial Step 5 checks -- the race-condition stress test,
`friendlyError()` against a real broken video, airplane mode, a very
large download -- haven't been individually gone through yet either).

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
- **Patch 19** -- Documentation/handoff update (no app code changes;
  patch 17/18's own scripts did need a small fix, see below). The
  user confirmed patch 18's fix worked: a real GitHub Actions run
  succeeded and produced a downloadable debug APK. Recorded that
  confirmation across `ROADMAP.md`/`HANDOFF.md`, and recorded an
  explicit decision from the user: **Step 9 (signed release) starts
  next**, ahead of Step 5's manual on-device checklist being
  individually gone through and reported back -- logged as a standing
  decision in `CLAUDE.md`'s instruction log so a future session acts on
  it rather than re-litigating `ROADMAP.md`'s original Step 9 gate.
  `ROADMAP.md`'s execution order, Step 5 section, and Step 9 section
  all updated to match, while preserving the original caution's actual
  point (a signed release is still built from unverified code) as
  context rather than deleting it outright. **Also vaccinated patch 16,
  17, and 18's own scripts, two levels deep:** `patch_claude_md()`
  (patch 16), `patch_roadmap()` (patch 17), and
  `patch_changelog()`/`patch_handoff()` (patch 18) each needed a
  `superseded_marker` for this patch's direct edits to those files.
  Then, since patch 17 also patches patch 16's *script file* (it
  already had a `patch_patch16_script()` function, added in patch 17
  itself to fix an earlier collision), and this patch's fix to patch
  16's script changes that same script file again, patch 17's check on
  it needed a `superseded_marker` too -- only surfaced on a second
  full-chain regression run after the first round of fixes existed,
  since the collision couldn't happen until they did. This snapshot is
  meant to be pasted into a new conversation next, per the user's own
  request.
- **Patch 20** -- Step 9: CI-based signed release build. Added
  `.github/workflows/build-release.yml`, mirroring `build-debug.yml`'s
  manual-`workflow_dispatch`, hand-versioned design, but running
  `assembleRelease` against a keystore assembled at runtime from four
  repo secrets (`KEYSTORE_BASE64`, decoded to a runner-local temp file;
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), written into a
  `keystore.properties` the existing signing config in
  `app/build.gradle.kts` already knows how to read -- that config
  predates this patch and needed no changes. Both the decoded keystore
  and the generated `keystore.properties` are deleted in an `if:
  always()` cleanup step so they don't persist past the build even on
  failure. This was a direct user request, not an autonomous roadmap
  step: building signed releases via GitHub Actions instead of a local
  Codespace `./gradlew assembleRelease`, matching how debug builds
  already work. `RELEASE.md` renamed its leftover `yt-offline`
  keystore-filename/alias/release-title naming to `kinescope` (flagged
  as cosmetic debt back in patch 19) and gained a "CI build (GitHub
  Actions)" section alongside the existing local-build option, which
  is kept since it still works and needs no secrets setup. `README.md`
  and `ROADMAP.md` updated to point at the new workflow. **Not marked
  as Step 9 done**: same as `build-debug.yml` wasn't marked confirmed
  until patch 18's real Actions run succeeded, this workflow's own
  first real run -- which needs the user to generate a keystore and add
  the four secrets first, since Claude has no access to do either --
  hasn't happened yet. See `ROADMAP.md`'s Step 9 section for the exact
  remaining checklist.
- **Patch 22** -- Fixed a startup crash on the user's first
  real-device launch attempt (Step 5, Android 13): `EmptyQueueState`
  loaded `android.R.drawable.stat_sys_download` via
  `painterResource()`, which only supports a static `VectorDrawable`
  or a rasterized image -- not the `AnimatedVectorDrawable` that
  framework resource actually is on real devices (confirmed against
  `painterResource()`'s own documentation, not assumed). Replaced
  with a small hand-authored static vector
  (`res/drawable/ic_download.xml`). `DownloadService`'s use of the
  same framework resource for `setSmallIcon()` is unaffected --
  notifications accept any drawable resource id directly, no Compose
  involved. Unrelated to Step 9 / CI signing; this is purely a Step 5
  finding.
- **Patch 23** -- Documentation-only: the user confirmed patch 22's fix
  works (the app runs correctly end to end on a real device).
  Folded that confirmation into every project doc, consolidated
  `ROADMAP.md`'s original 9-step plan (now functionally complete) into
  a single "Technical debt" section, rewrote `README.md` as a proper
  GitHub-facing README with CI status badges, and added `AGENTS.md` --
  a process/workflow file for AI coding agents, complementing
  `CLAUDE.md`'s ground rules. Also found and fixed a real, if currently
  harmless, security gap: `kinescope-release.jks.b64` (an empty
  placeholder) is git-tracked and wasn't covered by `.gitignore`'s
  existing `*.jks`/`*.keystore` rules -- fixed by adding
  `*.jks.b64`/`*.jks.base64.txt`.

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

Read `ROADMAP.md`'s top section first -- as of patch 23 it's a short
"current state" summary plus a single, consolidated "Technical debt"
list (the original 9-step plan's checklists don't live there
separately anymore; `CHANGELOG.md` has the full step-by-step history
instead). As of this snapshot:

**Done:** Steps 1-4, 6 (6.1-6.6; 6.7 -- an optional monochrome
adaptive-icon layer -- moved to Technical debt, opt-in only, not
required), 7 (Kinescope rename), 8 (`CJM.md`, patch 17 -- "keep this
document current" is ongoing by nature, not a one-time task), and the
environment/build-setup work that had to happen before Step 5 could
even start (patches 07-14). **`./gradlew assembleDebug` succeeds**,
both locally and via `.github/workflows/build-debug.yml` --
**confirmed by a real, successful Actions run** (patch 18). **Step 9's
CI infrastructure is confirmed working too**: a real signed release
build succeeded via `.github/workflows/build-release.yml` (patch 21).
**Step 5's core functionality is now confirmed on a real device**
(Android 13, patch 22's crash-fix + the user's patch-23 confirmation):
install, permissions, share/paste a link, queue and complete a
download, play it back via the system player, background persistence.

**A note on APK size, since the user mentioned an early debug build
felt "heavy":** expected for a debug build (debug symbols, no size
optimization), not a bug. The signed release build was trimmed from
~200MB to something much smaller by restricting `ndk.abiFilters` to
`arm64-v8a` only (patch 21) -- `youtubedl-android`'s bundled
Python/ffmpeg/ffprobe/QuickJS native libraries, multiplied per ABI,
dominate APK size regardless of app code; `isMinifyEnabled = false`
on the release build type is a deliberate, unrelated Step 1 decision
(avoids R8 breaking reflection-heavy coroutine/yt-dlp-wrapper code).

**Still open -- see `ROADMAP.md`'s "Technical debt" section for the
authoritative, current list, not this summary:**

1. **Confirm the signed release APK** (not just the debug build)
   installs and opens on a real device -- the one Step 9 item CI
   success alone doesn't cover.
2. **A handful of more adversarial Step 5 checks**, not individually
   confirmed even though basic functionality now is: the
   race-condition stress test (3-4 videos queued in quick
   succession), `friendlyError()`'s string-matching against a real
   broken/age-restricted/private video, airplane-mode behavior at
   queue time, and a very large/slow download. None of these are
   assumed to pass just because the app runs now -- say so plainly if
   anything here turns out to matter.
3. **`roadmap.md` (lowercase) -- GPT Astra's audit/plan.** Still
   queued for **after** everything above is closed. Don't start it
   early or merge it into `ROADMAP.md`. Once both roadmaps are fully
   executed, delete both files.
4. **The optional backlog** (queue-state persistence across a process
   kill, orphaned temp-file cleanup, `Thread`/`Handler` ->
   coroutines migration, `strings.xml` externalization,
   `collectAsState()` -> `collectAsStateWithLifecycle()`, playlist
   batch-queueing, a self-hosted sync backend -- never required, per
   `CLAUDE.md`) -- unscheduled, user-prioritized, see `ROADMAP.md` for
   the full list with reasoning. In-app delete is fully done (patch
   04).

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
- `res/drawable/ic_download.xml` -- small hand-authored static vector
  (arrow + tray) used by `EmptyQueueState`'s icon; added in patch 22
  to replace a framework `AnimatedVectorDrawable` that Compose's
  `painterResource()` can't load.
- `RELEASE.md` -- signing key generation, signed build (local or CI),
  install instructions; renamed from the old `yt-offline` naming to
  `kinescope` in patch 20. `design.md` -- the visual design system,
  with a section on what it approximates and what it deliberately
  avoids (Anthropic's actual fonts/logo/name); its "YT Offline"
  mentions are now "Kinescope". `CLAUDE.md` -- project ground rules.
  `AGENTS.md` -- process/workflow conventions for an AI coding agent
  working on this repo (added patch 23). `ROADMAP.md` -- current
  status and open technical debt (restructured patch 23; the
  step-by-step history of completed work lives in `CHANGELOG.md`/this
  file instead). `CHANGELOG.md` -- terse per-patch "what shipped"
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

**The project is in steady state, not active roadmap execution.**
Steps 1-9 are done or reduced to specific technical debt (see
`ROADMAP.md`). There's no default next task -- work from whatever the
user actually asks for. If they haven't asked for anything specific,
the highest-value unprompted next step is picking one item off
`ROADMAP.md`'s "Technical debt" list and asking which they'd like
tackled first, rather than assuming.

**What's confirmed as of patch 23:** the app runs correctly end to end
on a real device (Android 13) -- install, share/paste a link, queue
and complete a download, play it back, background persistence. Signed
release builds succeed via CI. **What's still open:** installing that
signed build on a device (Step 9's last item), and a handful of more
adversarial Step 5 checks (race-condition stress test,
`friendlyError()` against a real broken video, airplane mode, a very
large download) that haven't been individually confirmed -- don't
assume they pass just because the app runs now.

For how to work in this repo generally (source-of-truth files,
verification discipline, patch-script delivery and testing
conventions, scope discipline) see **`AGENTS.md`** -- that content used
to live in this section and has moved there so it isn't duplicated
across two files.
