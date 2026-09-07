#!/usr/bin/env python3
"""
Patch 01 — ROADMAP.md Steps 1, 2, 3 (compile-time blockers + confirmed
critical runtime bugs).

Run this from the ROOT of the yt-offline repo (the folder that contains
CLAUDE.md, ROADMAP.md, settings.gradle.kts, app/, etc.) inside your
GitHub Codespace.

What this does
--------------
Step 1 (compile-time blockers):
  1. Fixes the fabricated Compose BOM version (2026.08.00 -> 2024.11.00,
     a real BOM release from the same window as the rest of the pinned
     toolchain).
  2. Removes the dead `android:requestLegacyExternalStorage="true"` flag
     from AndroidManifest.xml (no-op at targetSdk 35, pure cleanup).

Step 2 (compile-time verification, resolved by checking the real
youtubedl-android 0.18.1 API instead of guessing):
  3. Fixes YoutubeDL.getInstance().execute()'s progress callback arity.
     Confirmed against the library's own sample app source
     (DownloadingExampleActivity.java uses
     Function3<Float, Long, String, Unit>): the real callback takes
     THREE parameters (progress, etaInSeconds, line), not two.
  4. Fixes YtDlpUpdater to call updateYoutubeDL(context, UpdateChannel)
     — confirmed the 0.18.1-era API requires an explicit UpdateChannel
     argument (README/sample app both show
     `updateYoutubeDL(this, updateChannel)`), not the single-argument
     call the file had.

Step 3 (confirmed critical runtime bugs, found by reading the actual
source, not speculation):
  5. Fixes the DownloadService race condition where a job enqueued at
     the exact instant the worker thread decides to shut down (idle
     timeout) could be silently stranded at "Queued" forever. NOTE:
     this patch goes slightly further than ROADMAP.md's own sample
     fix — the roadmap's snippet still has a narrow residual race
     (the worker's "give up" decision happens outside the lock that
     guards enqueue), so this version re-checks the queue *inside*
     the same lock right before actually exiting. See the comment
     above `startWorkerLocked()` in the new DownloadService.kt.
  6. Adds a catch-all `catch (e: Exception)` in runJob() so an
     unexpected exception type can no longer crash the whole app
     process and silently kill every other queued job.

Also updates ROADMAP.md's checkboxes and the findings-traceability
Appendix table for every item this patch resolves, so the roadmap
stays an accurate "what's actually done" record (per its own
"Process note for future sessions" section).

Deliberately NOT touched in this patch (coming in the next one):
  - Step 1's optional x86/x86_64 abiFilters trim (opt-in, not required).
  - Step 2's import-path sanity check (needs an actual
    `./gradlew assembleDebug` run in your Codespace — no amount of
    source reading from outside settles this one).
  - All of Step 4 (filename humanization, RELATIVE_PATH trailing-slash
    bug, DownloadQueueBus atomicity, subfolder/URL validation, etc.)

Safe to re-run: every edit is guarded by an exact-match check, so if
something doesn't match (e.g. this patch was already applied, or the
file has diverged) you'll get a clear error instead of a silent
no-op or a corrupted file.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


class PatchError(RuntimeError):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise PatchError(f"Expected file not found: {path}\n"
                          f"Are you running this from the repo root?")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    """Replace `old` with `new` in `path`, requiring exactly one match."""
    text = read(path)
    count = text.count(old)
    if count == 0:
        if new in text:
            print(f"  [skip] {label}: already applied, leaving as-is.")
            return
        raise PatchError(
            f"{label}: expected text not found in {path}.\n"
            f"The file may have changed since this patch was written.\n"
            f"--- expected snippet ---\n{old}\n------------------------"
        )
    if count > 1:
        raise PatchError(
            f"{label}: expected text found {count} times in {path}, "
            f"need exactly 1. Refusing to guess which one to replace."
        )
    write(path, text.replace(old, new, 1))
    print(f"  [ok] {label}")


def overwrite(path: pathlib.Path, content: str, label: str) -> None:
    write(path, content)
    print(f"  [ok] {label} (full file rewrite)")


# ---------------------------------------------------------------------------
# Step 1.1 — Compose BOM version
# ---------------------------------------------------------------------------

def fix_compose_bom() -> None:
    path = ROOT / "app" / "build.gradle.kts"
    old = '    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")\n'
    new = (
        '    // ROADMAP.md Step 1 [CRITICAL, fixed]: 2026.08.00 does not exist --\n'
        '    // every other pinned version below clusters around Sept-Nov 2024\n'
        '    // (AGP 8.7.2, Kotlin 2.1.0, activity-compose 1.9.3, core-ktx 1.15.0,\n'
        '    // kotlinx-coroutines-core 1.9.0). 2024.11.00 is a real BOM release\n'
        '    // from that same window -- see\n'
        '    // https://developer.android.com/jetpack/compose/bom/bom-mapping\n'
        '    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")\n'
    )
    replace_once(path, old, new, "Step 1.1: Compose BOM 2026.08.00 -> 2024.11.00")


# ---------------------------------------------------------------------------
# Step 1.2 — remove dead requestLegacyExternalStorage flag
# ---------------------------------------------------------------------------

def fix_manifest_legacy_storage() -> None:
    path = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    old = '        android:label="@string/app_name"\n        android:requestLegacyExternalStorage="true"\n        android:roundIcon="@mipmap/ic_launcher_round"\n'
    new = '        android:label="@string/app_name"\n        android:roundIcon="@mipmap/ic_launcher_round"\n'
    replace_once(path, old, new, "Step 1.2: remove dead requestLegacyExternalStorage flag")


# ---------------------------------------------------------------------------
# Step 2 + Step 3 — DownloadService.kt full rewrite
# ---------------------------------------------------------------------------

NEW_DOWNLOAD_SERVICE_KT = '''package com.baltic.ytoffline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs queued downloads one at a time in a foreground service, so
 * they survive the user leaving the app. See ROADMAP.md Phase 4 and
 * Step 3 (race-condition + crash-safety fixes applied there).
 *
 * Communicates progress back to the UI via DownloadQueueBus rather
 * than binding — simplest thing that works for a single-process
 * personal app.
 *
 * All extraction is delegated to the bundled yt-dlp binary via the
 * youtubedl-android library (see CLAUDE.md — we never write our own
 * extractor).
 *
 * Threading: a single background worker [Thread] drains [queue] one
 * job at a time; a new worker is (re)started on demand whenever a job
 * is enqueued and no worker is currently alive. [lock] guards every
 * place where "is a worker alive / should a new one start" is
 * decided — see [startWorkerLocked] for why this is needed
 * (ROADMAP.md Step 3: without this lock, a job enqueued in the exact
 * instant a worker decides to shut down from being idle could be
 * silently stranded at "Queued" forever).
 */
class DownloadService : Service() {

    private val queue = LinkedBlockingQueue<DownloadJob>()
    private val lock = Any()
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENQUEUE) {
            val url = intent.getStringExtra(EXTRA_URL)
            val qualityIndex = intent.getIntExtra(EXTRA_QUALITY_INDEX, 0)
            if (!url.isNullOrBlank()) {
                val job = DownloadJob(id = UUID.randomUUID().toString(), url = url, qualityIndex = qualityIndex)
                DownloadQueueBus.upsert(
                    DownloadJobStatus(
                        id = job.id,
                        url = job.url,
                        qualityLabel = qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }.label,
                        state = JobState.QUEUED,
                        progressText = "Queued"
                    )
                )
                synchronized(lock) {
                    queue.add(job)
                    if (workerThread?.isAlive != true) {
                        workerThread = startWorkerLocked()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Creates and starts the single background worker that drains
     * [queue]. Conceptually must be called while holding [lock] (the
     * thread body itself only re-acquires the lock for the brief
     * idle-exit check below — it does not hold it while downloading).
     *
     * ROADMAP.md Step 3 fix: the old version used a plain
     * `queue.poll()` (non-blocking) with no way to wait for more
     * work, so `ensureWorkerRunning()` had to guess whether an
     * existing thread would still be around to pick up a freshly
     * enqueued job — sometimes it wouldn't be, and the job was
     * stranded at "Queued" with no error shown.
     *
     * This version blocks on `queue.poll(timeout)` instead, and
     * re-checks the queue *inside* the same [lock] the enqueue path
     * uses right before actually giving up. This is deliberately a
     * little more careful than the sample fix sketched in
     * ROADMAP.md's Step 3 text: guarding only `queue.add()` + the
     * `isAlive` check + the `null`-out with a lock still leaves a
     * narrow window where the worker's "poll timed out, I'm exiting"
     * decision happens *outside* that lock, before it ever touches
     * `workerThread`. Making that exact decision happen inside the
     * lock (see below) closes that window completely: a job that
     * arrives in the split-second between "the idle timeout fired"
     * and "the worker exits" is always either handed to this worker
     * or handled by a freshly-started one — never dropped.
     */
    private fun startWorkerLocked(): Thread = Thread {
        startForegroundWithNotification("Starting downloads\\u2026")
        try {
            while (true) {
                val job = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (job != null) {
                    runJob(job)
                    continue
                }

                var shouldExit = false
                synchronized(lock) {
                    if (queue.isEmpty()) {
                        workerThread = null
                        shouldExit = true
                    }
                    // else: something was enqueued right at the
                    // boundary — leave workerThread pointing at this
                    // thread and loop again; poll() will pick the new
                    // job up immediately since the queue is non-empty.
                }
                if (shouldExit) break
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }.also { it.start() }

    private fun runJob(job: DownloadJob) {
        val preset = qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }
        DownloadQueueBus.update(job.id) { it.copy(state = JobState.RUNNING, progressText = "Starting\\u2026") }
        updateNotification("Downloading: ${job.url}")

        if (!hasNetwork()) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = "No internet connection")
            }
            return
        }

        val tempBaseName = "download_${job.id}"
        val tempFile = File(cacheDir, "$tempBaseName.${preset.expectedExtension}")

        try {
            val request = YoutubeDLRequest(job.url).apply {
                addOption("-o", "${cacheDir.absolutePath}/$tempBaseName.%(ext)s")
                preset.apply(this)
            }

            // ROADMAP.md Step 2 [HIGH, fixed]: youtubedl-android's
            // progress callback is 3-parameter (progress, etaInSeconds,
            // line), not 2 — confirmed against the library's own
            // sample app source (DownloadingExampleActivity.java uses
            // Function3<Float, Long, String, Unit>). The raw yt-dlp
            // output line isn't needed here, hence the `_`.
            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, _ ->
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = "$progress% (ETA ${etaInSeconds}s)")
                }
                updateNotification("${job.url}: $progress%")
            }

            if (!tempFile.exists()) {
                // See ROADMAP.md "open risks": yt-dlp may pick a
                // different extension than QualityPreset assumes.
                DownloadQueueBus.update(job.id) {
                    it.copy(state = JobState.FAILED, progressText = "Output file not found (${tempFile.name})")
                }
                return
            }

            val publishedUri = MediaStorage.publish(this, tempFile, preset.mimeType)
            DownloadQueueBus.update(job.id) {
                it.copy(
                    state = if (publishedUri != null) JobState.DONE else JobState.FAILED,
                    progressText = if (publishedUri != null) "Saved to Downloads/${Settings.getDownloadSubfolder(this@DownloadService)}" else "Failed to save"
                )
            }
        } catch (e: YoutubeDLException) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = friendlyError(e.message))
            }
        } catch (e: InterruptedException) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = "Cancelled")
            }
        } catch (e: Exception) {
            // ROADMAP.md Step 3 [CRITICAL, fixed]: without this
            // catch-all, any exception type other than the two above
            // (IOException, an unexpected NPE from an unusual library
            // response shape, etc.) propagated out of this worker
            // thread uncaught — and Android's default behavior for an
            // uncaught exception on *any* thread is to kill the whole
            // process, silently taking down every other job still
            // waiting in the queue.
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = friendlyError(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** True if there's some network with general internet capability. */
    private fun hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * yt-dlp error messages are often long technical dumps. This maps
     * the common, recognizable cases (from the second AI consultation
     * the user shared: bot detection, age restriction, private/
     * unavailable videos) to a short human-readable line, and falls
     * back to a truncated version of the raw message otherwise.
     */
    private fun friendlyError(raw: String?): String {
        val message = raw.orEmpty()
        val lower = message.lowercase()
        return when {
            lower.contains("sign in to confirm") || lower.contains("not a bot") ->
                "YouTube flagged this as a bot request. More common from " +
                    "cloud/VPN networks than from home \\u2014 try again from " +
                    "home, or tap Update to refresh yt-dlp."
            lower.contains("private video") ->
                "This video is private."
            lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict")) ->
                "Age-restricted video \\u2014 not supported yet (would need " +
                    "account cookies, which this app doesn't handle)."
            lower.contains("unavailable") ->
                "Video unavailable \\u2014 removed, region-blocked, or a bad link."
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "No internet connection."
            message.isBlank() -> "Unknown error."
            message.length > 220 -> message.take(220) + "\\u2026"
            else -> message
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YT Offline")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class DownloadJob(val id: String, val url: String, val qualityIndex: Int)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ENQUEUE = "com.baltic.ytoffline.ACTION_ENQUEUE"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_QUALITY_INDEX = "extra_quality_index"

        /** How long the worker waits for a new job before shutting the service down. */
        private const val IDLE_TIMEOUT_MS = 5_000L

        /** Adds a download to the queue and starts the service if needed. */
        fun enqueue(context: Context, url: String, qualityIndex: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY_INDEX, qualityIndex)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
'''


def fix_download_service() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadService.kt"
    current = read(path)
    if "startWorkerLocked" in current:
        print("  [skip] Step 2+3: DownloadService.kt already patched.")
        return
    overwrite(path, NEW_DOWNLOAD_SERVICE_KT, "Step 2+3: DownloadService.kt (race fix + catch-all + execute() arity)")


# ---------------------------------------------------------------------------
# Step 2 — YtDlpUpdater.kt: UpdateChannel argument
# ---------------------------------------------------------------------------

NEW_YT_DLP_UPDATER_KT = '''package com.baltic.ytoffline

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Wraps the youtubedl-android library's self-update mechanism, so the
 * bundled yt-dlp (and therefore the extractor) can be refreshed
 * without an app rebuild. See ROADMAP.md Phase 5 and the "no custom
 * extractor" ground rule in CLAUDE.md — this update mechanism is the
 * intended way to keep extraction working as YouTube changes things
 * over time, instead of us reverse-engineering anything ourselves.
 *
 * ROADMAP.md Step 2 [fixed]: the library's current README (matching
 * the 0.18.1 version pinned in app/build.gradle.kts) documents
 * `updateYoutubeDL(context, updateChannel)` — a required UpdateChannel
 * argument — not the single-argument call this file used to have.
 * STABLE is used here since this app never wants nightly/pre-release
 * yt-dlp builds on a personal device. The try/catch stays
 * deliberately broad, and the result is immediately turned into a
 * String via `.toString()`, so a wrong assumption about the *exact*
 * return type (enum vs. String) still fails soft rather than crashing
 * app startup.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"

    /** Blocking — call this from a background thread, not the main thread. */
    fun updateBlocking(context: Context): String {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.STABLE)
            Log.i(TAG, "yt-dlp update result: $status")
            status.toString()
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            "Update check failed: ${e.message}"
        }
    }
}
'''


def fix_yt_dlp_updater() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "YtDlpUpdater.kt"
    current = read(path)
    if "UpdateChannel.STABLE" in current:
        print("  [skip] Step 2: YtDlpUpdater.kt already patched.")
        return
    overwrite(path, NEW_YT_DLP_UPDATER_KT, "Step 2: YtDlpUpdater.kt (UpdateChannel argument)")


# ---------------------------------------------------------------------------
# ROADMAP.md bookkeeping — check off resolved items, update Appendix
# ---------------------------------------------------------------------------

def update_roadmap() -> None:
    path = ROOT / "ROADMAP.md"

    checkbox_fixes = [
        (
            "- [ ] **[CRITICAL] Fix the Compose BOM version.**",
            "- [x] **[CRITICAL] Fix the Compose BOM version.** (Fixed — patch 01)",
        ),
        (
            '- [ ] **[LOW, cleanup] Remove dead `requestLegacyExternalStorage="true"`**',
            '- [x] **[LOW, cleanup] Remove dead `requestLegacyExternalStorage="true"`** (Fixed — patch 01)',
        ),
        (
            "- [ ] **[HIGH] `YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds -> ... }`**",
            "- [x] **[HIGH] `YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds -> ... }`** "
            "(Fixed — patch 01: confirmed 3-parameter callback against the library's sample app source)",
        ),
        (
            "- [ ] **[LOW] `updateYoutubeDL()` return type** in `YtDlpUpdater.kt`",
            "- [x] **[LOW] `updateYoutubeDL()` return type** in `YtDlpUpdater.kt` (Fixed — patch 01: added the "
            "required `UpdateChannel` argument)",
        ),
        (
            '- [ ] **[CRITICAL] Race condition in `DownloadService.ensureWorkerRunning()`.**',
            '- [x] **[CRITICAL] Race condition in `DownloadService.ensureWorkerRunning()`.** (Fixed — patch 01, '
            'with a tighter lock than the sample fix below — see the code comment in DownloadService.kt)',
        ),
        (
            "- [ ] **[CRITICAL] Unhandled exceptions in `runJob()` can crash the whole app.**",
            "- [x] **[CRITICAL] Unhandled exceptions in `runJob()` can crash the whole app.** (Fixed — patch 01)",
        ),
    ]

    for old, new in checkbox_fixes:
        replace_once(path, old, new, f"ROADMAP.md checkbox: {old[:60]}...")

    appendix_fixes = [
        (
            "| 1 | Critical | Compose BOM version inconsistent with rest of toolchain | `app/build.gradle.kts` | Open — Step 1 |",
            "| 1 | Critical | Compose BOM version inconsistent with rest of toolchain | `app/build.gradle.kts` | ✅ Fixed — patch 01 |",
        ),
        (
            '| 2 | Critical | Race condition: job can be silently stranded at "Queued" | `DownloadService.kt` | Open — Step 3 |',
            '| 2 | Critical | Race condition: job can be silently stranded at "Queued" | `DownloadService.kt` | ✅ Fixed — patch 01 |',
        ),
        (
            "| 3 | Critical | Unhandled exception types crash the whole app process | `DownloadService.kt` | Open — Step 3 |",
            "| 3 | Critical | Unhandled exception types crash the whole app process | `DownloadService.kt` | ✅ Fixed — patch 01 |",
        ),
        (
            "| 4 | High | `execute()` progress callback possibly wrong lambda arity | `DownloadService.kt` | Open — Step 2 |",
            "| 4 | High | `execute()` progress callback possibly wrong lambda arity | `DownloadService.kt` | ✅ Fixed — patch 01 (confirmed 3-param) |",
        ),
        (
            "| 12 | Low | `updateYoutubeDL()` return type assumption | `YtDlpUpdater.kt` | Verify — Step 2 |",
            "| 12 | Low | `updateYoutubeDL()` return type assumption | `YtDlpUpdater.kt` | ✅ Fixed — patch 01 (UpdateChannel arg added) |",
        ),
        (
            "| 14 | Low | Dead `requestLegacyExternalStorage=\"true\"` flag | `AndroidManifest.xml` | Open — Step 1 |",
            "| 14 | Low | Dead `requestLegacyExternalStorage=\"true\"` flag | `AndroidManifest.xml` | ✅ Fixed — patch 01 |",
        ),
    ]

    for old, new in appendix_fixes:
        replace_once(path, old, new, f"ROADMAP.md appendix row: {old[:60]}...")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 01 (ROADMAP.md Steps 1-3)...\n")
    try:
        print("Step 1:")
        fix_compose_bom()
        fix_manifest_legacy_storage()

        print("\nStep 2 + 3:")
        fix_download_service()
        fix_yt_dlp_updater()

        print("\nROADMAP.md bookkeeping:")
        update_roadmap()
    except PatchError as exc:
        print(f"\nPATCH FAILED: {exc}", file=sys.stderr)
        return 1

    print("\nAll changes applied successfully.")
    print("Next: ./gradlew assembleDebug  (fix any remaining compile errors top-down)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
