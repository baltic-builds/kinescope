package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 64: the DNS choice of the YouTube tunnel (a ByeByeDPI-style picker; Yandex is a preset, Cloudflare stays the default). */
class TunnelDnsTest {
    @Test
    fun theDefaultIsStillCloudflareAndYandexIsAPreset() {
        assertEquals("1.1.1.1", TunnelDns.DEFAULT)
        assertEquals("77.88.8.8", TunnelDns.YANDEX)
        assertTrue(TunnelDns.YANDEX in TunnelDns.PRESETS)
    }

    @Test
    fun onlyThePresetsAreAccepted() {
        for (preset in TunnelDns.PRESETS) assertTrue(TunnelDns.isValid(preset))
        assertFalse(TunnelDns.isValid("8.8.4.4"))
        assertFalse(TunnelDns.isValid("not an address"))
    }

    @Test
    fun theChoiceIsTheServerTheTunnelHandsOut() {
        assertEquals("77.88.8.8", PrivateDns.tunnelDnsServer(PrivateDnsState.Off, TunnelDns.YANDEX))
        assertEquals("1.1.1.1", PrivateDns.tunnelDnsServer(PrivateDnsState.Off))
    }

    @Test
    fun theSystemChoiceAndAPrivateDnsHostNameAddNoServer() {
        assertNull(PrivateDns.tunnelDnsServer(PrivateDnsState.Off, TunnelDns.SYSTEM))
        assertNull(PrivateDns.tunnelDnsServer(PrivateDnsState.Strict("dns.example"), TunnelDns.YANDEX))
    }
}
