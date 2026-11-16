package com.kinescope.app

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsFallbackTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun theQueryIsOneRecursiveAQuestion() {
        val query = DnsWire.buildQuery(0x1234, "www.youtube.com")!!
        // header: id, flags 0x0100, one question, nothing else
        assertArrayEquals(bytes(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0), query.copyOfRange(0, 12))
        // 3www7youtube3com0, type A, class IN
        val name = bytes(3) + "www".toByteArray() + bytes(7) + "youtube".toByteArray() + bytes(3) + "com".toByteArray() + bytes(0)
        assertArrayEquals(name + bytes(0, 1, 0, 1), query.copyOfRange(12, query.size))
    }

    @Test
    fun aHostThatIsNotAPlainNameIsRefused() {
        assertNull(DnsWire.buildQuery(1, ""))
        assertNull(DnsWire.buildQuery(1, "a..b"))
        assertNull(DnsWire.buildQuery(1, "bad host.com"))
        assertNull(DnsWire.buildQuery(1, "x".repeat(64) + ".com"))
    }

    private fun answer(id: Int, rcode: Int = 0, vararg records: ByteArray, question: Boolean = true): ByteArray {
        val header = bytes(id shr 8, id and 0xFF, 0x81, 0x80 or rcode, 0, if (question) 1 else 0, 0, records.size, 0, 0, 0, 0)
        val q = if (question) bytes(3) + "foo".toByteArray() + bytes(0, 0, 1, 0, 1) else ByteArray(0)
        return header + q + records.fold(ByteArray(0)) { a, b -> a + b }
    }

    /** An answer record whose name is a pointer to the question's name (offset 12). */
    private fun a(vararg ip: Int) = bytes(0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + bytes(*ip)
    private fun cname() = bytes(0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0, 0x0C)

    @Test
    fun theAddressesOfAnAnswerAreRead() {
        val parsed = DnsWire.parseIpv4(answer(7, 0, cname(), a(142, 250, 1, 2), a(142, 250, 1, 3)), 7)!!
        assertEquals(2, parsed.size)
        assertArrayEquals(bytes(142, 250, 1, 2), parsed[0])
    }

    @Test
    fun aFailedOrForeignOrBrokenAnswerGivesNothing() {
        assertNull(DnsWire.parseIpv4(answer(7, 3), 7)) // NXDOMAIN
        assertNull(DnsWire.parseIpv4(answer(8, 0, a(1, 2, 3, 4)), 7)) // another query's answer
        assertNull(DnsWire.parseIpv4(bytes(0, 7, 0x81), 7)) // too short
        val cut = answer(7, 0, a(1, 2, 3, 4))
        assertNull(DnsWire.parseIpv4(cut.copyOf(cut.size - 2), 7)) // an answer that runs off the packet
        assertEquals(emptyList<ByteArray>(), DnsWire.parseIpv4(answer(7, 0), 7)) // no A record
    }

    private fun addr(vararg ip: Int) = InetAddress.getByAddress(bytes(*ip)) as Inet4Address

    @Test
    fun aRememberedAddressIsUsedWithoutAsking() {
        DnsFallback.clear()
        DnsFallback.remember("remembered.example", listOf(addr(10, 0, 0, 1)))
        val found = DnsFallback.ipv4("remembered.example") { _, _, _ -> throw AssertionError("must not ask") }
        assertEquals("10.0.0.1", found!!.hostAddress)
    }

    @Test
    fun theSecondResolverIsAskedWhenTheFirstFails() {
        DnsFallback.clear()
        val asked = mutableListOf<String>()
        val found = DnsFallback.ipv4("second.example") { _, server, _ ->
            asked += server
            if (server == "1.1.1.1") throw IOException("timeout") else listOf(addr(8, 8, 4, 4))
        }
        assertNotNull(found)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), asked)
    }

    @Test
    fun aHostNobodyAnswersIsNotAskedAgainAtOnce() {
        DnsFallback.clear()
        var calls = 0
        val query = { _: String, _: String, _: Int -> calls++; emptyList<Inet4Address>() }
        assertNull(DnsFallback.ipv4("dead.example", now = 1_000L, query = query))
        val after = calls
        assertNull(DnsFallback.ipv4("dead.example", now = 2_000L, query = query))
        assertEquals(after, calls)
        // later the question is asked again
        assertNull(DnsFallback.ipv4("dead.example", now = 60_000L, query = query))
        assertTrue(calls > after)
    }

    @Test
    fun theModeEndsByItself() {
        DnsFallback.clear()
        assertFalse(DnsFallback.active(0L))
        DnsFallback.mark(1_000L)
        assertTrue(DnsFallback.active(1_001L))
        assertFalse(DnsFallback.active(1_000L + DnsFallback.BROKEN_MS))
        DnsFallback.clear()
    }
}
