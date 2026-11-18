package com.kinescope.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.security.MessageDigest

// Patch 64: WHEN the app updates itself. One rule, replacing patch 55's per-version class checks, patch 56's twice-a-day random check
// and patch 63's first-launch question:
//   a. the first launch after an install: the strategy list, yt-dlp and the strategies are refreshed;
//   b. a later launch on a network the app has already refreshed on (the same Wi-Fi name, or mobile data): nothing runs;
//   c. the first launch on mobile data: the update runs (once; mobile data is one network here);
//   d. a launch on a Wi-Fi network (SSID) it has not seen: the update runs.
// "The update" is `DpiSearchService.startUpdate`: the list and yt-dlp first, then the refresh of the strategies (random order, until three
// reach 85 or more, see `SearchGoal.REFRESH`). Besides this, `YtOfflineApp` refreshes yt-dlp and the list every 30 days
// (`MaintenanceSchedule`) and at an install or an update; those two need no network knowledge.

/**
 * A network as a short, stable name. Only a hash is ever stored (a Wi-Fi name is personal): it answers "have I been here" and nothing else.
 */
internal object NetworkKey {
    /** The key of mobile data: every carrier is one network here (a SIM change is not a new place). */
    val MOBILE: String = hash("mobile")

    /** The key of a wired connection. */
    val ETHERNET: String = hash("ethernet")

    fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    fun wifi(ssid: String): String = hash("wifi:" + ssid)

    /** The Wi-Fi name as the system reports it: without the quotes, and null for "no name" (blank, `<unknown ssid>`, `0x`). */
    fun cleanSsid(raw: String?): String? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "0x" || text.equals("<unknown ssid>", ignoreCase = true)) return null
        return text
    }

    /** The network address of [address]/[prefix] (the host part cleared), so a changed DHCP lease is still the same network. */
    fun subnet(address: ByteArray, prefix: Int): String {
        val bits = prefix.coerceIn(0, address.size * 8)
        val masked = ByteArray(address.size) { index ->
            val keep = (bits - index * 8).coerceIn(0, 8)
            (address[index].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
        }
        return masked.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/" + bits
    }

    /** What stands in for the name when the phone may not read it (no location permission): the gateway and the subnet. */
    fun linkFingerprint(gateway: String?, subnet: String?): String = hash("wifi-link:" + (gateway ?: "-") + ":" + (subnet ?: "-"))
}

internal enum class UpdateReason { NONE, FIRST_LAUNCH, NEW_NETWORK }

/** Pure, unit-tested in `LaunchUpdateTest`. */
internal object LaunchUpdatePolicy {
    /** An update that was started and did not finish (stopped, no connection) is not started again at the next launch within this time. */
    const val RETRY_GAP_MS = 6L * 60L * 60L * 1000L

    /** How many networks are remembered; the oldest is forgotten first. */
    const val MAX_KNOWN = 24

    /**
     * [known] are the networks an update has finished on (oldest first), [current] the network now (null: no usable connection),
     * [attemptAt] when an update was last started (0 = never).
     */
    fun decide(known: List<String>, current: String?, attemptAt: Long, now: Long): UpdateReason = when {
        current == null -> UpdateReason.NONE
        current in known -> UpdateReason.NONE
        attemptAt > 0L && (now - attemptAt) in 0L until RETRY_GAP_MS -> UpdateReason.NONE
        known.isEmpty() -> UpdateReason.FIRST_LAUNCH
        else -> UpdateReason.NEW_NETWORK
    }

    fun remember(known: List<String>, key: String): List<String> = (known.filter { it != key } + key).takeLast(MAX_KNOWN)
}

/** What the launch has to do next. */
internal enum class LaunchStep { NOTHING, ASK_WIFI_NAME, UPDATE }

/** Which network the phone is on, as a [NetworkKey]. */
internal object NetworkIdentity {
    fun onWifi(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The key of the network the phone is on; null with no connection. Blocking-free but not cheap: call it off the main thread. */
    fun current(context: Context): String? {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return null
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiKey(app, manager, network)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKey.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKey.MOBILE
            else -> null
        }
    }

    /** The same as [current] (kept for callers that treat "no connection" as no key). */
    fun key(context: Context): String? = current(context)

    private fun wifiKey(context: Context, manager: ConnectivityManager, network: android.net.Network): String {
        // The Wi-Fi name is readable only with the location permission (a platform rule since Android 8.1).
        if (hasLocationPermission(context)) {
            @Suppress("DEPRECATION")
            val raw = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
            NetworkKey.cleanSsid(raw)?.let { return NetworkKey.wifi(it) }
        }
        // No name: the gateway and the subnet tell one network from another well enough (two routers with the same address and the
        // same subnet are not told apart; the name is the exact answer).
        val properties = runCatching { manager.getLinkProperties(network) }.getOrNull()
        val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val local = properties?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val subnet = local?.let { NetworkKey.subnet(it.address.address, it.prefixLength) }
        return NetworkKey.linkFingerprint(gateway, subnet)
    }
}

/** The state of the rule above, kept in its own preference file (only the main process touches it). */
internal object LaunchUpdate {
    private const val PREFS = "kinescope_launch_update"
    private const val KEY_KNOWN = "known"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_WIFI_NAME_ASKED = "wifi_name_asked"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun known(context: Context): List<String> =
        prefs(context).getString(KEY_KNOWN, "").orEmpty().split(',').filter { it.isNotBlank() }

    fun markAttempt(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_ATTEMPT_AT, at).apply()
    }

    /** The update finished (it was not stopped) on the network [key]: the next launch there does nothing. */
    fun markDone(context: Context, key: String) {
        prefs(context).edit().putString(KEY_KNOWN, LaunchUpdatePolicy.remember(known(context), key).joinToString(",")).apply()
        AppLog.i("LaunchUpdate", "done known=${known(context).size}")
    }

    fun wifiNameAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_NAME_ASKED, false)

    fun markWifiNameAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_WIFI_NAME_ASKED, true).apply()
    }

    /**
     * What the launch does now. Blocking (preference files, the system's network state): run it off the main thread. On a fresh install
     * it also gives both connection classes an untested starting chain, so "Watch YouTube" works while the first update still runs.
     */
    fun plan(context: Context, now: Long = System.currentTimeMillis()): LaunchStep {
        if (NetClass.entries.all { DpiStrategyStore.verifiedChain(context, it).isEmpty() }) DpiStrategyStore.seedStartingChains(context)
        // The question is asked once, on Wi-Fi, before the first decision there: without the permission the network is known by its
        // gateway and subnet only.
        if (NetworkIdentity.onWifi(context) && !NetworkIdentity.hasLocationPermission(context) && !wifiNameAsked(context)) {
            return LaunchStep.ASK_WIFI_NAME
        }
        val reason = LaunchUpdatePolicy.decide(known(context), NetworkIdentity.current(context), prefs(context).getLong(KEY_ATTEMPT_AT, 0L), now)
        AppLog.i("LaunchUpdate", "plan reason=${reason.name.lowercase()} known=${known(context).size}")
        return if (reason == UpdateReason.NONE) LaunchStep.NOTHING else LaunchStep.UPDATE
    }
}
