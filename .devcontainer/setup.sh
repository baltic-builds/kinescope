#!/usr/bin/env bash
# Runs once when the Codespace is created (see devcontainer.json).
# Installs the Android SDK command-line tools headlessly (no Android
# Studio, no emulator) and generates the Gradle wrapper.
set -euo pipefail

echo "== Installing Android command line tools =="

ANDROID_SDK_ROOT="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

# Patch 10: skip re-downloading/re-extracting if the tools are
# already installed -- previously this section ran unconditionally
# on every invocation of this script, and the final `mv` below fails
# outright on a second run because the destination directory already
# exists and is non-empty (observed in practice: 'mv: inter-device
# move failed ... unable to remove target: Directory not empty').
if [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Command line tools already installed at $ANDROID_SDK_ROOT/cmdline-tools/latest -- skipping download."
else
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
fi

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
# Patch 10: only append once -- otherwise every re-run of this
# script piles up another identical pair of export lines in
# .bashrc.
if ! grep -qF "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" "$HOME/.bashrc" 2>/dev/null; then
  {
    echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
    echo "export PATH=\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH"
  } >> "$HOME/.bashrc"
else
  echo "Environment variables already persisted in .bashrc -- skipping."
fi

echo "== Generating the Gradle wrapper =="
gradle wrapper --gradle-version 8.10.2

echo "== Pinning Gradle's JDK to a detected JDK 17 (matches devcontainer.json) =="
# Patch 11: the devcontainer Java feature is asked for JDK 17
# (see devcontainer.json), installed via SDKMAN under
# /usr/local/sdkman/candidates/java/<version>. Whatever JVM
# ./gradlew itself launches under has been observed NOT to be
# that JDK 17 in practice (a real ./gradlew assembleDebug run
# failed with a bare '25.0.2' version-number error, consistent
# with a too-new JDK breaking Gradle 8.10.2 / AGP). Rather than
# guess at an exact JDK identifier or path, discover an
# installed 17.x JDK live in this environment and pin Gradle to
# it explicitly via org.gradle.java.home, instead of relying on
# whatever 'java' happens to resolve first on PATH.
JDK17_HOME=""
for base in "/usr/local/sdkman/candidates/java" "/usr/lib/jvm"; do
  if [ -d "$base" ]; then
    found=$(find "$base" -maxdepth 1 -type d \( -name "17.*" -o -iname "*17*" \) 2>/dev/null | sort -V | tail -n 1)
    if [ -n "$found" ]; then
      JDK17_HOME="$found"
    fi
  fi
done

if [ -z "$JDK17_HOME" ]; then
  echo "WARNING: no JDK 17 install found under /usr/local/sdkman/candidates/java or /usr/lib/jvm." >&2
  echo "Gradle will use whatever 'java' resolves to on PATH, which may not be JDK 17." >&2
  echo "If ./gradlew assembleDebug fails with a bare version-number error, this is" >&2
  echo "likely why -- install one manually (e.g. 'sdk install java 17.0.13-ms') and" >&2
  echo "re-run this script." >&2
else
  echo "Found JDK 17 at: $JDK17_HOME"
  if ! grep -qF "org.gradle.java.home=" gradle.properties 2>/dev/null; then
    echo "org.gradle.java.home=$JDK17_HOME" >> gradle.properties
    echo "Pinned org.gradle.java.home=$JDK17_HOME in gradle.properties"
  else
    echo "gradle.properties already sets org.gradle.java.home -- leaving it as-is."
  fi
fi

echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="
