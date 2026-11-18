package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 64: when the app updates itself (`LaunchUpdatePolicy`) and how a network is named (`NetworkKey`). */
class LaunchUpdateTest {
    private val now = 1_000_000_000_000L
    private val home = NetworkKey.wifi("Home")
    private val office = NetworkKey.wifi("Office")

    @Test
    fun theFirstLaunchAfterAnInstallUpdates() {
        assertEquals(UpdateReason.FIRST_LAUNCH, LaunchUpdatePolicy.decide(emptyList(), home, 0L, now))
    }

    @Test
    fun theFirstLaunchOnMobileDataUpdates() {
        assertEquals(UpdateReason.FIRST_LAUNCH, LaunchUpdatePolicy.decide(emptyList(), NetworkKey.MOBILE, 0L, now))
        // Later, with Wi-Fi known, the first time on mobile data is a new network.
        assertEquals(UpdateReason.NEW_NETWORK, LaunchUpdatePolicy.decide(listOf(home), NetworkKey.MOBILE, 0L, now))
    }

    @Test
    fun theSameNetworkDoesNotUpdateAgain() {
        assertEquals(UpdateReason.NONE, LaunchUpdatePolicy.decide(listOf(home), home, 0L, now))
        assertEquals(UpdateReason.NONE, LaunchUpdatePolicy.decide(listOf(home, NetworkKey.MOBILE), NetworkKey.MOBILE, 0L, now))
    }

    @Test
    fun aNewWifiNetworkUpdates() {
        assertEquals(UpdateReason.NEW_NETWORK, LaunchUpdatePolicy.decide(listOf(home), office, 0L, now))
    }

    @Test
    fun noConnectionNeverUpdates() {
        assertEquals(UpdateReason.NONE, LaunchUpdatePolicy.decide(emptyList(), null, 0L, now))
    }

    @Test
    fun anUpdateThatDidNotFinishIsNotRepeatedWithinTheGap() {
        assertEquals(UpdateReason.NONE, LaunchUpdatePolicy.decide(listOf(home), office, now - 1_000L, now))
        assertEquals(UpdateReason.NEW_NETWORK, LaunchUpdatePolicy.decide(listOf(home), office, now - LaunchUpdatePolicy.RETRY_GAP_MS, now))
    }

    @Test
    fun theOldestNetworkIsForgottenFirst() {
        var known = emptyList<String>()
        for (i in 1..(LaunchUpdatePolicy.MAX_KNOWN + 3)) known = LaunchUpdatePolicy.remember(known, "n$i")
        assertEquals(LaunchUpdatePolicy.MAX_KNOWN, known.size)
        assertFalse("n1" in known)
        assertEquals("n${LaunchUpdatePolicy.MAX_KNOWN + 3}", known.last())
        // A network seen again moves to the end and is not listed twice.
        known = LaunchUpdatePolicy.remember(known, known.first())
        assertEquals(LaunchUpdatePolicy.MAX_KNOWN, known.size)
    }

    @Test
    fun aNetworkIsOnlyEverStoredAsAHash() {
        assertNotEquals("Home", home)
        assertEquals(16, home.length)
        assertEquals(home, NetworkKey.wifi("Home"))
        assertNotEquals(home, office)
        assertNotEquals(NetworkKey.MOBILE, NetworkKey.ETHERNET)
    }

    @Test
    fun theSystemsNoNameAnswersAreNotNames() {
        assertNull(NetworkKey.cleanSsid(null))
        assertNull(NetworkKey.cleanSsid("<unknown ssid>"))
        assertNull(NetworkKey.cleanSsid("\"\""))
        assertNull(NetworkKey.cleanSsid("0x"))
        assertEquals("Home", NetworkKey.cleanSsid("\"Home\""))
        assertEquals("Café 5G", NetworkKey.cleanSsid(" Café 5G "))
    }

    @Test
    fun aSubnetIgnoresTheHostPartOfTheAddress() {
        val a = NetworkKey.subnet(byteArrayOf(192.toByte(), 168.toByte(), 1, 57), 24)
        val b = NetworkKey.subnet(byteArrayOf(192.toByte(), 168.toByte(), 1, 200.toByte()), 24)
        assertEquals("192.168.1.0/24", a)
        assertEquals(a, b)
        assertEquals("10.0.0.0/8", NetworkKey.subnet(byteArrayOf(10, 20, 30, 40), 8))
        assertEquals("192.168.0.0/20", NetworkKey.subnet(byteArrayOf(192.toByte(), 168.toByte(), 15, 9), 20))
    }

    @Test
    fun withoutANameTheGatewayAndTheSubnetTellNetworksApart() {
        val one = NetworkKey.linkFingerprint("192.168.1.1", "192.168.1.0/24")
        assertEquals(one, NetworkKey.linkFingerprint("192.168.1.1", "192.168.1.0/24"))
        assertNotEquals(one, NetworkKey.linkFingerprint("192.168.0.1", "192.168.0.0/24"))
        assertTrue(one.isNotEmpty())
    }
}
