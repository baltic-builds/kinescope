#!/usr/bin/env python3
"""
Patch 02 — ROADMAP.md Step 4 (product-quality fixes, cheap wins before
device testing).

Run this from the ROOT of the yt-offline repo, AFTER patch 01 has
already been applied and committed (this patch checks for and
requires patch 01's markers in DownloadService.kt).

What this does (all seven Step 4 checklist items):

1. [HIGH] Filename humanization. Downloaded files and Library entries
   used to be named after the raw job UUID. Now the yt-dlp output
   template itself asks for the real title (`%(title).150B`), with the
   job id kept as a bracketed tag purely so the resulting file can be
   found afterward; the tag is stripped back out before the name is
   shown in the Library.

2. [MEDIUM] The "find the output file" step now scans the cache dir
   for that job-id tag instead of assuming an exact
   `tempBaseName.expectedExtension` — needed both because the title
   makes an exact name unpredictable now, and because
   `--merge-output-format` can be bypassed by yt-dlp's `/b`
   format-selector fallback branch. Common yt-dlp leftover suffixes
   (`.part`, `.ytdl`, etc.) are excluded from the scan.

3. [MEDIUM] `MediaStorage`'s insert (`publish()`) and query
   (`listPublished()`) now use the identical trailing-slash
   `RELATIVE_PATH` string, instead of relying on MediaStore to
   normalize a missing trailing slash on insert the same way across
   every OEM.

4. [MEDIUM] `DownloadQueueBus.upsert()`/`update()` now use
   `MutableStateFlow.update {}` (atomic) instead of a plain
   read-then-write on `.value`, fixing a genuine race between the
   main/binder thread (enqueue) and the worker thread (progress
   ticks).

5. [MEDIUM] The user-editable Downloads subfolder name is sanitized in
   `Settings.kt` (path separators stripped, `.`/`..` rejected) before
   it can reach `MediaStore.RELATIVE_PATH`, on both read and write.

6. [MEDIUM] Shared/pasted URLs are now host-validated against
   youtube.com/youtu.be before being enqueued or auto-filled from a
   share intent, with inline field-level error feedback in the UI.

7. [LOW] `playItem()`'s `startActivity(ACTION_VIEW)` is now wrapped in
   a try/catch for `ActivityNotFoundException`, showing a toast
   instead of crashing.
   [LOW, cleanup] The dead unreachable `else` branch in
   `DownloadService.startForegroundWithNotification()` is removed
   (minSdk 29 already guarantees the typed branch's condition).

Also updates ROADMAP.md's checkboxes and the findings-traceability
Appendix table for every item this patch resolves.

Still NOT touched (later patches):
  - Step 2's import-path sanity check (needs an actual
    `./gradlew assembleDebug` run — no amount of source reading
    settles this one).
  - Step 1's optional x86/x86_64 abiFilters trim.
  - Steps 5 onward (device testing, design system v2, Kinescope
    rebrand, docs/release).

Safe to re-run: every edit is guarded by an exact-match check.
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


def require_patch_01() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadService.kt"
    text = read(path)
    if "startWorkerLocked" not in text:
        raise PatchError(
            "DownloadService.kt doesn't have patch 01's fixes yet "
            "(no 'startWorkerLocked' found). Run patch_01_steps_1_2_3.py "
            "first, in order."
        )


# ---------------------------------------------------------------------------
# QualityPresets.kt — full rewrite (drop now-unused expectedExtension)
# ---------------------------------------------------------------------------

NEW_QUALITY_PRESETS_KT = '''package com.baltic.ytoffline

import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * One yt-dlp option set per quality choice, plus the MIME type needed
 * to publish the result to MediaStore. yt-dlp itself decides the real
 * filename (including title and extension) only once it's running --
 * see DownloadService.runJob(), which locates the output file by its
 * embedded job-id tag afterward rather than assuming a fixed name
 * (ROADMAP.md Step 4), so an `expectedExtension` field here is no
 * longer needed.
 *
 * Lives in its own file (moved out of MainActivity in Phase 4) so
 * DownloadService can use the same list without duplicating it.
 */
data class QualityPreset(
    val label: String,
    val mimeType: String,
    val apply: YoutubeDLRequest.() -> Unit
)

val qualityPresets = listOf(
    QualityPreset("1080p", "video/mp4") {
        addOption("-f", "bv*[height<=1080]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("720p", "video/mp4") {
        addOption("-f", "bv*[height<=720]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("480p", "video/mp4") {
        addOption("-f", "bv*[height<=480]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("Audio only (MP3)", "audio/mpeg") {
        addOption("-x")
        addOption("--audio-format", "mp3")
    }
)
'''


def fix_quality_presets() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "QualityPresets.kt"
    current = read(path)
    if "val expectedExtension: String," not in current:
        print("  [skip] QualityPresets.kt: already patched.")
        return
    overwrite(path, NEW_QUALITY_PRESETS_KT, "QualityPresets.kt (drop unused expectedExtension field)")


# ---------------------------------------------------------------------------
# DownloadQueueBus.kt — full rewrite (atomic updates)
# ---------------------------------------------------------------------------

NEW_DOWNLOAD_QUEUE_BUS_KT = '''package com.baltic.ytoffline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class DownloadJobStatus(
    val id: String,
    val url: String,
    val qualityLabel: String,
    val state: JobState,
    val progressText: String
)

/**
 * In-process shared state between DownloadService (producer) and the
 * UI (consumer). No IPC/binding needed since both run in the same
 * process for this app — deliberately the simplest thing that works,
 * per CLAUDE.md.
 */
object DownloadQueueBus {
    private val _jobs = MutableStateFlow<List<DownloadJobStatus>>(emptyList())
    val jobs = _jobs.asStateFlow()

    // ROADMAP.md Step 4 [MEDIUM, fixed]: upsert() runs from the
    // main/binder thread (enqueue) and update() runs from the worker
    // thread (progress ticks) -- both used to do a plain
    // read-`_jobs.value`-then-write, which genuinely races between
    // those two threads. `MutableStateFlow.update {}` is an atomic
    // compare-and-set retry loop instead.
    fun upsert(status: DownloadJobStatus) {
        _jobs.update { current -> current.filterNot { it.id == status.id } + status }
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }
}
'''


def fix_download_queue_bus() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadQueueBus.kt"
    current = read(path)
    if "_jobs.update" in current:
        print("  [skip] DownloadQueueBus.kt: already patched.")
        return
    overwrite(path, NEW_DOWNLOAD_QUEUE_BUS_KT, "DownloadQueueBus.kt (atomic upsert/update)")


# ---------------------------------------------------------------------------
# Settings.kt — full rewrite (subfolder sanitization)
# ---------------------------------------------------------------------------

NEW_SETTINGS_KT = '''package com.baltic.ytoffline

import android.content.Context

/**
 * Thin wrapper around SharedPreferences for the few settings this
 * app has. Deliberately not using DataStore or anything fancier —
 * SharedPreferences is built into the platform (zero extra
 * dependency) and plenty for two values.
 */
object Settings {
    private const val PREFS_NAME = "yt_offline_settings"
    private const val KEY_DEFAULT_QUALITY = "default_quality_index"
    private const val KEY_DOWNLOAD_SUBFOLDER = "download_subfolder"
    const val DEFAULT_SUBFOLDER = "YTOffline"

    fun getDefaultQualityIndex(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_QUALITY, 0)

    fun setDefaultQualityIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_QUALITY, index).apply()
    }

    fun getDownloadSubfolder(context: Context): String =
        sanitizeSubfolder(prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER)

    fun setDownloadSubfolder(context: Context, name: String) {
        prefs(context).edit().putString(KEY_DOWNLOAD_SUBFOLDER, sanitizeSubfolder(name)).apply()
    }

    /**
     * ROADMAP.md Step 4 [MEDIUM, fixed]: this value flows straight
     * into `MediaStore.Downloads.RELATIVE_PATH` in MediaStorage.kt for
     * both insert and query, so it needs to behave like a single flat
     * folder name, not a path -- path separators are stripped
     * entirely (not just rejected), and the pathological "." / ".."
     * cases fall back to the default instead of being let through as
     * literal (harmless but confusing) folder names. Sanitizing on
     * both read and write means even a value stored before this fix
     * existed comes out clean.
     */
    private fun sanitizeSubfolder(name: String): String {
        val stripped = name.replace("/", "").replace("\\\\", "").trim()
        return if (stripped.isEmpty() || stripped == "." || stripped == "..") DEFAULT_SUBFOLDER else stripped
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
'''


def fix_settings() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "Settings.kt"
    current = read(path)
    if "sanitizeSubfolder" in current:
        print("  [skip] Settings.kt: already patched.")
        return
    overwrite(path, NEW_SETTINGS_KT, "Settings.kt (sanitize download subfolder name)")


# ---------------------------------------------------------------------------
# MediaStorage.kt — full rewrite (trailing slash + displayName param)
# ---------------------------------------------------------------------------

NEW_MEDIA_STORAGE_KT = '''package com.baltic.ytoffline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Everything related to getting a finished download out of the app's
 * private cache and into the public Downloads collection, so it
 * shows up in any file manager / gallery / media player — not just
 * inside this app.
 *
 * Requires minSdk 29 (MediaStore.Downloads didn't exist before
 * Android 10). minSdk was bumped in Phase 3 for exactly this reason
 * — see ROADMAP.md.
 */
object MediaStorage {

    /**
     * Copies [tempFile] into the public Downloads/&lt;subfolder&gt; folder
     * via MediaStore and deletes the temp copy. [subfolder] defaults to
     * whatever's saved in Settings (see Phase 6). [displayName] defaults
     * to the temp file's own name, but callers can override it — see
     * DownloadService.runJob(), which passes a humanized title instead
     * of the raw job-id-tagged temp filename (ROADMAP.md Step 4).
     * Returns the resulting content Uri, or null on failure.
     */
    fun publish(
        context: Context,
        tempFile: java.io.File,
        mimeType: String,
        subfolder: String = Settings.getDownloadSubfolder(context),
        displayName: String = tempFile.name
    ): Uri? {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // ROADMAP.md Step 4 [MEDIUM, fixed]: trailing slash must
            // match listPublished()'s query exactly below -- relying
            // on MediaStore to normalize a missing one on insert the
            // same way across every OEM is exactly the kind of
            // assumption this project has already been burned by
            // (see the Compose BOM fix in patch 01).
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val opened = resolver.openOutputStream(itemUri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
            if (opened == null) {
                resolver.delete(itemUri, null, null)
                return null
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            tempFile.delete()
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    /** Lists items this app has previously published, newest first. */
    fun listPublished(
        context: Context,
        subfolder: String = Settings.getDownloadSubfolder(context)
    ): List<LibraryItem> {
        val resolver = context.contentResolver
        val items = mutableListOf<LibraryItem>()

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                items += LibraryItem(
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "(untitled)",
                    mimeType = cursor.getString(mimeCol) ?: "*/*"
                )
            }
        }
        return items
    }
}

data class LibraryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
)
'''


def fix_media_storage() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MediaStorage.kt"
    current = read(path)
    if "displayName: String = tempFile.name" in current:
        print("  [skip] MediaStorage.kt: already patched.")
        return
    overwrite(path, NEW_MEDIA_STORAGE_KT, "MediaStorage.kt (trailing-slash fix + displayName param)")


# ---------------------------------------------------------------------------
# DownloadService.kt — full rewrite (builds on patch 01: filename
# humanization, job-id-tag file scan, dead-branch removal)
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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs queued downloads one at a time in a foreground service, so
 * they survive the user leaving the app. See ROADMAP.md Phase 4,
 * Step 3 (race-condition + crash-safety fixes) and Step 4 (filename
 * humanization + robust output-file lookup).
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

        // ROADMAP.md Step 4 [HIGH, fixed]: give downloads a
        // human-readable name instead of a raw UUID. yt-dlp fills in
        // the real title via its own `%(title)s` output-template
        // field; `jobIdTag` stays embedded in the temp filename
        // purely so the file can be found again afterward (yt-dlp's
        // own sanitizing/truncation of the title makes the exact
        // resulting filename hard to predict up front) -- it's
        // stripped back out below before publishing to the Library.
        val jobIdTag = "[${job.id}]"
        val outputTemplate = "${cacheDir.absolutePath}/%(title).150B $jobIdTag.%(ext)s"

        try {
            val request = YoutubeDLRequest(job.url).apply {
                addOption("-o", outputTemplate)
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

            // ROADMAP.md Step 4 [MEDIUM, fixed]: find the output by
            // its embedded job-id tag rather than assuming an exact
            // `tempBaseName.expectedExtension` -- the humanized title
            // above already makes an exact name unpredictable, and
            // `--merge-output-format` can also be bypassed by
            // yt-dlp's `/b` fallback format-selector branch (see
            // QualityPresets.kt) when a video has no separate
            // video+audio streams to merge. Common yt-dlp leftover
            // suffixes are excluded, and the most recently modified
            // match wins.
            val outputFile = cacheDir.listFiles { file ->
                file.name.contains(jobIdTag) && INTERMEDIATE_SUFFIXES.none { suffix -> file.name.endsWith(suffix) }
            }?.maxByOrNull { it.lastModified() }

            if (outputFile == null) {
                DownloadQueueBus.update(job.id) {
                    it.copy(state = JobState.FAILED, progressText = "Output file not found")
                }
                return
            }

            val displayName = outputFile.name.replace(" $jobIdTag", "").ifBlank { outputFile.name }

            val publishedUri = MediaStorage.publish(this, outputFile, preset.mimeType, displayName = displayName)
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

    // ROADMAP.md Step 4 [LOW, fixed]: the API-29-only fallback branch
    // this used to have was dead code -- minSdk is already 29
    // (Build.VERSION_CODES.Q), so the typed-foreground-service-type
    // call below is always reachable and the `Build.VERSION.SDK_INT`
    // check + fallback were never going to run.
    private fun startForegroundWithNotification(text: String) {
        startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

        /** yt-dlp leftovers to ignore when scanning for the finished output file. */
        private val INTERMEDIATE_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".ffmpeg")

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
    if "jobIdTag" in current:
        print("  [skip] DownloadService.kt: Step 4 changes already applied.")
        return
    overwrite(path, NEW_DOWNLOAD_SERVICE_KT,
              "DownloadService.kt (filename humanization + job-id-tag scan + dead branch removed)")


# ---------------------------------------------------------------------------
# MainActivity.kt — targeted edits (host validation, ActivityNotFoundException guard)
# ---------------------------------------------------------------------------

def fix_main_activity() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MainActivity.kt"

    # 1. Imports
    replace_once(
        path,
        "import android.Manifest\n"
        "import android.content.Context\n"
        "import android.content.Intent\n"
        "import android.os.Build\n"
        "import android.os.Bundle\n"
        "import android.os.Handler\n"
        "import android.os.Looper\n"
        "import androidx.activity.ComponentActivity\n",
        "import android.Manifest\n"
        "import android.content.ActivityNotFoundException\n"
        "import android.content.Context\n"
        "import android.content.Intent\n"
        "import android.net.Uri\n"
        "import android.os.Build\n"
        "import android.os.Bundle\n"
        "import android.os.Handler\n"
        "import android.os.Looper\n"
        "import android.widget.Toast\n"
        "import androidx.activity.ComponentActivity\n",
        "MainActivity.kt: imports (ActivityNotFoundException, Uri, Toast)",
    )

    # 2. extractUrl -> host-validated version + isYouTubeUrl/YOUTUBE_HOSTS
    replace_once(
        path,
        '/** Pulls the first http(s) URL out of arbitrary shared text. */\n'
        'private fun extractUrl(text: String): String? =\n'
        '    Regex("""https?://\\S+""").find(text)?.value\n',
        '// ROADMAP.md Step 4 [MEDIUM, fixed]: restrict to YouTube hosts, both\n'
        '// for shared text (below) and for whatever\'s typed/pasted directly\n'
        '// into the composer field (see DownloadScreen\'s onSend) -- yt-dlp\n'
        '// supports 1000+ sites, but this app\'s whole stated purpose is\n'
        '// YouTube-only offline downloads (see CLAUDE.md), so anything else is\n'
        '// scope creep worth rejecting at the UI layer rather than silently\n'
        '// attempting it.\n'
        'private val YOUTUBE_HOSTS = setOf(\n'
        '    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"\n'
        ')\n'
        '\n'
        'private fun isYouTubeUrl(url: String): Boolean =\n'
        '    runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() in YOUTUBE_HOSTS\n'
        '\n'
        '/** Pulls the first YouTube URL out of arbitrary shared text. */\n'
        'private fun extractUrl(text: String): String? =\n'
        '    Regex("""https?://\\S+""").findAll(text).map { it.value }.firstOrNull { isYouTubeUrl(it) }\n',
        "MainActivity.kt: extractUrl() host validation + isYouTubeUrl()",
    )

    # 3. DownloadScreen state: add urlError
    replace_once(
        path,
        '    var url by remember { mutableStateOf("") }\n'
        '    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }\n',
        '    var url by remember { mutableStateOf("") }\n'
        '    var urlError by remember { mutableStateOf<String?>(null) }\n'
        '    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }\n',
        "MainActivity.kt: add urlError state",
    )

    # 4. ComposerBar call site: validate before enqueue
    replace_once(
        path,
        '                ComposerBar(\n'
        '                    url = url,\n'
        '                    onUrlChange = { url = it },\n'
        '                    onSend = {\n'
        '                        DownloadService.enqueue(context, url, selectedQuality)\n'
        '                        url = ""\n'
        '                    },\n'
        '                    selectedQuality = selectedQuality,\n'
        '                    onQualitySelected = { selectedQuality = it }\n'
        '                )\n',
        '                ComposerBar(\n'
        '                    url = url,\n'
        '                    onUrlChange = { url = it; urlError = null },\n'
        '                    onSend = {\n'
        '                        val trimmed = url.trim()\n'
        '                        if (isYouTubeUrl(trimmed)) {\n'
        '                            DownloadService.enqueue(context, trimmed, selectedQuality)\n'
        '                            url = ""\n'
        '                            urlError = null\n'
        '                        } else {\n'
        '                            urlError = "Only youtube.com / youtu.be links are supported"\n'
        '                        }\n'
        '                    },\n'
        '                    selectedQuality = selectedQuality,\n'
        '                    onQualitySelected = { selectedQuality = it },\n'
        '                    errorText = urlError\n'
        '                )\n',
        "MainActivity.kt: validate host before enqueue, wire up urlError",
    )

    # 5. ComposerBar signature: add errorText param
    replace_once(
        path,
        '@Composable\n'
        'private fun ComposerBar(\n'
        '    url: String,\n'
        '    onUrlChange: (String) -> Unit,\n'
        '    onSend: () -> Unit,\n'
        '    selectedQuality: Int,\n'
        '    onQualitySelected: (Int) -> Unit\n'
        ') {\n',
        '@Composable\n'
        'private fun ComposerBar(\n'
        '    url: String,\n'
        '    onUrlChange: (String) -> Unit,\n'
        '    onSend: () -> Unit,\n'
        '    selectedQuality: Int,\n'
        '    onQualitySelected: (Int) -> Unit,\n'
        '    errorText: String? = null\n'
        ') {\n',
        "MainActivity.kt: ComposerBar() errorText param",
    )

    # 6. OutlinedTextField: show the error inline
    replace_once(
        path,
        '            OutlinedTextField(\n'
        '                value = url,\n'
        '                onValueChange = onUrlChange,\n'
        '                modifier = Modifier.fillMaxWidth(),\n'
        '                placeholder = { Text("Paste a YouTube link\\u2026") },\n'
        '                singleLine = true,\n'
        '                shape = MaterialTheme.shapes.extraLarge,\n',
        '            OutlinedTextField(\n'
        '                value = url,\n'
        '                onValueChange = onUrlChange,\n'
        '                modifier = Modifier.fillMaxWidth(),\n'
        '                placeholder = { Text("Paste a YouTube link\\u2026") },\n'
        '                singleLine = true,\n'
        '                isError = errorText != null,\n'
        '                supportingText = errorText?.let { { Text(it) } },\n'
        '                shape = MaterialTheme.shapes.extraLarge,\n',
        "MainActivity.kt: OutlinedTextField isError/supportingText",
    )

    # 7. playItem(): guard startActivity
    replace_once(
        path,
        'private fun playItem(context: Context, item: LibraryItem) {\n'
        '    val intent = Intent(Intent.ACTION_VIEW).apply {\n'
        '        setDataAndType(item.uri, item.mimeType)\n'
        '        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)\n'
        '        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n'
        '    }\n'
        '    context.startActivity(intent)\n'
        '}\n',
        'private fun playItem(context: Context, item: LibraryItem) {\n'
        '    val intent = Intent(Intent.ACTION_VIEW).apply {\n'
        '        setDataAndType(item.uri, item.mimeType)\n'
        '        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)\n'
        '        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n'
        '    }\n'
        '    // ROADMAP.md Step 4 [LOW, fixed]: guard against no video player\n'
        '    // being installed at all -- unlikely on a real phone, but cheap\n'
        '    // insurance against a crash for a one-line try/catch.\n'
        '    try {\n'
        '        context.startActivity(intent)\n'
        '    } catch (e: ActivityNotFoundException) {\n'
        '        Toast.makeText(context, "No app found to play this file", Toast.LENGTH_SHORT).show()\n'
        '    }\n'
        '}\n',
        "MainActivity.kt: playItem() ActivityNotFoundException guard",
    )


