# Changelog

All notable changes to Kinescope are recorded here, one entry per
patch, newest first. This file exists so `ROADMAP.md` can stay focused
on what's still open: as of patch 16, a `ROADMAP.md` item that gets
checked off also gets its detailed checklist text removed from that
file and replaced with a one-line pointer here. `HANDOFF.md` remains
the deeper narrative/architectural snapshot for resuming work in a new
session; this file is the terse "what shipped, in which patch" record.

Patches are cumulative and applied in order (01, 02, 03, ...). See each
patch's own `.py` script for the exact, idempotent, exact-match-guarded
edits it makes.


## Patch 25c — Android string-resource compile hotfix

Patch 25b applied the full Patch-25 roadmap-completion tree, but its local
verification correctly stopped at `:app:mergeDebugResources`: the English
`error_control_cleanup_failed` resource used the ASCII apostrophe in
`Couldn't` without Android string-resource escaping. The XML was well-formed,
so the earlier generic XML/static validation did not catch the AAPT-specific
string grammar.

### Fixed
- Escaped the apostrophe as `Couldn\'t`, unblocking AAPT resource compilation.
- Extended the delivery-time static verification to reject unescaped ASCII
  apostrophes in string-resource text while still allowing XML entities and
  escaped apostrophes.
- Re-ran the full Patch-25 local gate: `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug` must all succeed before this recovery patch records itself as
  complete and removes the superseded delivery scripts.

## Patch 25 — Reliability roadmap completion and release hardening

Patch 24 was successfully applied, locally built and pushed by the user (`91169f5`; `BUILD SUCCESSFUL`). Patch 25 consumes the still-relevant engineering work from the former lowercase S0-S11 audit and closes everything that can be completed autonomously in Codespaces. Real-device and real-release verification stays open in `ROADMAP.md` and is deliberately not marked done here.

### Durable queue and lifecycle
- Added `DownloadJobStore`, an `AtomicFile`-backed journal written **before** foreground-service dispatch. Accepted jobs therefore survive process death as explicit recoverable `INTERRUPTED` rows instead of disappearing from the in-memory `StateFlow`.
- Journal restoration cleans stale `MediaStore` pending rows, normalizes jobs that died while executing, merges safely with any very-fast fresh enqueue, and removes orphaned per-job workspaces.
- Reworked the queue around stable `QualityId` values rather than list indexes; old index preferences migrate transparently.
- Closed two queue-control races found during the final patch-25 review: a Pause arriving after `queue.poll()` but before `runJob()` can no longer be cleared/ignored, and an idle worker can no longer call an unconditional `stopSelf()` after a newer start has already arrived. Idle shutdown now uses the service `startId` / `stopSelfResult()` contract and hands queued work to a successor worker safely.
- Serialized yt-dlp/ffmpeg initialization, execution and self-update behind `EngineController`, preventing an updater from replacing the executable while a download uses it.
- Added Android 15 `dataSync` foreground-service timeout handling. An active job is persisted as recoverable before the service stops.

### Input, output and storage correctness
- Added a shared strict `YouTubeUrlParser`: only supported YouTube video URL forms are canonicalized; playlist-only links, lookalike hosts, userinfo, non-standard ports, bad schemes and oversized shared input are rejected. Active duplicates are rejected by video ID.
- Added pure `DownloadErrorClassifier` so yt-dlp text matching is typed/testable rather than mixed into localized UI copy.
- Each job now owns `cacheDir/jobs/<id>` and resumes inside that workspace. Final-output lookup is job-local, orphan cleanup is deterministic, and `--no-playlist` is explicit.
- Video fallback selectors are bounded to the selected maximum resolution instead of allowing `/b` to silently exceed it.
- MediaStore publication is a real commit boundary: actual output extension drives MIME detection where possible, `IS_PENDING=0` must succeed before the private source is deleted, and an interrupted pending URI is journaled for cleanup/retry.
- Library queries include the current and historically used Kinescope subfolders, exclude pending rows, expose local size/date/type metadata, and run off the Compose/UI thread. Deletion now requires confirmation.

### UX, notifications, privacy and diagnostics
- Expanded job states to preparing/running/processing/saving/paused/interrupted/done/failed/stopped, with Resume available for recoverable states.
- Added notification tap/Stop actions, throttled progress notifications and a completion notification. Runtime notification permission is requested only when the user actually accepts a download, not on cold start.
- Disabled Android app backup while cookies/logs/job journal exist. `AppLog` now redacts URLs, known YouTube cookie values and app-private paths before persistent logging **and** Logcat; throwable output is bounded to the error type/message plus a short sanitized stack prefix.
- Updated light-theme action/status colors for stronger contrast while preserving the existing Kinescope palette.

