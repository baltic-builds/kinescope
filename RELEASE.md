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
  -keystore yt-offline-release.jks \
  -alias yt-offline \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

It'll ask for a store password, a key password (can be the same as
the store password), and some identity fields (name/org/etc — for a
personal app these can be anything, they're not verified by anyone).

**Move `yt-offline-release.jks` somewhere outside the repo folder**
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
storeFile=/home/vscode/keys/yt-offline-release.jks
storePassword=<the store password you set above>
keyAlias=yt-offline
keyPassword=<the key password you set above>
```

Use the actual absolute path to wherever you moved the `.jks` file in
step 1.

## 3. Build the signed release APK

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
device, so make sure step 2 is done first.

## 4. Publish it somewhere you can reach from your phone

Simplest: a **private GitHub Release** on this repo.

```bash
gh release create v1.0.0 \
  app/build/outputs/apk/release/app-release.apk \
  --title "YT Offline v1.0.0" \
  --notes "First signed build."
```

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