# ---------------------------------------------------------------------------
# ROADMAP.md bookkeeping
# ---------------------------------------------------------------------------

def update_roadmap() -> None:
    path = ROOT / "ROADMAP.md"

    checkbox_fixes = [
        (
            "- [ ] **[HIGH] Downloaded files and library entries have no human-readable name.**",
            "- [x] **[HIGH] Downloaded files and library entries have no human-readable name.** "
            "(Fixed — patch 02)",
        ),
        (
            '- [ ] **[MEDIUM] Keep the "scan cache dir for newest matching file" fallback**',
            '- [x] **[MEDIUM] Keep the "scan cache dir for newest matching file" fallback** (Fixed — patch 02)',
        ),
        (
            "- [ ] **[MEDIUM] Fix the `RELATIVE_PATH` trailing-slash mismatch** between",
            "- [x] **[MEDIUM] Fix the `RELATIVE_PATH` trailing-slash mismatch** (Fixed — patch 02) between",
        ),
        (
            "- [ ] **[MEDIUM] Make `DownloadQueueBus` updates atomic.**",
            "- [x] **[MEDIUM] Make `DownloadQueueBus` updates atomic.** (Fixed — patch 02)",
        ),
        (
            "- [ ] **[MEDIUM] Sanitize the user-editable Downloads subfolder name**",
            "- [x] **[MEDIUM] Sanitize the user-editable Downloads subfolder name** (Fixed — patch 02)",
        ),
        (
            "- [ ] **[MEDIUM] Host-validate shared/pasted URLs** in `MainActivity.kt`.",
            "- [x] **[MEDIUM] Host-validate shared/pasted URLs** in `MainActivity.kt`. (Fixed — patch 02)",
        ),
        (
            "- [ ] **[LOW] Guard `startActivity(ACTION_VIEW)`** in `MainActivity.kt`'s",
            "- [x] **[LOW] Guard `startActivity(ACTION_VIEW)`** (Fixed — patch 02) in `MainActivity.kt`'s",
        ),
        (
            "- [ ] **[LOW, cleanup] Remove the dead `else` branch** in",
            "- [x] **[LOW, cleanup] Remove the dead `else` branch** (Fixed — patch 02) in",
        ),
    ]

    for old, new in checkbox_fixes:
        replace_once(path, old, new, f"ROADMAP.md checkbox: {old[:60]}...")

    appendix_fixes = [
        (
            "| 5 | High | Downloaded files/library entries have raw-UUID names | `DownloadService.kt`, `MediaStorage.kt` | Open — Step 4 |",
            "| 5 | High | Downloaded files/library entries have raw-UUID names | `DownloadService.kt`, `MediaStorage.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 6 | Medium | Output filename/extension assumption (narrower risk, `/b` fallback branch) | `QualityPresets.kt`, `DownloadService.kt` | Open — Step 4 |",
            "| 6 | Medium | Output filename/extension assumption (narrower risk, `/b` fallback branch) | `QualityPresets.kt`, `DownloadService.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 7 | Medium | `RELATIVE_PATH` trailing-slash mismatch, insert vs. query | `MediaStorage.kt` | Open — Step 4 |",
            "| 7 | Medium | `RELATIVE_PATH` trailing-slash mismatch, insert vs. query | `MediaStorage.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 8 | Medium | `DownloadQueueBus` read-modify-write not atomic | `DownloadQueueBus.kt` | Open — Step 4 |",
            "| 8 | Medium | `DownloadQueueBus` read-modify-write not atomic | `DownloadQueueBus.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 9 | Medium | No subfolder name sanitization | `Settings.kt` | Open — Step 4 |",
            "| 9 | Medium | No subfolder name sanitization | `Settings.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 10 | Medium | No host validation on shared/pasted URLs | `MainActivity.kt` | Open — Step 4 |",
            "| 10 | Medium | No host validation on shared/pasted URLs | `MainActivity.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 13 | Low | Unguarded `startActivity(ACTION_VIEW)` | `MainActivity.kt` | Open — Step 4 |",
            "| 13 | Low | Unguarded `startActivity(ACTION_VIEW)` | `MainActivity.kt` | ✅ Fixed — patch 02 |",
        ),
        (
            "| 18 | Low | Dead unreachable `else` branch in foreground-service start | `DownloadService.kt` | Open — Step 4 |",
            "| 18 | Low | Dead unreachable `else` branch in foreground-service start | `DownloadService.kt` | ✅ Fixed — patch 02 |",
        ),
    ]

    for old, new in appendix_fixes:
        replace_once(path, old, new, f"ROADMAP.md appendix row: {old[:60]}...")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 02 (ROADMAP.md Step 4)...\n")
    try:
        require_patch_01()

        print("Kotlin source changes:")
        fix_quality_presets()
        fix_download_queue_bus()
        fix_settings()
        fix_media_storage()
        fix_download_service()
        fix_main_activity()

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
