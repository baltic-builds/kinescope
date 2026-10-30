package com.kinescope.app

import android.content.Context
import android.net.ConnectivityManager

/**
 * Patch 57: whether the phone has a Private DNS HOST NAME configured (Settings -> Network -> Private DNS -> a provider name), as
 * the system reports it for the network this app itself uses.
 *
 * Only [Strict] changes anything here: the tunnel then adds no DNS server of its own (see [PrivateDns.tunnelDnsServer]). The name
 * is never put in a log or a report (a provider name can carry a personal profile id); [PrivateDns.label] says only which state.
 */
internal sealed interface PrivateDnsState {
    /** A provider host name is set (the "Private DNS provider hostname" mode). */
    data class Strict(val hostname: String) : PrivateDnsState

    /** No host name: Private DNS is off or automatic (the system chooses by itself). */
    data object Off : PrivateDnsState

    /** The system did not say (no network, no permission). */
    data object Unknown : PrivateDnsState
}

internal object PrivateDns {
    /** The resolver the tunnel hands the YouTube app when the phone has no Private DNS name of its own. */
    const val TUNNEL_DNS = "1.1.1.1"

    /**
     * `LinkProperties.getPrivateDnsServerName()` (API 28, so always present at minSdk 29) of the default network of this app's
     * own uid. The tunnel carries only the chosen app, so this app's default network is the real one, not the tunnel.
     */
    fun state(context: Context): PrivateDnsState = try {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val hostname = if (manager == null || network == null) null else manager.getLinkProperties(network)?.privateDnsServerName
        when {
            manager == null || network == null -> PrivateDnsState.Unknown
            hostname.isNullOrBlank() -> PrivateDnsState.Off
            else -> PrivateDnsState.Strict(hostname)
        }
    } catch (e: SecurityException) {
        PrivateDnsState.Unknown
    }

    /**
     * The DNS server to give the tunnel, or null to give none. With none, Android uses the DNS of the default network (documented on
     * `VpnService.Builder.addDnsServer`), which is where a Private DNS name takes effect; a fixed server would bypass it.
     * This is ByeByeDPI's rule.
     */
    fun tunnelDnsServer(state: PrivateDnsState): String? = if (state is PrivateDnsState.Strict) null else TUNNEL_DNS

    /** A log token: `strict`, `off` or `unknown`. */
    fun label(state: PrivateDnsState): String = when (state) {
        is PrivateDnsState.Strict -> "strict"
        PrivateDnsState.Off -> "off"
        PrivateDnsState.Unknown -> "unknown"
    }
}
