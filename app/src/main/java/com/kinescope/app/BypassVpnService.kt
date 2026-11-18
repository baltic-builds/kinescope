package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BypassVpnUiState(
    val starting: Boolean = false,
    val active: Boolean = false,
    @StringRes val errorRes: Int? = null
)

object BypassVpnController {
    private val mutableState = MutableStateFlow(BypassVpnUiState())
    val state = mutableState.asStateFlow()

    // Patch 45: what the Home dashboard shows about a running tunnel: the verdict of the last traffic
    // window and the strategy in use (patch 55: no speed any more). It belongs to one run of the tunnel, so every state change but "active"
    // (which the service follows with the strategy position) starts it again from nothing.
    private val mutableTraffic = MutableStateFlow(TunnelTraffic())
    internal val traffic = mutableTraffic.asStateFlow()

    // Patch 56: the strategy line the running tunnel carries traffic on (the refresh and the random check never test it) and the last
    // change the router made on its own, so Home can show that the router works. Unlike the traffic they survive a reconnect
    // (`starting()`): a switch is followed by exactly that; they end with the tunnel (`stopped()`, `failed()`).
    private val mutableLine = MutableStateFlow<String?>(null)
    internal val activeLine = mutableLine.asStateFlow()
    private val mutableEvent = MutableStateFlow<RouterEvent?>(null)
    internal val lastEvent = mutableEvent.asStateFlow()

    internal fun starting() {
        mutableState.value = BypassVpnUiState(starting = true)
        mutableTraffic.value = TunnelTraffic()
    }

    internal fun active() { mutableState.value = BypassVpnUiState(active = true) }

    internal fun stopped() {
        mutableState.value = BypassVpnUiState()
        mutableTraffic.value = TunnelTraffic()
        mutableLine.value = null
        mutableEvent.value = null
    }

    internal fun failed(@StringRes errorRes: Int) {
        mutableState.value = BypassVpnUiState(errorRes = errorRes)
        mutableTraffic.value = TunnelTraffic()
        mutableLine.value = null
        mutableEvent.value = null
    }

    internal fun publishLine(line: String?) {
        mutableLine.value = line
    }

    internal fun publishEvent(kind: RouterEventKind) {
        mutableEvent.value = RouterEvent(kind, System.currentTimeMillis())
    }

    internal fun publishPosition(position: Int, total: Int) {
        mutableTraffic.update { it.copy(position = position, total = total) }
    }

    internal fun publishVerdict(verdict: FlowVerdict) {
        mutableTraffic.update { it.copy(verdict = verdict) }
    }
}

/**
 * Local Android VPN transport used only for the one app the user chose to unblock (the official YouTube app by default,
 * patch 51: any app that opens YouTube links, see `YoutubeApps`). Traffic enters a TUN
 * interface, hev-socks5-tunnel forwards it to Kinescope's local ByeDPI SOCKS5 endpoint, and
 * ByeDPI opens the real network connection. No remote VPN server is involved.
 */
class BypassVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinescope-bypass-vpn").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)
    private val stopCleanupStarted = AtomicBoolean(false)

    // Patch 60 (see TunnelLifecycle): `myGeneration` tells this instance's threads whether a newer instance has taken over the shared
    // UI state (a late thread of a dying instance must not overwrite it); `startInProgress` and `startAfterStop` are the instance's own
    // facts a start request is judged by; `exhaustRetries` counts the cool-down restarts of an exhausted tunnel; the monitor counters
    // are touched by the monitor thread only.
    private val myGeneration = generation.incrementAndGet()
    private val startInProgress = AtomicBoolean(false)
    @Volatile private var startAfterStop = false
    private val lifecycleLock = Any()
    private var stopDecided = false
    @Volatile private var exhaustRetries = 0
    private var monitorErrors = 0
    private val monitorRebuilds = ArrayDeque<Long>()

    @Volatile private var engineSession: DpiEngineSession? = null
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var tunnelRunning = false

    // Patch 57: what the last TUN was given as DNS (`1.1.1.1`, or `system` when the phone's own Private DNS name applies).
    @Volatile private var tunnelDnsTag = "?"

    // Patch 32: "Update" in the notification re-tests the strategies inside this service and then
    // reconnects with the best result, without leaving the notification.
    @Volatile private var updating = false
    @Volatile private var restarting = false
    @Volatile private var updateProgress: SearchProgress? = null
    private val monitorRunning = AtomicBoolean(false)

    // Patch 35: the tunnel probes its own engine and switches strategy when it stops carrying traffic.
    @Volatile private var currentLine: String? = null
    @Volatile private var nextProbeAt = 0L
    @Volatile private var failStreak = 0
    @Volatile private var probeOkCount = 0
    private val rotations = ArrayDeque<Long>()

    // Patch 44: the traffic of the YouTube app through the tunnel, read from the bridge's own counters.
    @Volatile private var nextFlowCheckAt = 0L
    @Volatile private var flowPrevious = TunnelCounters.ZERO
    @Volatile private var oneWayStreak = 0
    @Volatile private var flowWindows = 0
    @Volatile private var tunnelStartedAt = 0L

    // Patch 62: the start time of the run whose usage time the strategy ledger already has (a run is recorded once, whoever stops it).
    @Volatile private var useRecordedFor = -1L

    // Patch 46: the strategy that already proved it carries real traffic in this tunnel run, so it is recorded once.
    @Volatile private var provedLine: String? = null

    // Patch 56: how many times the transport died early on a strategy since it last carried traffic (see TransportRecovery), and when
    // the calendar of the random check is next looked at while the tunnel runs. Touched by the monitor thread only (the executor).
    private val earlyDeaths = HashMap<String, Int>()

    // Patch 59: the seven-second rule (see TunnelStall): the monitor thread reads the bridge counters once a second and a
    // strategy that has left the app waiting for about seven seconds is switched. `spareSearchDone`: a chain with nothing left
    // to switch to gets ONE short search for spare strategies per tunnel run (see rotateStrategy).
    @Volatile private var nextStallSampleAt = 0L
    @Volatile private var stallPrevious = TunnelCounters.ZERO
    @Volatile private var stallPreviousAt = 0L
    @Volatile private var stallState = StallState()
    @Volatile private var spareSearchDone = false

    // Patch 63: when real data last reached the YouTube app through the tunnel (elapsed-realtime ms, 0 = not in this run); a failed probe
    // is judged against it (see ProbeJudge). Written by the monitor thread, read by it too.
    @Volatile private var lastRealRxAt = 0L

    // Patch 53: the connection class (Wi-Fi, mobile data) the tunnel's strategies were chosen for, and for how many monitor
    // ticks in a row the system has reported another one (see followConnection).
    @Volatile private var tunnelClass: NetClass = NetClass.WIFI
    @Volatile private var netSwitchStreak = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        NetClassWatcher.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
            }
            ACTION_START -> beginStart(resumed = false)
            ACTION_UPDATE -> {
                val state = BypassVpnController.state.value
                when {
                    !state.active && !state.starting -> stopSelf()
                    state.active && !updating && !restarting && !DpiSearchController.state.value.running -> {
                        updating = true
                        postNotification()
                        // Its own thread: the single-thread executor is occupied by the tunnel
                        // monitor for as long as the tunnel is up.
                        Thread({ updateStrategies() }, "kinescope-bypass-update").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            }
            ACTION_REBUILD -> {
                // Patch 64: the strategy was chosen by hand (Home, Settings): the running tunnel is taken down and built again on the new
                // choice (before, the old tunnel stayed up on the old strategy). Its own thread, like the update's.
                val state = BypassVpnController.state.value
                if (state.active && !updating && !restarting && !startInProgress.get() && !stopRequested.get()) {
                    AppLog.i("BypassVpnService", "rebuild: a strategy was chosen by hand")
                    Thread({
                        BypassVpnController.publishEvent(RouterEventKind.UPDATE)
                        restartTunnel()
                    }, "kinescope-bypass-rebuild").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
            ACTION_REPOST -> {
                // The user swiped the notification away (Android 14+ allows that for foreground
                // services): put it straight back while the bypass is running.
                val state = BypassVpnController.state.value
                if (state.active || state.starting) postNotification() else stopSelf()
            }
            null -> {
                // Patch 60: Android restarted the service after it had killed the app (START_STICKY). The tunnel is resumed when the user
                // still wanted it on, a few times an hour at most; otherwise there is nothing to do.
                if (intent == null && BypassResume.shouldResume(this)) {
                    AppLog.i("BypassVpnService", "resuming after the app was killed")
                    beginStart(resumed = true)
                } else {
                    stopSelf()
                }
            }
        }
        // Patch 60: while the user wants the bypass on, Android is asked to bring the service back if it kills the app.
        return if (BypassResume.isDesired(this)) START_STICKY else START_NOT_STICKY
    }

    /**
     * Patch 60: a request to start the tunnel, judged from this instance's own facts ([TunnelLifecycle.onStartRequest]) and not from the
     * shared UI state, which can be stale: an "already active" that is not, or a start that arrives while the previous run is still being
     * stopped (the old code handed it to the instance that was shutting down and the start was lost).
     */
    private fun beginStart(resumed: Boolean) {
        val action = TunnelLifecycle.onStartRequest(
            tunnelRunning = tunnelRunning,
            startOrRestartInProgress = startInProgress.get() || restarting,
            stopInFlight = stopCleanupStarted.get()
        )
        AppLog.i("BypassVpnService", "start request ${action.name.lowercase()} resumed=$resumed")
        when (action) {
            StartAction.ALREADY_UP -> {
                if (!BypassVpnController.state.value.active) {
                    BypassVpnController.active()
                    BypassVpnController.publishLine(currentLine)
                }
                postNotification()
            }
            StartAction.ALREADY_STARTING -> Unit
            StartAction.AFTER_STOP -> {
                // The stop thread decides under the same lock whether this instance is reused or ends: a request that comes after that
                // decision is handed to Android again and is served by the next instance.
                val tooLate = synchronized(lifecycleLock) {
                    if (stopDecided) true else { startAfterStop = true; false }
                }
                if (tooLate) {
                    AppLog.i("BypassVpnService", "start request came after the stop was decided; sending it again")
                    start(applicationContext)
                    return
                }
                BypassResume.setDesired(this, true)
                BypassVpnController.starting()
                if (!startForegroundCompat(buildNotification(active = false))) giveUpForeground()
            }
            StartAction.BEGIN -> {
                stopRequested.set(false)
                earlyDeaths.clear()
                spareSearchDone = false
                exhaustRetries = 0
                BypassResume.setDesired(this, true)
                BypassVpnController.starting()
                if (!startForegroundCompat(buildNotification(active = false))) {
                    giveUpForeground()
                    return
                }
                startInProgress.set(true)
                val queued = runCatching {
                    // A session left behind by an earlier run would keep the one VPN engine gate taken: nothing runs now, so none is wanted.
                    executor.execute {
                        yieldToSearch()
                        DpiEngine.reclaimVpn()
                        startTunnel()
                    }
                }
                if (queued.isFailure) {
                    startInProgress.set(false)
                    AppLog.e("BypassVpnService", "Could not queue the start", queued.exceptionOrNull())
                    fail(R.string.bypass_vpn_tunnel_failed)
                }
            }
        }
    }

    /** Patch 60: the system refused to make the service a foreground service (a start from the background on a recent Android). */
    private fun giveUpForeground() {
        AppLog.w("BypassVpnService", "could not start in the foreground")
        BypassResume.setDesired(this, false)
        if (isCurrent()) BypassVpnController.failed(R.string.bypass_vpn_tunnel_failed)
        stopSelf()
    }

    /** Patch 60: whether this instance is the newest one, the only one that may publish the shared UI state. */
    private fun isCurrent(): Boolean = generation.get() == myGeneration

    private fun publishStopped() {
        if (isCurrent()) BypassVpnController.stopped()
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    /** Stop is never queued behind monitorTunnel: it may be performing a blocking health probe. */
    private fun requestStop() {
        stopRequested.set(true)
        BypassResume.setDesired(this, false)
        runCatching { tunInterface?.close() }
        if (stopCleanupStarted.compareAndSet(false, true)) {
            Thread({
                try { stopTunnelInternal() } finally { finishStop() }
            }, "kinescope-bypass-stop").apply { isDaemon = true; start() }
        }
    }

    /**
     * Patch 60: the end of a stop. Normally the service ends. When the user asked for the bypass again while the stop was being carried
     * out ([startAfterStop]), this instance is reused: the start thread is let finish first (the stop flag is still set, so whatever it is
     * doing ends quickly), then everything is made ready again and the start runs.
     */
    private fun finishStop() {
        val reuse = synchronized(lifecycleLock) {
            if (startAfterStop) true else { stopDecided = true; false }
        }
        if (!reuse) {
            stopSelf()
            return
        }
        val drained = runCatching { executor.submit { }.get(DRAIN_TIMEOUT_S, TimeUnit.SECONDS) }.isSuccess
        if (!drained) {
            AppLog.w("BypassVpnService", "the previous run did not end in time; not restarting")
            startAfterStop = false
            BypassResume.setDesired(this, false)
            if (isCurrent()) BypassVpnController.failed(R.string.bypass_vpn_tunnel_failed)
            stopSelf()
            return
        }
        startAfterStop = false
        stopRequested.set(false)
        stopCleanupStarted.set(false)
        earlyDeaths.clear()
        spareSearchDone = false
        exhaustRetries = 0
        BypassResume.setDesired(this, true)
        BypassVpnController.starting()
        postNotification()
        startInProgress.set(true)
        val queued = runCatching {
            executor.execute {
                DpiEngine.reclaimVpn()
                startTunnel()
            }
        }
        if (queued.isFailure) {
            startInProgress.set(false)
            fail(R.string.bypass_vpn_tunnel_failed)
        }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        val preserveError = BypassVpnController.state.value.errorRes != null
        recordTunnelUse()
        runCatching { if (tunnelRunning || TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() }
        tunnelRunning = false
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { engineSession?.close() }
        engineSession = null
        if (!preserveError && isCurrent()) BypassVpnController.stopped()
        executor.shutdownNow()
        super.onDestroy()
    }

    /** Patch 60: how one start attempt ended (see [startTunnel]). */
    private sealed class StartOutcome {
        object Up : StartOutcome()
        object Stopped : StartOutcome()
        class Failed(@StringRes val errorRes: Int) : StartOutcome()
    }

    /**
     * Patch 60: a start, with retries. A start that failed only because the connection was away or the engine did not come up
     * ([failureKind]) is tried again after 5, 10, 20, 40 and 60 s ([StartRetry]) while the state stays "starting" and the service stays
     * up; the wait ends early when the connection is back and at once when a stop is asked for. Any other failure, and the last retry,
     * ends the service as before. Before, the first failed start stopped the service, so a tunnel switched on a moment before the
     * connection came back never came up and the user had to notice and switch it on again.
     */
    private fun startTunnel(avoidLine: String? = null) {
        startInProgress.set(true)
        try {
            var retry = 0
            while (true) {
                val outcome = startTunnelOnce(avoidLine)
                if (outcome !is StartOutcome.Failed) return
                val delay = if (failureKind(outcome.errorRes) == StartFailure.TRANSIENT && !stopRequested.get()) {
                    StartRetry.delayBeforeRetry(retry + 1)
                } else {
                    null
                }
                if (delay == null) {
                    fail(outcome.errorRes)
                    return
                }
                retry++
                AppLog.i("BypassVpnService", "start failed res=${outcome.errorRes}; retry $retry in ${delay / 1000L}s")
                BypassVpnController.starting()
                postNotification()
                if (!waitOrStop(delay)) return
            }
        } finally {
            startInProgress.set(false)
        }
    }

    /** Patch 60: the start failures that a moment may cure: no connection, or an engine that did not come up. */
    private fun failureKind(@StringRes errorRes: Int): StartFailure = when (errorRes) {
        R.string.bypass_vpn_no_route, R.string.bypass_vpn_engine_failed -> StartFailure.TRANSIENT
        else -> StartFailure.PERMANENT
    }

    /** Patch 60: waits [ms], ending early when the connection returns after having been away; false when a stop was asked for. */
    private fun waitOrStop(ms: Long): Boolean {
        val begun = SystemClock.elapsedRealtime()
        val offline = NetClassWatcher.state.value == null
        while (true) {
            if (stopRequested.get()) return false
            val waited = SystemClock.elapsedRealtime() - begun
            if (waited >= ms) return true
            if (offline && waited >= CONNECTION_BACK_MIN_WAIT_MS && NetClassWatcher.state.value != null) return true
            try {
                Thread.sleep(minOf(500L, ms - waited))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun startTunnelOnce(avoidLine: String?): StartOutcome {
        var engine: DpiEngineSession? = null
        var tun: ParcelFileDescriptor? = null
        var hevStarted = false
        try {
            // Patch 53: the strategies of the connection the phone is on now (a class with none borrows the other's).
            val cls = NetClassWatcher.current(this)
            var chain = DpiStrategyStore.chain(this, cls)
            if (chain.isEmpty()) {
                return StartOutcome.Failed(R.string.bypass_vpn_no_verified_strategy)
            }
            // Patch 51: the tunnel carries the chosen app (Settings), not always the official YouTube app.
            val appPackage = YoutubeApps.chosenPackage(this)
            if (appPackage == null) {
                return StartOutcome.Failed(R.string.bypass_vpn_youtube_missing)
            }

            // Select the first live, healthy route on this network class. Merely starting a
            // SOCKS engine does NOT prove the restriction can be bypassed; never report an
            // active VPN using a strategy whose health check just failed.
            // A pinned manual choice leads, but failed choices are still skipped.
            // Patch 54: see selectRoute -- a connection the engine cannot reach is told from a blocked strategy, and a class that
            // was never tested gets a short search of its own when its borrowed strategies are blocked.
            val route = selectRoute(cls, chain, avoidLine)
            // Patch 60: a session the choice returned is owned from here on, so every way out below closes it (a stop that came in
            // between used to leave it open, and an open session keeps the one VPN engine gate taken until the app process dies).
            engine = route.engine
            if (stopRequested.get()) return StartOutcome.Stopped
            if (route.noRoute) throw BypassStartException(R.string.bypass_vpn_no_route)
            if (engine == null) throw BypassStartException(R.string.bypass_vpn_engine_failed)
            val chosenLine: String? = route.line
            chain = route.chain

            tun = buildAppTunnel(appPackage)
                ?: throw BypassStartException(R.string.bypass_vpn_tunnel_failed)
            val config = writeTunnelConfig(engine.port)
            if (!TProxyService.TProxyStartService(config.absolutePath, tun.fd)) {
                throw BypassStartException(R.string.bypass_vpn_tunnel_failed)
            }
            hevStarted = true

            var running = false
            for (attempt in 0 until 20) {
                if (stopRequested.get()) break
                if (TProxyService.TProxyIsRunning()) {
                    running = true
                    break
                }
                Thread.sleep(50L)
            }
            // Patch 44: the bridge reports "running" the moment its thread exists, before it has read its
            // configuration, and a configuration it rejects ends that thread within milliseconds. Look again after
            // a short settle time, so a bridge that died at once is an error and not a silent "starting".
            if (running && !stopRequested.get()) {
                Thread.sleep(BRIDGE_SETTLE_MS)
                running = TProxyService.TProxyIsRunning()
            }
            if (stopRequested.get()) return StartOutcome.Stopped
            if (!running) {
                AppLog.w("BypassVpnService", "tun bridge is not running after start")
                throw BypassStartException(R.string.bypass_vpn_tunnel_failed)
            }

            engineSession = engine
            tunInterface = tun
            tunnelRunning = true
            tunnelClass = cls
            netSwitchStreak = 0
            currentLine = chosenLine
            failStreak = 0
            val startedNow = SystemClock.elapsedRealtime()
            nextProbeAt = startedNow + FIRST_PROBE_DELAY_MS
            tunnelStartedAt = startedNow
            nextFlowCheckAt = startedNow + FLOW_INTERVAL_MS
            flowPrevious = TunnelCounters.ZERO
            oneWayStreak = 0
            flowWindows = 0
            provedLine = null
            // Patch 59: the bridge's counters start from zero again; so does the seven-second watch.
            stallPrevious = TunnelCounters.ZERO
            stallPreviousAt = startedNow
            lastRealRxAt = 0L
            stallState = StallState()
            nextStallSampleAt = startedNow + TunnelStall.SAMPLE_MS
            engine = null
            tun = null
            hevStarted = false
            nextUpkeepAt = startedNow + UPKEEP_INTERVAL_MS
            BypassVpnController.active()
            BypassVpnController.publishLine(chosenLine)
            BypassVpnController.publishPosition(chain.indexOf(chosenLine) + 1, chain.size)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(active = true))
            AppLog.i(
                "BypassVpnService",
                "tun up mtu=$TUN_MTU v6=0 dns=$tunnelDnsTag udp=udp net=${cls.key} s=${chain.indexOf(chosenLine) + 1}/${chain.size} " +
                    "app=${if (appPackage == YoutubeApps.DEFAULT_PACKAGE) "yt" else "other"}"
            )
            AppLog.i("BypassVpnService", "YouTube-only bypass active")
            if (monitorRunning.compareAndSet(false, true)) executor.execute { monitorTunnel() }
            return StartOutcome.Up
        } catch (e: BypassStartException) {
            return StartOutcome.Failed(e.errorRes)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return if (stopRequested.get()) StartOutcome.Stopped else StartOutcome.Failed(R.string.bypass_vpn_tunnel_failed)
        } catch (e: PackageManager.NameNotFoundException) {
            return StartOutcome.Failed(R.string.bypass_vpn_youtube_missing)
        } catch (e: Exception) {
            AppLog.e("BypassVpnService", "Could not start YouTube bypass", e)
            return StartOutcome.Failed(R.string.bypass_vpn_tunnel_failed)
        } catch (e: Throwable) {
            // Patch 60: an Error (out of memory, a native library that did not load) used to end the start thread silently and leave the
            // state at "starting" for ever, which the Home switch treats as "already on".
            AppLog.e("BypassVpnService", "Could not start YouTube bypass (error)", e)
            return StartOutcome.Failed(R.string.bypass_vpn_tunnel_failed)
        } finally {
            if (engine != null || tun != null || hevStarted) {
                if (hevStarted) runCatching { TProxyService.TProxyStopService() }
                runCatching { tun?.close() }
                runCatching { engine?.close() }
            }
            if (stopRequested.get() && !BypassVpnController.state.value.active) {
                publishStopped()
                if (!startAfterStop) stopSelf()
            }
        }
    }

    /**
     * Patch 64: "Watch YouTube" while a quiet strategy search (the automatic update, the refresh) is running. The search drives up to
     * dozens of connections through its own engine on the same network, and the tunnel's start-up checks, which have a few seconds each,
     * failed under that load: the tunnel then rotated through its chain, learned bad marks, or gave up, and the user had to stop the
     * search and start again by hand. Now the start stops the search first and waits (bounded) for it to end; what the search had
     * found so far is kept. If it has not ended in [SEARCH_YIELD_MS] the start goes on beside it, as before.
     */
    private fun yieldToSearch() {
        if (!DpiSearchController.state.value.running) return
        AppLog.i("BypassVpnService", "start: a strategy search is running; stopping it first")
        DpiSearchService.stop(applicationContext)
        val deadline = SystemClock.elapsedRealtime() + SEARCH_YIELD_MS
        while (DpiSearchController.state.value.running && !stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(200L)
        }
        AppLog.i(
            "BypassVpnService",
            if (DpiSearchController.state.value.running) "start: the search is still running; going on beside it" else "start: the search has stopped"
        )
    }

    /** Patch 54: the outcome of choosing the strategy a tunnel starts on. */
    private class RouteChoice(val engine: DpiEngineSession?, val line: String?, val chain: List<String>, val noRoute: Boolean)

    /**
     * Patch 54: the first strategy of [startChain] (or, failing that, of a chain found on demand) whose live check passes.
     *
     * A check that fails at CONNECT with "unreachable" never got to the strategy: the engine could not reach the host. The first
     * time that happens, [DpiBypass.reach] decides whether the cause is IPv6 (the class is then remembered as IPv4-only and the
     * chain is tried again from the start) or the connection itself (no route: the start stops at once, nothing is blamed). Such
     * a failure is never counted against a strategy. Strategies that failed at the TLS or HTTP stage are blocked ones; when every
     * one of them is, the class's chain is probably just not meant for this connection (a chain borrowed from the other class,
     * patch 53), so one short search runs for the class and its result is tried.
     */
    private fun selectRoute(cls: NetClass, startChain: List<String>, avoidLine: String? = null): RouteChoice {
        // Patch 58: a failure-driven restart must not select the route that just failed again.
        var chain = TunnelRotation.withoutFailed(startChain, avoidLine)
        var reachChecked = false
        var searched = false
        while (true) {
            val failedLines = mutableListOf<String>()
            var reachFaults = 0
            var restart = false
            for ((position, line) in chain.withIndex()) {
                val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
                if (stopRequested.get()) return RouteChoice(null, null, chain, false)
                val candidate = DpiEngine.startForVpn(this, parsed.args) ?: continue
                val health = try {
                    DpiBypass.checkHealth(candidate.port)
                } catch (e: Throwable) {
                    // Patch 60: an open session keeps the one VPN engine gate taken until the app process dies.
                    candidate.close()
                    throw e
                }
                AppLog.i(
                    "BypassVpnService",
                    "chk s=${position + 1}/${chain.size} ok=${health.ok} st=${health.stage} why=${health.why} " +
                        "rc=${health.replyCode ?: "-"} ms=${health.ms}"
                )
                if (TunnelStartupPolicy.accepts(health.ok, stopRequested.get())) {
                    // A confirmed working fallback is evidence that these routes failed on THIS network.
                    // Patch 61: failing the one-host start-up check is weak evidence (SOFT_BAD), not a failure in real use.
                    TunnelStartupPolicy.failuresToLearn(failedLines, line).forEach {
                        DpiPrefs.quarantine(this, cls, it, outcome = StrategyOutcome.SOFT_BAD)
                    }
                    return RouteChoice(candidate, line, chain, false)
                }
                candidate.close()
                if (stopRequested.get()) return RouteChoice(null, null, chain, false)
                if (health.reachFault) {
                    reachFaults++
                    if (!reachChecked) {
                        reachChecked = true
                        when (DpiBypass.reach(this, cls, line) { stopRequested.get() }) {
                            Reach.NO_ROUTE -> return RouteChoice(null, null, chain, true)
                            Reach.IPV4_ONLY -> {
                                restart = true
                                break
                            }
                            else -> Unit
                        }
                    }
                    continue
                }
                failedLines.add(line)
            }
            if (restart) continue
            if (stopRequested.get()) return RouteChoice(null, null, chain, false)
            // Every strategy failed before it could be judged: the connection, not the chain, is the problem.
            if (failedLines.isEmpty() && reachFaults > 0) return RouteChoice(null, null, chain, true)
            if (!searched && failedLines.isNotEmpty()) {
                searched = true
                val found = searchOnDemand(cls)
                val usable = TunnelRotation.withoutFailed(found, avoidLine)
                if (usable.isNotEmpty() && usable != chain) {
                    chain = usable
                    continue
                }
            }
            return RouteChoice(null, null, chain, false)
        }
    }

    /**
     * Patch 54: a short search for [cls] while the tunnel is starting: it stops at [SearchGoal.QUICK] (two working strategies), its
     * results are stored for the class and the chain it produced is returned (empty when nothing was found, the search was stopped
     * or another search is already running). The notification shows its progress like the "Update" action does.
     */
    private fun searchOnDemand(cls: NetClass): List<String> {
        if (DpiSearchController.state.value.running) return emptyList()
        updating = true
        postNotification()
        try {
            val results = DpiBypass.search(
                applicationContext,
                cls,
                isCancelled = { stopRequested.get() },
                onProgress = { snapshot ->
                    updateProgress = snapshot
                    postNotification()
                },
                goal = SearchGoal.QUICK
            )
            if (stopRequested.get()) return emptyList()
            if (!DpiBypass.recordSearch(applicationContext, cls, results)) return emptyList()
            if (!DpiBypass.applySearchResults(applicationContext, cls, results)) {
                AppLog.i("BypassVpnService", "start search net=${cls.key} found nothing")
                return emptyList()
            }
            AppLog.i("BypassVpnService", "start search net=${cls.key} found=${results.count { it.fullPass }}")
            return DpiStrategyStore.chain(applicationContext, cls)
        } catch (e: Exception) {
            AppLog.e("BypassVpnService", "Start search failed", e)
            return emptyList()
        } finally {
            updating = false
            updateProgress = null
            postNotification()
        }
    }

    private fun monitorTunnel() {
        try {
            var ticks = 0
            while (!stopRequested.get()) {
                Thread.sleep(500L)
                try {
                    if (!monitorTick(++ticks)) return
                    monitorErrors = 0
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // Patch 60: one failed look used to end the monitor for good, silently: the tunnel then looked up while nothing watched
                    // it, so a dead engine or a stalled strategy was never noticed and the Home switch (which ignores "on" while the
                    // state says active) could not bring it back.
                    monitorErrors++
                    if (MonitorErrors.shouldLog(monitorErrors)) {
                        AppLog.e("BypassVpnService", "monitor tick failed ($monitorErrors in a row)", e)
                    }
                    if (MonitorErrors.after(monitorErrors) == MonitorFault.REBUILD) {
                        monitorErrors = 0
                        rebuildAfterMonitorFault()
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            monitorRunning.set(false)
            // Patch 60: the watch is never left dead while the tunnel is up (an exit through an Error, or a stop that was withdrawn at
            // that very moment).
            if (!stopRequested.get() && tunnelRunning && monitorRunning.compareAndSet(false, true)) {
                runCatching { executor.execute { monitorTunnel() } }.onFailure { monitorRunning.set(false) }
            }
        }
    }

    /** Patch 60: one look at the tunnel (every half second). False: the monitor ends because a stop was asked for. */
    private fun monitorTick(tick: Int): Boolean {
        if (restarting) return true
        // Patch 56: a stop closes the TUN and the bridge at once; waking up in the middle of it, the old code took the dead
        // transport for a failure and blamed the strategy that was carrying the traffic. A stop is never a failure.
        if (stopRequested.get()) return false
        val engineAlive = engineSession?.isAlive == true
        val bridgeAlive = runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
        if (!engineAlive || !bridgeAlive) {
            // `restarting` is set before the old engine is closed, so seeing a closed
            // engine here means this flag is already visible: an update-triggered
            // reconnect is not a failure.
            if (restarting) return true
            handleTransportDeath(engineAlive, bridgeAlive)
            return !stopRequested.get()
        }
        if (tick % NOTIFICATION_CHECK_TICKS == 0) ensureNotificationVisible()
        if (!updating && !restarting) followConnection()
        if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextStallSampleAt) {
            checkTunnelStall()
            if (stopRequested.get()) return false
        }
        if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextFlowCheckAt) checkTunnelFlow()
        if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextProbeAt) probeTunnel()
        return true
    }

    /**
     * Patch 60: the monitor failed ten ticks in a row. The tunnel is rebuilt from nothing (the same path as a reconnect); if that does
     * not help either, the third rebuild within ten minutes gives up and says so instead of looping.
     */
    private fun rebuildAfterMonitorFault() {
        val now = SystemClock.elapsedRealtime()
        while (monitorRebuilds.isNotEmpty() && now - monitorRebuilds.first() > RotationLimits.WINDOW_MS) monitorRebuilds.removeFirst()
        when (RotationLimits.decide(monitorRebuilds, now, chainSize = MONITOR_REBUILDS, alternatives = 1)) {
            RotationDecision.TOO_SOON -> return
            RotationDecision.EXHAUSTED -> {
                AppLog.w("BypassVpnService", "the monitor keeps failing; giving up")
                BypassVpnController.failed(R.string.bypass_vpn_tunnel_failed)
                requestStop()
                return
            }
            RotationDecision.GO -> Unit
        }
        monitorRebuilds.addLast(now)
        AppLog.w("BypassVpnService", "the monitor keeps failing; rebuilding the tunnel")
        BypassVpnController.publishEvent(RouterEventKind.RECONNECTED)
        restartTunnel()
    }

    /**
     * Patch 56: the engine or the bridge stopped and nobody asked it to. [TransportRecovery] decides: a stop the user asked for ends
     * here (nothing is blamed), the first early death of a strategy is answered with a reconnect on the same strategy (the live check
     * of the start-up still has to pass), and a second early death before the strategy has carried traffic blames it and moves on, as a
     * failed probe does. A transport that had run for two minutes is reconnected and never counted. The line says which side died, so
     * the next report shows whether the engine process or the bridge is the one that goes.
     */
    private fun handleTransportDeath(engineAlive: Boolean, bridgeAlive: Boolean) {
        val line = currentLine
        val runMs = SystemClock.elapsedRealtime() - tunnelStartedAt
        val deaths = line?.let { earlyDeaths[it] } ?: 0
        val fate = TransportRecovery.decide(stopRequested.get(), runMs, deaths)
        AppLog.w(
            "BypassVpnService",
            "transport stopped unexpectedly engine=${if (engineAlive) "up" else "down"} bridge=${if (bridgeAlive) "up" else "down"} " +
                "run=${runMs / 1000L}s deaths=$deaths fate=${fate.name.lowercase()}"
        )
        when {
            fate == TransportFate.STOPPED_BY_USER -> return
            line == null -> {
                BypassVpnController.failed(R.string.bypass_vpn_tunnel_failed)
                requestStop()
            }
            fate == TransportFate.RESTART_SAME -> {
                earlyDeaths[line] = TransportRecovery.deathsAfter(deaths, runMs, fate)
                BypassVpnController.publishEvent(RouterEventKind.RECONNECTED)
                restartTunnel()
            }
            else -> {
                earlyDeaths.remove(line)
                rotateStrategy(line, RouterEventKind.TRANSPORT)
            }
        }
    }

    /**
     * Patch 53: the connection changed from Wi-Fi to mobile data or back. The strategies that carry traffic on one are no
     * guide on the other, so once the system has reported the other class for [NetSwitch.STABLE_TICKS] ticks in a row (a
     * handover flickers) the tunnel reconnects on the strategies of the new class ([restartTunnel] reads the class again).
     * Not a failure: it is not counted against the rotation limit and nothing is quarantined. No connection at all
     * changes nothing; the tunnel waits for one.
     */
    private fun followConnection() {
        val seen = NetClassWatcher.state.value
        netSwitchStreak = NetSwitch.streakAfter(netSwitchStreak, tunnelClass, seen)
        if (!NetSwitch.shouldSwitch(netSwitchStreak)) return
        AppLog.i("BypassVpnService", "net change ${tunnelClass.key}->${seen?.key}: reconnecting on that class's strategies")
        netSwitchStreak = 0
        BypassVpnController.publishEvent(RouterEventKind.NETWORK)
        restartTunnel()
    }

    /**
     * Re-tests every strategy while the tunnel keeps running, then reconnects with the new
     * verified chain -- but only if that chain differs from the current one. Nothing found, or
     * the same chain: the running connection is left alone.
     */
    private fun updateStrategies() {
        try {
            // Patch 53: the update tests the connection the tunnel runs on and stores its findings for that class.
            val cls = tunnelClass
            val results = DpiBypass.search(
                applicationContext,
                cls,
                isCancelled = { stopRequested.get() },
                onProgress = { snapshot ->
                    updateProgress = snapshot
                    postNotification()
                }
            )
            if (stopRequested.get()) return
            val previousChain = DpiStrategyStore.verifiedChain(applicationContext, cls)
            if (!DpiBypass.recordSearch(applicationContext, cls, results)) return
            // Patch 33: ranked (real transfer first) instead of the first passes in list order.
            if (!DpiBypass.applySearchResults(applicationContext, cls, results)) {
                AppLog.i("BypassVpnService", "Strategy update found nothing; keeping the current connection")
                return
            }
            if (DpiStrategyStore.verifiedChain(applicationContext, cls) == previousChain) {
                AppLog.i("BypassVpnService", "Strategy update found the same methods; keeping the connection")
                return
            }
            BypassVpnController.publishEvent(RouterEventKind.UPDATE)
            restartTunnel()
        } catch (e: Exception) {
            AppLog.e("BypassVpnService", "Strategy update failed", e)
        } finally {
            updating = false
            updateProgress = null
            if (!stopRequested.get() && BypassVpnController.state.value.active) postNotification()
        }
    }

    /**
     * Patch 35: every 30 s, three simultaneous connections through the tunnel's own engine (not
     * through the TUN, so the YouTube app's traffic is not disturbed). Fewer than two completing,
     * twice in a row, means the strategy has stopped carrying traffic.
     */
    private fun probeTunnel() {
        nextProbeAt = SystemClock.elapsedRealtime() + PROBE_INTERVAL_MS
        val session = engineSession ?: return
        val line = currentLine ?: return
        if (NetworkState.snapshot(this).kind == "none") return
        val load = DpiBypass.checkLoad(session.port, PROBE_CONNECTIONS)
        // Patch 63: a failed probe is only a synthetic witness. Real data that reached the app a moment ago, or a dead system name lookup
        // (the probe connects by name), outweigh it; see ProbeJudge for the report this comes from.
        val sinceRealData = lastRealRxAt.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
        val verdict = ProbeJudge.judge(load.passed, load.total, sinceRealData) { systemLookupWorks() }
        when (verdict) {
            ProbeVerdict.OK -> {
                failStreak = 0
                probeOkCount++
                if (probeOkCount == 1 || probeOkCount % 10 == 0) {
                    AppLog.i("BypassVpnService", "probe ok n=$probeOkCount ${load.passed}/${load.total} hs=${load.medianHandshakeMs}ms")
                }
            }
            ProbeVerdict.IGNORED_TRAFFIC, ProbeVerdict.IGNORED_DNS -> {
                failStreak = 0
                AppLog.i(
                    "BypassVpnService",
                    "probe ignored why=${if (verdict == ProbeVerdict.IGNORED_TRAFFIC) "traffic" else "dns"} " +
                        "${load.passed}/${load.total} hs=${load.medianHandshakeMs}ms data=${sinceRealData?.let { "${it / 1000L}s" } ?: "-"}"
                )
            }
            ProbeVerdict.FAIL -> {
                failStreak++
                AppLog.w("BypassVpnService", "probe fail streak=$failStreak ${load.passed}/${load.total} hs=${load.medianHandshakeMs}ms")
                if (failStreak >= FAILS_BEFORE_ROTATE) rotateStrategy(line, RouterEventKind.PROBE)
            }
        }
    }

    /**
     * Patch 63: whether the phone's own name lookup works right now (one bounded lookup of the probe host). A lookup that fails turns the
     * address fallback on ([DnsFallback]), so the next probe and the next start connect by address instead of failing by name.
     */
    private fun systemLookupWorks(): Boolean = try {
        DnsFallback.remember(NetworkCheck.DEFAULT_HOST, resolveBounded(NetworkCheck.DEFAULT_HOST, LOOKUP_TIMEOUT_MS))
        if (DnsFallback.active()) DnsFallback.clear()
        true
    } catch (e: java.io.IOException) {
        if (!DnsFallback.active()) AppLog.w("BypassVpnService", "dns fallback on: the system lookup is dead, probes connect by address")
        DnsFallback.mark()
        false
    }

    /**
     * Patch 44: every [FLOW_INTERVAL_MS], what the YouTube app sent into the tunnel and what came back, from the
     * bridge's own counters. [probeTunnel] talks to the engine directly, so it cannot see a failure between the
     * app and the engine (the TUN, the bridge, name lookups, UDP); this can. Three one-way windows in a row (the
     * app keeps sending, almost nothing real comes back) switch the strategy through the same rate-limited path
     * as a failed probe.
     */
    private fun checkTunnelFlow() {
        nextFlowCheckAt = SystemClock.elapsedRealtime() + FLOW_INTERVAL_MS
        val line = currentLine ?: return
        val current = TunnelCounters.fromStats(runCatching { TProxyService.TProxyGetStats() }.getOrNull()) ?: return
        val previous = flowPrevious
        val verdict = TunnelFlow.judge(previous, current)
        BypassVpnController.publishVerdict(verdict)
        flowPrevious = current
        flowWindows++
        oneWayStreak = TunnelFlow.streakAfter(oneWayStreak, verdict)
        // One line per window with traffic, plus the first window: a quiet tunnel does not fill the log.
        if (verdict != FlowVerdict.IDLE || flowWindows == 1) {
            AppLog.i(
                "BypassVpnService",
                "tun win=${FLOW_INTERVAL_MS / 1000}s v=${verdict.name.lowercase()} " +
                    "tx=${current.txPackets - previous.txPackets}p/${current.txBytes - previous.txBytes}B " +
                    "rx=${current.rxPackets - previous.rxPackets}p/${current.rxBytes - previous.rxBytes}B streak=$oneWayStreak"
            )
        }
        // Patch 46: a window in which real data came back to the YouTube app is proof that this strategy carries real
        // traffic. Remember it once per tunnel run; the chain then puts it first and keeps the others in order of success.
        if (provedLine != line && TunnelFlow.proves(previous, current)) {
            provedLine = line
            earlyDeaths.remove(line)
            exhaustRetries = 0
            DpiPrefs.promoteVerified(this, tunnelClass, line)
            val chain = DpiStrategyStore.chain(this, tunnelClass)
            BypassVpnController.publishPosition(chain.indexOf(line) + 1, chain.size)
            AppLog.i("BypassVpnService", "tun proven net=${tunnelClass.key} s=${chain.indexOf(line) + 1}/${chain.size}")
        }
        if (TunnelFlow.shouldRotate(oneWayStreak)) {
            AppLog.w("BypassVpnService", "tun one-way windows=$oneWayStreak: the app sends, nothing comes back")
            oneWayStreak = 0
            rotateStrategy(line, RouterEventKind.ONE_WAY)
        }
    }

    /**
     * Patch 59: the seven-second rule ([TunnelStall]). Once a second the bridge counters are compared with the last reading; an
     * app that has been waiting about seven seconds for an answer that does not come moves the tunnel to the next strategy, through
     * the same path as a failed probe ([rotateStrategy]: the strategy is quarantined and the restart excludes it). A phone with no
     * connection at all is not the strategy's fault: nothing is switched then.
     */
    private fun checkTunnelStall() {
        val now = SystemClock.elapsedRealtime()
        nextStallSampleAt = now + TunnelStall.SAMPLE_MS
        val line = currentLine ?: return
        val current = TunnelCounters.fromStats(runCatching { TProxyService.TProxyGetStats() }.getOrNull()) ?: return
        // Patch 63: a sample that brought real data back to the app is the best proof the strategy carries traffic right now.
        if (current.rxBytes - stallPrevious.rxBytes >= ProbeJudge.REAL_RX_BYTES) lastRealRxAt = now
        val step = TunnelStall.step(stallState, stallPrevious, current, stallPreviousAt, now)
        stallPrevious = current
        stallPreviousAt = now
        stallState = step.state
        val report = step.report
        if (step.verdict != StallVerdict.STALLED || report == null) return
        // Patch 61: a strategy that already carried real data in this run is not left because one quiet or blocked connection waited;
        // the probe and the one-way windows still catch a strategy that really died.
        if (provedLine == line) {
            AppLog.i("BypassVpnService", "stall ignored: proved in this run wait=${report.ms / 1000L}s")
            return
        }
        if (NetworkState.snapshot(this).kind == "none") {
            AppLog.i("BypassVpnService", "stall ignored: no connection")
            return
        }
        AppLog.w(
            "BypassVpnService",
            "stall net=${tunnelClass.key} wait=${report.ms / 1000L}s tx=${report.txPackets}p/${report.txBytes}B rx=${report.rxBytes}B"
        )
        rotateStrategy(line, RouterEventKind.ONE_WAY, "stall")
    }

    /** Patch 44: one line with the whole session's traffic, written when the tunnel is stopped. */
    private fun logTunnelTotals() {
        val totals = TunnelCounters.fromStats(runCatching { TProxyService.TProxyGetStats() }.getOrNull()) ?: return
        val seconds = (SystemClock.elapsedRealtime() - tunnelStartedAt) / 1000L
        AppLog.i(
            "BypassVpnService",
            "tun end up=${seconds}s tx=${totals.txPackets}p/${totals.txBytes}B rx=${totals.rxPackets}p/${totals.rxBytes}B"
        )
    }

    /**
     * Quarantines the failing strategy and reconnects on the next one; rate-limited so a dead network cannot thrash it. [kind] is why,
     * for the dashboard (`BypassVpnController.lastEvent`). Patch 56: nothing happens once a stop was asked for.
     */
    private fun rotateStrategy(line: String, kind: RouterEventKind, why: String = kind.name.lowercase()) {
        if (stopRequested.get()) return
        val cls = tunnelClass
        var chain = DpiStrategyStore.chain(this, cls)
        var alternatives = chain.count { it != line }
        // Patch 59: a chain with nothing left to switch to gets one short search for spare strategies per tunnel run.
        if (alternatives == 0 && !spareSearchDone) {
            spareSearchDone = true
            chain = searchOnDemand(cls).ifEmpty { chain }
            if (stopRequested.get()) return
            alternatives = chain.count { it != line }
        }
        val now = SystemClock.elapsedRealtime()
        while (rotations.isNotEmpty() && now - rotations.first() > RotationLimits.WINDOW_MS) rotations.removeFirst()
        when (RotationLimits.decide(rotations, now, chain.size, alternatives)) {
            RotationDecision.EXHAUSTED -> {
                // Patch 60: every strategy of the chain was used up. That is most often the network (a lift, a tunnel, a Wi-Fi that lost its
                // uplink), not the strategies, and it used to end the bypass for good. Now the tunnel is taken down (the app goes direct
                // meanwhile), the history is cleared and it reconnects after 1, 2 and 5 minutes before it gives up.
                val cooldown = StartRetry.exhaustedCooldown(exhaustRetries + 1)
                if (cooldown != null) {
                    exhaustRetries++
                    AppLog.w("BypassVpnService", "all recovery routes exhausted; reconnecting in ${cooldown / 1000L}s (try $exhaustRetries)")
                    rotations.clear()
                    spareSearchDone = false
                    failStreak = 0
                    restartAfterCooldown(cooldown)
                    return
                }
                AppLog.w("BypassVpnService", "all recovery routes exhausted; stopping unusable VPN")
                BypassVpnController.failed(R.string.bypass_vpn_engine_failed)
                requestStop()
                return
            }
            RotationDecision.TOO_SOON -> {
                failStreak = 0
                return
            }
            RotationDecision.GO -> Unit
        }
        rotations.addLast(now)
        // Patch 61: a stall (the app waited, nothing came back) is weak evidence; a failed probe, a one-way window or a dead transport is not.
        val outcome = if (why == "stall") StrategyOutcome.SOFT_BAD else StrategyOutcome.REAL_BAD
        DpiPrefs.quarantine(this, cls, line, outcome = outcome)
        AppLog.w("BypassVpnService", "rotate net=${cls.key} why=$why quarantined=1 alternatives=$alternatives")
        failStreak = 0
        BypassVpnController.publishEvent(kind)
        restartTunnel(avoidLine = line)
    }

    /** Patch 60: takes the tunnel down, waits [delayMs] (a stop ends the wait), and starts it again. */
    private fun restartAfterCooldown(delayMs: Long) {
        restarting = true
        try {
            stopTunnelInternal(publishStopped = false)
            if (stopRequested.get()) {
                publishStopped()
                if (!startAfterStop) stopSelf()
                return
            }
            BypassVpnController.starting()
            postNotification()
            if (!waitOrStop(delayMs)) return
            startTunnel()
        } finally {
            restarting = false
        }
    }

    private fun restartTunnel(avoidLine: String? = null) {
        restarting = true
        try {
            stopTunnelInternal(publishStopped = false)
            if (stopRequested.get()) {
                publishStopped()
                if (!startAfterStop) stopSelf()
                return
            }
            BypassVpnController.starting()
            postNotification()
            startTunnel(avoidLine)
        } finally {
            restarting = false
        }
    }

    private fun postNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(
                    active = BypassVpnController.state.value.active,
                    progress = updateProgress,
                    updating = updating
                )
            )
        }
    }

    /** Self-heal for Android 14+, where users can dismiss a foreground service's notification. */
    private fun ensureNotificationVisible() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.activeNotifications.none { it.id == NOTIFICATION_ID }) postNotification()
        }
    }

    /** Patch 51: a tunnel that lets exactly one app through, the one the user chose (YouTube unless changed in Settings). */
    private fun buildAppTunnel(appPackage: String): ParcelFileDescriptor? {
        // Patch 57, ByeByeDPI's defaults: the tunnel is IPv4-only. `VpnService.Builder` blocks a family for the apps it carries when no
        // address, route or DNS server of that family is added, so the app uses IPv4 at once instead of an IPv6 path the engine may
        // not be able to use (patch 54's reach fault). Adding ANY IPv6 address, route or DNS server would allow the family again.
        // DNS: the fixed resolver, unless the phone has a Private DNS name: then none is added and Android uses the default
        // network's DNS, where that name takes effect (`PrivateDns`).
        val dns = PrivateDns.tunnelDnsServer(PrivateDns.state(this), Settings.getTunnelDns(this))
        tunnelDnsTag = dns ?: "system"
        val builder = Builder()
            .setSession(getString(R.string.bypass_title))
            .setMtu(TUN_MTU)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication(appPackage)
        if (dns != null) builder.addDnsServer(dns)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
    }

    private fun writeTunnelConfig(port: Int): File {
        val file = File(cacheDir, "hev-youtube-bypass.yml")
        // Patch 57: the text is built by the pure `hevTunnelConfig` (IPv4 only), which a unit test pins.
        file.writeText(hevTunnelConfig(port, TUN_MTU))
        return file
    }

    /** Patch 62: adds the time this run of the tunnel spent on its strategy to the strategy ledger, once per run. */
    private fun recordTunnelUse() {
        val line = currentLine ?: return
        val started = tunnelStartedAt
        if (!tunnelRunning || started <= 0L || useRecordedFor == started) return
        useRecordedFor = started
        DpiLedgerStore.recordUse(this, tunnelClass, line, SystemClock.elapsedRealtime() - started)
    }

    private fun stopTunnelInternal(publishStopped: Boolean = true) {
        recordTunnelUse()
        if (tunnelRunning && !stopRequested.get()) logTunnelTotals()
        if (tunnelRunning || runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)) {
            runCatching { TProxyService.TProxyStopService() }
        }
        tunnelRunning = false
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { engineSession?.close() }
        engineSession = null
        // A reconnect (Update) publishes "starting" itself right after, so it must not flash
        // "stopped" to the UI in between.
        if (publishStopped && !startAfterStop && BypassVpnController.state.value.errorRes == null) publishStopped()
        AppLog.i("BypassVpnService", "YouTube-only bypass stopped")
    }

    private fun fail(@StringRes errorRes: Int) {
        AppLog.w("BypassVpnService", "Bypass start failed errorRes=$errorRes")
        BypassResume.setDesired(this, false)
        if (isCurrent()) BypassVpnController.failed(errorRes)
        stopSelf()
    }

    /** Patch 60: false when the system refuses (a foreground service may not be started from the background on a recent Android). */
    private fun startForegroundCompat(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        AppLog.w("BypassVpnService", "startForeground refused: ${e.javaClass.simpleName}")
        false
    }

    private fun buildNotification(
        active: Boolean,
        progress: SearchProgress? = null,
        updating: Boolean = false
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, BypassVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val updatePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BypassVpnService::class.java).apply { action = ACTION_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Fires when the user dismisses the notification; brings it straight back.
        val repostPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, BypassVpnService::class.java).apply { action = ACTION_REPOST },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            updating && progress != null -> getString(
                R.string.bypass_find_progress,
                (progress.index + 1).coerceAtMost(progress.total.coerceAtLeast(1)),
                progress.total
            )
            updating -> getString(R.string.bypass_search_notification_starting)
            active -> getString(R.string.bypass_notification_active)
            else -> getString(R.string.bypass_notification_starting)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kinescope)
            .setContentTitle(getString(R.string.bypass_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setDeleteIntent(repostPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        // "Update" only while the tunnel is up and no update is already running.
        if (active && !updating) {
            builder.addAction(0, getString(R.string.bypass_notification_update), updatePendingIntent)
        }
        return builder
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bypass_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private class BypassStartException(@StringRes val errorRes: Int) : Exception()

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** Patch 60: counts the service instances; only the newest one may publish the shared UI state. */
        private val generation = AtomicInteger(0)

        /** Patch 60: how long a start that came in during a stop waits for the previous run to end. */
        private const val DRAIN_TIMEOUT_S = 20L

        /** Patch 64: how long a start waits for a quiet search to stop before it goes on beside it (see [yieldToSearch]). */
        private const val SEARCH_YIELD_MS = 15_000L

        /** Patch 60: the connection must have been back this long before a retry wait is cut short (a handover flickers). */
        private const val CONNECTION_BACK_MIN_WAIT_MS = 2_000L

        /** Patch 60: rebuilds of the tunnel because the monitor kept failing, allowed per ten minutes. */
        private const val MONITOR_REBUILDS = 3
        private const val ACTION_START = "com.kinescope.app.ACTION_START_BYPASS_VPN"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_BYPASS_VPN"
        private const val ACTION_UPDATE = "com.kinescope.app.ACTION_UPDATE_BYPASS_VPN"
        private const val ACTION_REBUILD = "com.kinescope.app.ACTION_REBUILD_BYPASS_VPN"
        private const val ACTION_REPOST = "com.kinescope.app.ACTION_REPOST_BYPASS_VPN_NOTIFICATION"
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1101
        private const val TUN_MTU = 1500

        /** The tunnel monitor ticks every 500ms; every 6th tick (3s) it checks the notification. */
        private const val NOTIFICATION_CHECK_TICKS = 6

        // Patch 35: periodic probe and strategy rotation.
        // Patch 64: shorter, so a dead strategy is found sooner (was 30 s and 45 s).
        private const val PROBE_INTERVAL_MS = 20_000L
        private const val FIRST_PROBE_DELAY_MS = 20_000L

        /** Patch 56: how often a running tunnel asks the calendar of the random check whether one is due: ten minutes. */
        private const val UPKEEP_INTERVAL_MS = 10L * 60L * 1000L
        private const val PROBE_CONNECTIONS = 3

        /** Patch 63: the bound of the lookup that tells a dead name lookup from a failed strategy. */
        private const val LOOKUP_TIMEOUT_MS = 2_000
        private const val FAILS_BEFORE_ROTATE = 2

        // Patch 59: how often strategies may be switched is `RotationLimits`; what makes one switch is `TunnelStall` (7 s of
        // the app's traffic), the failed probe above and the one-way windows below.

        // Patch 44: the traffic of the YouTube app through the tunnel (see checkTunnelFlow).
        private const val FLOW_INTERVAL_MS = 10_000L
        private const val BRIDGE_SETTLE_MS = 250L

        fun start(context: Context): Boolean {
            val intent = Intent(context, BypassVpnService::class.java).apply { action = ACTION_START }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure { AppLog.e("BypassVpnService", "Could not dispatch start", it) }
                .getOrDefault(false)
        }

        /** Patch 64: a strategy was chosen by hand: a running tunnel is rebuilt on it; a stopped one is left alone. */
        fun rebuild(context: Context) {
            if (!BypassVpnController.state.value.active) return
            val intent = Intent(context, BypassVpnService::class.java).apply { action = ACTION_REBUILD }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("BypassVpnService", "Could not dispatch rebuild", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BypassVpnService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("BypassVpnService", "Could not dispatch stop", it) }
        }
    }
}
