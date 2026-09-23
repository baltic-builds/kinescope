package com.kinescope.app

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Where a SOCKS5 proxy listens. The bundled engine always listens on loopback. */
internal data class SocksEndpoint(val host: String, val port: Int)

internal data class ConnectResult(val ok: Boolean, val replyCode: Int?, val detail: String)

/** Minimal RFC 1928 SOCKS5 client (no authentication), used only for readiness and reachability checks. */
internal object Socks5 {
    private const val VERSION = 5
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4
    const val REPLY_GENERAL_FAILURE = 0x01
    const val REPLY_HOST_UNREACHABLE = 0x04

    /** Returns null when the peer accepts the no-auth greeting, otherwise a short technical reason. */
    fun greet(input: InputStream, output: OutputStream): String? {
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val reply = readFully(input, 2)
        return when {
            reply[0].toInt() != VERSION -> "not a SOCKS5 server"
            reply[1].toInt() == 0 -> null
            (reply[1].toInt() and 0xFF) == 0xFF -> "proxy requires authentication"
            else -> "unsupported authentication method"
        }
    }

    /** SOCKS5 CONNECT by host name, so the proxy does the name resolution (what `socks5h` means). */
    fun connectByName(input: InputStream, output: OutputStream, host: String, port: Int): ConnectResult {
        val name = host.toByteArray(Charsets.US_ASCII)
        require(name.size in 1..255) { "host name length" }
        val request = ByteArray(7 + name.size)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = name.size.toByte()
        System.arraycopy(name, 0, request, 5, name.size)
        request[5 + name.size] = (port shr 8).toByte()
        request[6 + name.size] = port.toByte()
        output.write(request)
        output.flush()

        val head = readFully(input, 4)
        if (head[0].toInt() != VERSION) return ConnectResult(false, null, "bad SOCKS5 reply")
        val code = head[1].toInt() and 0xFF
        if (code != 0) return ConnectResult(false, code, "reply 0x%02x (%s)".format(code, describeReply(code)))

        // Consume the bound address so nothing stands in front of the TLS stream that follows.
        val remaining = when (head[3].toInt()) {
            ATYP_IPV4 -> 4 + 2
            ATYP_IPV6 -> 16 + 2
            ATYP_DOMAIN -> (readFully(input, 1)[0].toInt() and 0xFF) + 2
            else -> return ConnectResult(false, null, "bad SOCKS5 address type")
        }
        readFully(input, remaining)
        return ConnectResult(true, 0, "connected")
    }

    private fun describeReply(code: Int): String = when (code) {
        0x01 -> "general failure"
        0x02 -> "not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown"
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count < 0) throw EOFException("connection closed by peer")
            read += count
        }
        return buffer
    }
}

/** Bounded name lookup: InetAddress has no timeout of its own, so it runs on a daemon thread. */
internal fun resolveBounded(host: String, timeoutMs: Int): List<InetAddress> {
    val task = FutureTask(Callable { InetAddress.getAllByName(host).toList() })
    Thread(task, "kinescope-check-dns").apply {
        isDaemon = true
        start()
    }
    try {
        return task.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        throw IOException("timeout after ${timeoutMs}ms")
    } catch (e: ExecutionException) {
        throw (e.cause as? IOException) ?: IOException(e.cause?.javaClass?.simpleName ?: "lookup failed")
    }
}

