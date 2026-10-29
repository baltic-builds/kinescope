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
import java.util.concurrent.atomic.AtomicBoolean
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

    @Volatile private var engineSession: DpiEngineSession? = null
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var tunnelRunning = false

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

    // Patch 46: the strategy that already proved it carries real traffic in this tunnel run, so it is recorded once.
    @Volatile private var provedLine: String? = null

    // Patch 56: how many times the transport died early on a strategy since it last carried traffic (see TransportRecovery), and when
    // the calendar of the random check is next looked at while the tunnel runs. Touched by the monitor thread only (the executor).
    private val earlyDeaths = HashMap<String, Int>()
    private val randomRunning = AtomicBoolean(false)
    @Volatile private var nextUpkeepAt = 0L

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
            ACTION_START -> {
                if (!BypassVpnController.state.value.active && !BypassVpnController.state.value.starting) {
                    stopRequested.set(false)
                    earlyDeaths.clear()
                    BypassVpnController.starting()
                    startForegroundCompat(buildNotification(active = false))
                    executor.execute { startTunnel() }
                }
            }
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
            ACTION_REPOST -> {
                // The user swiped the notification away (Android 14+ allows that for foreground
                // services): put it straight back while the bypass is running.
                val state = BypassVpnController.state.value
                if (state.active || state.starting) postNotification() else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    /** Stop is never queued behind monitorTunnel: it may be performing a blocking health probe. */
    private fun requestStop() {
        stopRequested.set(true)
        runCatching { tunInterface?.close() }
        if (stopCleanupStarted.compareAndSet(false, true)) {
            Thread({
                try { stopTunnelInternal() } finally { stopSelf() }
            }, "kinescope-bypass-stop").apply { isDaemon = true; start() }
        }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        val preserveError = BypassVpnController.state.value.errorRes != null
        runCatching { if (tunnelRunning || TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() }
        tunnelRunning = false
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { engineSession?.close() }
        engineSession = null
        if (!preserveError) BypassVpnController.stopped()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel() {
        var engine: DpiEngineSession? = null
        var tun: ParcelFileDescriptor? = null
        var hevStarted = false
        try {
            // Patch 53: the strategies of the connection the phone is on now (a class with none borrows the other's).
            val cls = NetClassWatcher.current(this)
            var chain = DpiStrategyStore.chain(this, cls)
            if (chain.isEmpty()) {
                fail(R.string.bypass_vpn_no_verified_strategy)
                return
            }
            // Patch 51: the tunnel carries the chosen app (Settings), not always the official YouTube app.
            val appPackage = YoutubeApps.chosenPackage(this)
            if (appPackage == null) {
                fail(R.string.bypass_vpn_youtube_missing)
                return
            }

            // Select the first live, healthy route on this network class. Merely starting a
            // SOCKS engine does NOT prove the restriction can be bypassed; never report an
            // active VPN using a strategy whose health check just failed.
            // A pinned manual choice leads, but failed choices are still skipped.
            // Patch 54: see selectRoute -- a connection the engine cannot reach is told from a blocked strategy, and a class that
            // was never tested gets a short search of its own when its borrowed strategies are blocked.
            val route = selectRoute(cls, chain)
            if (stopRequested.get()) return
            if (route.noRoute) throw BypassStartException(R.string.bypass_vpn_no_route)
            engine = route.engine ?: throw BypassStartException(R.string.bypass_vpn_engine_failed)
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
            if (stopRequested.get()) return
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
                "tun up mtu=$TUN_MTU v6=1 dns=1.1.1.1 udp=udp net=${cls.key} s=${chain.indexOf(chosenLine) + 1}/${chain.size} " +
                    "app=${if (appPackage == YoutubeApps.DEFAULT_PACKAGE) "yt" else "other"}"
            )
            AppLog.i("BypassVpnService", "YouTube-only bypass active")
            if (monitorRunning.compareAndSet(false, true)) executor.execute { monitorTunnel() }
            startRandomCheck(cls, chosenLine)
        } catch (e: BypassStartException) {
            fail(e.errorRes)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!stopRequested.get()) fail(R.string.bypass_vpn_tunnel_failed)
        } catch (e: PackageManager.NameNotFoundException) {
            fail(R.string.bypass_vpn_youtube_missing)
        } catch (e: Exception) {
            AppLog.e("BypassVpnService", "Could not start YouTube bypass", e)
            fail(R.string.bypass_vpn_tunnel_failed)
        } finally {
            if (engine != null || tun != null || hevStarted) {
                if (hevStarted) runCatching { TProxyService.TProxyStopService() }
                runCatching { tun?.close() }
                runCatching { engine?.close() }
            }
            if (stopRequested.get() && !BypassVpnController.state.value.active) {
                BypassVpnController.stopped()
                stopSelf()
            }
        }
    }

    /**
     * Patch 55: the two-a-day random check ([RandomCheck]) for the connection this tunnel runs on, started once the tunnel is up so
     * switching the bypass on is not delayed. It has its own thread (the monitor and the start/stop work take turns on [executor])
     * and gives way as soon as the tunnel is stopped. The strategy in use is not among the ones it tests.
     *
     * Patch 56: also looked at every [UPKEEP_INTERVAL_MS] while the tunnel runs (see [monitorTunnel]): a tunnel that stays on for days
     * used to be checked once, when it was switched on. One check at a time ([randomRunning]); the thread is only started when the
     * calendar says a check is due, so a quiet tunnel costs nothing.
     */
    private fun startRandomCheck(cls: NetClass, active: String?) {
        if (!RandomCheck.dueNow(applicationContext, cls)) return
        if (!randomRunning.compareAndSet(false, true)) return
        Thread({
            try {
                RandomCheck.runIfDue(applicationContext, cls, active) { stopRequested.get() || !tunnelRunning }
            } catch (e: Exception) {
                AppLog.e("BypassVpnService", "Random check failed", e)
            } finally {
                randomRunning.set(false)
            }
        }, "kinescope-random-check").apply { isDaemon = true }.start()
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
    private fun selectRoute(cls: NetClass, startChain: List<String>): RouteChoice {
        var chain = startChain
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
                val health = DpiBypass.checkHealth(candidate.port)
                AppLog.i(
                    "BypassVpnService",
                    "chk s=${position + 1}/${chain.size} ok=${health.ok} st=${health.stage} why=${health.why} " +
                        "rc=${health.replyCode ?: "-"} ms=${health.ms}"
                )
                if (TunnelStartupPolicy.accepts(health.ok, stopRequested.get())) {
                    // A confirmed working fallback is evidence that these routes failed on THIS network.
                    TunnelStartupPolicy.failuresToLearn(failedLines, line).forEach { DpiPrefs.quarantine(this, cls, it) }
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
                if (found.isNotEmpty() && found != chain) {
                    chain = found
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
                if (restarting) continue
                // Patch 56: a stop closes the TUN and the bridge at once; waking up in the middle of it, the old code took the dead
                // transport for a failure and blamed the strategy that was carrying the traffic. A stop is never a failure.
                if (stopRequested.get()) return
                val engineAlive = engineSession?.isAlive == true
                val bridgeAlive = runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
                if (!engineAlive || !bridgeAlive) {
                    // `restarting` is set before the old engine is closed, so seeing a closed
                    // engine here means this flag is already visible: an update-triggered
                    // reconnect is not a failure.
                    if (restarting) continue
                    handleTransportDeath(engineAlive, bridgeAlive)
                    if (stopRequested.get()) return
                    continue
                }
                if (++ticks % NOTIFICATION_CHECK_TICKS == 0) ensureNotificationVisible()
                if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextUpkeepAt) {
                    nextUpkeepAt = SystemClock.elapsedRealtime() + UPKEEP_INTERVAL_MS
                    startRandomCheck(tunnelClass, currentLine)
                }
                if (!updating && !restarting) followConnection()
                if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextFlowCheckAt) checkTunnelFlow()
                if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextProbeAt) probeTunnel()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            monitorRunning.set(false)
        }
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
        if (load.passed * 3 >= load.total * 2) {
            failStreak = 0
            probeOkCount++
            if (probeOkCount == 1 || probeOkCount % 10 == 0) {
                AppLog.i("BypassVpnService", "probe ok n=$probeOkCount ${load.passed}/${load.total} hs=${load.medianHandshakeMs}ms")
            }
            return
        }
        failStreak++
        AppLog.w("BypassVpnService", "probe fail streak=$failStreak ${load.passed}/${load.total} hs=${load.medianHandshakeMs}ms")
        if (failStreak >= FAILS_BEFORE_ROTATE) rotateStrategy(line, RouterEventKind.PROBE)
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
    private fun rotateStrategy(line: String, kind: RouterEventKind) {
        if (stopRequested.get()) return
        val now = SystemClock.elapsedRealtime()
        while (rotations.isNotEmpty() && now - rotations.first() > ROTATION_WINDOW_MS) rotations.removeFirst()
        val tooSoon = rotations.isNotEmpty() && now - rotations.last() < MIN_ROTATION_GAP_MS
        val tooMany = rotations.size >= MAX_ROTATIONS
        val cls = tunnelClass
        val alternatives = DpiStrategyStore.chain(this, cls).filter { it != line }
        if (tooMany || alternatives.isEmpty()) {
            AppLog.w("BypassVpnService", "all recovery routes exhausted; stopping unusable VPN")
            BypassVpnController.failed(R.string.bypass_vpn_engine_failed)
            requestStop()
            return
        }
        if (tooSoon) {
            nextProbeAt = now + MIN_ROTATION_GAP_MS
            failStreak = 0
            return
        }
        rotations.addLast(now)
        DpiPrefs.quarantine(this, cls, line)
        AppLog.w("BypassVpnService", "rotate net=${cls.key} why=${kind.name.lowercase()} quarantined=1 alternatives=${alternatives.size}")
        failStreak = 0
        BypassVpnController.publishEvent(kind)
        restartTunnel()
    }

    private fun restartTunnel() {
        restarting = true
        try {
            stopTunnelInternal(publishStopped = false)
            if (stopRequested.get()) {
                BypassVpnController.stopped()
                stopSelf()
                return
            }
            BypassVpnController.starting()
            postNotification()
            startTunnel()
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
        val builder = Builder()
            .setSession(getString(R.string.bypass_title))
            .setMtu(TUN_MTU)
            .addAddress("198.18.0.1", 32)
            .addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
            .addAllowedApplication(appPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
    }

    private fun writeTunnelConfig(port: Int): File {
        val file = File(cacheDir, "hev-youtube-bypass.yml")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'off'
            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              max-session-count: 512
              log-file: null
              log-level: warn
            """.trimIndent() + "\n"
        )
        return file
    }

    private fun stopTunnelInternal(publishStopped: Boolean = true) {
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
        if (publishStopped && BypassVpnController.state.value.errorRes == null) BypassVpnController.stopped()
        AppLog.i("BypassVpnService", "YouTube-only bypass stopped")
    }

    private fun fail(@StringRes errorRes: Int) {
        AppLog.w("BypassVpnService", "Bypass start failed errorRes=$errorRes")
        BypassVpnController.failed(errorRes)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        private const val ACTION_START = "com.kinescope.app.ACTION_START_BYPASS_VPN"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_BYPASS_VPN"
        private const val ACTION_UPDATE = "com.kinescope.app.ACTION_UPDATE_BYPASS_VPN"
        private const val ACTION_REPOST = "com.kinescope.app.ACTION_REPOST_BYPASS_VPN_NOTIFICATION"
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1101
        private const val TUN_MTU = 1500

        /** The tunnel monitor ticks every 500ms; every 6th tick (3s) it checks the notification. */
        private const val NOTIFICATION_CHECK_TICKS = 6

        // Patch 35: periodic probe and strategy rotation.
        private const val PROBE_INTERVAL_MS = 30_000L
        private const val FIRST_PROBE_DELAY_MS = 45_000L

        /** Patch 56: how often a running tunnel asks the calendar of the random check whether one is due: ten minutes. */
        private const val UPKEEP_INTERVAL_MS = 10L * 60L * 1000L
        private const val PROBE_CONNECTIONS = 3
        private const val FAILS_BEFORE_ROTATE = 2
        private const val MIN_ROTATION_GAP_MS = 90_000L
        private const val ROTATION_WINDOW_MS = 10L * 60L * 1000L
        private const val MAX_ROTATIONS = 3

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

        fun stop(context: Context) {
            val intent = Intent(context, BypassVpnService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("BypassVpnService", "Could not dispatch stop", it) }
        }
    }
}