### Tests, environment and release
- Added JVM unit tests for YouTube URL parsing, yt-dlp error classification and diagnostic privacy redaction; added the Android coroutines runtime explicitly.
- Release Actions now run `testDebugUnitTest` + `lintDebug` before assembly, serialize concurrent release runs, and verify signature, zip alignment, package name, requested version and arm64-only native contents before publishing the APK + SHA-256.
- `.devcontainer/setup.sh` now pins the official Android command-line tools Linux archive and verifies its SHA-256 instead of scraping a mutable webpage during Codespace creation.
- Added `THIRD_PARTY_NOTICES.md` and refreshed `README.md`, `ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `AGENTS.md`, `RELEASE.md` and `design.md` for the post-roadmap architecture. The handoff also removes a stale pre-first-install note: `com.kinescope.app` is now an established package on the user's Android 13 device and should be treated as fixed.

### Deliberate non-changes / upstream blockers
- The older audit's "no authentication / no embedded browser" assumption is not restored: the user's later explicit Sprint-1 requirement for optional YouTube WebView session capture supersedes it.
- Playlist expansion, cross-device sync/backend and custom YouTube extraction remain out of scope.
- The app stays on published `youtubedl-android 0.18.1`. Current upstream has an open 16 KB page-size native-payload issue, so Kinescope does **not** claim 16 KB-device compatibility until upstream ships a verified fix.

## Patch 24 — Sprint 1: resilient downloads, account session, localization, navigation and release publishing

First feature sprint after the original roadmap reached steady state. The implementation is complete in code but remains **pending real-device / real-GitHub verification**; per `AGENTS.md`, none of the device-dependent behavior below is considered confirmed until the user reports it working.

### Download reliability and control
- Switched yt-dlp self-update from `STABLE` to `NIGHTLY`. This follows current upstream guidance: stable can lag behind site changes, while nightly is the recommended channel for regular users.
- Added a bounded self-healing chain for YouTube verification / 403 / 429 failures: normal request (with saved YouTube cookies when available) -> refresh yt-dlp nightly -> `web_safari` + IPv4 fallback -> logged-out `android_vr` fallback. Each attempt retains yt-dlp's own extractor and uses modest retries / randomized delay rather than adding custom extraction or bot-bypass logic. If YouTube still refuses the request, the job is parked as resumable `PAUSED` rather than terminal `FAILED`; a successfully captured account session automatically resumes those verification-paused jobs.
- Added optional YouTube WebView session capture. Authenticated YouTube cookies are written in Netscape format to app-private storage and passed to yt-dlp together with the captured WebView User-Agent. No OAuth token/backend is introduced. Google may still reject embedded sign-in on some devices, and an authenticated session cannot guarantee recovery from an IP-level YouTube block.
- Added Pause / Resume / Stop controls. Running jobs are cancelled through youtubedl-android's `destroyProcessById(processId)` API; Pause retains temp fragments and Resume requeues the same job with `--continue`; Stop removes temp fragments.
- Added an extractor-readiness check in `DownloadService`, closing the old race where a very fast first download could theoretically beat `Application`'s background yt-dlp initialization.

### UI, localization and diagnostics
- Externalized the application UI into resources and added `values-ru/strings.xml`. Android automatically uses Russian when the device/application locale is Russian and the existing English resources otherwise.
- Replaced the old settings toggle / bottom composer with a three-action bottom navigation surface: Home, prominent center Add, Settings. The treatment is a translucent, elevated, softly bordered glass-style surface built from the existing design tokens.
- Android Back from Settings / Add now returns Home; the YouTube browser and hidden log journal return to Settings.
- Added a private rotating application log (state transitions and errors; no cookie values). Five quick taps on the Settings navbar icon open an in-app log journal with refresh, clear and explicit share actions.

### Build and visual identity
- Removed `.github/workflows/build-debug.yml`. The release workflow now validates inputs/secrets, uses Gradle caching, performs a clean signed build, verifies the APK with `apksigner`, writes a SHA-256 checksum, keeps a workflow artifact backup and publishes both files into a versioned GitHub Release.
- Reworked the launcher icon into an original retro-TV motif using Kinescope's cream / terracotta / warm-dark palette, with layered glass-like highlights. Added an Android 13+ monochrome layer for themed icons.
- Updated `README.md`, `ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `AGENTS.md`, `RELEASE.md`, and `design.md` to describe the new behavior and the still-required verification pass.
- Sprint delivery remains a single exact-match/hash-guarded Python patch. It runs static checks plus `./gradlew --no-daemon assembleDebug`; only after that build succeeds does the patch script delete itself from the repo.