/** True when a SOCKS5 server answers the no-auth greeting at [endpoint]. Never throws. */
internal fun isSocksReady(endpoint: SocksEndpoint, timeoutMs: Int = 300): Boolean {
    val socket = Socket()
    return try {
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
        socket.soTimeout = timeoutMs
        Socks5.greet(socket.getInputStream(), socket.getOutputStream()) == null
    } catch (e: IOException) {
        false
    } finally {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** True while something accepts TCP connections at [endpoint]. Never throws. */
internal fun isPortOpen(endpoint: SocksEndpoint, timeoutMs: Int = 200): Boolean {
    val socket = Socket()
    return try {
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
        true
    } catch (e: IOException) {
        false
    } finally {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

internal enum class CheckStage { DNS, TCP, PROXY, CONNECT, TLS, HTTP }

internal data class StageResult(
    val stage: CheckStage,
    val ok: Boolean,
    val detail: String,
    val replyCode: Int? = null
)

internal data class PathResult(val viaBypass: Boolean, val stages: List<StageResult>) {
    val ok: Boolean get() = stages.isNotEmpty() && stages.all { it.ok }
    val failed: StageResult? get() = stages.firstOrNull { !it.ok }
    val failedStage: CheckStage? get() = failed?.stage
}

internal data class NetworkCheckReport(val host: String, val direct: PathResult, val bypassed: PathResult?)

internal enum class Verdict {
    DIRECT_OK,
    DNS_BLOCKED,
    DNS_BLOCKS_BYPASS,
    TCP_BLOCKED,
    TLS_INTERFERENCE,
    BYPASS_FIXES,
    BYPASS_ALSO_FAILS,
    BYPASS_UNAVAILABLE
}

/** Maps a staged report to the single most useful sentence for the user. */
internal fun verdictOf(report: NetworkCheckReport): Verdict {
    if (report.direct.ok) return Verdict.DIRECT_OK
    val bypassed = report.bypassed
    if (bypassed != null) {
        return when {
            bypassed.ok -> Verdict.BYPASS_FIXES
            bypassed.failedStage == CheckStage.PROXY -> Verdict.BYPASS_UNAVAILABLE
            report.direct.failedStage == CheckStage.DNS && bypassed.failed.let {
                it?.stage == CheckStage.CONNECT &&
                    (it.replyCode == Socks5.REPLY_HOST_UNREACHABLE || it.replyCode == Socks5.REPLY_GENERAL_FAILURE)
            } -> Verdict.DNS_BLOCKS_BYPASS
            else -> Verdict.BYPASS_ALSO_FAILS
        }
    }
    return when (report.direct.failedStage) {
        CheckStage.DNS -> Verdict.DNS_BLOCKED
        CheckStage.TCP -> Verdict.TCP_BLOCKED
        else -> Verdict.TLS_INTERFERENCE
    }
}

internal fun interface SecureProbe {
    /** TLS handshake with SNI [host] over the already-connected [base] socket, then one HTTP request. */
    fun probe(base: Socket, host: String, port: Int, timeoutMs: Int): List<StageResult>
}

internal class DefaultSecureProbe(
    private val factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : SecureProbe {
    override fun probe(base: Socket, host: String, port: Int, timeoutMs: Int): List<StageResult> {
        val ssl: SSLSocket
        try {
            // autoClose=false: the caller owns and closes the base socket.
            ssl = factory.createSocket(base, host, port, false) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.sslParameters = ssl.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            return listOf(StageResult(CheckStage.TLS, false, describe(e)))
        }
        val tls = StageResult(CheckStage.TLS, true, ssl.session.protocol)
        // A handshake proves the ClientHello got through. One real request proves data flows after it.
        val http = try {
            val request = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Kinescope\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.flush()
            val line = ssl.inputStream.bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
            if (line.startsWith("HTTP/")) {
                StageResult(CheckStage.HTTP, true, line.take(32))
            } else {
                StageResult(CheckStage.HTTP, false, "unexpected reply")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            StageResult(CheckStage.HTTP, false, describe(e))
        }
        return listOf(tls, http)
    }
}

internal fun describe(error: Throwable): String {
    val name = error.javaClass.simpleName
    val message = error.message?.replace('\n', ' ')?.trim().orEmpty()
    return if (message.isEmpty()) name else "$name: ${message.take(80)}"
}

/**
 * Layer-by-layer reachability check of one host: DNS, TCP, TLS with SNI, then an HTTP request.
 * The same steps run through the bypass engine when an endpoint is given, so the result says
 * which layer a network interferes with and whether the engine gets around it.
 *
 * It checks one host at a time. Video servers (*.googlevideo.com) are many and can behave
 * differently, so a real download remains the final test.
 */
internal class NetworkCheck(
    private val stageTimeoutMs: Int = 3_000,
    private val secure: SecureProbe = DefaultSecureProbe()
) {
    fun run(engine: SocksEndpoint?, host: String = DEFAULT_HOST): NetworkCheckReport =
        NetworkCheckReport(
            host = host,
            direct = checkDirect(host),
            bypassed = engine?.let { checkViaBypass(it, host) }
        )

    fun checkDirect(host: String, port: Int = HTTPS_PORT): PathResult {
        val stages = mutableListOf<StageResult>()
        val addresses = try {
            resolveBounded(host, stageTimeoutMs)
        } catch (e: IOException) {
            stages += StageResult(CheckStage.DNS, false, describe(e))
            return PathResult(false, stages)
        }
        stages += StageResult(CheckStage.DNS, true, "${addresses.size} address(es)")

        val socket = Socket()
        try {
            var lastError: IOException? = null
            var connected = false
            for (address in addresses.take(MAX_ADDRESS_ATTEMPTS)) {
                try {
                    socket.connect(InetSocketAddress(address, port), stageTimeoutMs)
                    connected = true
                    break
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (!connected) {
                stages += StageResult(CheckStage.TCP, false, lastError?.let { describe(it) } ?: "no address")
                return PathResult(false, stages)
            }
            stages += StageResult(CheckStage.TCP, true, "connected")
            stages += secure.probe(socket, host, port, stageTimeoutMs)
        } finally {
            closeQuietly(socket)
        }
        return PathResult(false, stages)
    }

    fun checkViaBypass(engine: SocksEndpoint, host: String, port: Int = HTTPS_PORT): PathResult {
        val stages = mutableListOf<StageResult>()
        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress(engine.host, engine.port), stageTimeoutMs)
                socket.soTimeout = stageTimeoutMs
                val problem = Socks5.greet(socket.getInputStream(), socket.getOutputStream())
                if (problem != null) {
                    stages += StageResult(CheckStage.PROXY, false, problem)
                    return PathResult(true, stages)
                }
            } catch (e: IOException) {
                stages += StageResult(CheckStage.PROXY, false, describe(e))
                return PathResult(true, stages)
            }
            stages += StageResult(CheckStage.PROXY, true, "SOCKS5 without authentication")

            val connect = try {
                Socks5.connectByName(socket.getInputStream(), socket.getOutputStream(), host, port)
            } catch (e: IOException) {
                ConnectResult(false, null, describe(e))
            }
            stages += StageResult(CheckStage.CONNECT, connect.ok, connect.detail, connect.replyCode)
            if (!connect.ok) return PathResult(true, stages)
            stages += secure.probe(socket, host, port, stageTimeoutMs)
        } finally {
            closeQuietly(socket)
        }
        return PathResult(true, stages)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val DEFAULT_HOST = "www.youtube.com"
        private const val HTTPS_PORT = 443
        private const val MAX_ADDRESS_ATTEMPTS = 4
    }
}
