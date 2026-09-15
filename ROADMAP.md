# Roadmap

**Status as of this update (after patch 16):** Steps 1-4, Step 6
(6.1-6.6; 6.7 is optional and still skipped), Step 7 (Kinescope
rename), and the full build-environment/compile-error fix chain
(patches 07-14) are done. **`./gradlew assembleDebug` succeeds** — the
first successful build in this project's history. Nothing has
touched a real device yet; that's Step 5, next — now also buildable
via `.github/workflows/build-debug.yml` (manual `workflow_dispatch`,
hand-assigned version) as an alternative to a local Codespace build. A
full deep code review of the entire codebase was performed by Claude
Fable 5.1 in 4 passes; this document consolidated every finding from
that review into one ordered implementation plan. **As of patch 16,
completed Step sections below are collapsed to a one-line pointer
instead of repeating their full original checklist — see
`CHANGELOG.md` for what each patch actually did, and `HANDOFF.md` for
the patch-by-patch narrative.**

**Decided:** the Kinescope rename (Step 7) uses `applicationId` /
`namespace` **`com.kinescope.app`**.

**Execution order from here — this deliberately does NOT match the Step
numbers below**, because Step 7 must happen before Step 5's first device
install (changing `applicationId` after that is effectively irreversible —
Android treats it as a different app), and Step 6 needed to be finished
first since Step 7 touches many of the same files:

1. ~~Step 6 — Design system v2~~ ✅ done (6.1-6.6; 6.7 optional, skipped)
2. ~~Step 7 — Kinescope rename~~ ✅ done (patch 06)
3. **Step 5 — First device install + testing** ← next
4. Step 8 — Documentation
5. Step 9 — Signed release

**After Step 9 is done, and only then:** this repo also has a `roadmap.md` (lowercase) — a separate, newer sprint-based audit/plan (S0-S11, findings F01-F42) from GPT Astra, added by the user and not yet started. Do not merge it into this document or start it early — finish everything above (through Step 9) first. Once both this `ROADMAP.md` and `roadmap.md` are fully executed, both files get deleted.

Steps 1-4 (compile blockers, then critical/product-quality fixes) are done
and came first, as they had to — nothing else matters until the app
actually compiles.

**There is no Phase 8.** The sections below are **verification and fix
steps**, not new numbered feature phases — this document extends the
`Step 1-5` verification plan Fable proposed, it doesn't replace it with new
"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.

**How to use this document:** the numbered Step sections below keep their
original order (matching the initial review) for reference — follow the
**execution order above**, not the numbering, for what to actually do
next. Completed Steps are collapsed to a pointer at `CHANGELOG.md`
rather than repeating their checklist. The Appendix at the end now only
lists findings that are still open or informational — closed findings
moved to `CHANGELOG.md` too.

---

## Step 1 — Fix known compile-time blockers, before first sync

✅ **Done.** Compose BOM version fixed, dead `requestLegacyExternalStorage`
removed. See `CHANGELOG.md`'s Patch 01 entry for detail. (The optional,
never-done `ndk.abiFilters` trim moved to the Backlog section below —
it was never blocking anything.)

---

## Step 2 — First headless compile

✅ **Done.** `./gradlew assembleDebug` succeeds (first achieved after
patch 14). Progress-callback arity confirmed 3-parameter,
`updateYoutubeDL()`'s `UpdateChannel` argument added, import paths
confirmed against the library's real tagged source. See `CHANGELOG.md`'s
Patch 01, 07, and 14 entries.

---

## Step 3 — Critical runtime fixes, before first device install

✅ **Done.** The `DownloadService.ensureWorkerRunning()` race condition
(jobs silently stranded at "Queued") fixed with a blocking consumer loop
and lock-guarded state transitions; a catch-all exception handler added
so one bad download can't crash the whole app. See `CHANGELOG.md`'s
Patch 01 entry — and the doc comment above `startWorkerLocked()` in
`DownloadService.kt` for why this fix is tighter than the sample fix
originally sketched here.

---

## Step 4 — Product-quality fixes, cheap wins before device testing

✅ **Done.** Filenames humanized via yt-dlp's own title template,
job-id-tag output-file scanning, the `RELATIVE_PATH` trailing-slash
mismatch fixed, `DownloadQueueBus` updates made atomic, subfolder-name
sanitization, YouTube-host validation on shared/pasted URLs, an
`ActivityNotFoundException` guard on video playback, and a dead branch
removed. See `CHANGELOG.md`'s Patch 02 entry.

---

## Step 5 — Install and run on a real device

No emulator exists in this environment — this step is manual, on your own
phone. Beyond Fable's original test sequence, a few additions below
specifically target the bugs found in Step 3.

