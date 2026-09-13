#!/usr/bin/env python3
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
