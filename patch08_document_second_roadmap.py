#!/usr/bin/env python3
"""
Patch 08 -- Document the two-roadmap situation (ROADMAP.md vs roadmap.md).

Context: the repo currently contains two different planning documents:

  - `ROADMAP.md` (uppercase) -- the original Claude-Fable-5.1-authored,
    Step-numbered plan, already partially implemented (patches 01-07).
    This is the one `CLAUDE.md`/`HANDOFF.md` point at and the one all
    prior patches have been tracked against.
  - `roadmap.md` (lowercase) -- a separate, newer sprint-based audit/plan
    (S0-S11, findings F01-F42) authored by GPT Astra, added to the repo
    the day before this patch. Not yet started.

Per explicit user instruction: finish `ROADMAP.md` (Fable) through Step 9
first, THEN execute `roadmap.md` (Astra) in a later pass, THEN delete
both files once both are fully done. This patch only records that
decision in both cross-session documents so a future session (or a
fresh conversation bootstrapped from HANDOFF.md alone) doesn't have to
re-discover or re-litigate the two-roadmap ambiguity the way this
session did.

No code changes. Documentation only, same pattern as patch 05.

Usage:
    python3 patch08_document_second_roadmap.py [path to repo root]

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

    marker = "**After Step 9 is done, and only then:**"
    if marker in text:
        print("ROADMAP.md already documents the second roadmap -- skipping.")
        return

    anchor = (
        "5. Step 9 — Signed release\n\n"
        "Steps 1-4 (compile blockers, then critical/product-quality fixes) are done"
    )
    if anchor not in text:
        fail("Execution-order anchor not found in ROADMAP.md")

    replacement = (
        "5. Step 9 — Signed release\n\n"
        "**After Step 9 is done, and only then:** this repo also has a "
        "`roadmap.md` (lowercase) — a separate, newer sprint-based "
        "audit/plan (S0-S11, findings F01-F42) from GPT Astra, added by "
        "the user and not yet started. Do not merge it into this document "
        "or start it early — finish everything above (through Step 9) "
        "first. Once both this `ROADMAP.md` and `roadmap.md` are fully "
        "executed, both files get deleted.\n\n"
        "Steps 1-4 (compile blockers, then critical/product-quality fixes) are done"
    )

    text = text.replace(anchor, replacement)
    path.write_text(text)
    print(f"Patched {path}")


def patch_handoff(repo_root: Path):
    path = repo_root / "HANDOFF.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    marker = "**`roadmap.md` (lowercase) — GPT Astra's audit/plan.**"
    if marker in text:
        print("HANDOFF.md already documents the second roadmap -- skipping.")
        return

    anchor = (
        "3. **Step 9 — Signed release**, per `RELEASE.md`, only once every item\n"
        "   in Steps 1-5 is confirmed working on a real device. Note:\n"
        "   `RELEASE.md` still uses the old `yt-offline` name for the keystore\n"
        "   filename/alias and the GitHub release title — those are free-form\n"
        "   labels with no functional tie to `applicationId`, left alone during\n"
        "   the Step 7 rename and worth a quick pass (or not — purely cosmetic)\n"
        "   when Step 9 actually happens.\n"
    )
    if anchor not in text:
        fail("Step 9 bullet anchor not found in HANDOFF.md")

    addition = (
        "4. **`roadmap.md` (lowercase) — GPT Astra's audit/plan.** A "
        "separate, newer sprint-based document (S0-S11, findings F01-F42) "
        "added by the user on top of this one. Explicitly queued for "
        "**after** Step 9 above is fully done — don't start it early or "
        "merge it into this `ROADMAP.md`. Once both roadmaps are fully "
        "executed, delete both files.\n"
    )

    text = text.replace(anchor, anchor + addition)
    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 08 against: {repo_root}")

    patch_roadmap(repo_root)
    patch_handoff(repo_root)

    print("\nPatch 08 applied successfully.")


if __name__ == "__main__":
    main()
