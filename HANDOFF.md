# Handoff Snapshot

Paste this file's contents at the start of a new conversation to
resume work with minimal re-explaining. If the new conversation
doesn't already have repo access, also attach a fresh repomix export
(or paste `CLAUDE.md`, `AGENTS.md`, `ROADMAP.md`, `CHANGELOG.md`,
`CJM.md`, `design.md`, `RELEASE.md`, and `THIRD_PARTY_NOTICES.md` directly).

## Project identity

Personal Android app for downloading YouTube videos at home for
offline viewing during work trips to a network-restricted region.
Sideload-distributed only -- no Google Play, no backend, no required
cost. The rename to **Kinescope** is done: the codebase and package
are `com.kinescope.app` / "Kinescope" (previously
`com.baltic.ytoffline` / "YT Offline").

### Patch 25 / roadmap-completion status
### Patch 25c / AAPT hotfix status

Patch 25b applied the Patch-25 target but exposed one delivery-only compile
blocker during its mandatory Gradle gate: Android's resource compiler rejected
an unescaped ASCII apostrophe in the English `error_control_cleanup_failed`
string. Patch 25c escapes that apostrophe, adds an Android-specific static
resource check for the same class of mistake, and only records completion after
`testDebugUnitTest + lintDebug + assembleDebug` succeeds. No runtime behavior or
roadmap scope changed in this hotfix.


### Patch 28 / YouTube split-tunnel status

The user has now confirmed the two Patch-27 facts that were previously open: GitHub Actions builds successfully and ByeDPI works on the target device/network. Patch 28 builds on that verified base. The bypass Settings flow is now test-first, the Home screen can start a YouTube-only Android `VpnService`, and `hev-socks5-tunnel` bridges the TUN interface into a dedicated ByeDPI SOCKS5 engine hosted in `:dpi_vpn`. The official YouTube package is the only allowed VPN application, so Kinescope and both ByeDPI processes stay outside the TUN and cannot route-loop. Strategy tests/downloads remain in the separate short-lived `:dpi` process; while the YouTube session is active, a shared download automatically uses the same verified strategy without sharing native process state.

Patch 28 also fixes stale STOPPED queue rows and adds end-to-start swipe removal, while reducing the glass navbar footprint without changing the established Kinescope palette/type system. The remaining new device gate is narrow: verify first-run Android VPN consent, YouTube playback under the split tunnel, Share -> Kinescope -> download while the YouTube tunnel remains active and the isolated download engine starts cleanly, notification Stop, and swipe removal. Do not reopen the already-confirmed Patch-27 build/engine questions.

### Patch 27 / network bypass status

Patch 27 vendors the MIT `hufrea/byedpi` C engine and Kinescope's own JNI glue, with strategy parsing/search and a download-only SOCKS5 path. Its sandbox harness and unit tests remain documented in `CHANGELOG.md`. The previously open real-build/runtime gate is now closed by the user's current status: GitHub Actions builds successfully and ByeDPI works on the target device/network. Full design record: `INTEGRATION_PLAN.md` in the project's memory.


Patch 24 / Sprint 1 was successfully applied by the user, built in Codespaces (`BUILD SUCCESSFUL`) and pushed as commit `91169f5`. That confirms compilation of the Sprint-1 code, **not** its new device-dependent behavior. Real-device verification remains open.

Patch 25 consumes the still-relevant engineering work from the former lowercase S0-S11 audit and closes the autonomous reliability/hardening backlog: a durable `AtomicFile` job journal, explicit process-death recovery, strict canonical YouTube URL parsing, stable quality IDs, active-job dedupe, one serialized yt-dlp/ffmpeg/update boundary, per-job workspaces + orphan cleanup, hardened MediaStore commit semantics, off-main-thread library I/O, Android 15 foreground-service timeout handling, notification actions, privacy-redacted diagnostics with backup disabled, JVM tests, stronger release verification, pinned Android command-line tools, accessibility token corrections, and a third-party dependency inventory.

The old audit assumption that authentication / an embedded browser must never exist is **superseded** by the user's explicit Patch-24 requirement for optional YouTube sign-in. The remaining extractor boundary is unchanged: cookies/retries/upstream yt-dlp client fallbacks are allowed; custom extraction, BotGuard/PO-token generation, signature deciphering, or anti-bot bypass code is not.

