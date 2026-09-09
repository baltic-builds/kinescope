# Kinescope — Sprint Roadmap

**Status:** Planned  
**Source:** Complete code / UX / Android / design / privacy review of the supplied repository export.  
**Current release verdict:** NOT READY for a dependable release.

## 0. Purpose

This roadmap converts the complete review into executable, sequential sprints.

The goal is not to rewrite Kinescope or add architecture for its own sake. The goal is to turn the current unverified prototype into a small, dependable, local-first Android utility.

Preserve:
- single Android application module;
- single-process architecture;
- sequential downloads;
- yt-dlp as the extraction boundary;
- MediaStore publication;
- external playback;
- small preferences storage;
- Flow-based UI observation;
- manual dependency wiring;
- no backend;
- no custom extractor;
- no broad storage permissions.

Core sequence:

> **Build → control engine/service ownership → make output/publication correct → persist/recover jobs → add cancel/retry → repair UX → complete accessibility/design → verify → release.**

Do not introduce Hilt, multiple Gradle modules, generic Clean Architecture/use-case layers, an embedded browser/player, backend sync, authentication/cookie support, or DRM handling without a concrete product need.

---

# 1. Status model

Every task uses one state:

- `Planned` — identified but not implemented.
- `Implemented` — changed, but not yet proven by the required test.
- `Build-verified` — passes the relevant headless build/test/lint.
- `Device-verified` — confirmed on the required real device/API scenario.

**Rule:** “patched”, “reviewed”, “looks correct”, and static diff validation do not mean build-verified.

---

# 2. Sprint map

| Sprint | Name | Primary outcome | Priority |
|---|---|---|---|
| S0 | Evidence & Build Baseline | Reproducible build and truthful baseline | P0 |
| S1 | Identity & Repository Hardening | Final Kinescope identity + truthful project state | P1/P2 |
| S2 | Engine Ownership | Deterministic engine readiness/update/execution | P1 |
| S3 | Service Lifecycle | Correct FGS ownership, shutdown, timeout, cancellation | P1 |
| S4 | Output & Publication | Correct output, MIME, MediaStore commit, cleanup | P1 |
| S5 | Durable Jobs | Persistent queue, recovery, retry, dedupe | P1 |
| S6 | Core UX & Share | Clear download flow, share flow, Library reachability | P1/P2 |
| S7 | Design & Accessibility | Complete UI system and adaptive/accessibility behavior | P1/P2 |
| S8 | Diagnostics, Privacy & Platform | Safe diagnostics, updater audit, Android/native hardening | P1/P2 |
| S9 | Verification Matrix | Automated + device regression evidence | P1 |
| S10 | Release | Signed, documented, traceable release | P1 |
| S11 | Post-release Product | Only proven convenience features | P2/P3 |

---

# S0 — Evidence & Build Baseline

**Objective:** replace assumptions with executable evidence.  
**Dependencies:** none.

### Tasks

- [ ] `F01` Commit `gradlew`, `gradlew.bat`, wrapper JAR and wrapper properties.
- [ ] `F34` Make `.devcontainer/setup.sh` reproducible and idempotent.
- [ ] `F34` Fix `pipefail` handling around `sdkmanager --licenses`.
- [ ] `F34` Honor externally supplied `DOWNLOAD_URL`.
- [ ] `F34` Pin/checksum command-line tooling where practical.
- [ ] Run `./gradlew assembleDebug`.
- [ ] Run lint.
- [ ] Resolve actual yt-dlp/FFmpeg wrapper imports and APIs from the resolved artifacts.
- [ ] Record actual build environment and resolved dependency versions.
- [ ] `F13` Replace the platform animated drawable used through `painterResource` with a local static vector.
- [ ] Establish JVM unit-test infrastructure.
- [ ] Record every newly discovered build issue rather than silently changing scope.

### Exit criteria

- Clean checkout builds headlessly.
- Build/lint results are recorded.
- Gradle wrapper is committed.
- Native wrapper APIs are confirmed.
- First-launch drawable risk is removed.

### Findings

`F01, F13, F16, F34, F42`

---

# S1 — Identity & Repository Hardening

**Objective:** make the application and repository consistently Kinescope before meaningful deployment.  
**Dependencies:** S0.

### Tasks

- [ ] `F38` Apply final identity:
  - `applicationId = com.kinescope.app`
  - `namespace = com.kinescope.app`
  - root project identity = Kinescope.
