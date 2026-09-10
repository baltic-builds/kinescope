# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also paste (or attach a fresh
repomix export covering) `CLAUDE.md`, `ROADMAP.md`, `design.md`, and
`RELEASE.md`.

## Project identity

Personal Android app for downloading YouTube videos at home for
offline viewing during work trips to a network-restricted region.
Sideload-distributed only — no Google Play, no backend, no required
cost. The rename to **Kinescope** is done: the codebase and package
are `com.kinescope.app` / "Kinescope" (previously
`com.baltic.ytoffline` / "YT Offline").

## How this codebase got here

Written by Claude across 7 phases with no intermediate compilation
(explicit user instruction at the time: "keep going, test everything
at the end"). A deep 4-part review by Claude Fable 5.1 then produced
`ROADMAP.md` — a sequenced, prioritized fix list with a 33-item
findings-traceability Appendix. Six patch scripts have since been
applied against that roadmap, all delivered as self-contained Python
scripts for GitHub Codespaces, each one extracted into a working copy
and dry-run-verified (diffs + bracket balance + idempotency) before
being handed over — never delivered untested:

- **Patch 01** — ROADMAP Steps 1-3: the Compose BOM version (was a
  fabricated future release that doesn't exist), `execute()`'s
  progress-callback arity (confirmed 3-parameter against the
  youtubedl-android library's own sample-app source, not guessed),
  `updateYoutubeDL()`'s required `UpdateChannel` argument, the
  `DownloadService` worker race condition (fixed more thoroughly than
  the roadmap's own sample fix actually closes — see the doc comment
  above `startWorkerLocked()` for why), and a catch-all exception
  handler so one bad download can no longer crash the whole process.
- **Patch 02** — ROADMAP Step 4: filename humanization (yt-dlp writes
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
- **Patch 03** — ROADMAP Step 6.1/6.2/6.3/6.6: a real dark
  `ColorScheme` (there was only ever a light one), three tokens
  Material3's baseline `ColorScheme` has no slot for (`surfaceRaised`,
  `warning`, `errorContainer`) exposed via a `CompositionLocal`-backed
  `YtOfflineExtras` object (mirrors how `MaterialTheme.colorScheme`
  itself is accessed), a completed typography scale, and the
  adaptive-icon safe-zone clipping fix.
- **Patch 04** — ROADMAP Step 6.5: queue-row thumbnail placeholders
  and a real `LinearProgressIndicator` (required adding a
  `progressFraction: Float?` field to `DownloadJobStatus` — previously
  there was only a formatted string like "45% (ETA 12s)"), an
  empty-queue illustration, a Library overflow menu with **working**
  Share (`Intent.ACTION_SEND`) and Delete (`ContentResolver.delete()`
  — genuinely new functionality, not just a UI affordance), a
  sectioned Settings screen (with a persisted yt-dlp-last-updated
  timestamp, new), a dismissible connectivity-loss banner, and a
  composer-bar focus-border fix.
- **Patch 06** — ROADMAP Step 7 (Kinescope rename): `namespace` /
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
  unchanged — not user-visible, not in ROADMAP.md's Step 7 checklist,
  and renaming them would add risk for no user-facing benefit.
  (Patch 05 isn't listed here as a numbered accomplishment — it was
  the documentation-only patch that produced this file and the
  current ROADMAP.md status section.)

- **Patch 07** — Pre-Step-5 environment fix: `.devcontainer/setup.sh` had a `pipefail` bug (`yes | sdkmanager --licenses`, see `ROADMAP.md` Appendix #34) that could silently abort setup before the Gradle wrapper was ever generated — fixed by disabling `pipefail` around just that one pipeline and checking `sdkmanager`'s real exit status explicitly. Also pre-verified the `youtubedl-android`/`ffmpeg` import paths and API shapes (`YoutubeDL`/`YoutubeDLRequest`/`YoutubeDLException`/`UpdateChannel` in `com.yausername.youtubedl_android`, `FFmpeg` in `com.yausername.ffmpeg`, the 3-parameter `execute()` progress callback, `updateYoutubeDL(context, UpdateChannel)`) against the library's own current GitHub source and README — all matched, though this still isn't a substitute for the real `./gradlew assembleDebug` run.

**Version-compatibility note worth remembering:** Compose BOM
2024.11.00 (fixed in patch 01) pulls in Material3 **1.3.1**. Some APIs
changed shape in *later* Material3 versions than that — e.g.
`LinearProgressIndicator`'s lambda-based `progress: () -> Float`
overload wasn't added until 1.5.0-alpha17, so patch 04 deliberately
uses the older plain-`Float` overload (confirmed still valid, not even
deprecated, at 1.3.1 — verified against the actual androidx API
surface, not assumed). **If a future change bumps the Compose BOM,
recheck call sites like this one against whatever Material3 version
the new BOM actually pulls in** — current docs/tutorials default to
showing the latest API, which won't necessarily compile against an
older pinned version.

## What's actually done vs. still open

Read `ROADMAP.md`'s top section first — it has the authoritative,
up-to-date status summary and the correct execution order (which does
**not** match the document's own Step numbering). As of this snapshot:

**Done:** Steps 1, 2 (except Appendix finding #11 — the
`youtubedl-android`/`ffmpeg` import paths can only be confirmed by an
actual `./gradlew assembleDebug`, which still hasn't run), 3, 4, 6
(6.1-6.6; 6.7 — an optional monochrome adaptive-icon layer for Android
13+ themed icons — is still skipped, opt-in only, not required), and 7
(Kinescope rename — `applicationId`/`namespace`/`rootProject.name` now
`com.kinescope.app` / `kinescope`, Kotlin package directory moved and
repackaged, user-visible strings updated).

**Not done, in the order to actually do them:**

1. **Step 5 — First device install + testing.** Also the first time
   `./gradlew assembleDebug` actually runs — expect to find and fix
   compile errors here, most likely around the `youtubedl-android`
   import paths (Appendix finding #11, never confirmed any other
   way). `ROADMAP.md`'s Step 5 section has the full manual test
   checklist, including explicitly stress-testing the
   `DownloadService` race-condition fix from patch 01 (queue several
   videos in quick succession). Now that the `applicationId` is
   settled (`com.kinescope.app`), this is safe to run without
   revisiting the rename afterward.
2. **Step 8 — Documentation** (a rewritten `README.md` is already
   drafted and ready to paste in per `ROADMAP.md`; a `CJM.md`
   customer-journey-map document; keep `ROADMAP.md` itself current).
3. **Step 9 — Signed release**, per `RELEASE.md`, only once every item
   in Steps 1-5 is confirmed working on a real device. Note:
   `RELEASE.md` still uses the old `yt-offline` name for the keystore
   filename/alias and the GitHub release title — those are free-form
   labels with no functional tie to `applicationId`, left alone during
   the Step 7 rename and worth a quick pass (or not — purely cosmetic)
   when Step 9 actually happens.

**Backlog (optional, unscheduled — see `ROADMAP.md`'s Backlog section
for the full list with reasoning):** persisting queue state across a
process kill, orphaned-temp-file cleanup on service start, migrating
remaining `Thread`/`Handler` usage to coroutines for consistency with
`DownloadService`'s own fix, externalizing hardcoded UI strings to
`strings.xml`, `collectAsState()` → `collectAsStateWithLifecycle()`,
playlist batch-queueing, a self-hosted sync backend (explicitly never
required, per `CLAUDE.md`). In-app delete is fully done as of patch 04.

## File map (current, post-Kinescope-rename — package `com.kinescope.app`)

All files below live under
`app/src/main/java/com/kinescope/app/` (was
`app/src/main/java/com/baltic/ytoffline/` before patch 06).

- `MainActivity.kt` — screen composables: `DownloadScreen`,
  `QueueRow`, `EmptyQueueState`, `ConnectivityBanner`, `LibraryRow`,
  `ComposerBar`, `SettingsPanel`/`SettingsSectionHeader`. Also
  `playItem()`/`shareItem()` (Intent-based, both guard
  `ActivityNotFoundException`) and `isYouTubeUrl()`/`extractUrl()`
  (host allowlist). `TopAppBar` title now reads "Kinescope".
- `DownloadService.kt` — foreground service; a single background
  worker `Thread` draining a `LinkedBlockingQueue`, restarted on
  demand (see the `startWorkerLocked()` doc comment for the
  race-condition reasoning); `runJob()` does the actual yt-dlp
  `execute()` call, job-id-tag file scanning, and `friendlyError()`
  mapping. Notification title now reads "Kinescope";
  `ACTION_ENQUEUE` now `com.kinescope.app.ACTION_ENQUEUE`.
- `DownloadQueueBus.kt` — shared
  `MutableStateFlow<List<DownloadJobStatus>>` between the service
  (producer) and UI (consumer); `NO_INTERNET_MESSAGE` constant shared
  with `DownloadService` so the connectivity banner can't drift out of
  sync with a hand-typed string duplicated in two files.
- `QualityPresets.kt` — the four quality/format options
  (`label`/`mimeType`/`apply: YoutubeDLRequest.() -> Unit`).
- `MediaStorage.kt` — `publish()`, `listPublished()`, `delete()`
  against `MediaStore.Downloads`.
- `Settings.kt` — `SharedPreferences` wrapper: default quality index,
  sanitized Downloads subfolder name, last-yt-dlp-update timestamp.
- `YtDlpUpdater.kt` — wraps the library's self-update call, records
  the last-update timestamp on success.
- `YtOfflineApp.kt` — yt-dlp/ffmpeg init + startup update check.
  Class name kept as `YtOfflineApp` (internal identifier, not
  user-visible, not part of the Step 7 rename scope) despite living in
  `com.kinescope.app` now.
- `Theme.kt` — `YtOfflineTheme` (light + dark `ColorScheme`),
  `YtOfflineExtras` (the `success`/`warning`/`surfaceRaised` extension
  colors), the full typography scale, downloadable Google Fonts
  (Inter/Lora) via `font_certs.xml`. Same note on identifier names as
  above.
- `ic_launcher_foreground.xml` / `ic_launcher_background.xml` /
  `mipmap-anydpi-v26/ic_launcher*.xml` — adaptive icon, safe-zone
  fixed in patch 03. Unchanged by the rename (no icon/color changes
  needed for a name-only rebrand).
- `RELEASE.md` — signing key generation, signed build, install
  instructions; still uses the old `yt-offline` name in places
  (Step 9 scope, not touched by patch 06). `design.md` — the visual
  design system, with a section on what it approximates and what it
  deliberately avoids (Anthropic's actual fonts/logo/name); its "YT
  Offline" mentions are now "Kinescope". `CLAUDE.md` — project ground
  rules. `ROADMAP.md` — the living, checkbox-tracked implementation
  plan (read its top section first).

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7,
`versionName` "1.0.0", Compose BOM `2024.11.00` (Material3 1.3.1),
`youtubedl-android` 0.18.1. `applicationId`/`namespace`:
`com.kinescope.app`. App name: "Kinescope".

## How to resume in a new conversation

1. Export a fresh repomix XML of the repo (it should already reflect
   patches 01-06 if they were applied and committed — confirm with
   `git log`).
2. Paste it plus this file. `CLAUDE.md`/`ROADMAP.md`/`design.md` are
   nice-to-have if not already covered by the repomix export, but this
   file's "What's actually done vs. still open" section above should
   be enough to know where to pick up.
3. State which of the three "not done" items above to work on next —
   they're meant to happen in that order, but say so explicitly, since
   a new conversation has no memory of *why* that order matters
   otherwise.

## Immediate next step for Claude (in a new conversation)

Continue at **Step 5 (first device install + testing)** unless told
otherwise. `ROADMAP.md`'s Step 5 section has the full manual test
checklist. This is also the first time `./gradlew assembleDebug`
actually runs — expect compile errors, most likely around the
`youtubedl-android`/`ffmpeg` import paths (Appendix finding #11, never
confirmed any other way). Continue the established pattern for this
project:

- Read the actual current file content before editing — don't assume
  memory of it is accurate; things have changed across 6 patches.
- Verify uncertain library/API claims against a real source (the
  library's own sample code, the actual androidx API surface, etc.)
  rather than guessing from general familiarity — this project has
  already been burned once by a fabricated dependency version and
  once by a plausible-but-wrong callback signature.
- Deliver changes as a self-contained Python patch script for GitHub
  Codespaces. Extract the repomix into a local working copy first,
  dry-run the script against it, verify diffs and bracket balance and
  idempotency (run it twice), *then* deliver — never hand over an
  untested script.
- Update `ROADMAP.md`'s checkboxes and Appendix in the same patch as
  the code change they correspond to.
- Write all code, code comments, commit messages, and documentation in
  English, regardless of what language the conversation itself is in.
