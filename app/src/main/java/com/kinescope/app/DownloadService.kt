package com.kinescope.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Durable single-worker foreground queue. Accepted jobs are journaled before
 * the service is started; process death therefore becomes an explicit
 * INTERRUPTED state instead of a silently lost download.
 *
 * This class owns the Android service lifecycle, the command handlers, the worker thread and the order of
 * one job's steps ([runJob]). What each step does lives in its own class: [DownloadNotifier],
 * [JobLifecycle], [RecoveryChain] -> [RouteLadder] -> [YtDlpRun], and [DownloadPublisher].
 */
class DownloadService : Service() {
    private val queue = LinkedBlockingQueue<String>()
    private val lock = Any()
    private var workerThread: Thread? = null

    @Volatile
    private var activeJobId: String? = null

    @Volatile
    private var latestStartId = 0

    // Patch 40: collaborators, each a ContextWrapper around this service.
    private val notifier = DownloadNotifier(this)
    private val lifecycle = JobLifecycle(this)
    private val ytDlp = YtDlpRun(this, notifier)
    private val ladder = RouteLadder(this, ytDlp)
    private val recovery = RecoveryChain(this, ladder, lifecycle, notifier)
    private val publisher = DownloadPublisher(this, lifecycle, notifier)

    override fun onCreate() {
        super.onCreate()
        notifier.createChannel()
        AppLog.i("DownloadService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        if (action == ACTION_ENQUEUE || action == ACTION_RESUME) {
            // startForegroundService callers require this promptly, before any
            // disk/network work or worker startup.
            notifier.startForeground(getString(R.string.notification_starting), activeJobId)
        }

        when (action) {
            ACTION_ENQUEUE -> handleEnqueue(intent.getStringExtra(EXTRA_JOB_ID))
            ACTION_PAUSE -> handlePause(intent.getStringExtra(EXTRA_JOB_ID))
            ACTION_RESUME -> handleResume(intent.getStringExtra(EXTRA_JOB_ID))
            ACTION_STOP -> handleStop(intent.getStringExtra(EXTRA_JOB_ID))
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun handleEnqueue(id: String?) {
        if (id.isNullOrBlank()) {
            stopIfIdle()
            return
        }
        val job = DownloadJobStore.find(this, id)
        if (job == null || job.state in TERMINAL_STATES) {
            AppLog.w("DownloadService", "Ignoring enqueue for missing/terminal job=$id")
            stopIfIdle()
            return
        }
        synchronized(lock) {
            if (!queue.contains(id) && activeJobId != id) queue.add(id)
            ensureWorkerLocked()
        }
        AppLog.i("DownloadService", "Accepted queued job=$id quality=${job.qualityId}")
    }

    private fun handlePause(id: String?) {
        if (id.isNullOrBlank()) return
        val job = DownloadJobStore.find(this, id) ?: return
        when (job.state) {
            JobState.QUEUED -> {
                if (queue.remove(id)) {
                    lifecycle.transition(job, JobState.PAUSED, getString(R.string.status_paused))
                    AppLog.i("DownloadService", "Paused queued job=$id")
                } else {
                    JobControls.requested[id] = ControlAction.PAUSE
                    YoutubeDL.getInstance().destroyProcessById(id)
                    AppLog.i("DownloadService", "Pause raced queue start for job=$id")
                }
            }
            JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING -> {
                JobControls.requested[id] = ControlAction.PAUSE
                DownloadQueueBus.update(id) { it.copy(progressText = getString(R.string.status_pausing)) }
                val destroyed = YoutubeDL.getInstance().destroyProcessById(id)
                AppLog.i("DownloadService", "Pause requested job=$id destroyedNow=$destroyed")
            }
            else -> Unit
        }
    }

    private fun handleResume(id: String?) {
        if (id.isNullOrBlank()) {
            stopIfIdle()
            return
        }
        val job = DownloadJobStore.find(this, id)
        if (job == null || job.state !in RETRYABLE_STATES) {
            stopIfIdle()
            return
        }

        DownloadJobStore.pendingUri(job)?.let { MediaStorage.deletePending(this, it) }
        JobControls.requested.remove(id)
        val queued = lifecycle.transition(
            job.copy(pendingUri = null),
            JobState.QUEUED,
            getString(R.string.status_queued),
            failureKind = null
        )
        synchronized(lock) {
            if (!queue.contains(id) && activeJobId != id) queue.add(id)
            ensureWorkerLocked()
        }
        AppLog.i("DownloadService", "Retry/resume queued job=${queued.id}")
    }

    private fun handleStop(id: String?) {
        if (id.isNullOrBlank()) return
        val job = DownloadJobStore.find(this, id) ?: return
        if (job.state in TERMINAL_STATES) return

        if (activeJobId == id) {
            JobControls.requested[id] = ControlAction.STOP
            DownloadQueueBus.update(id) { it.copy(progressText = getString(R.string.status_stopping)) }
            val destroyed = YoutubeDL.getInstance().destroyProcessById(id)
            AppLog.i("DownloadService", "Stop requested job=$id destroyedNow=$destroyed")
        } else {
            queue.remove(id)
            lifecycle.finishControlled(job, ControlAction.STOP)
        }
        stopIfIdle()
    }

    private fun ensureWorkerLocked() {
        if (workerThread?.isAlive != true) workerThread = startWorkerLocked()
    }

    private fun startWorkerLocked(): Thread = Thread({
        try {
            while (true) {
                val id = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (id != null) {
                    val job = DownloadJobStore.find(this, id)
                    if (job != null && job.state == JobState.QUEUED) {
                        activeJobId = id
                        runJob(job)
                        activeJobId = null
                    }
                    continue
                }

                val shouldExit = synchronized(lock) { queue.isEmpty() }
                if (shouldExit) break
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.w("DownloadService", "Worker interrupted")
        } finally {
            activeJobId = null
            val (stopStartId, shouldAttemptStop) = synchronized(lock) {
                // Do not null-out workerThread until the old worker is actually
                // exiting. If a new job arrived after the idle poll timed out,
                // enqueue() still sees this worker as alive; start its successor
                // here instead of letting the old worker's stopSelf() kill a
                // freshly-started worker/service instance.
                if (workerThread === Thread.currentThread()) workerThread = null
                if (queue.isNotEmpty() && workerThread?.isAlive != true) {
                    workerThread = startWorkerLocked()
                }
                latestStartId to (queue.isEmpty() && workerThread?.isAlive != true)
            }

            // stopSelfResult() protects against a newer onStartCommand racing
            // this idle shutdown. A newer startId makes this return false, so
            // we must not tear down the foreground notification under it.
            if (shouldAttemptStop && stopSelfResult(stopStartId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                AppLog.i("DownloadService", "Worker stopped; service idle")
            } else {
                AppLog.i("DownloadService", "Worker handed off without stopping service")
            }
        }
    }, "kinescope-download-worker").also { it.start() }

    private fun runJob(initialJob: StoredDownloadJob) {
        var job = initialJob
        // A Pause/Stop can race exactly between queue.poll() and this method.
        // Consume and honor that control before touching the network instead
        // of clearing it and accidentally starting the download anyway.
        JobControls.requested.remove(job.id)?.let {
            lifecycle.finishControlled(job, it)
            return
        }

        DownloadJobStore.pendingUri(job)?.let {
            MediaStorage.deletePending(this, it)
            job = DownloadJobStore.update(this, job.id) { current -> current.copy(pendingUri = null) } ?: job
        }

        job = lifecycle.transition(job, JobState.PREPARING, getString(R.string.status_preparing), failureKind = null)
        notifier.update(getString(R.string.status_preparing), job.id)

        if (!hasNetwork()) {
            lifecycle.interruptJob(job, FailureKind.NO_INTERNET, getString(R.string.error_no_internet))
            return
        }

        // Patch 37: the site comes from the canonical URL. Only the YouTube session is re-captured
        // from the WebView before a run; the Instagram cookie file is rewritten by yt-dlp itself
        // when Instagram rotates a cookie, and a re-capture would overwrite that with older values.
        val source = job.mediaSource
        if (source == MediaSource.YOUTUBE && YouTubeAuth.hasSavedSession(this)) {
            YouTubeAuth.refreshSavedSession(this)
        }
        if (source == MediaSource.INSTAGRAM && InstagramShareResolver.isShareUrl(job.canonicalUrl)) {
            job = resolveInstagramShare(job)
        }

        val workspace = DownloadJobStore.workspaceDir(this, job.id)
        if (!workspace.exists() && !workspace.mkdirs()) {
            lifecycle.failJob(job, FailureKind.STORAGE, getString(R.string.error_workspace_create))
            return
        }
        // Reels usually have no real title (a caption or "Video by <user>"), so name the file after
        // the uploader and the post id instead; the id also keeps two Reels from colliding.
        val outputTemplate = "${workspace.absolutePath}/" + if (source == MediaSource.INSTAGRAM) {
            "%(uploader|Instagram).60B - %(id)s.%(ext)s"
        } else {
            "%(title).150B.%(ext)s"
        }
        val preset = qualityPreset(job.qualityId)

        // Patch 35: a strategy search steps aside between two strategies while a download runs.
        DpiSearchController.holdForDownload()
        try {
            ladder.awaitStrategySearch(job)
            EngineController.withEngine(this) {
                recovery.executeWithRecovery(job, preset, outputTemplate)
            }
        } catch (controlled: ControlledStop) {
            lifecycle.finishControlled(job, controlled.action)
            return
        } catch (_: AlreadyHandledFailure) {
            return
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lifecycle.interruptJob(job, FailureKind.CANCELLED, getString(R.string.status_interrupted))
            return
        } catch (e: Exception) {
            AppLog.e("DownloadService", "Engine execution failed job=${job.id}", e)
            lifecycle.failJob(job, FailureKind.ENGINE, friendlyDownloadError(e.message))
            return
        } finally {
            // Patch 39: the hold above was never released, so after the first download of a process
            // every strategy search that started later waited for ever between two strategies.
            DpiSearchController.releaseDownload()
        }

        JobControls.requested.remove(job.id)?.let {
            lifecycle.finishControlled(job, it)
            return
        }

        val outputFile = publisher.findFinalOutput(workspace)
        if (outputFile == null) {
            lifecycle.failJob(job, FailureKind.OTHER, getString(R.string.error_output_not_found))
            return
        }

        publisher.publishCompletedOutput(job, preset, outputFile)
    }

    /**
     * Patch 37: `instagram.com/share/reel/<token>` is what the Instagram app's "Copy link" often
     * produces, and it is not a Reel URL. Follow its redirect (see [InstagramShareResolver]: HTTP
     * redirects only, every hop validated) and keep the real link in the journal. When it cannot
     * be resolved the share link is handed to yt-dlp unchanged and the failure is logged.
     */
    private fun resolveInstagramShare(job: StoredDownloadJob): StoredDownloadJob {
        val resolved = InstagramShareResolver.resolve(job.canonicalUrl)
        if (resolved == null) {
            AppLog.w("DownloadService", "share link not resolved j=${job.id}; handing it to yt-dlp as is")
            return job
        }
        AppLog.i("DownloadService", "share link resolved j=${job.id}")
        return DownloadJobStore.update(this, job.id) { it.copy(canonicalUrl = resolved.canonicalUrl) } ?: job
    }

    private fun hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun stopIfIdle() {
        val stopStartId = synchronized(lock) {
            if (queue.isEmpty() && activeJobId == null && workerThread?.isAlive != true) latestStartId else null
        }
        if (stopStartId != null && stopSelfResult(stopStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        val id = activeJobId
        if (id != null) {
            JobControls.requested[id] = ControlAction.PAUSE
            YoutubeDL.getInstance().destroyProcessById(id)
            DownloadJobStore.find(this, id)?.let {
                if (it.state in EXECUTION_STATES) {
                    lifecycle.interruptJob(it, FailureKind.CANCELLED, getString(R.string.status_interrupted))
                }
            }
        }
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val id = activeJobId
        if (id != null) {
            JobControls.requested[id] = ControlAction.PAUSE
            YoutubeDL.getInstance().destroyProcessById(id)
            DownloadJobStore.find(this, id)?.let {
                lifecycle.interruptJob(it, FailureKind.CANCELLED, getString(R.string.error_service_timeout))
            }
        }
        AppLog.w("DownloadService", "Foreground-service dataSync timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    enum class EnqueueResult { ACCEPTED, DUPLICATE, INVALID, START_FAILED }

    companion object {
        private const val ACTION_ENQUEUE = "com.kinescope.app.ACTION_ENQUEUE"
        private const val ACTION_PAUSE = "com.kinescope.app.ACTION_PAUSE"
        private const val ACTION_RESUME = "com.kinescope.app.ACTION_RESUME"
        internal const val ACTION_STOP = "com.kinescope.app.ACTION_STOP"
        internal const val EXTRA_JOB_ID = "extra_job_id"
        private const val IDLE_TIMEOUT_MS = 5_000L

        private val EXECUTION_STATES = setOf(
            JobState.PREPARING,
            JobState.RUNNING,
            JobState.PROCESSING,
            JobState.SAVING
        )
        private val RETRYABLE_STATES = setOf(JobState.PAUSED, JobState.INTERRUPTED, JobState.FAILED)
        private val TERMINAL_STATES = setOf(JobState.DONE, JobState.STOPPED)

        fun enqueue(context: Context, url: String, qualityIndex: Int): EnqueueResult {
            // Patch 37: one strict entry point for both sites. `mediaId` is the bare YouTube video id
            // (unchanged, so existing journals still de-duplicate) or `ig:<shortcode>`.
            val parsed = MediaUrlParser.parse(url) ?: return EnqueueResult.INVALID
            if (DownloadJobStore.findActiveDuplicate(context, parsed.mediaId) != null) {
                return EnqueueResult.DUPLICATE
            }

            val now = System.currentTimeMillis()
            val preset = qualityPresets.getOrElse(qualityIndex) { qualityPresets[0] }
            val job = StoredDownloadJob(
                id = UUID.randomUUID().toString(),
                canonicalUrl = parsed.canonicalUrl,
                videoId = parsed.mediaId,
                qualityId = preset.id,
                state = JobState.QUEUED,
                destinationSubfolder = Settings.getDownloadSubfolder(context),
                createdAtMillis = now,
                updatedAtMillis = now
            )
            DownloadJobStore.upsert(context, job)
            DownloadQueueBus.upsert(
                DownloadJobStatus(
                    id = job.id,
                    url = job.canonicalUrl,
                    qualityLabel = context.getString(preset.labelRes),
                    state = JobState.QUEUED,
                    progressText = context.getString(R.string.status_queued)
                )
            )

            return try {
                val intent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_ENQUEUE
                    putExtra(EXTRA_JOB_ID, job.id)
                }
                ContextCompat.startForegroundService(context, intent)
                AppLog.i("DownloadService", "Persisted and dispatched job=${job.id} quality=${preset.id}")
                EnqueueResult.ACCEPTED
            } catch (e: Exception) {
                DownloadJobStore.update(context, job.id) {
                    it.copy(state = JobState.INTERRUPTED, failureKind = FailureKind.ENGINE)
                }
                DownloadQueueBus.update(job.id) {
                    it.copy(
                        state = JobState.INTERRUPTED,
                        progressText = context.getString(R.string.error_service_start),
                        failureKind = FailureKind.ENGINE
                    )
                }
                AppLog.e("DownloadService", "Could not start foreground service job=${job.id}", e)
                EnqueueResult.START_FAILED
            }
        }

        fun pause(context: Context, id: String) = sendControl(context, ACTION_PAUSE, id, foreground = false)
        fun stop(context: Context, id: String) {
            if (sendControl(context, ACTION_STOP, id, foreground = false)) DownloadQueueBus.remove(id)
        }
        fun resume(context: Context, id: String) = sendControl(context, ACTION_RESUME, id, foreground = true)

        private fun sendControl(context: Context, actionName: String, id: String, foreground: Boolean): Boolean {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = actionName
                putExtra(EXTRA_JOB_ID, id)
            }
            return runCatching {
                if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
                true
            }.onFailure { AppLog.e("DownloadService", "Control dispatch failed action=$actionName job=$id", it) }
                .getOrDefault(false)
        }
    }
}