- [ ] Rename source package declarations/paths where required.
- [ ] Change `app_name` from `YT Offline` to `Kinescope`.
- [ ] Remove legacy product naming from user-visible UI.
- [ ] Update `design.md`, `ROADMAP.md`, `HANDOFF.md`, `README.md`, `RELEASE.md`.
- [ ] `F37` Remove claims that implementation is verified when only static/patch validation exists.
- [ ] Use `Planned → Implemented → Build-verified → Device-verified` throughout project docs.
- [ ] Separate the mechanical rename from unrelated architecture work.
- [ ] Correct documentation around package-ID changes: changing `applicationId` creates a separate Android app; test installs can be discarded.

### Exit criteria

- Kinescope is the single product identity.
- Package identity is stable before meaningful deployment.
- Documentation distinguishes implementation from evidence.

### Findings

`F37, F38`

---

# S2 — Engine Ownership

**Objective:** make yt-dlp/FFmpeg initialization, update and execution deterministic.  
**Dependencies:** S0.

### Target architecture

```text
MainActivity
    ↓
DownloadViewModel
    ↓
JobRepository
    ↓
DownloadCoordinator
    ├── EngineController
    ├── MediaStorage
    └── temporary workspace

DownloadService = Android foreground-service lifecycle adapter
```

### Tasks

- [ ] `F02` Replace unused `isReady` with observable engine state:
  - `Initializing`
  - `Ready`
  - `Failed`
- [ ] Gate job execution on engine readiness.
- [ ] `F15` Serialize initialization, automatic update, manual update and execution.
- [ ] Define whether extractor updates may run while a download is active.
- [ ] Define safe behavior when updater fails.
- [ ] `F27` Audit updater:
  - source;
  - TLS;
  - integrity verification;
  - atomic replacement;
  - interrupted-update recovery;
  - rollback/known-good version;
  - native compatibility;
  - active-download coordination.
- [ ] Separate “last checked” from “installed/updated version”.
- [ ] If engine version is displayed, read the actual installed version.
- [ ] Replace stringly-typed engine/error state with typed domain values.
- [ ] `F17` Add one shared `YouTubeUrlParser`:
  - bounded input;
  - supported scheme;
  - supported hosts;
  - reject userinfo/unexpected ports;
  - supported video forms;
  - canonicalize tracking/playlist context;
  - structured validation errors.
- [ ] `F23` Replace persisted quality indices with stable IDs such as `VIDEO_1080`, `VIDEO_720`, `VIDEO_480`, `AUDIO_MP3`.

### Exit criteria

- Cold-start downloads cannot race initialization.
- Update and execution ownership is explicit.
- URL validation is shared by paste/type/share.
- Quality identity survives list-order changes.

### Findings

`F02, F15, F17, F23, F27`

---

# S3 — Service Lifecycle

**Objective:** make long-running foreground work lifecycle-correct.  
**Dependencies:** S2.

### Tasks

- [ ] `F03` Replace current worker restart/stop protocol with one execution owner.
- [ ] Eliminate old-worker/new-worker shutdown races.
- [ ] Serialize enqueue, active-job, foreground and idle-stop decisions.
- [ ] `F04` Promote foreground service promptly when valid work is accepted.
- [ ] Add explicit `onDestroy()` cleanup.
- [ ] Handle FGS start failures without claiming job acceptance.
- [ ] Implement real active-process cancellation.
- [ ] Distinguish Activity recreation, process death, force-stop, user stop and service timeout.
- [ ] Handle Android 15 `dataSync` timeout:
  - record interruption;
  - terminate engine safely;
  - preserve queued jobs;
  - stop promptly;
  - explain recovery later.
- [ ] Add notification cancel action.
- [ ] Add notification tap-to-open-job action.
- [ ] Test screen-off/sustained transfer.
- [ ] Do not add unrestricted battery access or permanent wake locks without evidence.

### Job state contract

```text
Queued
Preparing
Downloading
Processing
Saving
Completed
Failed
Cancelled
Interrupted
```

Add `WaitingForNetwork` only if real waiting/scheduling behavior exists.

### Exit criteria

- Rapid enqueue/stop/restart is deterministic.
- Active cancellation is real.
- Android 15 timeout becomes an explicit recoverable interruption.
- Service destruction does not silently lose accepted work.

### Findings

`F03, F04, F14, F42`

---

# S4 — Output & Publication

**Objective:** guarantee that a successful download becomes the correct Library file.  
**Dependencies:** S3.

