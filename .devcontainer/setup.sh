#!/usr/bin/env bash
# Runs once when the Codespace is created (see devcontainer.json).
# Installs the Android SDK command-line tools headlessly (no Android
# Studio, no emulator) and generates the Gradle wrapper.
set -euo pipefail

echo "== Installing Android command line tools =="

ANDROID_SDK_ROOT="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

# Google renames the build number in this URL frequently, so we scrape
# the current one from the official downloads page instead of
# hardcoding a version that will go stale.
DOWNLOAD_URL=$(curl -s https://developer.android.com/studio | \
  grep -o 'https://dl.google.com/android/repository/commandlinetools-linux-[0-9]*_latest.zip' | \
  head -n 1)

if [ -z "$DOWNLOAD_URL" ]; then
  echo "Could not find the command line tools URL automatically."
  echo "Get it manually from https://developer.android.com/studio#command-tools"
  echo "and re-run this script with DOWNLOAD_URL set, e.g.:"
  echo "  DOWNLOAD_URL=https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip bash .devcontainer/setup.sh"
  exit 1
fi

echo "Fetching: $DOWNLOAD_URL"
curl -sSL "$DOWNLOAD_URL" -o /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted
mv /tmp/cmdline-tools-extracted/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extracted

export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

echo "== Accepting SDK licenses =="
# Patch 07: set -o pipefail (line 5) makes this pipeline report a
# *failure* even when sdkmanager itself succeeds, because `yes` is
# killed by SIGPIPE (a non-zero exit status) the moment sdkmanager
# stops reading stdin -- pipefail picks up that left-hand non-zero
# status, and set -e then aborts the whole script right here,
# before platform-tools/platforms/build-tools are ever installed
# or the Gradle wrapper is ever generated. Disabling pipefail for
# just this one pipeline restores default bash behavior ($? = the
# *last* command's status, i.e. sdkmanager's real exit code), so a
# genuine sdkmanager failure still stops the script but yes's
# expected SIGPIPE no longer does.
set +o pipefail
yes | sdkmanager --licenses > /dev/null
sdkmanager_license_status=$?
set -o pipefail
if [ "$sdkmanager_license_status" -ne 0 ]; then
  echo "sdkmanager --licenses failed (exit $sdkmanager_license_status)" >&2
  exit "$sdkmanager_license_status"
fi

echo "== Installing platform-tools, platform 35, build-tools =="
# If a specific build-tools version below is not found, run
# `sdkmanager --list` in the Codespace terminal and swap in whatever
# version is actually available (see ROADMAP.md notes).
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "== Persisting environment variables for future shells =="
{
  echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  echo "export PATH=\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH"
} >> "$HOME/.bashrc"

echo "== Generating the Gradle wrapper =="
gradle wrapper --gradle-version 8.10.2

echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="
