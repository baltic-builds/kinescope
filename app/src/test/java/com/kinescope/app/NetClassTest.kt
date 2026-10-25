package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 53: which kind of connection a network is, and when a running tunnel follows a change of it. */
class NetClassTest {
    private fun classify(
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false,
        metered: Boolean = false
    ) = NetClass.classify(wifi, ethernet, cellular, metered)

    @Test
    fun unmeteredWifiAndEthernetAreWifi() {
        assertEquals(NetClass.WIFI, classify(wifi = true))
        assertEquals(NetClass.WIFI, classify(ethernet = true))
        assertEquals(NetClass.WIFI, classify(ethernet = true, metered = true))
    }

    @Test
    fun cellularIsMobile() {
        assertEquals(NetClass.MOBILE, classify(cellular = true, metered = true))
        assertEquals(NetClass.MOBILE, classify(cellular = true))
    }

    @Test
    fun meteredWifiIsAPhoneHotspotAndCountsAsMobile() {
        assertEquals(NetClass.MOBILE, classify(wifi = true, metered = true))
    }

    @Test
    fun aVpnWithNothingUnderneathAndNoNetworkSayNothing() {
        assertNull(classify())
        assertNull(classify(metered = true))
    }

    @Test
    fun wifiWinsWhenTheReportListsBothTransports() {
        assertEquals(NetClass.WIFI, classify(wifi = true, cellular = true))
    }

    @Test
    fun theClassesAreEachOthersOther() {
        assertEquals(NetClass.MOBILE, NetClass.WIFI.other)
        assertEquals(NetClass.WIFI, NetClass.MOBILE.other)
    }

    @Test
    fun theWifiRecordKeepsTheOldKeysAndMobileHasItsOwn() {
        assertEquals("", NetClass.WIFI.suffix)
        assertTrue(NetClass.MOBILE.suffix.isNotEmpty())
        assertEquals(setOf("wifi", "mobile"), NetClass.entries.map { it.key }.toSet())
    }

    @Test
    fun aClassChangeMustHoldForFourReadingsBeforeTheTunnelFollows() {
        var streak = 0
        val seen = listOf(NetClass.MOBILE, NetClass.MOBILE, NetClass.MOBILE, NetClass.MOBILE)
        val switched = mutableListOf<Boolean>()
        for (reading in seen) {
            streak = NetSwitch.streakAfter(streak, NetClass.WIFI, reading)
            switched += NetSwitch.shouldSwitch(streak)
        }
        assertEquals(listOf(false, false, false, true), switched)
    }

    @Test
    fun aFlickerOrALostConnectionResetsTheCount() {
        var streak = NetSwitch.streakAfter(0, NetClass.WIFI, NetClass.MOBILE)
        streak = NetSwitch.streakAfter(streak, NetClass.WIFI, NetClass.MOBILE)
        assertEquals(2, streak)
        // The old class is reported again: it was a handover glitch.
        assertEquals(0, NetSwitch.streakAfter(streak, NetClass.WIFI, NetClass.WIFI))
        // No connection is not a change to the other class.
        assertEquals(0, NetSwitch.streakAfter(streak, NetClass.WIFI, null))
        assertFalse(NetSwitch.shouldSwitch(0))
    }
}