**Next work is focused Patch-28 device verification, not another autonomous roadmap sprint.** `ROADMAP.md` is now the single roadmap and contains only real-device / real-GitHub-Release checks plus one upstream blocker: the currently pinned `youtubedl-android 0.18.1` must not be advertised as 16 KB page-size compatible while its upstream native-payload issue remains open. The former lowercase `roadmap.md` has been consumed and remains deleted.

**Milestone: the first successful `./gradlew assembleDebug` in this
project's history was achieved via patches 01-14.** The
`.github/workflows/build-debug.yml` CI path (patch 16) failed on its
first real run due to a leaked Codespace-only JDK path; patch 18 fixed
it, and the user has since confirmed a successful GitHub Actions build
and downloaded a debug APK. **Patch 20 delivered Step 9's CI workflow**
(`.github/workflows/build-release.yml`), and **patch 21 confirmed it
actually works**: a real signed release build succeeded via GitHub
Actions after fixing two real gotchas hit along the way (PKCS12's
same-password requirement, and the default Codespaces `gh` token
lacking rights to write repo secrets -- both in `CHANGELOG.md`'s Patch
21 entry) and trimming the ~200MB APK down via `ndk.abiFilters`. **Patch
22** fixed a startup crash hit on the user's first real-device launch
attempt (a Compose icon-loading bug in `EmptyQueueState`, unrelated to
signing/CI), and **patch 23 (this one) confirms the fix**: the user
reports the app now runs correctly end to end on a real device (Android
13) -- install, share/paste a link, queue and complete a download, play
it back, background persistence. **The original 9-step plan is now done
or reduced to specific, named technical debt** -- see `ROADMAP.md`'s
"Technical debt" section for the current, complete list (the signed-APK
device-confirm is the one Step 9 item still open; a handful of more
adversarial Step 5 checks -- the race-condition stress test,
`friendlyError()` against a real broken video, airplane mode, a very
large download -- haven't been individually gone through yet either).

## How this codebase got here

Written by Claude across 7 phases with no intermediate compilation
(explicit user instruction at the time: "keep going, test everything
at the end"). A deep 4-part review by Claude Fable 5.1 then produced
`ROADMAP.md` -- a sequenced, prioritized fix list with a
findings-traceability Appendix (35 rows total across the review and
patches 07-14's own discoveries; as of patch 16 the Appendix only
lists the 5 still-open/informational rows -- closed ones moved to
`CHANGELOG.md`, see its Patch 16 entry). All patches below were
delivered as self-contained Python scripts for GitHub Codespaces, each
one extracted into a working copy and dry-run-verified (diffs +
bracket balance + idempotency, usually across 2-5 full-chain passes)
before being handed over -- never delivered untested:

<!-- patch 01-20 narrative compressed by patch 28 -->
Patches 01-20 took the project from a never-compiled 7-phase draft to a
signed, CI-published release, closing the original review's numbered
roadmap steps one by one. The full patch-by-patch detail (each fix, each
root cause, each thing that was wrong about an earlier assumption) lives
in `CHANGELOG.md`'s own Patch 01 through Patch 20 entries and is not
repeated here -- this summary exists so a fresh session doesn't have to
read 500+ lines of largely-superseded narrative just to get oriented.

In short: patches 01-05 fixed the roadmap's Steps 1-4 and 6 (compile
blockers, a fabricated dependency version, a worker race condition,
filename/MediaStore correctness, the design-token/dark-theme gaps) and
produced this handoff file. Patch 06 executed the Kinescope rename
(`com.baltic.ytoffline` -> `com.kinescope.app`). Patches 07-14 chased
down the environment/toolchain problems blocking the very first
successful `./gradlew assembleDebug` -- a `pipefail`/SIGPIPE setup-script
bug, two rounds of JDK-version mismatches between what the devcontainer
requested and what the Codespace actually had on `PATH`, an invalid `--`
inside an XML comment, and finally a wrongly-assumed top-level class that
turned out to be nested (`YoutubeDL.UpdateChannel`) -- confirmed each
time against the library's own real tagged source rather than secondary
docs. Patch 14 is the first successful build in the project's history.
Patches 15-19 consolidated documentation, added `CHANGELOG.md`/`CJM.md`,
stood up the first CI workflow (`build-debug.yml`), and fixed a
machine-specific JDK path that had been committed into shared
`gradle.properties` and broke on the Actions runner -- surfacing (and
then fixing, repeatedly, one layer deeper each time) the "a later doc
patch breaks an earlier patch's own idempotency check" failure mode
documented below under "Key learnings." Patch 20 added the signed-release
CI workflow (`build-release.yml`).

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
  remaining checklist.
- **Patch 22** -- Fixed a startup crash on the user's first
  real-device launch attempt (Step 5, Android 13): `EmptyQueueState`
  loaded `android.R.drawable.stat_sys_download` via
  `painterResource()`, which only supports a static `VectorDrawable`
  or a rasterized image -- not the `AnimatedVectorDrawable` that
  framework resource actually is on real devices (confirmed against
  `painterResource()`'s own documentation, not assumed). Replaced
  with a small hand-authored static vector
  (`res/drawable/ic_download.xml`). `DownloadService`'s use of the
  same framework resource for `setSmallIcon()` is unaffected --
  notifications accept any drawable resource id directly, no Compose
  involved. Unrelated to Step 9 / CI signing; this is purely a Step 5
  finding.
- **Patch 23** -- Documentation-only: the user confirmed patch 22's fix
  works (the app runs correctly end to end on a real device).
  Folded that confirmation into every project doc, consolidated
  `ROADMAP.md`'s original 9-step plan (now functionally complete) into
  a single "Technical debt" section, rewrote `README.md` as a proper
  GitHub-facing README with CI status badges, and added `AGENTS.md` --
  a process/workflow file for AI coding agents, complementing
  `CLAUDE.md`'s ground rules. Also found and fixed a real, if currently
  harmless, security gap: `kinescope-release.jks.b64` (an empty
  placeholder) is git-tracked and wasn't covered by `.gitignore`'s
  existing `*.jks`/`*.keystore` rules -- fixed by adding
  `*.jks.b64`/`*.jks.base64.txt`.
- **Patch 24** -- First post-roadmap feature sprint. Added bounded
  yt-dlp-only YouTube recovery with nightly refresh and client fallbacks;
  exhausted verification blocks now pause the job for later resume instead
  of terminating it. Added optional app-private YouTube WebView session
  cookies, automatic resume after a successful session capture, Pause /
  Resume / Stop, EN/RU resources, Home/Add/Settings bottom navigation,
  correct back navigation, persistent hidden diagnostics, a new retro-TV
  adaptive icon, and a release-only CI workflow that verifies and publishes
  signed APKs to GitHub Releases. The implementation is intentionally still
  marked unconfirmed until the Sprint 1 device/Actions checklist in
  `ROADMAP.md` is reported back by the user.

**Version-compatibility note worth remembering:** Compose BOM
2024.11.00 (fixed in patch 01) pulls in Material3 **1.3.1**. Some APIs
changed shape in *later* Material3 versions than that -- e.g.
`LinearProgressIndicator`'s lambda-based `progress: () -> Float`
overload wasn't added until 1.5.0-alpha17, so patch 04 deliberately
uses the older plain-`Float` overload (confirmed still valid, not even
deprecated, at 1.3.1 -- verified against the actual androidx API
surface, not assumed; the successful build's compiler output shows it
as merely *deprecated*, not broken, confirming this was the right call
for now). **If a future change bumps the Compose BOM, recheck call
sites like this one against whatever Material3 version the new BOM
actually pulls in.**

- **Patch 25** -- Reliability roadmap completion. Replaced the remaining in-memory-only assumptions with a durable job journal and recoverable `INTERRUPTED` state; added strict/canonical single-video URL parsing, stable quality IDs, duplicate rejection, an engine/update synchronization boundary, per-job workspaces/orphan cleanup, bounded quality fallbacks, MediaStore two-phase commit recovery, library metadata/off-main-thread I/O, Android 15 `dataSync` timeout handling, notification controls, privacy-redacted logging with backup disabled, URL/error JVM tests, release tests/lint/package/ABI verification, a pinned+checksummed Android command-line-tools bootstrap, and `THIRD_PARTY_NOTICES.md`. The former lowercase roadmap was consumed rather than restored; its no-auth/no-WebView premise was superseded by the explicit user requirement in patch 24. Device-dependent verification remains open and is intentionally not claimed here.

- **Patch 27** -- User-requested in-app network bypass: vendored the MIT `hufrea/byedpi` C SOCKS5 desync engine into `app/src/main/cpp/byedpi/` (unmodified), wrote Kinescope's own JNI glue (`dpi_jni.c`) and a `:dpi`-process host service (`DpiEngineService.kt`/`DpiEngine.kt`) so the engine's process-wide C globals never leak state between runs. `DpiStrategyParser` is an allowlist-only parser for the engine's desync CLI syntax (verified against the real upstream strategy list and ~30 hostile inputs). `DpiStrategyStore` holds the enabled flag/selected strategy and an optional HTTPS download of ByeByeDPI's community strategy list. `DpiStrategySearch` tries candidates against three real YouTube-related hosts and keeps the best. `BypassSettingsSection()` adds the Settings UI (on/off, search, per-result selection, connection test, list update). `DownloadService` starts the engine per-attempt when enabled and points yt-dlp's `--proxy` at it (`socks5h`, so the engine resolves host names itself). Verified end-to-end in the sandbox with a host-compiled build of the real engine (JVM harnesses: start/stop/relay through every built-in strategy, a full SOCKS5 CONNECT -> TLS -> HTTP path, and 18 unit tests) -- the real arm64-v8a CMake/NDK build and all on-device behavior are unverified (`ROADMAP.md` gate).

## What's actually done vs. still open

`ROADMAP.md` is now the authoritative, compact list of **verification gates only**. The original 9-step roadmap and the later lowercase S0-S11 audit have both been consumed into patches 01-25. Do not recreate a second roadmap.

**Confirmed:**

- The pre-Sprint-1 core app works end to end on a real Android 13 device (install, share/paste, queue/download, offline playback, background persistence), confirmed around patches 22-23.
- Signed release CI has produced a real signed build in the past (patch 21).
- Patch 24 / Sprint 1 was applied and `assembleDebug` succeeded in Codespaces; commit `91169f5` was pushed. This confirms compile integration only, not the new Sprint-1 UX/recovery behavior on-device.
- Patch 25's delivery gate is `testDebugUnitTest + lintDebug + assembleDebug`; because the patch script self-deletes only after that gate succeeds, a committed patch-25 tree implies those local checks passed.

**Implemented in patch 25, still requiring device/Actions confirmation where applicable:** durable process-death recovery, Pause/Resume/Stop across lifecycle interruptions, strict URL rejection/dedupe, storage commit recovery, typed error UX, notification actions, locale behavior, optional YouTube WebView session, revised release publishing, icon rendering, and large/slow-transfer behavior.

**Known upstream blocker:** Android 15 can run on 16 KB page-size devices, but the pinned `youtubedl-android 0.18.1` currently has an open upstream report for a bundled native ffmpeg/libwebp payload that is still 4 KB-aligned. Do not label Kinescope 16 KB-compatible until a published wrapper update is verified.

Everything still open is enumerated in `ROADMAP.md`. Playlist auto-expansion, a cross-device backend and custom YouTube extraction are deliberately out of scope, not unfinished promises.

## File map (current package `com.kinescope.app`)

All Kotlin runtime files live under `app/src/main/java/com/kinescope/app/`.

- `MainActivity.kt` -- Compose navigation and screens (Home/Add/Settings/Logs/YouTube session), localized queue/library UX, off-main-thread library refresh/delete, notification permission timing and Android Back behavior.
- `DownloadService.kt` -- durable single-worker foreground queue, bounded yt-dlp recovery, Pause/Resume/Stop, per-job workspaces, publication, notification actions and Android 15 timeout handling.
- `DownloadJobStore.kt` -- `AtomicFile` durable journal and process-death normalization/cleanup. This is the source of truth across process death; `DownloadQueueBus` is only the live UI projection.
- `DownloadQueueBus.kt` -- in-process `StateFlow` projection of job status/progress.
- `EngineController.kt` -- one synchronization boundary for yt-dlp/ffmpeg init, extraction and self-update.
- `YouTubeUrlParser.kt` -- pure strict YouTube video URL extraction/canonicalization.
- `DownloadErrorClassifier.kt` -- pure typed classification of yt-dlp failures.
- `QualityPresets.kt` -- stable quality IDs + bounded yt-dlp format selectors.
- `MediaStorage.kt` -- MediaStore two-phase publication/list/delete and actual-output MIME derivation.
- `YouTubeAuth.kt` -- optional app-private YouTube WebView cookie/session capture for yt-dlp.
- `Settings.kt` -- SharedPreferences for stable quality, storage history and yt-dlp update timestamp.
- `AppLog.kt` -- rotating diagnostic journal using the shared privacy filter before file/Logcat output.
- `DiagnosticSanitizer.kt` -- pure URL/cookie/private-path redaction layer covered by JVM tests.
- `YtDlpUpdater.kt` -- nightly yt-dlp update wrapper routed through `EngineController`.
- `YtOfflineApp.kt` -- background journal restoration, engine readiness/update cadence and crash logging.
- `Theme.kt` -- Material3 light/dark tokens, Inter/Lora provider typography, shapes and extended success/warning tokens.
- `DpiNative.kt` / `DpiEngineService.kt` / `DpiEngine.kt` -- bundled DPI-bypass engine (patch 27): JNI binding, `:dpi`-process host service, bind/wait/close client.
- `NetworkCheck.kt` -- direct-vs-bypassed layered reachability probe (DNS/TCP/proxy/CONNECT/TLS/HTTP) with a plain-language `Verdict`.
- `DpiStrategies.kt` -- allowlist-only parser for the engine's desync CLI syntax + offline built-in strategies.
- `DpiStrategyStore.kt` -- bypass on/off + selected-strategy preferences, optional community strategy-list download.
- `DpiSearch.kt` / `DpiBypass.kt` -- strategy search against real probe hosts; glue used by both the download path and the Settings UI.
- `BypassSettings.kt` -- the Settings section Compose UI for the bypass feature.
- `app/src/main/cpp/` -- vendored ByeDPI C engine (`byedpi/`, MIT, unmodified) + Kinescope's own JNI glue (`dpi_jni.c`) and `CMakeLists.txt`.

Tests live under `app/src/test/java/com/kinescope/app/` and currently cover URL parsing, yt-dlp error classification and diagnostic privacy redaction.

Build/release infrastructure: `.devcontainer/setup.sh` pins Android command-line tools by official archive checksum; `.github/workflows/build-release.yml` is the only CI workflow and publishes signed releases. See `RELEASE.md`.

Documentation sources of truth: `CLAUDE.md` (constraints/decisions), `AGENTS.md` (working process), this file (session state), `ROADMAP.md` (remaining verification), `CHANGELOG.md` (history), `CJM.md`, `design.md`, `RELEASE.md`, and `THIRD_PARTY_NOTICES.md`.

## Key learnings & principles

- **Machine-specific config must never be written into a committed,
  shared file.** Patch 12 pinned Gradle's JDK by writing an absolute
  path into the project's own tracked `gradle.properties` -- it worked
  perfectly in the one Codespace that path existed in, and broke
  silently (well, not silently -- loudly, but only once someone else's
  environment actually tried to build) for every other environment
  that cloned the repo, surfacing four patches later the first time
  the new GitHub Actions workflow (patch 16) actually ran (patch 18).
  The fix -- writing to `$HOME/.gradle/gradle.properties` instead,
  which Gradle prioritizes over the project file and which never
  leaves the machine -- was available the whole time; the bug was
  writing to the wrong file, not the detection logic itself, which was
  correct from patch 12 onward. General principle: anything derived
  from *this specific machine's* filesystem layout belongs in a
  user-level/local config location, never in a file that gets `git
  add`ed.
- **ApplicationId timing:** changing `applicationId` after first device
  install is effectively irreversible on Android. The rename to
  `com.kinescope.app` was deliberately completed before the first install,
  and that package is now established on the user's real Android 13 device.
  Treat the applicationId as fixed for future updates.
- **BOM/API version discipline:** current docs/tutorials default to
  showing the latest API, which won't necessarily compile against an
  older pinned BOM/library version. Always check the actual resolved
  version's own API surface, not what's currently documented as
  default.
- **A Codespace can have more than one JDK, from more than one
  source, and they can disagree.** This project's Codespace has a
  Codespace-provided default JDK at `/home/codespace/java/current`
  (currently 25.0.2) *and* a separate, SDKMAN-managed install from the
  devcontainer's Java feature at
  `/usr/local/sdkman/candidates/java/` -- and the Codespace-provided one
  wins on `PATH`/`JAVA_HOME` regardless of what `devcontainer.json`
  requested. Don't assume a `"version": "17"` feature request is what's
  actually active -- check `ls /usr/local/sdkman/candidates/java/`
  directly, and don't assume that directory even contains what was
  requested, either.
- **Gradle has a hard JDK ceiling per version, documented in that
  version's own release notes** (e.g. "Gradle 8.10 now supports running
  on Java 23") -- a JDK newer than the ceiling fails with a bare,
  low-information error (in this project's case, literally just the
  version number `25.0.2` and nothing else) rather than a clear
  "unsupported Java version" message. Worth checking the release notes
  early if a Gradle build fails with an unexplained, terse error.
- **XML comments can never contain `--` anywhere in the body** (only
  valid as part of the closing `-->`) -- easy to introduce by accident
  via a parenthetical dash in a comment.
- **A library's own tagged source is more reliable than its README's
  example snippets or an older sample app**, both of which can drop
  qualifying context (like an outer class name) in ways that look like
  -- but aren't -- evidence a class is top-level rather than nested.
  When a real compile error contradicts an earlier "pre-verified"
  claim, re-verify against the actual tagged source (`git clone` +
  checkout the exact pinned version/tag) rather than re-reading the
  same secondary sources that produced the wrong conclusion the first
  time.
- **Patches that rewrite text produced by an earlier patch can silently
  break that earlier patch's own idempotency check**, if the earlier
  patch's "already applied" detection doesn't also recognize the later
  patch's marker. This happened twice in one earlier session (patch 12
  rewriting patch 11's setup.sh block; patch 14 rewriting patch 07's
  Appendix row), then twice more later (patch 16 rewriting patches
  07/13/14/15's ROADMAP.md anchors; patch 17 then breaking patch 16's
  own check the same way, one layer deeper) -- each time only caught by
  running the **full patch chain 3-5+ times from a clean copy**, not a
  single dry run. This isn't a one-off risk to remember, it's a
  standing expectation for this project: any future patch that touches
  `ROADMAP.md` (or any other file several patches already edit) should
  budget time to also patch its immediate predecessor's idempotency
  check, and multi-pass full-chain testing is the only reliable way to
  catch when that's needed.
- **SIGPIPE in setup scripts:** `pipefail` combined with `yes |` piped
  to a process that closes stdin early produces SIGPIPE errors; license
  acceptance in `sdkmanager` requires a more robust approach (fixed in
  patch 07).
- **Hidden directories:** dot-prefixed folders (e.g. `.devcontainer/`)
  are silently skipped by some file transfer tools and GUI managers --
  `git add -A` from the terminal is required to capture them.
- **A GitHub Actions runner is not the same environment as this
  Codespace.** The JDK-pinning workaround from patches 11-12 exists
  because *this specific Codespace* has a competing ambient JDK 25 on
  `PATH` ahead of the one actually wanted. A GitHub Actions runner
  (now `.github/workflows/build-release.yml`; the earlier debug workflow was removed in patch 24) is a clean, single-JDK environment where `actions/setup-java` is the only JDK present -- so the workflow doesn't need (and doesn't include) that same pin. Don't
  assume every environment inherits every fix a previous environment
  needed; re-derive from first principles per environment.

