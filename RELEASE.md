# Release signing & distribution

Personal sideload distribution only — see CLAUDE.md ground rules.
Nothing here should be run anywhere but your own Codespace/machine;
the keystore is a secret and never gets committed.

## 1. Generate a signing key (once)

Run this **inside the Codespace terminal**, not anywhere Claude can
see the output — `keytool` will prompt for passwords interactively:

```bash
keytool -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore kinescope-release.jks \
  -alias kinescope \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

It'll ask for a store password, a key password (can be the same as
the store password), and some identity fields (name/org/etc — for a
personal app these can be anything, they're not verified by anyone).

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
storePassword=<the store password you set above>
keyAlias=kinescope
keyPassword=<the key password you set above>
```

Use the actual absolute path to wherever you moved the `.jks` file in
step 1.

## 3. Build the signed release APK

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
touches the repository.

## 4. Publish it somewhere you can reach from your phone

Simplest: a **private GitHub Release** on this repo.

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

If you're building via Option B (CI), `versionCode` is derived
automatically from the workflow run number, so you don't need to bump
it by hand -- just supply a new `versionName` each run. The manual bump
above only matters for local (Option A) builds.