### Tasks

- [ ] `F06` Define and enforce single-video policy.
- [ ] Disable playlist expansion for ordinary single-video jobs.
- [ ] Reject playlist-only URLs unless playlist support is explicitly added later.
- [ ] `F07` Replace “newest matching file” discovery.
- [ ] Give each job a private work directory.
- [ ] Record exact expected/final output path.
- [ ] `F08` Define quality semantics: “up to” vs “prefer”.
- [ ] Apply intended constraints to fallback branches.
- [ ] Derive MIME from actual output.
- [ ] Test actual codec/container playback.
- [ ] `F09` Make publication explicit:

```text
DOWNLOADED
    ↓
PUBLISHING (URI recorded)
    ↓
PUBLISHED (URI confirmed)
    ↓
COMPLETED
```

- [ ] Verify `IS_PENDING` transition.
- [ ] Keep source until publication is confirmed.
- [ ] Recover pending rows after process death.
- [ ] Prevent cleanup errors from replacing the original failure.
- [ ] Exclude pending rows from Library.
- [ ] `F10` Implement job-owned temporary workspace cleanup.
- [ ] Preserve workspace when needed for safe retry/reconciliation.
- [ ] Add storage-full classification.

### Exit criteria

For every job the system can answer:

1. Did download succeed?
2. What is the exact output?
3. Was MediaStore publication confirmed?
4. Is the Library item valid?
5. Can failed publication be retried without losing the only valid source?

### Findings

`F06, F07, F08, F09, F10, F21`

---

# S5 — Durable Jobs

**Objective:** turn accepted downloads into durable, recoverable work.  
**Dependencies:** S4.

### Storage decision

Use a small atomic local journal initially. Room is not required unless history/metadata/query complexity genuinely demands it.

### Persist

- Job ID.
- Canonical source identity/URL.
- Stable quality ID.
- Enqueue order.
- Current phase.
- Failure code.
- Workspace identity.
- Destination snapshot.
- Pending/published URI.
- Important timestamps.

Do not persist every progress callback.

### Tasks

- [ ] `F05` Introduce `DownloadJob`.
- [ ] Introduce `JobRepository`.
- [ ] Introduce `JobJournal`.
- [ ] Persist job before requesting execution.
- [ ] Recover accepted running jobs as `Interrupted`.
- [ ] Reconcile workspace and publication URI on restart.
- [ ] `F14` Add cancel queued, cancel active, retry failed and retry interrupted.
- [ ] Preserve original job specification on retry.
- [ ] `F33` Canonicalize source identity and detect duplicates.
- [ ] Active duplicate → “Already downloading.”
- [ ] Completed duplicate → “Already downloaded — open file or download again.”
- [ ] Bound recent terminal-job retention.
- [ ] Move durable completed content conceptually into Library instead of an unbounded Queue.
- [ ] Snapshot destination at enqueue.

### Enqueue contract

```text
Validate
  ↓
Persist job
  ↓
Request execution
  ↓
Acknowledge acceptance
```

If execution request fails, the persisted job remains visible and recoverable.

### Exit criteria

- Process death never silently erases accepted jobs.
- Interrupted jobs are visible.
- Cancel/retry/dedupe are explicit.
- Journal corruption has a safe recovery path.
- Queue retention is bounded.

### Findings

`F05, F14, F21, F23, F33`

---

# S6 — Core UX & Share Flow

**Objective:** make Kinescope obviously a media-download utility rather than a chat application.  
**Dependencies:** S5.

## Information architecture

Compact phone:

- **Downloads**
- **Library**

Settings remains a top-bar action.

### Tasks

- [ ] `F12/F31` Replace chat-style send icon with labeled **Download**.
- [ ] Make URL input visibly a URL input.
- [ ] Add paste/clear behavior.
- [ ] Keep quality selection compact and understandable.
- [ ] Show destination clearly.
- [ ] Clear input only after durable job acceptance.
- [ ] Show `Preparing download engine`.
- [ ] Show human-readable phases: Preparing / Downloading / Processing / Saving.
- [ ] `F18` Make shared intents consumable:
  - cold launch;
  - warm launch;
  - repeated same URL;
  - new share while draft exists;
  - invalid text;
  - oversized text;
  - watch URL with playlist context;
  - rotation.
