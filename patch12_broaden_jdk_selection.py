#!/usr/bin/env python3
"""
Patch 12 -- Fix patch 11's JDK discovery: accept any Gradle-8.10.2-
compatible JDK (17-23), not only literal "17.x", and confirm the root
cause with real data.

Context: the user's diagnostic output confirmed the hypothesis from
patch 11's docstring, and also showed why patch 11's fix didn't yet do
anything useful:

  - `openjdk version "25.0.2"` is what `java`/`javac` resolve to on
    PATH (from `/home/codespace/java/current`, a GitHub-Codespaces-
    provided JDK that is separate from -- and takes priority over --
    the SDKMAN-managed one the devcontainer Java feature installs).
  - Under SDKMAN itself, `/usr/local/sdkman/candidates/java/` contains
    only `21.0.10-ms` and `25.0.2-ms` -- no 17.x at all, even though
    `devcontainer.json` asks the feature for `"version": "17"`. SDKMAN's
    own `current` symlink also points at `25.0.2-ms`.
  - Gradle's own 8.10 release notes confirm the ceiling directly:
    "Gradle now supports running on Java 23" -- i.e. Java 24+ is
    unsupported for *running* Gradle 8.10.2 itself. JDK 25.0.2 is two
    major versions past that.
  - So patch 11's discovery loop (which only looked for directories
    matching `17.*`/`*17*`) correctly found nothing -- there genuinely
    is no JDK 17 here -- and correctly printed its warning rather than
    guessing. The fix is to broaden the search to any JDK Gradle 8.10.2
    can actually run on (17 through 23), which in this environment
    means it will pick up the JDK 21.0.10-ms that's already installed,
    with no new downloads needed.

This patch replaces patch 11's `JDK17_HOME`-only search in
`.devcontainer/setup.sh` with a `find_gradle_compatible_jdk` helper that
scans the same two locations (SDKMAN's candidates dir, then
`/usr/lib/jvm` as a fallback for other image variants), skips the
`current` symlink itself (to avoid selecting a dangling/self-referential
entry), extracts the leading version number from each directory name
(handles both SDKMAN-style `21.0.10-ms` and apt-style
`java-17-openjdk-amd64` naming), and keeps the highest one found with a
major version between 17 and 23 inclusive. Verified against four cases
in isolation (only-too-new-available, two-in-range picks the higher one,
apt-style naming, nothing available) before writing this patch.

Also updates ROADMAP.md's patch-11 hypothesis note to record the
confirmed root cause and point at this patch.

Usage:
    python3 patch12_broaden_jdk_selection.py [path to repo root]

Idempotent: safe to run twice.
"""
import sys
from pathlib import Path


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


OLD_BLOCK = (
    "echo \"== Pinning Gradle's JDK to a detected JDK 17 (matches devcontainer.json) ==\"\n"
    "# Patch 11: the devcontainer Java feature is asked for JDK 17\n"
    "# (see devcontainer.json), installed via SDKMAN under\n"
    "# /usr/local/sdkman/candidates/java/<version>. Whatever JVM\n"
    "# ./gradlew itself launches under has been observed NOT to be\n"
    "# that JDK 17 in practice (a real ./gradlew assembleDebug run\n"
    "# failed with a bare '25.0.2' version-number error, consistent\n"
    "# with a too-new JDK breaking Gradle 8.10.2 / AGP). Rather than\n"
    "# guess at an exact JDK identifier or path, discover an\n"
    "# installed 17.x JDK live in this environment and pin Gradle to\n"
    "# it explicitly via org.gradle.java.home, instead of relying on\n"
    "# whatever 'java' happens to resolve first on PATH.\n"
    'JDK17_HOME=""\n'
    'for base in "/usr/local/sdkman/candidates/java" "/usr/lib/jvm"; do\n'
    '  if [ -d "$base" ]; then\n'
    '    found=$(find "$base" -maxdepth 1 -type d \\( -name "17.*" -o -iname "*17*" \\) 2>/dev/null | sort -V | tail -n 1)\n'
    '    if [ -n "$found" ]; then\n'
    '      JDK17_HOME="$found"\n'
    "    fi\n"
    "  fi\n"
    "done\n"
    "\n"
    'if [ -z "$JDK17_HOME" ]; then\n'
    '  echo "WARNING: no JDK 17 install found under /usr/local/sdkman/candidates/java or /usr/lib/jvm." >&2\n'
    "  echo \"Gradle will use whatever 'java' resolves to on PATH, which may not be JDK 17.\" >&2\n"
    '  echo "If ./gradlew assembleDebug fails with a bare version-number error, this is" >&2\n'
    "  echo \"likely why -- install one manually (e.g. 'sdk install java 17.0.13-ms') and\" >&2\n"
    '  echo "re-run this script." >&2\n'
    "else\n"
    '  echo "Found JDK 17 at: $JDK17_HOME"\n'
    '  if ! grep -qF "org.gradle.java.home=" gradle.properties 2>/dev/null; then\n'
    '    echo "org.gradle.java.home=$JDK17_HOME" >> gradle.properties\n'
    '    echo "Pinned org.gradle.java.home=$JDK17_HOME in gradle.properties"\n'
    "  else\n"
    '    echo "gradle.properties already sets org.gradle.java.home -- leaving it as-is."\n'
    "  fi\n"
    "fi\n"
)

