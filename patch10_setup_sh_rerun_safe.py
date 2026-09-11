#!/usr/bin/env python3
"""
Patch 10 -- Make .devcontainer/setup.sh safely re-runnable.

Context: the user's actual run log (three consecutive `bash
.devcontainer/setup.sh` invocations against the same Codespace, since
each of patches 07/08/09 was applied and tested one at a time) showed a
real failure on the third run:

    mv: inter-device move failed: '/tmp/cmdline-tools-extracted/cmdline-tools'
    to '/home/codespace/android-sdk/cmdline-tools/latest'; unable to remove
    target: Directory not empty

This happens because the script unconditionally re-downloads and
re-extracts the Android command-line tools and `mv`s them into place on
every run, with no check for "already installed." The first run's
`cmdline-tools/latest` directory was still there on the third run, so the
`mv` failed outright (and `set -e` then aborted the whole script).

This patch adds two independent idempotency guards, in the spirit of the
same "reproducible and idempotent setup.sh" goal patch 07 already
started on (see ROADMAP.md Appendix #34):

  1. Skip the download/extract/`mv` entirely if
     `cmdline-tools/latest/bin/sdkmanager` already exists and is
     executable -- the tools are already installed, nothing to do.
  2. Skip appending the `ANDROID_SDK_ROOT`/`PATH` export block to
     `~/.bashrc` if an identical block is already there, so re-running
     setup.sh doesn't pile up duplicate `export` lines in `.bashrc` on
     every run.

Note: this patch does NOT touch the `./gradlew assembleDebug` failure
itself (the cryptic "What went wrong: 25.0.2" error) -- that looks like
a separate, JDK-related issue and needs one diagnostic round-trip before
it's safe to write a real fix for it (see the accompanying message).

Usage:
    python3 patch10_setup_sh_rerun_safe.py [path to repo root]

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

    # --- Guard 1: skip cmdline-tools download/extract/mv if already installed ---
    old_download_block = (
        '# Google renames the build number in this URL frequently, so we scrape\n'
        '# the current one from the official downloads page instead of\n'
        '# hardcoding a version that will go stale.\n'
        'DOWNLOAD_URL=$(curl -s https://developer.android.com/studio | \\\n'
        "  grep -o 'https://dl.google.com/android/repository/commandlinetools-linux-[0-9]*_latest.zip' | \\\n"
        '  head -n 1)\n'
        '\n'
        'if [ -z "$DOWNLOAD_URL" ]; then\n'
        '  echo "Could not find the command line tools URL automatically."\n'
        '  echo "Get it manually from https://developer.android.com/studio#command-tools"\n'
        '  echo "and re-run this script with DOWNLOAD_URL set, e.g.:"\n'
        '  echo "  DOWNLOAD_URL=https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip bash .devcontainer/setup.sh"\n'
        '  exit 1\n'
        'fi\n'
        '\n'
        'echo "Fetching: $DOWNLOAD_URL"\n'
        'curl -sSL "$DOWNLOAD_URL" -o /tmp/cmdline-tools.zip\n'
        'unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted\n'
        'mv /tmp/cmdline-tools-extracted/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"\n'
        'rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extracted\n'
    )
    new_download_block = (
        "# Patch 10: skip re-downloading/re-extracting if the tools are\n"
        "# already installed -- previously this section ran unconditionally\n"
        "# on every invocation of this script, and the final `mv` below fails\n"
        "# outright on a second run because the destination directory already\n"
        "# exists and is non-empty (observed in practice: 'mv: inter-device\n"
        "# move failed ... unable to remove target: Directory not empty').\n"
        'if [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then\n'
        '  echo "Command line tools already installed at $ANDROID_SDK_ROOT/cmdline-tools/latest -- skipping download."\n'
        "else\n"
        '  # Google renames the build number in this URL frequently, so we scrape\n'
        '  # the current one from the official downloads page instead of\n'
        '  # hardcoding a version that will go stale.\n'
        '  DOWNLOAD_URL=$(curl -s https://developer.android.com/studio | \\\n'
        "    grep -o 'https://dl.google.com/android/repository/commandlinetools-linux-[0-9]*_latest.zip' | \\\n"
        '    head -n 1)\n'
        '\n'
        '  if [ -z "$DOWNLOAD_URL" ]; then\n'
        '    echo "Could not find the command line tools URL automatically."\n'
        '    echo "Get it manually from https://developer.android.com/studio#command-tools"\n'
        '    echo "and re-run this script with DOWNLOAD_URL set, e.g.:"\n'
        '    echo "  DOWNLOAD_URL=https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip bash .devcontainer/setup.sh"\n'
        '    exit 1\n'
        '  fi\n'
        '\n'
        '  echo "Fetching: $DOWNLOAD_URL"\n'
        '  curl -sSL "$DOWNLOAD_URL" -o /tmp/cmdline-tools.zip\n'
        '  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted\n'
        '  mv /tmp/cmdline-tools-extracted/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"\n'
        '  rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extracted\n'
        'fi\n'
    )

    if old_download_block in text:
        text = text.replace(old_download_block, new_download_block)
    elif "Patch 10: skip re-downloading" in text:
        print("setup.sh cmdline-tools guard already applied -- skipping.")
    else:
        fail("cmdline-tools download-block anchor not found in setup.sh")

    # --- Guard 2: don't duplicate the .bashrc export block on re-runs ---
    old_bashrc_block = (
        'echo "== Persisting environment variables for future shells =="\n'
        '{\n'
        '  echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"\n'
        '  echo "export PATH=\\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\\$ANDROID_SDK_ROOT/platform-tools:\\$PATH"\n'
        '} >> "$HOME/.bashrc"\n'
    )
    new_bashrc_block = (
        'echo "== Persisting environment variables for future shells =="\n'
        "# Patch 10: only append once -- otherwise every re-run of this\n"
        "# script piles up another identical pair of export lines in\n"
        "# .bashrc.\n"
        'if ! grep -qF "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" "$HOME/.bashrc" 2>/dev/null; then\n'
        '  {\n'
        '    echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"\n'
        '    echo "export PATH=\\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\\$ANDROID_SDK_ROOT/platform-tools:\\$PATH"\n'
        '  } >> "$HOME/.bashrc"\n'
        "else\n"
        '  echo "Environment variables already persisted in .bashrc -- skipping."\n'
        "fi\n"
    )

    if old_bashrc_block in text:
        text = text.replace(old_bashrc_block, new_bashrc_block)
    elif "only append once" in text:
        print("setup.sh .bashrc guard already applied -- skipping.")
    else:
        fail(".bashrc export-block anchor not found in setup.sh")

    path.write_text(text)
    print(f"Patched {path}")


def main():
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    repo_root = repo_root.resolve()
    print(f"Applying patch 10 against: {repo_root}")

    patch_setup_sh(repo_root)

    print("\nPatch 10 applied successfully.")


if __name__ == "__main__":
    main()
