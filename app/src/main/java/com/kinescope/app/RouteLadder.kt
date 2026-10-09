package com.kinescope.app

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDLException

/** Patch 35: what one route did. [kbps] is the measured speed for [RouteOutcome.SLOW]. */
private enum class RouteOutcome { OK, FAILED, SLOW }

private class RouteRun(val outcome: RouteOutcome, val kbps: Int = 0)

/**
 * Patch 40: the route ladder (see [executeWithBypassFallback]) and the wait for a running strategy search.
 * A [ContextWrapper] so the moved code runs unchanged.
 */
internal class RouteLadder(base: Context, private val ytDlp: YtDlpRun) : ContextWrapper(base) {
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
    fun executeWithBypassFallback(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String,
        profile: RecoveryProfile
    ) {
        val chain = DpiBypass.activeChain(this)
        if (chain.isEmpty()) {
            ytDlp.executeAttemptOverBypass(job, preset, outputTemplate, profile, null)
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
        JobControls.requested.remove(job.id)?.let { throw ControlledStop(it) }
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
            ytDlp.executeAttemptOverBypass(job, preset, outputTemplate, profile, session, limits)
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

    /**
     * Patch 33: while the strategy check is still running (the first launch scans all 72), a
     * download on a blocked network waits for it instead of starting without a bypass and
     * failing; the engine only allows one run at a time anyway. On a network that is not
     * blocked there is nothing to wait for.
     */
    fun awaitStrategySearch(job: StoredDownloadJob) {
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
            JobControls.requested.remove(job.id)?.let { throw ControlledStop(it) }
            try {
                Thread.sleep(1_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }

    private companion object {
        // Patch 35: run limits, see RunMonitor. Extraction 60 s, the CPU-bound QuickJS challenge
        // 180 s, downloading 40 s (silence AND no network bytes); a strategy that failed its live
        // check gets tighter ones. A route slower than MIN_ROUTE_KBPS over 25 s is abandoned, at
        // most MAX_SLOW_HOPS times per attempt.
        private val NORMAL_LIMITS = MonitorLimits(startMs = 60_000L, jsMs = 180_000L, idleMs = 40_000L)
        private val TIGHT_LIMITS = MonitorLimits(startMs = 30_000L, jsMs = 120_000L, idleMs = 20_000L)
        private const val MIN_ROUTE_KBPS = 80
        private const val MAX_SLOW_HOPS = 2
        private const val ENGINE_WAIT_LIMIT_MS = 45_000L
        private const val SEARCH_WAIT_LIMIT_MS = 8L * 60L * 1_000L
    }
}
