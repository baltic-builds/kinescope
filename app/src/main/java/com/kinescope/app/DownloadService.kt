package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * Durable single-worker foreground queue. Accepted jobs are journaled before
 * the service is started; process death therefore becomes an explicit
 * INTERRUPTED state instead of a silently lost download.
 */
class DownloadService : Service() {
    private val queue = LinkedBlockingQueue<String>()
    private val lock = Any()
    private var workerThread: Thread? = null

    @Volatile
    private var activeJobId: String? = null

    private var lastNotificationAt = 0L
    private var lastNotificationPercent = -1

    @Volatile
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLog.i("DownloadService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        if (action == ACTION_ENQUEUE || action == ACTION_RESUME) {
            // startForegroundService callers require this promptly, before any
            // disk/network work or worker startup.
            startForegroundWithNotification(getString(R.string.notification_starting))
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
                    transition(job, JobState.PAUSED, getString(R.string.status_paused))
                    AppLog.i("DownloadService", "Paused queued job=$id")
                } else {
                    requestedControls[id] = ControlAction.PAUSE
                    YoutubeDL.getInstance().destroyProcessById(id)
                    AppLog.i("DownloadService", "Pause raced queue start for job=$id")
                }
            }
            JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING -> {
                requestedControls[id] = ControlAction.PAUSE
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
        requestedControls.remove(id)
        val queued = transition(
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
            requestedControls[id] = ControlAction.STOP
            DownloadQueueBus.update(id) { it.copy(progressText = getString(R.string.status_stopping)) }
            val destroyed = YoutubeDL.getInstance().destroyProcessById(id)
            AppLog.i("DownloadService", "Stop requested job=$id destroyedNow=$destroyed")
        } else {
            queue.remove(id)
            finishControlled(job, ControlAction.STOP)
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
        requestedControls.remove(job.id)?.let {
            finishControlled(job, it)
            return
        }

        DownloadJobStore.pendingUri(job)?.let {
            MediaStorage.deletePending(this, it)
            job = DownloadJobStore.update(this, job.id) { current -> current.copy(pendingUri = null) } ?: job
        }

        job = transition(job, JobState.PREPARING, getString(R.string.status_preparing), failureKind = null)
        updateNotification(getString(R.string.notification_downloading), job.id)

        if (!hasNetwork()) {
            interruptJob(job, FailureKind.NO_INTERNET, getString(R.string.error_no_internet))
            return
        }

        if (YouTubeAuth.hasSavedSession(this)) YouTubeAuth.refreshSavedSession(this)

        val workspace = DownloadJobStore.workspaceDir(this, job.id)
        if (!workspace.exists() && !workspace.mkdirs()) {
            failJob(job, FailureKind.STORAGE, getString(R.string.error_workspace_create))
            return
        }
        val outputTemplate = "${workspace.absolutePath}/%(title).150B.%(ext)s"
        val preset = qualityPreset(job.qualityId)

        // Patch 35: a strategy search steps aside between two strategies while a download runs.
        DpiSearchController.holdForDownload()
        try {
            awaitStrategySearch(job)
            EngineController.withEngine(this) {
                executeWithRecovery(job, preset, outputTemplate)
            }
        } catch (controlled: ControlledStop) {
            finishControlled(job, controlled.action)
            return
        } catch (_: AlreadyHandledFailure) {
            return
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            interruptJob(job, FailureKind.CANCELLED, getString(R.string.status_interrupted))
            return
        } catch (e: Exception) {
            AppLog.e("DownloadService", "Engine execution failed job=${job.id}", e)
            failJob(job, FailureKind.ENGINE, friendlyError(e.message))
            return
        }

        requestedControls.remove(job.id)?.let {
            finishControlled(job, it)
            return
        }

        val outputFile = findFinalOutput(workspace)
        if (outputFile == null) {
            failJob(job, FailureKind.OTHER, getString(R.string.error_output_not_found))
            return
        }

        job = transition(job, JobState.PROCESSING, getString(R.string.status_processing))
        requestedControls.remove(job.id)?.let {
            finishControlled(job, it)
            return
        }

        publishCompletedOutput(job, preset, outputFile)
    }

    private fun executeWithRecovery(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String
    ) {
        val attempts = listOf(
            RecoveryProfile.DEFAULT,
            RecoveryProfile.DEFAULT_AFTER_REFRESH,
            RecoveryProfile.WEB_SAFARI_IPV4,
            RecoveryProfile.ANDROID_VR_LOGGED_OUT
        )
        var nightlyRefreshAttempted = false
        var lastError: String? = null

        for ((index, profile) in attempts.withIndex()) {
            requestedControls.remove(job.id)?.let { throw ControlledStop(it) }

            if (profile == RecoveryProfile.DEFAULT_AFTER_REFRESH && !nightlyRefreshAttempted) {
                nightlyRefreshAttempted = true
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_update))
                }
                AppLog.w("DownloadService", "Refreshing yt-dlp nightly before recovery job=${job.id}")
                runCatching { EngineController.updateNightly(this) }
                    .onFailure { AppLog.e("DownloadService", "Recovery updater failed job=${job.id}", it) }
            }

            if (index > 0) {
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_retry, index + 1, attempts.size))
                }
                try {
                    Thread.sleep(Random.nextLong(1_500L, 3_001L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }

            requestedControls.remove(job.id)?.let { throw ControlledStop(it) }
            transition(job, JobState.RUNNING, getString(R.string.status_starting), failureKind = null)

            try {
                executeAttempt(job, preset, outputTemplate, profile)
                return
            } catch (e: YoutubeDL.CanceledException) {
                val action = requestedControls.remove(job.id) ?: ControlAction.STOP
                throw ControlledStop(action)
            } catch (e: YoutubeDLException) {
                lastError = e.message.orEmpty()
                val recoverable = DownloadErrorClassifier.isRecoverableYoutubeBlock(lastError)
                AppLog.e(
                    "DownloadService",
                    "Attempt ${index + 1}/${attempts.size} failed job=${job.id} profile=$profile recoverable=$recoverable",
                    e
                )
                if (!recoverable || index == attempts.lastIndex) break
            } catch (e: BypassTransportException) {
                // Patch 33: the ladder already tried every strategy and a direct connection. A
                // different yt-dlp client profile cannot fix a connection that does not work.
                lastError = e.message.orEmpty()
                AppLog.e("DownloadService", "Attempt ${index + 1}/${attempts.size} could not reach YouTube job=${job.id}", e)
                break
            }
        }

        val kind = DownloadErrorClassifier.classify(lastError)
        if (kind == FailureKind.YOUTUBE_VERIFICATION) {
            pauseForVerification(job, friendlyError(lastError))
            throw AlreadyHandledFailure()
        }
        failJob(job, kind, friendlyError(lastError))
        throw AlreadyHandledFailure()
    }

    private fun executeAttempt(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile
    ) {
        // Patch 27: an optional local DPI-bypass engine (see DpiBypass.kt). It is off by default;
        // when the user has switched it on in Settings, this starts a fresh engine process for
        // exactly this attempt and always tears it down afterwards, success or failure, so no
        // orphaned ":dpi" process survives a crash or a cancelled attempt.
        executeWithBypassFallback(job, preset, outputTemplate, profile)
    }

    /** Patch 35: what one route did. [kbps] is the measured speed for [RouteOutcome.SLOW]. */
    private enum class RouteOutcome { OK, FAILED, SLOW }

    private class RouteRun(val outcome: RouteOutcome, val kbps: Int = 0)

    /**
     * Patch 35: one attempt through an ordered list of routes, with a fallback ladder that reacts
     * to how a route behaves, not only to whether it connects.
     *
     * A route is a verified bypass strategy or the direct connection (see [BypassRoutes]).
     * - The order is: the route that carried a download in the last ten minutes, then the stored
     *   chain (pinned strategy first, strategies that failed in real use recently last), with the
     *   direct route first when a third-party VPN is on.
     * - Each strategy gets a live check that only decides ORDER: a strategy that fails it is
     *   deferred, not dropped, and tried for real after the others with a shorter watchdog.
     * - A route that connects but stalls (no output and no network bytes) is abandoned; one whose
     *   speed stays under [MIN_ROUTE_KBPS] is abandoned too, at most [MAX_SLOW_HOPS] times: if
     *   two routes are equally slow the cause is not the route (throttling, a slow network), so
     *   the better of them is run to the end without a speed limit. The download resumes from the
     *   part file (`--continue`), so a hop does not start over.
     * - The direct route is last, and only if the network can resolve YouTube directly.
     * - The route that finishes is remembered (scoreboard) and stored as proven; routes that
     *   failed before it are quarantined for ten minutes, so the next download and the YouTube
     *   tunnel do not start with them.
     * Errors that are not about the connection (private video, bot check, ...) are rethrown
     * untouched for the normal recovery chain; when every route failed at the connection level
     * the chain is ended.
     */
    private fun executeWithBypassFallback(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile
    ) {
        val chain = DpiBypass.activeChain(this)
        if (chain.isEmpty()) {
            executeAttemptOverBypass(job, preset, outputTemplate, profile, null)
            return
        }

        val vpn = NetworkState.systemVpnActive(this)
        val recent = RouteScoreboard.recentGood(SystemClock.elapsedRealtime())
            ?.takeIf { it == BypassRoutes.DIRECT || it in chain }
        val order = BypassRoutes.order(chain, vpn, recent)
        val failures = ArrayList<String>()
        val hardFailed = ArrayList<String>()
        val deferred = ArrayList<String>()
        val slow = ArrayList<Pair<String, Int>>()
        AppLog.i(
            "DownloadService",
            "ladder j=${job.id} routes=${order.size} vpn=${if (vpn) 1 else 0} recent=${recent?.let { BypassRoutes.label(it, chain) } ?: "-"}"
        )

        for ((index, route) in order.withIndex()) {
            if (index > 0) announceRoute(job, index + 1, order.size)
            val judgeSpeed = index < order.lastIndex && slow.size < MAX_SLOW_HOPS
            val run = runRoute(
                job, preset, outputTemplate, profile, route, BypassRoutes.label(route, chain),
                checkFirst = route != recent,
                limits = if (judgeSpeed) NORMAL_LIMITS.copy(minKbps = MIN_ROUTE_KBPS) else NORMAL_LIMITS,
                failures = failures, deferred = deferred
            )
            when (run.outcome) {
                RouteOutcome.OK -> return finishOnRoute(route, chain, hardFailed)
                RouteOutcome.SLOW -> slow += route to run.kbps
                RouteOutcome.FAILED -> if (route !in deferred) hardFailed += route
            }
        }

        for ((index, route) in deferred.withIndex()) {
            announceRoute(job, order.size + index + 1, order.size + deferred.size)
            val run = runRoute(
                job, preset, outputTemplate, profile, route, BypassRoutes.label(route, chain),
                checkFirst = false, limits = TIGHT_LIMITS, failures = failures, deferred = null
            )
            if (run.outcome == RouteOutcome.OK) return finishOnRoute(route, chain, hardFailed)
            if (run.outcome == RouteOutcome.FAILED) hardFailed += route
        }

        val bestSlow = slow.maxByOrNull { it.second }
        if (bestSlow != null) {
            AppLog.i("DownloadService", "slow-all j=${job.id} best=${BypassRoutes.label(bestSlow.first, chain)} kbps=${bestSlow.second}")
            val run = runRoute(
                job, preset, outputTemplate, profile, bestSlow.first, BypassRoutes.label(bestSlow.first, chain),
                checkFirst = false, limits = NORMAL_LIMITS, failures = failures, deferred = null
            )
            if (run.outcome == RouteOutcome.OK) return finishOnRoute(bestSlow.first, chain, hardFailed)
        }

        if (!vpn && DpiBypass.directConnectionWorks()) {
            DownloadQueueBus.update(job.id) { it.copy(progressText = getString(R.string.status_trying_direct)) }
            val run = runRoute(
                job, preset, outputTemplate, profile, BypassRoutes.DIRECT, BypassRoutes.DIRECT,
                checkFirst = false, limits = NORMAL_LIMITS, failures = failures, deferred = null
            )
            if (run.outcome == RouteOutcome.OK) return finishOnRoute(BypassRoutes.DIRECT, chain, hardFailed)
        }

        AppLog.w("DownloadService", "ladder exhausted j=${job.id} fails=[${failures.joinToString("; ")}]")
        throw BypassTransportException("bypass transport failure: ${firstLine(failures.lastOrNull().orEmpty())}")
    }

    /** Remembers the route that carried the download and quarantines the ones that failed before it. */
    private fun finishOnRoute(route: String, chain: List<String>, hardFailed: List<String>) {
        RouteScoreboard.record(route, SystemClock.elapsedRealtime())
        if (route != BypassRoutes.DIRECT) DpiPrefs.promoteVerified(this, route)
        for (failed in hardFailed) {
            if (failed != BypassRoutes.DIRECT && failed != route) DpiPrefs.quarantine(this, failed)
        }
        if (hardFailed.isNotEmpty()) {
            AppLog.i("DownloadService", "quarantined n=${hardFailed.size} kept=${BypassRoutes.label(route, chain)}")
        }
    }

    private fun announceRoute(job: StoredDownloadJob, position: Int, total: Int) {
        DownloadQueueBus.update(job.id) {
            it.copy(progressText = getString(R.string.status_trying_another_way, position, total))
        }
    }

    /**
     * Runs the download once through [route]. The outcome is OK when it finished, SLOW when the
     * speed limit in [limits] tripped, FAILED when the route failed at the connection level
     * (noted in [failures]); anything else is rethrown. With [deferred] set, a strategy that fails
     * its live check is queued there instead of being run.
     */
    private fun runRoute(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile,
        route: String,
        label: String,
        checkFirst: Boolean,
        limits: MonitorLimits,
        failures: MutableList<String>,
        deferred: MutableList<String>?
    ): RouteRun {
        requestedControls.remove(job.id)?.let { throw ControlledStop(it) }
        val startedAt = SystemClock.elapsedRealtime()
        var session: BypassSession? = null
        try {
            if (route != BypassRoutes.DIRECT) {
                session = DpiBypass.startLine(this, route, label)
                if (session == null) {
                    failures += "$label no-start"
                    AppLog.w("DownloadService", "route j=${job.id} r=$label no-start")
                    return RouteRun(RouteOutcome.FAILED)
                }
                if (checkFirst) {
                    val health = DpiBypass.checkHealth(session.port)
                    val text = "chk j=${job.id} r=$label ok=${health.ok} st=${health.stage} why=${health.why} ms=${health.ms}"
                    if (health.ok) AppLog.i("DownloadService", text) else AppLog.w("DownloadService", text)
                    if (!health.ok && deferred != null) {
                        failures += "$label chk:${health.stage}:${health.why}"
                        deferred += route
                        return RouteRun(RouteOutcome.FAILED)
                    }
                }
            }
            executeAttemptOverBypass(job, preset, outputTemplate, profile, session, limits)
            AppLog.i("DownloadService", "route j=${job.id} r=$label ok ms=${SystemClock.elapsedRealtime() - startedAt}")
            return RouteRun(RouteOutcome.OK)
        } catch (e: BypassSlowException) {
            failures += "$label slow ${e.kbps}KB/s"
            AppLog.w("DownloadService", "route j=${job.id} r=$label slow kbps=${e.kbps}")
            return RouteRun(RouteOutcome.SLOW, e.kbps)
        } catch (e: BypassStalledException) {
            failures += "$label stall"
            AppLog.w("DownloadService", "route j=${job.id} r=$label stall")
            return RouteRun(RouteOutcome.FAILED)
        } catch (e: YoutubeDLException) {
            if (!DownloadErrorClassifier.isTransportFailure(e.message)) throw e
            failures += "$label ${LogFormat.ytdlpSummary(e.message)}"
            AppLog.w("DownloadService", "route j=${job.id} r=$label transport | ${LogFormat.ytdlpSummary(e.message)}")
            return RouteRun(RouteOutcome.FAILED)
        } finally {
            session?.close()
        }
    }

    private fun firstLine(text: String): String = text.lineSequence().firstOrNull().orEmpty().take(160)

    private fun speedText(kbps: Int): String =
        if (kbps >= 1024) getString(R.string.speed_mb, kbps / 1024.0) else getString(R.string.speed_kb, kbps)

    /**
     * Patch 33: while the strategy check is still running (the first launch scans all 72), a
     * download on a blocked network waits for it instead of starting without a bypass and
     * failing; the engine only allows one run at a time anyway. On a network that is not
     * blocked there is nothing to wait for.
     */
    private fun awaitStrategySearch(job: StoredDownloadJob) {
        if (!DpiSearchController.state.value.running) return
        // Patch 35: with verified strategies already in place the download does not wait for the
        // whole check; it only waits for the strategy the check is testing right now, and the
        // check pauses before the next one (see holdForDownload). With none, it waits as before.
        val haveChain = DpiStrategyStore.chain(this).isNotEmpty()
        if (!haveChain && DpiBypass.directConnectionWorks()) return
        DownloadQueueBus.update(job.id) { it.copy(progressText = getString(R.string.status_waiting_for_check)) }
        val deadline = SystemClock.elapsedRealtime() + if (haveChain) ENGINE_WAIT_LIMIT_MS else SEARCH_WAIT_LIMIT_MS
        while (SystemClock.elapsedRealtime() < deadline &&
            (if (haveChain) DpiEngine.regularBusy() else DpiSearchController.state.value.running)
        ) {
            requestedControls.remove(job.id)?.let { throw ControlledStop(it) }
            try {
                Thread.sleep(1_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }

    private fun executeAttemptOverBypass(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile,
        bypass: BypassSession?,
        limits: MonitorLimits? = null
    ) {
        val request = YoutubeDLRequest(job.canonicalUrl).apply {
            addOption("-o", outputTemplate)
            addOption("--no-playlist")
            addOption("--continue")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            addOption("--extractor-retries", "3")
            // Patch 33: give up on a silent socket after 15 s (the default is 20 s per try)
            // so a dead route fails, and hands over to the next strategy, quickly.
            addOption("--socket-timeout", "15")
            addOption("--retry-sleep", "http:exp=1:8")
            addOption("--sleep-requests", "0.75")
            preset.apply(this)

            if (profile.useCookies && YouTubeAuth.hasSavedSession(this@DownloadService)) {
                addOption("--cookies", YouTubeAuth.cookieFile(this@DownloadService).absolutePath)
                YouTubeAuth.userAgent(this@DownloadService)?.let { addOption("--add-header", "User-Agent:$it") }
            }
            profile.extractorArgs?.let { addOption("--extractor-args", it) }
            if (profile.forceIpv4) addOption("--force-ipv4")
            // socks5h: the bypass engine resolves the host name itself, not the device, so a
            // network that filters DNS for these hosts does not defeat the bypass by itself
            // (NetworkCheck's DNS_BLOCKS_BYPASS verdict names exactly this remaining case).
            bypass?.let { addOption("--proxy", "socks5h://${DpiEngine.HOST}:${it.port}") }
        }

        val monitorLimits = limits ?: UNJUDGED_LIMITS
        AppLog.i(
            "DownloadService",
            "run j=${job.id} p=$profile auth=${profile.useCookies && YouTubeAuth.hasSavedSession(this)} " +
                "bypass=${bypass != null}" +
                (limits?.let { " wd=${it.startMs / 1000}/${it.jsMs / 1000}/${it.idleMs / 1000}s min=${it.minKbps}KB/s" } ?: "")
        )
        // Patch 35: the monitor. yt-dlp is judged by what it prints and by the bytes the app
        // receives (TrafficStats, our uid: the engine process counts too), not only by silence:
        //   - stalled: no stdout line AND no network bytes for a phase-dependent time (extraction
        //     60 s, the CPU-bound QuickJS challenge 180 s, downloading 40 s);
        //   - slow: the average speed printed by yt-dlp stayed under limits.minKbps for the whole
        //     window (only when the caller asks for it).
        // Only stdout reaches the wrapper's callback; yt-dlp's retry warnings go to stderr, so a
        // stdout line does mean yt-dlp is alive. The wrapper's own progress value only updates when
        // a line ends in "ETA mm:ss" and stays at -1 for "ETA Unknown", so the line is parsed here.
        val monitor = RunMonitor(monitorLimits, SystemClock::elapsedRealtime)
        val stalledReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val slowKbps = AtomicLong(-1L)
        val finished = AtomicBoolean(false)
        val judged = limits != null
        val watchdog = Thread({
            val uid = android.os.Process.myUid()
            var lastRx = android.net.TrafficStats.getUidRxBytes(uid)
            var lastBeat = SystemClock.elapsedRealtime()
            var rxRateKbps = 0L
            try {
                while (!finished.get()) {
                    Thread.sleep(WATCHDOG_TICK_MS)
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    if (rx >= 0 && lastRx >= 0) {
                        monitor.onNetworkBytes(rx - lastRx)
                        rxRateKbps = (rx - lastRx) * 1_000L / WATCHDOG_TICK_MS / 1_024L
                    }
                    lastRx = rx
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBeat >= HEARTBEAT_MS) {
                        lastBeat = now
                        val s = monitor.snapshot()
                        AppLog.i(
                            "DownloadService",
                            "hb j=${job.id} ph=${s.phase.tag} pct=${"%.1f".format(java.util.Locale.US, s.percent)} " +
                                "kbps=${s.kbps} rx=${rxRateKbps} lines=${s.lines} idle=${s.idleMs / 1000}s last=\"${s.lastLine.take(70)}\""
                        )
                    }
                    if (finished.get() || !judged) continue
                    when (val verdict = monitor.verdict()) {
                        is RunVerdict.Stalled -> {
                            stalledReason.set("no data from YouTube for ${verdict.idleMs / 1000}s in phase ${verdict.phase}")
                            AppLog.w("DownloadService", "stall j=${job.id} ph=${verdict.phase} idle=${verdict.idleMs / 1000}s")
                            YoutubeDL.getInstance().destroyProcessById(job.id)
                            break
                        }
                        is RunVerdict.Slow -> {
                            slowKbps.set(verdict.kbps.toLong())
                            AppLog.w("DownloadService", "slow j=${job.id} kbps=${verdict.kbps}")
                            YoutubeDL.getInstance().destroyProcessById(job.id)
                            break
                        }
                        RunVerdict.Ok -> Unit
                    }
                }
            } catch (_: InterruptedException) {
                // The attempt ended; nothing to watch any more.
            }
        }, "kinescope-watchdog").apply {
            isDaemon = true
            start()
        }

        var result = "ok"
        val startedAt = SystemClock.elapsedRealtime()
        try {
            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, line ->
                monitor.onLine(line)
                val snapshot = monitor.snapshot()
                val percent = YtdlpLine.parse(line).percent
                    ?: snapshot.percent.takeIf { it >= 0f }
                    ?: progress.takeIf { it >= 0f }
                if (requestedControls[job.id] != null) {
                    YoutubeDL.getInstance().destroyProcessById(job.id)
                } else if (percent != null) {
                    val text = when {
                        etaInSeconds >= 0 -> getString(R.string.progress_percent_eta, percent, etaInSeconds)
                        snapshot.kbps > 0 -> getString(R.string.progress_percent_speed, percent, speedText(snapshot.kbps))
                        else -> getString(R.string.progress_percent_only, percent)
                    }
                    DownloadQueueBus.update(job.id) {
                        it.copy(
                            state = JobState.RUNNING,
                            progressText = text,
                            progressFraction = (percent / 100f).coerceIn(0f, 1f)
                        )
                    }
                    updateProgressNotification(job.id, percent.toInt())
                } else {
                    // Still extracting / solving the challenge / choosing formats.
                    DownloadQueueBus.update(job.id) {
                        it.copy(
                            state = JobState.RUNNING,
                            progressText = getString(R.string.status_preparing_download),
                            progressFraction = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val userControl = requestedControls[job.id] != null
            val stalled = stalledReason.get()
            result = when {
                userControl -> "cancel"
                stalled != null -> "stall"
                slowKbps.get() >= 0 -> "slow"
                else -> "err"
            }
            if (!userControl && (e is YoutubeDL.CanceledException || e is YoutubeDLException)) {
                if (stalled != null) throw BypassStalledException(stalled)
                if (slowKbps.get() >= 0) throw BypassSlowException(slowKbps.get().toInt())
            }
            throw e
        } finally {
            finished.set(true)
            watchdog.interrupt()
            val s = monitor.snapshot()
            AppLog.i(
                "DownloadService",
                "end j=${job.id} res=$result dur=${(SystemClock.elapsedRealtime() - startedAt) / 1000}s ph=${s.phase.tag} " +
                    "pct=${"%.1f".format(java.util.Locale.US, s.percent)} kbps=${s.kbps} lines=${s.lines} last=\"${s.lastLine.take(70)}\""
            )
        }
    }

    private fun publishCompletedOutput(job: StoredDownloadJob, preset: QualityPreset, outputFile: File) {
        transition(job, JobState.SAVING, getString(R.string.status_saving))
        // Download bytes/merge are already complete. Remove the notification's
        // Stop action while committing the file so a normal user cannot create
        // a new control request in the tiny finalization window.
        updateNotification(getString(R.string.status_saving), null)
        requestedControls.remove(job.id)?.let {
            finishControlled(job, it)
            return
        }

        val publishedUri = MediaStorage.publish(
            context = this,
            tempFile = outputFile,
            preferredMimeType = preset.preferredMimeType,
            subfolder = job.destinationSubfolder,
            displayName = outputFile.name,
            onPendingUri = { uri ->
                DownloadJobStore.update(this, job.id) { current -> current.copy(pendingUri = uri.toString()) }
            },
            shouldCancel = { requestedControls.containsKey(job.id) }
        )

        requestedControls.remove(job.id)?.let { action ->
            if (publishedUri != null && !MediaStorage.deleteUri(this, publishedUri)) {
                failJob(job, FailureKind.STORAGE, getString(R.string.error_control_cleanup_failed))
                return
            }
            finishControlled(job, action)
            return
        }

        if (publishedUri != null) {
            DownloadJobStore.remove(this, job.id)
            DownloadJobStore.cleanupWorkspace(this, job.id)
            DownloadQueueBus.update(job.id) {
                it.copy(
                    state = JobState.DONE,
                    progressText = getString(R.string.status_saved_to, job.destinationSubfolder),
                    progressFraction = null,
                    failureKind = null
                )
            }
            showCompletionNotification(job.id)
            AppLog.i("DownloadService", "Completed job=${job.id}")
        } else {
            failJob(job, FailureKind.STORAGE, getString(R.string.error_failed_to_save))
        }
    }

    private fun findFinalOutput(workspace: File): File? = workspace
        .listFiles { file ->
            file.isFile && INTERMEDIATE_SUFFIXES.none { suffix -> file.name.endsWith(suffix, ignoreCase = true) }
        }
        ?.maxByOrNull { it.lastModified() }

    private fun finishControlled(job: StoredDownloadJob, action: ControlAction) {
        requestedControls.remove(job.id)
        when (action) {
            ControlAction.PAUSE -> {
                transition(job, JobState.PAUSED, getString(R.string.status_paused))
                AppLog.i("DownloadService", "Paused job=${job.id}; workspace kept for resume")
            }
            ControlAction.STOP -> {
                DownloadJobStore.pendingUri(job)?.let { MediaStorage.deletePending(this, it) }
                DownloadJobStore.remove(this, job.id)
                DownloadJobStore.cleanupWorkspace(this, job.id)
                DownloadQueueBus.remove(job.id)
                AppLog.i("DownloadService", "Stopped job=${job.id}; workspace removed")
            }
        }
    }

    private fun pauseForVerification(job: StoredDownloadJob, message: String) {
        transition(
            job,
            JobState.PAUSED,
            message,
            failureKind = FailureKind.YOUTUBE_VERIFICATION
        )
        AppLog.w("DownloadService", "Job=${job.id} paused for YouTube verification; resumable")
    }

    private fun interruptJob(job: StoredDownloadJob, kind: FailureKind, message: String) {
        transition(job, JobState.INTERRUPTED, message, failureKind = kind)
        AppLog.w("DownloadService", "Job=${job.id} interrupted kind=$kind")
    }

    private fun failJob(job: StoredDownloadJob, kind: FailureKind, message: String) {
        transition(job, JobState.FAILED, message, failureKind = kind)
        AppLog.e("DownloadService", "Job=${job.id} failed kind=$kind")
    }

    private fun transition(
        job: StoredDownloadJob,
        state: JobState,
        text: String,
        failureKind: FailureKind? = job.failureKind
    ): StoredDownloadJob {
        val updated = DownloadJobStore.update(this, job.id) { current ->
            current.copy(state = state, failureKind = failureKind)
        } ?: job.copy(state = state, failureKind = failureKind)
        DownloadQueueBus.upsert(
            DownloadJobStatus(
                id = updated.id,
                url = updated.canonicalUrl,
                qualityLabel = getString(qualityPreset(updated.qualityId).labelRes),
                state = state,
                progressText = text,
                progressFraction = null,
                failureKind = failureKind
            )
        )
        return updated
    }

    private fun friendlyError(raw: String?): String {
        val message = raw.orEmpty()
        return when (DownloadErrorClassifier.classify(raw)) {
            FailureKind.YOUTUBE_VERIFICATION -> if (NetworkState.systemVpnActive(this)) {
                getString(R.string.error_verification_vpn)
            } else {
                getString(R.string.error_youtube_verification)
            }
            FailureKind.PRIVATE_VIDEO -> getString(R.string.error_private_video)
            FailureKind.AGE_RESTRICTED -> getString(R.string.error_age_restricted)
            FailureKind.UNAVAILABLE -> getString(R.string.error_video_unavailable)
            FailureKind.NO_INTERNET -> getString(R.string.error_no_internet)
            FailureKind.CONNECTION_BLOCKED -> getString(R.string.error_connection_blocked)
            else -> if (message.isBlank()) {
                getString(R.string.error_unknown)
            } else {
                // AppLog retains the technical detail in redacted form. The UI
                // gets only a bounded message to avoid full yt-dlp dumps.
                getString(R.string.error_download_generic, message.lineSequence().first().take(180))
            }
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
            buildNotification(text, activeJobId, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(text: String, jobId: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, jobId, null))
    }

    private fun updateProgressNotification(jobId: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (percent == lastNotificationPercent && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        if (now - lastNotificationAt < NOTIFICATION_THROTTLE_MS && percent !in setOf(0, 100)) return
        lastNotificationAt = now
        lastNotificationPercent = percent
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_progress, percent), jobId, percent)
        )
    }

    private fun buildNotification(text: String, jobId: String?, progress: Int?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (progress != null) builder.setProgress(100, progress.coerceIn(0, 100), false)
        if (!jobId.isNullOrBlank()) {
            val stopIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_JOB_ID, jobId)
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                jobId.hashCode() and 0x7fffffff,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.stop), stopPendingIntent)
        }
        return builder.build()
    }

    private fun showCompletionNotification(jobId: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_complete))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            COMPLETION_NOTIFICATION_BASE + abs(jobId.hashCode() % 10_000),
            notification
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            requestedControls[id] = ControlAction.PAUSE
            YoutubeDL.getInstance().destroyProcessById(id)
            DownloadJobStore.find(this, id)?.let {
                if (it.state in EXECUTION_STATES) {
                    interruptJob(it, FailureKind.CANCELLED, getString(R.string.status_interrupted))
                }
            }
        }
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val id = activeJobId
        if (id != null) {
            requestedControls[id] = ControlAction.PAUSE
            YoutubeDL.getInstance().destroyProcessById(id)
            DownloadJobStore.find(this, id)?.let {
                interruptJob(it, FailureKind.CANCELLED, getString(R.string.error_service_timeout))
            }
        }
        AppLog.w("DownloadService", "Foreground-service dataSync timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class ControlAction { PAUSE, STOP }

    private class ControlledStop(val action: ControlAction) : RuntimeException()
    private class AlreadyHandledFailure : RuntimeException()

    /** Patch 35: a route stayed under the minimum speed and was abandoned for the next one. */
    private class BypassSlowException(val kbps: Int) : Exception("slow route")

    /** Patch 33: a bypass run produced no data for too long and was stopped by the watchdog. */
    private class BypassStalledException(message: String) : Exception(message)

    /** Patch 33: every bypass strategy and the direct fallback failed at the connection level. */
    private class BypassTransportException(message: String) : Exception(message)

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

    enum class EnqueueResult { ACCEPTED, DUPLICATE, INVALID, START_FAILED }

    companion object {
        private val requestedControls = ConcurrentHashMap<String, ControlAction>()

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_BASE = 2_000
        private const val ACTION_ENQUEUE = "com.kinescope.app.ACTION_ENQUEUE"
        private const val ACTION_PAUSE = "com.kinescope.app.ACTION_PAUSE"
        private const val ACTION_RESUME = "com.kinescope.app.ACTION_RESUME"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP"
        private const val EXTRA_JOB_ID = "extra_job_id"
        private const val IDLE_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_THROTTLE_MS = 750L

        // Patch 33: bypass watchdog and first-launch wait.
        private const val WATCHDOG_TICK_MS = 2_000L

        // Patch 35: run limits, see RunMonitor. Extraction 60 s, the CPU-bound QuickJS challenge
        // 180 s, downloading 40 s (silence AND no network bytes); a strategy that failed its live
        // check gets tighter ones. A route slower than MIN_ROUTE_KBPS over 25 s is abandoned, at
        // most MAX_SLOW_HOPS times per attempt.
        private val NORMAL_LIMITS = MonitorLimits(startMs = 60_000L, jsMs = 180_000L, idleMs = 40_000L)
        private val TIGHT_LIMITS = MonitorLimits(startMs = 30_000L, jsMs = 120_000L, idleMs = 20_000L)
        private val UNJUDGED_LIMITS = MonitorLimits(
            startMs = Long.MAX_VALUE / 4, jsMs = Long.MAX_VALUE / 4, idleMs = Long.MAX_VALUE / 4
        )
        private const val MIN_ROUTE_KBPS = 80
        private const val MAX_SLOW_HOPS = 2
        private const val HEARTBEAT_MS = 15_000L
        private const val ENGINE_WAIT_LIMIT_MS = 45_000L
        private const val SEARCH_WAIT_LIMIT_MS = 8L * 60L * 1_000L
        private val INTERMEDIATE_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".ffmpeg")
        private val EXECUTION_STATES = setOf(
            JobState.PREPARING,
            JobState.RUNNING,
            JobState.PROCESSING,
            JobState.SAVING
        )
        private val RETRYABLE_STATES = setOf(JobState.PAUSED, JobState.INTERRUPTED, JobState.FAILED)
        private val TERMINAL_STATES = setOf(JobState.DONE, JobState.STOPPED)

        fun enqueue(context: Context, url: String, qualityIndex: Int): EnqueueResult {
            val parsed = YouTubeUrlParser.parse(url).parsed ?: return EnqueueResult.INVALID
            if (DownloadJobStore.findActiveDuplicate(context, parsed.videoId) != null) {
                return EnqueueResult.DUPLICATE
            }

            val now = System.currentTimeMillis()
            val preset = qualityPresets.getOrElse(qualityIndex) { qualityPresets[0] }
            val job = StoredDownloadJob(
                id = UUID.randomUUID().toString(),
                canonicalUrl = parsed.canonicalUrl,
                videoId = parsed.videoId,
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
