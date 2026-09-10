#!/usr/bin/env python3
"""
Patch 09 -- Fix a stale Step-number cross-reference in ROADMAP.md.

Context: found while re-reading ROADMAP.md end-to-end looking for other
inconsistencies alongside patches 07-08. The Step 5 section ends with:

    Do not move to Step 7 (signed release) until every item above passes.

This is a leftover from before the Kinescope rename was inserted as its
own Step 7 (patch 06). Signed release is now Step 9 (see the "## Step 9
— Signed release" heading and the execution-order list at the top of the
document); Step 7 is "Branding: rename to Kinescope" and is already done.
Every other Step 7/8/9 reference in the file was checked with grep and is
already consistent with the current numbering -- this is the one leftover
spot.

Documentation only, no code/behavior change. Same pattern as patches 05
and 08.

Usage:
    python3 patch09_fix_stale_step_reference.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    old_line = "Do not move to Step 7 (signed release) until every item above passes."
    new_line = "Do not move to Step 9 (signed release) until every item above passes."

    if old_line in text:
        text = text.replace(old_line, new_line)
    elif new_line in text:
        print("ROADMAP.md already has the corrected Step 9 reference -- skipping.")
        return
    else:
        fail("Neither the stale nor the corrected Step 5/9 anchor line was found in ROADMAP.md")

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 09 against: {repo_root}")

    patch_roadmap(repo_root)

    print("\nPatch 09 applied successfully.")


if __name__ == "__main__":
    main()
