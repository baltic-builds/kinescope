#!/usr/bin/env python3
"""
Patch 21 -- APK size trim + RELEASE.md keystore-password fix.

Two small, related fixes discovered while actually running Step 9's CI
signed build for the first time (patch 20's infrastructure, now
confirmed working end to end):

1. The signed release APK came out at ~200MB. `app/build.gradle.kts`'s
   `ndk.abiFilters` was building for all four ABIs
   (armeabi-v7a/arm64-v8a/x86/x86_64); `youtubedl-android` bundles a
   full Python runtime, ffmpeg, ffprobe, and QuickJS as native
   libraries per ABI, which is what actually dominates the size (see
   the Gradle build log's "Unable to strip the following libraries"
   line). This was already a known, flagged Backlog item in
   ROADMAP.md ("Trim x86/x86_64 from ndk.abiFilters ... if the target
   phone is arm64"); this patch acts on it now that it's no longer
   theoretical. Trimmed to `arm64-v8a` only, which covers the
   overwhelming majority of real Android phones since ~2019.

2. RELEASE.md's Step 1 said the keytool key password "can be the same
   as the store password" -- true, but incomplete: for a PKCS12
   keystore (this project's `-storetype`), the store and key passwords
   MUST be identical. Java's PKCS12 implementation doesn't support a
   separate per-key password; if you give keytool two different ones,
   it silently keeps only the store password for the actual key
   encryption. The keystore then fails to open later with
   `KeytoolException: ... Given final block not properly padded` -- a
   wrong-password decryption error that gives no hint the root cause
   was two mismatched passwords. Hit this for real during patch 20's
   first CI signing attempts; fixed by generating both passwords as
   one value from the start. RELEASE.md's Step 1 now does that
   non-interactively instead of relying on manually typing the same
   thing twice at two separate prompts.

Also, while setting up Step 9's secrets, the default `gh` CLI token
available inside a GitHub Codespace turned out to lack the admin
rights needed to write repository Actions secrets (fails with "HTTP
403: Must have admin rights to Repository") -- a known `gh`/Codespaces
limitation, not a bug in this project. Setting secrets from a
Codespace needs a separate Personal Access Token (`repo` scope) passed
via `GH_TOKEN=<pat> gh secret set ...`, not the Codespace's own
ambient auth. Recorded here since it isn't obvious from the error
message alone.

Updates: app/build.gradle.kts, RELEASE.md, ROADMAP.md, CHANGELOG.md,
HANDOFF.md.

Run from the repo root:
    python3 patch21_apk_size_and_keystore_docs.py

Idempotent: safe to run twice.
"""

import os

REPO_ROOT = os.getcwd()


