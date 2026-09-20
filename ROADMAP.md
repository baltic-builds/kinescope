# Roadmap

**Current state (as of patch 23): the original 9-step implementation
plan is done, or reduced to the specific technical debt below.** Steps
1–9 — compile fixes, critical runtime fixes, product-quality fixes,
device install/testing, design system v2, the Kinescope rename,
documentation, and signed release — are all complete or closed out to
named items in "Technical debt" below. `./gradlew assembleDebug`
succeeds locally and via CI; a signed release build succeeds via CI;
**the app is confirmed working on a real device** (Android 13):
install, permissions, share/paste a link, queue and complete a
download, play it back, background persistence. For the full
step-by-step history of how it got here — including two dead ends
that turned out to matter (a fabricated Compose BOM version, a
plausible-but-wrong `UpdateChannel` location) — see `CHANGELOG.md`
(one entry per patch) and `HANDOFF.md` (the fuller narrative + key
learnings). **This file now tracks only what's left open, not what
already shipped.**

**Decided, unchanged:** `applicationId`/`namespace` is
`com.kinescope.app`. No Google Play distribution — sideload only. No
custom YouTube extraction — everything goes through `yt-dlp` via
`youtubedl-android`. See `CLAUDE.md` for the full ground rules.

---

## Technical debt

Nothing below blocks normal use of the app. Roughly ordered by how
much it'd actually matter if it bit you.

### Not yet individually confirmed on a real device

Basic functionality is confirmed working (see above), but these more
adversarial checks from the original Step 5 checklist haven't been
individually gone through and reported back yet. "The app works"
means the core loop works, not that these specific edge cases have
been exercised:

- [ ] **Race-condition stress test:** queue 3–4 videos in quick
      succession (within a couple seconds of each other, the realistic
      "prepping for a trip" pattern) and confirm every single one
      actually starts and completes. This is the exact scenario
      `DownloadService`'s worker-restart fix (patch 01) was written
      for.
- [ ] **`friendlyError()` against a real broken video:** try an
      age-restricted or private video and confirm the error-text
      matching (e.g. "Sign in to confirm you're not a bot" for
      bot-detection) still matches current yt-dlp output. Flagged
      since the original review as "needs a real device to settle,"
      and still does.
- [ ] **Airplane mode at queue time:** confirm the app degrades
      gracefully rather than crashing (doubles as a regression check
      for the Step 3 catch-all exception handler).
- [ ] **A very large/slow download:** confirm it doesn't get killed
      mid-transfer. If it ever does on Android 14+ specifically, note
      that `dataSync`-type foreground services have a rolling
      execution-time budget (hours/day, not indefinite) —
      informational, unlikely to matter for typical video lengths.

### Signed release

- [ ] Confirm the **signed** release APK (not just the debug build)
      actually installs and opens on a real device. CI produces it
      successfully (`.github/workflows/build-release.yml`, confirmed
      patch 21); this is the one step that needs a device, not just a
      green Actions run.

### Optional backlog (unscheduled, user-prioritized)

- [ ] Persist download queue state (small local DB or file) so a
      process kill doesn't silently lose in-flight job status with
      zero UI indication — `DownloadQueueBus` is a bare in-memory
      `StateFlow` today; reasonable for v1, worth revisiting.
- [ ] Orphaned temp-file cleanup on `DownloadService` startup, in case
      an aggressive OEM battery manager (Xiaomi/Huawei/Samsung-class
      skins do this even to foreground services) OOM-kills the process
      mid-download.
- [ ] Add a `<monochrome>` adaptive icon layer for Android 13+ themed
      icons (Material You tinting support) — purely cosmetic; the icon
      just won't participate in themed-icon tinting without it.
- [ ] Migrate `collectAsState()` to `collectAsStateWithLifecycle()` in
      `MainActivity.kt` — fine as-is for a single-screen app, revisit
      only if a second screen (e.g. a dedicated Library screen) is
      added.
- [ ] Migrate remaining raw `Thread`/`Handler(Looper.getMainLooper())`
      usage (`YtOfflineApp.kt`, `MainActivity.kt`'s `runUpdate()`) to
      `rememberCoroutineScope()` + `withContext(Dispatchers.IO)`, for
      consistency with the coroutines-based fix already applied to
      `DownloadService` in Step 3.
- [ ] Externalize remaining hardcoded UI strings ("Queue", "Library",
      `friendlyError()` messages, Settings labels) into `strings.xml`
      — zero functional impact for a personal single-language app,
      purely a "nice to have if you're already touching that code."
- [ ] Batch-queue a full playlist by URL, if that becomes a real use
      case.
- [ ] Self-hosted backend for cross-device queue sync — explicitly
      optional per `CLAUDE.md`'s zero-required-cost rule, never a
      requirement.
- [ ] Note for future Codespace rebuilds: `.devcontainer/setup.sh`
      scrapes the Android cmdline-tools download URL from a live
      webpage rather than a pinned version — fails safely (a loud
      error with instructions) but means a rebuilt Codespace could
      silently pick up a newer cmdline-tools version than the original
      build did. If a *rebuilt* Codespace ever behaves differently
      than the original for no apparent code reason, check this
      script's output first.

In-app delete for library entries is fully done (patch 04) — not
debt, just noted here since it used to live in this list.

---

## After this file: `roadmap.md` (lowercase)

A separate, newer sprint-based audit/plan (S0–S11, findings F01–F42)
from GPT Astra, added by the user. Deliberately queued for **after**
every item above is closed — do not merge it into this document or
start it early. Once both this `ROADMAP.md` and `roadmap.md` are fully
executed, both files get deleted.

---

## Process note (still true)

The original build-verify-after-every-phase discipline in `CLAUDE.md`
was intentionally overridden by explicit user instruction during
initial development ("keep going, test everything at the end"). That
was a valid call for a solo prototyping burst, but it's also *why*
this document's now-closed Steps 1–4 existed at all — nearly every
Critical/High finding in this project's early history was a direct
consequence of code that was never compiled, let alone run. Going
forward: **prefer compiling (and, where practical, running) after
each meaningful change**, not just at the end of a long unattended
session. See `AGENTS.md` for the fuller process this project follows.