- [ ] Shared URL opens Downloads even if Settings was previously visible.
- [ ] Never auto-download a received share without explicit confirmation.
- [ ] `F24` Replace string-based error identity with typed failures.
- [ ] Correct age-restriction vs bot/sign-in classification.
- [ ] Errors must answer:
  1. What happened?
  2. Is the file saved?
  3. What can I do next?
- [ ] `F25` Add actionable progress notification, tap-to-open, cancel, completion notification, throttling and privacy-safe text.
- [ ] Request notification permission at first download with context.
- [ ] Ensure long Queue cannot hide Library.
- [ ] `F32` Add Library loading/empty/ready/error/missing-file states.
- [ ] Show title, saved date, size, quality/type.
- [ ] Expose full title in details/accessibility.
- [ ] Keep newest-first.
- [ ] Add delete confirmation.
- [ ] Preserve external playback and distinguish missing-file/player errors.
- [ ] `F22` Make Settings consistently immediate-save or consistently transactional.
- [ ] Remove top-bar updater shortcut.
- [ ] Remove developer-only `CLAUDE.md` reference from user-facing Settings.
- [ ] Hide/defer arbitrary folder configuration unless genuinely useful.
- [ ] `F11` Move all MediaStore queries off the main thread and stop progress-driven querying.

## First-run experience

No onboarding carousel.

Show:

- Kinescope;
- “Save YouTube videos for offline viewing.”
- URL field;
- Download;
- quality summary;
- `Saved to Downloads/Kinescope`;
- short lawful-use explanation;
- share-from-YouTube hint.

### Exit criteria

A new user understands where to paste/share, what Download does, where the file goes and how to recover from failure without documentation.

### Findings

`F11, F12, F14, F18, F19, F20, F21, F22, F24, F25, F31, F32, F33`

---

# S7 — Design System & Accessibility

**Objective:** make the interface coherent, legible and adaptive.  
**Dependencies:** S6.

### Preserve

- warm background;
- terracotta identity;
- soft corners;
- restrained elevation;
- low visual noise.

### Tasks

- [ ] `F28` Correct primary/action/status contrast.
- [ ] Validate foreground/background pairs instead of judging by eye.
- [ ] Strengthen focus indication.
- [ ] `F30` Complete semantic/container roles and component states.
- [ ] Remove unused tokens or implement them.
- [ ] Tokenize repeated semantic spacing: 4/8/12/16/24/32/48dp.
- [ ] Standardize shapes: 8/12/16/24dp and selective pill.
- [ ] `F39` Recalculate launcher icon safe-zone geometry.
- [ ] Add monochrome icon only if desired; cosmetic, not release-blocking.
- [ ] `F40` Prefer system fonts or properly bundled/licensed fonts.
- [ ] Correct `design.md` so it matches implementation.
- [ ] `F29` Add accessibility semantics for job state/actions.
- [ ] Minimum 48×48dp interactive targets.
- [ ] Status cannot rely on color alone.
- [ ] Full titles accessible when visually truncated.
- [ ] Visible keyboard focus and logical tab order.
- [ ] Correct dialog focus/dismissal.
- [ ] TalkBack and Switch Access validation.
- [ ] 200% font-scale validation.
- [ ] Reduced-motion compatibility.
- [ ] `F20` Correct IME/navigation-bar/window-inset handling.
- [ ] Correct Back behavior: transient UI → details/settings → Activity exit.
- [ ] Compact phone: single-column Downloads/Library.
- [ ] Large phone: wider margins/max content width.
- [ ] Foldables: window-size-aware layout, no hinge-crossing content.
- [ ] Tablets/expanded windows: navigation rail, list/detail and Library detail.
- [ ] Landscape: compact input, scrollable content, no fixed-height empty state, no forced orientation.

### Exit criteria

- Core flows work at large text scale.
- Core actions have valid targets/focus.
- Contrast is measured.
- Phone/landscape/expanded layouts remain usable.
- Design documentation matches actual implementation.

### Findings

`F20, F28, F29, F30, F31, F39, F40`

---

# S8 — Diagnostics, Privacy & Platform Hardening

**Objective:** reduce operational risk and make diagnostics safe.  
**Dependencies:** S4–S7.

## Diagnostics

- [ ] `F41` Add bounded local diagnostic buffer.
- [ ] Record timestamp, job ID, stage, event code, duration, bytes/progress summary, exception category and engine/app version.
- [ ] Do not store by default: full URLs, video titles, cookies, raw command lines or full extractor output.
- [ ] Provide user-triggered diagnostic export.
- [ ] Preview diagnostics before export.
- [ ] Keep production diagnostics local unless explicitly shared.