**Build environment (patches 07-14):** the environment needed five fixes before the first compile could even be attempted — a `pipefail` bug in `setup.sh` (07), two re-run/idempotency bugs in `setup.sh` (10), and a JDK/Gradle mismatch where this Codespace's actual default JDK (25.0.2) is too new for Gradle 8.10.2 (ceiling: Java 23, per Gradle's own 8.10 release notes), fixed by pinning Gradle to an already-installed JDK 21 instead (11-12). Two real compile errors followed: an invalid `--` inside an XML comment (13), and `UpdateChannel` actually being a nested class of `YoutubeDL` rather than top-level, i.e. Appendix #11 (14). Full story in `HANDOFF.md`'s patch history and "Key learnings" — kept brief here since it's now resolved history, not an open risk. **`./gradlew assembleDebug` succeeds.**

- [ ] Install the debug APK (`adb install`, or transfer + tap).
- [ ] Grant any runtime permissions prompted (notifications, etc.).
- [ ] Share a real YouTube link into the app via the Android share sheet;
      separately, paste one directly.
- [ ] Queue a short video at a low quality preset first (fastest full
      round-trip).
- [ ] Confirm: it downloads, appears in Library **with a real title**
      (not a UUID, if Step 4's filename fix is in), plays via the system
      player, and is visible in a file manager under
      `Downloads/<subfolder>`.
- [ ] Background the app mid-download; confirm the notification persists
      and the download completes.
- [ ] **Specifically stress-test the Step 3 race condition**: queue 3–4
      videos in quick succession (within a couple seconds of each other,
      simulating the realistic "prepping for a trip" pattern from the
      CJM) and confirm every single one actually starts and completes —
      this is the exact scenario that used to be able to strand a job
      silently at "Queued."
- [ ] Try one deliberately broken case (an age-restricted or private
      video) to see the actual error text yt-dlp returns, and confirm
      `friendlyError()`'s string-matching against real current yt-dlp
      output (e.g. "Sign in to confirm you're not a bot" for
      bot-detection) still works — this was flagged as "partially
      confirmed, partially outdated" and genuinely needs a real device to
      resolve, no amount of code reading settles it.
- [ ] Try airplane mode / no connectivity at queue time, confirm the app
      degrades gracefully rather than crashing (this is also where the
      Step 3 catch-all fix should prevent any exception type from taking
      down the whole app, so this doubles as a regression check for that
      fix).
- [ ] Confirm the app doesn't crash on a very large/slow download; if it
      ever does get killed mid-transfer on Android 14+ specifically, note
      that `dataSync`-type foreground services have a rolling execution
      time budget (hours/day, not indefinite) — informational only,
      unlikely to matter for typical video lengths, but worth knowing if
      it ever happens.

Do not move to Step 9 (signed release) until every item above passes.

---

## Step 6 — Design system v2

✅ **Done** (6.1-6.6: light/dark color tokens, a completed typography
scale, per-screen component patterns, adaptive-icon safe-zone fix). **No
"Claude"/Anthropic name, logo, or licensed fonts anywhere — this
constraint is unchanged and non-negotiable**, and nothing in patches
03-04 violated it. See `CHANGELOG.md`'s Patch 03 and 04 entries for
detail. 6.7 below is the one item still open.

### 6.7 Optional, not required for v2

- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) — cosmetic only, currently the
      icon just won't participate in themed-icon tinting.

---

## Step 7 — Branding: rename to Kinescope

✅ **Done.** `namespace`/`applicationId`/`rootProject.name` →
`com.kinescope.app` / `kinescope`, done before Step 5's first device
install while `applicationId` was still a safe, reversible change; every
user-visible "YT Offline" string → "Kinescope". Internal-only Kotlin
identifiers (`YtOfflineTheme`, `YtOfflineApp`, etc.) deliberately left
unchanged — not user-visible, not in scope. See `CHANGELOG.md`'s Patch
06 entry.

---

## Step 8 — Documentation

- [x] Replace `README.md` with a real one (Fixed — patch 15; written
      fresh rather than pasting an old drafted version, which could no
      longer be located in the repo by that session — covers what it
      is, who it's for, build instructions, status, documentation map,
      and explicit limitations). This checkbox itself was accidentally
      left unflipped until patch 16 caught it.
- [x] Add a `CJM.md` (or fold into `design.md`) capturing the Customer
      Journey Map produced during the review — five stages (prep at home
      → queue & download → departure/loses access → watch offline in-
      region → return & refresh library), with the explicit finding that
      the single highest-risk moment is the silent-failure window at
      home the night before a trip. This is the "why" behind Steps 3–4's
      priority ordering and is worth keeping as a living reference, not
      just a one-time review artifact. (Fixed — patch 17: written as a
      standalone `CJM.md` rather than folded into `design.md`, since the
      two documents serve different audiences — one visual, one
      product/prioritization.)
- [ ] Keep this `ROADMAP.md` itself as the living source of truth for
      "what's actually been verified vs. still assumed" — update the
      checkboxes above as each item is actually done, don't let it drift
      back into "written but unverified" the way the original 7 phases did.

