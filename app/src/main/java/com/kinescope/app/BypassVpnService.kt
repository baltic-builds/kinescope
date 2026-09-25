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
            val chain = DpiStrategyStore.verifiedChain(this)
            if (chain.isEmpty()) {
                fail(R.string.bypass_vpn_no_verified_strategy)
                return
            }
            ensureYouTubeInstalled()

            // Try the verified strategy, then its verified fallbacks in order, and keep the
            // first engine that actually starts.
            for (line in chain) {
                val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
                engine = DpiEngine.startForVpn(this, parsed.args)
                if (engine != null) break
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
            engine = null
            tun = null
            hevStarted = false
            BypassVpnController.active()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(active = true))
            AppLog.i("BypassVpnService", "YouTube-only bypass active")
            executor.execute { monitorTunnel() }
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
            while (!stopRequested.get()) {
                Thread.sleep(500L)
                val engineAlive = engineSession?.isAlive == true
                val bridgeAlive = runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
                if (!engineAlive || !bridgeAlive) {
                    AppLog.w("BypassVpnService", "YouTube bypass transport stopped unexpectedly")
                    BypassVpnController.failed(
                        if (!engineAlive) R.string.bypass_vpn_engine_failed else R.string.bypass_vpn_tunnel_failed
                    )
                    stopSelf()
                    return
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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

    private fun stopTunnelInternal() {
        if (tunnelRunning || runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)) {
            runCatching { TProxyService.TProxyStopService() }
        }
        tunnelRunning = false
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { engineSession?.close() }
        engineSession = null
        BypassVpnController.stopped()
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

    private fun buildNotification(active: Boolean): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.bypass_notification_title))
            .setContentText(getString(if (active) R.string.bypass_notification_active else R.string.bypass_notification_starting))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
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
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1101
        private const val TUN_MTU = 1500

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
