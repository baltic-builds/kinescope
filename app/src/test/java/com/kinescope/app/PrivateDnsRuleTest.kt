package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 57: the tunnel's DNS rule and its configuration (ByeByeDPI's defaults: no DNS of ours under Private DNS, IPv4 only). */
class PrivateDnsRuleTest {
    @Test
    fun aPrivateDnsHostNameMeansTheTunnelAddsNoDnsServer() {
        assertNull(PrivateDns.tunnelDnsServer(PrivateDnsState.Strict("dns.example")))
    }

    @Test
    fun withoutOneTheTunnelUsesTheFixedResolver() {
        assertEquals("1.1.1.1", PrivateDns.tunnelDnsServer(PrivateDnsState.Off))
        assertEquals("1.1.1.1", PrivateDns.tunnelDnsServer(PrivateDnsState.Unknown))
    }

    @Test
    fun theLabelNeverContainsTheHostName() {
        assertEquals("strict", PrivateDns.label(PrivateDnsState.Strict("secret-profile-id.dns.example")))
        assertEquals("off", PrivateDns.label(PrivateDnsState.Off))
        assertEquals("unknown", PrivateDns.label(PrivateDnsState.Unknown))
    }

    @Test
    fun theBridgeConfigurationIsIpv4OnlyAndCarriesUdpOverSocks() {
        val text = hevTunnelConfig(port = 41234, mtu = 1500)
        assertFalse(text.contains("ipv6"))
        assertFalse(text.contains("fc00"))
        assertTrue(text.contains("ipv4: 198.18.0.1"))
        assertTrue(text.contains("mtu: 1500"))
        assertTrue(text.contains("port: 41234"))
        assertTrue(text.contains("udp: 'udp'"))
        assertTrue(text.endsWith("\n"))
    }
}