def read(path):
    with open(os.path.join(REPO_ROOT, path), "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    with open(os.path.join(REPO_ROOT, path), "w", encoding="utf-8") as f:
        f.write(content)


def guarded_replace(path, old, new, already_applied_marker, label):
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


# ---------------------------------------------------------------------------
# 1. app/build.gradle.kts -- trim abiFilters to arm64-v8a
# ---------------------------------------------------------------------------

def patch_gradle():
    path = "app/build.gradle.kts"
    guarded_replace(
        path,
        old="""        // youtubedl-android bundles native Python/yt-dlp binaries per
        // ABI; without this the APK would try to include every ABI
        // and bloat, or fail to package correctly on some setups.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }""",
        new="""        // youtubedl-android bundles a full Python runtime, ffmpeg,
        // ffprobe, and QuickJS as native libraries -- multiplied per
        // ABI, this is what made the release APK ~200MB (confirmed by
        // a real build, patch 21; see CHANGELOG.md). arm64-v8a covers
        // the overwhelming majority of real Android phones since
        // ~2019; x86/x86_64 only matter for emulators, and
        // armeabi-v7a only for older 32-bit devices. Add
        // "armeabi-v7a" back to this list if you ever need to install
        // on one of those.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }""",
        already_applied_marker='abiFilters += listOf("arm64-v8a")',
        label="trim ndk.abiFilters to arm64-v8a",
    )


# ---------------------------------------------------------------------------
# 2. RELEASE.md -- PKCS12 same-password fix
# ---------------------------------------------------------------------------

def patch_release_md():
    path = "RELEASE.md"

    guarded_replace(
        path,
        old="""Run this **inside the Codespace terminal**, not anywhere Claude can
see the output — `keytool` will prompt for passwords interactively:

```bash
keytool -genkeypair \\
  -v \\
  -storetype PKCS12 \\
  -keystore kinescope-release.jks \\
  -alias kinescope \\
  -keyalg RSA \\
  -keysize 2048 \\
  -validity 10000
```

It'll ask for a store password, a key password (can be the same as
the store password), and some identity fields (name/org/etc — for a
personal app these can be anything, they're not verified by anyone).""",
        new="""Run this **inside the Codespace terminal**, not anywhere Claude can
see the output:

```bash
PASS="$(openssl rand -base64 24)"
echo "Keystore password (save this now, it's shown only once): $PASS"

keytool -genkeypair \\
  -v \\
  -storetype PKCS12 \\
  -keystore kinescope-release.jks \\
  -alias kinescope \\
  -keyalg RSA \\
  -keysize 2048 \\
  -validity 10000 \\
  -storepass "$PASS" \\
  -keypass "$PASS" \\
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
personal app).""",
        already_applied_marker='Store password and key password must be identical',
        label="fix Step 1: non-interactive keytool + PKCS12 same-password requirement",
    )

    guarded_replace(
        path,
        old="""```properties
storeFile=/home/vscode/keys/kinescope-release.jks
storePassword=<the store password you set above>
keyAlias=kinescope
keyPassword=<the key password you set above>
```""",
        new="""```properties
storeFile=/home/vscode/keys/kinescope-release.jks
storePassword=<the password you generated in step 1>
keyAlias=kinescope
keyPassword=<the same password -- must match storePassword, see step 1>
```""",
        already_applied_marker="must match storePassword, see step 1",
        label="clarify keystore.properties placeholders match one password",
    )


# ---------------------------------------------------------------------------
# 3. ROADMAP.md
# ---------------------------------------------------------------------------

def patch_roadmap_md():
    path = "ROADMAP.md"

    guarded_replace(
        path,
        old="""- [ ] One-time: generate the release keystore and add the
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
        new="""- [x] One-time: generate the release keystore and add the
      `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
      `KEY_PASSWORD` repo secrets — done. Took several attempts; see
      `CHANGELOG.md`'s Patch 21 entry for the two real gotchas hit
      along the way (the default Codespaces `gh` token lacking
      repo-admin rights to write secrets, and PKCS12 keystores
      requiring an identical store/key password).
- [x] Trigger the workflow and produce a signed APK — confirmed by a
      real successful Actions run. The resulting APK was ~200MB;
      trimmed via the `ndk.abiFilters` Backlog item below (patch 21).
- [ ] Confirm the signed APK actually installs and opens correctly on
      a real device. **This is the one remaining item that flips Step
      9 to fully done** — same standard `build-debug.yml` was held to
      (not marked confirmed until patch 18's fix was verified by a
      real successful Actions run, not just code review).""",
        already_applied_marker="confirmed by a\n      real successful Actions run. The resulting APK was ~200MB",
        label="Step 9 checklist: record confirmed CI signing run",
    )

    guarded_replace(
        path,
        old="""- [ ] Trim `x86`/`x86_64` from `ndk.abiFilters` in `app/build.gradle.kts`
      if the target phone is arm64 (the overwhelming majority are) and
      emulator support isn't needed — shrinks the APK, since
      `youtubedl-android`'s bundled native binaries dominate its size.
      Originally Step 1's one optional, non-blocking item; moved here in
      patch 16 since it was never actually blocking anything.""",
        new="""- [x] Trim `x86`/`x86_64` (and `armeabi-v7a`) from `ndk.abiFilters` in
      `app/build.gradle.kts`, down to `arm64-v8a` only — done, patch 21,
      once the ~200MB real release build made this no longer
      theoretical. Originally Step 1's one optional, non-blocking item;
      moved here in patch 16, acted on in patch 21. Add `armeabi-v7a`
      back if an older 32-bit device ever needs to install this.""",
        already_applied_marker="down to `arm64-v8a` only — done, patch 21,\n      once the ~200MB real release build",
        label="Backlog: check off abiFilters trim",
    )


# ---------------------------------------------------------------------------
# 4. CHANGELOG.md
# ---------------------------------------------------------------------------

def patch_changelog_md():
    path = "CHANGELOG.md"
    guarded_replace(
        path,
        old="""Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.

## Patch 20 — Step 9: CI-based signed release build""",
        new="""Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.

## Patch 21 — APK size trim + keystore-password fix

Follow-up from actually running patch 20's CI signing workflow for the
first time; both findings below came from that real run, not review.

### Changed
- `app/build.gradle.kts` -- `ndk.abiFilters` trimmed from all four ABIs
  down to `arm64-v8a` only. `youtubedl-android`'s bundled Python
  runtime, ffmpeg, ffprobe, and QuickJS native libraries (duplicated
  per ABI) are what made the first real signed build ~200MB; arm64-v8a
  covers the overwhelming majority of real phones since ~2019. Add
  `armeabi-v7a` back if an older 32-bit device needs to install this.
- `RELEASE.md`'s Step 1 -- keytool command is now non-interactive
  (`-storepass`/`-keypass` both set from one generated variable)
  instead of relying on typing the same password twice at separate
  interactive prompts, and now states plainly that store/key passwords
  **must** be identical for a PKCS12 keystore, not merely "can be the
  same."

### Findings (closed)
- **PKCS12 requires identical store/key passwords.** Java's PKCS12
  keystore implementation doesn't support a separate per-key password;
  giving `keytool` two different ones makes it silently keep only the
  store password for the real encryption. The keystore then fails
  later with `KeytoolException: ... Given final block not properly
  padded` when Gradle tries to read the key with the (wrong, ignored)
  key password -- a decryption-with-the-wrong-password error that
  gives no hint the actual cause was two mismatched passwords entered
  at generation time. Cost several regeneration cycles during Step 9
  setup before the actual cause was found.
- **The default Codespaces `gh` CLI token can't write repo secrets.**
  `gh secret set` fails with `HTTP 403: Must have admin rights to
  Repository` using the ambient auth a Codespace provides by default --
  a known `gh`/Codespaces limitation, not specific to this project.
  Needs a separate Personal Access Token (classic, `repo` scope)
  supplied via `GH_TOKEN=<pat> gh secret set ...` to actually write
  repository-level Actions secrets from inside a Codespace.

## Patch 20 — Step 9: CI-based signed release build""",
        already_applied_marker="## Patch 21 — APK size trim + keystore-password fix",
        label="add Patch 21 entry",
    )


# ---------------------------------------------------------------------------
# 5. HANDOFF.md
# ---------------------------------------------------------------------------

def patch_handoff_md():
    path = "HANDOFF.md"

    guarded_replace(
        path,
        old="""**Patch 20 delivered Step 9's CI workflow**
(`.github/workflows/build-release.yml`), per the user's direction to
build release APKs via GitHub Actions rather than a local Codespace
build -- but it isn't confirmed working yet: the user still needs to do
a one-time keystore/secrets setup and trigger a real run. See
"Immediate next step" below for what that does and doesn't mean.""",
        new="""**Patch 20 delivered Step 9's CI workflow**
(`.github/workflows/build-release.yml`), and **patch 21 confirms it
actually works**: a real signed release build succeeded via GitHub
Actions after fixing two real gotchas hit along the way (PKCS12's
same-password requirement, and the default Codespaces `gh` token
lacking rights to write repo secrets -- both in `CHANGELOG.md`'s Patch
21 entry) and trimming the ~200MB APK down via `ndk.abiFilters`. What's
still open: confirming the signed APK actually installs and opens on a
real device -- that's the one item left before Step 9 counts as fully
done. See "Immediate next step" below.""",
        already_applied_marker="**patch 21 confirms it\nactually works**",
        label="milestone paragraph: record patch 21's confirmed CI run",
    )

    guarded_replace(
        path,
        old="""**Step 9 (signed release)'s CI infrastructure was delivered in patch
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
patch 18), not by guessing.""",
        new="""**Step 9's CI workflow is now confirmed working** (patch 21): a real
signed release build succeeded via GitHub Actions. Getting there
surfaced two real gotchas, both fixed and documented in
`CHANGELOG.md`'s Patch 21 entry and `RELEASE.md`: PKCS12 keystores
require an identical store/key password (mismatched ones fail later
with an opaque `Given final block not properly padded` error, not an
obviously-a-password-problem one), and the default `gh` token inside a
Codespace lacks rights to write repo secrets (needs a separate PAT).
The ~200MB first build was trimmed via `ndk.abiFilters` (also patch
21). The one item left before Step 9 counts as fully done: the user
installing the signed APK on a real device and confirming it opens
correctly -- don't mark that done without an explicit report, same
discipline `build-debug.yml` was held to.""",
        already_applied_marker="**Step 9's CI workflow is now confirmed working** (patch 21)",
        label="immediate next step: record patch 21 confirmation",
    )


def main():
    print("Applying patch 21 (APK size trim + keystore-password fix)...")
    patch_gradle()
    patch_release_md()
    patch_roadmap_md()
    patch_changelog_md()
    patch_handoff_md()
    print("Patch 21 complete.")


if __name__ == "__main__":
    main()
