package com.kinescope.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** Patch 57: how far one HTTP response got. */
internal enum class BodyOutcome {
    /** The status line and the whole body arrived (or enough of it to reach the cap). Any status counts, a 404 included. */
    COMPLETE,

    /** The headers arrived but the body stopped short: the signature of a connection a network lets through and then starves. */
    TRUNCATED,

    /** No usable response: nothing arrived, or what arrived was not an HTTP response. */
    NO_RESPONSE
}

/** Patch 57: [why] is a short log token (`ok`, `capped`, `short`, `stall`, `reset`, `eof`, `timeout`, `badhead`, `badchunk`). */
internal data class BodyJudgement(val outcome: BodyOutcome, val status: Int, val bytes: Int, val why: String) {
    val ok: Boolean get() = outcome == BodyOutcome.COMPLETE
}

/**
 * Patch 57: decides whether an HTTP/1.x response was received in full. Pure; it reads an [InputStream] (a TLS stream in the app,
 * a byte array in the tests).
 *
 * Why it exists: a status line or a few kilobytes prove very little. A filter that lets a connection start and then starves it
 * (the "first ~16 KB pass, then nothing" pattern) shows up only as a body that ends before the length the server announced.
 * ByeByeDPI counts a host as reached only when the announced length arrived; this is the same rule, extended to chunked bodies and to
 * bodies with no announced length (read to the end of the stream).
 *
 * Framing, in this order: no body for 1xx/204/304; `Transfer-Encoding: chunked` until the terminating chunk; `Content-Length`;
 * otherwise until the stream ends. A body larger than [maxBodyBytes] is judged complete when that many bytes arrived: it was
 * flowing, and a probe must stay cheap.
 */
internal object HttpBodyJudge {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val DEFAULT_MAX_BODY = 256 * 1024

    private val statusLine = Regex("^HTTP/1\\.[01] (\\d{3})")

    /**
     * Reads one response from [input]. [deadlineNanos] is an absolute `System.nanoTime()` value checked between reads; a read that
     * blocks longer than the socket's own timeout ends with a [SocketTimeoutException], which counts as a stall.
     */
    fun judge(
        input: InputStream,
        maxBodyBytes: Int = DEFAULT_MAX_BODY,
        deadlineNanos: Long = Long.MAX_VALUE,
        clock: () -> Long = System::nanoTime
    ): BodyJudgement {
        val source = Source(input, deadlineNanos, clock)
        val head = try {
            readHead(source)
        } catch (e: SocketTimeoutException) {
            return BodyJudgement(BodyOutcome.NO_RESPONSE, 0, 0, "timeout")
        } catch (e: IOException) {
            return BodyJudgement(BodyOutcome.NO_RESPONSE, 0, 0, "reset")
        } ?: return BodyJudgement(BodyOutcome.NO_RESPONSE, 0, 0, "eof")
        if (head.status < 0) return BodyJudgement(BodyOutcome.NO_RESPONSE, 0, 0, "badhead")

        val status = head.status
        if (status in 100..199 || status == 204 || status == 304) return BodyJudgement(BodyOutcome.COMPLETE, status, 0, "ok")
        return try {
            when {
                head.chunked -> readChunked(source, status, maxBodyBytes)
                head.length != null -> readFixed(source, status, head.length, maxBodyBytes)
                else -> readToEnd(source, status, maxBodyBytes)
            }
        } catch (e: SocketTimeoutException) {
            BodyJudgement(BodyOutcome.TRUNCATED, status, source.bodyBytes, "stall")
        } catch (e: IOException) {
            BodyJudgement(BodyOutcome.TRUNCATED, status, source.bodyBytes, "reset")
        }
    }

    private class Head(val status: Int, val chunked: Boolean, val length: Long?)

