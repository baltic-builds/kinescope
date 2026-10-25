package com.kinescope.app

/** The VPN must never claim a working route on engine startup alone. */
internal object TunnelStartupPolicy {
    fun accepts(healthPassed: Boolean, stopping: Boolean): Boolean = healthPassed && !stopping

    /** Only penalize failed candidates if a different strategy actually passed on this network. */
    fun failuresToLearn(failed: List<String>, winner: String?): List<String> =
        if (winner == null) emptyList() else failed.filter { it != winner }.distinct()
}
