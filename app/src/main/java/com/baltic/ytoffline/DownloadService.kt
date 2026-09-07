package com.baltic.ytoffline

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
        startForegroundWithNotification("Starting downloads\u2026")
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
        DownloadQueueBus.update(job.id) { it.copy(state = JobState.RUNNING, progressText = "Starting\u2026") }
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
                    "cloud/VPN networks than from home \u2014 try again from " +
                    "home, or tap Update to refresh yt-dlp."
            lower.contains("private video") ->
                "This video is private."
            lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict")) ->
                "Age-restricted video \u2014 not supported yet (would need " +
                    "account cookies, which this app doesn't handle)."
            lower.contains("unavailable") ->
                "Video unavailable \u2014 removed, region-blocked, or a bad link."
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "No internet connection."
            message.isBlank() -> "Unknown error."
            message.length > 220 -> message.take(220) + "\u2026"
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
