#!/usr/bin/env python3
"""
Patch 15 -- Documentation consolidation after the first successful build.

Context: patch 14 fixed the last compile error, and the user's real
`./gradlew assembleDebug` succeeded for the first time in this
project's history. This is a natural checkpoint before handing off to a
new conversation, so this patch:

  1. Replaces README.md (previously just a bare "# kinescope" title)
     with a real one: what the app is/isn't, build instructions, and a
     documentation map. ROADMAP.md's Step 8 checklist referenced an
     already-drafted README "ready to paste in" from the original
     review, but that draft text isn't present anywhere in the repo
     files available this session -- so this is a fresh rewrite instead
     of pasting in a draft that can no longer be located.
  2. Replaces HANDOFF.md with a full refresh: patch history extended
     through patch 14 (previously stopped at 07), the "what's done"
     summary updated to reflect the successful build, and a new "Key
     learnings" section capturing what patches 07-14 actually
     discovered (multiple JDKs per Codespace, Gradle's per-version JDK
     ceiling, XML comment rules, verifying against tagged source over
     READMEs, cross-patch idempotency pitfalls).
  3. Updates ROADMAP.md:
     - Rewrites the top status block (was still stuck at "after patches
       01-07" and "assembleDebug has still never run", both stale).
     - Marks Step 2's `youtubedl-android`/`ffmpeg` import-path checkbox
       done (it's now confirmed by an actual successful compile, not
       just pre-verified).
     - Consolidates the two scattered environment-debugging notes left
       in the Step 5 section by patches 07 and 12 into one clean
       "build environment" note, since the full story now belongs in
       HANDOFF.md's patch history rather than inline in the living
       roadmap. The actual Step 5 device-testing checklist itself is
       untouched -- none of it has been done yet.

Usage:
    python3 patch15_docs_after_first_build.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


NEW_README = """# Kinescope

A personal Android app for downloading YouTube videos at home, for
offline viewing during work trips to a network-restricted region.

**Status:** the first successful `./gradlew assembleDebug` was achieved
after patches 01-14. Not yet installed on a real device -- Step 5
(device install + manual testing) is next. See `HANDOFF.md` for the
current session-handoff snapshot and `ROADMAP.md` for the full,
actively-tracked implementation plan.

## What this is

Share a YouTube link into the app (or paste one directly), pick a
quality preset, and it downloads in the background via a foreground
service. The finished file lands in the device's Downloads folder,
ready for offline playback in any video player -- no connectivity
needed once it's downloaded.

## What this explicitly is not

- Not distributed via Google Play -- **sideload only** (install the
  debug or signed APK directly).
