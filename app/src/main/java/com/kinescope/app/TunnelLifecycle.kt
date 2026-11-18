package com.kinescope.app

import android.content.Context

/**
 * Patch 60: the rules that keep the YouTube tunnel up (or bring it back) without the user having to find out that it died.
 * Everything here is a pure decision; `BypassVpnService` applies it. Unit-tested in `TunnelLifecycleTest`.
 *
 * What a code audit found behind "it stops working every now and then and toggling it on does nothing":
 * - the Home switch itself ignores "on" while the controller says the tunnel is active or starting, so any stale state
 *   makes the switch dead; the states could go stale because the monitor thread died silently on the first unexpected
 *   exception (the tunnel then looked up but nothing watched it), because a start that hit an Error (not an Exception)
 *   killed the start thread and left the state at "starting" for ever, and because a stop followed within a second or two by a
 *   start went to the instance that was shutting down (the start was lost and the shutdown then wiped the state);
 * - a start that failed only because the connection was away (no route) or the engine did not come up yet stopped the whole
 *   service, and a tunnel whose strategies were all exhausted was stopped for good, so nothing came back when the network did;
 * - an engine session that was never closed (a stop racing the end of a route choice) kept the one VPN engine gate taken until
 *   the app process died, so every later start failed at once;
 * - when Android killed the app process, the tunnel was gone and nothing brought it back.
 */
internal enum class StartAction {
    /** Nothing runs and nothing is being started: begin. */
    BEGIN,

    /** The tunnel is up already: publish that and leave it alone. */
    ALREADY_UP,

    /** A start or a reconnect is in progress: leave it alone. */
    ALREADY_STARTING,

    /** A stop is still being carried out by this instance: start when it has finished (the instance is reused, not destroyed). */
    AFTER_STOP
}

internal object TunnelLifecycle {
    /** What a start request does, judged from the instance's own facts and never from the shared UI state alone. */
    fun onStartRequest(tunnelRunning: Boolean, startOrRestartInProgress: Boolean, stopInFlight: Boolean): StartAction = when {
        stopInFlight -> StartAction.AFTER_STOP
        tunnelRunning -> StartAction.ALREADY_UP
        startOrRestartInProgress -> StartAction.ALREADY_STARTING
        else -> StartAction.BEGIN
    }
}

/** Why a start failed, as far as retrying is concerned. */
internal enum class StartFailure {
    /** The connection was away or the engine did not come up in time: it may well work in a moment. */
    TRANSIENT,

    /** Nothing a retry changes: no strategy known, the YouTube app missing, the VPN permission gone. */
    PERMANENT
}

internal object StartRetry {
    /** Waits before the 1st, 2nd, ... retry of a failed start: about three minutes in all. */
    val BACKOFF_MS = longArrayOf(3_000L, 6_000L, 12_000L, 24_000L, 45_000L, 60_000L)

    /** Waits before the 1st, 2nd, 3rd restart of a tunnel whose every strategy was used up: it was most likely the network. */
    val EXHAUSTED_COOLDOWN_MS = longArrayOf(30_000L, 90_000L, 240_000L)

    /** The wait before retry number [retry] (1-based) of a failed start, or null when the retries are used up. */
    fun delayBeforeRetry(retry: Int): Long? = BACKOFF_MS.getOrNull(retry - 1)

    /** The cool-down before restart number [restart] (1-based) of an exhausted tunnel, or null when they are used up. */
    fun exhaustedCooldown(restart: Int): Long? = EXHAUSTED_COOLDOWN_MS.getOrNull(restart - 1)
}

/** What the monitor does about an exception thrown while it looked at the tunnel. */
internal enum class MonitorFault {
    /** One-off: keep watching. */
    IGNORE,

    /** It keeps happening: the tunnel is rebuilt. */
    REBUILD
}

internal object MonitorErrors {
    /** Consecutive ticks (half a second each) that threw before the tunnel is rebuilt: five seconds. */
    const val CONSECUTIVE_BEFORE_REBUILD = 10

    fun after(consecutive: Int): MonitorFault =
        if (consecutive >= CONSECUTIVE_BEFORE_REBUILD) MonitorFault.REBUILD else MonitorFault.IGNORE

    /** The first error and then every tenth is logged, so a loop of errors does not fill the log. */
    fun shouldLog(consecutive: Int): Boolean = consecutive == 1 || consecutive % CONSECUTIVE_BEFORE_REBUILD == 0
}

internal object ResumePolicy {
    /** At most this many automatic resumes after Android killed the app... */
    const val MAX_RESUMES = 3

    /** ... within this long, so a crash loop cannot keep restarting the service. */
    const val WINDOW_MS = 60L * 60L * 1000L

    /** [recent] are the wall-clock times of the resumes so far. */
    fun allow(recent: List<Long>, now: Long): Boolean = recent.count { now - it in 0L..WINDOW_MS } < MAX_RESUMES
}

/**
 * Patch 60: "the user wants the bypass on", kept across the death of the app process. Set when the user switches the bypass on,
 * cleared by every stop that is not Android killing the app (the user's stop, the VPN permission being taken away, a start that
 * gave up). While it is set, the service asks Android to restart it after a kill (`START_STICKY`) and resumes the tunnel.
 */
internal object BypassResume {
    private const val PREFS = "kinescope_bypass_runtime"
    private const val KEY_DESIRED = "desired"
    private const val KEY_RESUMES = "resumes"

    fun setDesired(context: Context, desired: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESIRED, desired).apply()
    }

    fun isDesired(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESIRED, false)

    /** True (and recorded) when the bypass is wanted and the resume limit is not used up. */
    fun shouldResume(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DESIRED, false)) return false
        val recent = prefs.getString(KEY_RESUMES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        if (!ResumePolicy.allow(recent, now)) return false
        val kept = (recent.filter { now - it in 0L..ResumePolicy.WINDOW_MS } + now).joinToString(",")
        prefs.edit().putString(KEY_RESUMES, kept).apply()
        return true
    }
}