NEW_BLOCK = (
    'echo "== Pinning Gradle to a JDK it can actually run on =="\n'
    "# Patch 12: patch 11 only looked for a literal JDK 17, on the\n"
    "# assumption that devcontainer.json's \"version\": \"17\" request had\n"
    "# been honored. A real diagnostic run showed that assumption was\n"
    "# wrong in this environment -- only 21.0.10-ms and 25.0.2-ms exist\n"
    "# under SDKMAN, no 17.x at all -- and confirmed the root cause via\n"
    "# Gradle's own 8.10 release notes: \"Gradle now supports running on\n"
    "# Java 23\", i.e. JDK 24+ cannot run Gradle 8.10.2. `java`/`javac` on\n"
    "# PATH resolve to a separate, newer JDK provided by the Codespace\n"
    "# itself (/home/codespace/java/current, currently 25.0.2), which is\n"
    "# why the build failed with a bare '25.0.2' error. Broaden the\n"
    "# search to accept any installed JDK Gradle 8.10.2 can run on\n"
    "# (17-23 inclusive), preferring the highest one found, rather than\n"
    "# requiring exactly 17.\n"
    "find_gradle_compatible_jdk() {\n"
    "  best_major=0\n"
    '  best_dir=""\n'
    '  for base in "/usr/local/sdkman/candidates/java" "/usr/lib/jvm"; do\n'
    '    if [ -d "$base" ]; then\n'
    '      for dir in "$base"/*/; do\n'
    '        dir="${dir%/}"\n'
    '        name=$(basename "$dir")\n'
    '        [ "$name" = "current" ] && continue\n'
    "        major=$(echo \"$name\" | grep -oE '[0-9]+' | head -n 1)\n"
    '        if [ -n "$major" ] && [ "$major" -ge 17 ] && [ "$major" -le 23 ] && [ "$major" -gt "$best_major" ]; then\n'
    '          best_major="$major"\n'
    '          best_dir="$dir"\n'
    "        fi\n"
    "      done\n"
    "    fi\n"
    "  done\n"
    '  echo "$best_dir"\n'
    "}\n"
    "\n"
    'JDK_HOME=$(find_gradle_compatible_jdk)\n'
    "\n"
    'if [ -z "$JDK_HOME" ]; then\n'
    '  echo "WARNING: no JDK between 17 and 23 found under /usr/local/sdkman/candidates/java or /usr/lib/jvm." >&2\n'
    "  echo \"Gradle will use whatever 'java' resolves to on PATH, which may be too new for Gradle 8.10.2.\" >&2\n"
    '  echo "Install one manually (e.g. '"'"'sdk install java 21.0.10-ms'"'"') and re-run this script." >&2\n'
    "else\n"
    '  echo "Found a Gradle-compatible JDK at: $JDK_HOME"\n'
    '  if grep -qF "org.gradle.java.home=" gradle.properties 2>/dev/null; then\n'
    '    sed -i "s#^org.gradle.java.home=.*#org.gradle.java.home=$JDK_HOME#" gradle.properties\n'
    '    echo "Updated org.gradle.java.home=$JDK_HOME in gradle.properties"\n'
    "  else\n"
    '    echo "org.gradle.java.home=$JDK_HOME" >> gradle.properties\n'
    '    echo "Pinned org.gradle.java.home=$JDK_HOME in gradle.properties"\n'
    "  fi\n"
    "fi\n"
)


def patch_setup_sh(repo_root: Path):
    path = repo_root / ".devcontainer" / "setup.sh"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    if OLD_BLOCK in text:
        text = text.replace(OLD_BLOCK, NEW_BLOCK)
    elif "find_gradle_compatible_jdk" in text:
        print("setup.sh already has the broadened JDK selection -- skipping.")
        return
    else:
        fail("Patch 11's JDK17_HOME block anchor not found in setup.sh")

    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    marker = "**Confirmed root cause (patch 12):**"
    if marker in text:
        print("ROADMAP.md already records the confirmed root cause -- skipping.")
        return

    anchor = (
        "**Hypothesis under test (patch 11):** the first real "
        "`./gradlew assembleDebug` run failed before Kotlin compilation "
        "even started, with a bare `25.0.2` version-number error -- "
        "suspected JDK-too-new-for-Gradle-8.10.2 mismatch, not a "
        "project code issue. Patch 11 added auto-detection/pinning of an "
        "installed JDK 17 via `org.gradle.java.home` in "
        "`gradle.properties`. Not yet confirmed working -- needs the "
        "next `./gradlew assembleDebug` run to either succeed or show a "
        "different error."
    )
    if anchor not in text:
        fail("Patch-11 hypothesis-note anchor not found in ROADMAP.md")

    replacement = (
        "**Confirmed root cause (patch 12):** a real diagnostic run "
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
        "confirm."
    )

    text = text.replace(anchor, replacement)
    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 12 against: {repo_root}")

    patch_setup_sh(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 12 applied successfully.")


if __name__ == "__main__":
    main()