## Patch 23 — Documentation overhaul: Step 5 confirmed working, project reaches steady state

The user confirmed patch 22's fix: the app now runs correctly end to
end on a real device (Android 13) — the startup crash is gone, and
the core install/share-or-paste/queue/download/play loop works. This
patch is documentation only (no app code changes).

### Changed
- `README.md` — fully rewritten: build-status badges for both GitHub
  Actions workflows, an accurate tech-stack table (Kotlin 2.1.0,
  Compose BOM `2024.11.00`/Material3 1.3.1, `youtubedl-android`
  0.18.1, Gradle 8.10.2/AGP 8.7.2, `minSdk` 29/`compileSdk`/`targetSdk`
  35, `arm64-v8a`-only), and a "Status" section reflecting the app's
  actual working state instead of the pre-first-build snapshot patch
  15 originally wrote.
- `ROADMAP.md` — consolidated. The original 9-step plan is done or
  reduced to specific, named technical debt; collapsed from a 363-line
  step-by-step checklist to a short "Current state" summary plus a
  single "Technical debt" section (the one remaining Step 9
  device-confirm item, Step 5's not-yet-individually-confirmed
  adversarial checks, the Backlog items, and the still-queued
  `roadmap.md` lowercase audit). Full step-by-step history stays in
  `CHANGELOG.md`/`HANDOFF.md`, which this file now points to rather
  than duplicating.
- `HANDOFF.md` — "Project identity", "What's actually done vs. still
  open", and "Immediate next step" rewritten for the new steady state:
  Step 5's crash-fix is confirmed by the user; basic on-device
  functionality (install, permissions, share/paste, download, play,
  background persistence) is confirmed working; the adversarial edge
  cases (race-condition stress test, `friendlyError()` against a real
  broken video, airplane mode, a very large download) remain open,
  unconfirmed technical debt, not assumed to have passed just because
  the app runs now. The generic "how to work in this repo" checklist
  that used to live in "Immediate next step" has moved to the new
  `AGENTS.md` instead of staying duplicated across two files. Also
  fixed two stale lines caught while reviewing this file: the file map
  still described `RELEASE.md` as using old `yt-offline` naming
  (patch 20 already renamed it) and described `ROADMAP.md` as a
  "checkbox-tracked implementation plan" (no longer accurate after
  this patch). Patch-by-patch narrative (patches 01–22) is unchanged.
- `.gitignore` — added `*.jks.b64` and `*.jks.base64.txt`. Found while
  reviewing the repo for this patch: `kinescope-release.jks.b64` (an
  empty, 0-byte placeholder, currently harmless) is tracked in git and
  wasn't covered by the existing `*.jks`/`*.keystore` rules — if that
  filename were ever reused to actually hold a base64-encoded
  keystore (e.g. following `RELEASE.md`'s manual base64 steps but
  writing the output inside the repo folder by habit), it would
  commit the signing key straight into git history. Not a live leak
  today, but a real landmine for next time.

### Added
- `AGENTS.md` — a process/workflow file for AI coding agents working
  on this repo (source-of-truth reading order, verification
  discipline, patch-script delivery/testing conventions, scope
  discipline), following the emerging AGENTS.md convention. Complements
  rather than duplicates `CLAUDE.md` (project ground rules/constraints)
  and `HANDOFF.md` (state snapshot).

### Findings (closed)
- **`kinescope-release.jks.b64` is git-tracked and not gitignored.**
  Currently empty and harmless, but the filename invites exactly the
  kind of accidental-secret-commit `RELEASE.md`'s own base64 step
  warns about. Fixed via `.gitignore` (see above). Recommended,
  not automated by this patch: `git rm --cached kinescope-release.jks.b64`
  to stop tracking the empty file too (see delivery commands).

## Patch 22 — Fixed a startup crash found on the first real-device launch (Step 5)

The user's first real-device install (Android 13) crashed immediately.
`EmptyQueueState` is what a fresh install shows first (queue empty, no
downloads yet) — that's the exact composable that crashed, on its
`painterResource()` call.

