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

echo "== Pinning Gradle to a JDK it can actually run on =="
# Patch 12: patch 11 only looked for a literal JDK 17, on the
# assumption that devcontainer.json's "version": "17" request had
# been honored. A real diagnostic run showed that assumption was
# wrong in this environment -- only 21.0.10-ms and 25.0.2-ms exist
# under SDKMAN, no 17.x at all -- and confirmed the root cause via
# Gradle's own 8.10 release notes: "Gradle now supports running on
# Java 23", i.e. JDK 24+ cannot run Gradle 8.10.2. `java`/`javac` on
# PATH resolve to a separate, newer JDK provided by the Codespace
# itself (/home/codespace/java/current, currently 25.0.2), which is
# why the build failed with a bare '25.0.2' error. Broaden the
# search to accept any installed JDK Gradle 8.10.2 can run on
# (17-23 inclusive), preferring the highest one found, rather than
# requiring exactly 17.
#
# Patch 18: this pin is a machine-specific absolute path (this exact
# Codespace's SDKMAN install location). Patch 12 wrote it into the
# project's own committed gradle.properties, which meant it got
# pushed to git and shipped to every environment that clones this
# repo -- including the GitHub Actions runner added in patch 16,
# where /usr/local/sdkman/candidates/java/21.0.10-ms doesn't exist
# and the build failed outright ("Value ... given for
# org.gradle.java.home Gradle property is invalid"). Written to the
# user-level $HOME/.gradle/gradle.properties instead: Gradle already
# gives user-level gradle.properties higher precedence than the
# project-level one, and this file lives outside the repo entirely,
# so it never leaves this machine.
find_gradle_compatible_jdk() {
  best_major=0
  best_dir=""
  for base in "/usr/local/sdkman/candidates/java" "/usr/lib/jvm"; do
    if [ -d "$base" ]; then
      for dir in "$base"/*/; do
        dir="${dir%/}"
        name=$(basename "$dir")
        [ "$name" = "current" ] && continue
        major=$(echo "$name" | grep -oE '[0-9]+' | head -n 1)
        if [ -n "$major" ] && [ "$major" -ge 17 ] && [ "$major" -le 23 ] && [ "$major" -gt "$best_major" ]; then
          best_major="$major"
          best_dir="$dir"
        fi
      done
    fi
  done
  echo "$best_dir"
}

JDK_HOME=$(find_gradle_compatible_jdk)
USER_GRADLE_PROPERTIES="$HOME/.gradle/gradle.properties"

if [ -z "$JDK_HOME" ]; then
  echo "WARNING: no JDK between 17 and 23 found under /usr/local/sdkman/candidates/java or /usr/lib/jvm." >&2
  echo "Gradle will use whatever 'java' resolves to on PATH, which may be too new for Gradle 8.10.2." >&2
  echo "Install one manually (e.g. 'sdk install java 21.0.10-ms') and re-run this script." >&2
else
  echo "Found a Gradle-compatible JDK at: $JDK_HOME"
  mkdir -p "$HOME/.gradle"
  touch "$USER_GRADLE_PROPERTIES"
  if grep -qF "org.gradle.java.home=" "$USER_GRADLE_PROPERTIES" 2>/dev/null; then
    sed -i "s#^org.gradle.java.home=.*#org.gradle.java.home=$JDK_HOME#" "$USER_GRADLE_PROPERTIES"
    echo "Updated org.gradle.java.home=$JDK_HOME in $USER_GRADLE_PROPERTIES"
  else
    echo "org.gradle.java.home=$JDK_HOME" >> "$USER_GRADLE_PROPERTIES"
    echo "Pinned org.gradle.java.home=$JDK_HOME in $USER_GRADLE_PROPERTIES"
  fi
fi

echo "== Setup complete. In a new terminal, try: ./gradlew assembleDebug =="
