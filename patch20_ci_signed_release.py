#!/usr/bin/env python3
"""
Patch 20 -- Step 9: CI-based signed release build.

Adds a new GitHub Actions workflow, `.github/workflows/build-release.yml`,
that builds a *signed release* APK from a base64-encoded keystore stored
as repository secrets. It mirrors `build-debug.yml`'s design (manual
`workflow_dispatch` trigger, hand-supplied version name, versionCode
derived from the run number) -- see ROADMAP.md's Step 9 section and
CLAUDE.md's user instruction log: per explicit user direction, release
builds are now produced via GitHub Actions instead of a local
`./gradlew assembleRelease` run in the Codespace.

Also updates:
  - RELEASE.md   -- renames leftover "yt-offline" keystore/alias/release
                    naming to "kinescope"; documents the new CI build
                    path as an added option alongside the existing local
                    build (kept, since it still works and needs no CI
                    secrets setup).
  - README.md    -- build section mentions the new release workflow;
                    the RELEASE.md doc-map entry no longer says "not
                    started yet".
  - ROADMAP.md   -- Step 9 section gets checklist items separating what
                    this patch actually delivers (the CI workflow) from
                    what still needs the user to do by hand (one-time
                    keystore + secrets setup, then a confirmed real run)
                    before Step 9 can be marked fully done -- same
                    discipline `build-debug.yml` was held to: not marked
                    confirmed until a real Actions run actually
                    succeeded (patch 18).
  - HANDOFF.md   -- milestone paragraph, patch history, and "Immediate
                    next step" updated to match.
  - CHANGELOG.md -- new Patch 20 entry (newest-first, per convention).
  - CLAUDE.md    -- instruction log: records the CI-for-release decision.

Run from the repo root:
    python3 patch20_ci_signed_release.py

Idempotent: safe to run twice (each edit is skipped if already applied).
"""

import os
import sys

REPO_ROOT = os.getcwd()