### Changed
- `app/src/main/java/com/kinescope/app/MainActivity.kt` —
  `EmptyQueueState`'s icon no longer loads
  `android.R.drawable.stat_sys_download` via `painterResource()`. That
  framework resource is an `AnimatedVectorDrawable` on real devices
  (it's the system's own animated download-in-progress notification
  glyph); Compose's `painterResource()` only supports a plain static
  `VectorDrawable` or a rasterized image (PNG/JPG/WEBP), not an
  API-driven XML type like an animated-vector — confirmed against
  `painterResource()`'s own documentation, which states this
  restriction explicitly ("API based xml Drawables are not supported
  here"). Loading one this way raises `IllegalArgumentException` at
  runtime, every time — which for this composable means immediately,
  on a fresh install.
- Added `app/src/main/res/drawable/ic_download.xml` — a small,
  hand-authored static vector (arrow + tray), reusing the same visual
  motif as `ic_launcher_foreground.xml` for consistency. Deliberately
  not `material-icons-extended` (still avoided for one glyph, per
  `CLAUDE.md`'s zero-required-cost/no-bloat spirit) and not the
  framework resource that just crashed.

### Findings (closed)
- **`android.R.drawable.stat_sys_download` cannot be loaded via
  Compose's `painterResource()`.** It's an `AnimatedVectorDrawable` on
  real devices, not a static `VectorDrawable` or a raster image —
  `painterResource()`'s own documentation explicitly scopes support to
  those two types only. `DownloadService.buildNotification()`'s
  `setSmallIcon()` use of the same resource is unaffected and
  deliberately left as-is: Android notifications accept any drawable
  resource id directly (no Compose involved), which is exactly what
  this animated icon is designed for.
- **`./gradlew assembleDebug` succeeding does not catch this class of
  bug.** Nothing about this crash shows up at compile time — it's a
  resource-type mismatch that only manifests when the composable
  actually runs on a device, which is exactly the gap Step 5's
  real-device checklist exists to catch.

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

## Patch 19 — Documentation/handoff update, pivot to Step 9

No app code changes; patch 17/18's own scripts needed a small fix (see
below).

### Changed
- `ROADMAP.md` — recorded that patch 18's CI fix is confirmed by a
  real, successful GitHub Actions run (not just sandbox simulation),
  and that the user has downloaded a debug build via it. Recorded an
  explicit decision: Step 9 (signed release) starts next, ahead of
  Step 5's manual checklist being individually confirmed — updated the
  execution order, Step 5's closing note, and Step 9's gate language
  accordingly (the original caution stays as context, not deleted).
  Step 8 marked done in the execution order (CJM.md, patch 17).
- `HANDOFF.md` — milestone paragraph, "what's done/not done" section,
  patch history, and "Immediate next step" all updated for the above;
  the latter now also states the sprint-based delivery convention
  (finish a batch of work, then one patch script per sprint rather than
  per tiny step) and a brief note on APK size (debug builds are
  expected to be larger; release won't shrink dramatically either,
  since `isMinifyEnabled = false` is deliberate — see Backlog's
  `ndk.abiFilters` item if size ever needs addressing).
- `CLAUDE.md` — instruction log: recorded the Step 9-starts-now
  decision as a standing directive (don't re-litigate in a new
  session), and the sprint-based patch-delivery convention.
- `patch16_ci_workflow_and_changelog.py`,
  `patch17_customer_journey_map.py`, and
  `patch18_fix_ci_jdk_pin_leak.py` — the now-familiar pattern, two
  levels deep this time: `patch_claude_md()` (patch 16),
  `patch_roadmap()` (patch 17), and `patch_changelog()`/`patch_handoff()`
  (patch 18) each gained a `superseded_marker` for this patch's direct
  edits to those files. Then, since patch 17 also patches patch 16's
  *script file* (`patch_patch16_script()`, added back in patch 17 to
  fix the ROADMAP.md/HANDOFF.md collision described in that patch's own
  entry), and this patch's fix to patch 16's script (the
  `patch_claude_md()` guard above) changes that same script file
  *again*, patch 17's check on it needed a `superseded_marker` too —
  caught only on a second full-chain regression run after the first
  round of fixes above, since the first round's fixes had to actually
  exist before this second-order collision could even surface.

## Patch 18 — Fixed the GitHub Actions build failure

### Fixed
- **The `Build Debug APK` workflow (patch 16) failed on its first real
  run**: `Value '/usr/local/sdkman/candidates/java/21.0.10-ms' given
  for org.gradle.java.home Gradle property is invalid`. Root cause:
  patch 12 wrote this JDK pin directly into the project's own
  **committed** `gradle.properties`, which is correct for this one
  Codespace (that exact path exists there) but wrong for literally
  every other environment that clones the repo — including the GitHub
  Actions runner added four patches later in patch 16, where that path
  doesn't exist and Gradle refuses to start at all.
- Fixed by moving the pin out of the committed, project-level
  `gradle.properties` and into the user-level
  `$HOME/.gradle/gradle.properties` instead — Gradle already gives
  user-level `gradle.properties` higher precedence than the
  project-level one (confirmed against Gradle's own build-environment
  documentation), and that file lives outside the repo entirely, so it
  never gets committed or shipped anywhere. `.devcontainer/setup.sh`'s
  JDK-detection logic (unchanged) now writes there instead of into the
  tracked file. The stale, invalid line removed from the committed
  `gradle.properties`, which is what immediately unblocks the GitHub
  Actions build.
- Verified: simulated both the Codespace path (fake SDKMAN JDK
  directories, confirmed the pin lands in `$HOME/.gradle/gradle.properties`
  and the project file stays untouched, across two runs for
  idempotency) and confirmed the committed `gradle.properties` no
  longer contains any machine-specific path.

## Patch 17 — Customer Journey Map

### Added
- `CJM.md` — the five-stage Customer Journey Map (prep at home → queue
  & download → departure/loses access → watch offline in-region →
  return & refresh library) originally produced during the 4-part
  review, written up as a standalone living document rather than left
  implicit in `ROADMAP.md`'s prioritization. States the key finding
  explicitly: stage 1 → stage 3 is a one-way door with no retry once
  internet access is lost, which is why every crash/race/silent-failure
  fix in Steps 1/3/4 was ranked Critical/High regardless of how narrow
  the trigger condition looked on paper.

### Changed
- `ROADMAP.md` — Step 8's `CJM.md` checkbox marked done.
- `patch16_ci_workflow_and_changelog.py` — `whole_file_guarded_replace()`
  gained an optional `superseded_marker` parameter, and its
  `patch_roadmap()` **and** `patch_handoff()` calls now use it (patch
  17 modifies both files after patch 16 already did). Caught by this
  patch's own multi-pass full-chain regression test: since this patch
  further modifies `ROADMAP.md` and `HANDOFF.md` after patch 16 already
  did, re-running the full chain from a clean copy made patch 16's own
  idempotency check fail on its second pass for both files (the file no
  longer matched either patch 16's "old" or "new" expected content,
  because patch 17 had since changed it again) — the same "later patch
  breaks an earlier patch's idempotency check" failure mode as patch
  16's own fix for patches 07/13/14/15, recurring one layer deeper.
  This is expected to keep recurring for any future patch that touches
  `ROADMAP.md` or `HANDOFF.md` again; each one should budget time to
  vaccinate its immediate predecessor the same way.

## Patch 16 — CI build workflow, changelog process

### Added
- `.github/workflows/build-debug.yml` — manual-only (`workflow_dispatch`)
  GitHub Actions workflow that builds a debug APK and uploads it as a
  run artifact, so the phone-sideload path no longer depends on adb or
  downloading a local Codespace build through the browser. Version name
  is supplied by hand on each run; `versionCode` is derived from the
  Actions run number so it always increases (avoids
  `INSTALL_FAILED_VERSION_DOWNGRADE` when reinstalling over an older
  build on the same device). No automatic trigger — always run by
  hand, one version per run.
- `CHANGELOG.md` (this file) and the process it establishes.

### Changed
- `app/build.gradle.kts` — `versionCode`/`versionName` now read
  optional Gradle properties `appVersionCode`/`appVersionName` (set by
  the new workflow via `-P`), falling back to the existing hardcoded
  `7` / `"1.0.0"` when absent — local Codespace builds
  (`./gradlew assembleDebug` with no `-P` flags) are unaffected.
- `ROADMAP.md` — Steps 1, 2, 3, 4, 6 (6.1-6.6), and 7 collapsed to a
  one-line "done, see CHANGELOG.md" pointer each; their closed Appendix
  findings (originally rows 1-15, 18, 21-32, 34-35) removed from the
  Appendix table for the same reason, with a note explaining where they
  went. Step 8's README checkbox corrected to `[x]` — the work was
  actually done in patch 15 but the checkbox was never flipped. The
  optional, never-done `ndk.abiFilters` trim moved from Step 1 into
  Backlog, since it was never actually blocking anything.
- `CLAUDE.md` — instruction log: build via GitHub Actions (manual
  trigger, hand-assigned version) instead of a local Codespace
  `./gradlew` + manual download; completed `ROADMAP.md` items get
  removed from that file and logged here on completion.
- `README.md` — Building section now mentions the Actions-based build
  path alongside the local Codespace one.
- `HANDOFF.md` — patch history extended with Patch 15 (missing until
  now) and this entry; file map mentions `CHANGELOG.md` and the new
  workflow file.
- `patch07_fix_setup_and_verify_imports.py`,
  `patch13_fix_invalid_xml_comment.py`,
  `patch14_fix_updatechannel_import.py`,
  `patch15_docs_after_first_build.py` — each got a short-circuit guard
  added to its `patch_roadmap()` function: if patch 16's Appendix-trim
  marker is present, skip that patch's ROADMAP.md edit entirely instead
  of either failing loudly (its anchor rows/text are gone) or, worse,
  silently resurrecting content patch 16 intentionally removed (row 34
  and 35's insertion anchors survive patch 16 untouched, so without
  this guard a repeated full-chain run would have quietly re-added
  them). Caught by this patch's own multi-pass full-chain regression
  test — the exact failure mode `HANDOFF.md`'s "Key learnings" already
  documents from patches 11/12 and 07/14's earlier collision, now
  recurring between 07/13/14/15 and this patch.

## Patch 15 — Documentation consolidation after the first successful build

### Added
- `README.md` fully rewritten (previously a bare "# kinescope" title):
  what the app is/isn't, build instructions, documentation map.

### Changed
- `HANDOFF.md` refreshed: patch history through patch 14, "what's
  done" summary updated for the successful build, new "Key learnings"
  section.
- `ROADMAP.md` top status block updated; Step 2's import-path checkbox
  marked done (confirmed by a real compile, not just pre-verified);
  the two scattered Step 5 environment notes from patches 07 and 12
  consolidated into one.

## Patch 14 — Fixed the final compile error; first successful build

### Fixed
- `UpdateChannel` is a nested class of `YoutubeDL`
  (`com.yausername.youtubedl_android.YoutubeDL.UpdateChannel`), not
  top-level as patch 07 concluded from a README comment that dropped
  the qualifying prefix. Confirmed by `git clone`-ing the actual
  library at the pinned `0.18.1` tag and reading the real source
  directly. Appendix finding #11 (now closed, see above).
- **After this patch, `./gradlew assembleDebug` succeeded for the
  first time in this project's history.**

## Patch 13 — Fixed the first real compile error

### Fixed
- `ic_launcher_foreground.xml` had `--` inside an XML comment body,
  which the XML spec forbids anywhere except the closing `-->`.
  Confirmed via a regex scan that this was the only occurrence in the
  repo.

## Patch 12 — Fixed the JDK/Gradle mismatch

### Fixed
- Broadened patch 11's JDK search from "exactly 17" to any JDK Gradle
  8.10.2 can run on (17-23, per Gradle's own 8.10 release notes).
  Diagnostics confirmed the real cause: this Codespace's `java`/`javac`
  on `PATH` resolve to a Codespace-provided JDK 25.0.2, separate from
  and taking priority over the devcontainer's SDKMAN-managed install
  (which only has `21.0.10-ms` and `25.0.2-ms`, no 17.x, despite
  `devcontainer.json` requesting 17). Pinned Gradle to the
  already-installed `21.0.10-ms` via `org.gradle.java.home` in
  `gradle.properties`. **This is what actually fixed the JDK
  mismatch** — the next build got past environment setup for the
  first time.

## Patch 11 — First JDK/Gradle mismatch fix attempt

### Added
- Auto-detection of an installed JDK 17 via SDKMAN, pinned through
  `org.gradle.java.home` if found. Didn't fix anything yet — this
  Codespace has no JDK 17 at all (see patch 12).

## Patch 10 — `setup.sh` re-run safety

### Fixed
- The Android cmdline-tools download/extract/`mv` block failed on a
  second run ("Directory not empty"). The `.bashrc` export block also
  duplicated itself on every run. Both guarded to skip/no-op when
  already done.

## Patch 09 — Fixed a stale ROADMAP.md cross-reference

### Fixed
- Step 5's section referenced "Step 7 (signed release)" from before
  the Kinescope-rename step was inserted as its own Step 7; signed
  release has been Step 9 since patch 06. Documentation only.

## Patch 08 — Documented the two-roadmap situation

### Added
- Recorded, in both `ROADMAP.md` and `HANDOFF.md`, that this repo has
  two separate roadmap files (`ROADMAP.md` by Claude Fable 5.1,
  `roadmap.md` by GPT Astra) and the required execution order: finish
  `ROADMAP.md` through Step 9 first, then `roadmap.md`, then delete
  both. Documentation only.

## Patch 07 — Pre-Step-5 environment fix

### Fixed
- `.devcontainer/setup.sh`'s `pipefail` + `yes | sdkmanager --licenses`
  combination could silently abort setup before the Gradle wrapper was
  ever generated (SIGPIPE). Fixed by disabling `pipefail` around just
  that pipeline and checking `sdkmanager`'s real exit status.

### Verified
- Pre-verified `youtubedl-android`/`ffmpeg` import paths and API
  shapes against the library's README and sample app. **This
  pre-verification turned out to be incomplete** — see patch 14.

## Patch 06 — Kinescope rename

### Changed
- `namespace`/`applicationId` → `com.kinescope.app`,
  `rootProject.name` → `kinescope`. Every Kotlin source file moved
  from `com/baltic/ytoffline/` to `com/kinescope/app/` with matching
  `package` declarations. `app_name` in `strings.xml` → "Kinescope",
  plus the notification title and `TopAppBar` title so the rebrand
  isn't half-done on screen. `design.md`'s "YT Offline" mentions
  updated. Internal-only identifiers (`YtOfflineApp`, `YtOfflineTheme`,
  `YtOfflineExtras`, etc.) deliberately left unchanged — not
  user-visible, not in Step 7's scope.

## Patch 05 — Documentation only

### Added
- `HANDOFF.md` and the `ROADMAP.md` status section, established in the
  previous session.

## Patch 04 — Step 6.5: component patterns per screen

### Added
- Queue-row thumbnail placeholders, a real `LinearProgressIndicator`
  (added `progressFraction: Float?` to `DownloadJobStatus`), an
  empty-queue illustration, a Library overflow menu with working Share
  (`Intent.ACTION_SEND`) and Delete (`ContentResolver.delete()`), a
  sectioned Settings screen with a persisted yt-dlp-last-updated
  timestamp, a dismissible connectivity-loss banner, a composer-bar
  focus-border fix.

## Patch 03 — Step 6.1/6.2/6.3/6.6: design system tokens

### Added
- A real dark `ColorScheme` (previously light-only), `surfaceRaised` /
  `warning` / `errorContainer` tokens via a `CompositionLocal`-backed
  `YtOfflineExtras` object, a completed typography scale.

### Fixed
- Adaptive-icon safe-zone clipping.

## Patch 02 — Step 4: product-quality fixes

### Fixed
- Filename humanization (yt-dlp's own title template, with a bracketed
  job-id tag for finding the output file afterward), job-id-tag-based
  output file scanning (replacing an exact-filename assumption),
  `MediaStore.RELATIVE_PATH`'s trailing-slash mismatch between insert
  and query, atomic `DownloadQueueBus` updates
  (`MutableStateFlow.update {}`), Downloads-subfolder-name
  sanitization, YouTube-host validation on shared/pasted URLs, an
  `ActivityNotFoundException` guard on the video-player launch intent.

## Patch 01 — Steps 1-3: compile blockers, critical runtime fixes

### Fixed
- Fabricated Compose BOM version replaced with a real one
  (`2024.11.00`). `execute()`'s progress-callback arity confirmed
  3-parameter against the library's own sample-app source.
  `updateYoutubeDL()`'s required `UpdateChannel` argument added. The
  `DownloadService` worker race condition fixed (blocking consumer
  loop + lock-guarded state transition, tighter than the roadmap's own
  sample fix). A catch-all exception handler added so one bad download
  can no longer crash the whole process. Dead
  `requestLegacyExternalStorage="true"` removed.

<!-- patch 27: the paragraph this note was meant to extend was not found verbatim in this file (it may have been reworded since this patch was written); appended standalone instead. Consider folding it into the surrounding prose by hand. -->
## Patch 27 — In-app network bypass (bundled ByeDPI engine)

User-requested feature: the user's corporate Wi-Fi is believed to restrict YouTube by inspecting connection headers (DPI), and asked for the approach used by [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) / [ByeDPI](https://github.com/hufrea/byedpi) to be built directly into Kinescope, with Settings controls to turn it on, search for and choose a strategy, and update. Full research and design record: `INTEGRATION_PLAN.md` in the project's memory.

### What was verified before writing this patch (sandbox, not a real device)
- Cloned upstream `hufrea/byedpi` at commit `ba532298`; it is a ~6.6k LOC MIT-licensed C SOCKS5 server implementing "desync" strategies (split/disorder/fake first packets) as CLI flags. It is not a VPN and does not encrypt traffic or hide the IP.
- Vendored those C sources unmodified into `app/src/main/cpp/byedpi/` (win_service.* excluded, Windows-only) and wrote Kinescope's own JNI glue (`dpi_jni.c`) from scratch -- no code from ByeByeDPI's own (GPL-3.0) Kotlin/Java/native-lib.c was used or derived from.
- Built the vendored engine + glue as a host `.so` (glibc, JNI headers fetched from the OpenJDK mirror) and drove it with a JVM test harness: started/stopped the real engine through every built-in strategy, relayed real bytes through it via a hand-written SOCKS5 client to both a plain TCP echo server and a local TLS+HTTP server (full CONNECT -> TLS handshake -> HTTP request path). All built-ins passed both harnesses; a concurrent second engine start is correctly refused.
- Wrote `DpiStrategyParser`, an allowlist-only parser for the engine's desync CLI syntax: it accepts the documented split/disorder/oob/fake/tlsrec/auto/etc. options and their attached-argument and `--long-form` spellings, substitutes the `{sni}` placeholder ByeByeDPI's own list uses, and rejects everything that could change the listen address, read/write a file, or connect elsewhere (`--ip`, `--port`, `--hosts`, `--cache-file`, `--connect-to`, `--daemon`, ...). Verified against the real upstream `proxytest_strategies.list` (59 of 60 lines accepted; the one rejected line uses an option this app does not expose) and ~30 hand-written hostile inputs (path/host/port overrides, shell metacharacters, and getopt long-option-abbreviation collisions such as `-daemon`, `-de`, `-nno-domain`). 18 JVM unit tests across the parser, the shared `NetworkCheck` (patch 26) extended for the bypass path, and the strategy search.

### Added
- `app/src/main/cpp/byedpi/` -- vendored unmodified ByeDPI C sources (MIT license, provenance in `KINESCOPE_VENDOR.txt`).
- `app/src/main/cpp/dpi_jni.c` + `CMakeLists.txt` -- Kinescope's own JNI glue; 16 KB-page-aligned link flags (`-Wl,-z,max-page-size=16384`).
- `DpiNative.kt` -- JNI declarations (`nativeRun`/`nativeStop`), loads `libkinescope_dpi.so`.
- `DpiEngineService.kt` -- hosts the engine in its own `:dpi` process (declared in `AndroidManifest.xml`), killed after every run so the engine's process-wide C globals never carry state between strategies or downloads.
- `DpiEngine.kt` -- binds/unbinds the service, waits for the SOCKS5 port to answer (bounded, polling), exposes an `AutoCloseable` `BypassSession`.
- `DpiStrategies.kt` -- `DpiStrategyParser` (above) and a small offline `DpiBuiltInStrategies` list so the feature works before any list update and without network access.
- `DpiStrategyStore.kt` -- `SharedPreferences` for the enabled flag and selected strategy, plus an HTTPS-only (size- and count-bounded) download of ByeByeDPI's own community strategy list, re-validated through the same parser before it is ever used.
- `DpiSearch.kt` (`DpiStrategySearch`) -- tries candidate strategies in turn against three real probe hosts (`www.youtube.com`, `i.ytimg.com`, `redirector.googlevideo.com`); stops at the first strategy that gets all three through, otherwise keeps the one with the most hosts through.
- `DpiBypass.kt` -- glue used by both the download path and the Settings UI: starts the engine for a download when enabled, and reuses patch 26's `NetworkCheck`/`Verdict` machinery to diagnose direct-vs-bypassed reachability per host.
- `BypassSettingsSection()` in `BypassSettings.kt`, wired into `SettingsScreen`: on/off switch, "Find a working strategy" (progress + Stop, per-result "Use" buttons), "Test the connection" (direct vs. bypassed stage-by-stage result + plain-language verdict), "Update the strategy list".
- `DownloadService.executeAttempt()`: when the bypass is enabled, starts the engine for the duration of that attempt and points yt-dlp's `--proxy` at `socks5h://127.0.0.1:<port>` (the `h` matters: the engine resolves the host name itself, so DNS filtering of the device does not by itself defeat it); the session is always closed in a `finally`, including on exception.
- New JVM tests: `DpiStrategyParserTest`, `NetworkCheckTest` (extended: bypass-path SOCKS5 fixtures, `Verdict.DNS_BLOCKS_BYPASS`), `DpiStrategySearchTest`.
- `.devcontainer/setup.sh` and `.github/workflows/build-release.yml` now also install `ndk;27.2.12479018`, required to compile the new native code.
- `THIRD_PARTY_NOTICES.md`, `README.md` updated for the new dependency and feature.

### Explicitly not done in this patch
- Not verified on a real device or a real GitHub Actions run (new `ROADMAP.md` gate). In particular, whether CMake cross-compiles cleanly for arm64-v8a in that exact toolchain is unproven -- only a host (glibc, x86_64) build was tested.
- Not on by default; the user must switch it on in Settings.
- Does not claim to fix DNS- or IP-level blocking; the Verdict text says so explicitly (`DNS_BLOCKED`, `DNS_BLOCKS_BYPASS`) rather than implying the bypass is a universal fix.
- Does not touch `DownloadErrorClassifier`/`NetworkProbe` (patch 26); those are unrelated to whether a bypass is active.
