#!/usr/bin/env python3
"""
Patch 22 -- Fix a startup crash hit on the user's first real-device
launch attempt (Android 13), during ROADMAP.md's Step 5 checklist.

Root cause: `EmptyQueueState` in MainActivity.kt (the screen a fresh
install shows first, before any download exists) loaded
`android.R.drawable.stat_sys_download` via Compose's
`painterResource()`. That framework resource is an
AnimatedVectorDrawable on real devices -- it's the system's own
animated download-in-progress notification glyph. `painterResource()`
only supports a plain static VectorDrawable or a rasterized image
(PNG/JPG/WEBP); its own documentation states this explicitly ("API
based xml Drawables are not supported here"). Loading an
animated-vector through it raises IllegalArgumentException at runtime,
every single time -- which for `EmptyQueueState` means immediately, on
a fresh install, before Step 5's checklist can even get past its first
item.

`DownloadService.buildNotification()`'s `setSmallIcon()` use of the
same framework resource is UNCHANGED and NOT a bug: Android
notifications accept any drawable resource id directly (no Compose
involved), which is exactly what this animated icon is designed for.

Fix: add a small hand-authored static vector
(res/drawable/ic_download.xml, arrow + tray -- the same motif already
used in ic_launcher_foreground.xml, scaled to a normal 24dp icon
viewport) and point EmptyQueueState's Icon() at that instead.
Deliberately not material-icons-extended: this project has already
decided against pulling in that dependency for a single glyph (see the
comment this patch replaces), and that reasoning still holds.

Docs updated in this same patch (workflow discipline: code + docs
together): CHANGELOG.md (new Patch 22 entry), ROADMAP.md (Step 5 note
-- none of that checklist's boxes are confirmed yet; the crash
happened before any of them could be exercised), and HANDOFF.md (three
places: the top status paragraph, the "Patch 20" narrative bullet list,
and the Step 5 bullet under "what's actually done vs. still open", and
the "Immediate next step" section).

Usage:
    python3 patch_22_fix_empty_queue_icon_crash.py

Run from the repository root. Idempotent -- safe to run twice.
"""

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
        print(f"[skip] {path}: already applied")
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
    print(f"[ok]   {path}: applied")


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
# 1. New static vector drawable -- replaces the framework animated-vector
#    that painterResource() can't load.
# ---------------------------------------------------------------------------

IC_DOWNLOAD_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Hand-authored static download glyph (arrow + tray), reusing the same
  visual motif as ic_launcher_foreground.xml (scaled to a normal 24dp
  icon viewport) so the app's own icon language stays consistent.

  Added in Patch 22 to replace android.R.drawable.stat_sys_download in
  EmptyQueueState (MainActivity.kt): that framework resource is an
  AnimatedVectorDrawable on real devices, and Jetpack Compose's
  painterResource() only supports a plain static VectorDrawable or a
  rasterized image (PNG/JPG/WEBP), not an API-driven XML type such as
  an animated-vector. See CHANGELOG.md's Patch 22 entry.

  fillColor below is irrelevant to the rendered color: Icon() in
  MainActivity.kt applies its own `tint` as a ColorFilter over
  whatever this draws, same as every Icons.Default.* icon already used
  in that file.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Arrow stem -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M10.5,3 L13.5,3 L13.5,11 L10.5,11 Z" />
    <!-- Arrowhead -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M8,11 L16,11 L12,16 Z" />
    <!-- Tray / base line -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M8,18 L16,18 L16,20 L8,20 Z" />
