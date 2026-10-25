package com.kinescope.app

/**
 * Patch 53: the two kinds of connection the strategies are rated for, each on its own.
 *
 * A strategy that gets through the filter of a Wi-Fi network says nothing about the one on the mobile operator's
 * network: the equipment, and so the way traffic is inspected, is different. Everything the app learns about a strategy
 * (the last test, real downloads, the tunnel's traffic, failures, the chain, the pin) is therefore kept per [NetClass].
 *
 * [suffix] is appended to the name of every preference key that belongs to a class. [WIFI] has none, so the data of
 * builds from before patch 53, all of which was collected on whatever network the phone was on and, in practice, on
 * Wi-Fi, stays where it was and becomes the Wi-Fi record without a migration.
 */
internal enum class NetClass(val key: String, val suffix: String) {
    /** Wi-Fi and Ethernet: an unmetered local network. */
    WIFI("wifi", ""),

    /** Mobile data, and a Wi-Fi network that Android marks as metered (in practice a phone's hotspot). */
    MOBILE("mobile", "_mobile");

    /** The other class: where a class with no record of its own borrows its first chain from. */
    val other: NetClass get() = if (this == WIFI) MOBILE else WIFI

    companion object {
        /**
         * The class of a connection from what the system reports about it; null when it says nothing usable (no
         * connection, or only a VPN with no transport underneath). Wi-Fi and Ethernet count as [WIFI] unless Android
         * marks the Wi-Fi as metered: that is a phone hotspot, whose traffic leaves through a mobile operator, and
         * it behaves like mobile data. Cellular alone is [MOBILE].
         */
        fun classify(wifi: Boolean, ethernet: Boolean, cellular: Boolean, metered: Boolean): NetClass? = when {
            ethernet -> WIFI
            wifi -> if (metered) MOBILE else WIFI
            cellular -> MOBILE
            else -> null
        }
    }
}

/**
 * Patch 53: when the running tunnel follows the connection to the other class. A handover between Wi-Fi and mobile data
 * flickers (the new network is reported before the old one is gone, and the other way round), so the tunnel switches only
 * after the reported class has differed from the one it runs on for [STABLE_TICKS] readings in a row. Pure,
 * unit-tested in `NetClassTest`.
 */
internal object NetSwitch {
    /** Readings (the tunnel's monitor ticks, 500 ms each) the new class must hold: two seconds. */
    const val STABLE_TICKS = 4

    /** The run of readings that differ from [running] after one more reading [seen] (null = no connection, which is not a change). */
    fun streakAfter(streak: Int, running: NetClass, seen: NetClass?): Int =
        if (seen == null || seen == running) 0 else streak + 1

    fun shouldSwitch(streak: Int): Boolean = streak >= STABLE_TICKS
}
