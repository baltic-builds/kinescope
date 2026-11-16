package com.kinescope.app

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Patch 63: the wire format of a plain DNS lookup over UDP, only as much as the bypass checks need: one question for an A record
 * (RFC 1035) and the IPv4 addresses of its answer. Pure, unit-tested in `DnsFallbackTest`.
 */
internal object DnsWire {
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val MAX_LABEL = 63

    /** A recursive query for the A record of [host]; null when [host] is not a plain ASCII name. */
    fun buildQuery(id: Int, host: String): ByteArray? {
        val labels = host.trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > MAX_LABEL || it.any { c -> c.code !in 0x21..0x7E } }) return null
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8)
        out.write(id and 0xFF)
        out.write(0x01) // flags: standard query, recursion desired
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // one question
        repeat(6) { out.write(0x00) } // no answers, authorities or additionals
        for (label in labels) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)
        out.write(0x00)
        out.write(TYPE_A)
        out.write(0x00)
        out.write(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * The IPv4 addresses of the A records in [response], for a reply to the query with [id]. Null when the packet is malformed, is not
     * an answer to that query, or says the lookup failed (a non-zero response code); an empty list when it answered with no A record.
     */
    fun parseIpv4(response: ByteArray, id: Int): List<ByteArray>? {
        if (response.size < 12) return null
        if (((response[0].toInt() and 0xFF) shl 8 or (response[1].toInt() and 0xFF)) != id) return null
        val flags = (response[2].toInt() and 0xFF) shl 8 or (response[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0 || flags and 0x000F != 0) return null
        val questions = u16(response, 4)
        val answers = u16(response, 6)
        var pos = 12
        repeat(questions) {
            pos = skipName(response, pos) ?: return null
            pos += 4
            if (pos > response.size) return null
        }
        val found = ArrayList<ByteArray>()
        repeat(answers) {
            pos = skipName(response, pos) ?: return null
            if (pos + 10 > response.size) return null
            val type = u16(response, pos)
            val klass = u16(response, pos + 2)
            val length = u16(response, pos + 8)
            pos += 10
            if (pos + length > response.size) return null
            if (type == TYPE_A && klass == CLASS_IN && length == 4) found += response.copyOfRange(pos, pos + 4)
            pos += length
        }
        return found
    }

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) shl 8 or (bytes[at + 1].toInt() and 0xFF)

    /** The position after the (possibly compressed) name that starts at [start]; null when it runs off the packet. */
    private fun skipName(bytes: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= bytes.size) return null
            val length = bytes[pos].toInt() and 0xFF
            when {
                length == 0 -> return pos + 1
                length and 0xC0 == 0xC0 -> return if (pos + 2 <= bytes.size) pos + 2 else null
                length and 0xC0 != 0 -> return null
                else -> pos += 1 + length
            }
        }
    }
}

/**
 * Patch 63: what the bypass checks do when the phone's own name lookup does not work.
 *
 * Read from two device reports (2026-11-04, two devices on one Wi-Fi, about fifteen minutes apart): every check ended in
 * `byp reach net=wifi none rc=4 ... dns=nohost a4=0 a6=0`, the app's own lookup of the probe host returned nothing, so the engine's
 * lookup (the same system resolver) did too, and the app called it "no route": the tunnel was taken down after a probe, could not be
 * started again for three minutes and gave up, and the strategy check on the other device was voided. The YouTube app itself is
 * given a public DNS server by the tunnel and connects by address, so a name lookup that fails says nothing about the strategy.
 *
 * So when a lookup is shown to fail, the checks connect through the engine BY ADDRESS, the way the YouTube app does: the address comes
 * from one the app saw before, or from a plain UDP query to a public resolver. The mode switches on for [BROKEN_MS] when
 * `DpiBypass.reach` finds that only the name lookup is broken (or when the tunnel's probe finds the system lookup dead), and every
 * connection a check makes then goes by address; it ends by itself, so a repaired lookup is used again. Downloads through
 * `socks5h` still resolve in the engine; this only keeps the CHECKS and the TUNNEL honest.
 */
internal object DnsFallback {
    const val BROKEN_MS = 10L * 60L * 1000L
    private const val NEGATIVE_MS = 20_000L
    private const val QUERY_TIMEOUT_MS = 1_500
    private val SERVERS = listOf("1.1.1.1", "8.8.8.8")

    @Volatile
    private var brokenUntil = 0L
    private val remembered = ConcurrentHashMap<String, Inet4Address>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    fun active(now: Long = System.currentTimeMillis()): Boolean = now < brokenUntil

    /** Switches the mode on (or extends it). Does not log: it is pure so that unit tests run without Android; callers log. */
    fun mark(now: Long = System.currentTimeMillis()) {
        brokenUntil = now + BROKEN_MS
    }

    fun clear() {
        brokenUntil = 0L
        failedUntil.clear()
    }

    /** Remembers an IPv4 address a lookup returned, for the time the lookup does not. */
    fun remember(host: String, addresses: List<InetAddress>) {
        addresses.filterIsInstance<Inet4Address>().firstOrNull()?.let { remembered[host] = it }
    }

    /**
     * An IPv4 address of [host]: one remembered from an earlier lookup, else the answer of a public resolver over UDP. Null when none
     * can be found (a host that failed is not asked again for [NEGATIVE_MS]). Blocking: call it from a background thread.
     */
    fun ipv4(
        host: String,
        now: Long = System.currentTimeMillis(),
        query: (host: String, server: String, timeoutMs: Int) -> List<Inet4Address> = ::udpQuery
    ): Inet4Address? {
        remembered[host]?.let { return it }
        if ((failedUntil[host] ?: 0L) > now) return null
        for (server in SERVERS) {
            val address = try {
                query(host, server, QUERY_TIMEOUT_MS).firstOrNull()
            } catch (e: IOException) {
                null
            }
            if (address != null) {
                remembered[host] = address
                return address
            }
        }
        failedUntil[host] = now + NEGATIVE_MS
        return null
    }

    private fun udpQuery(host: String, server: String, timeoutMs: Int): List<Inet4Address> {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val request = DnsWire.buildQuery(id, host) ?: return emptyList()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetAddress.getByName(server), 53)
            socket.send(DatagramPacket(request, request.size))
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val parsed = DnsWire.parseIpv4(buffer.copyOf(packet.length), id) ?: return emptyList()
            return parsed.map { InetAddress.getByAddress(it) as Inet4Address }
        }
    }
}
