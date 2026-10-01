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

data class BypassVpnUiState(
    val starting: Boolean = false,
    val active: Boolean = false,
    @StringRes val errorRes: Int? = null
)

object BypassVpnController {
    private val mutableState = MutableStateFlow(BypassVpnUiState())
    val state = mutableState.asStateFlow()

    internal fun starting() { mutableState.value = BypassVpnUiState(starting = true) }
    internal fun active() { mutableState.value = BypassVpnUiState(active = true) }
    internal fun stopped() { mutableState.value = BypassVpnUiState() }
    internal fun failed(@StringRes errorRes: Int) { mutableState.value = BypassVpnUiState(errorRes = errorRes) }

}

/**
 * Local Android VPN transport used only for the official YouTube app. Traffic enters a TUN
 * interface, hev-socks5-tunnel forwards it to Kinescope's local ByeDPI SOCKS5 endpoint, and
 * ByeDPI opens the real network connection. No remote VPN server is involved.
 */
class BypassVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinescope-bypass-vpn").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested.set(true)
                executor.execute {
                    stopTunnelInternal()
                    stopSelf()
                }
            }
            ACTION_START -> {
                if (!BypassVpnController.state.value.active && !BypassVpnController.state.value.starting) {
                    stopRequested.set(false)
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
        stopRequested.set(true)
        executor.execute {
            stopTunnelInternal()
            stopSelf()
        }
        super.onRevoke()
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
            val chain = DpiStrategyStore.chain(this)
            if (chain.isEmpty()) {
                fail(R.string.bypass_vpn_no_verified_strategy)
                return
            }
            ensureYouTubeInstalled()

            // Try the verified strategy, then its verified fallbacks in order, and keep the
            // first engine that actually starts.
            // Patch 33: a strategy that merely starts is not necessarily one that gets through
            // right now (the network can change between the search and this start), so each
            // candidate is also checked with one real connection before the tunnel is built on
            // it. If none passes, the first one that started is used anyway, as before.
            var firstStarted: String? = null
            var chosenLine: String? = null
            for ((position, line) in chain.withIndex()) {
                val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
                val candidate = DpiEngine.startForVpn(this, parsed.args) ?: continue
                val health = DpiBypass.checkHealth(candidate.port)
                AppLog.i(
                    "BypassVpnService",
                    "chk s=${position + 1}/${chain.size} ok=${health.ok} st=${health.stage} why=${health.why} ms=${health.ms}"
                )
                if (health.ok) {
                    engine = candidate
                    chosenLine = line
                    break
                }
                if (firstStarted == null) firstStarted = line
                candidate.close()
            }
            if (engine == null && firstStarted != null) {
                val parsed = DpiStrategyParser.parse(firstStarted) as? DpiStrategyParser.Parsed.Ok
                engine = parsed?.let { DpiEngine.startForVpn(this, it.args) }
                chosenLine = firstStarted
            }
            if (engine == null) throw BypassStartException(R.string.bypass_vpn_engine_failed)
            if (stopRequested.get()) return

            tun = buildYouTubeTunnel()
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
            if (!running || stopRequested.get()) return

            engineSession = engine
            tunInterface = tun
            tunnelRunning = true
            currentLine = chosenLine
            failStreak = 0
            nextProbeAt = SystemClock.elapsedRealtime() + FIRST_PROBE_DELAY_MS
            engine = null
            tun = null
            hevStarted = false
            BypassVpnController.active()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(active = true))
            AppLog.i("BypassVpnService", "YouTube-only bypass active")
            if (monitorRunning.compareAndSet(false, true)) executor.execute { monitorTunnel() }
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

    private fun monitorTunnel() {
        try {
            var ticks = 0
            while (!stopRequested.get()) {
                Thread.sleep(500L)
                if (restarting) continue
                val engineAlive = engineSession?.isAlive == true
                val bridgeAlive = runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
                if (!engineAlive || !bridgeAlive) {
                    // `restarting` is set before the old engine is closed, so seeing a closed
                    // engine here means this flag is already visible: an update-triggered
                    // reconnect is not a failure.
                    if (restarting) continue
                    AppLog.w("BypassVpnService", "YouTube bypass transport stopped unexpectedly")
                    BypassVpnController.failed(
                        if (!engineAlive) R.string.bypass_vpn_engine_failed else R.string.bypass_vpn_tunnel_failed
                    )
                    stopSelf()
                    return
                }
                if (++ticks % NOTIFICATION_CHECK_TICKS == 0) ensureNotificationVisible()
                if (!updating && !restarting && SystemClock.elapsedRealtime() >= nextProbeAt) probeTunnel()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            monitorRunning.set(false)
        }
    }

    /**
     * Re-tests every strategy while the tunnel keeps running, then reconnects with the new
     * verified chain -- but only if that chain differs from the current one. Nothing found, or
     * the same chain: the running connection is left alone.
     */
    private fun updateStrategies() {
        try {
            val results = DpiBypass.search(
                applicationContext,
                isCancelled = { stopRequested.get() },
                onProgress = { snapshot ->
                    updateProgress = snapshot
                    postNotification()
                }
            )
            if (stopRequested.get()) return
            val previousChain = DpiStrategyStore.verifiedChain(applicationContext)
            // Patch 33: ranked (real transfer first) instead of the first passes in list order.
            if (!DpiBypass.applySearchResults(applicationContext, results)) {
                AppLog.i("BypassVpnService", "Strategy update found nothing; keeping the current connection")
                return
            }
            if (DpiStrategyStore.verifiedChain(applicationContext) == previousChain) {
                AppLog.i("BypassVpnService", "Strategy update found the same methods; keeping the connection")
                return
            }
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
        if (failStreak >= FAILS_BEFORE_ROTATE) rotateStrategy(line)
    }

    /** Quarantines the failing strategy and reconnects on the next one; rate-limited so a dead network cannot thrash it. */
    private fun rotateStrategy(line: String) {
        val now = SystemClock.elapsedRealtime()
        while (rotations.isNotEmpty() && now - rotations.first() > ROTATION_WINDOW_MS) rotations.removeFirst()
        val tooSoon = rotations.isNotEmpty() && now - rotations.last() < MIN_ROTATION_GAP_MS
        val tooMany = rotations.size >= MAX_ROTATIONS
        val alternatives = DpiStrategyStore.chain(this).filter { it != line }
        if (tooSoon || tooMany || alternatives.isEmpty()) {
            AppLog.w("BypassVpnService", "rotate skipped soon=$tooSoon many=$tooMany alternatives=${alternatives.size}")
            failStreak = 0
            nextProbeAt = now + if (tooMany) ROTATION_WINDOW_MS / 2 else PROBE_INTERVAL_MS
            return
        }
        rotations.addLast(now)
        DpiPrefs.quarantine(this, line)
        AppLog.w("BypassVpnService", "rotate quarantined=1 alternatives=${alternatives.size}")
        failStreak = 0
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

    private fun ensureYouTubeInstalled() {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(YOUTUBE_PACKAGE, 0)
    }

    private fun buildYouTubeTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.bypass_title))
            .setMtu(TUN_MTU)
            .addAddress("198.18.0.1", 32)
            .addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
            .addAllowedApplication(YOUTUBE_PACKAGE)
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
        if (publishStopped) BypassVpnController.stopped()
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
        private const val PROBE_CONNECTIONS = 3
        private const val FAILS_BEFORE_ROTATE = 2
        private const val MIN_ROTATION_GAP_MS = 90_000L
        private const val ROTATION_WINDOW_MS = 10L * 60L * 1000L
        private const val MAX_ROTATIONS = 3

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
