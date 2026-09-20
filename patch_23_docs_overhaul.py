#!/usr/bin/env python3
"""
Patch 23 -- Documentation overhaul: Step 5 confirmed working, project
reaches steady state.

The user confirmed patch 22's fix: the app now runs correctly end to
end on a real device (Android 13) -- the startup crash is gone, and
the core install / share-or-paste / queue / download / play loop
works. This patch makes NO app code changes. It folds that
confirmation into every project doc and reshapes them for the new
steady state:

- `ROADMAP.md` -- the original 9-step plan is done or reduced to
  specific, named technical debt. Consolidated from a step-by-step
  checklist (363 lines) down to a single "Technical debt" section.
  Full step-by-step history stays in CHANGELOG.md/HANDOFF.md, which
  this file now points to instead of duplicating.
- `README.md` -- fully rewritten as a proper GitHub-facing README:
  CI status badges, an accurate tech-stack table, and a "Status"
  section reflecting the app's actual working state (patch 15's
  version predates the first successful build and was already stale).
- `HANDOFF.md` -- "Project identity" status, "What's actually done vs.
  still open", and "Immediate next step" rewritten for the new steady
  state. The generic "how to work in this repo" process checklist that
  used to live in "Immediate next step" has moved to the new
  `AGENTS.md` (see below) so it isn't duplicated across two files.
  Two small stale-doc fixes caught while reviewing this file: the file
  map still described `RELEASE.md` as using old `yt-offline` naming
  (patch 20 already renamed it) and described `ROADMAP.md` as a
  "checkbox-tracked implementation plan" (no longer accurate after
  this patch's restructuring).
- `AGENTS.md` (new) -- a process/workflow file for AI coding agents
  working on this repo, following the emerging AGENTS.md convention
  (a plain-Markdown "README for agents", read by Codex/Cursor/Jules/
  etc. as well as Claude). Complements rather than duplicates
  `CLAUDE.md` (ground rules on what the app is/isn't allowed to do).
- `CHANGELOG.md` -- new Patch 23 entry, above Patch 22's.
- `.gitignore` -- added `*.jks.b64` / `*.jks.base64.txt`. Found while
  reviewing the repo for this patch: `kinescope-release.jks.b64` (an
  empty, 0-byte placeholder, currently harmless) is git-tracked and
  wasn't covered by the existing `*.jks`/`*.keystore` rules. Not a
  live leak today, but a real landmine if that filename is ever reused
  to actually hold a base64-encoded keystore. See this patch's delivery
  commands for an optional `git rm --cached` to stop tracking the
  empty file too.

Usage:
    python3 patch_23_docs_overhaul.py

Run from the repository root. Idempotent -- safe to run twice.
"""

import hashlib
import os
import sys

REPO_ROOT = os.getcwd()


class PatchError(RuntimeError):
    pass


def _abs(path):
    return os.path.join(REPO_ROOT, path)


