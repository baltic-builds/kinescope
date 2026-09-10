#!/usr/bin/env python3
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
    if step5_anchor in text and "**Environment note (patch 07):**" not in text:
        text = text.replace(step5_anchor, step5_note)
    elif "**Environment note (patch 07):**" in text:
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
