# Release signing & distribution

Personal sideload distribution only — see CLAUDE.md ground rules.
Nothing here should be run anywhere but your own Codespace/machine;
the keystore is a secret and never gets committed.

## 1. Generate a signing key (once)

Run this **inside the Codespace terminal**, not anywhere Claude can
see the output:

```bash
PASS="$(openssl rand -base64 24)"
echo "Keystore password (save this now, it's shown only once): $PASS"

keytool -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore kinescope-release.jks \
  -alias kinescope \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=Kinescope, OU=Personal, O=Personal, L=NA, ST=NA, C=US"
```

**Store password and key password must be identical** for a PKCS12
keystore (this project's `-storetype`) -- Java's PKCS12
implementation doesn't actually support a separate per-key password.
Give `keytool` two different ones (including via the old interactive
prompts, which used to let you type a different value at the "Enter
key password" step) and it silently keeps only the store password for
the real encryption, ignoring what you typed for the key password. The
keystore then fails to open later with `KeytoolException: ... Given
final block not properly padded` -- a wrong-password decryption error
that gives no hint the actual cause was two mismatched passwords. Hit
this for real during patch 20/21's CI signing setup (see
`CHANGELOG.md`'s Patch 21 entry). Generating one password up front and
passing it to both `-storepass` and `-keypass`, as above, avoids the
trap entirely -- no identity fields to fill in interactively either,
they're all supplied by `-dname` (edit that string if you want
different placeholder values; none of it is verified by anyone for a
personal app).

**Move `kinescope-release.jks` somewhere outside the repo folder**
(e.g. your Codespace's home directory, `~/keys/`) so there's no risk
of it ending up in git even by accident. `.gitignore` already
excludes `*.jks` / `*.keystore` / `keystore.properties` as a second
layer of protection, but don't rely on that alone.

If you ever recreate the Codespace from scratch, keep a copy of this
file somewhere durable (e.g. a password manager's file storage) —
losing it means future signed builds can't update earlier installs
without uninstalling first.

## 2. Create `keystore.properties` (once per Codespace/checkout)

In the **repo root** (this file is gitignored, safe to create here):

```properties
storeFile=/home/vscode/keys/kinescope-release.jks
storePassword=<the password you generated in step 1>
keyAlias=kinescope
keyPassword=<the same password -- must match storePassword, see step 1>
```

Use the actual absolute path to wherever you moved the `.jks` file in
step 1.

## 3. Build the signed release APK

Two ways to get a signed APK. Local build remains a fallback. The normal path is the release-only GitHub Actions workflow, which needs a one-time repo-secrets setup and now publishes the verified APK directly to GitHub Releases.

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

Then, under this repo's **Actions** tab, run *Build and Publish Signed Release* (`.github/workflows/build-release.yml`) by hand and supply a version name such as `1.2.0`. The workflow serializes release runs, validates all four secrets, runs `testDebugUnitTest` + `lintDebug`, builds with a monotonic CI versionCode, verifies the APK signature with `apksigner`, verifies zip alignment, checks the application ID / requested version name / arm64-only native payload, creates a SHA-256 checksum, uploads both as a 30-day workflow artifact, and creates a GitHub Release tagged `v<version>` containing the same two files. Optional release notes can be entered at dispatch time; otherwise GitHub generates them. Signing material is deleted in an `if: always()` cleanup step.


### Android 15 / 16 KB page-size note

The release workflow verifies ordinary zip alignment, but that is **not** a claim that every bundled native library is compatible with Android devices using 16 KB memory pages. The currently pinned `youtubedl-android 0.18.1` has an open upstream report for a bundled ffmpeg/libwebp payload that remains 4 KB-aligned. Keep the release ABI at `arm64-v8a`, but treat 16 KB-device support as upstream-blocked until a published wrapper version is verified. Do not vendor a custom replacement native payload just to make this check green without a separate explicit decision.

## 4. Publish it somewhere you can reach from your phone

**CI already does this automatically.** A successful release workflow creates the versioned GitHub Release. The command below is only a local-build fallback if you intentionally used Option A:

```bash
gh release create v1.0.0 \
  app/build/outputs/apk/release/app-release.apk \
  --title "Kinescope v1.0.0" \
  --notes "First signed build."
```

(Substitute the downloaded `kinescope-<version>-release.apk` path if
you built via Option B instead of a local build.)

(`gh` — the GitHub CLI — is already available in the Codespaces base
image. If the repo is private, the release and its APK asset stay
private too; you'll need to be signed into the same GitHub account on
your phone's browser to download it, or transfer the file another
way — e.g. straight over Syncthing, same as the video files
themselves.)

## 5. Install on your phone

Download the APK, then tap it. Android will prompt to allow installs
from whichever app you downloaded it with (browser, GitHub app, file
manager) if that's not already allowed — this is expected for any
sideloaded app and isn't specific to this one.

## Updating later

Bump `versionCode` (and usually `versionName`) in
`app/build.gradle.kts` before each new signed build — Android refuses
to "update" an installed app with a build that has the same or lower
`versionCode`. As long as you sign with the **same** keystore from
step 1, installing a new version over the old one keeps your settings
and doesn't require uninstalling first.

If you're building via Option B (CI), `versionCode` is derived automatically as `10000 + GITHUB_RUN_NUMBER`, so you don't need to bump it by hand -- just supply a new `versionName` each run. The manual bump
above only matters for local (Option A) builds.