def read(path):
    with open(_abs(path), "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    with open(_abs(path), "w", encoding="utf-8") as f:
        f.write(content)


def guarded_replace(path, old, new, marker=None):
    """
    Replace exactly one occurrence of `old` with `new` in `path`.

    Idempotent: if `marker` (or `new`, when no marker is given) is
    already present in the file, this is a no-op. Raises PatchError
    if `old` isn't found exactly once -- never silently no-ops or
    guesses.
    """
    content = read(path)
    check = marker if marker is not None else new
    if check in content:
        print(f"[skip] {path}: already applied ({marker or 'edit'})")
        return
    count = content.count(old)
    if count == 0:
        raise PatchError(
            f"{path}: anchor text not found. Expected to find:\n"
            f"-----\n{old[:400]}\n-----\n"
            "File may have changed since this patch was written -- "
            "re-check the current content before re-running."
        )
    if count > 1:
        raise PatchError(
            f"{path}: anchor text matched {count} times, expected exactly 1:\n"
            f"-----\n{old[:400]}\n-----"
        )
    write(path, content.replace(old, new, 1))
    print(f"[ok]   {path}: applied ({marker or 'edit'})")


def whole_file_guarded_rewrite(path, new_content, applied_marker, known_good_sha256):
    """
    Replace a file's ENTIRE content, guarded by idempotency only:

    Idempotent: if `applied_marker` (a string unique to `new_content`) is
    already present, skip -- this patch already ran.

    `known_good_sha256` is accepted but NOT enforced as a hard gate: an
    earlier version of this function raised PatchError on any hash
    mismatch, verified locally against a repomix-extracted copy of the
    repo. In the field that check false-positived -- the real file's
    hash (computed by this script, reading straight off the Codespace
    disk) didn't match the hash computed from a repomix export of the
    same, seemingly-unchanged file. Root cause not pinned down (likely
    some normalization repomix's own packing step applies -- e.g.
    trailing-whitespace/line-ending handling -- that isn't present when
    Python reads the raw file), but it means this sha256 pre-check
    can't be trusted against repomix-derived expectations. Since these
    are full-file rewrites with no piece-by-piece content to preserve
    and full history lives in git regardless, a mismatch is now just
    logged, not fatal.
    """
    content = read(path)
    if applied_marker in content:
        print(f"[skip] {path}: already applied")
        return
    actual = hashlib.sha256(content.encode("utf-8")).hexdigest()
    if actual != known_good_sha256:
        print(
            f"[note] {path}: current content's sha256 ({actual}) doesn't match "
            f"this patch's dev-time snapshot ({known_good_sha256}) -- "
            "proceeding anyway (full history is in git if this needs "
            "reverting)."
        )
    write(path, new_content)
    print(f"[ok]   {path}: rewritten")


def create_file_if_missing(path, content, description):
    full = _abs(path)
    if os.path.exists(full):
        existing = read(path)
        if existing == content:
            print(f"[skip] {path}: already exists with expected content")
            return
        raise PatchError(
            f"{path}: already exists with DIFFERENT content than this patch "
            "would write -- refusing to overwrite. Inspect manually."
        )
    os.makedirs(os.path.dirname(full) or ".", exist_ok=True)
    write(path, content)
    print(f"[ok]   {path}: created ({description})")


# ---------------------------------------------------------------------------
# 1. README.md -- full rewrite.
# ---------------------------------------------------------------------------

README_CONTENT = """# Kinescope

[![Build Debug APK](https://github.com/baltic-builds/kinescope/actions/workflows/build-debug.yml/badge.svg)](https://github.com/baltic-builds/kinescope/actions/workflows/build-debug.yml)
[![Build Signed Release APK](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml/badge.svg)](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-29-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![License](https://img.shields.io/badge/license-personal--use--only-lightgrey)

A personal Android app for downloading YouTube videos at home, for
offline viewing during work trips to a network-restricted region.
Sideload-only \u2014 no Google Play, no backend, no required cost.

**Status: working.** Confirmed on a real device (Android 13): install,
share/paste a link, queue a download, play it back offline, and
background persistence all work. Signed release builds succeed via
CI; installing that signed build on a device is the one remaining
open item \u2014 see [Technical debt](#technical-debt).

## What this is

Share a YouTube link into the app (or paste one directly), pick a
quality preset, and it downloads in the background via a foreground
service. The finished file lands in the device's Downloads folder,
ready for offline playback in any video player \u2014 no connectivity
needed once it's downloaded.

## What this explicitly is not

- Not distributed via Google Play \u2014 **sideload only** (install the
  debug or signed APK directly).
- No backend, no account system, no cross-device sync.
- No custom YouTube extraction logic. All extraction goes through
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), via the
  [youtubedl-android](https://github.com/yausername/youtubedl-android)
  wrapper library \u2014 never reverse-engineered independently.
- No required paid services.
- Built for one person's own use, not for general distribution.

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.1.0 |
| UI | Jetpack Compose, Material3 (Compose BOM `2024.11.00` \u2192 Material3 1.3.1) |
| Extraction | [`youtubedl-android`](https://github.com/yausername/youtubedl-android) `0.18.1` (wraps `yt-dlp` + bundled ffmpeg/ffprobe) |
| Build | Gradle `8.10.2`, AGP `8.7.2`, JDK 21 |
| `minSdk` / `compileSdk` / `targetSdk` | 29 / 35 / 35 |
| ABI | `arm64-v8a` only (kept the debug/release APK small \u2014 see [Technical debt](#technical-debt) if you need another) |
| Distribution | Sideloaded APK (debug or signed release), built locally or via GitHub Actions |

## Building

This project is developed in GitHub Codespaces \u2014 there's no Android
Studio GUI or emulator in that environment, so every build has to
succeed headlessly.

1. Open a Codespace on this repo. `.devcontainer/devcontainer.json`
   requests a JDK and installs Gradle automatically on container
   creation via `postCreateCommand`.
2. If `gradlew` isn't present yet (a fresh Codespace, or the
   `postCreateCommand` didn't finish), run `bash .devcontainer/setup.sh`
   manually. It installs the Android SDK command-line tools, accepts
   licenses, installs `platform-tools`/`platform 35`/`build-tools`,
   generates the Gradle wrapper, and pins Gradle to a JDK version it
   actually supports \u2014 see `HANDOFF.md`'s "Key learnings" section for
   why a Codespace can have multiple JDKs and why that last step
   matters.
3. `./gradlew assembleDebug`
4. Install the resulting APK on a device (`adb install
   app/build/outputs/apk/debug/app-debug.apk`, or transfer the file and
   tap it).

**Alternative: build via GitHub Actions.** If adb isn't available, or
downloading the APK through the Codespace browser UI is inconvenient,
use the *Build Debug APK* workflow under this repo's Actions tab
(`.github/workflows/build-debug.yml`) instead of steps 3\u20134 above \u2014 run
it by hand, supply a version name, and download the resulting APK from
the run's Artifacts. No automatic trigger; each run is a deliberate,
manually-versioned build.

**Signed release builds** work the same way via the *Build Signed
Release APK* workflow (`.github/workflows/build-release.yml`), backed
by four repo secrets (a base64-encoded keystore plus its
passwords/alias) \u2014 see `RELEASE.md` for the one-time setup, and for
the local-build alternative (`./gradlew assembleRelease`).

## Documentation map

- **`CLAUDE.md`** \u2014 ground rules for this project (no custom
  extraction, English-only code/docs, no paid services, no Anthropic
  branding).
- **`AGENTS.md`** \u2014 process/workflow conventions for an AI coding
  agent working on this repo (source-of-truth reading order,
  verification discipline, patch-delivery and testing conventions).
- **`ROADMAP.md`** \u2014 current status and open technical debt. The
  original 9-step implementation plan is done; this file tracks what's
  left, not what already shipped.
- **`roadmap.md`** (lowercase) \u2014 a second, separate sprint-based
  audit/plan (S0\u2013S11) from GPT Astra. Queued for **after** every item
  in `ROADMAP.md` above is closed \u2014 see the note near its top for why,
  and don't start it early.
- **`HANDOFF.md`** \u2014 cross-session snapshot: what's done, what's next,
  full patch history, key learnings. Read this first when resuming
  work in a new conversation.
- **`CHANGELOG.md`** \u2014 one entry per patch, newest first: exactly what
  changed and why.
- **`CJM.md`** \u2014 the Customer Journey Map behind every priority
  decision in this project's history.
- **`design.md`** \u2014 the visual design system (colors, typography,
  icon), including an explicit section on what it approximates and
  what it deliberately avoids.
- **`RELEASE.md`** \u2014 signing-key generation and the signed-release
  process.

## Technical debt

See `ROADMAP.md` for the full, current list: confirming the signed
release build on a real device, a handful of adversarial Step 5 checks
not yet individually run (race-condition stress test, error-text
matching against a real broken video, airplane mode, a very large
download), and an optional, unscheduled backlog. Nothing here blocks
normal use of the app.

## License

Personal project, not distributed publicly and not intended for
reuse. No license is granted.
"""

README_SHA256 = "9f28ef09098ac4c46bc0671c665f23625514a0158588edd9811ea93c3ecdc7ad"


def patch_readme():
    whole_file_guarded_rewrite(
        "README.md",
        README_CONTENT,
        applied_marker="Confirmed on a real device (Android 13)",
        known_good_sha256=README_SHA256,
    )


# ---------------------------------------------------------------------------
# 2. ROADMAP.md -- consolidated to a single Technical debt section.
# ---------------------------------------------------------------------------

ROADMAP_CONTENT = """# Roadmap

**Current state (as of patch 23): the original 9-step implementation
plan is done, or reduced to the specific technical debt below.** Steps
1\u20139 \u2014 compile fixes, critical runtime fixes, product-quality fixes,
device install/testing, design system v2, the Kinescope rename,
documentation, and signed release \u2014 are all complete or closed out to
named items in "Technical debt" below. `./gradlew assembleDebug`
succeeds locally and via CI; a signed release build succeeds via CI;
**the app is confirmed working on a real device** (Android 13):
install, permissions, share/paste a link, queue and complete a
download, play it back, background persistence. For the full
step-by-step history of how it got here \u2014 including two dead ends
that turned out to matter (a fabricated Compose BOM version, a
plausible-but-wrong `UpdateChannel` location) \u2014 see `CHANGELOG.md`
(one entry per patch) and `HANDOFF.md` (the fuller narrative + key
learnings). **This file now tracks only what's left open, not what
already shipped.**

**Decided, unchanged:** `applicationId`/`namespace` is
`com.kinescope.app`. No Google Play distribution \u2014 sideload only. No
custom YouTube extraction \u2014 everything goes through `yt-dlp` via
`youtubedl-android`. See `CLAUDE.md` for the full ground rules.

---

## Technical debt

Nothing below blocks normal use of the app. Roughly ordered by how
much it'd actually matter if it bit you.

### Not yet individually confirmed on a real device

Basic functionality is confirmed working (see above), but these more
adversarial checks from the original Step 5 checklist haven't been
individually gone through and reported back yet. "The app works"
means the core loop works, not that these specific edge cases have
been exercised:

- [ ] **Race-condition stress test:** queue 3\u20134 videos in quick
      succession (within a couple seconds of each other, the realistic
      "prepping for a trip" pattern) and confirm every single one
      actually starts and completes. This is the exact scenario
      `DownloadService`'s worker-restart fix (patch 01) was written
      for.
- [ ] **`friendlyError()` against a real broken video:** try an
      age-restricted or private video and confirm the error-text
      matching (e.g. "Sign in to confirm you're not a bot" for
      bot-detection) still matches current yt-dlp output. Flagged
      since the original review as "needs a real device to settle,"
      and still does.
- [ ] **Airplane mode at queue time:** confirm the app degrades
      gracefully rather than crashing (doubles as a regression check
      for the Step 3 catch-all exception handler).
- [ ] **A very large/slow download:** confirm it doesn't get killed
      mid-transfer. If it ever does on Android 14+ specifically, note
      that `dataSync`-type foreground services have a rolling
      execution-time budget (hours/day, not indefinite) \u2014
      informational, unlikely to matter for typical video lengths.

### Signed release

- [ ] Confirm the **signed** release APK (not just the debug build)
      actually installs and opens on a real device. CI produces it
      successfully (`.github/workflows/build-release.yml`, confirmed
      patch 21); this is the one step that needs a device, not just a
      green Actions run.

### Optional backlog (unscheduled, user-prioritized)

- [ ] Persist download queue state (small local DB or file) so a
      process kill doesn't silently lose in-flight job status with
      zero UI indication \u2014 `DownloadQueueBus` is a bare in-memory
      `StateFlow` today; reasonable for v1, worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case
      an aggressive OEM battery manager (Xiaomi/Huawei/Samsung-class
      skins do this even to foreground services) OOM-kills the process
      mid-download.
- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) \u2014 purely cosmetic; the icon
      just won't participate in themed-icon tinting without it.
- [ ] Migrate `collectAsState()` to `collectAsStateWithLifecycle()` in
      `MainActivity.kt` \u2014 fine as-is for a single-screen app, revisit
      only if a second screen (e.g. a dedicated Library screen) is
      added.
- [ ] Migrate remaining raw `Thread`/`Handler(Looper.getMainLooper())`
      usage (`YtOfflineApp.kt`, `MainActivity.kt`'s `runUpdate()`) to
      `rememberCoroutineScope()` + `withContext(Dispatchers.IO)`, for
      consistency with the coroutines-based fix already applied to
      `DownloadService` in Step 3.
- [ ] Externalize remaining hardcoded UI strings ("Queue", "Library",
      `friendlyError()` messages, Settings labels) into `strings.xml`
      \u2014 zero functional impact for a personal single-language app,
      purely a "nice to have if you're already touching that code."
- [ ] Batch-queue a full playlist by URL, if that becomes a real use
      case.
- [ ] Self-hosted backend for cross-device queue sync \u2014 explicitly
      optional per `CLAUDE.md`'s zero-required-cost rule, never a
      requirement.
- [ ] Note for future Codespace rebuilds: `.devcontainer/setup.sh`
      scrapes the Android cmdline-tools download URL from a live
      webpage rather than a pinned version \u2014 fails safely (a loud
      error with instructions) but means a rebuilt Codespace could
      silently pick up a newer cmdline-tools version than the original
      build did. If a *rebuilt* Codespace ever behaves differently
      than the original for no apparent code reason, check this
      script's output first.

In-app delete for library entries is fully done (patch 04) \u2014 not
debt, just noted here since it used to live in this list.

---

## After this file: `roadmap.md` (lowercase)

A separate, newer sprint-based audit/plan (S0\u2013S11, findings F01\u2013F42)
from GPT Astra, added by the user. Deliberately queued for **after**
every item above is closed \u2014 do not merge it into this document or
start it early. Once both this `ROADMAP.md` and `roadmap.md` are fully
executed, both files get deleted.

---

## Process note (still true)

The original build-verify-after-every-phase discipline in `CLAUDE.md`
was intentionally overridden by explicit user instruction during
initial development ("keep going, test everything at the end"). That
was a valid call for a solo prototyping burst, but it's also *why*
this document's now-closed Steps 1\u20134 existed at all \u2014 nearly every
Critical/High finding in this project's early history was a direct
consequence of code that was never compiled, let alone run. Going
forward: **prefer compiling (and, where practical, running) after
each meaningful change**, not just at the end of a long unattended
session. See `AGENTS.md` for the fuller process this project follows.
"""

ROADMAP_SHA256 = "bd6eaeb00c33bcd76cd6c8f7e86ee2505aa724afb22697504f6669225b62c919"


def patch_roadmap():
    whole_file_guarded_rewrite(
        "ROADMAP.md",
        ROADMAP_CONTENT,
        applied_marker="Current state (as of patch 23)",
        known_good_sha256=ROADMAP_SHA256,
    )


# ---------------------------------------------------------------------------
# 3. AGENTS.md -- new file.
# ---------------------------------------------------------------------------

AGENTS_CONTENT = """# AGENTS.md \u2014 How to work on this repo

A process guide for any AI coding agent (Claude or otherwise) picking
up work on Kinescope. This complements rather than duplicates
`CLAUDE.md` (project ground rules \u2014 what the app is and isn't allowed
to do) and `HANDOFF.md` (a point-in-time snapshot of what's done and
what's next). This file is about *how* to work here, not *what* the
current state is \u2014 that's always `HANDOFF.md`'s job, and it changes
every patch.

## Read this first, in this order

1. **`CLAUDE.md`** \u2014 ground rules. Personal use only, no custom
   extraction, English-only docs, no paid services, no Anthropic
   branding. Don't deviate without being asked.
2. **`HANDOFF.md`** \u2014 what's actually done, what's actually open, the
   full patch-by-patch narrative, and hard-won key learnings. Read
   this before touching anything; a fresh session has no memory of
   *why* things are the way they are otherwise.
3. **`ROADMAP.md`** \u2014 current status and open technical debt. Short by
   design; the step-by-step history of how each item got closed lives
   in `CHANGELOG.md`, not here.
4. **`CHANGELOG.md`** \u2014 one entry per patch, newest first: exactly
   what changed and why, including findings that turned out wrong
   (dead ends are worth keeping on record, not just successes).

Never assume memory from earlier in a conversation is still accurate,
especially several patches in. Read the actual current file content
before editing anything.

## Environment

- Development happens exclusively in **GitHub Codespaces** \u2014 no
  Android Studio GUI, no emulator, no connected device. Every build
  has to succeed headlessly.
- Build: `./gradlew assembleDebug` (or `assembleRelease`, if
  `keystore.properties` is present \u2014 see `RELEASE.md`).
- If `gradlew` is missing or the devcontainer's `postCreateCommand`
  didn't finish, run `bash .devcontainer/setup.sh` manually.
- CI: `.github/workflows/build-debug.yml` and `build-release.yml`,
  both manual (`workflow_dispatch`) triggers, hand-versioned per run.
- Real-device testing is manual, on the user's own phone, and always
  needs an explicit report back \u2014 see "What 'done' means" below.

## Verification discipline

Don't guess at a library/API/version from general familiarity. This
project has been burned twice by exactly that: a fabricated Compose
BOM version that didn't exist (patch 01), and a plausible-but-wrong
guess at where `UpdateChannel` lived in `youtubedl-android` \u2014 a README
snippet had silently dropped the qualifying outer-class name, and the
guess held until a real compile error contradicted it (patch 14).
Whenever a claim about a third-party library, framework API, or
version matters for correctness:

- Prefer the library's own tagged source (`git clone` + checkout the
  exact pinned version) or official documentation over a README
  snippet, a blog post, or an old sample app.
- If a real error contradicts an earlier "verified" assumption,
  re-verify against the primary source directly \u2014 don't re-read the
  same secondary source that produced the wrong conclusion the first
  time.
- This applies to platform APIs too, not just third-party libraries \u2014
  patch 22's fix (a Compose `painterResource()` crash on a framework
  `AnimatedVectorDrawable`) was confirmed against `painterResource()`'s
  own documentation before being called the root cause, not assumed
  from the stack trace alone.

## Patch delivery process

Every code or doc change is delivered as a single, self-contained
Python patch script, not as inline instructions to run by hand:

1. **Exact-match guarded edits.** Every edit raises a clear error if
   its anchor text isn't found, rather than silently no-op'ing or
   guessing at a fuzzy match.
2. **Idempotent.** Safe to run twice without duplicating content \u2014
   check for a distinguishing marker of the change already being
   applied and skip if so.
3. **A module docstring** explaining what the patch does and why,
   referencing the relevant `ROADMAP.md`/`CHANGELOG.md` item.
4. **Tested before delivery, every time:** extract the current repo
   into a local working copy, run the script against it, diff the
   result against intent, check bracket/brace balance in any touched
   source files, validate any touched XML is well-formed, and run the
   script a second (and, for anything touching a file several past
   patches also edit, a third+) time to confirm idempotency. Never
   hand over an untested script.
5. **Docs updated in the same patch as the code they describe** \u2014
   `ROADMAP.md`'s technical-debt list and `CHANGELOG.md`'s new entry
   land together with the change, not as a follow-up.
6. **Alongside every patch script:** the command to run it,
   `./gradlew assembleDebug` as a sanity check, and the git
   add/commit/push commands with a descriptive commit message.
7. **Scoped to one coherent unit of work** \u2014 one `ROADMAP.md` item, or
   a tightly related group \u2014 rather than sprawling across unrelated
   changes. Work in sprints across a conversation (a batch of related
   items handled back-to-back without stopping for approval between
   them), then deliver **one** patch script per sprint, not one per
   tiny step.

**A later patch can silently break an earlier patch's own idempotency
check**, if the earlier patch's "already applied" detection doesn't
also recognize the later patch's marker (this happened repeatedly
across patches 12/14/16/17/19 \u2014 see `HANDOFF.md`'s "Key learnings").
Any patch touching a file several previous patches already edited
(`ROADMAP.md`, `HANDOFF.md`, `CHANGELOG.md` especially) should budget
time to also patch its immediate predecessor's idempotency check, and
run the full patch chain multiple times from a clean copy to catch it
\u2014 a single dry run isn't enough.

**Never write a machine-specific path** (a Codespace's local
filesystem layout, an absolute SDK/JDK location, etc.) into a file
that gets `git add`ed. Anything derived from the current environment
belongs in a user-level/local config location instead \u2014 patch 18
exists because patch 12 got this wrong once already.

## What "done" means

Nothing gets marked done, confirmed, or closed without an explicit
report from the user that they actually ran it and it actually
worked. "The app compiles" is not "the app works"; "CI succeeded" is
not "it installs on a real device." This project's entire `ROADMAP.md`
existed, for most of its life, because seven early development phases
were written with no intermediate compilation at all \u2014 don't recreate
that gap in a different shape by assuming a later stage is fine just
because an earlier one was.

## Language

All code, code comments, commit messages, and technical documentation
(`ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `design.md`, this file,
etc.) are written in English, regardless of what language the
conversation itself is in. Conversational replies to the user can be
in whatever language they write in.

## Scope discipline

Personal, single-user app. No feature should introduce a required
paid service, a backend, or a dependency on Anthropic branding/name.
Extraction always goes through `yt-dlp` (via `youtubedl-android`) \u2014
never write custom YouTube extraction logic. See `CLAUDE.md` for the
full ground rules.
"""


def patch_agents_md():
    create_file_if_missing(
        "AGENTS.md",
        AGENTS_CONTENT,
        "process/workflow conventions for an AI coding agent",
    )


# ---------------------------------------------------------------------------
# 4. CHANGELOG.md -- new Patch 23 entry, above Patch 22's.
# ---------------------------------------------------------------------------

CHANGELOG_PATCH_23 = """## Patch 23 \u2014 Documentation overhaul: Step 5 confirmed working, project reaches steady state

The user confirmed patch 22's fix: the app now runs correctly end to
end on a real device (Android 13) \u2014 the startup crash is gone, and
the core install/share-or-paste/queue/download/play loop works. This
patch is documentation only (no app code changes).

### Changed
- `README.md` \u2014 fully rewritten: build-status badges for both GitHub
  Actions workflows, an accurate tech-stack table (Kotlin 2.1.0,
  Compose BOM `2024.11.00`/Material3 1.3.1, `youtubedl-android`
  0.18.1, Gradle 8.10.2/AGP 8.7.2, `minSdk` 29/`compileSdk`/`targetSdk`
  35, `arm64-v8a`-only), and a "Status" section reflecting the app's
  actual working state instead of the pre-first-build snapshot patch
  15 originally wrote.
- `ROADMAP.md` \u2014 consolidated. The original 9-step plan is done or
  reduced to specific, named technical debt; collapsed from a 363-line
  step-by-step checklist to a short "Current state" summary plus a
  single "Technical debt" section (the one remaining Step 9
  device-confirm item, Step 5's not-yet-individually-confirmed
  adversarial checks, the Backlog items, and the still-queued
  `roadmap.md` lowercase audit). Full step-by-step history stays in
  `CHANGELOG.md`/`HANDOFF.md`, which this file now points to rather
  than duplicating.
- `HANDOFF.md` \u2014 "Project identity", "What's actually done vs. still
  open", and "Immediate next step" rewritten for the new steady state:
  Step 5's crash-fix is confirmed by the user; basic on-device
  functionality (install, permissions, share/paste, download, play,
  background persistence) is confirmed working; the adversarial edge
  cases (race-condition stress test, `friendlyError()` against a real
  broken video, airplane mode, a very large download) remain open,
  unconfirmed technical debt, not assumed to have passed just because
  the app runs now. The generic "how to work in this repo" checklist
  that used to live in "Immediate next step" has moved to the new
  `AGENTS.md` instead of staying duplicated across two files. Also
  fixed two stale lines caught while reviewing this file: the file map
  still described `RELEASE.md` as using old `yt-offline` naming
  (patch 20 already renamed it) and described `ROADMAP.md` as a
  "checkbox-tracked implementation plan" (no longer accurate after
  this patch). Patch-by-patch narrative (patches 01\u201322) is unchanged.
- `.gitignore` \u2014 added `*.jks.b64` and `*.jks.base64.txt`. Found while
  reviewing the repo for this patch: `kinescope-release.jks.b64` (an
  empty, 0-byte placeholder, currently harmless) is tracked in git and
  wasn't covered by the existing `*.jks`/`*.keystore` rules \u2014 if that
  filename were ever reused to actually hold a base64-encoded
  keystore (e.g. following `RELEASE.md`'s manual base64 steps but
  writing the output inside the repo folder by habit), it would
  commit the signing key straight into git history. Not a live leak
  today, but a real landmine for next time.

### Added
- `AGENTS.md` \u2014 a process/workflow file for AI coding agents working
  on this repo (source-of-truth reading order, verification
  discipline, patch-script delivery/testing conventions, scope
  discipline), following the emerging AGENTS.md convention. Complements
  rather than duplicates `CLAUDE.md` (project ground rules/constraints)
  and `HANDOFF.md` (state snapshot).

### Findings (closed)
- **`kinescope-release.jks.b64` is git-tracked and not gitignored.**
  Currently empty and harmless, but the filename invites exactly the
  kind of accidental-secret-commit `RELEASE.md`'s own base64 step
  warns about. Fixed via `.gitignore` (see above). Recommended,
  not automated by this patch: `git rm --cached kinescope-release.jks.b64`
  to stop tracking the empty file too (see delivery commands).

"""


def patch_changelog():
    path = "CHANGELOG.md"
    anchor = "## Patch 22 \u2014 Fixed a startup crash found on the first real-device launch (Step 5)\n"
    guarded_replace(
        path,
        anchor,
        CHANGELOG_PATCH_23 + anchor,
        marker="## Patch 23 \u2014 Documentation overhaul: Step 5 confirmed working, project reaches steady state",
    )


# ---------------------------------------------------------------------------
# 5. .gitignore -- close the jks.b64 gap.
# ---------------------------------------------------------------------------

def patch_gitignore():
    path = ".gitignore"
    old = (
        "keystore.properties\n"
        "*.jks\n"
        "*.keystore"
    )
    new = (
        "keystore.properties\n"
        "*.jks\n"
        "*.keystore\n"
        "*.jks.b64\n"
        "*.jks.base64.txt\n"
    )
    guarded_replace(path, old, new, marker="*.jks.b64")


# ---------------------------------------------------------------------------
# 6. HANDOFF.md -- several targeted section rewrites.
# ---------------------------------------------------------------------------

def patch_handoff():
    path = "HANDOFF.md"

    # 6a. Top-of-file pointer: mention AGENTS.md alongside the other docs.
    old_pointer = (
        "doesn't already have repo access, also attach a fresh repomix export\n"
        "(or paste `CLAUDE.md`, `ROADMAP.md`, `CHANGELOG.md`, `CJM.md`,\n"
        "`roadmap.md`, `design.md`, and `RELEASE.md` directly).\n"
    )
    new_pointer = (
        "doesn't already have repo access, also attach a fresh repomix export\n"
        "(or paste `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md`, `CHANGELOG.md`,\n"
        "`CJM.md`, `roadmap.md`, `design.md`, and `RELEASE.md` directly).\n"
    )
    guarded_replace(path, old_pointer, new_pointer, marker="`CLAUDE.md`, `AGENTS.md`, `ROADMAP.md`")

    # 6b. "Project identity" status paragraph.
    old_status = (
        "**Milestone: the first successful `./gradlew assembleDebug` in this\n"
        "project's history was achieved via patches 01-14.** The\n"
        "`.github/workflows/build-debug.yml` CI path (patch 16) failed on its\n"
        "first real run due to a leaked Codespace-only JDK path; patch 18 fixed\n"
        "it, and the user has since confirmed a successful GitHub Actions build\n"
        "and downloaded a debug APK. **Per the user's explicit direction (patch\n"
        "19), the next step is Step 9 (signed release)** -- ahead of Step 5's\n"
        "manual on-device checklist being individually gone through and\n"
        "reported back. **Patch 20 delivered Step 9's CI workflow**\n"
        "(`.github/workflows/build-release.yml`), and **patch 21 confirms it\n"
        "actually works**: a real signed release build succeeded via GitHub\n"
        "Actions after fixing two real gotchas hit along the way (PKCS12's\n"
        "same-password requirement, and the default Codespaces `gh` token\n"
        "lacking rights to write repo secrets -- both in `CHANGELOG.md`'s Patch\n"
        "21 entry) and trimming the ~200MB APK down via `ndk.abiFilters`. What's\n"
        "still open: confirming the signed APK actually installs and opens on a\n"
        "real device -- that's the one item left before Step 9 counts as fully\n"
        "done. **Patch 22** separately fixed a startup crash hit on the user's\n"
        "first real-device launch attempt for Step 5 (unrelated code path, a\n"
        "Compose icon-loading bug, not a signing/CI issue) -- see \"Immediate\n"
        "next step\" below.\n"
    )
    new_status = (
        "**Milestone: the first successful `./gradlew assembleDebug` in this\n"
        "project's history was achieved via patches 01-14.** The\n"
        "`.github/workflows/build-debug.yml` CI path (patch 16) failed on its\n"
        "first real run due to a leaked Codespace-only JDK path; patch 18 fixed\n"
        "it, and the user has since confirmed a successful GitHub Actions build\n"
        "and downloaded a debug APK. **Patch 20 delivered Step 9's CI workflow**\n"
        "(`.github/workflows/build-release.yml`), and **patch 21 confirmed it\n"
        "actually works**: a real signed release build succeeded via GitHub\n"
        "Actions after fixing two real gotchas hit along the way (PKCS12's\n"
        "same-password requirement, and the default Codespaces `gh` token\n"
        "lacking rights to write repo secrets -- both in `CHANGELOG.md`'s Patch\n"
        "21 entry) and trimming the ~200MB APK down via `ndk.abiFilters`. **Patch\n"
        "22** fixed a startup crash hit on the user's first real-device launch\n"
        "attempt (a Compose icon-loading bug in `EmptyQueueState`, unrelated to\n"
        "signing/CI), and **patch 23 (this one) confirms the fix**: the user\n"
        "reports the app now runs correctly end to end on a real device (Android\n"
        "13) -- install, share/paste a link, queue and complete a download, play\n"
        "it back, background persistence. **The original 9-step plan is now done\n"
        "or reduced to specific, named technical debt** -- see `ROADMAP.md`'s\n"
        "\"Technical debt\" section for the current, complete list (the signed-APK\n"
        "device-confirm is the one Step 9 item still open; a handful of more\n"
        "adversarial Step 5 checks -- the race-condition stress test,\n"
        "`friendlyError()` against a real broken video, airplane mode, a very\n"
        "large download -- haven't been individually gone through yet either).\n"
    )
    guarded_replace(path, old_status, new_status, marker="patch 23 (this one) confirms the fix")

    # 6c. Patch-by-patch narrative: add the Patch 23 bullet after Patch 22's.
    old_narrative_tail = (
        "  involved. Unrelated to Step 9 / CI signing; this is purely a Step 5\n"
        "  finding.\n"
        "\n"
        "**Version-compatibility note worth remembering:**"
    )
    new_narrative_tail = (
        "  involved. Unrelated to Step 9 / CI signing; this is purely a Step 5\n"
        "  finding.\n"
        "- **Patch 23** -- Documentation-only: the user confirmed patch 22's fix\n"
        "  works (the app runs correctly end to end on a real device).\n"
        "  Folded that confirmation into every project doc, consolidated\n"
        "  `ROADMAP.md`'s original 9-step plan (now functionally complete) into\n"
        "  a single \"Technical debt\" section, rewrote `README.md` as a proper\n"
        "  GitHub-facing README with CI status badges, and added `AGENTS.md` --\n"
        "  a process/workflow file for AI coding agents, complementing\n"
        "  `CLAUDE.md`'s ground rules. Also found and fixed a real, if currently\n"
        "  harmless, security gap: `kinescope-release.jks.b64` (an empty\n"
        "  placeholder) is git-tracked and wasn't covered by `.gitignore`'s\n"
        "  existing `*.jks`/`*.keystore` rules -- fixed by adding\n"
        "  `*.jks.b64`/`*.jks.base64.txt`.\n"
        "\n"
        "**Version-compatibility note worth remembering:**"
    )
    guarded_replace(path, old_narrative_tail, new_narrative_tail, marker="- **Patch 23** -- Documentation-only")

    # 6d. "What's actually done vs. still open" -- rewritten, DRY against ROADMAP.md.
    old_done_section = (
        "## What's actually done vs. still open\n"
        "\n"
        "Read `ROADMAP.md`'s top section first -- it has the authoritative,\n"
        "up-to-date status summary and the correct execution order (which does\n"
        "**not** match the document's own Step numbering). As of this snapshot:\n"
        "\n"
        "**Done:** Steps 1, 2 (including Appendix finding #11, now fully\n"
        "confirmed by the actual successful compile -- not just pre-verified),\n"
        "3, 4, 6 (6.1-6.6; 6.7 -- an optional monochrome adaptive-icon layer for\n"
        "Android 13+ themed icons -- is still skipped, opt-in only, not\n"
        "required), 7 (Kinescope rename), 8 (`CJM.md`, patch 17 -- the one\n"
        "remaining item, \"keep this document current,\" is ongoing by nature,\n"
        "not a one-time task), and the environment/build-setup work that had to\n"
        "happen before Step 5 could even start (patches 07-14: a working,\n"
        "re-runnable `setup.sh`, a Gradle/JDK pin that actually works in this\n"
        "Codespace, and every compile error fixed). **`./gradlew assembleDebug`\n"
        "now succeeds**, both locally and via the `.github/workflows/build-debug.yml`\n"
        "GitHub Actions workflow (patch 16) -- **confirmed by a real,\n"
        "successful Actions run** after patch 18 fixed a Codespace-only JDK path\n"
        "that had leaked into the committed `gradle.properties` and broke the\n"
        "workflow's first attempt. The user has downloaded a debug build via\n"
        "that path.\n"
        "\n"
        "**A note on APK size, since the user mentioned the debug build feels\n"
        "\"heavy\":** expected, not a bug -- debug builds include debug symbols\n"
        "and skip any size optimization. A release build (Step 9) won't\n"
        "necessarily be dramatically smaller either, though: `isMinifyEnabled =\n"
        "false` on the release build type was a deliberate Step 1 decision\n"
        "(avoids R8 breaking reflection-heavy coroutine/yt-dlp-wrapper code),\n"
        "and `youtubedl-android`'s bundled native binaries (not app code) likely\n"
        "dominate APK size regardless. `ROADMAP.md`'s Backlog has an\n"
        "never-done, optional `ndk.abiFilters` trim (drop `x86`/`x86_64` if the\n"
        "target phone is arm64) as the one concrete lever if size becomes an\n"
        "actual problem worth spending time on -- don't assume it's needed\n"
        "without the user asking.\n"
        "\n"
        "**Not done, and the order changed as of patch 19 by explicit user\n"
        "decision:**\n"
        "\n"
        "1. **Step 9 -- Signed release.** CI infrastructure delivered (patch\n"
        "   20): `.github/workflows/build-release.yml` builds a signed release\n"
        "   APK from four repo secrets, mirroring `build-debug.yml`. What's\n"
        "   left is entirely on the user's side and can't be advanced further\n"
        "   from here: (a) generate the release keystore and add the\n"
        "   `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`\n"
        "   repo secrets (`RELEASE.md`'s \"CI build\" section has the exact\n"
        "   steps), then (b) trigger the workflow once and confirm the APK it\n"
        "   produces actually installs and opens on a real device. Only (b)\n"
        "   flips Step 9 to done -- same standard `build-debug.yml` was held to\n"
        "   (patch 18). Separately, and still true regardless of CI\n"
        "   infrastructure: a signed release is built from the same code Step\n"
        "   5 would have exercised, so anything that checklist would have\n"
        "   caught (the race-condition stress test, `friendlyError()` against\n"
        "   real yt-dlp output, airplane-mode behavior) is genuinely still\n"
        "   unverified. `RELEASE.md`'s old `yt-offline` naming (keystore\n"
        "   filename/alias, GitHub release title) has been renamed to\n"
        "   `kinescope` as part of patch 20.\n"
        "2. **Step 5 -- Device install + manual testing.** Not blocking Step 9\n"
        "   anymore, but still open and still worth doing when there's a chance\n"
        "   -- `ROADMAP.md`'s Step 5 section has the full checklist, including\n"
        "   explicitly stress-testing the `DownloadService` race-condition fix\n"
        "   from patch 01 (queue several videos in quick succession) and\n"
        "   checking `friendlyError()`'s string-matching against real current\n"
        "   yt-dlp error text, which genuinely needs a real device and can't be\n"
        "   settled by reading code. **Patch 22** fixed a startup crash hit on\n"
        "   the very first launch attempt (see below) -- the checklist above\n"
        "   needs a full re-run from item 1 with the patched build, since\n"
        "   nothing on it was actually exercised before the crash.\n"
        "3. **`roadmap.md` (lowercase) -- GPT Astra's audit/plan.** A separate,\n"
        "   newer sprint-based document (S0-S11, findings F01-F42). Explicitly\n"
        "   queued for **after** Step 9 above is fully done -- don't start it\n"
        "   early or merge it into this `ROADMAP.md`. Once both roadmaps are\n"
        "   fully executed, delete both files.\n"
        "\n"
        "**Backlog (optional, unscheduled -- see `ROADMAP.md`'s Backlog section\n"
        "for the full list with reasoning):** persisting queue state across a\n"
        "process kill, orphaned-temp-file cleanup on service start, migrating\n"
        "remaining `Thread`/`Handler` usage to coroutines for consistency with\n"
        "`DownloadService`'s own fix, externalizing hardcoded UI strings to\n"
        "`strings.xml`, `collectAsState()` -> `collectAsStateWithLifecycle()`,\n"
        "playlist batch-queueing, a self-hosted sync backend (explicitly never\n"
        "required, per `CLAUDE.md`). In-app delete is fully done as of patch 04.\n"
    )
    new_done_section = (
        "## What's actually done vs. still open\n"
        "\n"
        "Read `ROADMAP.md`'s top section first -- as of patch 23 it's a short\n"
        "\"current state\" summary plus a single, consolidated \"Technical debt\"\n"
        "list (the original 9-step plan's checklists don't live there\n"
        "separately anymore; `CHANGELOG.md` has the full step-by-step history\n"
        "instead). As of this snapshot:\n"
        "\n"
        "**Done:** Steps 1-4, 6 (6.1-6.6; 6.7 -- an optional monochrome\n"
        "adaptive-icon layer -- moved to Technical debt, opt-in only, not\n"
        "required), 7 (Kinescope rename), 8 (`CJM.md`, patch 17 -- \"keep this\n"
        "document current\" is ongoing by nature, not a one-time task), and the\n"
        "environment/build-setup work that had to happen before Step 5 could\n"
        "even start (patches 07-14). **`./gradlew assembleDebug` succeeds**,\n"
        "both locally and via `.github/workflows/build-debug.yml` --\n"
        "**confirmed by a real, successful Actions run** (patch 18). **Step 9's\n"
        "CI infrastructure is confirmed working too**: a real signed release\n"
        "build succeeded via `.github/workflows/build-release.yml` (patch 21).\n"
        "**Step 5's core functionality is now confirmed on a real device**\n"
        "(Android 13, patch 22's crash-fix + the user's patch-23 confirmation):\n"
        "install, permissions, share/paste a link, queue and complete a\n"
        "download, play it back via the system player, background persistence.\n"
        "\n"
        "**A note on APK size, since the user mentioned an early debug build\n"
        "felt \"heavy\":** expected for a debug build (debug symbols, no size\n"
        "optimization), not a bug. The signed release build was trimmed from\n"
        "~200MB to something much smaller by restricting `ndk.abiFilters` to\n"
        "`arm64-v8a` only (patch 21) -- `youtubedl-android`'s bundled\n"
        "Python/ffmpeg/ffprobe/QuickJS native libraries, multiplied per ABI,\n"
        "dominate APK size regardless of app code; `isMinifyEnabled = false`\n"
        "on the release build type is a deliberate, unrelated Step 1 decision\n"
        "(avoids R8 breaking reflection-heavy coroutine/yt-dlp-wrapper code).\n"
        "\n"
        "**Still open -- see `ROADMAP.md`'s \"Technical debt\" section for the\n"
        "authoritative, current list, not this summary:**\n"
        "\n"
        "1. **Confirm the signed release APK** (not just the debug build)\n"
        "   installs and opens on a real device -- the one Step 9 item CI\n"
        "   success alone doesn't cover.\n"
        "2. **A handful of more adversarial Step 5 checks**, not individually\n"
        "   confirmed even though basic functionality now is: the\n"
        "   race-condition stress test (3-4 videos queued in quick\n"
        "   succession), `friendlyError()`'s string-matching against a real\n"
        "   broken/age-restricted/private video, airplane-mode behavior at\n"
        "   queue time, and a very large/slow download. None of these are\n"
        "   assumed to pass just because the app runs now -- say so plainly if\n"
        "   anything here turns out to matter.\n"
        "3. **`roadmap.md` (lowercase) -- GPT Astra's audit/plan.** Still\n"
        "   queued for **after** everything above is closed. Don't start it\n"
        "   early or merge it into `ROADMAP.md`. Once both roadmaps are fully\n"
        "   executed, delete both files.\n"
        "4. **The optional backlog** (queue-state persistence across a process\n"
        "   kill, orphaned temp-file cleanup, `Thread`/`Handler` ->\n"
        "   coroutines migration, `strings.xml` externalization,\n"
        "   `collectAsState()` -> `collectAsStateWithLifecycle()`, playlist\n"
        "   batch-queueing, a self-hosted sync backend -- never required, per\n"
        "   `CLAUDE.md`) -- unscheduled, user-prioritized, see `ROADMAP.md` for\n"
        "   the full list with reasoning. In-app delete is fully done (patch\n"
        "   04).\n"
    )
    guarded_replace(path, old_done_section, new_done_section, marker="patch 22's crash-fix + the user's patch-23 confirmation")

    # 6e. File map -- add ic_download.xml, fix two stale descriptions.
    old_filemap_icon = (
        "- `ic_launcher_foreground.xml` / `ic_launcher_background.xml` /\n"
        "  `mipmap-anydpi-v26/ic_launcher*.xml` -- adaptive icon, safe-zone\n"
        "  fixed in patch 03; an invalid `--` inside a comment fixed in patch\n"
        "  13.\n"
    )
    new_filemap_icon = (
        "- `ic_launcher_foreground.xml` / `ic_launcher_background.xml` /\n"
        "  `mipmap-anydpi-v26/ic_launcher*.xml` -- adaptive icon, safe-zone\n"
        "  fixed in patch 03; an invalid `--` inside a comment fixed in patch\n"
        "  13.\n"
        "- `res/drawable/ic_download.xml` -- small hand-authored static vector\n"
        "  (arrow + tray) used by `EmptyQueueState`'s icon; added in patch 22\n"
        "  to replace a framework `AnimatedVectorDrawable` that Compose's\n"
        "  `painterResource()` can't load.\n"
    )
    guarded_replace(path, old_filemap_icon, new_filemap_icon, marker="res/drawable/ic_download.xml` -- small hand-authored")

    old_filemap_docs = (
        "- `RELEASE.md` -- signing key generation, signed build, install\n"
        "  instructions; still uses the old `yt-offline` name in places\n"
        "  (Step 9 scope, not touched by patch 06). `design.md` -- the visual\n"
        "  design system, with a section on what it approximates and what it\n"
        "  deliberately avoids (Anthropic's actual fonts/logo/name); its \"YT\n"
        "  Offline\" mentions are now \"Kinescope\". `CLAUDE.md` -- project ground\n"
        "  rules. `ROADMAP.md` -- the living, checkbox-tracked implementation\n"
        "  plan (read its top section first; as of patch 16 it only carries\n"
        "  detail for what's still open -- completed Steps point to\n"
        "  `CHANGELOG.md`). `CHANGELOG.md` -- terse per-patch \"what shipped\"\n"
        "  record, newest first (patch 17). `CJM.md` -- the five-stage Customer\n"
        "  Journey Map behind Steps 1/3/4's priority ordering (patch 17).\n"
        "  `roadmap.md` -- GPT Astra's sprint-based plan, queued for after\n"
        "  `ROADMAP.md`. `.github/workflows/build-debug.yml` -- manual GitHub\n"
        "  Actions workflow that builds and uploads a versioned debug APK\n"
        "  (patch 16).\n"
    )
    new_filemap_docs = (
        "- `RELEASE.md` -- signing key generation, signed build (local or CI),\n"
        "  install instructions; renamed from the old `yt-offline` naming to\n"
        "  `kinescope` in patch 20. `design.md` -- the visual design system,\n"
        "  with a section on what it approximates and what it deliberately\n"
        "  avoids (Anthropic's actual fonts/logo/name); its \"YT Offline\"\n"
        "  mentions are now \"Kinescope\". `CLAUDE.md` -- project ground rules.\n"
        "  `AGENTS.md` -- process/workflow conventions for an AI coding agent\n"
        "  working on this repo (added patch 23). `ROADMAP.md` -- current\n"
        "  status and open technical debt (restructured patch 23; the\n"
        "  step-by-step history of completed work lives in `CHANGELOG.md`/this\n"
        "  file instead). `CHANGELOG.md` -- terse per-patch \"what shipped\"\n"
        "  record, newest first (patch 17). `CJM.md` -- the five-stage Customer\n"
        "  Journey Map behind Steps 1/3/4's priority ordering (patch 17).\n"
        "  `roadmap.md` -- GPT Astra's sprint-based plan, queued for after\n"
        "  `ROADMAP.md`. `.github/workflows/build-debug.yml` -- manual GitHub\n"
        "  Actions workflow that builds and uploads a versioned debug APK\n"
        "  (patch 16).\n"
    )
    guarded_replace(path, old_filemap_docs, new_filemap_docs, marker="added patch 23). `ROADMAP.md` -- current\n  status and open technical debt")

    # 6f. "Immediate next step" -- rewritten for steady state; generic process
    # checklist moved to AGENTS.md instead of staying duplicated here.
    old_next_step = (
        "## Immediate next step for Claude (in a new conversation)\n"
        "\n"
        "**Step 9's CI workflow is now confirmed working** (patch 21): a real\n"
        "signed release build succeeded via GitHub Actions. Getting there\n"
        "surfaced two real gotchas, both fixed and documented in\n"
        "`CHANGELOG.md`'s Patch 21 entry and `RELEASE.md`: PKCS12 keystores\n"
        "require an identical store/key password (mismatched ones fail later\n"
        "with an opaque `Given final block not properly padded` error, not an\n"
        "obviously-a-password-problem one), and the default `gh` token inside a\n"
        "Codespace lacks rights to write repo secrets (needs a separate PAT).\n"
        "The ~200MB first build was trimmed via `ndk.abiFilters` (also patch\n"
        "21). The one item left before Step 9 counts as fully done: the user\n"
        "installing the signed APK on a real device and confirming it opens\n"
        "correctly -- don't mark that done without an explicit report, same\n"
        "discipline `build-debug.yml` was held to.\n"
        "\n"
        "**Patch 22** fixed a startup crash the user hit on the very first real\n"
        "device launch attempt (Android 13): `EmptyQueueState` -- what a fresh\n"
        "install shows before any download exists -- called `painterResource()`\n"
        "on `android.R.drawable.stat_sys_download`, which is an\n"
        "`AnimatedVectorDrawable` on real devices and not loadable that way (see\n"
        "`CHANGELOG.md`'s Patch 22 entry). Replaced with a small hand-authored\n"
        "static vector (`res/drawable/ic_download.xml`). **None of Step 5's\n"
        "checklist items are confirmed yet** -- the crash happened before any of\n"
        "them could be exercised -- so the next report back from the user should\n"
        "be a full re-run of that checklist from item 1, not just a confirmation\n"
        "that this one crash is gone.\n"
        "\n"
        "In the meantime, or once the user reports Step 9 confirmed working:\n"
        "**Step 5's manual on-device checklist** is next in priority (still\n"
        "open, `ROADMAP.md`'s Step 5 section has the full list), followed by\n"
        "`roadmap.md`'s GPT Astra audit once both Step 5 and Step 9 are\n"
        "genuinely done.\n"
        "\n"
        "**Work in sprints, not one patch per tiny step:** continue\n"
        "autonomously through a batch of work -- Step 9's steps, and Step 5's\n"
        "still-open manual checklist if/when the user reports back on it --\n"
        "without stopping between individual items, then deliver **one**\n"
        "self-contained Python patch script at the end of the sprint covering\n"
        "everything in it, rather than one patch per small change. The user\n"
        "installs several sprints' patches together.\n"
        "\n"
        "Continue the established pattern for this project:\n"
        "\n"
        "- Think and write all code, code comments, commit messages, and\n"
        "  documentation in English, regardless of what language the\n"
        "  conversation itself is in.\n"
        "- Read the actual current file content before editing -- don't assume\n"
        "  memory of it is accurate; things have changed across 19 patches.\n"
        "- Verify uncertain library/API claims against a real source -- ideally\n"
        "  the actual tagged source via `git clone` (github.com is reachable\n"
        "  from the sandbox), not just a README or an old sample app, both of\n"
        "  which have already produced a wrong conclusion once in this project\n"
        "  (see patch 14). For Step 9 specifically: verify Android keystore/\n"
        "  signing-config syntax and any GitHub Actions secrets-handling claims\n"
        "  against real sources the same way -- this project has not yet done\n"
        "  anything with signing, so there's no prior verified assumption to\n"
        "  lean on here.\n"
        "- Deliver changes as a self-contained Python patch script for GitHub\n"
        "  Codespaces. Extract the repomix into a local working copy first,\n"
        "  dry-run the script against it, verify diffs and bracket balance and\n"
        "  idempotency -- and for any patch chain longer than a couple of\n"
        "  patches, run the **full chain multiple times from a clean copy**,\n"
        "  since a later patch can silently break an earlier patch's own\n"
        "  idempotency check (see \"Key learnings\" above; patches 16, 17, and 18\n"
        "  each had to vaccinate their immediate predecessor's ROADMAP.md/\n"
        "  HANDOFF.md/CHANGELOG.md checks -- expect this to keep recurring for\n"
        "  any patch touching those files again, including this one).\n"
        "- When a `ROADMAP.md` item is completed, collapse its checklist to a\n"
        "  one-line pointer in that file and record what actually changed in\n"
        "  `CHANGELOG.md` instead (process established patch 16) -- do this in\n"
        "  the same patch as the code change it corresponds to.\n"
        "- Never write a machine-specific path (a Codespace's local filesystem\n"
        "  layout, an absolute SDK/JDK location, etc.) into a file that gets\n"
        "  `git add`ed -- patch 18 exists because patch 12 did exactly that.\n"
        "  Anything derived from the current environment belongs in a\n"
        "  user-level/local config location instead."
    )
    new_next_step = (
        "## Immediate next step for Claude (in a new conversation)\n"
        "\n"
        "**The project is in steady state, not active roadmap execution.**\n"
        "Steps 1-9 are done or reduced to specific technical debt (see\n"
        "`ROADMAP.md`). There's no default next task -- work from whatever the\n"
        "user actually asks for. If they haven't asked for anything specific,\n"
        "the highest-value unprompted next step is picking one item off\n"
        "`ROADMAP.md`'s \"Technical debt\" list and asking which they'd like\n"
        "tackled first, rather than assuming.\n"
        "\n"
        "**What's confirmed as of patch 23:** the app runs correctly end to end\n"
        "on a real device (Android 13) -- install, share/paste a link, queue\n"
        "and complete a download, play it back, background persistence. Signed\n"
        "release builds succeed via CI. **What's still open:** installing that\n"
        "signed build on a device (Step 9's last item), and a handful of more\n"
        "adversarial Step 5 checks (race-condition stress test,\n"
        "`friendlyError()` against a real broken video, airplane mode, a very\n"
        "large download) that haven't been individually confirmed -- don't\n"
        "assume they pass just because the app runs now.\n"
        "\n"
        "For how to work in this repo generally (source-of-truth files,\n"
        "verification discipline, patch-script delivery and testing\n"
        "conventions, scope discipline) see **`AGENTS.md`** -- that content used\n"
        "to live in this section and has moved there so it isn't duplicated\n"
        "across two files."
    )
    guarded_replace(path, old_next_step, new_next_step, marker="The project is in steady state, not active roadmap execution")


def main():
    if not os.path.isdir(_abs("app/src/main/java/com/kinescope/app")):
        raise PatchError(
            "Doesn't look like the Kinescope repo root (expected "
            "app/src/main/java/com/kinescope/app to exist). Run this from "
            "the repository root."
        )

    patch_readme()
    patch_roadmap()
    patch_agents_md()
    patch_changelog()
    patch_gitignore()
    patch_handoff()

    print("\nPatch 23 applied successfully.")


if __name__ == "__main__":
    try:
        main()
    except PatchError as e:
        print(f"\nPATCH FAILED: {e}", file=sys.stderr)
        sys.exit(1)
