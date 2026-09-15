#!/usr/bin/env python3
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
