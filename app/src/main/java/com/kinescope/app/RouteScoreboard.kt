package com.kinescope.app

/**
 * Patch 35: the route that carried the most recent download, remembered for ten minutes so the next
 * download starts on it without another check. In memory only: after a restart, or once the
 * network may have changed, the normal chain and checks apply again.
 *
 * Patch 53: one remembered route per connection class. A route that carried a download on Wi-Fi says nothing about
 * mobile data, so it is never offered for the other class.
 */
internal object RouteScoreboard {
    private const val TTL_MS = 10L * 60L * 1000L
    private val routes = arrayOfNulls<String>(NetClass.entries.size)
    private val times = LongArray(NetClass.entries.size)

    @Synchronized
    fun record(cls: NetClass, route: String, now: Long) {
        routes[cls.ordinal] = route
        times[cls.ordinal] = now
    }

    @Synchronized
    fun recentGood(cls: NetClass, now: Long): String? = routes[cls.ordinal]?.takeIf { now - times[cls.ordinal] in 0..TTL_MS }
}
