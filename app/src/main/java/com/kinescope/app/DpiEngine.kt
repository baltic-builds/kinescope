package com.kinescope.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A running bypass engine: a local SOCKS5 proxy on loopback. Close it when the work is done. */
interface BypassSession : AutoCloseable {
    val port: Int
}

/**
 * Client side of [DpiEngineService]. The engine lives exactly as long as this session is bound;
 * [close] unbinds, which makes the service kill its own process.
 *
 * Every function here blocks. Call them from a background thread, never from the main thread.
 */
class DpiEngineSession internal constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val lost: AtomicBoolean,
    override val port: Int
) : BypassSession {
    private val closed = AtomicBoolean(false)

    /** False once the engine process is gone (crash, kill) or the session was closed. */
    val isAlive: Boolean get() = !lost.get() && !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        DpiEngine.release(context, connection, port)
    }
}

object DpiEngine {
    const val HOST = "127.0.0.1"

    private const val READY_TIMEOUT_MS = 6_000L
    private const val START_ATTEMPTS = 2
    private const val POLL_INTERVAL_MS = 80L
    private const val PORT_CLOSE_WAIT_MS = 2_000L
    // Lets the activity manager finish cleaning up the killed process before the next bind.
    private const val SETTLE_MS = 200L

    /**
     * Starts the engine with an already validated strategy and waits until its SOCKS5 port
     * answers. Returns null if it did not come up; nothing is left running in that case.
     */
    fun start(context: Context, strategyArgs: List<String>, readyTimeoutMs: Long = READY_TIMEOUT_MS): DpiEngineSession? {
        val appContext = context.applicationContext
        repeat(START_ATTEMPTS) {
            val port = freePort() ?: return null
            startOnce(appContext, port, strategyArgs, readyTimeoutMs)?.let { return it }
        }
        return null
    }

    private fun startOnce(context: Context, port: Int, strategyArgs: List<String>, readyTimeoutMs: Long): DpiEngineSession? {
        val intent = Intent(context, DpiEngineService::class.java)
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
                    session = DpiEngineSession(context, connection, lost, port)
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

    /** Waits until the engine's port stops accepting connections, i.e. its process is really gone. */
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
    } catch (e: IOException) {
        null
    }
}
