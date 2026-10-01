package com.kinescope.app

/**
 * Patch 35: the route that carried the most recent download, remembered for ten minutes so the next
 * download starts on it without another check. In memory only: after a restart, or once the
 * network may have changed, the normal chain and checks apply again.
 */
internal object RouteScoreboard {
    private const val TTL_MS = 10L * 60L * 1000L
    private var route: String? = null
    private var at = 0L

    @Synchronized
    fun record(route: String, now: Long) {
        this.route = route
        this.at = now
    }

    @Synchronized
    fun recentGood(now: Long): String? = route?.takeIf { now - at in 0..TTL_MS }

    @Synchronized
    fun forget() {
        route = null
    }
}
