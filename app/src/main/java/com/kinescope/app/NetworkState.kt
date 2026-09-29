package com.kinescope.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Patch 34: what the device is connected through right now.
 *
 * `activeNetwork` is the default network for this app's own uid. Kinescope's YouTube tunnel only
 * carries the YouTube app (see BypassVpnService), so it never shows up here; a VPN that does is
 * a third-party one.
 */
internal object NetworkState {
    data class Snapshot(val kind: String, val vpn: Boolean)

    fun snapshot(context: Context): Snapshot {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return Snapshot("none", false)
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
            ?: return Snapshot("none", false)
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            vpn -> "vpn"
            else -> "other"
        }
        return Snapshot(kind, vpn)
    }

    fun systemVpnActive(context: Context): Boolean = snapshot(context).vpn
}
