# Roadmap

**Status as of this update:** All 7 original numbered phases + two design passes are
written but **still unbuilt** — `./gradlew assembleDebug` has never run. A full
deep code review of the entire codebase (docs, build config, all Kotlin
source, all resources) was performed by Claude Fable 5.1, in 4 passes, and
this document consolidates every finding from that review into one ordered
implementation plan.

**There is no Phase 8.** The sections below are **verification and fix
steps**, not new numbered feature phases — this document extends the
`Step 1–5` verification plan Fable proposed, it doesn't replace it with new
"Phases." See `HANDOFF.md` and `CLAUDE.md` for why that distinction matters.

**How to use this document:** work top to bottom. Steps 1–3 are blocking —
nothing else matters until the app compiles and runs once on a real phone.
Steps 4–6 (design, branding, docs) can happen in any order once Steps 1–3
are done, ideally before Step 7 (signed release). The Appendix at the end
is a full traceability table — every single finding from all 4 review
parts is listed there with its current status, so nothing gets lost.

---

## Step 1 — Fix known compile-time blockers, before first sync

Do these *before* running `./gradlew assembleDebug` for the first time —
they're the ones the review is fairly confident will fail loudly and
immediately.

- [ ] **[CRITICAL] Fix the Compose BOM version.**
  `app/build.gradle.kts` currently pins:
  ```kotlin
  val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
  ```
  Every other pinned version in the same file (AGP 8.7.2, Kotlin 2.1.0,
  Gradle 8.10.2, `activity-compose` 1.9.3, `core-ktx` 1.15.0,
  `kotlinx-coroutines-core` 1.9.0) clusters around Sept–Nov 2024. The BOM
  is the one outlier, ~21 months later — almost certainly a fabricated
  version string. Replace it with a real BOM from the same window (check
  the [Compose BOM mapping table](https://developer.android.com/jetpack/compose/bom/bom-mapping)
  for the latest one that actually existed at write time — `2024.10.01` or
  `2024.11.00` are good starting guesses), or just run
  `./gradlew dependencies` and let Gradle's own resolution error tell you
  the nearest valid version.

- [ ] **[LOW, cleanup] Remove dead `requestLegacyExternalStorage="true"`**
  from `AndroidManifest.xml`. Only honored at `targetSdkVersion <= 29`;
  here `targetSdk = 35`, so it's silently ignored. `MediaStore`-based
  publishing (Phase 3) is the real mechanism already handling this
  correctly — the flag is leftover noise from copy-pasting
  `youtubedl-android`'s own README setup instructions. Zero functional
  risk either way, just delete it for clarity.

- [ ] **[Optional, not required] Trim `x86`/`x86_64` from `ndk.abiFilters`**
  in `app/build.gradle.kts` if your target phone is arm64 (the
  overwhelming majority are) and you don't need emulator support — shrinks
  the APK, since `youtubedl-android`'s bundled native binaries dominate
  APK size. Purely optional; the full 4-ABI set is not wrong, just larger
  than necessary for a single-physical-phone target.

**Confirmed fine, no action needed at this step** (verified against the
real build files during the review, listed here so you don't waste time
re-checking them): `sdkmanager` package identifiers
(`platforms;android-35`, `build-tools;35.0.0`) match `compileSdk`/`targetSdk`
correctly · `android:extractNativeLibs="true"` is correct and required by
`youtubedl-android`'s native binaries, keep it · AGP 8.7.2 + Gradle 8.10.2
+ Kotlin 2.1.0 is a real, mutually compatible toolchain · `isMinifyEnabled
= false` on release is a deliberate, reasonable call (avoids R8 breaking
reflection-heavy coroutine/yt-dlp-wrapper code) for a personal one-user
app, revisit only if APK size ever actually becomes a problem.

---

## Step 2 — First headless compile

```bash
./gradlew assembleDebug
```

Fix compile errors **top-down, earliest-phase code first** — an early
wrong assumption commonly cascades into unrelated-looking errors further
down the same file. Specifically watch for:

- [ ] **[HIGH] `YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds -> ... }`**
  in `DownloadService.kt` — the real `youtubedl-android` progress callback
  is believed to be **three-parameter**
  (`progress: Float, etaInSeconds: Long, line: String`), not two. Kotlin
  requires exact arity for lambda literals against a function type, so
  this should either fail to compile (in which case: add the third
  parameter, ignore it if unused — `{ progress, etaInSeconds, _ -> ... }`)
  or, if it *does* compile as written, that means the real signature is
  two-parameter after all and no fix is needed. Either way this is a
  compile-time question that resolves itself here — just don't be
  surprised by it.

- [ ] **[LOW] `youtubedl-android`/`com.yausername.ffmpeg` import paths** —
  believed correct after review, but do a 30-second sanity check
  (`unzip -l` the resolved AAR in `~/.gradle/caches`, or just read the
  compiler's "cannot resolve symbol" errors if any appear) rather than
  trusting anyone's memory, including this document's.

- [ ] **[LOW] `updateYoutubeDL()` return type** in `YtDlpUpdater.kt` — if
  the real method returns an enum (`YoutubeDLUpdateStatus`) rather than a
  printable `String`, this is a type-mismatch compile error, not a silent
  runtime no-op. Low-impact either way (fails loud and cheap to fix here,
  or fails soft at runtime if the assumption happens to be compatible).

**Confirmed fine, no action needed at this step:** `addOption(key)` /
`addOption(key, value)` overload usage in `QualityPresets.kt` matches the
real `YoutubeDLRequest` API shape.

Do not move to Step 3 until `assembleDebug` succeeds cleanly.

---

## Step 3 — Critical runtime fixes, before first device install

These two are **confirmed real bugs found by reading the actual source**,
not speculation — fix both before the first real-device test, not after.
They map directly onto the single highest-risk moment in the whole
product's Customer Journey Map: queuing several videos back-to-back at
home the night before a trip, when a silent failure is most likely and
most expensive (no way to retry once already in-region).

- [ ] **[CRITICAL] Race condition in `DownloadService.ensureWorkerRunning()`.**
  `queue.poll()` is non-blocking and the `workerThread?.isAlive == true`
  check is unsynchronized. Sequence that strands a job forever at
  "Queued": worker thread finishes a job, `poll()` returns `null`, worker
  is about to exit → at that exact moment a new job is enqueued from the
  main thread → `ensureWorkerRunning()` sees `isAlive == true` (worker
  hasn't finished tearing down yet) and assumes the existing worker will
  pick it up → but the worker already committed to exiting and never
  re-polls. The new job sits at "Queued" forever with no error shown.

  **Fix** — replace the poll-until-empty pattern with a blocking consumer
  loop and a lock-guarded state transition:
  ```kotlin
  workerThread = Thread {
      startForegroundWithNotification("Starting downloads…")
      try {
          while (true) {
              val job = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: break
              runJob(job)
          }
      } finally {
          synchronized(lock) { workerThread = null }
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
      }
  }
  ```
  with `queue.add()`, the `isAlive` check, and the `workerThread`
  assignment all guarded by the same lock. **Better long-term fix**, worth
  doing now since it also resolves the idiomaticity note in the Backlog
  section below: replace the raw `Thread` + `LinkedBlockingQueue` with a
  `Channel<DownloadJob>` consumed by a single coroutine launched once in
  `onCreate()` and never torn down until the service itself is destroyed —
  this removes the restart-detection problem entirely instead of patching
  around it, and brings the file in line with the `kotlinx-coroutines-core`
  dependency already used elsewhere.

- [ ] **[CRITICAL] Unhandled exceptions in `runJob()` can crash the whole app.**
  Only `YoutubeDLException` and `InterruptedException` are caught. Any
  other exception type (`IOException`, an unexpected `NullPointerException`
  from an unusual library response shape, etc.) propagates out of the
  worker thread uncaught — and Android's default behavior for an uncaught
  exception on *any* thread is to kill the whole process. One malformed
  video or one unexpected error type can silently take down every other
  job still waiting in the queue, with zero explanation to the user.

  **Fix** — add a catch-all after the existing specific catches, so
  specific handling still takes priority but nothing escapes:
  ```kotlin
  } catch (e: Exception) {
      DownloadQueueBus.update(job.id) {
          it.copy(state = JobState.FAILED, progressText = friendlyError(e.message ?: e.javaClass.simpleName))
      }
  }
  ```

---

## Step 4 — Product-quality fixes, cheap wins before device testing

Not crash-level bugs, but real defects with disproportionately bad
failure modes for what this app is actually for. All cheap (minutes each)
relative to their impact — do these in the same sitting as Step 3 since
you'll already be in `DownloadService.kt`/`MediaStorage.kt`.

- [ ] **[HIGH] Downloaded files and library entries have no human-readable name.**
  `job.id` (a raw UUID) is used as both the temp filename and the
  `DISPLAY_NAME` shown in the app's own Library and in any file manager —
  every file looks like `download_3f9a2e1b-....mp4`. This directly
  undermines the CJM's "is this the right file?" / library-management
  stage, especially with multiple videos queued at once (the realistic
  usage pattern). **Fix, pick one:**
  - Cheapest: after a successful download, run a near-instant second
    yt-dlp invocation with `--print title` (no download) to get the
    title, sanitize it (strip `/`, `:`, etc.), and use it as
    `DISPLAY_NAME` while keeping the UUID-based temp filename internally.
  - Better, and pairs naturally with the next fix: use yt-dlp's own title
    template directly, e.g. `-o "${cacheDir}/%(title).150B [${job.id}].%(ext)s"`
    — the bracketed job id keeps the file uniquely identifiable for the
    "find the output file" step, and gets a real title into the filename
    with no extra yt-dlp invocation. Strip the bracketed id back out
    before setting `DISPLAY_NAME`.

- [ ] **[MEDIUM] Keep the "scan cache dir for newest matching file" fallback**
  for locating the completed download, rather than assuming an exact
  `tempBaseName.expectedExtension`. The `--merge-output-format mp4` option
  reliably forces `.mp4` when the format selector's primary branch
  (`bv*[height<=1080]+ba`) is used — but if the `/b` fallback branch
  triggers (no separate video+audio streams available for that video),
  `--merge-output-format` may not apply and the real extension could be
  whatever the single pre-muxed stream uses (occasionally `.webm`). Narrower
  risk than originally feared, but still worth the defensive fix — and it
  naturally combines with the filename fix above if you search for
  `*[${job.id}]*` instead of an exact name match.

- [ ] **[MEDIUM] Fix the `RELATIVE_PATH` trailing-slash mismatch** between
  `MediaStorage.publish()` (no trailing slash on insert) and
  `MediaStorage.listPublished()` (trailing slash in the query selection).
  `MediaStore` conventionally stores `RELATIVE_PATH` with a trailing slash
  and *usually* normalizes a missing one on insert — but relying on that
  normalization behaving identically across OEM `MediaStore`
  implementations is exactly the kind of assumption this project has
  already been burned by. If it doesn't normalize on some device, the
  exact-match query silently returns zero rows and the Library list
  appears permanently empty even though downloads succeeded. **Fix**: use
  the identical string, with trailing slash, in both places:
  ```kotlin
  put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
  ```

- [ ] **[MEDIUM] Make `DownloadQueueBus` updates atomic.** `upsert()` and
  `update()` both do a plain read-`_jobs.value`-then-write, not atomic —
  `upsert()` runs from the main/binder thread (enqueue), `update()` runs
  from the worker thread (progress ticks), and these genuinely race. Worst
  case is a dropped progress tick or a briefly-missing row, not a crash,
  but the fix is trivial:
  ```kotlin
  fun upsert(status: DownloadJobStatus) {
      _jobs.update { current -> current.filterNot { it.id == status.id } + status }
  }
  fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
      _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }
  ```

- [ ] **[MEDIUM] Sanitize the user-editable Downloads subfolder name**
  in `Settings.kt` before it flows into `MediaStore.RELATIVE_PATH` for
  both insert and query. Reject or strip path separators (`/`, `\`) and
  `..` segments — worst case today is a silently-failed publish or files
  landing somewhere unexpected.

- [ ] **[MEDIUM] Host-validate shared/pasted URLs** in `MainActivity.kt`.
  The current regex (`https?://\S+`) accepts any http(s) URL, not just
  YouTube — yt-dlp will happily attempt any of the 1000+ sites it
  supports. This doesn't violate the "no custom extractor" rule (still
  100% via yt-dlp), but it's scope creep against the app's stated single
  purpose. Restrict to `youtube.com`/`youtu.be` hosts before enqueueing,
  both as a UX guardrail and to keep the app doing exactly one thing.

- [ ] **[LOW] Guard `startActivity(ACTION_VIEW)`** in `MainActivity.kt`'s
  `playItem()` with a try/catch around the call, showing a toast/snackbar
  on `ActivityNotFoundException` instead of crashing. Very unlikely on a
  real phone with any video player installed, but cheap insurance.

- [ ] **[LOW, cleanup] Remove the dead `else` branch** in
  `DownloadService.startForegroundWithNotification()` — the
  no-type `startForeground()` fallback is unreachable since
  `minSdk = 29` already satisfies the `>= Build.VERSION_CODES.Q` check
  guarding the typed branch. Harmless, just simplify.

**Confirmed fine, no action needed:** foreground service type declaration
+ runtime `startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` call
match exactly · `POST_NOTIFICATIONS` is both declared in the manifest and
requested at runtime in `MainActivity.onCreate()`, correctly non-fatal if
denied · no `<queries>` manifest entry is needed — `playItem()` calls
`startActivity()` directly with no `resolveActivity()` precheck, so the
API 30+ package-visibility restriction never applies here.

---

## Step 5 — Install and run on a real device

No emulator exists in this environment — this step is manual, on your own
phone. Beyond Fable's original test sequence, a few additions below
specifically target the bugs found in Step 3.

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

Do not move to Step 7 (signed release) until every item above passes.

---

## Step 6 — Design system v2

Building on `design.md`/`Theme.kt`'s existing foundation (same nature:
warm palette, single accent, soft geometry) but tightened into an actual
system — a real dark theme, semantic tokens for states the original
palette didn't cover, and named component patterns per screen instead of
colors/shapes applied ad hoc. **No "Claude"/Anthropic name, logo, or
licensed fonts anywhere — this constraint is unchanged and non-negotiable.**
This step can happen in parallel with Steps 1–5 if you want, but should be
merged before Step 7.

### 6.1 Color tokens — light (additions to the existing palette)

- [ ] Add `surfaceRaised` — white surface + soft shadow (`alpha 0.04`
      black), for modal sheets/dialogs, distinct from flat row cards.
- [ ] Add `warning` (`#B8862E`) — new middle state between success/error,
      for "Paused"/"Retrying" job status (currently missing).
- [ ] Add `errorContainer` (`#F9DEDC`) — for error banners and empty-state
      backgrounds, distinct from the existing per-row `error` color.

### 6.2 Color tokens — dark (new, currently missing entirely)

- [ ] Implement a `darkColorScheme(...)` alongside the existing
      `lightColorScheme(...)`, selected via `isSystemInDarkTheme()` in
      `Theme.kt` (standard Material3 pattern, no new architectural risk):

  | Token | Hex | Notes |
  |---|---|---|
  | `background` | `#1B1A17` | Warm near-black, keeps the "warm" principle in dark mode |
  | `surface` | `#252420` | |
  | `surfaceVariant` | `#302E28` | |
  | `onBackground`/`onSurface` | `#F5F3EC` | |
  | `onSurfaceVariant` | `#B8B6AC` | |
  | `outline` | `#3D3B34` | |
  | `accent` | `#E08D6D` | Lightened terracotta — pure `#D97757` loses contrast on dark |
  | `onAccent` | `#3D1A0E` | Dark text on the lightened accent reads better than white |
  | `accentContainer` | `#5C3423` | |
  | `onAccentContainer` | `#F3DDD2` | |
  | `success` | `#9AB07C` | |
  | `warning` | `#D6A24E` | |
  | `error` | `#FFB4AB` | Material3-standard dark-scheme error red |
  | `errorContainer` | `#5C0F0C` | |

### 6.3 Typography — tighten the existing scale

Keep the Inter (body) + Lora (headline) pairing already implemented
(low-risk per the review). Make sure every role below is actually defined,
not just the ones currently in use:

- [ ] `displaySmall` — Lora SemiBold — app title, true empty/first-run state only
- [ ] `headlineSmall` — Lora SemiBold — screen-level headers, for if more screens are added
- [ ] `titleMedium` — Inter SemiBold — section headers ("Queue", "Library")
- [ ] `titleSmall` — Inter Medium — row titles (video name)
- [ ] `bodyMedium` — Inter Regular — status lines, settings descriptions
- [ ] `labelLarge` — Inter Medium — button text
- [ ] `labelSmall` — Inter Medium — chips, timestamps, byte counts

### 6.4 Shape scale

No changes needed — already coherent: `extraSmall` 6dp (badges),
`small` 10dp (text fields/chips), `medium` 14dp (row cards), `large` 20dp
(composer bar/dialogs), `extraLarge` 28dp (pill buttons).

### 6.5 Component patterns to implement per screen

- [ ] **Download queue (active)** — `medium`-shape surface on
      `surfaceVariant`, solid `accentContainer` thumbnail placeholder with
      a play-glyph (no network thumbnail fetch, keeps cost/scope at zero),
      status line colored per state (`onSurfaceVariant` queued, `accent`
      downloading, `success` done, `warning` retrying, `error` failed),
      linear progress bar in `accent` only while actively downloading.
- [ ] **Download queue (empty)** — centered `accentContainer` circle
      behind a download-arrow icon, no button (the composer bar below is
      already the call to action — don't duplicate it).
- [ ] **Library** — same row pattern as queue, filled icon-only Play
      button in `accent`, secondary overflow icon (⋮) for
      delete/share-file actions (this is also the fix for the CJM's
      "no in-app delete" gap, see Backlog).
- [ ] **Settings** — group into labeled sections (`titleMedium` headers,
      `outline`-divided rows) instead of a flat list: Default Quality,
      Storage (subfolder name), Extractor (yt-dlp version + manual update
      button + last-updated timestamp).
- [ ] **Error states** — inline per-row errors keep `error`/`errorContainer`
      as today; add a new dismissible banner pattern
      (`errorContainer` background, `error` text) specifically for
      connectivity-loss-at-queue-time, since that's a systemic state that
      deserves different visual treatment than a single video's failure.
- [ ] **Composer bar** — keep the existing `large`-shape pill with `accent`
      send button; add a subtle `outline`-colored focus border (currently
      likely relies on fill alone for affordance) and an inline greyed
      placeholder hint ("Paste a YouTube link").
- [ ] **App icon** — no change needed to the concept (terracotta
      background, cream download-glyph, no external assets/licensing
      risk) — but see the safe-zone fix below.

### 6.6 Adaptive icon fix

- [ ] **[LOW] Fix `ic_launcher_foreground.xml`'s tray shape clipping
  outside the adaptive-icon safe zone.** The tray/base rectangle's bottom
  corners (`34,87` / `74,87`) sit ~38.6dp from center — outside the
  guaranteed-visible 33dp-radius safe circle. On circular-mask
  launchers/OEM skins, the bottom corners will be silently clipped,
  making the tray look shortened/asymmetric (fine on squircle/rounded-
  square masks, which is why this is easy to miss). **Fix**: narrow the
  tray's x-range at the bottom, e.g. `40,79 → 68,79 → 68,87 → 40,87`
  instead of `34...74`. Purely cosmetic, fix whenever you're already
  looking at the icon on a real device.

### 6.7 Optional, not required for v2

- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) — cosmetic only, currently the
      icon just won't participate in themed-icon tinting.

---

## Step 7 — Branding: rename to Kinescope

- [ ] **[Do this now, not later]** `namespace`/`applicationId`/
      `rootProject.name` in `app/build.gradle.kts` and
      `settings.gradle.kts` still say `com.baltic.ytoffline` / `yt-offline`.
      **Because the app has never been installed on a real device yet,
      this is the last safe window to change `applicationId` cleanly** —
      once a real device install exists, changing `applicationId` becomes
      a one-way door (Android treats it as an entirely new app: separate
      data, separate install, old one needs manual uninstall). If you
      want the "Kinescope" rename to be permanent and clean, do the full
      `applicationId`/`namespace` rename **before** Step 5's first
      install, not after.
- [ ] Update `app_name` in `strings.xml` from `"YT Offline"` to
      `"Kinescope"` — this one is always safe to change at any time,
      `android:label` is purely cosmetic (unlike `applicationId`).
- [ ] No icon/color changes are required for a name-only rebrand — the
      existing terracotta/cream identity carries over fine.

---

## Step 8 — Documentation

- [ ] Replace `README.md` with the full rewritten version already drafted
      during the review (covers: what it is, who it's for, build
      instructions, current unbuilt status, documentation map, and
      explicit limitations — personal use only, no custom extraction, no
      required paid services). Paste it in as-is; it's ready to commit.
- [ ] Add a `CJM.md` (or fold into `design.md`) capturing the Customer
      Journey Map produced during the review — five stages (prep at home
      → queue & download → departure/loses access → watch offline in-
      region → return & refresh library), with the explicit finding that
      the single highest-risk moment is the silent-failure window at
      home the night before a trip. This is the "why" behind Steps 3–4's
      priority ordering and is worth keeping as a living reference, not
      just a one-time review artifact.
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

- [ ] Persist download queue state (small local DB or file) so a process
      kill doesn't silently lose in-flight job status with zero UI
      indication — currently accepted debt (`DownloadQueueBus` is a bare
      in-memory `StateFlow`), reasonable for v1 but worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case the
      process was OOM-killed mid-download by an aggressive OEM battery
      manager (Xiaomi/Huawei/Samsung-class skins do this even to
      foreground services) — scan for leftover temp files from a
      previous run on service start, resume or clean them up.
- [ ] In-app delete for library entries (vs. relying on an external file
      manager) — partially addressed by the Step 6.5 overflow-menu
      pattern; make sure the actual delete logic (both the `MediaStore`
      row and file) gets implemented, not just the UI affordance.
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

## Appendix — Full findings traceability (all 4 review parts)

Every finding from the review, in one place, so nothing gets lost even if
the sections above get edited over time.

| # | Priority | Finding | Location | Status |
|---|---|---|---|---|
| 1 | Critical | Compose BOM version inconsistent with rest of toolchain | `app/build.gradle.kts` | Open — Step 1 |
| 2 | Critical | Race condition: job can be silently stranded at "Queued" | `DownloadService.kt` | Open — Step 3 |
| 3 | Critical | Unhandled exception types crash the whole app process | `DownloadService.kt` | Open — Step 3 |
| 4 | High | `execute()` progress callback possibly wrong lambda arity | `DownloadService.kt` | Open — Step 2 |
| 5 | High | Downloaded files/library entries have raw-UUID names | `DownloadService.kt`, `MediaStorage.kt` | Open — Step 4 |
| 6 | Medium | Output filename/extension assumption (narrower risk, `/b` fallback branch) | `QualityPresets.kt`, `DownloadService.kt` | Open — Step 4 |
| 7 | Medium | `RELATIVE_PATH` trailing-slash mismatch, insert vs. query | `MediaStorage.kt` | Open — Step 4 |
| 8 | Medium | `DownloadQueueBus` read-modify-write not atomic | `DownloadQueueBus.kt` | Open — Step 4 |
| 9 | Medium | No subfolder name sanitization | `Settings.kt` | Open — Step 4 |
| 10 | Medium | No host validation on shared/pasted URLs | `MainActivity.kt` | Open — Step 4 |
| 11 | Low | `youtubedl-android`/`ffmpeg` import paths | multiple files | Verify — Step 2 |
| 12 | Low | `updateYoutubeDL()` return type assumption | `YtDlpUpdater.kt` | Verify — Step 2 |
| 13 | Low | Unguarded `startActivity(ACTION_VIEW)` | `MainActivity.kt` | Open — Step 4 |
| 14 | Low | Dead `requestLegacyExternalStorage="true"` flag | `AndroidManifest.xml` | Open — Step 1 |
| 15 | Low | Adaptive icon tray clips outside safe zone on circular masks | `ic_launcher_foreground.xml` | Open — Step 6.6 |
| 16 | Low | Inconsistent Thread/Handler vs. coroutines style | `YtOfflineApp.kt`, `MainActivity.kt` | Open — Step 3 (partial), Backlog (rest) |
| 17 | Low | Only `app_name` externalized to `strings.xml` | `strings.xml` | Backlog |
| 18 | Low | Dead unreachable `else` branch in foreground-service start | `DownloadService.kt` | Open — Step 4 |
| 19 | Info | No monochrome adaptive-icon layer (Android 13+ themed icons) | resources | Backlog |
| 20 | Info | `dataSync` foreground service execution time budget on API 34+ | `DownloadService.kt` | Informational only |
| 21 | Info | `applicationId`/`namespace`/`rootProject.name` still say `ytoffline` | build files | Open — Step 7 |
| 22 | — | `sdkmanager` package identifiers | `.devcontainer/setup.sh` | ✅ Confirmed correct |
| 23 | — | `extractNativeLibs="true"` | `AndroidManifest.xml` | ✅ Confirmed correct, keep |
| 24 | — | AGP/Gradle/Kotlin toolchain compatibility | `app/build.gradle.kts` | ✅ Confirmed compatible |
| 25 | — | `isMinifyEnabled = false` on release | `app/build.gradle.kts` | ✅ Confirmed reasonable |
| 26 | — | Foreground service type declaration + runtime call | manifest + `DownloadService.kt` | ✅ Confirmed correct |
| 27 | — | `POST_NOTIFICATIONS` declared + runtime request | manifest + `MainActivity.kt` | ✅ Confirmed correct |
| 28 | — | `<queries>` manifest requirement | N/A | ✅ Confirmed not needed |
| 29 | — | `addOption` overload usage | `QualityPresets.kt` | ✅ Confirmed correct |
| 30 | — | `font_certs.xml` / `Theme.kt` cross-reference | resources | ✅ Confirmed correct |
| 31 | — | `versionCode` hardcoding | `app/build.gradle.kts` | ✅ Confirmed resolved (Phase 7) |
| 32 | — | English-only / no custom extractor / zero required cost / sideload-only | whole codebase | ✅ Confirmed `CLAUDE.md`-compliant |
| 33 | — | `friendlyError()` string matching against real yt-dlp output | `DownloadService.kt` | Needs device verification — Step 5 |
