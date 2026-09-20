package com.kinescope.app

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
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Serial foreground download queue. The single-worker model is kept
 * deliberately: it reduces YouTube request bursts and makes pause,
 * recovery, and temp-file continuation predictable.
 */
class DownloadService : Service() {

    private val queue = LinkedBlockingQueue<DownloadJob>()
    private val lock = Any()
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLog.i("DownloadService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> handleEnqueue(intent)
            ACTION_PAUSE -> handlePause(intent.getStringExtra(EXTRA_JOB_ID))
            ACTION_RESUME -> handleResume(intent.getStringExtra(EXTRA_JOB_ID))
            ACTION_STOP -> handleStop(intent.getStringExtra(EXTRA_JOB_ID))
        }
        return START_NOT_STICKY
    }

    private fun handleEnqueue(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        val qualityIndex = intent.getIntExtra(EXTRA_QUALITY_INDEX, 0)
        if (url.isNullOrBlank()) return

        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            url = url,
            qualityIndex = qualityIndex
        )
        jobsById[job.id] = job
        DownloadQueueBus.upsert(
            DownloadJobStatus(
                id = job.id,
                url = job.url,
                qualityLabel = getString(
                    qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }.labelRes
                ),
                state = JobState.QUEUED,
                progressText = getString(R.string.status_queued)
            )
        )
        AppLog.i("DownloadService", "Queued job=${job.id} qualityIndex=$qualityIndex url=$url")
        synchronized(lock) {
            queue.add(job)
            ensureWorkerLocked()
        }
    }

    private fun handlePause(id: String?) {
        if (id.isNullOrBlank()) return
        val job = jobsById[id] ?: return
        val status = DownloadQueueBus.find(id) ?: return
        when (status.state) {
            JobState.QUEUED -> {
                if (queue.remove(job)) {
                    DownloadQueueBus.update(id) {
                        it.copy(
                            state = JobState.PAUSED,
                            progressText = getString(R.string.status_paused),
                            progressFraction = null
                        )
                    }
                    AppLog.i("DownloadService", "Paused queued job=$id")
                } else {
                    // The worker may have polled the job between our
                    // status read and queue.remove(). Treat that as a
                    // running pause instead of silently losing the tap.
                    requestedControls[id] = ControlAction.PAUSE
                    YoutubeDL.getInstance().destroyProcessById(id)
                    AppLog.i("DownloadService", "Pause raced queue start for job=$id")
                }
            }
            JobState.RUNNING -> {
                requestedControls[id] = ControlAction.PAUSE
                DownloadQueueBus.update(id) { it.copy(progressText = getString(R.string.status_pausing)) }
                val destroyed = YoutubeDL.getInstance().destroyProcessById(id)
                AppLog.i("DownloadService", "Pause requested for running job=$id destroyedNow=$destroyed")
            }
            else -> Unit
        }
    }

    private fun handleResume(id: String?) {
        if (id.isNullOrBlank()) return
        val job = jobsById[id] ?: return
        if (DownloadQueueBus.find(id)?.state != JobState.PAUSED) return

        requestedControls.remove(id)
        DownloadQueueBus.update(id) {
            it.copy(
                state = JobState.QUEUED,
                progressText = getString(R.string.status_queued),
                progressFraction = null,
                failureKind = null
            )
        }
        synchronized(lock) {
            if (!queue.contains(job)) queue.add(job)
            ensureWorkerLocked()
        }
        AppLog.i("DownloadService", "Resumed job=$id")
    }

    private fun handleStop(id: String?) {
        if (id.isNullOrBlank()) return
        val job = jobsById[id] ?: return
        val state = DownloadQueueBus.find(id)?.state ?: return
        when (state) {
            JobState.RUNNING -> {
                requestedControls[id] = ControlAction.STOP
                DownloadQueueBus.update(id) { it.copy(progressText = getString(R.string.status_stopping)) }
                val destroyed = YoutubeDL.getInstance().destroyProcessById(id)
                AppLog.i("DownloadService", "Stop requested for running job=$id destroyedNow=$destroyed")
            }
            JobState.QUEUED -> {
                if (queue.remove(job)) {
                    finishControlled(job, ControlAction.STOP)
                } else {
                    // Same boundary as Pause: the worker may already
                    // own the job even though the UI still read QUEUED.
                    requestedControls[id] = ControlAction.STOP
                    YoutubeDL.getInstance().destroyProcessById(id)
                    AppLog.i("DownloadService", "Stop raced queue start for job=$id")
                }
            }
            JobState.PAUSED -> finishControlled(job, ControlAction.STOP)
            else -> Unit
        }
        stopIfIdle()
    }

    private fun ensureWorkerLocked() {
        if (workerThread?.isAlive != true) {
            workerThread = startWorkerLocked()
        }
    }

    private fun startWorkerLocked(): Thread = Thread {
        startForegroundWithNotification(getString(R.string.notification_starting))
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
                }
                if (shouldExit) break
            }
        } catch (e: InterruptedException) {
            AppLog.w("DownloadService", "Worker interrupted")
            Thread.currentThread().interrupt()
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            AppLog.i("DownloadService", "Worker stopped")
        }
    }.also { it.start() }

    private fun runJob(job: DownloadJob) {
        val preset = qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }
        val jobIdTag = "[${job.id}]"
        val outputTemplate = "${cacheDir.absolutePath}/%(title).150B $jobIdTag.%(ext)s"

        DownloadQueueBus.update(job.id) {
            it.copy(
                state = JobState.RUNNING,
                progressText = getString(R.string.status_starting),
                progressFraction = null,
                failureKind = null
            )
        }
        updateNotification(getString(R.string.notification_downloading))
        AppLog.i("DownloadService", "Starting job=${job.id}")

        if (!hasNetwork()) {
            failJob(job, FailureKind.NO_INTERNET, getString(R.string.error_no_internet))
            return
        }

        if (!ensureExtractorReady(job)) return
        if (YouTubeAuth.hasSavedSession(this)) {
            YouTubeAuth.refreshSavedSession(this)
        }

        var nightlyRefreshAttempted = false
        var lastError: String? = null
        val attempts = listOf(
            RecoveryProfile.DEFAULT,
            RecoveryProfile.DEFAULT_AFTER_REFRESH,
            RecoveryProfile.WEB_SAFARI_IPV4,
            RecoveryProfile.ANDROID_VR_LOGGED_OUT
        )

        for ((index, profile) in attempts.withIndex()) {
            requestedControls[job.id]?.let { action ->
                finishControlled(job, action)
                return
            }

            if (profile == RecoveryProfile.DEFAULT_AFTER_REFRESH && !nightlyRefreshAttempted) {
                nightlyRefreshAttempted = true
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_update))
                }
                AppLog.w("DownloadService", "Refreshing yt-dlp nightly before retry job=${job.id}")
                YtDlpUpdater.updateBlocking(this)
            }

            if (index > 0) {
                val delayMs = Random.nextLong(1_500L, 3_001L)
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_retry, index + 1, attempts.size))
                }
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failJob(job, FailureKind.OTHER, getString(R.string.error_cancelled))
                    return
                }
            }

            requestedControls[job.id]?.let { action ->
                finishControlled(job, action)
                return
            }

            try {
                executeAttempt(job, preset, outputTemplate, profile)
                requestedControls[job.id]?.let { action ->
                    finishControlled(job, action)
                    return
                }
                publishCompletedOutput(job, preset, jobIdTag)
                return
            } catch (e: YoutubeDL.CanceledException) {
                val action = requestedControls.remove(job.id) ?: ControlAction.STOP
                finishControlled(job, action)
                return
            } catch (e: YoutubeDLException) {
                lastError = e.message.orEmpty()
                val recoverable = isRecoverableYoutubeBlock(lastError)
                AppLog.e(
                    "DownloadService",
                    "Attempt ${index + 1}/${attempts.size} failed for job=${job.id} profile=$profile recoverable=$recoverable",
                    e
                )
                if (!recoverable || index == attempts.lastIndex) break
            } catch (e: Exception) {
                AppLog.e("DownloadService", "Unexpected job failure job=${job.id}", e)
                failJob(job, FailureKind.OTHER, friendlyError(e.message ?: e.javaClass.simpleName))
                return
            }
        }

        val failureKind = if (isRecoverableYoutubeBlock(lastError.orEmpty())) {
            FailureKind.YOUTUBE_VERIFICATION
        } else {
            FailureKind.OTHER
        }
        if (failureKind == FailureKind.YOUTUBE_VERIFICATION) {
            pauseForVerification(job, friendlyError(lastError))
        } else {
            failJob(job, failureKind, friendlyError(lastError))
        }
    }

    private fun executeAttempt(
        job: DownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile
    ) {
        val request = YoutubeDLRequest(job.url).apply {
            addOption("-o", outputTemplate)
            addOption("--continue")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            addOption("--extractor-retries", "3")
            addOption("--retry-sleep", "http:exp=1:8")
            addOption("--sleep-requests", "0.75")
            preset.apply(this)

            if (profile.useCookies && YouTubeAuth.hasSavedSession(this@DownloadService)) {
                addOption("--cookies", YouTubeAuth.cookieFile(this@DownloadService).absolutePath)
                YouTubeAuth.userAgent(this@DownloadService)?.let { userAgent ->
                    addOption("--add-header", "User-Agent:$userAgent")
                }
            }
            profile.extractorArgs?.let { addOption("--extractor-args", it) }
            if (profile.forceIpv4) addOption("--force-ipv4")
        }

        AppLog.i(
            "DownloadService",
            "Executing job=${job.id} profile=$profile cookies=${profile.useCookies && YouTubeAuth.hasSavedSession(this)}"
        )
        YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, _ ->
            if (requestedControls[job.id] != null) {
                YoutubeDL.getInstance().destroyProcessById(job.id)
            } else {
                DownloadQueueBus.update(job.id) {
                    it.copy(
                        progressText = getString(R.string.progress_percent_eta, progress, etaInSeconds),
                        progressFraction = (progress / 100f).coerceIn(0f, 1f)
                    )
                }
                updateNotification(getString(R.string.notification_progress, progress.toInt()))
            }
        }
    }

    private fun publishCompletedOutput(job: DownloadJob, preset: QualityPreset, jobIdTag: String) {
        val outputFile = cacheDir.listFiles { file ->
            file.name.contains(jobIdTag) && INTERMEDIATE_SUFFIXES.none { suffix -> file.name.endsWith(suffix) }
        }?.maxByOrNull { it.lastModified() }

        if (outputFile == null) {
            failJob(job, FailureKind.OTHER, getString(R.string.error_output_not_found))
            return
        }

        val displayName = outputFile.name.replace(" $jobIdTag", "").ifBlank { outputFile.name }
        val publishedUri = MediaStorage.publish(this, outputFile, preset.mimeType, displayName = displayName)
        if (publishedUri != null) {
            jobsById.remove(job.id)
            requestedControls.remove(job.id)
            DownloadQueueBus.update(job.id) {
                it.copy(
                    state = JobState.DONE,
                    progressText = getString(R.string.status_saved_to, Settings.getDownloadSubfolder(this)),
                    progressFraction = null,
                    failureKind = null
                )
            }
            AppLog.i("DownloadService", "Completed job=${job.id} uri=$publishedUri")
        } else {
            failJob(job, FailureKind.OTHER, getString(R.string.error_failed_to_save))
        }
    }

    private fun finishControlled(job: DownloadJob, action: ControlAction) {
        requestedControls.remove(job.id)
        when (action) {
            ControlAction.PAUSE -> {
                DownloadQueueBus.update(job.id) {
                    it.copy(
                        state = JobState.PAUSED,
                        progressText = getString(R.string.status_paused),
                        progressFraction = null
                    )
                }
                AppLog.i("DownloadService", "Paused running job=${job.id}; partial files kept for resume")
            }
            ControlAction.STOP -> {
                cleanupTempFiles(job.id)
                jobsById.remove(job.id)
                DownloadQueueBus.update(job.id) {
                    it.copy(
                        state = JobState.STOPPED,
                        progressText = getString(R.string.status_stopped),
                        progressFraction = null
                    )
                }
                AppLog.i("DownloadService", "Stopped running job=${job.id}; temp files removed")
            }
        }
    }

    /**
     * A YouTube verification block is recoverable by design: keep the
     * job and its partial files so signing in (or simply retrying later
     * on a different network state) can resume the same download instead
     * of turning a transient YouTube decision into a terminal failure.
     */
    private fun pauseForVerification(job: DownloadJob, message: String) {
        requestedControls.remove(job.id)
        DownloadQueueBus.update(job.id) {
            it.copy(
                state = JobState.PAUSED,
                progressText = message,
                progressFraction = null,
                failureKind = FailureKind.YOUTUBE_VERIFICATION
            )
        }
        AppLog.w("DownloadService", "Job=${job.id} paused for YouTube verification; resumable")
    }

    private fun failJob(job: DownloadJob, kind: FailureKind, message: String) {
        jobsById.remove(job.id)
        requestedControls.remove(job.id)
        DownloadQueueBus.update(job.id) {
            it.copy(
                state = JobState.FAILED,
                progressText = message,
                progressFraction = null,
                failureKind = kind
            )
        }
        AppLog.e("DownloadService", "Job=${job.id} failed kind=$kind message=$message")
    }

    private fun cleanupTempFiles(jobId: String) {
        val tag = "[$jobId]"
        cacheDir.listFiles()?.filter { it.name.contains(tag) }?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun isRecoverableYoutubeBlock(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.contains("sign in to confirm") ||
            lower.contains("not a bot") ||
            lower.contains("http error 403") ||
            lower.contains("http error 429") ||
            lower.contains("too many requests") ||
            lower.contains("requested format is not available")
    }

    private fun ensureExtractorReady(job: DownloadJob): Boolean {
        return try {
            // Application initializes these in the background for fast
            // startup; doing the same idempotent initialization here
            // guarantees a very fast first tap cannot race that thread.
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            true
        } catch (e: Exception) {
            AppLog.e("DownloadService", "Extractor initialization failed for job=${job.id}", e)
            failJob(job, FailureKind.OTHER, friendlyError(e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun friendlyError(raw: String?): String {
        val message = raw.orEmpty()
        val lower = message.lowercase()
        return when {
            lower.contains("sign in to confirm") || lower.contains("not a bot") ||
                lower.contains("http error 403") || lower.contains("http error 429") ->
                getString(R.string.error_youtube_verification)
            lower.contains("private video") -> getString(R.string.error_private_video)
            lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict")) ->
                getString(R.string.error_age_restricted)
            lower.contains("unavailable") -> getString(R.string.error_video_unavailable)
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                getString(R.string.error_no_internet)
            message.isBlank() -> getString(R.string.error_unknown)
            else -> getString(R.string.error_download_generic, message.take(220))
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startForegroundWithNotification(text: String) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            if (queue.isEmpty() && workerThread?.isAlive != true) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class DownloadJob(val id: String, val url: String, val qualityIndex: Int)
    private enum class ControlAction { PAUSE, STOP }
    private enum class RecoveryProfile(
        val extractorArgs: String?,
        val forceIpv4: Boolean,
        val useCookies: Boolean
    ) {
        DEFAULT(null, false, true),
        DEFAULT_AFTER_REFRESH(null, false, true),
        WEB_SAFARI_IPV4("youtube:player_client=web_safari", true, true),
        ANDROID_VR_LOGGED_OUT("youtube:player_client=android_vr", false, false)
    }

    companion object {
        private val jobsById = ConcurrentHashMap<String, DownloadJob>()
        private val requestedControls = ConcurrentHashMap<String, ControlAction>()

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ENQUEUE = "com.kinescope.app.ACTION_ENQUEUE"
        private const val ACTION_PAUSE = "com.kinescope.app.ACTION_PAUSE"
        private const val ACTION_RESUME = "com.kinescope.app.ACTION_RESUME"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_QUALITY_INDEX = "extra_quality_index"
        private const val EXTRA_JOB_ID = "extra_job_id"
        private const val IDLE_TIMEOUT_MS = 5_000L
        private val INTERMEDIATE_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".ffmpeg")

        fun enqueue(context: Context, url: String, qualityIndex: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY_INDEX, qualityIndex)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, id: String) = sendControl(context, ACTION_PAUSE, id, foreground = false)
        fun stop(context: Context, id: String) = sendControl(context, ACTION_STOP, id, foreground = false)
        fun resume(context: Context, id: String) = sendControl(context, ACTION_RESUME, id, foreground = true)

        private fun sendControl(context: Context, actionName: String, id: String, foreground: Boolean) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = actionName
                putExtra(EXTRA_JOB_ID, id)
            }
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