## Privacy

- [ ] `F26` Remove full URLs from lock-screen notification content.
- [ ] Redact sensitive error/log details.
- [ ] Decide intentional Android backup policy.
- [ ] Exclude job URLs, executable caches and temporary work files from backup.
- [ ] Decide whether preference backup is useful.
- [ ] Explain extractor update checks in Settings.
- [ ] Prefer system/bundled fonts.

## Lawful use and notices

- [ ] `F36` Add concise first-run guidance:

> Download only content you own, have permission to download, or are otherwise legally entitled to save. Platform terms may restrict downloading. Kinescope does not bypass DRM or account restrictions.

- [ ] Repeat a concise reminder near file sharing.
- [ ] Keep authentication/cookie support out of v1.
- [ ] Keep DRM handling/bypass out of scope.
- [ ] Audit FFmpeg licensing for the actual bundled build/configuration.
- [ ] Add notices for redistributed wrapper/runtime/fonts/components.

## Android/native

- [ ] `F16` Inspect native libraries and bundled executables.
- [ ] Verify ELF alignment and packaging.
- [ ] Verify ABI set.
- [ ] Test 16 KB page-size environment where available.
- [ ] Test actual extraction on supported arm64 device.
- [ ] Evaluate compile/target 36 as a complete toolchain/platform migration, not a number-only change.
- [ ] Validate API 35 edge-to-edge and FGS behavior.
- [ ] Validate API 36 behavior where supported.
- [ ] Keep `minSdk = 29` unless evidence requires otherwise.
- [ ] Keep R8 disabled during stabilization unless there is a concrete reason to enable it.
- [ ] Consider arm64-specific release artifact for the personal device.

## Supply chain

- [ ] `F27` Document updater source and integrity controls.
- [ ] Verify atomic replacement and interrupted-update recovery.
- [ ] Verify rollback/known-good behavior.
- [ ] Prevent update execution from racing active downloads.

### Exit criteria

- Diagnostics are useful without leaking content.
- Backup behavior is intentional.
- Licensing/notices are complete.
- Native/runtime compatibility has evidence.
- Updater trust boundary is documented and tested.

### Findings

`F16, F26, F27, F36, F40, F41`

---

# S9 — Verification Matrix

**Objective:** turn implementation into regression evidence.  
**Dependencies:** S0–S8.

Tests must be added alongside implementation; this sprint consolidates coverage.

## Unit tests

### URL parsing

- [ ] Watch URL.
- [ ] `youtu.be`.
- [ ] Shorts.
- [ ] Mobile/music host forms.
- [ ] Watch + playlist context.
- [ ] Playlist-only URL.
- [ ] Channel/homepage.
- [ ] Malicious lookalike hostname.
- [ ] Userinfo.
- [ ] Unsupported scheme.
- [ ] Unexpected port.
- [ ] Trailing punctuation.
- [ ] Multiple URLs.
- [ ] Oversized shared text.
- [ ] Same canonical video with tracking parameters.

### Settings

- [ ] Legacy quality migration.
- [ ] Invalid quality.
- [ ] Empty/whitespace folder.
- [ ] Separators.
- [ ] Dot names.
- [ ] Control characters.
- [ ] Excessive length.
- [ ] Displayed vs committed destination.

### Errors

- [ ] Private content.
- [ ] Age-confirmation phrase.
- [ ] Bot phrase.
- [ ] DNS/network failure.
- [ ] Storage full.
- [ ] FFmpeg failure.
- [ ] Unknown error with URL redaction.

### Queue/state machine

- [ ] FIFO.
- [ ] Duplicate active job.
- [ ] Cancel queued.
- [ ] Cancel active.
- [ ] Retry terminal job.
- [ ] Late progress after cancellation.
- [ ] Enqueue at idle-stop boundary.
- [ ] Persist before acceptance.
- [ ] Recover running job as interrupted.
- [ ] Publication-before-status-commit ordering.
- [ ] Corrupt journal recovery.

Use a fake engine with latches/coroutine scheduling to reproduce the shutdown race deterministically.

## Compose/UI tests

