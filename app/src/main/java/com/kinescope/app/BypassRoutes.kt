package com.kinescope.app

/**
 * Patch 34: the order in which a download tries its routes to YouTube.
 *
 * A route is either a verified bypass strategy line or [DIRECT] (no proxy). Pure, unit-tested.
 */
internal object BypassRoutes {
    const val DIRECT = "direct"

    /**
     * Verified strategies in rank order. When a third-party VPN is on, the direct route goes
     * first: the VPN already carries the traffic, and bypass tricks on top of it can only get in
     * the way. A route that already got a download through for this job goes before everything else.
     */
    fun order(chain: List<String>, systemVpn: Boolean, proven: String?): List<String> {
        val base = buildList {
            if (systemVpn) add(DIRECT)
            addAll(chain)
        }.distinct()
        if (proven == null || proven !in base) return base
        return listOf(proven) + base.filter { it != proven }
    }

    /** `direct` or `sN/M`, the compact name used in the log and the queue status. */
    fun label(route: String, chain: List<String>): String =
        if (route == DIRECT) DIRECT else "s${chain.indexOf(route) + 1}/${chain.size}"
}
