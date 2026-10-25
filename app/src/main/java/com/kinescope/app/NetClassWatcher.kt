package com.kinescope.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Patch 53: the connection class of the device's default network as a flow, so that what is on screen (Home, Settings)
 * and the running tunnel follow a change from Wi-Fi to mobile data and back. The class itself comes from
 * [NetworkState.classify]. Every process that needs it calls [start] (the main process from `YtOfflineApp`, the tunnel's
 * process from `BypassVpnService`); it registers once and the system keeps calling it. Null means no usable connection.
 */
internal object NetClassWatcher {
    private val mutable = MutableStateFlow<NetClass?>(null)
    val state: StateFlow<NetClass?> = mutable

    @Volatile
    private var tracked: Network? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            tracked = network
            mutable.value = NetworkState.classify(capabilities)
        }

        override fun onLost(network: Network) {
            // The default network changing from one to another reports the new one first; only the loss of the one in
            // use means there is no connection.
            if (network == tracked) {
                tracked = null
                mutable.value = null
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (registered) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        mutable.value = NetworkState.connectionClass(context)
        registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
    }

    /** The class now: the watcher's when it has one, else asked of the system directly, else Wi-Fi ([NetworkState.currentClass]). */
    fun current(context: Context): NetClass = mutable.value ?: NetworkState.currentClass(context)
}

/** The name of the connection class in the language of the device ("Wi-Fi", "mobile data"). */
internal fun NetClass.labelRes(): Int = when (this) {
    NetClass.WIFI -> R.string.net_wifi
    NetClass.MOBILE -> R.string.net_mobile
}