<!-- patch 27 blocks relocated by patch 28 -->
## How to resume in a new conversation

1. Export a fresh repomix XML from the current repository and attach it.
2. Read `CLAUDE.md` -> this `HANDOFF.md` -> `ROADMAP.md` -> `CHANGELOG.md` -> `AGENTS.md`. There is no lowercase second roadmap anymore.
3. Check the latest commit and the user's most recent device/Actions report before changing any `[ ]` item in `ROADMAP.md`.
4. Continue from a failed verification item if one exists; otherwise do not invent a new implementation phase just because the roadmap is short.

## Immediate next step for Claude (in a new conversation)

**Run/collect verification for patches 24-25.** The autonomous roadmap work is complete. The exact remaining checks are in `ROADMAP.md`: repeated YouTube recovery/session behavior, process-death + resume, queue stress/dedupe, typed error cases, airplane mode, large/audio downloads and MediaStore cleanup, RU/EN/navigation/logging/icon/notifications, then a fresh signed GitHub Release installed over the prior signed build.

**Network-bypass follow-up:** the user has confirmed the Patch-27 engine on the real target path: GitHub Actions builds successfully and ByeDPI works on-device. Do not reopen that old gate unless a regression appears. Patch 28's only remaining network gate is the new YouTube-only Android VPN lifecycle (`ROADMAP.md`).

Do not mark a device-dependent item done from source inspection or a green compile. If a verification fails, diagnose that concrete failure first and update `CHANGELOG.md` / `HANDOFF.md` in the same patch as the fix.

The 16 KB page-size item is upstream-dependent with the currently pinned wrapper. Re-check upstream before changing `youtubedl-android`; do not vendor a custom native payload as a shortcut without a new explicit user decision.

For general repo process (guarded/idempotent patch scripts, verification discipline, English-only code/docs, no machine-specific committed paths) see `AGENTS.md`.


