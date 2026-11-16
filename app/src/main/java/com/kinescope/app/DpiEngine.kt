package com.kinescope.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A running bypass engine: a local SOCKS5 proxy on loopback. Close it when the work is done. */
interface BypassSession : AutoCloseable {
    val port: Int
}

/**
 * Client side of a dedicated ByeDPI service process. Each process may host at most one
 * native engine at a time because upstream ByeDPI keeps process-wide C state.
 *
 * Every function here blocks. Call it from a background thread, never from the main thread.
 */
class DpiEngineSession internal constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val lost: AtomicBoolean,
    private val gate: Semaphore,
    override val port: Int
) : BypassSession {
    private val closed = AtomicBoolean(false)

    /** False once the engine process is gone (crash, kill) or the session was closed. */
    val isAlive: Boolean get() = !lost.get() && !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            DpiEngine.release(context, connection, port)
        } finally {
            DpiEngine.forget(this)
            gate.release()
        }
    }
}

object DpiEngine {
    const val HOST = "127.0.0.1"

    private const val READY_TIMEOUT_MS = 6_000L
    private const val START_ATTEMPTS = 2

    /** Patch 63: the tunnel's engine gets one more try, and a process that vanished at once is given a moment before the next one. */
    private const val VPN_START_ATTEMPTS = 3
    private const val LOST_RETRY_PAUSE_MS = 700L
    private const val POLL_INTERVAL_MS = 80L
    private const val PORT_CLOSE_WAIT_MS = 2_000L
    private const val SETTLE_MS = 200L

    private val regularGate = Semaphore(1, true)
    private val vpnGate = Semaphore(1, true)

    /** Patch 60: how long a VPN engine start waits for the gate (a session being closed takes up to two seconds to let go of it). */
    private const val VPN_GATE_WAIT_MS = 3_000L

    // Patch 60: the session that holds the VPN engine gate. Only the tunnel service uses it and only one tunnel runs, so a session that is
    // still held when a new run begins belongs to nobody (a stop raced the end of a route choice) and is closed by [reclaimVpn]; before,
    // it kept the gate taken until the app process died and every later start failed at once.
    private val vpnHolder = java.util.concurrent.atomic.AtomicReference<DpiEngineSession?>(null)

    /** Closes a VPN engine session that nobody owns. Blocks like [DpiEngineSession.close]: call it from a background thread. */
    fun reclaimVpn() {
        vpnHolder.getAndSet(null)?.close()
    }

    internal fun forget(session: DpiEngineSession) {
        vpnHolder.compareAndSet(session, null)
    }

    /**
     * Regular short-lived engine used by strategy tests and Kinescope downloads. Patch 54: [ipv4Only] null means "as the
     * connection the phone is on has proven it needs" (`DpiPrefs.ipv4Only`); a caller that is finding that out passes it.
     */
    fun start(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long = READY_TIMEOUT_MS,
        ipv4Only: Boolean? = null
    ): DpiEngineSession? = startIsolated(
        context,
        strategyArgs,
        readyTimeoutMs,
        DpiEngineService::class.java,
        regularGate,
        ipv4Only
    )

    /** Long-lived engine reserved for the external YouTube split-tunnel session. */
    fun startForVpn(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long = READY_TIMEOUT_MS,
        ipv4Only: Boolean? = null
    ): DpiEngineSession? = startIsolated(
        context,
        strategyArgs,
        readyTimeoutMs,
        DpiVpnEngineService::class.java,
        vpnGate,
        ipv4Only,
        VPN_GATE_WAIT_MS,
        VPN_START_ATTEMPTS
    )?.also { vpnHolder.set(it) }

    fun regularBusy(): Boolean = regularGate.availablePermits() == 0

    private fun startIsolated(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long,
        serviceClass: Class<out Service>,
        gate: Semaphore,
        ipv4Only: Boolean?,
        gateWaitMs: Long = 0L,
        attempts: Int = START_ATTEMPTS
    ): DpiEngineSession? {
        val vpn = serviceClass == DpiVpnEngineService::class.java
        if (!gate.tryAcquire(gateWaitMs, TimeUnit.MILLISECONDS)) {
            AppLog.w("Engine", "start refused svc=${if (vpn) "vpn" else "reg"} why=gate")
            return null
        }
        var handedOff = false
        try {
            val appContext = context.applicationContext
            val v4Only = ipv4Only ?: DpiPrefs.ipv4Only(appContext, NetClassWatcher.current(appContext))
            for (attempt in 1..attempts) {
                val port = freePort() ?: return null
                val began = System.nanoTime()
                val attemptResult = startOnce(
                    appContext,
                    port,
                    strategyArgs,
                    readyTimeoutMs,
                    serviceClass,
                    gate,
                    v4Only
                )
                if (attemptResult.session != null) {
                    handedOff = true
                    return attemptResult.session
                }
                // Patch 63: a device report showed the tunnel's engine failing to start for about 40 s after a restart with nothing in
                // the log to say why. One line per failed attempt: which service, how it failed, how long it took.
                val ms = (System.nanoTime() - began) / 1_000_000L
                AppLog.w(
                    "Engine",
                    "start failed svc=${if (vpn) "vpn" else "reg"} why=${attemptResult.why} try=$attempt/$attempts ms=$ms"
                )
                if (attempt < attempts && attemptResult.why == WHY_LOST) {
                    try {
                        Thread.sleep(LOST_RETRY_PAUSE_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
            return null
        } finally {
            if (!handedOff) gate.release()
        }
    }

    /** Patch 63: how one start attempt ended: the session, or the short reason it did not come up. */
    private class StartAttempt(val session: DpiEngineSession?, val why: String)

    private const val WHY_LOST = "lost"

    private fun startOnce(
        context: Context,
        port: Int,
        strategyArgs: List<String>,
        readyTimeoutMs: Long,
        serviceClass: Class<out Service>,
        gate: Semaphore,
        ipv4Only: Boolean
    ): StartAttempt {
        val intent = Intent(context, serviceClass)
            .putExtra(DpiEngineService.EXTRA_ARGS, dpiLaunchArguments(HOST, port, strategyArgs, ipv4Only).toTypedArray())
        val connected = CountDownLatch(1)
        val lost = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                lost.set(true)
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                lost.set(true)
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                lost.set(true)
                connected.countDown()
            }
        }

        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            release(context, connection, port)
            return StartAttempt(null, "bind")
        }
        var session: DpiEngineSession? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
            if (!connected.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) return StartAttempt(null, "timeout")
            val endpoint = SocksEndpoint(HOST, port)
            while (!lost.get() && System.nanoTime() < deadline) {
                if (isSocksReady(endpoint)) {
                    session = DpiEngineSession(context, connection, lost, gate, port)
                    return StartAttempt(session, "ok")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return StartAttempt(null, if (lost.get()) WHY_LOST else "notready")
        } finally {
            if (session == null) release(context, connection, port)
        }
    }

    internal fun release(context: Context, connection: ServiceConnection, port: Int) {
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Not bound (bindService failed, or already released).
        }
        awaitPortClosed(port)
    }

    private fun awaitPortClosed(port: Int) {
        val endpoint = SocksEndpoint(HOST, port)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PORT_CLOSE_WAIT_MS)
        try {
            while (isPortOpen(endpoint) && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            Thread.sleep(SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun freePort(): Int? = try {
        ServerSocket(0, 1, InetAddress.getByName(HOST)).use { it.localPort }
    } catch (_: IOException) {
        null
    }
}