- [ ] First launch.
- [ ] URL validation.
- [ ] Download enabled/disabled state.
- [ ] Keyboard submit.
- [ ] Quality selection.
- [ ] Engine-preparing state.
- [ ] Queue progress.
- [ ] Cancellation.
- [ ] Typed error/retry.
- [ ] Long Queue does not hide Library.
- [ ] Library loading/empty/error.
- [ ] Delete confirmation.
- [ ] Settings semantics.
- [ ] Back from Settings/details.
- [ ] Draft restoration.
- [ ] Warm/cold/repeated share.
- [ ] Large-font rendering.

Use fake repositories. Do not make ordinary UI tests depend on live YouTube.

## Integration tests

- [ ] Real MediaStore publish/read/delete.
- [ ] MIME/display name.
- [ ] Pending-row exclusion.
- [ ] Output-stream failure.
- [ ] Failed pending-state cleanup.
- [ ] Interrupted publication recovery.
- [ ] Source retained after failed publication.
- [ ] Work-directory cleanup.
- [ ] Service start/stop/cancel.
- [ ] Initialization failure.
- [ ] Extractor update excluded from active execution.
- [ ] Process restart with durable jobs.

## Device/API matrix

| Environment | Purpose |
|---|---|
| API 29 | Minimum MediaStore behavior |
| API 33 | Notification permission |
| API 34 | FGS type/permission |
| API 35 | Target behavior, edge-to-edge, data-sync timeout |
| API 36 | Android 16 back/resizing/large-screen behavior |
| Actual arm64 phone | Real extraction, FFmpeg, screen-off, playback |
| Aggressive-OEM device if available | Background interruption |
| Large-screen/foldable emulator if available | Adaptive UI |
| 16 KB page environment | Native compatibility |
| No-GMS/offline font environment if fonts retained | Font fallback |

Distinguish Activity recreation, process death, force-stop, user stop and service timeout.

### Exit criteria

- All release-gate tests pass.
- Every P0/P1 finding has evidence.
- Remaining P2/P3 items are explicitly documented.
- Live extraction tests remain manual/occasional and do not destabilize unit tests.

### Findings

All `F01–F42`, especially the P0/P1 reliability findings.

---

# S10 — Release

**Objective:** produce a signed, traceable and honest Kinescope release.  
**Dependencies:** S9 and all release gates.

### Tasks

- [ ] `F35` Generate/preserve signing key outside the repository.
- [ ] Verify signing configuration.
- [ ] Build release APK.
- [ ] Verify APK signature/certificate.
- [ ] Verify artifact contents.
- [ ] Install signed APK.
- [ ] Install a newer signed APK over the previous signed APK.
- [ ] Verify app data survives update.
- [ ] Verify versionCode/versionName policy.
- [ ] Confirm final application ID.
- [ ] `F36` Complete third-party notices.
- [ ] Rewrite README with actual build/install/use/limitations.
- [ ] Update RELEASE.md.
- [ ] Update HANDOFF.md.
- [ ] Update ROADMAP.md with evidence-based statuses.
- [ ] Record known device/API limitations.
- [ ] Make clear that distribution is a personal sideloaded utility.
- [ ] Never describe an unverified debug APK as a dependable offline-preparation tool.

### Exit criteria

A signed release can be installed, updated, used for several downloads, interrupted/recovered, and trusted to expose the resulting files in Library.

### Findings

`F35, F36, F37, F38`

---

# S11 — Post-release Product Roadmap

**Objective:** add convenience only after reliability is proven.  
**Prerequisite:** S10 complete and real usage has exposed actual needs.

## S11.1 — Better share flow refinements

- [ ] Improve duplicate-share behavior.
- [ ] Add metadata confirmation only for ambiguous/special cases.
- [ ] Keep ordinary links to one explicit confirmation.

## S11.2 — Duplicate detection

If not fully completed in S5:

- [ ] Canonical URL identity.
- [ ] Active duplicate warning.
- [ ] Completed duplicate warning.
- [ ] Intentional re-download.

## S11.3 — Metadata

- [ ] File size.
- [ ] Saved date.
- [ ] Selected quality.
- [ ] Media type.
- [ ] Useful title.

## S11.4 — Storage

- [ ] Storage usage.
- [ ] Cleanup.
- [ ] Orphan workspace cleanup.
- [ ] Storage warnings.

## S11.5 — Smart quality defaults

- [ ] Deterministic sensible caps.
- [ ] Consider device/storage/network context.
- [ ] Do not introduce opaque “AI quality selection”.

## S11.6 — Metered/Wi-Fi policy

Only if real mobile-data risk is observed:

