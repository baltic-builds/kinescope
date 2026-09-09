#!/usr/bin/env python3
"""
Patch 06 — ROADMAP.md Step 7: rename to Kinescope.

Run this from the ROOT of the repo, AFTER patches 01-05 have already been
applied and committed.

**Why now, not later:** the app has never been installed on a real device
(Step 5 hasn't run yet). This is the last safe window to change
`applicationId` cleanly -- once a real device install exists, Android
treats a changed `applicationId` as a brand-new app (separate data,
separate install, manual uninstall of the old one). ROADMAP.md's execution
order deliberately puts Step 7 before Step 5 for exactly this reason.

What this does:

1. Renames the Gradle identity: `namespace`/`applicationId` in
   `app/build.gradle.kts` from `com.baltic.ytoffline` to
   `com.kinescope.app` (the value already decided and recorded in
   ROADMAP.md/HANDOFF.md), and `rootProject.name` in
   `settings.gradle.kts` from `yt-offline` to `kinescope`.

2. Moves every Kotlin source file from
   `app/src/main/java/com/baltic/ytoffline/` to
   `app/src/main/java/com/kinescope/app/`, rewriting each file's
   `package` declaration to match. `AndroidManifest.xml`'s
   `android:name=".XxxYyy"` entries are relative to `namespace` and need
   no change -- they still resolve correctly once `namespace` above is
   updated. No app-specific `R.xxx` fully-qualified references exist in
   source (checked: the only `R.drawable` usages are `android.R.drawable`,
   the platform's own resources), so nothing else needs touching for the
   package move itself.

3. Updates `app_name` in `strings.xml` from "YT Offline" to "Kinescope" --
   this is the actual `android:label` shown under the launcher icon.

4. Fixes the two other user-visible strings that would otherwise leave the
   rebrand looking half-done on screen: the foreground-service
   notification title in `DownloadService.kt` and the `TopAppBar` title in
   `MainActivity.kt`, both currently hardcoded "YT Offline". Also updates
   the internal `ACTION_ENQUEUE` intent-action string to match the new
   package, for hygiene -- it's self-referential (defined and read only
   within `DownloadService.kt` itself) so this isn't functionally
   required, just keeps it from being a confusing leftover.

5. Updates the three "YT Offline" mentions in `design.md` (the explicit
   "app stays 'YT Offline'" honesty-section line, plus two mentions in the
   typography table / screen-mapping section) to "Kinescope".

6. Marks ROADMAP.md's Step 7 checkboxes done, refreshes its top status
   summary and execution-order list (Step 7 done, Step 5 next), and
   updates Appendix finding #21.

7. Rewrites HANDOFF.md: project identity is no longer "mid-rename",
   patch-history list gets a Patch 06 entry, done/not-done section moves
   Step 7 to done and Step 5 to next, file map header reflects the new
   package path, and "Immediate next step" now points at Step 5.

**Deliberately NOT done (out of scope for this Step):** internal Kotlin
identifiers that happen to contain "YtOffline" (`YtOfflineApp` the class,
`YtOfflineTheme`, `YtOfflineExtras`, `YtOfflineTypography`, etc.) are left
unchanged. They're not user-visible, ROADMAP.md's Step 7 checklist never
listed them, and renaming them adds risk (more surface area, more chance
of a typo breaking the build) for zero user-facing benefit. Revisit only
if it becomes annoying to read the code. `RELEASE.md`'s keystore
filename/alias and GitHub release title (still say "yt-offline"/"YT
Offline") are also left alone -- that's Step 9 scope, not this one, and
they're free-form labels with no functional tie to `applicationId`.

Safe to re-run: every edit is guarded by an exact-match check, and the
file-move step detects "already moved" and skips cleanly.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent

OLD_PKG_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline"
NEW_PKG_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "kinescope" / "app"

KT_FILES = [
    "DownloadQueueBus.kt",
    "DownloadService.kt",
    "MainActivity.kt",
    "MediaStorage.kt",
    "QualityPresets.kt",
    "Settings.kt",
    "Theme.kt",
    "YtDlpUpdater.kt",
    "YtOfflineApp.kt",
]

OLD_PACKAGE_LINE = "package com.baltic.ytoffline\n"
NEW_PACKAGE_LINE = "package com.kinescope.app\n"


class PatchError(RuntimeError):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise PatchError(f"Expected file not found: {path}\n"
                          f"Are you running this from the repo root?")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = read(path)
    count = text.count(old)
    if count == 0:
        if new in text:
            print(f"  [skip] {label}: already applied, leaving as-is.")
            return
        raise PatchError(
            f"{label}: expected text not found in {path}.\n"
            f"The file may have changed since this patch was written.\n"
            f"--- expected snippet ---\n{old}\n------------------------"
        )
    if count > 1:
        raise PatchError(
            f"{label}: expected text found {count} times in {path}, "
            f"need exactly 1. Refusing to guess which one to replace."
        )
    write(path, text.replace(old, new, 1))
    print(f"  [ok] {label}")


def overwrite(path: pathlib.Path, content: str, label: str) -> None:
    write(path, content)
    print(f"  [ok] {label} (full file rewrite)")


def require_prior_patches() -> None:
    handoff = read(ROOT / "HANDOFF.md")
    if "com.kinescope.app" not in handoff:
        raise PatchError(
            "HANDOFF.md doesn't mention 'com.kinescope.app' yet -- expected "
            "patch 05 to already be applied (it records the Kinescope naming "
            "decision). Run patches 01-05 first, in order."
        )


# ---------------------------------------------------------------------------
# 1. Gradle identity
# ---------------------------------------------------------------------------

def fix_build_gradle_identity() -> None:
    path = ROOT / "app" / "build.gradle.kts"
    replace_once(
        path,
        'namespace = "com.baltic.ytoffline"',
        'namespace = "com.kinescope.app"',
        "app/build.gradle.kts: namespace -> com.kinescope.app",
    )
    replace_once(
        path,
        'applicationId = "com.baltic.ytoffline"',
        'applicationId = "com.kinescope.app"',
        "app/build.gradle.kts: applicationId -> com.kinescope.app",
    )


def fix_settings_gradle_name() -> None:
    path = ROOT / "settings.gradle.kts"
    replace_once(
        path,
        'rootProject.name = "yt-offline"',
        'rootProject.name = "kinescope"',
        "settings.gradle.kts: rootProject.name -> kinescope",
    )


# ---------------------------------------------------------------------------
# 2. Move + repackage Kotlin sources
# ---------------------------------------------------------------------------

def move_and_repackage_sources() -> None:
    already_moved = NEW_PKG_DIR.exists() and all((NEW_PKG_DIR / f).exists() for f in KT_FILES)
    old_present = OLD_PKG_DIR.exists() and any((OLD_PKG_DIR / f).exists() for f in KT_FILES)

    if already_moved and not old_present:
        print("  [skip] Kotlin sources: already moved to com/kinescope/app/.")
        return

    if not old_present:
        raise PatchError(
            f"Expected source files not found under {OLD_PKG_DIR}, and the "
            f"new location {NEW_PKG_DIR} isn't fully populated either. Repo "
            f"state doesn't match what this patch expects."
        )

    NEW_PKG_DIR.mkdir(parents=True, exist_ok=True)

    for filename in KT_FILES:
        old_path = OLD_PKG_DIR / filename
        new_path = NEW_PKG_DIR / filename

        if not old_path.exists():
            if new_path.exists():
                print(f"  [skip] {filename}: already moved.")
                continue
            raise PatchError(f"Expected source file not found: {old_path}")

        text = old_path.read_text(encoding="utf-8")
        if OLD_PACKAGE_LINE not in text:
            raise PatchError(
                f"{filename}: expected package declaration "
                f"'{OLD_PACKAGE_LINE.strip()}' not found. File may already "
                f"be repackaged, or has changed since this patch was written."
            )
        text = text.replace(OLD_PACKAGE_LINE, NEW_PACKAGE_LINE, 1)
        new_path.write_text(text, encoding="utf-8")
        old_path.unlink()
        print(f"  [ok] moved + repackaged: {filename} -> com/kinescope/app/")

    # Clean up the now-empty old package directories. Never touch the
    # top-level com/ dir -- the new package tree lives under it too.
    if OLD_PKG_DIR.exists() and not any(OLD_PKG_DIR.iterdir()):
        OLD_PKG_DIR.rmdir()
        print("  [ok] removed empty directory com/baltic/ytoffline/")

    baltic_dir = OLD_PKG_DIR.parent  # .../com/baltic
    if baltic_dir.exists() and baltic_dir.name == "baltic" and not any(baltic_dir.iterdir()):
        baltic_dir.rmdir()
        print("  [ok] removed empty directory com/baltic/")


# ---------------------------------------------------------------------------
# 3. strings.xml
# ---------------------------------------------------------------------------

def fix_app_name() -> None:
    path = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    replace_once(
        path,
        '<string name="app_name">YT Offline</string>',
        '<string name="app_name">Kinescope</string>',
        "strings.xml: app_name -> Kinescope",
    )


# ---------------------------------------------------------------------------
# 4. User-visible strings left in DownloadService.kt / MainActivity.kt
# ---------------------------------------------------------------------------

def fix_notification_title() -> None:
    path = NEW_PKG_DIR / "DownloadService.kt"
    replace_once(
        path,
        '.setContentTitle("YT Offline")',
        '.setContentTitle("Kinescope")',
        "DownloadService.kt: notification title -> Kinescope",
    )


def fix_action_enqueue_string() -> None:
    path = NEW_PKG_DIR / "DownloadService.kt"
    replace_once(
        path,
        'private const val ACTION_ENQUEUE = "com.baltic.ytoffline.ACTION_ENQUEUE"',
        'private const val ACTION_ENQUEUE = "com.kinescope.app.ACTION_ENQUEUE"',
        "DownloadService.kt: ACTION_ENQUEUE -> com.kinescope.app",
    )


def fix_topbar_title() -> None:
    path = NEW_PKG_DIR / "MainActivity.kt"
    replace_once(
        path,
        'title = { Text("YT Offline", style = MaterialTheme.typography.headlineSmall) },',
        'title = { Text("Kinescope", style = MaterialTheme.typography.headlineSmall) },',
        "MainActivity.kt: TopAppBar title -> Kinescope",
    )


# ---------------------------------------------------------------------------
# 5. design.md
# ---------------------------------------------------------------------------

def fix_design_md() -> None:
    path = ROOT / "design.md"
    replace_once(
        path,
        'The app stays "YT Offline."',
        'The app is named **Kinescope** -- a name with no connection to '
        'Anthropic or Claude.',
        'design.md: honesty-section name line -> Kinescope',
    )
    replace_once(
        path,
        'The "YT Offline" title only',
        'The "Kinescope" title only',
        'design.md: typography table title mention -> Kinescope',
    )
    replace_once(
        path,
        '**App title** ("YT Offline")',
        '**App title** ("Kinescope")',
        'design.md: screen-mapping title mention -> Kinescope',
    )


# ---------------------------------------------------------------------------
# 6. ROADMAP.md
# ---------------------------------------------------------------------------

def fix_roadmap_status_line() -> None:
    path = ROOT / "ROADMAP.md"
    replace_once(
        path,
        '**Status as of this update (after patches 01-04):** Steps 1-4 and Step 6\n'
        '(6.1-6.6; 6.7 is optional and still skipped) are done. `./gradlew\n'
        'assembleDebug` has **still never run** — nothing here has been\n'
        'build-verified yet. A full deep code review of the entire codebase was\n',
        '**Status as of this update (after patches 01-06):** Steps 1-4, Step 6\n'
        '(6.1-6.6; 6.7 is optional and still skipped), and Step 7 (Kinescope\n'
        'rename) are done. `./gradlew assembleDebug` has **still never run** —\n'
        'nothing here has been build-verified yet. A full deep code review of\n'
        'the entire codebase was\n',
        'ROADMAP.md: top status line -> includes Step 7 done',
    )


def fix_roadmap_execution_order() -> None:
    path = ROOT / "ROADMAP.md"
    replace_once(
        path,
        '1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)\n'
        '2. **Step 7 — Kinescope rename** ← next\n'
        '3. Step 5 — First device install + testing\n'
        '4. Step 8 — Documentation\n'
        '5. Step 9 — Signed release',
        '1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)\n'
        '2. ~~Step 7 — Kinescope rename~~ ✅ done (patch 06)\n'
        '3. **Step 5 — First device install + testing** ← next\n'
        '4. Step 8 — Documentation\n'
        '5. Step 9 — Signed release',
        'ROADMAP.md: execution order -> Step 7 done, Step 5 next',
    )


def fix_roadmap_step7_checkboxes() -> None:
    path = ROOT / "ROADMAP.md"
    old = (
        '## Step 7 — Branding: rename to Kinescope\n'
        '\n'
        '- [ ] **[Do this now, not later]** `namespace`/`applicationId`/\n'
        '      `rootProject.name` in `app/build.gradle.kts` and\n'
        '      `settings.gradle.kts` still say `com.baltic.ytoffline` / `yt-offline`.\n'
        '      **Because the app has never been installed on a real device yet,\n'
        '      this is the last safe window to change `applicationId` cleanly** —\n'
        '      once a real device install exists, changing `applicationId` becomes\n'
        '      a one-way door (Android treats it as an entirely new app: separate\n'
        '      data, separate install, old one needs manual uninstall). If you\n'
        '      want the "Kinescope" rename to be permanent and clean, do the full\n'
        '      `applicationId`/`namespace` rename **before** Step 5\'s first\n'
        '      install, not after.\n'
        '- [ ] Update `app_name` in `strings.xml` from `"YT Offline"` to\n'
        '      `"Kinescope"` — this one is always safe to change at any time,\n'
        '      `android:label` is purely cosmetic (unlike `applicationId`).\n'
        '- [ ] No icon/color changes are required for a name-only rebrand — the\n'
        '      existing terracotta/cream identity carries over fine.\n'
    )
    new = (
        '## Step 7 — Branding: rename to Kinescope ✅ done (patch 06)\n'
        '\n'
        '- [x] **[Do this now, not later]** `namespace`/`applicationId`/\n'
        '      `rootProject.name` in `app/build.gradle.kts` and\n'
        '      `settings.gradle.kts` renamed to `com.kinescope.app` / `kinescope`.\n'
        '      Done before Step 5\'s first device install, while `applicationId`\n'
        '      was still a safe, reversible change. (Fixed — patch 06.) The\n'
        '      Kotlin package directory moved to match\n'
        '      (`app/src/main/java/com/kinescope/app/`, was\n'
        '      `com/baltic/ytoffline/`), and every file\'s `package` declaration\n'
        '      updated accordingly.\n'
        '- [x] Update `app_name` in `strings.xml` from `"YT Offline"` to\n'
        '      `"Kinescope"`. (Fixed — patch 06.) Also updated two other\n'
        '      user-visible strings left over from the old name so the rebrand\n'
        '      doesn\'t look half-done on screen: the foreground-service\n'
        '      notification title (`DownloadService.kt`) and the `TopAppBar`\n'
        '      title (`MainActivity.kt`). The internal `ACTION_ENQUEUE` intent-\n'
        '      action string was also updated to match the new package for\n'
        '      hygiene, though it\'s self-referential and wasn\'t a functional\n'
        '      requirement.\n'
        '- [x] No icon/color changes are required for a name-only rebrand — the\n'
        '      existing terracotta/cream identity carries over fine, confirmed,\n'
        '      no action taken. (Kotlin *identifiers* like `YtOfflineTheme`,\n'
        '      `YtOfflineApp`, `YtOfflineExtras` were deliberately left\n'
        '      unchanged — internal-only, not user-visible, never part of this\n'
        '      Step\'s scope; revisit only if it becomes annoying to read.)\n'
    )
    replace_once(path, old, new, "ROADMAP.md: Step 7 checkboxes -> done")


def fix_roadmap_appendix_row21() -> None:
    path = ROOT / "ROADMAP.md"
    replace_once(
        path,
        '| 21 | Info | `applicationId`/`namespace`/`rootProject.name` still say `ytoffline` | build files | Open — Step 7 |',
        '| 21 | Info | `applicationId`/`namespace`/`rootProject.name` still say `ytoffline` | build files | ✅ Fixed — patch 06 |',
        "ROADMAP.md: Appendix row 21 -> done",
    )


# ---------------------------------------------------------------------------
# 7. HANDOFF.md — full rewrite
# ---------------------------------------------------------------------------

NEW_HANDOFF_MD = '''# Handoff Snapshot

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
'''


def fix_handoff() -> None:
    path = ROOT / "HANDOFF.md"
    current = read(path)
    if "patch 06" in current or "com.kinescope.app / \"Kinescope\"" in current:
        print("  [skip] HANDOFF.md: already patched.")
        return
    overwrite(path, NEW_HANDOFF_MD, "HANDOFF.md (full rewrite: Step 7 done, Step 5 next)")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 06 (ROADMAP.md Step 7: Kinescope rename)...\n")
    try:
        require_prior_patches()

        print("Gradle identity:")
        fix_build_gradle_identity()
        fix_settings_gradle_name()

        print("\nKotlin sources (move + repackage):")
        move_and_repackage_sources()

        print("\nstrings.xml:")
        fix_app_name()

        print("\nUser-visible strings (DownloadService.kt / MainActivity.kt):")
        fix_notification_title()
        fix_action_enqueue_string()
        fix_topbar_title()

        print("\ndesign.md:")
        fix_design_md()

        print("\nROADMAP.md:")
        fix_roadmap_status_line()
        fix_roadmap_execution_order()
        fix_roadmap_step7_checkboxes()
        fix_roadmap_appendix_row21()

        print("\nHANDOFF.md:")
        fix_handoff()
    except PatchError as exc:
        print(f"\nPATCH FAILED: {exc}", file=sys.stderr)
        return 1

    print("\nAll changes applied successfully.")
    print("Package is now com.kinescope.app, app name is 'Kinescope'.")
    print("Next: ./gradlew assembleDebug as a sanity check, then Step 5")
    print("(first device install + testing).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