---

## Step 9 — Signed release

Only once **every item in Steps 1–5 is done and confirmed on a real
device**. Follow `RELEASE.md` in full — do not skip ahead to save time; an
unverified debug build signed into a release build is still unverified.

---

## Backlog — optional, unscheduled, not required

Everything below is opt-in and user-prioritized, explicitly **not** a
commitment or a new numbered phase:

- [ ] Trim `x86`/`x86_64` from `ndk.abiFilters` in `app/build.gradle.kts`
      if the target phone is arm64 (the overwhelming majority are) and
      emulator support isn't needed — shrinks the APK, since
      `youtubedl-android`'s bundled native binaries dominate its size.
      Originally Step 1's one optional, non-blocking item; moved here in
      patch 16 since it was never actually blocking anything.
- [ ] Persist download queue state (small local DB or file) so a process
      kill doesn't silently lose in-flight job status with zero UI
      indication — currently accepted debt (`DownloadQueueBus` is a bare
      in-memory `StateFlow`), reasonable for v1 but worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case the
      process was OOM-killed mid-download by an aggressive OEM battery
      manager (Xiaomi/Huawei/Samsung-class skins do this even to
      foreground services) — scan for leftover temp files from a
      previous run on service start, resume or clean them up.
- [x] In-app delete for library entries (vs. relying on an external file
      manager) — done as of patch 04: the Step 6.5 overflow menu's
      Delete action calls `MediaStorage.delete()`
      (`ContentResolver.delete()` on the app's own `MediaStore` row),
      not just the UI affordance.
- [ ] Migrate `collectAsState()` to `collectAsStateWithLifecycle()` in
      `MainActivity.kt` — fine as-is for a single-screen app, revisit only
      if a second screen (e.g. a dedicated Library screen) is added.
- [ ] Migrate remaining raw `Thread`/`Handler(Looper.getMainLooper())`
      usage (`YtOfflineApp.kt`, `MainActivity.kt`'s `runUpdate()`) to
      `rememberCoroutineScope()` + `withContext(Dispatchers.IO)`, for
      consistency with the coroutines-based fix already applied to
      `DownloadService` in Step 3.
- [ ] Externalize remaining hardcoded UI strings ("Queue", "Library",
      `friendlyError()` messages, Settings labels) into `strings.xml` —
      zero functional impact for a personal single-language app, purely a
      "nice to have if you're already touching that code."
- [ ] Batch-queue a full playlist by URL, if that becomes a real use case.
- [ ] Self-hosted backend for cross-device queue sync — explicitly
      optional per `CLAUDE.md`'s zero-required-cost rule, never a
      requirement.
- [ ] Note for future Codespace rebuilds: `.devcontainer/setup.sh` scrapes
      the Android cmdline-tools download URL from a live webpage rather
      than a pinned version, which fails safely (loud error with
      instructions) but means a rebuilt Codespace could silently pick up
      a newer cmdline-tools version than your first successful build did.
      If a *rebuilt* Codespace ever behaves differently than the original
      for no apparent code reason, check this script's output first.

---

## Process note for future sessions

The original build-verify-after-every-phase discipline in `CLAUDE.md` was
intentionally overridden by explicit user instruction during initial
development ("keep going, we'll test everything at the end"). That was a
valid call for a solo prototyping burst, but it's also *exactly* why this
document exists — nearly every Critical/High finding above is a direct
consequence of code that was never compiled, let alone run. Going forward,
once Step 2 succeeds for the first time: **prefer compiling after each
meaningful change**, not just at the end of a long unattended session.

---

## Appendix — Open findings only

Originally a full traceability table for every finding from the 4-part
review (35 rows). As of patch 16, closed findings (everything that was
✅ Fixed/Confirmed — originally rows 1-15, 18, 21-32, 34-35) have moved
to `CHANGELOG.md`'s per-patch entries, keeping this table to what's
still actually open or informational. Original row numbers preserved
below for cross-reference with `CHANGELOG.md` and old session history.

| # | Priority | Finding | Location | Status |
|---|---|---|---|---|
| 16 | Low | Inconsistent Thread/Handler vs. coroutines style | `YtOfflineApp.kt`, `MainActivity.kt` | Open — Step 3 (partial), Backlog (rest) |
| 17 | Low | Only `app_name` externalized to `strings.xml` | `strings.xml` | Backlog |
| 19 | Info | No monochrome adaptive-icon layer (Android 13+ themed icons) | resources | Backlog (Step 6.7) |
| 20 | Info | `dataSync` foreground service execution time budget on API 34+ | `DownloadService.kt` | Informational only |
| 33 | — | `friendlyError()` string matching against real yt-dlp output | `DownloadService.kt` | Needs device verification — Step 5 |