- [ ] Warn on metered connection.
- [ ] Optional Wi-Fi-only policy.

## S11.7 — Local history

- [ ] Bounded local download history.

## S11.8 — Local thumbnails

- [ ] Local thumbnails only when useful.
- [ ] Avoid unnecessary network fetching.

## S11.9 — Localization

- [ ] Externalize all UI strings.
- [ ] Translate only when needed.

## S11.10 — Batch paste

- [ ] Several independent video links.
- [ ] Prefer this before full playlist support.

---

# 4. Explicitly deferred / out of scope

| Feature | Decision |
|---|---|
| Playlist support | Defer; requires preview, item count, size/selection and partial-failure model |
| Scheduling/rules | Defer until real need |
| Additional platforms | Defer; keep YouTube-centered |
| Integrated playback | Low priority; external playback remains preferable |
| Widget | Only for proven shortcut need |
| Android Auto | Do not pursue for current product |
| Self-hosted sync/backend | Defer indefinitely unless real multi-device need appears |
| Authentication/cookie import | Keep out of v1 |
| DRM handling/bypass | Do not add |

---

# 5. Technical debt backlog

These must not displace P0/P1 reliability work.

- [ ] Externalize remaining hardcoded strings.
- [ ] Migrate remaining raw Thread/Handler usage to coroutines where ownership improves clarity.
- [ ] Use `collectAsStateWithLifecycle()` where appropriate.
- [ ] Continue extracting UI from `MainActivity`.
- [ ] Complete component-level design tokens.
- [ ] Optional monochrome launcher icon.
- [ ] Optional arm64-specific release artifact.
- [ ] Consider Room only when journal complexity genuinely demands it.

---

# 6. Master finding traceability

| ID | Priority | Finding | Sprint |
|---|---|---|---|
| F01 | P0 | No reproducible verified build / missing wrapper | S0, S9 |
| F02 | P1 | Engine readiness not consumed | S2 |
| F03 | P1 | Worker/service shutdown race | S3 |
| F04 | P1 | FGS destruction/timeout/cancellation incomplete | S3 |
| F05 | P1 | Jobs are volatile | S5 |
| F06 | P1 | Playlist-capable input can exceed one-job contract | S4 |
| F07 | P1 | Output discovery guesses newest file | S4 |
| F08 | P1 | Quality/MIME semantics unreliable | S4 |
| F09 | P1 | MediaStore publication not crash-recoverable | S4 |
| F10 | P1 | Temporary artifacts unmanaged | S4 |
| F11 | P1 | Main-thread MediaStore queries | S6 |
| F12 | P1 | Queue can hide Library | S6 |
| F13 | P1 | Platform drawable risk on first launch | S0 |
| F14 | P1 | No cancel/retry | S3, S5, S6 |
| F15 | P1 | Engine/update/execution overlap | S2 |
| F16 | P1 | Native/wrapper compatibility unverified | S0, S8, S9 |
| F17 | P2 | URL/share validation too weak | S2 |
| F18 | P2 | Share events not properly consumed | S6 |
| F19 | P2 | UI state/lifecycle ownership weak | S6 |
| F20 | P2 | Insets/back behavior incomplete | S6, S7 |
| F21 | P2 | Library tied to current folder / pending rows | S4, S5, S6 |
| F22 | P2 | Settings Save/Cancel semantics inconsistent | S6 |
| F23 | P2 | Quality index persistence / folder validation | S2, S5 |
| F24 | P2 | String-based error identity and poor mapping | S6 |
| F25 | P2 | Notifications/progress incomplete | S3, S6 |
| F26 | P2 | Notification/log/backup privacy | S8 |
| F27 | P2 | Executable updater trust boundary unverified | S2, S8 |
| F28 | P1 | Core contrast problems | S7 |
| F29 | P2 | Accessibility/adaptive behavior incomplete | S7 |
| F30 | P2 | Design system incomplete | S7 |
| F31 | P2 | Chat-like primary action | S6, S7 |
| F32 | P2 | Library lacks complete state model | S6 |
| F33 | P2 | No duplicate protection / unbounded terminal queue | S5 |
| F34 | P2 | Bootstrap is drift-prone | S0 |
| F35 | P2 | Release signing/artifact verification incomplete | S10 |
| F36 | P2 | Lawful-use guidance/notices incomplete | S8, S10 |
| F37 | P2 | Documentation contains false/overstated claims | S1, S10 |
| F38 | P3 | Branding rename incomplete | S1 |
| F39 | P3 | Launcher icon safe-zone claim incorrect | S7 |
| F40 | P3 | Downloadable fonts add unnecessary runtime dependency | S7, S8 |
| F41 | P2 | Diagnostics unstructured / potentially sensitive | S8 |
| F42 | P1 | No deterministic regression-test foundation | S0, S9 |

