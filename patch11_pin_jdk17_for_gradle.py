#!/usr/bin/env python3
"""
Patch 11 -- Auto-detect and pin a JDK 17 for Gradle, to fix the
`./gradlew assembleDebug` failure ("What went wrong: 25.0.2").

Context: the user's real run log showed `./gradlew assembleDebug` (Gradle
8.10.2, as pinned by the project wrapper) failing with a bare, cryptic
error whose entire message is a version number:

    * What went wrong:
    25.0.2

Working hypothesis, not yet 100% confirmed by a stacktrace: this is a
JDK-too-new-for-Gradle-8.10.2 mismatch. Evidence for it:
  - The *system* `gradle` (installed by the devcontainer Java feature,
    used only to generate the wrapper) is version 9.4.0, and its own
    release-notes banner in the same log explicitly advertises "Java 26
    support" -- meaning whatever JDK is actually active in this
    Codespace is new enough that a very recent Gradle needed dedicated
    work to support it.
  - Gradle 8.10.2 shipped in November 2024, well before JDK 25 (GA
    September 2025) existed, so it cannot possibly have been tested or
    built to support it.
  - An unrelated Android/Gradle project's public bug report (filed
    January 2026) shows this exact literal error text, "25.0.2", coming
    from the same kind of cause: an Android Gradle Plugin / lint
    component crashing when run under a JDK newer than it expects, with
    an exception whose message ends up being just the JDK version
    string.
  - `.devcontainer/devcontainer.json` explicitly requests
    `"version": "17"` from the `ghcr.io/devcontainers/features/java:1`
    feature -- so the intent has always been JDK 17, not whatever newer
    JDK apparently ends up resolved on PATH/JAVA_HOME when Gradle's own
    daemon starts.

That feature installs Java via SDKMAN (confirmed from the feature's own
published source), under `/usr/local/sdkman/candidates/java/<version>`,
with `/usr/local/sdkman/candidates/java/current` as a symlink to
whichever version is set as the SDKMAN default. Something about that
resolution isn't reaching Gradle's own JVM selection for the *wrapper*
build (the system `gradle` 9.4.0 run directly by this script works fine,
so this is specifically about what JVM `./gradlew` itself launches
under).

Rather than guess at an exact installed version string (e.g.
"17.0.13-ms" vs "17.0.13-tem" -- it depends on the feature's default JDK
distribution, which isn't pinned in devcontainer.json) or a hardcoded
path, this patch adds a small piece of *discovery* logic to
`.devcontainer/setup.sh` that runs against the real, live environment:
it looks for an already-installed JDK 17 under SDKMAN's candidates
directory (and, as a fallback, under `/usr/lib/jvm`, in case some image
variant provisions Java a different way), and if it finds one, pins
Gradle to it explicitly via `org.gradle.java.home` in `gradle.properties`
-- the standard, official Gradle mechanism for controlling which JVM
runs Gradle itself, independent of whatever ends up first on PATH.

If no JDK 17 is found, the script does NOT fail or guess -- it prints a
clear warning and leaves `gradle.properties` untouched, so the user
knows to install one manually (`sdk install java 17.0.13-ms` or
similar) rather than silently doing something wrong.

This is a hypothesis-driven fix, not a confirmed one -- flagged as such
in ROADMAP.md until the user's next `./gradlew assembleDebug` run either
succeeds or produces a different, more specific error to work from.

Usage:
    python3 patch11_pin_jdk17_for_gradle.py [path to repo root]

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

    anchor = (
        'echo "== Generating the Gradle wrapper =="\n'
        "gradle wrapper --gradle-version 8.10.2\n"
        "\n"
        'echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="\n'
    )
    if anchor not in text:
        if "Pinning Gradle's JDK to a detected JDK 17" in text:
            print("setup.sh already has the JDK 17 pin step -- skipping.")
            return
        fail("Gradle-wrapper-generation anchor not found in setup.sh")

    jdk_pin_block = (
        'echo "== Pinning Gradle'
        "'"
        's JDK to a detected JDK 17 (matches devcontainer.json) =="\n'
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
        "JDK17_HOME=\"\"\n"
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
        '  echo "Gradle will use whatever '"'"'java'"'"' resolves to on PATH, which may not be JDK 17." >&2\n'
        '  echo "If ./gradlew assembleDebug fails with a bare version-number error, this is" >&2\n'
        '  echo "likely why -- install one manually (e.g. '"'"'sdk install java 17.0.13-ms'"'"') and" >&2\n'
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
        "\n"
    )

    replacement = anchor.replace(
        'echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="\n',
        jdk_pin_block
        + 'echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="\n',
    )

    text = text.replace(anchor, replacement)
    path.write_text(text)
    print(f"Patched {path}")


def patch_roadmap(repo_root: Path):
    path = repo_root / "ROADMAP.md"
    if not path.exists():
        fail(f"{path} not found")
    text = path.read_text()

    marker = "**Hypothesis under test (patch 11):**"
    if marker in text:
        print("ROADMAP.md already documents the JDK-17-pin hypothesis -- skipping.")
        return

    anchor = "Do not move to Step 9 (signed release) until every item above passes."
    if anchor not in text:
        fail("Step 5 closing-line anchor not found in ROADMAP.md")

    note = (
        anchor
        + "\n\n**Hypothesis under test (patch 11):** the first real "
        "`./gradlew assembleDebug` run failed before Kotlin compilation "
        "even started, with a bare `25.0.2` version-number error -- "
        "suspected JDK-too-new-for-Gradle-8.10.2 mismatch, not a "
        "project code issue. Patch 11 added auto-detection/pinning of an "
        "installed JDK 17 via `org.gradle.java.home` in "
        "`gradle.properties`. Not yet confirmed working -- needs the "
        "next `./gradlew assembleDebug` run to either succeed or show a "
        "different error."
    )

    text = text.replace(anchor, note)
    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 11 against: {repo_root}")

    patch_setup_sh(repo_root)
    patch_roadmap(repo_root)

    print("\nPatch 11 applied successfully.")


if __name__ == "__main__":
    main()