def read(path):
    full = os.path.join(REPO_ROOT, path)
    with open(full, "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    full = os.path.join(REPO_ROOT, path)
    os.makedirs(os.path.dirname(full) or ".", exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content)


def guarded_replace(path, old, new, already_applied_marker, label):
    """Replace `old` with `new` in `path`. Skips if `already_applied_marker`
    is already present (idempotent re-run). Raises if `old` isn't found
    exactly once (anchor drifted -- needs a human, not a silent no-op)."""
    content = read(path)
    if already_applied_marker in content:
        print(f"  [skip] {path}: {label} already applied")
        return
    count = content.count(old)
    if count != 1:
        raise RuntimeError(
            f"{path}: expected exactly 1 occurrence of anchor for "
            f"'{label}', found {count}. Anchor text may have drifted -- "
            f"inspect the file manually before re-running."
        )
    write(path, content.replace(old, new, 1))
    print(f"  [ok]   {path}: {label}")


def create_if_absent(path, content, label):
    full = os.path.join(REPO_ROOT, path)
    if os.path.exists(full):
        existing = read(path)
        if existing == content:
            print(f"  [skip] {path}: {label} already present, unchanged")
            return
        raise RuntimeError(
            f"{path}: file already exists with different content than "
            f"this patch expects to create. Inspect manually -- refusing "
            f"to overwrite."
        )
    write(path, content)
    print(f"  [ok]   {path}: {label} (created)")


# ---------------------------------------------------------------------------
# 1. New GitHub Actions workflow: build-release.yml
# ---------------------------------------------------------------------------

BUILD_RELEASE_YML = """name: Build Signed Release APK

# Manual-only build, mirroring build-debug.yml's design (see ROADMAP.md
# Step 9 / CHANGELOG.md Patch 20). Produces a release-signed APK using a
# keystore stored as a base64-encoded GitHub secret -- see RELEASE.md
# for how to generate the keystore and add the required secrets. No
# push/PR/schedule trigger by design -- every build is a deliberate,
# hand-versioned run.
on:
  workflow_dispatch:
    inputs:
      version_name:
        description: >-
          App version name to stamp on this build (e.g. "1.1.0").
          versionCode is derived automatically from this run's number
          (below), so it always increases even if you reuse a
          version_name -- avoids INSTALL_FAILED_VERSION_DOWNGRADE when
          reinstalling over an older build on the same device.
        required: true
        type: string

permissions:
  contents: read

jobs:
  build-release:
    name: Build signed release APK
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          # Same reasoning as build-debug.yml: a GitHub Actions runner
          # is a clean, single-JDK environment (unlike this project's
          # Codespace -- see HANDOFF.md's "Key learnings"), so no extra
          # org.gradle.java.home pinning is needed here.

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4
        with:
          # Matches compileSdk/targetSdk 35 and the build-tools version
          # already confirmed correct (see ROADMAP.md's Appendix).
          packages: "platform-tools platforms;android-35 build-tools;35.0.0"

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Decode signing keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          if [ -z "$KEYSTORE_BASE64" ]; then
            echo "KEYSTORE_BASE64 repo secret is not set -- see RELEASE.md's 'CI build (GitHub Actions)' section to generate and add it." >&2
            exit 1
          fi
          echo "$KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/kinescope-release.jks"

      - name: Write keystore.properties
        env:
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          {
            echo "storeFile=$RUNNER_TEMP/kinescope-release.jks"
            echo "storePassword=$KEYSTORE_PASSWORD"
            echo "keyAlias=$KEY_ALIAS"
            echo "keyPassword=$KEY_PASSWORD"
          } > keystore.properties
          # keystore.properties is read by app/build.gradle.kts's signing
          # config (rootProject.file("keystore.properties")); it is
          # gitignored and only ever exists on this ephemeral runner.

      - name: Build signed release APK
        run: >-
          ./gradlew --no-daemon assembleRelease
          -PappVersionName="${{ inputs.version_name }}"
          -PappVersionCode="${{ github.run_number }}"

      - name: Rename APK with version
        id: rename_apk
        run: |
          src="app/build/outputs/apk/release/app-release.apk"
          if [ ! -f "$src" ]; then
            echo "Expected APK not found at $src" >&2
            exit 1
          fi
          dest="app/build/outputs/apk/release/kinescope-${{ inputs.version_name }}-release.apk"
          mv "$src" "$dest"
          echo "apk_path=$dest" >> "$GITHUB_OUTPUT"

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: kinescope-${{ inputs.version_name }}-release
          path: ${{ steps.rename_apk.outputs.apk_path }}
          if-no-files-found: error
          retention-days: 30

      - name: Clean up signing material
        if: always()
        run: rm -f keystore.properties "$RUNNER_TEMP/kinescope-release.jks"
"""


def patch_build_release_workflow():
    create_if_absent(
        ".github/workflows/build-release.yml",
        BUILD_RELEASE_YML,
        "signed-release CI workflow",
    )


# ---------------------------------------------------------------------------
# 2. RELEASE.md -- rename yt-offline -> kinescope, document the CI path
# ---------------------------------------------------------------------------

def patch_release_md():
    path = "RELEASE.md"

    guarded_replace(
        path,
        old="""```bash
keytool -genkeypair \\
  -v \\
  -storetype PKCS12 \\
  -keystore yt-offline-release.jks \\
  -alias yt-offline \\
  -keyalg RSA \\
  -keysize 2048 \\
  -validity 10000
```""",
        new="""```bash
keytool -genkeypair \\
  -v \\
  -storetype PKCS12 \\
  -keystore kinescope-release.jks \\
  -alias kinescope \\
  -keyalg RSA \\
  -keysize 2048 \\
  -validity 10000
```""",
        already_applied_marker="-keystore kinescope-release.jks",
        label="rename keystore filename/alias in keytool command",
    )

    guarded_replace(
        path,
        old="**Move `yt-offline-release.jks` somewhere outside the repo folder**",
        new="**Move `kinescope-release.jks` somewhere outside the repo folder**",
        already_applied_marker="**Move `kinescope-release.jks` somewhere outside the repo folder**",
        label="rename keystore filename in move-it-out-of-repo note",
    )

    guarded_replace(
        path,
        old="""```properties
storeFile=/home/vscode/keys/yt-offline-release.jks
storePassword=<the store password you set above>
keyAlias=yt-offline
keyPassword=<the key password you set above>
```""",
        new="""```properties
storeFile=/home/vscode/keys/kinescope-release.jks
storePassword=<the store password you set above>
keyAlias=kinescope
keyPassword=<the key password you set above>
```""",
        already_applied_marker="storeFile=/home/vscode/keys/kinescope-release.jks",
        label="rename keystore filename/alias in keystore.properties example",
    )

    # Section 3: split into "local build" (existing) vs. "CI build" (new).
    guarded_replace(
        path,
        old="""## 3. Build the signed release APK

```bash
./gradlew assembleRelease
```

Output lands at:

```
app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` isn't present, this still builds — just
unsigned — rather than failing (see the comment in
`app/build.gradle.kts`). An unsigned APK won't install on a normal
device, so make sure step 2 is done first.""",
        new="""## 3. Build the signed release APK

Two ways to get a signed APK. Local build needs no extra setup beyond
step 2 above; CI build needs a one-time repo-secrets setup but doesn't
require downloading the keystore into every Codespace, and matches how
debug builds already work (`.github/workflows/build-debug.yml`).

### Option A: Local build in Codespace

```bash
./gradlew assembleRelease
```

Output lands at:

```
app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` isn't present, this still builds -- just
unsigned -- rather than failing (see the comment in
`app/build.gradle.kts`). An unsigned APK won't install on a normal
device, so make sure step 2 is done first.

### Option B: CI build via GitHub Actions

One-time setup: add four repository secrets under **Settings -> Secrets
and variables -> Actions -> New repository secret**:

| Secret name         | Value                                         |
|----------------------|------------------------------------------------|
| `KEYSTORE_BASE64`    | base64 of `kinescope-release.jks` (see below)  |
| `KEYSTORE_PASSWORD`  | the store password from step 1                 |
| `KEY_ALIAS`          | `kinescope` (or whatever alias you used)       |
| `KEY_PASSWORD`       | the key password from step 1                   |

Generate the base64 value from the keystore you created in step 1
(before you moved it out of the repo folder, or from wherever you moved
it to):

```bash
base64 -w 0 kinescope-release.jks > kinescope-release.jks.base64.txt
```

Paste the contents of that `.txt` file as `KEYSTORE_BASE64`'s value,
then delete the `.txt` file locally -- it's no longer needed once it's
in GitHub's secret store, and it's an unencrypted copy of your signing
key while it exists.

Then, under this repo's **Actions** tab, run the *Build Signed Release
APK* workflow (`.github/workflows/build-release.yml`) by hand, supply a
version name, and download the signed APK from the run's Artifacts --
same manual-trigger, hand-versioned design as the debug build workflow.
The workflow decodes the keystore into a runner-local temp file for the
build only and deletes it (along with the generated
`keystore.properties`) before the job ends; the keystore itself never
touches the repository.""",
        already_applied_marker="### Option B: CI build via GitHub Actions",
        label="split section 3 into local/CI build options",
    )

    guarded_replace(
        path,
        old="""gh release create v1.0.0 \\
  app/build/outputs/apk/release/app-release.apk \\
  --title "YT Offline v1.0.0" \\
  --notes "First signed build."
```""",
        new="""gh release create v1.0.0 \\
  app/build/outputs/apk/release/app-release.apk \\
  --title "Kinescope v1.0.0" \\
  --notes "First signed build."
```

(Substitute the downloaded `kinescope-<version>-release.apk` path if
you built via Option B instead of a local build.)""",
        already_applied_marker='--title "Kinescope v1.0.0"',
        label="rename release title, note CI artifact path",
    )

    guarded_replace(
        path,
        old="""Bump `versionCode` (and usually `versionName`) in
`app/build.gradle.kts` before each new signed build — Android refuses
to "update" an installed app with a build that has the same or lower
`versionCode`. As long as you sign with the **same** keystore from
step 1, installing a new version over the old one keeps your settings
and doesn't require uninstalling first.""",
        new="""Bump `versionCode` (and usually `versionName`) in
`app/build.gradle.kts` before each new signed build — Android refuses
to "update" an installed app with a build that has the same or lower
`versionCode`. As long as you sign with the **same** keystore from
step 1, installing a new version over the old one keeps your settings
and doesn't require uninstalling first.

If you're building via Option B (CI), `versionCode` is derived
automatically from the workflow run number, so you don't need to bump
it by hand -- just supply a new `versionName` each run. The manual bump
above only matters for local (Option A) builds.""",
        already_applied_marker="If you're building via Option B (CI), `versionCode` is derived",
        label="clarify versionCode auto-bump for CI builds",
    )


# ---------------------------------------------------------------------------
# 3. README.md
# ---------------------------------------------------------------------------

def patch_readme_md():
    path = "README.md"

    guarded_replace(
        path,
        old="""**Alternative: build via GitHub Actions.** If adb isn't available, or
downloading the APK through the Codespace browser UI is inconvenient,
use the *Build Debug APK* workflow under this repo's Actions tab
(`.github/workflows/build-debug.yml`) instead of steps 3-4 above — run
it by hand, supply a version name, and download the resulting APK from
the run's Artifacts. No automatic trigger; each run is a deliberate,
manually-versioned build.""",
        new="""**Alternative: build via GitHub Actions.** If adb isn't available, or
downloading the APK through the Codespace browser UI is inconvenient,
use the *Build Debug APK* workflow under this repo's Actions tab
(`.github/workflows/build-debug.yml`) instead of steps 3-4 above — run
it by hand, supply a version name, and download the resulting APK from
the run's Artifacts. No automatic trigger; each run is a deliberate,
manually-versioned build.

**Signed release builds** work the same way via the *Build Signed
Release APK* workflow (`.github/workflows/build-release.yml`) — see
`RELEASE.md` for the one-time keystore/secrets setup it needs.""",
        already_applied_marker="**Signed release builds** work the same way via the *Build Signed",
        label="mention release workflow in build instructions",
    )

    guarded_replace(
        path,
        old="""- **`RELEASE.md`** -- signing-key generation and the signed-release
  process (Step 9 -- not started yet).""",
        new="""- **`RELEASE.md`** -- signing-key generation and the signed-release
  process (Step 9 -- CI workflow in place; pending the user's one-time
  keystore/secrets setup and a first confirmed run).""",
        already_applied_marker="CI workflow in place; pending the user's one-time",
        label="update Step 9 status in doc map",
    )


# ---------------------------------------------------------------------------
# 4. ROADMAP.md
# ---------------------------------------------------------------------------

def patch_roadmap_md():
    path = "ROADMAP.md"

    guarded_replace(
        path,
        old="""**Per the user's
explicit direction (patch 19), Step 9 (signed release) starts next**,
ahead of Step 5's manual on-device checklist being individually
itemized and confirmed back — see the Step 9 section below for what
that means in practice.""",
        new="""**Per the user's
explicit direction (patch 19), Step 9 (signed release) starts next**,
ahead of Step 5's manual on-device checklist being individually
itemized and confirmed back — see the Step 9 section below for what
that means in practice. **Patch 20** delivered Step 9's CI
infrastructure (a `build-release.yml` workflow, mirroring
`build-debug.yml`), per the user's direction to build release APKs via
GitHub Actions rather than a local Codespace build — but, same as
`build-debug.yml` before patch 18's fix, this isn't marked done until
the user has done the one-time keystore/secrets setup and confirmed a
real signed run actually works.""",
        already_applied_marker="**Patch 20** delivered Step 9's CI",
        label="status header: note patch 20's CI infra",
    )

    guarded_replace(
        path,
        old="""## Step 9 — Signed release

Originally: only once **every item in Steps 1–5 is done and confirmed
on a real device**. Follow `RELEASE.md` in full — do not skip ahead to
save time; an unverified debug build signed into a release build is
still unverified.""",
        new="""## Step 9 — Signed release

Originally: only once **every item in Steps 1–5 is done and confirmed
on a real device**. Follow `RELEASE.md` in full — do not skip ahead to
save time; an unverified debug build signed into a release build is
still unverified.

- [x] Release signing config in `app/build.gradle.kts` — reads
      `keystore.properties`, falls back to an unsigned build if it's
      absent. Predates this patch, unchanged by it.
- [x] CI workflow to build a signed release APK
      (`.github/workflows/build-release.yml`) — added patch 20. Mirrors
      `build-debug.yml`'s manual-trigger, hand-versioned design; decodes
      a base64 keystore secret into a runner-local temp file for the
      build only and deletes it before the job ends. Full setup
      instructions in `RELEASE.md`'s "CI build (GitHub Actions)"
      section.
- [ ] One-time: generate the release keystore and add the
      `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
      `KEY_PASSWORD` repo secrets (see `RELEASE.md`) — needs the user,
      Claude has no access to GitHub repo secrets or a keystore to
      generate on their behalf.
- [ ] Trigger the workflow once, download the resulting APK, and
      confirm it actually installs and opens correctly on a real
      device. **This is the item that flips Step 9 to done** — same
      standard `build-debug.yml` was held to (not marked confirmed
      until patch 18's fix was verified by a real successful Actions
      run, not just code review).""",
        already_applied_marker="- [x] Release signing config in `app/build.gradle.kts` — reads",
        label="Step 9: add checklist splitting delivered infra vs. pending user action",
    )

    guarded_replace(
        path,
        old="""4. **Step 9 — Signed release** ← next, per explicit user direction
   (patch 19) — see the Step 9 section for what this means for Step
   5's still-unconfirmed manual checklist""",
        new="""4. **Step 9 — Signed release** ← CI infrastructure delivered (patch
   20); awaiting the user's one-time keystore/secrets setup and a
   confirmed real run before this step counts as done — see the Step 9
   section for what this means for Step 5's still-unconfirmed manual
   checklist""",
        already_applied_marker="CI infrastructure delivered (patch\n   20)",
        label="execution order: update Step 9 line",
    )


# ---------------------------------------------------------------------------
# 5. HANDOFF.md
# ---------------------------------------------------------------------------

def patch_handoff_md():
    path = "HANDOFF.md"

    guarded_replace(
        path,
        old="""**Per the user's explicit direction (patch
19), the next step is Step 9 (signed release)** -- ahead of Step 5's
manual on-device checklist being individually gone through and
reported back. See "Immediate next step" below for what that does and
doesn't mean.""",
        new="""**Per the user's explicit direction (patch
19), the next step is Step 9 (signed release)** -- ahead of Step 5's
manual on-device checklist being individually gone through and
reported back. **Patch 20 delivered Step 9's CI workflow**
(`.github/workflows/build-release.yml`), per the user's direction to
build release APKs via GitHub Actions rather than a local Codespace
build -- but it isn't confirmed working yet: the user still needs to do
a one-time keystore/secrets setup and trigger a real run. See
"Immediate next step" below for what that does and doesn't mean.""",
        already_applied_marker="**Patch 20 delivered Step 9's CI workflow**",
        label="milestone paragraph: note patch 20",
    )

    guarded_replace(
        path,
        old="""- **Patch 19** -- Documentation/handoff update (no app code changes;
  patch 17/18's own scripts did need a small fix, see below). The
  user confirmed patch 18's fix worked: a real GitHub Actions run
  succeeded and produced a downloadable debug APK. Recorded that
  confirmation across `ROADMAP.md`/`HANDOFF.md`, and recorded an
  explicit decision from the user: **Step 9 (signed release) starts
  next**, ahead of Step 5's manual on-device checklist being
  individually gone through and reported back -- logged as a standing
  decision in `CLAUDE.md`'s instruction log so a future session acts on
  it rather than re-litigating `ROADMAP.md`'s original Step 9 gate.
  `ROADMAP.md`'s execution order, Step 5 section, and Step 9 section
  all updated to match, while preserving the original caution's actual
  point (a signed release is still built from unverified code) as
  context rather than deleting it outright. **Also vaccinated patch 16,
  17, and 18's own scripts, two levels deep:** `patch_claude_md()`
  (patch 16), `patch_roadmap()` (patch 17), and
  `patch_changelog()`/`patch_handoff()` (patch 18) each needed a
  `superseded_marker` for this patch's direct edits to those files.
  Then, since patch 17 also patches patch 16's *script file* (it
  already had a `patch_patch16_script()` function, added in patch 17
  itself to fix an earlier collision), and this patch's fix to patch
  16's script changes that same script file again, patch 17's check on
  it needed a `superseded_marker` too -- only surfaced on a second
  full-chain regression run after the first round of fixes existed,
  since the collision couldn't happen until they did. This snapshot is
  meant to be pasted into a new conversation next, per the user's own
  request.""",
        new="""- **Patch 19** -- Documentation/handoff update (no app code changes;
  patch 17/18's own scripts did need a small fix, see below). The
  user confirmed patch 18's fix worked: a real GitHub Actions run
  succeeded and produced a downloadable debug APK. Recorded that
  confirmation across `ROADMAP.md`/`HANDOFF.md`, and recorded an
  explicit decision from the user: **Step 9 (signed release) starts
  next**, ahead of Step 5's manual on-device checklist being
  individually gone through and reported back -- logged as a standing
  decision in `CLAUDE.md`'s instruction log so a future session acts on
  it rather than re-litigating `ROADMAP.md`'s original Step 9 gate.
  `ROADMAP.md`'s execution order, Step 5 section, and Step 9 section
  all updated to match, while preserving the original caution's actual
  point (a signed release is still built from unverified code) as
  context rather than deleting it outright. **Also vaccinated patch 16,
  17, and 18's own scripts, two levels deep:** `patch_claude_md()`
  (patch 16), `patch_roadmap()` (patch 17), and
  `patch_changelog()`/`patch_handoff()` (patch 18) each needed a
  `superseded_marker` for this patch's direct edits to those files.
  Then, since patch 17 also patches patch 16's *script file* (it
  already had a `patch_patch16_script()` function, added in patch 17
  itself to fix an earlier collision), and this patch's fix to patch
  16's script changes that same script file again, patch 17's check on
  it needed a `superseded_marker` too -- only surfaced on a second
  full-chain regression run after the first round of fixes existed,
  since the collision couldn't happen until they did. This snapshot is
  meant to be pasted into a new conversation next, per the user's own
  request.
- **Patch 20** -- Step 9: CI-based signed release build. Added
  `.github/workflows/build-release.yml`, mirroring `build-debug.yml`'s
  manual-`workflow_dispatch`, hand-versioned design, but running
  `assembleRelease` against a keystore assembled at runtime from four
  repo secrets (`KEYSTORE_BASE64`, decoded to a runner-local temp file;
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), written into a
  `keystore.properties` the existing signing config in
  `app/build.gradle.kts` already knows how to read -- that config
  predates this patch and needed no changes. Both the decoded keystore
  and the generated `keystore.properties` are deleted in an `if:
  always()` cleanup step so they don't persist past the build even on
  failure. This was a direct user request, not an autonomous roadmap
  step: building signed releases via GitHub Actions instead of a local
  Codespace `./gradlew assembleRelease`, matching how debug builds
  already work. `RELEASE.md` renamed its leftover `yt-offline`
  keystore-filename/alias/release-title naming to `kinescope` (flagged
  as cosmetic debt back in patch 19) and gained a "CI build (GitHub
  Actions)" section alongside the existing local-build option, which
  is kept since it still works and needs no secrets setup. `README.md`
  and `ROADMAP.md` updated to point at the new workflow. **Not marked
  as Step 9 done**: same as `build-debug.yml` wasn't marked confirmed
  until patch 18's real Actions run succeeded, this workflow's own
  first real run -- which needs the user to generate a keystore and add
  the four secrets first, since Claude has no access to do either --
  hasn't happened yet. See `ROADMAP.md`'s Step 9 section for the exact
  remaining checklist.""",
        already_applied_marker="- **Patch 20** -- Step 9: CI-based signed release build.",
        label="patch history: add Patch 20 entry",
    )

    guarded_replace(
        path,
        old="""1. **Step 9 -- Signed release, next.** Per `RELEASE.md`. This starts
   now even though Step 5's manual checklist (below) hasn't been
   individually gone through and reported back -- a deliberate call by
   the project owner, recorded in `CLAUDE.md`'s instruction log. Don't
   re-litigate this or refuse citing `ROADMAP.md`'s original "only
   once Step 5 is confirmed" language; that language is still there,
   with a patch-19 note explaining the override. What it still gets
   right: a signed release is built from the same code Step 5 would
   have exercised, so anything that checklist would have caught (the
   race-condition stress test, `friendlyError()` against real yt-dlp
   output, airplane-mode behavior) is genuinely still unverified -- it
   just isn't blocking Step 9 from starting. If something during Step
   9 depends on one of those actually being true, say so, don't assume
   it's fine because Step 9 was authorized. Note: `RELEASE.md` still
   uses the old `yt-offline` name for the keystore filename/alias and
   the GitHub release title -- cosmetic, worth a quick pass while
   already in that file for Step 9.""",
        new="""1. **Step 9 -- Signed release.** CI infrastructure delivered (patch
   20): `.github/workflows/build-release.yml` builds a signed release
   APK from four repo secrets, mirroring `build-debug.yml`. What's
   left is entirely on the user's side and can't be advanced further
   from here: (a) generate the release keystore and add the
   `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
   repo secrets (`RELEASE.md`'s "CI build" section has the exact
   steps), then (b) trigger the workflow once and confirm the APK it
   produces actually installs and opens on a real device. Only (b)
   flips Step 9 to done -- same standard `build-debug.yml` was held to
   (patch 18). Separately, and still true regardless of CI
   infrastructure: a signed release is built from the same code Step
   5 would have exercised, so anything that checklist would have
   caught (the race-condition stress test, `friendlyError()` against
   real yt-dlp output, airplane-mode behavior) is genuinely still
   unverified. `RELEASE.md`'s old `yt-offline` naming (keystore
   filename/alias, GitHub release title) has been renamed to
   `kinescope` as part of patch 20.""",
        already_applied_marker="1. **Step 9 -- Signed release.** CI infrastructure delivered (patch",
        label="'not done' list: update Step 9 item",
    )

    guarded_replace(
        path,
        old="""**Step 9 (signed release) is next**, per the user's explicit direction
recorded in `CLAUDE.md`'s instruction log and `ROADMAP.md`'s Step 9
section (patch 19) -- ahead of Step 5's manual on-device checklist
being individually gone through and reported back. This is a
deliberate call by the project owner, not an oversight or something to
push back on; act on it. The one thing worth keeping in mind while
doing so: a signed release is built from the same code Step 5 would
have exercised, so anything that checklist would have caught is
genuinely still unverified -- if something during Step 9 turns out to
depend on one of those things actually working (the race-condition
fix, `friendlyError()`'s string matching, etc.), say so plainly rather
than assuming it's fine.

Follow `RELEASE.md` in full for Step 9. Note it still uses the old
`yt-offline` name for the keystore filename/alias and the GitHub
release title -- worth a quick pass while already in that file. The
compile itself is confirmed working both locally and via
`.github/workflows/build-debug.yml` (patch 16, fixed patch 18) with a
real successful Actions run, so no environment debugging is expected
going in.""",
        new="""**Step 9 (signed release)'s CI infrastructure was delivered in patch
20**: `.github/workflows/build-release.yml`, a signed-build counterpart
to `build-debug.yml`. Nothing further to *build* here until the user
does the one-time keystore/secrets setup and reports back the result
of a real run -- see `ROADMAP.md`'s Step 9 checklist for the exact
remaining items, all of which need the user's own action (generating a
keystore, adding repo secrets, triggering the workflow, installing the
result on a real device). Don't mark Step 9 done based on the workflow
existing or looking correct on review -- the same as `build-debug.yml`
wasn't marked confirmed until patch 18's fix was verified by an actual
successful Actions run, not just code review. If the user reports the
workflow failed, debug from the actual error output (same discipline as
patch 18), not by guessing.

In the meantime, or once the user reports Step 9 confirmed working:
**Step 5's manual on-device checklist** is next in priority (still
open, `ROADMAP.md`'s Step 5 section has the full list), followed by
`roadmap.md`'s GPT Astra audit once both Step 5 and Step 9 are
genuinely done.""",
        already_applied_marker="**Step 9 (signed release)'s CI infrastructure was delivered in patch",
        label="immediate next step: reflect patch 20 delivery",
    )


# ---------------------------------------------------------------------------
# 6. CLAUDE.md
# ---------------------------------------------------------------------------

def patch_claude_md():
    path = "CLAUDE.md"

    guarded_replace(
        path,
        old="""- Work in sprints across conversations: continue autonomously through
  a batch of `ROADMAP.md` work without stopping between individual
  steps, then deliver one Python patch script at the end covering the
  whole sprint (not one patch per tiny change) — the user installs
  several sprints' patches together.""",
        new="""- Work in sprints across conversations: continue autonomously through
  a batch of `ROADMAP.md` work without stopping between individual
  steps, then deliver one Python patch script at the end covering the
  whole sprint (not one patch per tiny change) — the user installs
  several sprints' patches together.
- **Decision (patch 20): release builds (Step 9) are produced via
  GitHub Actions** (`.github/workflows/build-release.yml`), not a
  local `./gradlew assembleRelease` run in the Codespace — matches how
  debug builds already work. The local build path in `RELEASE.md`
  Option A is kept as a working fallback, not removed.""",
        already_applied_marker="**Decision (patch 20): release builds (Step 9) are produced via",
        label="instruction log: record CI-for-release decision",
    )


# ---------------------------------------------------------------------------
# 7. CHANGELOG.md
# ---------------------------------------------------------------------------

def patch_changelog_md():
    path = "CHANGELOG.md"

    guarded_replace(
        path,
        old="""Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.

## Patch 19 — Documentation/handoff update, pivot to Step 9""",
        new="""Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.

## Patch 20 — Step 9: CI-based signed release build

### Added
- `.github/workflows/build-release.yml` -- manual-trigger workflow that
  builds a signed release APK. Assembles `keystore.properties` at
  runtime from four repo secrets (`KEYSTORE_BASE64` decoded to a
  runner-local temp file, plus `KEYSTORE_PASSWORD`/`KEY_ALIAS`/
  `KEY_PASSWORD`), runs `assembleRelease` against the existing signing
  config in `app/build.gradle.kts` (unchanged by this patch), then
  deletes both the decoded keystore and `keystore.properties` in an
  `if: always()` step. Mirrors `build-debug.yml`'s manual
  `workflow_dispatch` trigger and run-number-derived `versionCode`.
- `RELEASE.md`'s "CI build (GitHub Actions)" section, documenting the
  one-time secrets setup and how to trigger the new workflow. Kept the
  existing local-build path (`assembleRelease` in the Codespace) as
  Option A alongside it.

### Changed
- `RELEASE.md` -- renamed leftover `yt-offline` keystore
  filename/alias and GitHub release title to `kinescope` (flagged as
  cosmetic debt in patch 19).
- `README.md` -- build section mentions the new release workflow; the
  `RELEASE.md` doc-map entry no longer says "not started yet".
- `ROADMAP.md`, `HANDOFF.md` -- Step 9 status split into what this
  patch delivered (the CI workflow itself) vs. what still needs the
  user's own action before Step 9 counts as done: generating a
  keystore, adding the four repo secrets, and confirming one real
  signed run actually installs on a device. Not marking this done
  without that confirmation follows the same discipline
  `build-debug.yml` was held to (not confirmed until patch 18's real
  Actions run succeeded).
- `CLAUDE.md` -- instruction log: recorded the CI-for-release decision.

## Patch 19 — Documentation/handoff update, pivot to Step 9""",
        already_applied_marker="## Patch 20 — Step 9: CI-based signed release build",
        label="add Patch 20 entry",
    )


# ---------------------------------------------------------------------------

def main():
    print("Applying patch 20 (Step 9: CI-based signed release build)...")
    patch_build_release_workflow()
    patch_release_md()
    patch_readme_md()
    patch_roadmap_md()
    patch_handoff_md()
    patch_claude_md()
    patch_changelog_md()
    print("Patch 20 complete.")


if __name__ == "__main__":
    main()