---

# 7. Review contradictions that must not survive

Remove or correct these claims in project documentation:

- “Worker race fixed completely.”
- “One bad download can no longer crash the process.”
- “Service migrated to coroutines.”
- “`isReady` protects startup.”
- Incorrect historical claim about the Material progress API.
- Incorrect Android 14/API 34 scope for the data-sync timeout.
- “Dark mode is not included.”
- “Full typography scale is complete.”
- Incorrect Material `errorContainer` statement.
- “Filled Play button implemented” when implementation is an unfilled `IconButton`.
- “Extractor version shown” when it is not actually read/displayed.
- “Last updated” meaning installed update when it may only mean a successful check.
- Launcher icon safe-zone claim that does not satisfy the stated radius.
- “Rename must happen before the first diagnostic install” as an absolute rule.
- “Same versionCode cannot update.”
- “Source is never delivered untested” when only patch validation occurred.
- “Full README draft is ready” when the exported README contains only `# kinescope`.
- “Library lists all previously published files” when it queries only the current folder.
- Connectivity claim based on fragile string equality.
- ABI-size commentary that mischaracterizes the four conventional ABIs.
- Setup-script claim that `DOWNLOAD_URL` can be externally overridden when the script overwrites it.

---

# 8. Product principles

## Keep

- Small local-first utility.
- Android-only.
- No backend.
- No authentication.
- No DRM bypass.
- No custom extraction.
- yt-dlp as extraction boundary.
- Sequential download execution.
- MediaStore publication.
- External playback.
- No broad storage permission.
- No mandatory paid services.
- Sideloaded distribution.
- English code/documentation.

## Do not optimize for

- Enterprise architecture.
- Multiple modules.
- DI frameworks.
- Backend synchronization.
- Embedded browser.
- Integrated player.
- Maximum feature count.
- “AI” where deterministic rules are sufficient.

## Optimize for

> **The user can prepare several videos before leaving home and trust that the saved Library is actually usable offline.**

---

# 9. Dependable-release definition of done

- [ ] Clean reproducible headless build.
- [ ] Wrapper committed.
- [ ] Cold launch succeeds.
- [ ] Immediate share succeeds.
- [ ] First download does not race engine initialization.
- [ ] Multiple queued videos execute FIFO.
- [ ] Worker/service shutdown boundary is deterministic.
- [ ] Android 15 FGS timeout is handled.
- [ ] Cancellation works.
- [ ] Retry works.
- [ ] Process restart restores interrupted jobs.
- [ ] Single-video policy is enforced.
- [ ] Final output is deterministic.
- [ ] MIME is correct.
- [ ] Publication is crash-recoverable.
- [ ] Temporary work is cleaned safely.
- [ ] Library queries do not run on the main thread during progress.
- [ ] Library remains reachable with a long queue.
- [ ] Duplicate downloads are handled.
- [ ] Notifications are actionable and privacy-conscious.
- [ ] Notification denial behavior is understood.
- [ ] Share flow works cold/warm/repeated.
- [ ] Library has complete loading/empty/error/missing-file states.
- [ ] Delete confirmation works.
- [ ] Core contrast is corrected.
- [ ] TalkBack/focus/large text behavior is acceptable.
- [ ] Landscape/expanded layouts are acceptable.
- [ ] API 35/36 validation is complete to supported scope.
- [ ] 16 KB native compatibility is verified.
- [ ] Extractor/native compatibility is demonstrated.
- [ ] Updater behavior is audited.
- [ ] Backup policy is intentional.
- [ ] Diagnostics are redacted.
- [ ] Lawful-use and dependency notices are present.
- [ ] README/RELEASE/HANDOFF/ROADMAP describe reality.
- [ ] Signed APK is verified.
- [ ] Signed update over previous build succeeds.

---

# 10. Final rule

**No new feature sprint may outrank an unresolved P0/P1 reliability or release gate.**

The project should remain small.

Success is not “Kinescope has more features.”

Success is:

> **A user queues videos, leaves the app, returns later, and finds the correct playable files in Library without wondering whether the download actually happened.**
