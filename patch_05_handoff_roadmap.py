#!/usr/bin/env python3
"""
Patch 05 — refresh ROADMAP.md's top summary and rewrite HANDOFF.md.

Run this from the ROOT of the yt-offline repo, AFTER patches 01, 02,
03, and 04 have already been applied and committed.

This patch does not touch any Kotlin/XML source — it's documentation
only, bringing the two cross-session continuity files up to date with
everything patches 01-04 actually did, plus recording the decisions
made along the way (Kinescope rename target: applicationId/namespace
`com.kinescope.app`; execution order 6 -> 7 -> 5 -> 8 -> 9, which
deliberately does not match the Step numbering).

What this does:

1. Replaces ROADMAP.md's top intro section (the "Status as of this
   update" block) with a corrected, current summary: what's actually
   done (Steps 1-4, Step 6 except optional 6.7), what's next and in
   what order, and the Kinescope naming decision. The detailed
   per-step sections and the Appendix below it are untouched -- they
   were already accurate.

2. Marks the Backlog's "in-app delete for library entries" item done
   -- it was previously listed as "partially addressed by the Step
   6.5 overflow-menu pattern" (written before patch 04 existed), but
   patch 04 implemented the actual delete logic
   (MediaStorage.delete() / ContentResolver.delete()), not just the
   UI affordance, so it's genuinely complete now.

3. Fully rewrites HANDOFF.md, which was last updated before any of
   the 4 patches existed (it still described "9 items" of risk from
   before Fable's review even produced the current ROADMAP.md). The
   new version summarizes what each patch did, the current true
   done/not-done status, the version-compatibility finding worth
   remembering (Compose BOM 2024.11.00 -> Material3 1.3.1, older than
   several APIs' current tutorials assume), and how to resume in a
   new conversation.

Safe to re-run: every edit is guarded by an exact-match check.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


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
    main_activity = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MainActivity.kt"
    text = read(main_activity)
    if "QueueRow" not in text:
        raise PatchError(
            "MainActivity.kt doesn't have patch 04's changes yet (no "
            "'QueueRow' found). Run patches 01-04 first, in order."
        )


# ---------------------------------------------------------------------------
# ROADMAP.md — refreshed top summary
# ---------------------------------------------------------------------------

OLD_ROADMAP_INTRO = '# Roadmap\n\n**Status as of this update:** All 7 original numbered phases + two design passes are\nwritten but **still unbuilt** — `./gradlew assembleDebug` has never run. A full\ndeep code review of the entire codebase (docs, build config, all Kotlin\nsource, all resources) was performed by Claude Fable 5.1, in 4 passes, and\nthis document consolidates every finding from that review into one ordered\nimplementation plan.\n\n**There is no Phase 8.** The sections below are **verification and fix\nsteps**, not new numbered feature phases — this document extends the\n`Step 1–5` verification plan Fable proposed, it doesn\'t replace it with new\n"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.\n\n**How to use this document:** work top to bottom. Steps 1–3 are blocking —\nnothing else matters until the app compiles and runs once on a real phone.\nSteps 4–6 (design, branding, docs) can happen in any order once Steps 1–3\nare done, ideally before Step 7 (signed release). The Appendix at the end\nis a full traceability table — every single finding from all 4 review\nparts is listed there with its current status, so nothing gets lost.\n\n'

NEW_ROADMAP_INTRO = '# Roadmap\n\n**Status as of this update (after patches 01-04):** Steps 1-4 and Step 6\n(6.1-6.6; 6.7 is optional and still skipped) are done. `./gradlew\nassembleDebug` has **still never run** — nothing here has been\nbuild-verified yet. A full deep code review of the entire codebase was\nperformed by Claude Fable 5.1 in 4 passes; this document consolidates every\nfinding from that review into one ordered implementation plan, and its\ncheckboxes/Appendix are kept current as work actually gets done (see\n`HANDOFF.md` for the patch-by-patch history).\n\n**Decided:** the Kinescope rename (Step 7) uses `applicationId` /\n`namespace` **`com.kinescope.app`**.\n\n**Execution order from here — this deliberately does NOT match the Step\nnumbers below**, because Step 7 must happen before Step 5\'s first device\ninstall (changing `applicationId` after that is effectively irreversible —\nAndroid treats it as a different app), and Step 6 needed to be finished\nfirst since Step 7 touches many of the same files:\n\n1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)\n2. **Step 7 — Kinescope rename** ← next\n3. Step 5 — First device install + testing\n4. Step 8 — Documentation\n5. Step 9 — Signed release\n\nSteps 1-4 (compile blockers, then critical/product-quality fixes) are done\nand came first, as they had to — nothing else matters until the app\nactually compiles.\n\n**There is no Phase 8.** The sections below are **verification and fix\nsteps**, not new numbered feature phases — this document extends the\n`Step 1-5` verification plan Fable proposed, it doesn\'t replace it with new\n"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.\n\n**How to use this document:** the numbered Step sections below keep their\noriginal order (matching the initial review) for reference — follow the\n**execution order above**, not the numbering, for what to actually do\nnext. The Appendix at the end is a full traceability table; every finding\nfrom the original 4-part review is listed there with its current status.\n\n'


def fix_roadmap_intro() -> None:
    path = ROOT / "ROADMAP.md"
    replace_once(path, OLD_ROADMAP_INTRO, NEW_ROADMAP_INTRO, "ROADMAP.md: refreshed top status summary")


def fix_roadmap_backlog_delete_item() -> None:
    path = ROOT / "ROADMAP.md"
    old = (
        "- [ ] In-app delete for library entries (vs. relying on an external file\n"
        "      manager) — partially addressed by the Step 6.5 overflow-menu\n"
        "      pattern; make sure the actual delete logic (both the `MediaStore`\n"
        "      row and file) gets implemented, not just the UI affordance.\n"
    )
    new = (
        "- [x] In-app delete for library entries (vs. relying on an external file\n"
        "      manager) — done as of patch 04: the Step 6.5 overflow menu's\n"
        "      Delete action calls `MediaStorage.delete()`\n"
        "      (`ContentResolver.delete()` on the app's own `MediaStore` row),\n"
        "      not just the UI affordance.\n"
    )
    replace_once(path, old, new, "ROADMAP.md: mark in-app delete backlog item done")


# ---------------------------------------------------------------------------
# HANDOFF.md — full rewrite
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
cost. Currently mid-rename: the codebase and package are still
`com.baltic.ytoffline` / "YT Offline"; the decided new identity is
**Kinescope**, `applicationId`/`namespace` **`com.kinescope.app`** —
that's Step 7, not done yet (see "What's next" below).

## How this codebase got here

Written by Claude across 7 phases with no intermediate compilation
(explicit user instruction at the time: "keep going, test everything
at the end"). A deep 4-part review by Claude Fable 5.1 then produced
`ROADMAP.md` — a sequenced, prioritized fix list with a 33-item
findings-traceability Appendix. Four patch scripts have since been
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
**not** match the document's own Step numbering, because Step 7 must
happen before Step 5's first device install). As of this snapshot:

**Done:** Steps 1, 2 (except Appendix finding #11 — the
`youtubedl-android`/`ffmpeg` import paths can only be confirmed by an
actual `./gradlew assembleDebug`, which still hasn't run), 3, 4, and 6
(6.1-6.6; 6.7 — an optional monochrome adaptive-icon layer for Android
13+ themed icons — is still skipped, opt-in only, not required).

**Not done, in the order to actually do them:**

1. **Step 7 — Kinescope rename** (`applicationId`/`namespace` →
   `com.kinescope.app`). Time-sensitive: must happen before Step 5's
   first device install, since changing `applicationId` after that is
   effectively irreversible (Android treats it as a different app).
2. **Step 5 — First device install + testing.** Also the first time
   `./gradlew assembleDebug` actually runs — expect to find and fix
   compile errors here, most likely around the `youtubedl-android`
   import paths (Appendix finding #11, never confirmed any other
   way). `ROADMAP.md`'s Step 5 section has the full manual test
   checklist, including explicitly stress-testing the
   `DownloadService` race-condition fix from patch 01 (queue several
   videos in quick succession).
3. **Step 8 — Documentation** (a rewritten `README.md` is already
   drafted and ready to paste in per `ROADMAP.md`; a `CJM.md`
   customer-journey-map document; keep `ROADMAP.md` itself current).
4. **Step 9 — Signed release**, per `RELEASE.md`, only once every item
   in Steps 1-5 is confirmed working on a real device.

**Backlog (optional, unscheduled — see `ROADMAP.md`'s Backlog section
for the full list with reasoning):** persisting queue state across a
process kill, orphaned-temp-file cleanup on service start, migrating
remaining `Thread`/`Handler` usage to coroutines for consistency with
`DownloadService`'s own fix, externalizing hardcoded UI strings to
`strings.xml`, `collectAsState()` → `collectAsStateWithLifecycle()`,
playlist batch-queueing, a self-hosted sync backend (explicitly never
required, per `CLAUDE.md`). In-app delete — previously listed here as
only partially done — is now **fully done** as of patch 04.

## File map (current, pre-Kinescope-rename names)

- `MainActivity.kt` — screen composables: `DownloadScreen`,
  `QueueRow`, `EmptyQueueState`, `ConnectivityBanner`, `LibraryRow`,
  `ComposerBar`, `SettingsPanel`/`SettingsSectionHeader`. Also
  `playItem()`/`shareItem()` (Intent-based, both guard
  `ActivityNotFoundException`) and `isYouTubeUrl()`/`extractUrl()`
  (host allowlist).
- `DownloadService.kt` — foreground service; a single background
  worker `Thread` draining a `LinkedBlockingQueue`, restarted on
  demand (see the `startWorkerLocked()` doc comment for the
  race-condition reasoning); `runJob()` does the actual yt-dlp
  `execute()` call, job-id-tag file scanning, and `friendlyError()`
  mapping.
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
- `Theme.kt` — `YtOfflineTheme` (light + dark `ColorScheme`),
  `YtOfflineExtras` (the `success`/`warning`/`surfaceRaised` extension
  colors), the full typography scale, downloadable Google Fonts
  (Inter/Lora) via `font_certs.xml`.
- `ic_launcher_foreground.xml` / `ic_launcher_background.xml` /
  `mipmap-anydpi-v26/ic_launcher*.xml` — adaptive icon, safe-zone
  fixed in patch 03.
- `RELEASE.md` — signing key generation, signed build, install
  instructions. `design.md` — the visual design system, with a
  section on what it approximates and what it deliberately avoids
  (Anthropic's actual fonts/logo/name). `CLAUDE.md` — project ground
  rules. `ROADMAP.md` — the living, checkbox-tracked implementation
  plan (read its top section first).

`minSdk` 29, `compileSdk`/`targetSdk` 35, `versionCode` 7,
`versionName` "1.0.0", Compose BOM `2024.11.00` (Material3 1.3.1),
`youtubedl-android` 0.18.1.

## How to resume in a new conversation

1. Export a fresh repomix XML of the repo (it should already reflect
   patches 01-04 if they were applied and committed — confirm with
   `git log`).
2. Paste it plus this file. `CLAUDE.md`/`ROADMAP.md`/`design.md` are
   nice-to-have if not already covered by the repomix export, but this
   file's "What's actually done vs. still open" section above should
   be enough to know where to pick up.
3. State which of the four "not done" items above to work on next —
   they're meant to happen in that order (Step 7 before Step 5,
   specifically), but say so explicitly, since a new conversation has
   no memory of *why* that order matters otherwise.

## Immediate next step for Claude (in a new conversation)

Continue at **Step 7 (Kinescope rename)** unless told otherwise.
`ROADMAP.md`'s Step 7 section has the specific file-by-file rename
checklist (`applicationId`, `namespace`, `rootProject.name`, package
declarations, `R` class references, `strings.xml` app name, and the
`design.md` line that still says the app "stays YT Offline"). Continue
the established pattern for this project:

- Read the actual current file content before editing — don't assume
  memory of it is accurate; things have changed across 4 patches.
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
    if "Kinescope rename target" in current or "com.kinescope.app" in current:
        print("  [skip] HANDOFF.md: already patched.")
        return
    overwrite(path, NEW_HANDOFF_MD, "HANDOFF.md (full rewrite: patch history, current status, resume instructions)")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 05 (refresh ROADMAP.md summary + rewrite HANDOFF.md)...\n")
    try:
        require_prior_patches()

        print("ROADMAP.md:")
        fix_roadmap_intro()
        fix_roadmap_backlog_delete_item()

        print("\nHANDOFF.md:")
        fix_handoff()
    except PatchError as exc:
        print(f"\nPATCH FAILED: {exc}", file=sys.stderr)
        return 1

    print("\nAll changes applied successfully.")
    print("Documentation only -- no source changes, no need to rebuild.")
    print("Next: Step 7 (Kinescope rename, applicationId com.kinescope.app).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
