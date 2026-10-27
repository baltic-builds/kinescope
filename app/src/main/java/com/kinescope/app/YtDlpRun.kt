package com.kinescope.app

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Patch 35: a route stayed under the minimum speed and was abandoned for the next one. */
internal class BypassSlowException(val kbps: Int) : Exception("slow route")

/** Patch 33: a bypass run produced no data for too long and was stopped by the watchdog. */
internal class BypassStalledException(message: String) : Exception(message)

/** Patch 33: every bypass strategy and the direct fallback failed at the connection level. */
internal class BypassTransportException(message: String) : Exception(message)

/**
 * Patch 40: one yt-dlp execution: builds the request, runs it with the progress callback and the stall/slow
 * watchdog (see RunMonitor) and reports progress to the queue and the notification. Retry, route and recovery
 * decisions belong to [RouteLadder] and [RecoveryChain].
 */
internal class YtDlpRun(base: Context, private val notifier: DownloadNotifier) : ContextWrapper(base) {
    private fun speedText(kbps: Int): String =
        if (kbps >= 1024) getString(R.string.speed_mb, kbps / 1024.0) else getString(R.string.speed_kb, kbps)

    fun executeAttemptOverBypass(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile,
        bypass: BypassSession?,
        limits: MonitorLimits? = null
    ) {
        val mediaSource = job.mediaSource
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
            if (mediaSource == MediaSource.INSTAGRAM) {
                // A carousel post is a playlist to yt-dlp; one Reel is wanted.
                addOption("--playlist-items", "1")
                applyInstagramFormat(preset.id)
            } else {
                preset.apply(this)
            }

            if (mediaSource == MediaSource.INSTAGRAM) {
                // Instagram: the user's own signed-in session, nothing else (no YouTube cookies,
                // no custom user agent: yt-dlp talks to Instagram with its own headers).
                if (InstagramAuth.hasSavedSession(this@YtDlpRun)) {
                    addOption("--cookies", InstagramAuth.cookieFile(this@YtDlpRun).absolutePath)
                }
            } else if (profile.useCookies && YouTubeAuth.hasSavedSession(this@YtDlpRun)) {
                addOption("--cookies", YouTubeAuth.cookieFile(this@YtDlpRun).absolutePath)
                YouTubeAuth.userAgent(this@YtDlpRun)?.let { addOption("--add-header", "User-Agent:$it") }
            }
            profile.extractorArgs?.let { addOption("--extractor-args", it) }
            if (profile.forceIpv4) addOption("--force-ipv4")
            // socks5h: the bypass engine resolves the host name itself, not the device, so a
            // network that filters DNS for these hosts does not defeat the bypass by itself.
            bypass?.let { addOption("--proxy", "socks5h://${DpiEngine.HOST}:${it.port}") }
        }

        val monitorLimits = limits ?: UNJUDGED_LIMITS
        AppLog.i(
            "DownloadService",
            "run j=${job.id} p=$profile src=${if (mediaSource == MediaSource.INSTAGRAM) "ig" else "yt"} " +
                "auth=${if (mediaSource == MediaSource.INSTAGRAM) InstagramAuth.hasSavedSession(this) else profile.useCookies && YouTubeAuth.hasSavedSession(this)} " +
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
        var lastUiAt = 0L
        var lastUiPhase = RunMonitor.Phase.START
        var finishingAnnounced = false
        try {
            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, line ->
                // Patch 38: yt-dlp prints the ids it chose ("Downloading 1 format(s): a+b"). For Instagram
                // a DASH pair (`dash-...vd+dash-...ad`) is the VP9 route, so the id line in a report
                // shows what was actually downloaded when a saved Reel does not play.
                if (mediaSource == MediaSource.INSTAGRAM && line.contains("format(s):")) {
                    AppLog.i("DownloadService", "fmt j=${job.id} ${line.substringAfter("format(s):").trim().take(90)}")
                }
                monitor.onLine(line)
                val snapshot = monitor.snapshot()
                val now = SystemClock.elapsedRealtime()
                val percent = YtdlpLine.parse(line).percent
                    ?: snapshot.percent.takeIf { it >= 0f }
                    ?: progress.takeIf { it >= 0f }
                if (JobControls.requested[job.id] != null) {
                    YoutubeDL.getInstance().destroyProcessById(job.id)
                } else if (snapshot.phase == RunMonitor.Phase.QUIET) {
                    if (lastUiPhase != RunMonitor.Phase.QUIET) {
                        DownloadQueueBus.update(job.id) {
                            it.copy(
                                state = JobState.PROCESSING,
                                progressText = getString(R.string.status_processing),
                                progressFraction = null
                            )
                        }
                        notifier.update(getString(R.string.status_processing), job.id)
                        lastUiAt = now
                    }
                } else if (percent != null) {
                    val finishing = percent >= 99.5f
                    if (!finishing) finishingAnnounced = false
                    if ((finishing && !finishingAnnounced) || (!finishing && now - lastUiAt >= UI_PROGRESS_THROTTLE_MS)) {
                        val roundedEta = if (etaInSeconds >= 0) ((etaInSeconds + 2) / 5) * 5 else -1
                        val text = when {
                            finishing -> getString(R.string.status_finishing_download)
                            snapshot.kbps > 0 -> getString(R.string.progress_percent_speed, percent, speedText(snapshot.kbps))
                            roundedEta >= 0 -> getString(R.string.progress_percent_eta, percent, roundedEta)
                            else -> getString(R.string.progress_percent_only, percent)
                        }
                        DownloadQueueBus.update(job.id) {
                            it.copy(
                                state = JobState.RUNNING,
                                progressText = text,
                                progressFraction = (percent / 100f).coerceIn(0f, 1f)
                            )
                        }
                        if (finishing) {
                            finishingAnnounced = true
                            notifier.update(getString(R.string.status_finishing_download), job.id)
                        } else {
                            notifier.updateProgress(job.id, percent.toInt())
                        }
                        lastUiAt = now
                    }
                } else if (lastUiPhase != snapshot.phase) {
                    // Extraction / JS challenge / format selection: announce the phase once instead of
                    // recomposing the whole queue for every yt-dlp line that says the same thing.
                    DownloadQueueBus.update(job.id) {
                        it.copy(
                            state = JobState.RUNNING,
                            progressText = getString(R.string.status_preparing_download),
                            progressFraction = null
                        )
                    }
                    lastUiAt = now
                }
                lastUiPhase = snapshot.phase
            }
        } catch (e: Exception) {
            val userControl = JobControls.requested[job.id] != null
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

    private companion object {
        // Patch 33: bypass watchdog and first-launch wait.
        private const val WATCHDOG_TICK_MS = 2_000L

        private const val UI_PROGRESS_THROTTLE_MS = 750L
        private const val HEARTBEAT_MS = 15_000L
        private val UNJUDGED_LIMITS = MonitorLimits(
            startMs = Long.MAX_VALUE / 4, jsMs = Long.MAX_VALUE / 4, idleMs = Long.MAX_VALUE / 4
        )
    }
}
