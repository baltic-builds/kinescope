package com.kinescope.app

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** What a forced refresh tests: [GOAL] until three ways are confirmed at 85 or more (Home), [FULL] every way of the list (Settings). */
internal enum class RefreshKind { GOAL, FULL }

/**
 * Patch 64: the refresh buttons (Home's, and "Test strategies" in Settings) are never locked. A download that hangs used to keep both
 * disabled for ever (a search needs the engine and the network to itself, and a running download held it), so the only way out was
 * to kill the app. Now a press does, in this order:
 *  1. pauses every queued and running download (a stuck one is interrupted after a few seconds and marked interrupted);
 *  2. stops a search that is already running;
 *  3. stops the YouTube tunnel (it is started again afterwards on the best way the refresh found);
 *  4. runs the refresh and waits for it to end;
 *  5. starts the tunnel again if it was on, and resumes the downloads from the queue.
 * Every wait is bounded, so one step that hangs cannot hold up the next. It runs on its own thread, not tied to a screen. A second
 * press while it runs does nothing (the search has its Stop button).
 */
internal object ForceRefresh {
    private val running = AtomicBoolean(false)

    private const val DOWNLOAD_WAIT_MS = 6_000L
    private const val HARD_WAIT_MS = 3_000L
    private const val SEARCH_WAIT_MS = 20_000L
    private const val TUNNEL_WAIT_MS = 10_000L
    private const val START_WAIT_MS = 15_000L
    private const val REFRESH_MAX_MS = 45L * 60L * 1000L
    private const val POLL_MS = 200L

    fun start(context: Context, kind: RefreshKind) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            AppLog.i("ForceRefresh", "already running")
            return
        }
        Thread({
            try {
                run(app, kind)
            } catch (e: Exception) {
                AppLog.e("ForceRefresh", "Forced refresh failed", e)
            } finally {
                running.set(false)
            }
        }, "kinescope-force-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private fun executing(context: Context): Boolean =
        DownloadJobStore.load(context).any { it.state in EXECUTING }

    private fun run(context: Context, kind: RefreshKind) {
        val tunnelWasOn = BypassVpnController.state.value.let { it.active || it.starting }
        val toResume = DownloadJobStore.load(context)
            .filter { it.state == JobState.QUEUED || it.state in EXECUTING }
            .sortedBy { it.createdAtMillis }
            .map { it.id }
        AppLog.i("ForceRefresh", "start kind=${kind.name.lowercase()} downloads=${toResume.size} tunnel=$tunnelWasOn")

        if (toResume.isNotEmpty()) {
            DownloadService.pauseAll(context, hard = false)
            if (!waitUntil(DOWNLOAD_WAIT_MS) { !executing(context) }) {
                AppLog.w("ForceRefresh", "a download did not stop; interrupting the worker")
                DownloadService.pauseAll(context, hard = true)
                DpiSearchController.clearHolds()
                waitUntil(HARD_WAIT_MS) { !executing(context) }
            }
        }
        if (DpiSearchController.state.value.running) {
            DpiSearchService.stop(context)
            if (!waitUntil(SEARCH_WAIT_MS) { !DpiSearchController.state.value.running }) AppLog.w("ForceRefresh", "the running search did not stop")
        }
        if (tunnelWasOn) {
            BypassVpnService.stop(context)
            waitUntil(TUNNEL_WAIT_MS) { BypassVpnController.state.value.let { !it.active && !it.starting } }
        }

        // The old result line would look like the end of the new search.
        DpiSearchController.idle()
        when (kind) {
            RefreshKind.GOAL -> DpiSearchService.startRefresh(context)
            RefreshKind.FULL -> DpiSearchService.startFull(context)
        }
        val started = waitUntil(START_WAIT_MS) { DpiSearchController.state.value.let { it.running || it.resultMessageRes != null } }
        if (started) waitUntil(REFRESH_MAX_MS) { !DpiSearchController.state.value.running }
        AppLog.i("ForceRefresh", "refresh ${if (started) "ended" else "did not start"}")

        // Back to what was running before, on the strategies the refresh left.
        if (tunnelWasOn && VpnService.prepare(context) == null) BypassVpnService.start(context)
        for (id in toResume) DownloadService.resume(context, id)
        AppLog.i("ForceRefresh", "restored tunnel=$tunnelWasOn downloads=${toResume.size}")
    }

    private val EXECUTING = setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)

    /** True when [condition] held before [timeoutMs] ran out. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }
}