</vector>
"""


def patch_add_ic_download():
    create_file_if_missing(
        "app/src/main/res/drawable/ic_download.xml",
        IC_DOWNLOAD_XML,
        "static download-arrow vector for EmptyQueueState",
    )


# ---------------------------------------------------------------------------
# 2. MainActivity.kt -- swap the framework animated-vector for the new
#    static one, and rewrite the now-outdated comment explaining why.
# ---------------------------------------------------------------------------

def patch_main_activity():
    path = "app/src/main/java/com/kinescope/app/MainActivity.kt"

    old_comment = (
        "// ROADMAP.md Step 6.5 [Download queue (empty)]: centered\n"
        "// primaryContainer circle behind a download icon, no button (the\n"
        "// composer bar below is already the call to action).\n"
        "//\n"
        "// Icon choice: reuses the system stat_sys_download glyph already\n"
        "// proven to work in this exact codebase (DownloadService's\n"
        "// notification icon), rather than pulling in the large\n"
        "// material-icons-extended dependency for a single \"download arrow\"\n"
        "// glyph that core doesn't include, or hand-authoring a new vector\n"
        "// resource. Worth a look on a real device (see design.md's \"compare\n"
        "// against a real screen\" note) -- system status-bar icons are\n"
        "// designed for small sizes, so this may want swapping for a custom\n"
        "// vector later if it looks rough scaled up.\n"
    )
    new_comment = (
        "// ROADMAP.md Step 6.5 [Download queue (empty)]: centered\n"
        "// primaryContainer circle behind a download icon, no button (the\n"
        "// composer bar below is already the call to action).\n"
        "//\n"
        "// Icon choice (Patch 22): a small hand-authored static vector\n"
        "// (res/drawable/ic_download.xml), not material-icons-extended --\n"
        "// still avoided for one glyph, per CLAUDE.md's zero-required-cost/\n"
        "// no-bloat spirit -- and not the framework's\n"
        "// android.R.drawable.stat_sys_download this used to reference.\n"
        "// That framework icon is an AnimatedVectorDrawable on real devices\n"
        "// (it's the system's own animated download-in-progress\n"
        "// notification glyph -- still used as-is in DownloadService's\n"
        "// setSmallIcon(), which is fine, since Android notifications accept\n"
        "// any drawable resource id directly, no Compose involved). Jetpack\n"
        "// Compose's painterResource() only supports a plain static\n"
        "// VectorDrawable or a rasterized image, not an API-driven XML type\n"
        "// like an animated-vector -- confirmed against painterResource()'s\n"
        "// own documentation. This crashed on the very first real-device\n"
        "// launch during the Step 5 checklist (EmptyQueueState is what a\n"
        "// fresh install shows first, before any download exists) -- see\n"
        "// CHANGELOG.md's Patch 22 entry.\n"
    )
    guarded_replace(path, old_comment, new_comment, marker="Icon choice (Patch 22)")

    old_icon = "                painter = painterResource(id = android.R.drawable.stat_sys_download),\n"
    new_icon = "                painter = painterResource(id = R.drawable.ic_download),\n"
    guarded_replace(path, old_icon, new_icon, marker="painterResource(id = R.drawable.ic_download)")


# ---------------------------------------------------------------------------
# 3. CHANGELOG.md -- new Patch 22 entry, inserted above the Patch 21 entry.
# ---------------------------------------------------------------------------

CHANGELOG_PATCH_22 = """## Patch 22 \u2014 Fixed a startup crash found on the first real-device launch (Step 5)

The user's first real-device install (Android 13) crashed immediately.
`EmptyQueueState` is what a fresh install shows first (queue empty, no
downloads yet) \u2014 that's the exact composable that crashed, on its
`painterResource()` call.

