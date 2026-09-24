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
            gate.release()
        }
    }
}

object DpiEngine {
    const val HOST = "127.0.0.1"

    private const val READY_TIMEOUT_MS = 6_000L
    private const val START_ATTEMPTS = 2
    private const val POLL_INTERVAL_MS = 80L
    private const val PORT_CLOSE_WAIT_MS = 2_000L
    private const val SETTLE_MS = 200L

    private val regularGate = Semaphore(1, true)
    private val vpnGate = Semaphore(1, true)

    /** Regular short-lived engine used by strategy tests and Kinescope downloads. */
    fun start(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long = READY_TIMEOUT_MS
    ): DpiEngineSession? = startIsolated(
        context,
        strategyArgs,
        readyTimeoutMs,
        DpiEngineService::class.java,
        regularGate
    )

    /** Long-lived engine reserved for the external YouTube split-tunnel session. */
    fun startForVpn(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long = READY_TIMEOUT_MS
    ): DpiEngineSession? = startIsolated(
        context,
        strategyArgs,
        readyTimeoutMs,
        DpiVpnEngineService::class.java,
        vpnGate
    )

    fun regularBusy(): Boolean = regularGate.availablePermits() == 0

    private fun startIsolated(
        context: Context,
        strategyArgs: List<String>,
        readyTimeoutMs: Long,
        serviceClass: Class<out Service>,
        gate: Semaphore
    ): DpiEngineSession? {
        if (!gate.tryAcquire()) return null
        var handedOff = false
        try {
            val appContext = context.applicationContext
            repeat(START_ATTEMPTS) {
                val port = freePort() ?: return null
                val session = startOnce(
                    appContext,
                    port,
                    strategyArgs,
                    readyTimeoutMs,
                    serviceClass,
                    gate
                )
                if (session != null) {
                    handedOff = true
                    return session
                }
            }
            return null
        } finally {
            if (!handedOff) gate.release()
        }
    }

    private fun startOnce(
        context: Context,
        port: Int,
        strategyArgs: List<String>,
        readyTimeoutMs: Long,
        serviceClass: Class<out Service>,
        gate: Semaphore
    ): DpiEngineSession? {
        val intent = Intent(context, serviceClass)
            .putExtra(DpiEngineService.EXTRA_ARGS, dpiLaunchArguments(HOST, port, strategyArgs).toTypedArray())
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
            return null
        }
        var session: DpiEngineSession? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
            if (!connected.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) return null
            val endpoint = SocksEndpoint(HOST, port)
            while (!lost.get() && System.nanoTime() < deadline) {
                if (isSocksReady(endpoint)) {
                    session = DpiEngineSession(context, connection, lost, gate, port)
                    return session
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return null
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