    /** Null when the stream ended before any byte; a head with status -1 when the bytes were not an HTTP response. */
    private fun readHead(source: Source): Head? {
        val raw = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = source.read()
            if (b < 0) return if (raw.size() == 0) null else Head(-1, false, null)
            raw.write(b)
            tail = (tail shl 8) or b
            if (raw.size() >= 4 && tail == 0x0D0A0D0A) break
            if (raw.size() >= 2 && (tail and 0xFFFF) == 0x0A0A) break
            if (raw.size() > MAX_HEADER_BYTES) return Head(-1, false, null)
        }
        val lines = String(raw.toByteArray(), Charsets.ISO_8859_1).split("\n").map { it.trimEnd('\r') }
        val status = statusLine.find(lines.first())?.groupValues?.get(1)?.toInt() ?: return Head(-1, false, null)
        var chunked = false
        var length: Long? = null
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "transfer-encoding" -> if (value.lowercase().contains("chunked")) chunked = true
                "content-length" -> length = value.toLongOrNull()?.takeIf { it >= 0 }
            }
        }
        return Head(status, chunked, length)
    }

    private fun readFixed(source: Source, status: Int, announced: Long, cap: Int): BodyJudgement {
        val target = minOf(announced, cap.toLong())
        var got = 0L
        while (got < target) {
            if (source.readBody() < 0) return BodyJudgement(BodyOutcome.TRUNCATED, status, got.toInt(), "short")
            got++
        }
        return BodyJudgement(BodyOutcome.COMPLETE, status, got.toInt(), if (announced > cap) "capped" else "ok")
    }

    private fun readToEnd(source: Source, status: Int, cap: Int): BodyJudgement {
        var got = 0
        while (got < cap) {
            if (source.readBody() < 0) return BodyJudgement(BodyOutcome.COMPLETE, status, got, "ok")
            got++
        }
        return BodyJudgement(BodyOutcome.COMPLETE, status, got, "capped")
    }

    private fun readChunked(source: Source, status: Int, cap: Int): BodyJudgement {
        var got = 0L
        while (true) {
            val line = readLine(source) ?: return BodyJudgement(BodyOutcome.TRUNCATED, status, got.toInt(), "short")
            val size = line.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 }
                ?: return BodyJudgement(BodyOutcome.TRUNCATED, status, got.toInt(), "badchunk")
            if (size == 0L) return BodyJudgement(BodyOutcome.COMPLETE, status, got.toInt(), "ok")
            var left = size
            while (left > 0) {
                if (source.readBody() < 0) return BodyJudgement(BodyOutcome.TRUNCATED, status, got.toInt(), "short")
                left--
                got++
                if (got >= cap) return BodyJudgement(BodyOutcome.COMPLETE, status, got.toInt(), "capped")
            }
            // The CRLF that ends the chunk data.
            if (source.read() < 0 || source.read() < 0) return BodyJudgement(BodyOutcome.TRUNCATED, status, got.toInt(), "short")
        }
    }

    /** One line without its terminator, or null at the end of the stream. */
    private fun readLine(source: Source): String? {
        val line = StringBuilder()
        while (true) {
            val b = source.read()
            if (b < 0) return null
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
            if (line.length > 64) return line.toString()
        }
    }

    /** A buffered view of the stream that counts body bytes and honours the deadline. */
    private class Source(private val input: InputStream, private val deadlineNanos: Long, private val clock: () -> Long) {
        private val buffer = ByteArray(8192)
        private var pos = 0
        private var limit = 0
        var bodyBytes = 0
            private set

        /** The next byte, or -1 at the end of the stream. */
        fun read(): Int {
            if (pos == limit) {
                if (clock() > deadlineNanos) throw SocketTimeoutException("deadline")
                val count = input.read(buffer, 0, buffer.size)
                if (count < 0) return -1
                if (count == 0) throw IOException("empty read")
                pos = 0
                limit = count
            }
            return buffer[pos++].toInt() and 0xFF
        }

        fun readBody(): Int {
            val b = read()
            if (b >= 0) bodyBytes++
            return b
        }
    }
}
