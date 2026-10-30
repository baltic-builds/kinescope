package com.kinescope.app

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 57: a host counts as reached only when its whole response arrived (the rule ByeByeDPI's strategy test uses). */
class HttpBodyJudgeTest {
    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))

    /** Serves [data], then fails with [error] instead of ending (a stalled or reset connection). */
    private class Failing(private val data: ByteArray, private val error: IOException) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else throw error
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) throw error
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun aFixedLengthBodyThatArrivedInFullIsComplete() {
        val body = "x".repeat(100)
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n$body"))
        assertEquals(BodyOutcome.COMPLETE, result.outcome)
        assertEquals(200, result.status)
        assertEquals(100, result.bytes)
        assertTrue(result.ok)
    }

    @Test
    fun anErrorStatusStillCountsWhenTheBodyIsWhole() {
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 404 Not Found\r\nContent-Length: 3\r\n\r\nabc"))
        assertTrue(result.ok)
        assertEquals(404, result.status)
    }

    @Test
    fun aBodyThatStopsBeforeTheAnnouncedLengthIsTruncated() {
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(40)))
        assertEquals(BodyOutcome.TRUNCATED, result.outcome)
        assertEquals("short", result.why)
        assertEquals(40, result.bytes)
        assertFalse(result.ok)
    }

    @Test
    fun aStallInTheMiddleOfTheBodyIsTruncated() {
        val head = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(16)
        val result = HttpBodyJudge.judge(Failing(head.toByteArray(), SocketTimeoutException("read timed out")))
        assertEquals(BodyOutcome.TRUNCATED, result.outcome)
        assertEquals("stall", result.why)
    }

    @Test
    fun aResetInTheMiddleOfTheBodyIsTruncated() {
        val head = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(16)
        val result = HttpBodyJudge.judge(Failing(head.toByteArray(), IOException("Connection reset")))
        assertEquals(BodyOutcome.TRUNCATED, result.outcome)
        assertEquals("reset", result.why)
    }

    @Test
    fun aChunkedBodyIsCompleteOnlyAtTheTerminatingChunk() {
        val whole = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n3\r\nabc\r\n0\r\n\r\n"
        val ok = HttpBodyJudge.judge(bytes(whole))
        assertTrue(ok.ok)
        assertEquals(8, ok.bytes)

        val cut = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n3\r\nab"))
        assertEquals(BodyOutcome.TRUNCATED, cut.outcome)
        val noEnd = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n"))
        assertEquals(BodyOutcome.TRUNCATED, noEnd.outcome)
    }

    @Test
    fun aBadChunkSizeIsTruncated() {
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nhello\r\n"))
        assertEquals(BodyOutcome.TRUNCATED, result.outcome)
        assertEquals("badchunk", result.why)
    }

    @Test
    fun aBodyWithNoAnnouncedLengthIsReadToTheEndOfTheStream() {
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nsome text"))
        assertTrue(result.ok)
        assertEquals(9, result.bytes)
        val stalled = HttpBodyJudge.judge(
            Failing("HTTP/1.1 200 OK\r\n\r\nsome".toByteArray(), SocketTimeoutException("read timed out"))
        )
        assertEquals(BodyOutcome.TRUNCATED, stalled.outcome)
    }

    @Test
    fun noBodyStatusesAreCompleteWithoutReadingAnything() {
        assertTrue(HttpBodyJudge.judge(bytes("HTTP/1.1 204 No Content\r\n\r\n")).ok)
        assertTrue(HttpBodyJudge.judge(bytes("HTTP/1.1 304 Not Modified\r\nContent-Length: 500\r\n\r\n")).ok)
    }

    @Test
    fun aZeroLengthBodyIsComplete() {
        assertTrue(HttpBodyJudge.judge(bytes("HTTP/1.1 301 Moved\r\nLocation: /x\r\nContent-Length: 0\r\n\r\n")).ok)
    }

    @Test
    fun aBodyLargerThanTheCapIsCompleteOnceTheCapArrived() {
        val data = "HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\n\r\n" + "x".repeat(70_000)
        val result = HttpBodyJudge.judge(bytes(data), maxBodyBytes = 64 * 1024)
        assertTrue(result.ok)
        assertEquals("capped", result.why)
        assertEquals(64 * 1024, result.bytes)
    }

    @Test
    fun nothingAtAllIsNoResponse() {
        val empty = HttpBodyJudge.judge(bytes(""))
        assertEquals(BodyOutcome.NO_RESPONSE, empty.outcome)
        assertEquals("eof", empty.why)
        val timeout = HttpBodyJudge.judge(Failing(ByteArray(0), SocketTimeoutException("read timed out")))
        assertEquals(BodyOutcome.NO_RESPONSE, timeout.outcome)
        assertEquals("timeout", timeout.why)
        val reset = HttpBodyJudge.judge(Failing(ByteArray(0), IOException("Connection reset")))
        assertEquals("reset", reset.why)
    }

    @Test
    fun bytesThatAreNotAnHttpResponseAreNoResponse() {
        val result = HttpBodyJudge.judge(bytes("\u0015\u0003\u0003\u0000\u0002\u0002\u0028 garbage garbage\r\n\r\n"))
        assertEquals(BodyOutcome.NO_RESPONSE, result.outcome)
        assertEquals("badhead", result.why)
        assertEquals("badhead", HttpBodyJudge.judge(bytes("HTTP/2 200\r\n\r\n")).why)
    }

    @Test
    fun anOversizedHeaderIsRefused() {
        val result = HttpBodyJudge.judge(bytes("HTTP/1.1 200 OK\r\nX-Pad: " + "a".repeat(HttpBodyJudge.MAX_HEADER_BYTES + 100)))
        assertEquals("badhead", result.why)
    }

    @Test
    fun theDeadlineEndsAReadThatWouldContinue() {
        var now = 0L
        val ticking = object : InputStream() {
            override fun read(): Int = 'x'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val head = "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray()
                if (now == 0L) {
                    System.arraycopy(head, 0, b, off, head.size)
                    now = 10
                    return head.size
                }
                now += 10
                java.util.Arrays.fill(b, off, off + minOf(len, 100), 'x'.code.toByte())
                return minOf(len, 100)
            }
        }
        val result = HttpBodyJudge.judge(ticking, deadlineNanos = 100L, clock = { now })
        assertEquals(BodyOutcome.TRUNCATED, result.outcome)
        assertEquals("stall", result.why)
    }

    @Test
    fun headersWithBareLineFeedsAreAccepted() {
        assertTrue(HttpBodyJudge.judge(bytes("HTTP/1.0 200 OK\nContent-Length: 2\n\nhi")).ok)
    }
}
