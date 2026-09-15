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
