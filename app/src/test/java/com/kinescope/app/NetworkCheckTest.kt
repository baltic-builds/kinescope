package com.kinescope.app

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCheckTest {
    /** One-connection-at-a-time SOCKS5 stand-in. [reply] receives the connect request and writes the answer. */
    private class FakeSocks(
        private val method: Int = 0x00,
        private val version: Int = 0x05,
        private val reply: (DataInputStream, Socket, CopyOnWriteArrayList<String>) -> Unit
    ) : AutoCloseable {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<String>()
        val endpoint = SocksEndpoint("127.0.0.1", server.localPort)

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    try {
                        val input = DataInputStream(socket.getInputStream())
                        input.readFully(ByteArray(3))
                        socket.getOutputStream().write(byteArrayOf(version.toByte(), method.toByte()))
                        socket.getOutputStream().flush()
                        if (version == 5 && method == 0) reply(input, socket, requests)
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun connectReply(code: Int): (DataInputStream, Socket, CopyOnWriteArrayList<String>) -> Unit = { input, socket, log ->
        val head = ByteArray(5)
        input.readFully(head)
        val name = ByteArray(head[4].toInt() and 0xFF)
        input.readFully(name)
        val port = ByteArray(2)
        input.readFully(port)
        log += "${String(name)}:${((port[0].toInt() and 0xFF) shl 8) or (port[1].toInt() and 0xFF)} atyp=${head[3].toInt()}"
        socket.getOutputStream().write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        socket.getOutputStream().flush()
        if (code == 0) Thread.sleep(200)
    }

    private val okSecure = SecureProbe { _, _, _, _ ->
        listOf(StageResult(CheckStage.TLS, true, "TLSv1.3"), StageResult(CheckStage.HTTP, true, "HTTP/1.1 200"))
    }

    @Test
    fun greetingAcceptsNoAuthAndExplainsRefusals() {
        FakeSocks(method = 0x00, reply = connectReply(0)).use { assertTrue(isSocksReady(it.endpoint)) }
        FakeSocks(method = 0xFF, reply = connectReply(0)).use { assertFalse(isSocksReady(it.endpoint)) }
        FakeSocks(version = 0x04, reply = connectReply(0)).use { assertFalse(isSocksReady(it.endpoint)) }
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).let { s -> SocksEndpoint("127.0.0.1", s.localPort).also { s.close() } }
        assertFalse(isSocksReady(closed))
        assertFalse(isPortOpen(closed))
    }

    @Test
    fun connectByNameSendsTheHostNameAndParsesTheReply() {
        FakeSocks(reply = connectReply(0)).use { fake ->
            val report = NetworkCheck(1_000, okSecure).checkViaBypass(fake.endpoint, "www.youtube.com")
            assertTrue(report.ok)
            assertTrue(report.viaBypass)
            assertEquals(listOf(CheckStage.PROXY, CheckStage.CONNECT, CheckStage.TLS, CheckStage.HTTP), report.stages.map { it.stage })
            assertEquals(listOf("www.youtube.com:443 atyp=3"), fake.requests.toList())
        }
    }

    @Test
    fun connectFailureKeepsTheSocksReplyCode() {
        FakeSocks(reply = connectReply(0x04)).use { fake ->
            val report = NetworkCheck(1_000, okSecure).checkViaBypass(fake.endpoint, "www.youtube.com")
            assertFalse(report.ok)
            assertEquals(CheckStage.CONNECT, report.failedStage)
            assertEquals(0x04, report.failed?.replyCode)
        }
    }

    @Test
    fun anUnreachableEngineFailsAtTheProxyStage() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).let { s -> SocksEndpoint("127.0.0.1", s.localPort).also { s.close() } }
        val report = NetworkCheck(1_000, okSecure).checkViaBypass(closed, "www.youtube.com")
        assertEquals(CheckStage.PROXY, report.failedStage)
    }

    private fun path(bypass: Boolean, vararg stages: Pair<CheckStage, Boolean>, reply: Int? = null) =
        PathResult(bypass, stages.map { (stage, ok) -> StageResult(stage, ok, "", if (!ok) reply else null) })

    private val directOk = path(false, CheckStage.DNS to true, CheckStage.TCP to true, CheckStage.TLS to true, CheckStage.HTTP to true)

    @Test
    fun verdictsNameTheBlockedLayerAndWhetherTheBypassHelps() {
        val dnsFail = path(false, CheckStage.DNS to false)
        val tcpFail = path(false, CheckStage.DNS to true, CheckStage.TCP to false)
        val tlsFail = path(false, CheckStage.DNS to true, CheckStage.TCP to true, CheckStage.TLS to false)
        val viaOk = path(true, CheckStage.PROXY to true, CheckStage.CONNECT to true, CheckStage.TLS to true, CheckStage.HTTP to true)
        val viaNoEngine = path(true, CheckStage.PROXY to false)
        val viaTlsFail = path(true, CheckStage.PROXY to true, CheckStage.CONNECT to true, CheckStage.TLS to false)
        val viaNoHost = path(true, CheckStage.PROXY to true, CheckStage.CONNECT to false, reply = 0x04)
        val viaRefused = path(true, CheckStage.PROXY to true, CheckStage.CONNECT to false, reply = 0x05)

        fun verdict(direct: PathResult, via: PathResult?) = verdictOf(NetworkCheckReport("h", direct, via))
        assertEquals(Verdict.DIRECT_OK, verdict(directOk, null))
        assertEquals(Verdict.DIRECT_OK, verdict(directOk, viaOk))
        assertEquals(Verdict.DNS_BLOCKED, verdict(dnsFail, null))
        assertEquals(Verdict.TCP_BLOCKED, verdict(tcpFail, null))
        assertEquals(Verdict.TLS_INTERFERENCE, verdict(tlsFail, null))
        assertEquals(Verdict.BYPASS_FIXES, verdict(tlsFail, viaOk))
        assertEquals(Verdict.BYPASS_UNAVAILABLE, verdict(tlsFail, viaNoEngine))
        assertEquals(Verdict.BYPASS_ALSO_FAILS, verdict(tlsFail, viaTlsFail))
        // DNS is blocked and the engine cannot resolve the name either: nothing a DPI strategy can do.
        assertEquals(Verdict.DNS_BLOCKS_BYPASS, verdict(dnsFail, viaNoHost))
        assertEquals(Verdict.BYPASS_ALSO_FAILS, verdict(dnsFail, viaRefused))
        assertEquals(Verdict.BYPASS_ALSO_FAILS, verdict(tcpFail, viaNoHost))
    }
}
