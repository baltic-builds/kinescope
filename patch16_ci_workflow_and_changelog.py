#!/usr/bin/env python3
"""
Patch 16 -- CI build workflow, plus a roadmap/changelog process change.

Context: the user now builds APKs via GitHub Actions (manual trigger,
hand-assigned version per run) instead of a local Codespace
`./gradlew assembleDebug` + browser download, and wants completed
ROADMAP.md items removed from that file (collapsed to a one-line
pointer) with the actual detail logged in a new CHANGELOG.md instead,
going forward, item by item, in order.

This patch:
  1. Adds `.github/workflows/build-debug.yml` -- a manual-only
     (`workflow_dispatch`) GitHub Actions workflow that builds a debug
     APK and uploads it as a run artifact. Version name is supplied by
     hand each run; `versionCode` is derived from the Actions run
     number so it always increases (avoids
     INSTALL_FAILED_VERSION_DOWNGRADE on reinstall). No automatic
     trigger.
  2. Makes `app/build.gradle.kts`'s `versionCode`/`versionName`
     overridable via optional Gradle properties (`appVersionCode`/
     `appVersionName`, read by that workflow via `-P`), falling back
     to the existing hardcoded `7`/`"1.0.0"` for local builds that
     don't pass them.
  3. Creates `CHANGELOG.md`, backfilled with concise entries for
     patches 01-16 (the detail that used to live in ROADMAP.md's
     per-item checklists).
  4. Trims `ROADMAP.md`: Steps 1, 2, 3, 4, 6 (6.1-6.6), and 7 collapsed
     to one-line "done, see CHANGELOG.md" pointers; their closed
     Appendix findings removed from the Appendix table (kept: rows
     16, 17, 19, 20, 33, which are still open/informational); Step 8's
     README checkbox corrected to [x] (the work was actually done in
     patch 15, but the checkbox was never flipped); the never-done,
     optional `ndk.abiFilters` trim moved from Step 1 into Backlog
     (it was never actually blocking anything). Step 5's full
     checklist -- the current active work -- is untouched.
  5. Updates `CLAUDE.md`'s instruction log, `README.md`'s Building
     section, and `HANDOFF.md` (missing Patch 15 entry added, new
     Patch 16 entry, file map, "what's done", Key learnings, How to
     resume, Immediate next step) to match.
  6. Patches `patch07_fix_setup_and_verify_imports.py`,
     `patch13_fix_invalid_xml_comment.py`,
     `patch14_fix_updatechannel_import.py`, and
     `patch15_docs_after_first_build.py` themselves: each depends on
     ROADMAP.md text (Appendix rows 11/34/35, the top status block,
     Step 2's checkbox) that step 4 above removes or rewrites. Caught
     by this patch's own multi-pass full-chain regression test (see
     module-level note below) -- without this fix, re-running the full
     chain from a clean copy would make those four scripts either fail
     loudly or silently resurrect content this patch intentionally
     removed. Each gets a short-circuit guard: if this patch's
     Appendix-trim marker is present, skip that script's ROADMAP.md
     edit entirely -- there's nothing left for it to do.

Whole-file edits (ROADMAP.md, HANDOFF.md, CLAUDE.md, README.md,
app/build.gradle.kts, and the four older patch scripts in step 6) use
an exact-match guard on the ENTIRE file content rather than small
anchored fragments: these files each get several interrelated changes
in this patch, and a single whole-file guard is more robust than
independent small anchors against a heavily-restructured document like
ROADMAP.md. If a file's content matches neither the expected pre-patch
nor post-patch state, the script fails loudly and asks for manual
review rather than guessing.

Tested per this project's established discipline: extracted into a
local working copy, dry-run-verified (diffs + bracket/brace balance +
idempotency), and the full available patch chain (07 through this one;
01-06 and 11 predate this repomix export and are already baked into
the source) was run 3 times from a clean copy to confirm no patch's
own idempotency check breaks another's -- this is exactly what caught
the issue fixed in step 6 above.

Usage:
    python3 patch16_ci_workflow_and_changelog.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def write_if_needed(path: Path, content: str, marker: str, label: str):
    """For brand-new files: create if missing/stale, skip if the
    idempotency marker is already present."""
    if path.exists():
        existing = path.read_text()
        if marker in existing:
            print(f"{label} already up to date -- skipping.")
            return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    print(f"Wrote {path}")


def whole_file_guarded_replace(path: Path, expected_old: str, new_content: str, label: str, superseded_marker: str = None):
    """Exact-match guarded replace of an ENTIRE file's content.
    Idempotent: if the file already equals `new_content`, skip. If
    `superseded_marker` is given and present in the file, a later
    patch has already modified this file further -- also skip, since
    there's nothing left for this patch to do. If the file matches
    none of those, fail loudly instead of overwriting something
    unexpected."""
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()
    if text == new_content:
        print(f"{label} already up to date -- skipping.")
        return
    if superseded_marker and superseded_marker in text:
        print(f"{label} already superseded by a later patch -- skipping.")
        return
    if text != expected_old:
        fail(
            f"{path} doesn't match the expected pre-patch content "
            f"(and isn't already patched either) -- it may have changed "
            f"since last session. Manual review needed for {label}."
        )
    path.write_text(new_content)
    print(f"Patched: {label}")


NEW_WORKFLOW_YML = """name: Build Debug APK

# Manual-only build. No push/PR/schedule trigger by design -- every
# build is a deliberate, hand-versioned run (see ROADMAP.md Step 5 /
# CHANGELOG.md Patch 16). Produces a debug (not release-signed) APK,
# uploaded as a workflow artifact for manual sideload -- no adb or
# local Codespace download required.
on:
  workflow_dispatch:
    inputs:
      version_name:
        description: >-
          App version name to stamp on this build (e.g. "1.1.0").
          versionCode is derived automatically from this run's number
          (below), so it always increases even if you reuse a
          version_name -- avoids INSTALL_FAILED_VERSION_DOWNGRADE when
          reinstalling over an older build on the same device.
        required: true
        type: string

permissions:
  contents: read

jobs:
  build-debug:
    name: Build debug APK
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          # Note: unlike the project's GitHub Codespace (see
          # HANDOFF.md's "Key learnings" -- a Codespace-provided JDK
          # 25.0.2 competes with SDKMAN's JDK 21 and had to be pinned
          # around via gradle.properties, patches 11-12), a GitHub
          # Actions runner is a clean, single-JDK environment: this
          # setup-java step is the only JDK on PATH, so no extra
          # pinning is needed here. JDK 21 matches the JDK already
          # confirmed to work for this project and sits comfortably
          # under Gradle 8.10.2's Java 23 ceiling.

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4
        with:
          # Matches compileSdk/targetSdk 35 and the build-tools version
          # already confirmed correct in ROADMAP.md's Appendix (closed
          # finding, see CHANGELOG.md).
          packages: "platform-tools platforms;android-35 build-tools;35.0.0"

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Build debug APK
        run: >-
          ./gradlew --no-daemon assembleDebug
          -PappVersionName="${{ inputs.version_name }}"
          -PappVersionCode="${{ github.run_number }}"

      - name: Rename APK with version
        id: rename_apk
        run: |
          src="app/build/outputs/apk/debug/app-debug.apk"
          if [ ! -f "$src" ]; then
            echo "Expected APK not found at $src" >&2
            exit 1
          fi
          dest="app/build/outputs/apk/debug/kinescope-${{ inputs.version_name }}-debug.apk"
          mv "$src" "$dest"
          echo "apk_path=$dest" >> "$GITHUB_OUTPUT"

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: kinescope-${{ inputs.version_name }}-debug
          path: ${{ steps.rename_apk.outputs.apk_path }}
          if-no-files-found: error
          retention-days: 30
"""


def patch_workflow_file(repo_root: Path):
    write_if_needed(
        repo_root / ".github" / "workflows" / "build-debug.yml",
        NEW_WORKFLOW_YML,
        marker="workflow_dispatch",
        label=".github/workflows/build-debug.yml",
    )


NEW_CHANGELOG = """# Changelog

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
"""


def patch_changelog(repo_root: Path):
    write_if_needed(
        repo_root / "CHANGELOG.md",
        NEW_CHANGELOG,
        marker="## Patch 16",
        label="CHANGELOG.md",
    )

OLD_ROADMAP_MD = r"""# Roadmap

**Status as of this update (after patches 01-15):** Steps 1-4, Step 6
(6.1-6.6; 6.7 is optional and still skipped), Step 7 (Kinescope
rename), and the full build-environment/compile-error fix chain
(patches 07-14) are done. **`./gradlew assembleDebug` succeeds** — the
first successful build in this project's history. Nothing has
touched a real device yet; that's Step 5, next. A full deep code
review of the entire codebase was performed by Claude Fable 5.1 in 4
passes; this document consolidates every finding from that review
into one ordered implementation plan, and its checkboxes/Appendix are
kept current as work actually gets done (see `HANDOFF.md` for the
patch-by-patch history).

**Decided:** the Kinescope rename (Step 7) uses `applicationId` /
`namespace` **`com.kinescope.app`**.

**Execution order from here — this deliberately does NOT match the Step
numbers below**, because Step 7 must happen before Step 5's first device
install (changing `applicationId` after that is effectively irreversible —
Android treats it as a different app), and Step 6 needed to be finished
first since Step 7 touches many of the same files:

1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)
2. ~~Step 7 — Kinescope rename~~ ✅ done (patch 06)
3. **Step 5 — First device install + testing** ← next
4. Step 8 — Documentation
5. Step 9 — Signed release

**After Step 9 is done, and only then:** this repo also has a `roadmap.md` (lowercase) — a separate, newer sprint-based audit/plan (S0-S11, findings F01-F42) from GPT Astra, added by the user and not yet started. Do not merge it into this document or start it early — finish everything above (through Step 9) first. Once both this `ROADMAP.md` and `roadmap.md` are fully executed, both files get deleted.

Steps 1-4 (compile blockers, then critical/product-quality fixes) are done
and came first, as they had to — nothing else matters until the app
actually compiles.

**There is no Phase 8.** The sections below are **verification and fix
steps**, not new numbered feature phases — this document extends the
`Step 1-5` verification plan Fable proposed, it doesn't replace it with new
"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.

**How to use this document:** the numbered Step sections below keep their
original order (matching the initial review) for reference — follow the
**execution order above**, not the numbering, for what to actually do
next. The Appendix at the end is a full traceability table; every finding
from the original 4-part review is listed there with its current status.

---

## Step 1 — Fix known compile-time blockers, before first sync

Do these *before* running `./gradlew assembleDebug` for the first time —
they're the ones the review is fairly confident will fail loudly and
immediately.