### Changed
- `app/src/main/java/com/kinescope/app/MainActivity.kt` \u2014
  `EmptyQueueState`'s icon no longer loads
  `android.R.drawable.stat_sys_download` via `painterResource()`. That
  framework resource is an `AnimatedVectorDrawable` on real devices
  (it's the system's own animated download-in-progress notification
  glyph); Compose's `painterResource()` only supports a plain static
  `VectorDrawable` or a rasterized image (PNG/JPG/WEBP), not an
  API-driven XML type like an animated-vector \u2014 confirmed against
  `painterResource()`'s own documentation, which states this
  restriction explicitly ("API based xml Drawables are not supported
  here"). Loading one this way raises `IllegalArgumentException` at
  runtime, every time \u2014 which for this composable means immediately,
  on a fresh install.
- Added `app/src/main/res/drawable/ic_download.xml` \u2014 a small,
  hand-authored static vector (arrow + tray), reusing the same visual
  motif as `ic_launcher_foreground.xml` for consistency. Deliberately
  not `material-icons-extended` (still avoided for one glyph, per
  `CLAUDE.md`'s zero-required-cost/no-bloat spirit) and not the
  framework resource that just crashed.

### Findings (closed)
- **`android.R.drawable.stat_sys_download` cannot be loaded via
  Compose's `painterResource()`.** It's an `AnimatedVectorDrawable` on
  real devices, not a static `VectorDrawable` or a raster image \u2014
  `painterResource()`'s own documentation explicitly scopes support to
  those two types only. `DownloadService.buildNotification()`'s
  `setSmallIcon()` use of the same resource is unaffected and
  deliberately left as-is: Android notifications accept any drawable
  resource id directly (no Compose involved), which is exactly what
  this animated icon is designed for.
- **`./gradlew assembleDebug` succeeding does not catch this class of
  bug.** Nothing about this crash shows up at compile time \u2014 it's a
  resource-type mismatch that only manifests when the composable
  actually runs on a device, which is exactly the gap Step 5's
  real-device checklist exists to catch.

"""


def patch_changelog():
    path = "CHANGELOG.md"
    anchor = "## Patch 21 \u2014 APK size trim + keystore-password fix\n"
    guarded_replace(
        path,
        anchor,
        CHANGELOG_PATCH_22 + anchor,
        marker="## Patch 22 \u2014 Fixed a startup crash found on the first real-device launch (Step 5)",
    )


# ---------------------------------------------------------------------------
# 4. ROADMAP.md -- note in the Step 5 section. No checkbox is marked done:
#    the crash happened before any of them could be exercised.
# ---------------------------------------------------------------------------

def patch_roadmap():
    path = "ROADMAP.md"
    old = (
        "Do not move to Step 9 (signed release) until every item above passes.\n"
        "**Update (patch 19): the user has explicitly directed starting Step 9\n"
        "now anyway** \u2014 see that section for what this means and doesn't mean.\n"
    )
    new = (
        "**Update (patch 22):** the very first real-device launch attempt hit an\n"
        "immediate startup crash \u2014 `EmptyQueueState` (what a fresh install shows\n"
        "before any download exists) called `painterResource()` on a framework\n"
        "resource that turns out to be an `AnimatedVectorDrawable`, which Compose\n"
        "can't load that way. Fixed \u2014 see `CHANGELOG.md`'s Patch 22 entry. None\n"
        "of the checkboxes above are confirmed yet; the crash happened before any\n"
        "of them could be exercised. **Re-run this checklist from the top** with\n"
        "the patched build.\n"
        "\n"
        "Do not move to Step 9 (signed release) until every item above passes.\n"
        "**Update (patch 19): the user has explicitly directed starting Step 9\n"
        "now anyway** \u2014 see that section for what this means and doesn't mean.\n"
    )
    guarded_replace(path, old, new, marker="**Update (patch 22):**")


# ---------------------------------------------------------------------------
# 5. HANDOFF.md -- four small updates: top status paragraph, the narrative
#    patch-by-patch bullet list, the "what's done vs. open" Step 5 bullet,
#    and "Immediate next step".
# ---------------------------------------------------------------------------

def patch_handoff():
    path = "HANDOFF.md"

    # 5a. Top status paragraph.
    old_top = (
        "real device -- that's the one item left before Step 9 counts as fully\n"
        "done. See \"Immediate next step\" below.\n"
    )
    new_top = (
        "real device -- that's the one item left before Step 9 counts as fully\n"
        "done. **Patch 22** separately fixed a startup crash hit on the user's\n"
        "first real-device launch attempt for Step 5 (unrelated code path, a\n"
        "Compose icon-loading bug, not a signing/CI issue) -- see \"Immediate\n"
        "next step\" below.\n"
    )
    guarded_replace(path, old_top, new_top, marker="**Patch 22** separately fixed a startup crash")

    # 5b. Narrative patch-by-patch bullet list (after the Patch 20 bullet).
    old_list = (
        "  hasn't happened yet. See `ROADMAP.md`'s Step 9 section for the exact\n"
        "  remaining checklist.\n"
        "\n"
        "**Version-compatibility note worth remembering:**"
    )
    new_list = (
        "  hasn't happened yet. See `ROADMAP.md`'s Step 9 section for the exact\n"
        "  remaining checklist.\n"
        "- **Patch 22** -- Fixed a startup crash on the user's first\n"
        "  real-device launch attempt (Step 5, Android 13): `EmptyQueueState`\n"
        "  loaded `android.R.drawable.stat_sys_download` via\n"
        "  `painterResource()`, which only supports a static `VectorDrawable`\n"
        "  or a rasterized image -- not the `AnimatedVectorDrawable` that\n"
        "  framework resource actually is on real devices (confirmed against\n"
        "  `painterResource()`'s own documentation, not assumed). Replaced\n"
        "  with a small hand-authored static vector\n"
        "  (`res/drawable/ic_download.xml`). `DownloadService`'s use of the\n"
        "  same framework resource for `setSmallIcon()` is unaffected --\n"
        "  notifications accept any drawable resource id directly, no Compose\n"
        "  involved. Unrelated to Step 9 / CI signing; this is purely a Step 5\n"
        "  finding.\n"
        "\n"
        "**Version-compatibility note worth remembering:**"
    )
    guarded_replace(path, old_list, new_list, marker="- **Patch 22** -- Fixed a startup crash")

    # 5c. "What's actually done vs. still open" -- Step 5 bullet.
    old_step5 = (
        "   checking `friendlyError()`'s string-matching against real current\n"
        "   yt-dlp error text, which genuinely needs a real device and can't be\n"
        "   settled by reading code.\n"
        "3. **`roadmap.md` (lowercase)"
    )
    new_step5 = (
        "   checking `friendlyError()`'s string-matching against real current\n"
        "   yt-dlp error text, which genuinely needs a real device and can't be\n"
        "   settled by reading code. **Patch 22** fixed a startup crash hit on\n"
        "   the very first launch attempt (see below) -- the checklist above\n"
        "   needs a full re-run from item 1 with the patched build, since\n"
        "   nothing on it was actually exercised before the crash.\n"
        "3. **`roadmap.md` (lowercase)"
    )
    guarded_replace(path, old_step5, new_step5, marker="**Patch 22** fixed a startup crash hit on")

    # 5d. "Immediate next step for Claude".
    old_next = (
        "correctly -- don't mark that done without an explicit report, same\n"
        "discipline `build-debug.yml` was held to.\n"
        "\n"
        "In the meantime, or once the user reports Step 9 confirmed working:"
    )
    new_next = (
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
        "In the meantime, or once the user reports Step 9 confirmed working:"
    )
    guarded_replace(path, old_next, new_next, marker="**Patch 22** fixed a startup crash the user hit")


def main():
    if not os.path.isdir(_abs("app/src/main/java/com/kinescope/app")):
        raise PatchError(
            "Doesn't look like the Kinescope repo root (expected "
            "app/src/main/java/com/kinescope/app to exist). Run this from "
            "the repository root."
        )

    patch_add_ic_download()
    patch_main_activity()
    patch_changelog()
    patch_roadmap()
    patch_handoff()

    print("\nPatch 22 applied successfully.")


if __name__ == "__main__":
    try:
        main()
    except PatchError as e:
        print(f"\nPATCH FAILED: {e}", file=sys.stderr)
        sys.exit(1)