- No backend, no account system, no cross-device sync.
- No custom YouTube extraction logic. All extraction goes through
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), via the
  [youtubedl-android](https://github.com/yausername/youtubedl-android)
  wrapper library -- never reverse-engineered independently.
- No required paid services.
- Built for one person's own use, not for general distribution.

## Building

This project is developed in GitHub Codespaces.

1. Open a Codespace on this repo. `.devcontainer/devcontainer.json`
   requests a JDK and installs Gradle automatically on container
   creation via `postCreateCommand`.
2. If `gradlew` isn't present yet (a fresh Codespace, or the
   `postCreateCommand` didn't finish), run `bash .devcontainer/setup.sh`
   manually. It installs the Android SDK command-line tools, accepts
   licenses, installs `platform-tools`/`platform 35`/`build-tools`,
   generates the Gradle wrapper, and pins Gradle to a JDK version it
   actually supports -- see `HANDOFF.md`'s "Key learnings" section for
   why a Codespace can have multiple JDKs and why that last step
   matters.
3. `./gradlew assembleDebug`
4. Install the resulting APK on a device (`adb install
   app/build/outputs/apk/debug/app-debug.apk`, or transfer the file and
   tap it).

## Documentation map

- **`CLAUDE.md`** -- ground rules for this project (no custom
  extraction, English-only code/docs, no paid services, no Anthropic
  branding).
- **`ROADMAP.md`** -- the current, active implementation plan (Claude
  Fable 5.1's original 9-step review, plus an ongoing patch-by-patch
  traceability Appendix). Work happens here first.
- **`roadmap.md`** (lowercase) -- a second, separate sprint-based
  audit/plan (S0-S11) from GPT Astra. Deliberately queued for **after**
  `ROADMAP.md` above is fully done -- see the note near the top of
  `ROADMAP.md` for why, and don't start it early.
- **`HANDOFF.md`** -- cross-session snapshot: what's done, what's next,
  full patch history, key learnings. Read this first when resuming work
  in a new conversation.
- **`design.md`** -- the visual design system (colors, typography,
  icon), including an explicit section on what it approximates and what
  it deliberately avoids.
- **`RELEASE.md`** -- signing-key generation and the signed-release
  process (Step 9 -- not started yet).

## Tech stack

Kotlin, Jetpack Compose (Material3), `youtubedl-android` 0.18.1 (a
yt-dlp wrapper), Gradle 8.10.2. `compileSdk`/`targetSdk` 35, `minSdk`
29.
"""

NEW_HANDOFF = """# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also attach a fresh repomix export
(or paste `CLAUDE.md`, `ROADMAP.md`, `roadmap.md`, `design.md`, and
`RELEASE.md` directly).

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
findings-traceability Appendix (now 35 rows; started at 33, grew as
patches 07-14 found more). All patches below were delivered as
self-contained Python scripts for GitHub Codespaces, each one extracted
into a working copy and dry-run-verified (diffs + bracket balance +
idempotency, usually across 2-5 full-chain passes) before being handed
over -- never delivered untested:

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
assembleDebug` now succeeds.**

**Not done, in the order to actually do them:**

1. **Step 5 -- Device install + manual testing.** The compile is done;
   nothing has touched a real device yet. `ROADMAP.md`'s Step 5 section
   has the full manual test checklist, including explicitly
   stress-testing the `DownloadService` race-condition fix from patch
   01 (queue several videos in quick succession). No compile errors are
   expected at this point, but can't be fully ruled out -- if
   `./gradlew assembleDebug` somehow needs to run again for any reason,
   the environment fixes (patches 07-12) are already in place, so this
   should be a normal build, not another environment debugging session.
2. **Step 8 -- Documentation.** `README.md` was rewritten in this
   session (patch 15) rather than using an older draft referenced in
   `ROADMAP.md` that was no longer available in the repo. Still open:
   a `CJM.md` customer-journey-map document, and keeping `ROADMAP.md`
   itself current (ongoing, not a one-time task).
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
  plan (read its top section first). `roadmap.md` -- GPT Astra's
  sprint-based plan, queued for after `ROADMAP.md`.

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7,
`versionName` "1.0.0", Compose BOM `2024.11.00` (Material3 1.3.1),
`youtubedl-android` 0.18.1, Gradle `8.10.2`. `applicationId`/
`namespace`: `com.kinescope.app`. App name: "Kinescope".

## Key learnings & principles

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
  patch's marker. This happened twice in this session (patch 12
  rewriting patch 11's setup.sh block; patch 14 rewriting patch 07's
  Appendix row) and was only caught by running the **full patch chain
  3-5 times from a clean copy**, not a single dry run. Worth doing
  multi-pass regression testing for any patch chain longer than a
  couple of patches, especially once later patches start touching
  earlier patches' output.
- **SIGPIPE in setup scripts:** `pipefail` combined with `yes |` piped
  to a process that closes stdin early produces SIGPIPE errors; license
  acceptance in `sdkmanager` requires a more robust approach (fixed in
  patch 07).
- **Hidden directories:** dot-prefixed folders (e.g. `.devcontainer/`)
  are silently skipped by some file transfer tools and GUI managers --
  `git add -A` from the terminal is required to capture them.

## How to resume in a new conversation

1. Export a fresh repomix XML of the repo (it should reflect patches
   01-14 if they were applied and committed -- confirm with `git log`).
2. Paste it plus this file. `CLAUDE.md`/`ROADMAP.md`/`roadmap.md`/
   `design.md` are nice-to-have if not already covered by the repomix
   export, but this file's "What's actually done vs. still open"
   section above should be enough to know where to pick up.
3. State which of the "not done" items above to work on next -- they're
   meant to happen in that order, but say so explicitly, since a new
   conversation has no memory of *why* that order matters otherwise.

## Immediate next step for Claude (in a new conversation)

Continue at **Step 5 (device install + manual testing)** unless told
otherwise. The compile is done -- `./gradlew assembleDebug` succeeds.
`ROADMAP.md`'s Step 5 section has the full manual test checklist,
including explicitly stress-testing the `DownloadService`
race-condition fix from patch 01 (queue several videos in quick
succession). Continue the established pattern for this project:

- Read the actual current file content before editing -- don't assume
  memory of it is accurate; things have changed across 14 patches.
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
  idempotency check (see "Key learnings" above).
- Update `ROADMAP.md`'s checkboxes and Appendix in the same patch as
  the code change they correspond to.
- Write all code, code comments, commit messages, and documentation in
  English, regardless of what language the conversation itself is in.
"""


def write_if_needed(path: Path, content: str, marker: str, label: str):
    if path.exists():
        existing = path.read_text()
        if marker in existing:
            print(f"{label} already up to date -- skipping.")
            return
    path.write_text(content)
    print(f"Wrote {path}")


def patch_readme(repo_root: Path):
    write_if_needed(
        repo_root / "README.md",
        NEW_README,
        "the first successful `./gradlew assembleDebug` was achieved",
        "README.md",
    )


def patch_handoff(repo_root: Path):
    write_if_needed(
        repo_root / "HANDOFF.md",
        NEW_HANDOFF,
        "Milestone: the first successful",
        "HANDOFF.md",
    )


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    # 1. Top status block
    old_status_block = (
        "**Status as of this update (after patches 01-07):** Steps 1-4, Step 6\n"
        "(6.1-6.6; 6.7 is optional and still skipped), and Step 7 (Kinescope\n"
        "rename) are done. `./gradlew assembleDebug` has **still never run** —\n"
        "nothing here has been build-verified yet. A full deep code review of\n"
        "the entire codebase was\n"
        "performed by Claude Fable 5.1 in 4 passes; this document consolidates every\n"
        "finding from that review into one ordered implementation plan, and its\n"
        "checkboxes/Appendix are kept current as work actually gets done (see\n"
        "`HANDOFF.md` for the patch-by-patch history).\n"
    )
    new_status_block = (
        "**Status as of this update (after patches 01-15):** Steps 1-4, Step 6\n"
        "(6.1-6.6; 6.7 is optional and still skipped), Step 7 (Kinescope\n"
        "rename), and the full build-environment/compile-error fix chain\n"
        "(patches 07-14) are done. **`./gradlew assembleDebug` succeeds** — the\n"
        "first successful build in this project's history. Nothing has\n"
        "touched a real device yet; that's Step 5, next. A full deep code\n"
        "review of the entire codebase was performed by Claude Fable 5.1 in 4\n"
        "passes; this document consolidates every finding from that review\n"
        "into one ordered implementation plan, and its checkboxes/Appendix are\n"
        "kept current as work actually gets done (see `HANDOFF.md` for the\n"
        "patch-by-patch history).\n"
    )
    if old_status_block in text:
        text = text.replace(old_status_block, new_status_block)
    elif new_status_block in text:
        pass
    else:
        fail("Top status block anchor not found in ROADMAP.md")

    # 2. Step 2 checkbox
    old_step2 = (
        "- [ ] **[LOW] `youtubedl-android`/`com.yausername.ffmpeg` import paths** —\n"
        "  believed correct after review, but do a 30-second sanity check\n"
        "  (`unzip -l` the resolved AAR in `~/.gradle/caches`, or just read the\n"
        "  compiler's \"cannot resolve symbol\" errors if any appear) rather than\n"
        "  trusting anyone's memory, including this document's.\n"
    )
    new_step2 = (
        "- [x] **[LOW] `youtubedl-android`/`com.yausername.ffmpeg` import paths**\n"
        "  (Fixed — patch 14: confirmed via an actual successful compile,\n"
        "  after finding and fixing one real bug — `UpdateChannel` is a\n"
        "  nested class of `YoutubeDL`, not top-level — by reading the\n"
        "  library's actual tagged 0.18.1 source directly. See Appendix #11.)\n"
    )
    if old_step2 in text:
        text = text.replace(old_step2, new_step2)
    elif new_step2 in text:
        pass
    else:
        fail("Step 2 import-path checkbox anchor not found in ROADMAP.md")

    # 3. Consolidate the two scattered Step 5 environment notes
    old_note_1 = (
        "**Environment note (patch 07):** `.devcontainer/setup.sh` had a "
        "`pipefail`-related bug that could silently abort setup before "
        "`gradlew` was ever generated (see Appendix #34) — fixed. Appendix "
        "#11 (`youtubedl-android`/`ffmpeg` import paths) has also been "
        "pre-verified against the library's own current source — still "
        "needs final confirmation by an actual `./gradlew assembleDebug` "
        "run, but the single most-likely compile blocker going into this "
        "step is now lower-risk than before.\n"
    )
    old_note_2 = (
        "\n**Confirmed root cause (patch 12):** a real diagnostic run "
        "confirmed the patch-11 hypothesis and refined it. `java`/`javac` "
        "resolve to a Codespace-provided JDK 25.0.2 "
        "(`/home/codespace/java/current`), separate from and taking "
        "priority over the devcontainer Java feature's SDKMAN-managed "
        "install. SDKMAN itself only has `21.0.10-ms` and `25.0.2-ms` -- "
        "no 17.x at all, despite `devcontainer.json` requesting version "
        "17. Gradle's own 8.10 release notes confirm the ceiling: "
        '"Gradle now supports running on Java 23" -- JDK 24+ cannot run '
        "Gradle 8.10.2. Patch 12 broadened patch 11's JDK search to "
        "accept any installed JDK in the 17-23 range (picking up the "
        "already-installed 21.0.10-ms here) instead of requiring exactly "
        "17. Still needs the next `./gradlew assembleDebug` run to "
        "confirm.\n"
    )
    new_note = (
        "**Build environment (patches 07-14):** the environment needed five "
        "fixes before the first compile could even be attempted — a "
        "`pipefail` bug in `setup.sh` (07), two re-run/idempotency bugs in "
        "`setup.sh` (10), and a JDK/Gradle mismatch where this Codespace's "
        "actual default JDK (25.0.2) is too new for Gradle 8.10.2 (ceiling: "
        "Java 23, per Gradle's own 8.10 release notes), fixed by pinning "
        "Gradle to an already-installed JDK 21 instead (11-12). Two real "
        "compile errors followed: an invalid `--` inside an XML comment "
        "(13), and `UpdateChannel` actually being a nested class of "
        "`YoutubeDL` rather than top-level, i.e. Appendix #11 (14). Full "
        "story in `HANDOFF.md`'s patch history and \"Key learnings\" — kept "
        "brief here since it's now resolved history, not an open risk. "
        "**`./gradlew assembleDebug` succeeds.**\n"
    )
    if old_note_1 in text and old_note_2 in text:
        text = text.replace(old_note_1, new_note)
        text = text.replace(old_note_2, "")
    elif "**Build environment (patches 07-14):**" in text:
        pass
    else:
        fail("Step 5 environment-note anchors not found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 15 against: {repo_root}")

    patch_readme(repo_root)
    patch_handoff(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 15 applied successfully.")


if __name__ == "__main__":
    main()