- [x] **[CRITICAL] Fix the Compose BOM version.** (Fixed — patch 01)
  `app/build.gradle.kts` currently pins:
  ```kotlin
  val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
  ```
  Every other pinned version in the same file (AGP 8.7.2, Kotlin 2.1.0,
  Gradle 8.10.2, `activity-compose` 1.9.3, `core-ktx` 1.15.0,
  `kotlinx-coroutines-core` 1.9.0) clusters around Sept–Nov 2024. The BOM
  is the one outlier, ~21 months later — almost certainly a fabricated
  version string. Replace it with a real BOM from the same window (check
  the [Compose BOM mapping table](https://developer.android.com/jetpack/compose/bom/bom-mapping)
  for the latest one that actually existed at write time — `2024.10.01` or
  `2024.11.00` are good starting guesses), or just run
  `./gradlew dependencies` and let Gradle's own resolution error tell you
  the nearest valid version.

- [x] **[LOW, cleanup] Remove dead `requestLegacyExternalStorage="true"`** (Fixed — patch 01)
  from `AndroidManifest.xml`. Only honored at `targetSdkVersion <= 29`;
  here `targetSdk = 35`, so it's silently ignored. `MediaStore`-based
  publishing (Phase 3) is the real mechanism already handling this
  correctly — the flag is leftover noise from copy-pasting
  `youtubedl-android`'s own README setup instructions. Zero functional
  risk either way, just delete it for clarity.

- [ ] **[Optional, not required] Trim `x86`/`x86_64` from `ndk.abiFilters`**
  in `app/build.gradle.kts` if your target phone is arm64 (the
  overwhelming majority are) and you don't need emulator support — shrinks
  the APK, since `youtubedl-android`'s bundled native binaries dominate
  APK size. Purely optional; the full 4-ABI set is not wrong, just larger
  than necessary for a single-physical-phone target.

**Confirmed fine, no action needed at this step** (verified against the
real build files during the review, listed here so you don't waste time
re-checking them): `sdkmanager` package identifiers
(`platforms;android-35`, `build-tools;35.0.0`) match `compileSdk`/`targetSdk`
correctly · `android:extractNativeLibs="true"` is correct and required by
`youtubedl-android`'s native binaries, keep it · AGP 8.7.2 + Gradle 8.10.2
+ Kotlin 2.1.0 is a real, mutually compatible toolchain · `isMinifyEnabled
= false` on release is a deliberate, reasonable call (avoids R8 breaking
reflection-heavy coroutine/yt-dlp-wrapper code) for a personal one-user
app, revisit only if APK size ever actually becomes a problem.

---

## Step 2 — First headless compile

```bash
./gradlew assembleDebug
```

Fix compile errors **top-down, earliest-phase code first** — an early
wrong assumption commonly cascades into unrelated-looking errors further
down the same file. Specifically watch for:

- [x] **[HIGH] `YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds -> ... }`** (Fixed — patch 01: confirmed 3-parameter callback against the library's sample app source)
  in `DownloadService.kt` — the real `youtubedl-android` progress callback
  is believed to be **three-parameter**
  (`progress: Float, etaInSeconds: Long, line: String`), not two. Kotlin
  requires exact arity for lambda literals against a function type, so
  this should either fail to compile (in which case: add the third
  parameter, ignore it if unused — `{ progress, etaInSeconds, _ -> ... }`)
  or, if it *does* compile as written, that means the real signature is
  two-parameter after all and no fix is needed. Either way this is a
  compile-time question that resolves itself here — just don't be
  surprised by it.

- [x] **[LOW] `youtubedl-android`/`com.yausername.ffmpeg` import paths**
  (Fixed — patch 14: confirmed via an actual successful compile,
  after finding and fixing one real bug — `UpdateChannel` is a
  nested class of `YoutubeDL`, not top-level — by reading the
  library's actual tagged 0.18.1 source directly. See Appendix #11.)

- [x] **[LOW] `updateYoutubeDL()` return type** in `YtDlpUpdater.kt` (Fixed — patch 01: added the required `UpdateChannel` argument) — if
  the real method returns an enum (`YoutubeDLUpdateStatus`) rather than a
  printable `String`, this is a type-mismatch compile error, not a silent
  runtime no-op. Low-impact either way (fails loud and cheap to fix here,
  or fails soft at runtime if the assumption happens to be compatible).

**Confirmed fine, no action needed at this step:** `addOption(key)` /
`addOption(key, value)` overload usage in `QualityPresets.kt` matches the
real `YoutubeDLRequest` API shape.

Do not move to Step 3 until `assembleDebug` succeeds cleanly.

---

## Step 3 — Critical runtime fixes, before first device install

These two are **confirmed real bugs found by reading the actual source**,
not speculation — fix both before the first real-device test, not after.
They map directly onto the single highest-risk moment in the whole
product's Customer Journey Map: queuing several videos back-to-back at
home the night before a trip, when a silent failure is most likely and
most expensive (no way to retry once already in-region).

- [x] **[CRITICAL] Race condition in `DownloadService.ensureWorkerRunning()`.** (Fixed — patch 01, with a tighter lock than the sample fix below — see the code comment in DownloadService.kt)
  `queue.poll()` is non-blocking and the `workerThread?.isAlive == true`
  check is unsynchronized. Sequence that strands a job forever at
  "Queued": worker thread finishes a job, `poll()` returns `null`, worker
  is about to exit → at that exact moment a new job is enqueued from the
  main thread → `ensureWorkerRunning()` sees `isAlive == true` (worker
  hasn't finished tearing down yet) and assumes the existing worker will
  pick it up → but the worker already committed to exiting and never
  re-polls. The new job sits at "Queued" forever with no error shown.

  **Fix** — replace the poll-until-empty pattern with a blocking consumer
  loop and a lock-guarded state transition:
  ```kotlin
  workerThread = Thread {
      startForegroundWithNotification("Starting downloads…")
      try {
          while (true) {
              val job = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: break
              runJob(job)
          }
      } finally {
          synchronized(lock) { workerThread = null }
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
      }
  }
  ```
  with `queue.add()`, the `isAlive` check, and the `workerThread`
  assignment all guarded by the same lock. **Better long-term fix**, worth
  doing now since it also resolves the idiomaticity note in the Backlog
  section below: replace the raw `Thread` + `LinkedBlockingQueue` with a
  `Channel<DownloadJob>` consumed by a single coroutine launched once in
  `onCreate()` and never torn down until the service itself is destroyed —
  this removes the restart-detection problem entirely instead of patching
  around it, and brings the file in line with the `kotlinx-coroutines-core`
  dependency already used elsewhere.

- [x] **[CRITICAL] Unhandled exceptions in `runJob()` can crash the whole app.** (Fixed — patch 01)
  Only `YoutubeDLException` and `InterruptedException` are caught. Any
  other exception type (`IOException`, an unexpected `NullPointerException`
  from an unusual library response shape, etc.) propagates out of the
  worker thread uncaught — and Android's default behavior for an uncaught
  exception on *any* thread is to kill the whole process. One malformed
  video or one unexpected error type can silently take down every other
  job still waiting in the queue, with zero explanation to the user.

  **Fix** — add a catch-all after the existing specific catches, so
  specific handling still takes priority but nothing escapes:
  ```kotlin
  } catch (e: Exception) {
      DownloadQueueBus.update(job.id) {
          it.copy(state = JobState.FAILED, progressText = friendlyError(e.message ?: e.javaClass.simpleName))
      }
  }
  ```

---

## Step 4 — Product-quality fixes, cheap wins before device testing

Not crash-level bugs, but real defects with disproportionately bad
failure modes for what this app is actually for. All cheap (minutes each)
relative to their impact — do these in the same sitting as Step 3 since
you'll already be in `DownloadService.kt`/`MediaStorage.kt`.

- [x] **[HIGH] Downloaded files and library entries have no human-readable name.** (Fixed — patch 02)
  `job.id` (a raw UUID) is used as both the temp filename and the
  `DISPLAY_NAME` shown in the app's own Library and in any file manager —
  every file looks like `download_3f9a2e1b-....mp4`. This directly
  undermines the CJM's "is this the right file?" / library-management
  stage, especially with multiple videos queued at once (the realistic
  usage pattern). **Fix, pick one:**
  - Cheapest: after a successful download, run a near-instant second
    yt-dlp invocation with `--print title` (no download) to get the
    title, sanitize it (strip `/`, `:`, etc.), and use it as
    `DISPLAY_NAME` while keeping the UUID-based temp filename internally.
  - Better, and pairs naturally with the next fix: use yt-dlp's own title
    template directly, e.g. `-o "${cacheDir}/%(title).150B [${job.id}].%(ext)s"`
    — the bracketed job id keeps the file uniquely identifiable for the
    "find the output file" step, and gets a real title into the filename
    with no extra yt-dlp invocation. Strip the bracketed id back out
    before setting `DISPLAY_NAME`.

- [x] **[MEDIUM] Keep the "scan cache dir for newest matching file" fallback** (Fixed — patch 02)
  for locating the completed download, rather than assuming an exact
  `tempBaseName.expectedExtension`. The `--merge-output-format mp4` option
  reliably forces `.mp4` when the format selector's primary branch
  (`bv*[height<=1080]+ba`) is used — but if the `/b` fallback branch
  triggers (no separate video+audio streams available for that video),
  `--merge-output-format` may not apply and the real extension could be
  whatever the single pre-muxed stream uses (occasionally `.webm`). Narrower
  risk than originally feared, but still worth the defensive fix — and it
  naturally combines with the filename fix above if you search for
  `*[${job.id}]*` instead of an exact name match.

- [x] **[MEDIUM] Fix the `RELATIVE_PATH` trailing-slash mismatch** (Fixed — patch 02) between
  `MediaStorage.publish()` (no trailing slash on insert) and
  `MediaStorage.listPublished()` (trailing slash in the query selection).
  `MediaStore` conventionally stores `RELATIVE_PATH` with a trailing slash
  and *usually* normalizes a missing one on insert — but relying on that
  normalization behaving identically across OEM `MediaStore`
  implementations is exactly the kind of assumption this project has
  already been burned by. If it doesn't normalize on some device, the
  exact-match query silently returns zero rows and the Library list
  appears permanently empty even though downloads succeeded. **Fix**: use
  the identical string, with trailing slash, in both places:
  ```kotlin
  put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
  ```

- [x] **[MEDIUM] Make `DownloadQueueBus` updates atomic.** (Fixed — patch 02) `upsert()` and
  `update()` both do a plain read-`_jobs.value`-then-write, not atomic —
  `upsert()` runs from the main/binder thread (enqueue), `update()` runs
  from the worker thread (progress ticks), and these genuinely race. Worst
  case is a dropped progress tick or a briefly-missing row, not a crash,
  but the fix is trivial:
  ```kotlin
  fun upsert(status: DownloadJobStatus) {
      _jobs.update { current -> current.filterNot { it.id == status.id } + status }
  }
  fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
      _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }
  ```

- [x] **[MEDIUM] Sanitize the user-editable Downloads subfolder name** (Fixed — patch 02)
  in `Settings.kt` before it flows into `MediaStore.RELATIVE_PATH` for
  both insert and query. Reject or strip path separators (`/`, `\`) and
  `..` segments — worst case today is a silently-failed publish or files
  landing somewhere unexpected.

- [x] **[MEDIUM] Host-validate shared/pasted URLs** in `MainActivity.kt`. (Fixed — patch 02)
  The current regex (`https?://\S+`) accepts any http(s) URL, not just
  YouTube — yt-dlp will happily attempt any of the 1000+ sites it
  supports. This doesn't violate the "no custom extractor" rule (still
  100% via yt-dlp), but it's scope creep against the app's stated single
  purpose. Restrict to `youtube.com`/`youtu.be` hosts before enqueueing,
  both as a UX guardrail and to keep the app doing exactly one thing.

- [x] **[LOW] Guard `startActivity(ACTION_VIEW)`** (Fixed — patch 02) in `MainActivity.kt`'s
  `playItem()` with a try/catch around the call, showing a toast/snackbar
  on `ActivityNotFoundException` instead of crashing. Very unlikely on a
  real phone with any video player installed, but cheap insurance.

- [x] **[LOW, cleanup] Remove the dead `else` branch** (Fixed — patch 02) in
  `DownloadService.startForegroundWithNotification()` — the
  no-type `startForeground()` fallback is unreachable since
  `minSdk = 29` already satisfies the `>= Build.VERSION_CODES.Q` check
  guarding the typed branch. Harmless, just simplify.

**Confirmed fine, no action needed:** foreground service type declaration
+ runtime `startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` call
match exactly · `POST_NOTIFICATIONS` is both declared in the manifest and
requested at runtime in `MainActivity.onCreate()`, correctly non-fatal if
denied · no `<queries>` manifest entry is needed — `playItem()` calls
`startActivity()` directly with no `resolveActivity()` precheck, so the
API 30+ package-visibility restriction never applies here.

---

## Step 5 — Install and run on a real device

No emulator exists in this environment — this step is manual, on your own
phone. Beyond Fable's original test sequence, a few additions below
specifically target the bugs found in Step 3.

**Build environment (patches 07-14):** the environment needed five fixes before the first compile could even be attempted — a `pipefail` bug in `setup.sh` (07), two re-run/idempotency bugs in `setup.sh` (10), and a JDK/Gradle mismatch where this Codespace's actual default JDK (25.0.2) is too new for Gradle 8.10.2 (ceiling: Java 23, per Gradle's own 8.10 release notes), fixed by pinning Gradle to an already-installed JDK 21 instead (11-12). Two real compile errors followed: an invalid `--` inside an XML comment (13), and `UpdateChannel` actually being a nested class of `YoutubeDL` rather than top-level, i.e. Appendix #11 (14). Full story in `HANDOFF.md`'s patch history and "Key learnings" — kept brief here since it's now resolved history, not an open risk. **`./gradlew assembleDebug` succeeds.**

- [ ] Install the debug APK (`adb install`, or transfer + tap).
- [ ] Grant any runtime permissions prompted (notifications, etc.).
- [ ] Share a real YouTube link into the app via the Android share sheet;
      separately, paste one directly.
- [ ] Queue a short video at a low quality preset first (fastest full
      round-trip).
- [ ] Confirm: it downloads, appears in Library **with a real title**
      (not a UUID, if Step 4's filename fix is in), plays via the system
      player, and is visible in a file manager under
      `Downloads/<subfolder>`.
- [ ] Background the app mid-download; confirm the notification persists
      and the download completes.
- [ ] **Specifically stress-test the Step 3 race condition**: queue 3–4
      videos in quick succession (within a couple seconds of each other,
      simulating the realistic "prepping for a trip" pattern from the
      CJM) and confirm every single one actually starts and completes —
      this is the exact scenario that used to be able to strand a job
      silently at "Queued."
- [ ] Try one deliberately broken case (an age-restricted or private
      video) to see the actual error text yt-dlp returns, and confirm
      `friendlyError()`'s string-matching against real current yt-dlp
      output (e.g. "Sign in to confirm you're not a bot" for
      bot-detection) still works — this was flagged as "partially
      confirmed, partially outdated" and genuinely needs a real device to
      resolve, no amount of code reading settles it.
- [ ] Try airplane mode / no connectivity at queue time, confirm the app
      degrades gracefully rather than crashing (this is also where the
      Step 3 catch-all fix should prevent any exception type from taking
      down the whole app, so this doubles as a regression check for that
      fix).
- [ ] Confirm the app doesn't crash on a very large/slow download; if it
      ever does get killed mid-transfer on Android 14+ specifically, note
      that `dataSync`-type foreground services have a rolling execution
      time budget (hours/day, not indefinite) — informational only,
      unlikely to matter for typical video lengths, but worth knowing if
      it ever happens.

Do not move to Step 9 (signed release) until every item above passes.

---

## Step 6 — Design system v2

Building on `design.md`/`Theme.kt`'s existing foundation (same nature:
warm palette, single accent, soft geometry) but tightened into an actual
system — a real dark theme, semantic tokens for states the original
palette didn't cover, and named component patterns per screen instead of
colors/shapes applied ad hoc. **No "Claude"/Anthropic name, logo, or
licensed fonts anywhere — this constraint is unchanged and non-negotiable.**
This step can happen in parallel with Steps 1–5 if you want, but should be
merged before Step 7.

### 6.1 Color tokens — light (additions to the existing palette)

- [x] Add `surfaceRaised` — white surface + soft shadow (`alpha 0.04`
      black), for modal sheets/dialogs, distinct from flat row cards.
- [x] Add `warning` (`#B8862E`) — new middle state between success/error,
      for "Paused"/"Retrying" job status (currently missing).
- [x] Add `errorContainer` (`#F9DEDC`) — for error banners and empty-state
      backgrounds, distinct from the existing per-row `error` color.

### 6.2 Color tokens — dark (new, currently missing entirely)

- [x] Implement a `darkColorScheme(...)` alongside the existing
      `lightColorScheme(...)`, selected via `isSystemInDarkTheme()` in
      `Theme.kt` (standard Material3 pattern, no new architectural risk):

  | Token | Hex | Notes |
  |---|---|---|
  | `background` | `#1B1A17` | Warm near-black, keeps the "warm" principle in dark mode |
  | `surface` | `#252420` | |
  | `surfaceVariant` | `#302E28` | |
  | `onBackground`/`onSurface` | `#F5F3EC` | |
  | `onSurfaceVariant` | `#B8B6AC` | |
  | `outline` | `#3D3B34` | |
  | `accent` | `#E08D6D` | Lightened terracotta — pure `#D97757` loses contrast on dark |
  | `onAccent` | `#3D1A0E` | Dark text on the lightened accent reads better than white |
  | `accentContainer` | `#5C3423` | |
  | `onAccentContainer` | `#F3DDD2` | |
  | `success` | `#9AB07C` | |
  | `warning` | `#D6A24E` | |
  | `error` | `#FFB4AB` | Material3-standard dark-scheme error red |
  | `errorContainer` | `#5C0F0C` | |

### 6.3 Typography — tighten the existing scale

Keep the Inter (body) + Lora (headline) pairing already implemented
(low-risk per the review). Make sure every role below is actually defined,
not just the ones currently in use:

- [x] `displaySmall` — Lora SemiBold — app title, true empty/first-run state only (Fixed — patch 03)
- [x] `headlineSmall` — Lora SemiBold — screen-level headers, for if more screens are added (Fixed — patch 03)
- [x] `titleMedium` — Inter SemiBold — section headers ("Queue", "Library") (Fixed — patch 03)
- [x] `titleSmall` — Inter Medium — row titles (video name) (Fixed — patch 03)
- [x] `bodyMedium` — Inter Regular — status lines, settings descriptions (Fixed — patch 03)
- [x] `labelLarge` — Inter Medium — button text (Fixed — patch 03)
- [x] `labelSmall` — Inter Medium — chips, timestamps, byte counts (Fixed — patch 03)

### 6.4 Shape scale

No changes needed — already coherent: `extraSmall` 6dp (badges),
`small` 10dp (text fields/chips), `medium` 14dp (row cards), `large` 20dp
(composer bar/dialogs), `extraLarge` 28dp (pill buttons).

### 6.5 Component patterns to implement per screen

- [x] **Download queue (active)** (Fixed — patch 04) — `medium`-shape surface on
      `surfaceVariant`, solid `accentContainer` thumbnail placeholder with
      a play-glyph (no network thumbnail fetch, keeps cost/scope at zero),
      status line colored per state (`onSurfaceVariant` queued, `accent`
      downloading, `success` done, `warning` retrying, `error` failed),
      linear progress bar in `accent` only while actively downloading.
- [x] **Download queue (empty)** (Fixed — patch 04) — centered `accentContainer` circle
      behind a download-arrow icon, no button (the composer bar below is
      already the call to action — don't duplicate it).
- [x] **Library** (Fixed — patch 04) — same row pattern as queue, filled icon-only Play
      button in `accent`, secondary overflow icon (⋮) for
      delete/share-file actions (this is also the fix for the CJM's
      "no in-app delete" gap, see Backlog).
- [x] **Settings** (Fixed — patch 04) — group into labeled sections (`titleMedium` headers,
      `outline`-divided rows) instead of a flat list: Default Quality,
      Storage (subfolder name), Extractor (yt-dlp version + manual update
      button + last-updated timestamp).
- [x] **Error states** (Fixed — patch 04) — inline per-row errors keep `error`/`errorContainer`
      as today; add a new dismissible banner pattern
      (`errorContainer` background, `error` text) specifically for
      connectivity-loss-at-queue-time, since that's a systemic state that
      deserves different visual treatment than a single video's failure.
- [x] **Composer bar** (Fixed — patch 04) — keep the existing `large`-shape pill with `accent`
      send button; add a subtle `outline`-colored focus border (currently
      likely relies on fill alone for affordance) and an inline greyed
      placeholder hint ("Paste a YouTube link").
- [x] **App icon** (N/A — no icon concept change needed; see Step 6.6 for the safe-zone fix) — no change needed to the concept (terracotta
      background, cream download-glyph, no external assets/licensing
      risk) — but see the safe-zone fix below.

### 6.6 Adaptive icon fix

- [x] **[LOW, fixed — patch 03] Fix `ic_launcher_foreground.xml`'s tray shape clipping
  outside the adaptive-icon safe zone.** The tray/base rectangle's bottom
  corners (`34,87` / `74,87`) sit ~38.6dp from center — outside the
  guaranteed-visible 33dp-radius safe circle. On circular-mask
  launchers/OEM skins, the bottom corners will be silently clipped,
  making the tray look shortened/asymmetric (fine on squircle/rounded-
  square masks, which is why this is easy to miss). **Fix**: narrow the
  tray's x-range at the bottom, e.g. `40,79 → 68,79 → 68,87 → 40,87`
  instead of `34...74`. Purely cosmetic, fix whenever you're already
  looking at the icon on a real device.

### 6.7 Optional, not required for v2

- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) — cosmetic only, currently the
      icon just won't participate in themed-icon tinting.

---

## Step 7 — Branding: rename to Kinescope ✅ done (patch 06)

- [x] **[Do this now, not later]** `namespace`/`applicationId`/
      `rootProject.name` in `app/build.gradle.kts` and
      `settings.gradle.kts` renamed to `com.kinescope.app` / `kinescope`.
      Done before Step 5's first device install, while `applicationId`
      was still a safe, reversible change. (Fixed — patch 06.) The
      Kotlin package directory moved to match
      (`app/src/main/java/com/kinescope/app/`, was
      `com/baltic/ytoffline/`), and every file's `package` declaration
      updated accordingly.
- [x] Update `app_name` in `strings.xml` from `"YT Offline"` to
      `"Kinescope"`. (Fixed — patch 06.) Also updated two other
      user-visible strings left over from the old name so the rebrand
      doesn't look half-done on screen: the foreground-service
      notification title (`DownloadService.kt`) and the `TopAppBar`
      title (`MainActivity.kt`). The internal `ACTION_ENQUEUE` intent-
      action string was also updated to match the new package for
      hygiene, though it's self-referential and wasn't a functional
      requirement.
- [x] No icon/color changes are required for a name-only rebrand — the
      existing terracotta/cream identity carries over fine, confirmed,
      no action taken. (Kotlin *identifiers* like `YtOfflineTheme`,
      `YtOfflineApp`, `YtOfflineExtras` were deliberately left
      unchanged — internal-only, not user-visible, never part of this
      Step's scope; revisit only if it becomes annoying to read.)

---

## Step 8 — Documentation

- [ ] Replace `README.md` with the full rewritten version already drafted
      during the review (covers: what it is, who it's for, build
      instructions, current unbuilt status, documentation map, and
      explicit limitations — personal use only, no custom extraction, no
      required paid services). Paste it in as-is; it's ready to commit.
- [ ] Add a `CJM.md` (or fold into `design.md`) capturing the Customer
      Journey Map produced during the review — five stages (prep at home
      → queue & download → departure/loses access → watch offline in-
      region → return & refresh library), with the explicit finding that
      the single highest-risk moment is the silent-failure window at
      home the night before a trip. This is the "why" behind Steps 3–4's
      priority ordering and is worth keeping as a living reference, not
      just a one-time review artifact.
- [ ] Keep this `ROADMAP.md` itself as the living source of truth for
      "what's actually been verified vs. still assumed" — update the
      checkboxes above as each item is actually done, don't let it drift
      back into "written but unverified" the way the original 7 phases did.

---

## Step 9 — Signed release

Only once **every item in Steps 1–5 is done and confirmed on a real
device**. Follow `RELEASE.md` in full — do not skip ahead to save time; an
unverified debug build signed into a release build is still unverified.

---

## Backlog — optional, unscheduled, not required

Everything below is opt-in and user-prioritized, explicitly **not** a
commitment or a new numbered phase:

- [ ] Persist download queue state (small local DB or file) so a process
      kill doesn't silently lose in-flight job status with zero UI
      indication — currently accepted debt (`DownloadQueueBus` is a bare
      in-memory `StateFlow`), reasonable for v1 but worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case the
      process was OOM-killed mid-download by an aggressive OEM battery
      manager (Xiaomi/Huawei/Samsung-class skins do this even to
      foreground services) — scan for leftover temp files from a
      previous run on service start, resume or clean them up.
- [x] In-app delete for library entries (vs. relying on an external file
      manager) — done as of patch 04: the Step 6.5 overflow menu's
      Delete action calls `MediaStorage.delete()`
      (`ContentResolver.delete()` on the app's own `MediaStore` row),
      not just the UI affordance.
- [ ] Migrate `collectAsState()` to `collectAsStateWithLifecycle()` in
      `MainActivity.kt` — fine as-is for a single-screen app, revisit only
      if a second screen (e.g. a dedicated Library screen) is added.
- [ ] Migrate remaining raw `Thread`/`Handler(Looper.getMainLooper())`
      usage (`YtOfflineApp.kt`, `MainActivity.kt`'s `runUpdate()`) to
      `rememberCoroutineScope()` + `withContext(Dispatchers.IO)`, for
      consistency with the coroutines-based fix already applied to
      `DownloadService` in Step 3.
- [ ] Externalize remaining hardcoded UI strings ("Queue", "Library",
      `friendlyError()` messages, Settings labels) into `strings.xml` —
      zero functional impact for a personal single-language app, purely a
      "nice to have if you're already touching that code."
- [ ] Batch-queue a full playlist by URL, if that becomes a real use case.
- [ ] Self-hosted backend for cross-device queue sync — explicitly
      optional per `CLAUDE.md`'s zero-required-cost rule, never a
      requirement.
- [ ] Note for future Codespace rebuilds: `.devcontainer/setup.sh` scrapes
      the Android cmdline-tools download URL from a live webpage rather
      than a pinned version, which fails safely (loud error with
      instructions) but means a rebuilt Codespace could silently pick up
      a newer cmdline-tools version than your first successful build did.
      If a *rebuilt* Codespace ever behaves differently than the original
      for no apparent code reason, check this script's output first.

---

## Process note for future sessions

The original build-verify-after-every-phase discipline in `CLAUDE.md` was
intentionally overridden by explicit user instruction during initial
development ("keep going, we'll test everything at the end"). That was a
valid call for a solo prototyping burst, but it's also *exactly* why this
document exists — nearly every Critical/High finding above is a direct
consequence of code that was never compiled, let alone run. Going forward,
once Step 2 succeeds for the first time: **prefer compiling after each
meaningful change**, not just at the end of a long unattended session.

---

## Appendix — Full findings traceability (all 4 review parts)

Every finding from the review, in one place, so nothing gets lost even if
the sections above get edited over time.

| # | Priority | Finding | Location | Status |
|---|---|---|---|---|
| 1 | Critical | Compose BOM version inconsistent with rest of toolchain | `app/build.gradle.kts` | ✅ Fixed — patch 01 |
| 2 | Critical | Race condition: job can be silently stranded at "Queued" | `DownloadService.kt` | ✅ Fixed — patch 01 |
| 3 | Critical | Unhandled exception types crash the whole app process | `DownloadService.kt` | ✅ Fixed — patch 01 |
| 4 | High | `execute()` progress callback possibly wrong lambda arity | `DownloadService.kt` | ✅ Fixed — patch 01 (confirmed 3-param) |
| 5 | High | Downloaded files/library entries have raw-UUID names | `DownloadService.kt`, `MediaStorage.kt` | ✅ Fixed — patch 02 |
| 6 | Medium | Output filename/extension assumption (narrower risk, `/b` fallback branch) | `QualityPresets.kt`, `DownloadService.kt` | ✅ Fixed — patch 02 |
| 7 | Medium | `RELATIVE_PATH` trailing-slash mismatch, insert vs. query | `MediaStorage.kt` | ✅ Fixed — patch 02 |
| 8 | Medium | `DownloadQueueBus` read-modify-write not atomic | `DownloadQueueBus.kt` | ✅ Fixed — patch 02 |
| 9 | Medium | No subfolder name sanitization | `Settings.kt` | ✅ Fixed — patch 02 |
| 10 | Medium | No host validation on shared/pasted URLs | `MainActivity.kt` | ✅ Fixed — patch 02 |
| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple files | ✅ Confirmed via real compile — patch 14. One mistake found and fixed: `UpdateChannel` is a nested class of `YoutubeDL`, not top-level (patch 07's README-based pre-verification missed this); everything else patch 07 checked was correct, re-confirmed against the actual tagged 0.18.1 source |
| 12 | Low | `updateYoutubeDL()` return type assumption | `YtDlpUpdater.kt` | ✅ Fixed — patch 01 (UpdateChannel arg added) |
| 13 | Low | Unguarded `startActivity(ACTION_VIEW)` | `MainActivity.kt` | ✅ Fixed — patch 02 |
| 14 | Low | Dead `requestLegacyExternalStorage="true"` flag | `AndroidManifest.xml` | ✅ Fixed — patch 01 |
| 15 | Low | Adaptive icon tray clips outside safe zone on circular masks | `ic_launcher_foreground.xml` | ✅ Fixed — patch 03 |
| 16 | Low | Inconsistent Thread/Handler vs. coroutines style | `YtOfflineApp.kt`, `MainActivity.kt` | Open — Step 3 (partial), Backlog (rest) |
| 17 | Low | Only `app_name` externalized to `strings.xml` | `strings.xml` | Backlog |
| 18 | Low | Dead unreachable `else` branch in foreground-service start | `DownloadService.kt` | ✅ Fixed — patch 02 |
| 19 | Info | No monochrome adaptive-icon layer (Android 13+ themed icons) | resources | Backlog |
| 20 | Info | `dataSync` foreground service execution time budget on API 34+ | `DownloadService.kt` | Informational only |
| 21 | Info | `applicationId`/`namespace`/`rootProject.name` still say `ytoffline` | build files | ✅ Fixed — patch 06 |
| 22 | — | `sdkmanager` package identifiers | `.devcontainer/setup.sh` | ✅ Confirmed correct |
| 23 | — | `extractNativeLibs="true"` | `AndroidManifest.xml` | ✅ Confirmed correct, keep |
| 24 | — | AGP/Gradle/Kotlin toolchain compatibility | `app/build.gradle.kts` | ✅ Confirmed compatible |
| 25 | — | `isMinifyEnabled = false` on release | `app/build.gradle.kts` | ✅ Confirmed reasonable |
| 26 | — | Foreground service type declaration + runtime call | manifest + `DownloadService.kt` | ✅ Confirmed correct |
| 27 | — | `POST_NOTIFICATIONS` declared + runtime request | manifest + `MainActivity.kt` | ✅ Confirmed correct |
| 28 | — | `<queries>` manifest requirement | N/A | ✅ Confirmed not needed |
| 29 | — | `addOption` overload usage | `QualityPresets.kt` | ✅ Confirmed correct |
| 30 | — | `font_certs.xml` / `Theme.kt` cross-reference | resources | ✅ Confirmed correct |
| 31 | — | `versionCode` hardcoding | `app/build.gradle.kts` | ✅ Confirmed resolved (Phase 7) |
| 32 | — | English-only / no custom extractor / zero required cost / sideload-only | whole codebase | ✅ Confirmed `CLAUDE.md`-compliant |
| 33 | — | `friendlyError()` string matching against real yt-dlp output | `DownloadService.kt` | Needs device verification — Step 5 |
| 34 | — | `.devcontainer/setup.sh`: `pipefail` + `yes \| sdkmanager --licenses` silently aborts setup before the Gradle wrapper is generated | `.devcontainer/setup.sh` | ✅ Fixed — patch 07 (found during Step 5 environment prep, not part of the original 4-part review) |
| 35 | — | Invalid XML comment (`--` inside a comment body) in `ic_launcher_foreground.xml` broke `mergeDebugResources` -- the first real compile error hit after the JDK/Gradle environment was fixed | `ic_launcher_foreground.xml` | ✅ Fixed — patch 13 (found via the first successful `./gradlew assembleDebug` attempt) |
"""

NEW_ROADMAP_MD = r"""# Roadmap

**Status as of this update (after patch 16):** Steps 1-4, Step 6
(6.1-6.6; 6.7 is optional and still skipped), Step 7 (Kinescope
rename), and the full build-environment/compile-error fix chain
(patches 07-14) are done. **`./gradlew assembleDebug` succeeds** — the
first successful build in this project's history. Nothing has
touched a real device yet; that's Step 5, next — now also buildable
via `.github/workflows/build-debug.yml` (manual `workflow_dispatch`,
hand-assigned version) as an alternative to a local Codespace build. A
full deep code review of the entire codebase was performed by Claude
Fable 5.1 in 4 passes; this document consolidated every finding from
that review into one ordered implementation plan. **As of patch 16,
completed Step sections below are collapsed to a one-line pointer
instead of repeating their full original checklist — see
`CHANGELOG.md` for what each patch actually did, and `HANDOFF.md` for
the patch-by-patch narrative.**

**Decided:** the Kinescope rename (Step 7) uses `applicationId` /
`namespace` **`com.kinescope.app`**.

**Execution order from here — this deliberately does NOT match the Step
numbers below**, because Step 7 must happen before Step 5's first device
install (changing `applicationId` after that is effectively irreversible —
Android treats it as a different app), and Step 6 needed to be finished
first since Step 7 touches many of the same files:

1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)
2. ~~Step 7 — Kinescope rename~~ ✅ done (patch 06)
3. **Step 5 — First device install + testing** ← next
4. Step 8 — Documentation
5. Step 9 — Signed release

**After Step 9 is done, and only then:** this repo also has a `roadmap.md` (lowercase) — a separate, newer sprint-based audit/plan (S0-S11, findings F01-F42) from GPT Astra, added by the user and not yet started. Do not merge it into this document or start it early — finish everything above (through Step 9) first. Once both this `ROADMAP.md` and `roadmap.md` are fully executed, both files get deleted.

Steps 1-4 (compile blockers, then critical/product-quality fixes) are done
and came first, as they had to — nothing else matters until the app
actually compiles.

**There is no Phase 8.** The sections below are **verification and fix
steps**, not new numbered feature phases — this document extends the
`Step 1-5` verification plan Fable proposed, it doesn't replace it with new
"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.

**How to use this document:** the numbered Step sections below keep their
original order (matching the initial review) for reference — follow the
**execution order above**, not the numbering, for what to actually do
next. Completed Steps are collapsed to a pointer at `CHANGELOG.md`
rather than repeating their checklist. The Appendix at the end now only
lists findings that are still open or informational — closed findings
moved to `CHANGELOG.md` too.

---

## Step 1 — Fix known compile-time blockers, before first sync

✅ **Done.** Compose BOM version fixed, dead `requestLegacyExternalStorage`
removed. See `CHANGELOG.md`'s Patch 01 entry for detail. (The optional,
never-done `ndk.abiFilters` trim moved to the Backlog section below —
it was never blocking anything.)

---

## Step 2 — First headless compile

✅ **Done.** `./gradlew assembleDebug` succeeds (first achieved after
patch 14). Progress-callback arity confirmed 3-parameter,
`updateYoutubeDL()`'s `UpdateChannel` argument added, import paths
confirmed against the library's real tagged source. See `CHANGELOG.md`'s
Patch 01, 07, and 14 entries.

---

## Step 3 — Critical runtime fixes, before first device install

✅ **Done.** The `DownloadService.ensureWorkerRunning()` race condition
(jobs silently stranded at "Queued") fixed with a blocking consumer loop
and lock-guarded state transitions; a catch-all exception handler added
so one bad download can't crash the whole app. See `CHANGELOG.md`'s
Patch 01 entry — and the doc comment above `startWorkerLocked()` in
`DownloadService.kt` for why this fix is tighter than the sample fix
originally sketched here.

---

## Step 4 — Product-quality fixes, cheap wins before device testing

✅ **Done.** Filenames humanized via yt-dlp's own title template,
job-id-tag output-file scanning, the `RELATIVE_PATH` trailing-slash
mismatch fixed, `DownloadQueueBus` updates made atomic, subfolder-name
sanitization, YouTube-host validation on shared/pasted URLs, an
`ActivityNotFoundException` guard on video playback, and a dead branch
removed. See `CHANGELOG.md`'s Patch 02 entry.

---

## Step 5 — Install and run on a real device

No emulator exists in this environment — this step is manual, on your own
phone. Beyond Fable's original test sequence, a few additions below
specifically target the bugs found in Step 3.

**Build environment (patches 07-14):** the environment needed five fixes before the first compile could even be attempted — a `pipefail` bug in `setup.sh` (07), two re-run/idempotency bugs in `setup.sh` (10), and a JDK/Gradle mismatch where this Codespace's actual default JDK (25.0.2) is too new for Gradle 8.10.2 (ceiling: Java 23, per Gradle's own 8.10 release notes), fixed by pinning Gradle to an already-installed JDK 21 instead (11-12). Two real compile errors followed: an invalid `--` inside an XML comment (13), and `UpdateChannel` actually being a nested class of `YoutubeDL` rather than top-level, i.e. Appendix #11 (14). Full story in `HANDOFF.md`'s patch history and "Key learnings" — kept brief here since it's now resolved history, not an open risk. **`./gradlew assembleDebug` succeeds.**

- [ ] Install the debug APK (`adb install`, or transfer + tap).
- [ ] Grant any runtime permissions prompted (notifications, etc.).
- [ ] Share a real YouTube link into the app via the Android share sheet;
      separately, paste one directly.
- [ ] Queue a short video at a low quality preset first (fastest full
      round-trip).
- [ ] Confirm: it downloads, appears in Library **with a real title**
      (not a UUID, if Step 4's filename fix is in), plays via the system
      player, and is visible in a file manager under
      `Downloads/<subfolder>`.
- [ ] Background the app mid-download; confirm the notification persists
      and the download completes.
- [ ] **Specifically stress-test the Step 3 race condition**: queue 3–4
      videos in quick succession (within a couple seconds of each other,
      simulating the realistic "prepping for a trip" pattern from the
      CJM) and confirm every single one actually starts and completes —
      this is the exact scenario that used to be able to strand a job
      silently at "Queued."
- [ ] Try one deliberately broken case (an age-restricted or private
      video) to see the actual error text yt-dlp returns, and confirm
      `friendlyError()`'s string-matching against real current yt-dlp
      output (e.g. "Sign in to confirm you're not a bot" for
      bot-detection) still works — this was flagged as "partially
      confirmed, partially outdated" and genuinely needs a real device to
      resolve, no amount of code reading settles it.
- [ ] Try airplane mode / no connectivity at queue time, confirm the app
      degrades gracefully rather than crashing (this is also where the
      Step 3 catch-all fix should prevent any exception type from taking
      down the whole app, so this doubles as a regression check for that
      fix).
- [ ] Confirm the app doesn't crash on a very large/slow download; if it
      ever does get killed mid-transfer on Android 14+ specifically, note
      that `dataSync`-type foreground services have a rolling execution
      time budget (hours/day, not indefinite) — informational only,
      unlikely to matter for typical video lengths, but worth knowing if
      it ever happens.

Do not move to Step 9 (signed release) until every item above passes.

---

## Step 6 — Design system v2

✅ **Done** (6.1-6.6: light/dark color tokens, a completed typography
scale, per-screen component patterns, adaptive-icon safe-zone fix). **No
"Claude"/Anthropic name, logo, or licensed fonts anywhere — this
constraint is unchanged and non-negotiable**, and nothing in patches
03-04 violated it. See `CHANGELOG.md`'s Patch 03 and 04 entries for
detail. 6.7 below is the one item still open.

### 6.7 Optional, not required for v2

- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) — cosmetic only, currently the
      icon just won't participate in themed-icon tinting.

---

## Step 7 — Branding: rename to Kinescope

✅ **Done.** `namespace`/`applicationId`/`rootProject.name` →
`com.kinescope.app` / `kinescope`, done before Step 5's first device
install while `applicationId` was still a safe, reversible change; every
user-visible "YT Offline" string → "Kinescope". Internal-only Kotlin
identifiers (`YtOfflineTheme`, `YtOfflineApp`, etc.) deliberately left
unchanged — not user-visible, not in scope. See `CHANGELOG.md`'s Patch
06 entry.

---

## Step 8 — Documentation

- [x] Replace `README.md` with a real one (Fixed — patch 15; written
      fresh rather than pasting an old drafted version, which could no
      longer be located in the repo by that session — covers what it
      is, who it's for, build instructions, status, documentation map,
      and explicit limitations). This checkbox itself was accidentally
      left unflipped until patch 16 caught it.
- [ ] Add a `CJM.md` (or fold into `design.md`) capturing the Customer
      Journey Map produced during the review — five stages (prep at home
      → queue & download → departure/loses access → watch offline in-
      region → return & refresh library), with the explicit finding that
      the single highest-risk moment is the silent-failure window at
      home the night before a trip. This is the "why" behind Steps 3–4's
      priority ordering and is worth keeping as a living reference, not
      just a one-time review artifact.
- [ ] Keep this `ROADMAP.md` itself as the living source of truth for
      "what's actually been verified vs. still assumed" — update the
      checkboxes above as each item is actually done, don't let it drift
      back into "written but unverified" the way the original 7 phases did.

---

## Step 9 — Signed release

Only once **every item in Steps 1–5 is done and confirmed on a real
device**. Follow `RELEASE.md` in full — do not skip ahead to save time; an
unverified debug build signed into a release build is still unverified.

---

## Backlog — optional, unscheduled, not required

Everything below is opt-in and user-prioritized, explicitly **not** a
commitment or a new numbered phase:

- [ ] Trim `x86`/`x86_64` from `ndk.abiFilters` in `app/build.gradle.kts`
      if the target phone is arm64 (the overwhelming majority are) and
      emulator support isn't needed — shrinks the APK, since
      `youtubedl-android`'s bundled native binaries dominate its size.
      Originally Step 1's one optional, non-blocking item; moved here in
      patch 16 since it was never actually blocking anything.
- [ ] Persist download queue state (small local DB or file) so a process
      kill doesn't silently lose in-flight job status with zero UI
      indication — currently accepted debt (`DownloadQueueBus` is a bare
      in-memory `StateFlow`), reasonable for v1 but worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case the
      process was OOM-killed mid-download by an aggressive OEM battery
      manager (Xiaomi/Huawei/Samsung-class skins do this even to
      foreground services) — scan for leftover temp files from a
      previous run on service start, resume or clean them up.
- [x] In-app delete for library entries (vs. relying on an external file
      manager) — done as of patch 04: the Step 6.5 overflow menu's
      Delete action calls `MediaStorage.delete()`
      (`ContentResolver.delete()` on the app's own `MediaStore` row),
      not just the UI affordance.
- [ ] Migrate `collectAsState()` to `collectAsStateWithLifecycle()` in
      `MainActivity.kt` — fine as-is for a single-screen app, revisit only
      if a second screen (e.g. a dedicated Library screen) is added.
- [ ] Migrate remaining raw `Thread`/`Handler(Looper.getMainLooper())`
      usage (`YtOfflineApp.kt`, `MainActivity.kt`'s `runUpdate()`) to
      `rememberCoroutineScope()` + `withContext(Dispatchers.IO)`, for
      consistency with the coroutines-based fix already applied to
      `DownloadService` in Step 3.
- [ ] Externalize remaining hardcoded UI strings ("Queue", "Library",
      `friendlyError()` messages, Settings labels) into `strings.xml` —
      zero functional impact for a personal single-language app, purely a
      "nice to have if you're already touching that code."
- [ ] Batch-queue a full playlist by URL, if that becomes a real use case.
- [ ] Self-hosted backend for cross-device queue sync — explicitly
      optional per `CLAUDE.md`'s zero-required-cost rule, never a
      requirement.
- [ ] Note for future Codespace rebuilds: `.devcontainer/setup.sh` scrapes
      the Android cmdline-tools download URL from a live webpage rather
      than a pinned version, which fails safely (loud error with
      instructions) but means a rebuilt Codespace could silently pick up
      a newer cmdline-tools version than your first successful build did.
      If a *rebuilt* Codespace ever behaves differently than the original
      for no apparent code reason, check this script's output first.

---

## Process note for future sessions

The original build-verify-after-every-phase discipline in `CLAUDE.md` was
intentionally overridden by explicit user instruction during initial
development ("keep going, we'll test everything at the end"). That was a
valid call for a solo prototyping burst, but it's also *exactly* why this
document exists — nearly every Critical/High finding above is a direct
consequence of code that was never compiled, let alone run. Going forward,
once Step 2 succeeds for the first time: **prefer compiling after each
meaningful change**, not just at the end of a long unattended session.

---

## Appendix — Open findings only

Originally a full traceability table for every finding from the 4-part
review (35 rows). As of patch 16, closed findings (everything that was
✅ Fixed/Confirmed — originally rows 1-15, 18, 21-32, 34-35) have moved
to `CHANGELOG.md`'s per-patch entries, keeping this table to what's
still actually open or informational. Original row numbers preserved
below for cross-reference with `CHANGELOG.md` and old session history.

| # | Priority | Finding | Location | Status |
|---|---|---|---|---|
| 16 | Low | Inconsistent Thread/Handler vs. coroutines style | `YtOfflineApp.kt`, `MainActivity.kt` | Open — Step 3 (partial), Backlog (rest) |
| 17 | Low | Only `app_name` externalized to `strings.xml` | `strings.xml` | Backlog |
| 19 | Info | No monochrome adaptive-icon layer (Android 13+ themed icons) | resources | Backlog (Step 6.7) |
| 20 | Info | `dataSync` foreground service execution time budget on API 34+ | `DownloadService.kt` | Informational only |
| 33 | — | `friendlyError()` string matching against real yt-dlp output | `DownloadService.kt` | Needs device verification — Step 5 |
"""

OLD_HANDOFF_MD = r"""# Handoff Snapshot

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

NEW_HANDOFF_MD = r"""# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also attach a fresh repomix export
(or paste `CLAUDE.md`, `ROADMAP.md`, `CHANGELOG.md`, `roadmap.md`,
`design.md`, and `RELEASE.md` directly).

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
assembleDebug` now succeeds**, both locally and via the new
`.github/workflows/build-debug.yml` GitHub Actions workflow (patch 16).

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
   Getting the APK onto the phone no longer requires adb: the
   `Build Debug APK` GitHub Actions workflow (patch 16) builds and
   uploads it as a downloadable run artifact instead.
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
  plan (read its top section first; as of patch 16 it only carries
  detail for what's still open -- completed Steps point to
  `CHANGELOG.md`). `CHANGELOG.md` -- terse per-patch "what shipped"
  record, newest first (patch 16). `roadmap.md` -- GPT Astra's
  sprint-based plan, queued for after `ROADMAP.md`.
  `.github/workflows/build-debug.yml` -- manual GitHub Actions
  workflow that builds and uploads a versioned debug APK (patch 16).

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7 (default;
overridable via `-PappVersionCode`, see patch 16), `versionName`
"1.0.0" (default; overridable via `-PappVersionName`), Compose BOM
`2024.11.00` (Material3 1.3.1), `youtubedl-android` 0.18.1, Gradle
`8.10.2`. `applicationId`/`namespace`: `com.kinescope.app`. App name:
"Kinescope".

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

Continue at **Step 5 (device install + manual testing)** unless told
otherwise. The compile is done -- `./gradlew assembleDebug` succeeds,
either locally or via the `Build Debug APK` GitHub Actions workflow
(`.github/workflows/build-debug.yml`, patch 16 -- manual trigger, hand
-assigned version, no adb required). `ROADMAP.md`'s Step 5 section has
the full manual test checklist, including explicitly stress-testing the
`DownloadService` race-condition fix from patch 01 (queue several
videos in quick succession). Continue the established pattern for this
project:

- Read the actual current file content before editing -- don't assume
  memory of it is accurate; things have changed across 16 patches.
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
- When a `ROADMAP.md` item is completed, collapse its checklist to a
  one-line pointer in that file and record what actually changed in
  `CHANGELOG.md` instead (process established patch 16) -- do this in
  the same patch as the code change it corresponds to.
- Write all code, code comments, commit messages, and documentation in
  English, regardless of what language the conversation itself is in.
"""

OLD_CLAUDE_MD = r"""# CLAUDE.md — Project Instructions

Read this file first in any new session working on this repo.

## What this project is
A personal Android app that downloads YouTube videos for offline
viewing. Built by Baltic, who travels for work to a region where
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
See `ROADMAP.md` for phase status and `HANDOFF.md` for the latest
session snapshot. **Read `HANDOFF.md` first** if you're starting a
fresh conversation with no prior context on this repo.

## User instruction log
(Append new instructions from the user here, most recent last, so
future sessions don't lose them.)

- Write all code and documentation in English.
- Keep writing phases back-to-back without pausing for a build check
  after each one — the user will build and test everything together
  once, later. Still flag unverified/risky assumptions in code
  comments and ROADMAP.md as they come up; just don't stop and wait.
"""

NEW_CLAUDE_MD = r"""# CLAUDE.md — Project Instructions

Read this file first in any new session working on this repo.

## What this project is
A personal Android app that downloads YouTube videos for offline
viewing. Built by Baltic, who travels for work to a region where
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
See `ROADMAP.md` for phase status and `HANDOFF.md` for the latest
session snapshot. **Read `HANDOFF.md` first** if you're starting a
fresh conversation with no prior context on this repo.

## User instruction log
(Append new instructions from the user here, most recent last, so
future sessions don't lose them.)

- Write all code and documentation in English.
- Keep writing phases back-to-back without pausing for a build check
  after each one — the user will build and test everything together
  once, later. Still flag unverified/risky assumptions in code
  comments and ROADMAP.md as they come up; just don't stop and wait.
- Build the APK via GitHub Actions (`.github/workflows/build-debug.yml`,
  manual `workflow_dispatch` trigger only, version assigned by hand on
  each run) instead of a local Codespace `./gradlew` build + manual
  download — added patch 16.
- Process, starting patch 16: work through `ROADMAP.md` in order. When
  an item is completed, remove its detailed checklist text from
  `ROADMAP.md` (collapse to a one-line pointer) and log what changed in
  `CHANGELOG.md` instead. Deliver a Python patch script per stage, as
  before.
"""

OLD_README_MD = r"""# Kinescope

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

NEW_README_MD = r"""# Kinescope

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

**Alternative: build via GitHub Actions.** If adb isn't available, or
downloading the APK through the Codespace browser UI is inconvenient,
use the *Build Debug APK* workflow under this repo's Actions tab
(`.github/workflows/build-debug.yml`) instead of steps 3-4 above — run
it by hand, supply a version name, and download the resulting APK from
the run's Artifacts. No automatic trigger; each run is a deliberate,
manually-versioned build.

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

OLD_APP_BUILD_GRADLE_KTS = r"""import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Phase 7: release signing config. Reads from keystore.properties,
// which is gitignored and never committed — see RELEASE.md for how
// to create it. If the file doesn't exist (e.g. a fresh clone before
// signing is set up), release builds fall back to unsigned rather
// than failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreConfig = keystorePropertiesFile.exists()
if (hasKeystoreConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.kinescope.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinescope.app"
        // Bumped from 26 to 29 in Phase 3: MediaStore.Downloads (used
        // to publish finished files to the public Downloads folder)
        // doesn't exist before Android 10. Personal app, one device —
        // not worth a legacy fallback path for pre-2019 phones.
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"

        // youtubedl-android bundles native Python/yt-dlp binaries per
        // ABI; without this the APK would try to include every ABI
        // and bloat, or fail to package correctly on some setups.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (hasKeystoreConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: minification/R8 can break
            // reflection-heavy libraries (coroutines, the yt-dlp
            // wrapper) in ways that are painful to debug for a
            // personal app with exactly one user. Not worth it just
            // to shrink the APK.
            isMinifyEnabled = false
            if (hasKeystoreConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // ROADMAP.md Step 1 [CRITICAL, fixed]: 2026.08.00 does not exist --
    // every other pinned version below clusters around Sept-Nov 2024
    // (AGP 8.7.2, Kotlin 2.1.0, activity-compose 1.9.3, core-ktx 1.15.0,
    // kotlinx-coroutines-core 1.9.0). 2024.11.00 is a real BOM release
    // from that same window -- see
    // https://developer.android.com/jetpack/compose/bom/bom-mapping
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Android wrapper around the yt-dlp executable (bundles yt-dlp +
    // a Python runtime). We depend on this instead of writing any
    // extraction logic ourselves — see CLAUDE.md ground rules.
    // https://github.com/yausername/youtubedl-android
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // Phase 4: foreground service + notification + shared queue state.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Design pass: downloadable Google Fonts (Inter/Lora) instead of
    // bundling font files. See Theme.kt and design.md.
    implementation("androidx.compose.ui:ui-text-google-fonts")
}
"""

NEW_APP_BUILD_GRADLE_KTS = r"""import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Phase 7: release signing config. Reads from keystore.properties,
// which is gitignored and never committed — see RELEASE.md for how
// to create it. If the file doesn't exist (e.g. a fresh clone before
// signing is set up), release builds fall back to unsigned rather
// than failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreConfig = keystorePropertiesFile.exists()
if (hasKeystoreConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.kinescope.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinescope.app"
        // Bumped from 26 to 29 in Phase 3: MediaStore.Downloads (used
        // to publish finished files to the public Downloads folder)
        // doesn't exist before Android 10. Personal app, one device —
        // not worth a legacy fallback path for pre-2019 phones.
        minSdk = 29
        targetSdk = 35
        // Patch 16: overridable via `-PappVersionCode=<n> -PappVersionName=<name>`
        // so .github/workflows/build-debug.yml can stamp a manually-chosen
        // version per run without editing this file. Falls back to these
        // hardcoded defaults for local Codespace builds that don't pass
        // the properties (e.g. plain `./gradlew assembleDebug`).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 7
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

        // youtubedl-android bundles native Python/yt-dlp binaries per
        // ABI; without this the APK would try to include every ABI
        // and bloat, or fail to package correctly on some setups.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (hasKeystoreConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: minification/R8 can break
            // reflection-heavy libraries (coroutines, the yt-dlp
            // wrapper) in ways that are painful to debug for a
            // personal app with exactly one user. Not worth it just
            // to shrink the APK.
            isMinifyEnabled = false
            if (hasKeystoreConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // ROADMAP.md Step 1 [CRITICAL, fixed]: 2026.08.00 does not exist --
    // every other pinned version below clusters around Sept-Nov 2024
    // (AGP 8.7.2, Kotlin 2.1.0, activity-compose 1.9.3, core-ktx 1.15.0,
    // kotlinx-coroutines-core 1.9.0). 2024.11.00 is a real BOM release
    // from that same window -- see
    // https://developer.android.com/jetpack/compose/bom/bom-mapping
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Android wrapper around the yt-dlp executable (bundles yt-dlp +
    // a Python runtime). We depend on this instead of writing any
    // extraction logic ourselves — see CLAUDE.md ground rules.
    // https://github.com/yausername/youtubedl-android
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // Phase 4: foreground service + notification + shared queue state.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Design pass: downloadable Google Fonts (Inter/Lora) instead of
    // bundling font files. See Theme.kt and design.md.
    implementation("androidx.compose.ui:ui-text-google-fonts")
}
"""

OLD_PATCH07_FIX_SETUP_AND_VERIFY_IMPORTS_PY = r'''#!/usr/bin/env python3
"""
Patch 07 -- Codespace build-environment fix + pre-build import-path
verification, ahead of ROADMAP.md Step 5 (first device install).

Context: HANDOFF.md's "Immediate next step" and ROADMAP.md's execution
order both point at Step 5 next -- and the first real `./gradlew
assembleDebug` that step requires. Before that can even be attempted, a
fresh Codespace has to actually reach a working state. While preparing
for that, this patch fixes a concrete, previously-undetected bug in
`.devcontainer/setup.sh` that would silently abort the script before it
ever generates the Gradle wrapper -- which plausibly explains a missing
`gradlew` after a fresh Codespace build, independent of anything else.

Bug (found by reading setup.sh, not by guessing): the script sets
`set -euo pipefail` (line 5) and then runs
`yes | sdkmanager --licenses > /dev/null`. Under `pipefail`, a pipeline's
exit status is the *rightmost command that exited non-zero* -- not simply
the last command's exit status. `yes` is always killed by SIGPIPE the
moment `sdkmanager` stops reading stdin (once it has consumed enough "y"
answers), which bash reports as a non-zero exit status for `yes`
(128 + SIGPIPE). Even when `sdkmanager --licenses` itself succeeds (exit
0), `yes`'s SIGPIPE-induced non-zero status is what `pipefail` picks up,
and `set -e` then kills the whole script right there -- before
platform-tools/platforms/build-tools are installed and before
`gradle wrapper` ever runs. This is a well-known, essentially
deterministic gotcha with the `yes | <cmd> --accept-licenses` idiom under
`pipefail`, not a hypothetical edge case, and it matches the new
Sprint-roadmap review's finding F34 (see the "two roadmap files" note
raised alongside this patch).

Fix: temporarily disable `pipefail` around just that one pipeline (so
`$?` reflects `sdkmanager`'s own exit status, per default non-pipefail
bash semantics: last command's exit status), capture that status
explicitly, restore `pipefail`, and fail loudly with the real exit code
only if `sdkmanager` itself actually failed.

Also updates ROADMAP.md:
 - Appendix finding #11 (`youtubedl-android`/`ffmpeg` import paths):
   downgraded from "Verify -- Step 2" to a pre-verified status. While
   preparing this patch, the exact import paths and API shapes currently
   used in DownloadService.kt / YtDlpUpdater.kt / YtOfflineApp.kt
   (package `com.yausername.youtubedl_android` for
   YoutubeDL/YoutubeDLRequest/YoutubeDLException/UpdateChannel, package
   `com.yausername.ffmpeg` for FFmpeg, the 3-parameter `execute()`
   progress callback, and the `updateYoutubeDL(context, UpdateChannel)`
   signature) were checked against the youtubedl-android library's own
   current GitHub sample-app source and README
   (io.github.junkfood02.youtubedl-android:library/ffmpeg:0.18.1 is
   still the current release; the upstream repo is active, not
   archived) and all matched. This is NOT a substitute for an actual
   compile -- still genuinely unverified until `./gradlew assembleDebug`
   runs -- but it meaningfully de-risks the single most-likely compile
   blocker going into Step 5.
 - Adds a new Appendix row (#34) for the pipefail bug this patch fixes.
 - Adds a short note under the Step 5 heading documenting both of the
   above.
 - Bumps the top "Status as of this update" line to "01-07".

Also appends a Patch 07 bullet to HANDOFF.md's patch history, mirroring
the pattern of patches 01-04/06.

Usage:
    python3 patch07_fix_setup_and_verify_imports.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_setup_sh(repo_root: Path):
    path = repo_root / ".devcontainer" / "setup.sh"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_block = (
        'echo "== Accepting SDK licenses =="\n'
        'yes | sdkmanager --licenses > /dev/null\n'
    )
    if old_block not in text:
        if "sdkmanager_license_status" in text:
            print("setup.sh already patched -- skipping.")
            return
        fail(
            "Could not find the expected 'Accepting SDK licenses' block in "
            "setup.sh (anchor text not found). Refusing to guess -- check "
            "whether setup.sh has changed since this patch was written."
        )

    new_block = (
        'echo "== Accepting SDK licenses =="\n'
        "# Patch 07: set -o pipefail (line 5) makes this pipeline report a\n"
        "# *failure* even when sdkmanager itself succeeds, because `yes` is\n"
        "# killed by SIGPIPE (a non-zero exit status) the moment sdkmanager\n"
        "# stops reading stdin -- pipefail picks up that left-hand non-zero\n"
        "# status, and set -e then aborts the whole script right here,\n"
        "# before platform-tools/platforms/build-tools are ever installed\n"
        "# or the Gradle wrapper is ever generated. Disabling pipefail for\n"
        "# just this one pipeline restores default bash behavior ($? = the\n"
        "# *last* command's status, i.e. sdkmanager's real exit code), so a\n"
        "# genuine sdkmanager failure still stops the script but yes's\n"
        "# expected SIGPIPE no longer does.\n"
        "set +o pipefail\n"
        "yes | sdkmanager --licenses > /dev/null\n"
        "sdkmanager_license_status=$?\n"
        "set -o pipefail\n"
        'if [ "$sdkmanager_license_status" -ne 0 ]; then\n'
        '  echo "sdkmanager --licenses failed (exit $sdkmanager_license_status)" >&2\n'
        '  exit "$sdkmanager_license_status"\n'
        "fi\n"
    )

    text = text.replace(old_block, new_block)
    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    # 1. Appendix row #11
    old_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Verify — Step 2 |"
    )
    new_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Pre-verified against library source — patch 07 (still "
        "needs final `./gradlew` confirmation) |"
    )
    if old_row_11 in text:
        text = text.replace(old_row_11, new_row_11)
    elif new_row_11 in text:
        pass  # already patched
    elif "Confirmed via real compile — patch 14" in text:
        # Patch 14 later superseded this row's text entirely (it found
        # and fixed a real bug -- UpdateChannel -- that this patch's
        # source-reading pass missed). That's a strictly newer, more
        # authoritative status for the same row, so there's nothing for
        # patch 07 to do here on a repeated full-chain run.
        pass
    else:
        fail("Appendix row #11 anchor not found in ROADMAP.md")

    # 2. New Appendix row #34
    anchor_row_33 = (
        "| 33 | — | `friendlyError()` string matching against real yt-dlp "
        "output | `DownloadService.kt` | Needs device verification — Step 5 |"
    )
    new_row_34 = (
        "\n| 34 | — | `.devcontainer/setup.sh`: `pipefail` + "
        "`yes \\| sdkmanager --licenses` silently aborts setup before the "
        "Gradle wrapper is generated | `.devcontainer/setup.sh` | ✅ Fixed "
        "— patch 07 (found during Step 5 environment prep, not part of the "
        "original 4-part review) |"
    )
    if "| 34 |" not in text:
        if anchor_row_33 not in text:
            fail("Appendix row #33 anchor not found in ROADMAP.md")
        text = text.replace(anchor_row_33, anchor_row_33 + new_row_34)

    # 3. Note under the Step 5 heading
    step5_anchor = (
        "No emulator exists in this environment — this step is manual, on "
        "your own\nphone. Beyond Fable's original test sequence, a few "
        "additions below\nspecifically target the bugs found in Step 3."
    )
    step5_note = (
        step5_anchor
        + "\n\n**Environment note (patch 07):** `.devcontainer/setup.sh` had a "
        "`pipefail`-related bug that could silently abort setup before "
        "`gradlew` was ever generated (see Appendix #34) — fixed. Appendix "
        "#11 (`youtubedl-android`/`ffmpeg` import paths) has also been "
        "pre-verified against the library's own current source — still "
        "needs final confirmation by an actual `./gradlew assembleDebug` "
        "run, but the single most-likely compile blocker going into this "
        "step is now lower-risk than before."
    )
    # Patch 15 later consolidated this note (and patch 12's) into a
    # single "Build environment (patches 07-14)" paragraph and removed
    # the original text entirely. Without checking for that marker too,
    # a repeated full-chain run would see this note "missing" and
    # re-insert it every time -- which patch 15 would then re-consolidate
    # into yet another duplicate paragraph, growing by one on every pass.
    already_present = (
        "**Environment note (patch 07):**" in text
        or "**Build environment (patches 07-14):**" in text
    )
    if step5_anchor in text and not already_present:
        text = text.replace(step5_anchor, step5_note)
    elif already_present:
        pass
    else:
        fail("Step 5 intro anchor not found in ROADMAP.md")

    # 4. Top status line
    old_status = (
        "**Status as of this update (after patches 01-06):** Steps 1-4, Step 6"
    )
    new_status = (
        "**Status as of this update (after patches 01-07):** Steps 1-4, Step 6"
    )
    if old_status in text:
        text = text.replace(old_status, new_status)
    elif new_status in text:
        pass
    elif "**Status as of this update (after patches 01-" in text:
        # A later, more comprehensive documentation patch (15) already
        # rewrote this status line to reference a higher patch number.
        # That's strictly newer/better information than what this patch
        # would set, so there's nothing to do here -- this is a purely
        # cosmetic status line, not a functional change, so treat any
        # already-updated version of it as satisfying this step.
        print("Top status line already reflects a later patch -- skipping.")
    else:
        fail("Top status line anchor not found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def patch_handoff(repo_root: Path):
    path = repo_root / "HANDOFF.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    if "**Patch 07**" in text:
        print("HANDOFF.md already mentions patch 07 -- skipping.")
        return

    marker = "\n**Version-compatibility note worth remembering:**"
    if marker not in text:
        fail("Version-compatibility note marker not found in HANDOFF.md")

    patch07_bullet = (
        "\n- **Patch 07** — Pre-Step-5 environment fix: "
        "`.devcontainer/setup.sh` had a `pipefail` bug "
        "(`yes | sdkmanager --licenses`, see `ROADMAP.md` Appendix #34) "
        "that could silently abort setup before the Gradle wrapper was "
        "ever generated — fixed by disabling `pipefail` around just that "
        "one pipeline and checking `sdkmanager`'s real exit status "
        "explicitly. Also pre-verified the `youtubedl-android`/`ffmpeg` "
        "import paths and API shapes (`YoutubeDL`/`YoutubeDLRequest`/"
        "`YoutubeDLException`/`UpdateChannel` in "
        "`com.yausername.youtubedl_android`, `FFmpeg` in "
        "`com.yausername.ffmpeg`, the 3-parameter `execute()` progress "
        "callback, `updateYoutubeDL(context, UpdateChannel)`) against the "
        "library's own current GitHub source and README — all matched, "
        "though this still isn't a substitute for the real "
        "`./gradlew assembleDebug` run.\n"
    )

    text = text.replace(marker, patch07_bullet + marker)
    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 07 against: {repo_root}")

    patch_setup_sh(repo_root)
    patch_roadmap(repo_root)
    patch_handoff(repo_root)

    print("\nPatch 07 applied successfully.")


if __name__ == "__main__":
    main()
'''

NEW_PATCH07_FIX_SETUP_AND_VERIFY_IMPORTS_PY = r'''#!/usr/bin/env python3
"""
Patch 07 -- Codespace build-environment fix + pre-build import-path
verification, ahead of ROADMAP.md Step 5 (first device install).

Context: HANDOFF.md's "Immediate next step" and ROADMAP.md's execution
order both point at Step 5 next -- and the first real `./gradlew
assembleDebug` that step requires. Before that can even be attempted, a
fresh Codespace has to actually reach a working state. While preparing
for that, this patch fixes a concrete, previously-undetected bug in
`.devcontainer/setup.sh` that would silently abort the script before it
ever generates the Gradle wrapper -- which plausibly explains a missing
`gradlew` after a fresh Codespace build, independent of anything else.

Bug (found by reading setup.sh, not by guessing): the script sets
`set -euo pipefail` (line 5) and then runs
`yes | sdkmanager --licenses > /dev/null`. Under `pipefail`, a pipeline's
exit status is the *rightmost command that exited non-zero* -- not simply
the last command's exit status. `yes` is always killed by SIGPIPE the
moment `sdkmanager` stops reading stdin (once it has consumed enough "y"
answers), which bash reports as a non-zero exit status for `yes`
(128 + SIGPIPE). Even when `sdkmanager --licenses` itself succeeds (exit
0), `yes`'s SIGPIPE-induced non-zero status is what `pipefail` picks up,
and `set -e` then kills the whole script right there -- before
platform-tools/platforms/build-tools are installed and before
`gradle wrapper` ever runs. This is a well-known, essentially
deterministic gotcha with the `yes | <cmd> --accept-licenses` idiom under
`pipefail`, not a hypothetical edge case, and it matches the new
Sprint-roadmap review's finding F34 (see the "two roadmap files" note
raised alongside this patch).

Fix: temporarily disable `pipefail` around just that one pipeline (so
`$?` reflects `sdkmanager`'s own exit status, per default non-pipefail
bash semantics: last command's exit status), capture that status
explicitly, restore `pipefail`, and fail loudly with the real exit code
only if `sdkmanager` itself actually failed.

Also updates ROADMAP.md:
 - Appendix finding #11 (`youtubedl-android`/`ffmpeg` import paths):
   downgraded from "Verify -- Step 2" to a pre-verified status. While
   preparing this patch, the exact import paths and API shapes currently
   used in DownloadService.kt / YtDlpUpdater.kt / YtOfflineApp.kt
   (package `com.yausername.youtubedl_android` for
   YoutubeDL/YoutubeDLRequest/YoutubeDLException/UpdateChannel, package
   `com.yausername.ffmpeg` for FFmpeg, the 3-parameter `execute()`
   progress callback, and the `updateYoutubeDL(context, UpdateChannel)`
   signature) were checked against the youtubedl-android library's own
   current GitHub sample-app source and README
   (io.github.junkfood02.youtubedl-android:library/ffmpeg:0.18.1 is
   still the current release; the upstream repo is active, not
   archived) and all matched. This is NOT a substitute for an actual
   compile -- still genuinely unverified until `./gradlew assembleDebug`
   runs -- but it meaningfully de-risks the single most-likely compile
   blocker going into Step 5.
 - Adds a new Appendix row (#34) for the pipefail bug this patch fixes.
 - Adds a short note under the Step 5 heading documenting both of the
   above.
 - Bumps the top "Status as of this update" line to "01-07".

Also appends a Patch 07 bullet to HANDOFF.md's patch history, mirroring
the pattern of patches 01-04/06.

Usage:
    python3 patch07_fix_setup_and_verify_imports.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_setup_sh(repo_root: Path):
    path = repo_root / ".devcontainer" / "setup.sh"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_block = (
        'echo "== Accepting SDK licenses =="\n'
        'yes | sdkmanager --licenses > /dev/null\n'
    )
    if old_block not in text:
        if "sdkmanager_license_status" in text:
            print("setup.sh already patched -- skipping.")
            return
        fail(
            "Could not find the expected 'Accepting SDK licenses' block in "
            "setup.sh (anchor text not found). Refusing to guess -- check "
            "whether setup.sh has changed since this patch was written."
        )

    new_block = (
        'echo "== Accepting SDK licenses =="\n'
        "# Patch 07: set -o pipefail (line 5) makes this pipeline report a\n"
        "# *failure* even when sdkmanager itself succeeds, because `yes` is\n"
        "# killed by SIGPIPE (a non-zero exit status) the moment sdkmanager\n"
        "# stops reading stdin -- pipefail picks up that left-hand non-zero\n"
        "# status, and set -e then aborts the whole script right here,\n"
        "# before platform-tools/platforms/build-tools are ever installed\n"
        "# or the Gradle wrapper is ever generated. Disabling pipefail for\n"
        "# just this one pipeline restores default bash behavior ($? = the\n"
        "# *last* command's status, i.e. sdkmanager's real exit code), so a\n"
        "# genuine sdkmanager failure still stops the script but yes's\n"
        "# expected SIGPIPE no longer does.\n"
        "set +o pipefail\n"
        "yes | sdkmanager --licenses > /dev/null\n"
        "sdkmanager_license_status=$?\n"
        "set -o pipefail\n"
        'if [ "$sdkmanager_license_status" -ne 0 ]; then\n'
        '  echo "sdkmanager --licenses failed (exit $sdkmanager_license_status)" >&2\n'
        '  exit "$sdkmanager_license_status"\n'
        "fi\n"
    )

    text = text.replace(old_block, new_block)
    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    # Patch 16 collapsed every section this patch touches (Appendix rows
    # 11 and 34, the Step 5 note, the top status line) into CHANGELOG.md
    # plus one-line pointers. That's a strictly newer, equally-valid
    # "done" state for all of it -- nothing left for this patch to do on
    # a repeated full-chain run once patch 16 has landed. Without this
    # guard, a re-run would either fail loudly (row 11's anchor is gone)
    # or silently resurrect row 34 (its insertion anchor, row 33, is
    # untouched, so the old insert-if-missing check would re-add it).
    if "## Appendix — Open findings only" in text:
        print("ROADMAP.md already restructured by patch 16 -- skipping patch 07's ROADMAP.md edits.")
        return

    # 1. Appendix row #11
    old_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Verify — Step 2 |"
    )
    new_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Pre-verified against library source — patch 07 (still "
        "needs final `./gradlew` confirmation) |"
    )
    if old_row_11 in text:
        text = text.replace(old_row_11, new_row_11)
    elif new_row_11 in text:
        pass  # already patched
    elif "Confirmed via real compile — patch 14" in text:
        # Patch 14 later superseded this row's text entirely (it found
        # and fixed a real bug -- UpdateChannel -- that this patch's
        # source-reading pass missed). That's a strictly newer, more
        # authoritative status for the same row, so there's nothing for
        # patch 07 to do here on a repeated full-chain run.
        pass
    else:
        fail("Appendix row #11 anchor not found in ROADMAP.md")

    # 2. New Appendix row #34
    anchor_row_33 = (
        "| 33 | — | `friendlyError()` string matching against real yt-dlp "
        "output | `DownloadService.kt` | Needs device verification — Step 5 |"
    )
    new_row_34 = (
        "\n| 34 | — | `.devcontainer/setup.sh`: `pipefail` + "
        "`yes \\| sdkmanager --licenses` silently aborts setup before the "
        "Gradle wrapper is generated | `.devcontainer/setup.sh` | ✅ Fixed "
        "— patch 07 (found during Step 5 environment prep, not part of the "
        "original 4-part review) |"
    )
    if "| 34 |" not in text:
        if anchor_row_33 not in text:
            fail("Appendix row #33 anchor not found in ROADMAP.md")
        text = text.replace(anchor_row_33, anchor_row_33 + new_row_34)

    # 3. Note under the Step 5 heading
    step5_anchor = (
        "No emulator exists in this environment — this step is manual, on "
        "your own\nphone. Beyond Fable's original test sequence, a few "
        "additions below\nspecifically target the bugs found in Step 3."
    )
    step5_note = (
        step5_anchor
        + "\n\n**Environment note (patch 07):** `.devcontainer/setup.sh` had a "
        "`pipefail`-related bug that could silently abort setup before "
        "`gradlew` was ever generated (see Appendix #34) — fixed. Appendix "
        "#11 (`youtubedl-android`/`ffmpeg` import paths) has also been "
        "pre-verified against the library's own current source — still "
        "needs final confirmation by an actual `./gradlew assembleDebug` "
        "run, but the single most-likely compile blocker going into this "
        "step is now lower-risk than before."
    )
    # Patch 15 later consolidated this note (and patch 12's) into a
    # single "Build environment (patches 07-14)" paragraph and removed
    # the original text entirely. Without checking for that marker too,
    # a repeated full-chain run would see this note "missing" and
    # re-insert it every time -- which patch 15 would then re-consolidate
    # into yet another duplicate paragraph, growing by one on every pass.
    already_present = (
        "**Environment note (patch 07):**" in text
        or "**Build environment (patches 07-14):**" in text
    )
    if step5_anchor in text and not already_present:
        text = text.replace(step5_anchor, step5_note)
    elif already_present:
        pass
    else:
        fail("Step 5 intro anchor not found in ROADMAP.md")

    # 4. Top status line
    old_status = (
        "**Status as of this update (after patches 01-06):** Steps 1-4, Step 6"
    )
    new_status = (
        "**Status as of this update (after patches 01-07):** Steps 1-4, Step 6"
    )
    if old_status in text:
        text = text.replace(old_status, new_status)
    elif new_status in text:
        pass
    elif "**Status as of this update (after patches 01-" in text:
        # A later, more comprehensive documentation patch (15) already
        # rewrote this status line to reference a higher patch number.
        # That's strictly newer/better information than what this patch
        # would set, so there's nothing to do here -- this is a purely
        # cosmetic status line, not a functional change, so treat any
        # already-updated version of it as satisfying this step.
        print("Top status line already reflects a later patch -- skipping.")
    else:
        fail("Top status line anchor not found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def patch_handoff(repo_root: Path):
    path = repo_root / "HANDOFF.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    if "**Patch 07**" in text:
        print("HANDOFF.md already mentions patch 07 -- skipping.")
        return

    marker = "\n**Version-compatibility note worth remembering:**"
    if marker not in text:
        fail("Version-compatibility note marker not found in HANDOFF.md")

    patch07_bullet = (
        "\n- **Patch 07** — Pre-Step-5 environment fix: "
        "`.devcontainer/setup.sh` had a `pipefail` bug "
        "(`yes | sdkmanager --licenses`, see `ROADMAP.md` Appendix #34) "
        "that could silently abort setup before the Gradle wrapper was "
        "ever generated — fixed by disabling `pipefail` around just that "
        "one pipeline and checking `sdkmanager`'s real exit status "
        "explicitly. Also pre-verified the `youtubedl-android`/`ffmpeg` "
        "import paths and API shapes (`YoutubeDL`/`YoutubeDLRequest`/"
        "`YoutubeDLException`/`UpdateChannel` in "
        "`com.yausername.youtubedl_android`, `FFmpeg` in "
        "`com.yausername.ffmpeg`, the 3-parameter `execute()` progress "
        "callback, `updateYoutubeDL(context, UpdateChannel)`) against the "
        "library's own current GitHub source and README — all matched, "
        "though this still isn't a substitute for the real "
        "`./gradlew assembleDebug` run.\n"
    )

    text = text.replace(marker, patch07_bullet + marker)
    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 07 against: {repo_root}")

    patch_setup_sh(repo_root)
    patch_roadmap(repo_root)
    patch_handoff(repo_root)

    print("\nPatch 07 applied successfully.")


if __name__ == "__main__":
    main()
'''

OLD_PATCH13_FIX_INVALID_XML_COMMENT_PY = r'''#!/usr/bin/env python3
"""
Patch 13 -- Fix an invalid XML comment in ic_launcher_foreground.xml.

Context: with the JDK mismatch resolved (patch 12), the user's real
`./gradlew assembleDebug` run got past environment setup entirely and
reached actual project compilation for the first time -- and hit a real
resource-compilation error:

    Resource compilation failed (Failed to compile resource file:
    .../app/src/main/res/drawable/ic_launcher_foreground.xml: .
    Cause: javax.xml.stream.XMLStreamException: ParseError at
    [row,col]:[17,55]
    Message: The string "--" is not permitted within comments.)

The XML spec forbids the two-character sequence "--" anywhere inside a
comment's body (it's only valid as part of the closing "-->" marker).
The comment added by ROADMAP.md Step 6.6 (narrowing the icon's base/tray
path to fit the adaptive-icon safe zone) used " -- " as a parenthetical
dash, which XML doesn't allow. Confirmed via a full regex scan of every
`<!-- ... -->` block in every `.xml` file in the repo that this is the
only occurrence of the pattern -- nothing else needs the same fix.

Fix: replace " -- " with "; " in that one comment. Comment text only, no
functional/visual change to the icon itself.

Usage:
    python3 patch13_fix_invalid_xml_comment.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_icon(repo_root: Path):
    path = (
        repo_root
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old = (
        "    <!-- ROADMAP.md Step 6.6 [LOW, fixed]: narrowed from 34/74 to\n"
        "         40/68 so the bottom corners land inside the adaptive-icon\n"
        "         safe-zone circle (33dp radius from center) -- the wider\n"
        "         version was getting silently clipped on circular-mask\n"
        "         launchers/OEM skins. -->\n"
    )
    new = (
        "    <!-- ROADMAP.md Step 6.6 [LOW, fixed]: narrowed from 34/74 to\n"
        "         40/68 so the bottom corners land inside the adaptive-icon\n"
        "         safe-zone circle (33dp radius from center); the wider\n"
        "         version was getting silently clipped on circular-mask\n"
        "         launchers/OEM skins. -->\n"
    )

    if old in text:
        text = text.replace(old, new)
    elif new in text:
        print(f"{path} already fixed -- skipping.")
        return
    else:
        fail("Expected comment block not found in ic_launcher_foreground.xml")

    # Sanity check: no comment body in the file still contains "--".
    import re

    for m in re.finditer(r"<!--(.*?)-->", text, re.S):
        if "--" in m.group(1):
            fail(
                "Post-edit sanity check failed: a comment body still "
                "contains '--'. Refusing to write a file that would "
                "still fail XML parsing."
            )

    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    anchor = (
        "| 34 | — | `.devcontainer/setup.sh`: `pipefail` + "
        "`yes \\| sdkmanager --licenses` silently aborts setup before the "
        "Gradle wrapper is generated | `.devcontainer/setup.sh` | ✅ Fixed "
        "— patch 07 (found during Step 5 environment prep, not part of the "
        "original 4-part review) |"
    )
    new_row_35 = (
        "\n| 35 | — | Invalid XML comment (`--` inside a comment body) in "
        "`ic_launcher_foreground.xml` broke `mergeDebugResources` -- the "
        "first real compile error hit after the JDK/Gradle environment was "
        "fixed | `ic_launcher_foreground.xml` | ✅ Fixed — patch 13 (found "
        "via the first successful `./gradlew assembleDebug` attempt) |"
    )
    if "| 35 |" not in text:
        if anchor not in text:
            fail("Appendix row #34 anchor not found in ROADMAP.md")
        text = text.replace(anchor, anchor + new_row_35)

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 13 against: {repo_root}")

    patch_icon(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 13 applied successfully.")


if __name__ == "__main__":
    main()
'''

NEW_PATCH13_FIX_INVALID_XML_COMMENT_PY = r'''#!/usr/bin/env python3
"""
Patch 13 -- Fix an invalid XML comment in ic_launcher_foreground.xml.

Context: with the JDK mismatch resolved (patch 12), the user's real
`./gradlew assembleDebug` run got past environment setup entirely and
reached actual project compilation for the first time -- and hit a real
resource-compilation error:

    Resource compilation failed (Failed to compile resource file:
    .../app/src/main/res/drawable/ic_launcher_foreground.xml: .
    Cause: javax.xml.stream.XMLStreamException: ParseError at
    [row,col]:[17,55]
    Message: The string "--" is not permitted within comments.)

The XML spec forbids the two-character sequence "--" anywhere inside a
comment's body (it's only valid as part of the closing "-->" marker).
The comment added by ROADMAP.md Step 6.6 (narrowing the icon's base/tray
path to fit the adaptive-icon safe zone) used " -- " as a parenthetical
dash, which XML doesn't allow. Confirmed via a full regex scan of every
`<!-- ... -->` block in every `.xml` file in the repo that this is the
only occurrence of the pattern -- nothing else needs the same fix.

Fix: replace " -- " with "; " in that one comment. Comment text only, no
functional/visual change to the icon itself.

Usage:
    python3 patch13_fix_invalid_xml_comment.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_icon(repo_root: Path):
    path = (
        repo_root
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old = (
        "    <!-- ROADMAP.md Step 6.6 [LOW, fixed]: narrowed from 34/74 to\n"
        "         40/68 so the bottom corners land inside the adaptive-icon\n"
        "         safe-zone circle (33dp radius from center) -- the wider\n"
        "         version was getting silently clipped on circular-mask\n"
        "         launchers/OEM skins. -->\n"
    )
    new = (
        "    <!-- ROADMAP.md Step 6.6 [LOW, fixed]: narrowed from 34/74 to\n"
        "         40/68 so the bottom corners land inside the adaptive-icon\n"
        "         safe-zone circle (33dp radius from center); the wider\n"
        "         version was getting silently clipped on circular-mask\n"
        "         launchers/OEM skins. -->\n"
    )

    if old in text:
        text = text.replace(old, new)
    elif new in text:
        print(f"{path} already fixed -- skipping.")
        return
    else:
        fail("Expected comment block not found in ic_launcher_foreground.xml")

    # Sanity check: no comment body in the file still contains "--".
    import re

    for m in re.finditer(r"<!--(.*?)-->", text, re.S):
        if "--" in m.group(1):
            fail(
                "Post-edit sanity check failed: a comment body still "
                "contains '--'. Refusing to write a file that would "
                "still fail XML parsing."
            )

    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    # Patch 16 removed Appendix row 34 (this patch's insertion anchor)
    # entirely, migrating its content to CHANGELOG.md. Without this
    # guard, a repeated full-chain run would see row 35 "missing" and
    # silently re-insert it after row 34's old position.
    if "## Appendix — Open findings only" in text:
        print("ROADMAP.md already restructured by patch 16 -- skipping patch 13's ROADMAP.md edit.")
        return

    anchor = (
        "| 34 | — | `.devcontainer/setup.sh`: `pipefail` + "
        "`yes \\| sdkmanager --licenses` silently aborts setup before the "
        "Gradle wrapper is generated | `.devcontainer/setup.sh` | ✅ Fixed "
        "— patch 07 (found during Step 5 environment prep, not part of the "
        "original 4-part review) |"
    )
    new_row_35 = (
        "\n| 35 | — | Invalid XML comment (`--` inside a comment body) in "
        "`ic_launcher_foreground.xml` broke `mergeDebugResources` -- the "
        "first real compile error hit after the JDK/Gradle environment was "
        "fixed | `ic_launcher_foreground.xml` | ✅ Fixed — patch 13 (found "
        "via the first successful `./gradlew assembleDebug` attempt) |"
    )
    if "| 35 |" not in text:
        if anchor not in text:
            fail("Appendix row #34 anchor not found in ROADMAP.md")
        text = text.replace(anchor, anchor + new_row_35)

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 13 against: {repo_root}")

    patch_icon(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 13 applied successfully.")


if __name__ == "__main__":
    main()
'''

OLD_PATCH14_FIX_UPDATECHANNEL_IMPORT_PY = r'''#!/usr/bin/env python3
"""
Patch 14 -- Fix the UpdateChannel import in YtDlpUpdater.kt.

Context: with the environment and the XML comment fixed, the user's real
`./gradlew assembleDebug` reached actual Kotlin compilation for the
first time and hit:

    e: YtDlpUpdater.kt:5:41 Unresolved reference 'UpdateChannel'.
    e: YtDlpUpdater.kt:36:75 Unresolved reference 'UpdateChannel'.

Root cause, confirmed this time against the actual tagged source (not a
README snippet or an old sample app, both of which were previously
mis-read -- see below): `git clone` of
https://github.com/yausername/youtubedl-android at tag `0.18.1` (the
exact version pinned in app/build.gradle.kts) shows `UpdateChannel` is a
nested class of `YoutubeDL`, declared inside YoutubeDL.kt:

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel(...)
        object NIGHTLY : UpdateChannel(...)
        object MASTER : UpdateChannel(...)
        ...
    }

-- not a top-level class in the `com.yausername.youtubedl_android`
package. The library's own internal updater
(YoutubeDLUpdater.kt) imports it exactly as:

    import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel

This corrects patch 07's earlier "pre-verified" claim about this
specific import, which was wrong: patch 07 read the library's README
comment ("UpdateChannel.NIGHTLY or UpdateChannel.STABLE", written
without a qualifying prefix) as evidence `UpdateChannel` was top-level,
and dismissed the older Java sample app's `YoutubeDL.UpdateChannel._STABLE`
usage as pre-Kotlin-rewrite and therefore stale. The nesting in that old
sample was actually still correct in 0.18.1 -- only the constant names
changed (`_STABLE` -> `STABLE`; the underscore-prefixed names still
exist too, as `@JvmField` Java-interop aliases on the companion object,
which is why the old sample still compiles against new versions and
gave no obvious signal anything was wrong). Everything else patch 07
checked (YoutubeDL/YoutubeDLRequest/YoutubeDLException/FFmpeg locations,
the 3-parameter execute() callback, the updateYoutubeDL(context,
UpdateChannel) signature) was re-verified against this same real 0.18.1
checkout and is confirmed correct -- this was the one mistake, and it's
now fixed against ground truth rather than inference.

Fix: import the nested class directly, matching the library's own
internal usage exactly. No other line needs to change -- `UpdateChannel.STABLE`
on line 36 resolves correctly once the import does.

Usage:
    python3 patch14_fix_updatechannel_import.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_updater(repo_root: Path):
    path = (
        repo_root
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "kinescope"
        / "app"
        / "YtDlpUpdater.kt"
    )
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_import = "import com.yausername.youtubedl_android.UpdateChannel\n"
    new_import = "import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel\n"

    if old_import in text:
        text = text.replace(old_import, new_import)
    elif new_import in text:
        print(f"{path} already fixed -- skipping.")
        return
    else:
        fail("Expected UpdateChannel import line not found in YtDlpUpdater.kt")

    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Pre-verified against library source — patch 07 (still "
        "needs final `./gradlew` confirmation) |"
    )
    new_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | ✅ Confirmed via real compile — patch 14. One mistake "
        "found and fixed: `UpdateChannel` is a nested class of `YoutubeDL`, "
        "not top-level (patch 07's README-based pre-verification missed "
        "this); everything else patch 07 checked was correct, "
        "re-confirmed against the actual tagged 0.18.1 source |"
    )
    if old_row_11 in text:
        text = text.replace(old_row_11, new_row_11)
    elif new_row_11 in text:
        print("ROADMAP.md Appendix #11 already updated -- skipping.")
    else:
        fail("Appendix row #11 anchor not found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 14 against: {repo_root}")

    patch_updater(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 14 applied successfully.")


if __name__ == "__main__":
    main()
'''

NEW_PATCH14_FIX_UPDATECHANNEL_IMPORT_PY = r'''#!/usr/bin/env python3
"""
Patch 14 -- Fix the UpdateChannel import in YtDlpUpdater.kt.

Context: with the environment and the XML comment fixed, the user's real
`./gradlew assembleDebug` reached actual Kotlin compilation for the
first time and hit:

    e: YtDlpUpdater.kt:5:41 Unresolved reference 'UpdateChannel'.
    e: YtDlpUpdater.kt:36:75 Unresolved reference 'UpdateChannel'.

Root cause, confirmed this time against the actual tagged source (not a
README snippet or an old sample app, both of which were previously
mis-read -- see below): `git clone` of
https://github.com/yausername/youtubedl-android at tag `0.18.1` (the
exact version pinned in app/build.gradle.kts) shows `UpdateChannel` is a
nested class of `YoutubeDL`, declared inside YoutubeDL.kt:

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel(...)
        object NIGHTLY : UpdateChannel(...)
        object MASTER : UpdateChannel(...)
        ...
    }

-- not a top-level class in the `com.yausername.youtubedl_android`
package. The library's own internal updater
(YoutubeDLUpdater.kt) imports it exactly as:

    import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel

This corrects patch 07's earlier "pre-verified" claim about this
specific import, which was wrong: patch 07 read the library's README
comment ("UpdateChannel.NIGHTLY or UpdateChannel.STABLE", written
without a qualifying prefix) as evidence `UpdateChannel` was top-level,
and dismissed the older Java sample app's `YoutubeDL.UpdateChannel._STABLE`
usage as pre-Kotlin-rewrite and therefore stale. The nesting in that old
sample was actually still correct in 0.18.1 -- only the constant names
changed (`_STABLE` -> `STABLE`; the underscore-prefixed names still
exist too, as `@JvmField` Java-interop aliases on the companion object,
which is why the old sample still compiles against new versions and
gave no obvious signal anything was wrong). Everything else patch 07
checked (YoutubeDL/YoutubeDLRequest/YoutubeDLException/FFmpeg locations,
the 3-parameter execute() callback, the updateYoutubeDL(context,
UpdateChannel) signature) was re-verified against this same real 0.18.1
checkout and is confirmed correct -- this was the one mistake, and it's
now fixed against ground truth rather than inference.

Fix: import the nested class directly, matching the library's own
internal usage exactly. No other line needs to change -- `UpdateChannel.STABLE`
on line 36 resolves correctly once the import does.

Usage:
    python3 patch14_fix_updatechannel_import.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_updater(repo_root: Path):
    path = (
        repo_root
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "kinescope"
        / "app"
        / "YtDlpUpdater.kt"
    )
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_import = "import com.yausername.youtubedl_android.UpdateChannel\n"
    new_import = "import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel\n"

    if old_import in text:
        text = text.replace(old_import, new_import)
    elif new_import in text:
        print(f"{path} already fixed -- skipping.")
        return
    else:
        fail("Expected UpdateChannel import line not found in YtDlpUpdater.kt")

    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    # Patch 16 removed Appendix row 11 entirely, migrating its content
    # to CHANGELOG.md's Patch 14 entry. Nothing left for this patch to
    # do on a repeated full-chain run once patch 16 has landed.
    if "## Appendix — Open findings only" in text:
        print("ROADMAP.md already restructured by patch 16 -- skipping patch 14's ROADMAP.md edit.")
        return

    old_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | Pre-verified against library source — patch 07 (still "
        "needs final `./gradlew` confirmation) |"
    )
    new_row_11 = (
        "| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple "
        "files | ✅ Confirmed via real compile — patch 14. One mistake "
        "found and fixed: `UpdateChannel` is a nested class of `YoutubeDL`, "
        "not top-level (patch 07's README-based pre-verification missed "
        "this); everything else patch 07 checked was correct, "
        "re-confirmed against the actual tagged 0.18.1 source |"
    )
    if old_row_11 in text:
        text = text.replace(old_row_11, new_row_11)
    elif new_row_11 in text:
        print("ROADMAP.md Appendix #11 already updated -- skipping.")
    else:
        fail("Appendix row #11 anchor not found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 14 against: {repo_root}")

    patch_updater(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 14 applied successfully.")


if __name__ == "__main__":
    main()
'''

OLD_PATCH15_DOCS_AFTER_FIRST_BUILD_PY = r'''#!/usr/bin/env python3
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
'''

NEW_PATCH15_DOCS_AFTER_FIRST_BUILD_PY = r'''#!/usr/bin/env python3
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

    # Patch 16 rewrote the top status block's wording (not just its
    # patch-number suffix) and collapsed Step 2 to a one-line pointer,
    # so neither this patch's old_status_block/new_status_block nor its
    # old_step2/new_step2 text can match anymore either way. That's a
    # strictly newer, equally-valid "done" state -- nothing left for
    # this patch to do on a repeated full-chain run once patch 16 has
    # landed. (The Step 5 environment-note check below is unaffected --
    # patch 16 doesn't touch Step 5 -- but short-circuiting here is
    # simpler and safer than tracking each check's fate individually.)
    if "## Appendix — Open findings only" in text:
        print("ROADMAP.md already restructured by patch 16 -- skipping patch 15's ROADMAP.md edits.")
        return

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
'''



def patch_roadmap(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "ROADMAP.md", OLD_ROADMAP_MD, NEW_ROADMAP_MD, "ROADMAP.md",
        superseded_marker="Fixed — patch 17: written as a",
    )


def patch_handoff(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "HANDOFF.md", OLD_HANDOFF_MD, NEW_HANDOFF_MD, "HANDOFF.md",
        superseded_marker="**Patch 17** -- Added `CJM.md`",
    )


def patch_claude_md(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "CLAUDE.md", OLD_CLAUDE_MD, NEW_CLAUDE_MD, "CLAUDE.md",
        superseded_marker="Decision (patch 19):",
    )


def patch_readme(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "README.md", OLD_README_MD, NEW_README_MD, "README.md"
    )


def patch_build_gradle(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "app" / "build.gradle.kts",
        OLD_APP_BUILD_GRADLE_KTS,
        NEW_APP_BUILD_GRADLE_KTS,
        "app/build.gradle.kts",
    )


def patch_patch07_script(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "patch07_fix_setup_and_verify_imports.py",
        OLD_PATCH07_FIX_SETUP_AND_VERIFY_IMPORTS_PY,
        NEW_PATCH07_FIX_SETUP_AND_VERIFY_IMPORTS_PY,
        "patch07_fix_setup_and_verify_imports.py (ROADMAP.md idempotency guard)",
    )


def patch_patch13_script(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "patch13_fix_invalid_xml_comment.py",
        OLD_PATCH13_FIX_INVALID_XML_COMMENT_PY,
        NEW_PATCH13_FIX_INVALID_XML_COMMENT_PY,
        "patch13_fix_invalid_xml_comment.py (ROADMAP.md idempotency guard)",
    )


def patch_patch14_script(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "patch14_fix_updatechannel_import.py",
        OLD_PATCH14_FIX_UPDATECHANNEL_IMPORT_PY,
        NEW_PATCH14_FIX_UPDATECHANNEL_IMPORT_PY,
        "patch14_fix_updatechannel_import.py (ROADMAP.md idempotency guard)",
    )


def patch_patch15_script(repo_root: Path):
    whole_file_guarded_replace(
        repo_root / "patch15_docs_after_first_build.py",
        OLD_PATCH15_DOCS_AFTER_FIRST_BUILD_PY,
        NEW_PATCH15_DOCS_AFTER_FIRST_BUILD_PY,
        "patch15_docs_after_first_build.py (ROADMAP.md idempotency guard)",
    )


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    if not (repo_root / "settings.gradle.kts").exists():
        fail(
            f"{repo_root} doesn't look like the repo root "
            f"(settings.gradle.kts not found). Pass the repo root as an "
            f"argument, or run this from the repo root."
        )

    patch_workflow_file(repo_root)
    patch_changelog(repo_root)
    patch_build_gradle(repo_root)
    patch_roadmap(repo_root)
    patch_claude_md(repo_root)
    patch_readme(repo_root)
    patch_handoff(repo_root)
    patch_patch07_script(repo_root)
    patch_patch13_script(repo_root)
    patch_patch14_script(repo_root)
    patch_patch15_script(repo_root)

    print("\nPatch 16 applied successfully.")


if __name__ == "__main__":
    main()
