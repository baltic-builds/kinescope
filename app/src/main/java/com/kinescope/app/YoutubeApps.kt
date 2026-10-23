package com.kinescope.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

// Patch 51: the app that "Unblock YouTube" opens, and that the YouTube tunnel lets through, is a setting. The default is
// the official YouTube app; any other app that opens youtube.com links can be chosen (an alternative client, or a browser,
// which opens the YouTube site). The tunnel carries the chosen app only (`BypassVpnService.buildYouTubeTunnel`).
//
// Android 11+ hides other apps unless the manifest asks: `<queries>` lists the intent that finds them (a VIEW of an https
// link) and the official YouTube package.

internal data class YoutubeApp(val packageName: String, val label: String)

internal object YoutubeApps {
    /** The official YouTube app: the default, and the one the tunnel always served before patch 51. */
    const val DEFAULT_PACKAGE = BypassVpnService.YOUTUBE_PACKAGE

    private const val HOME_URL = "https://www.youtube.com/"

    // Only used to ask which apps handle a YouTube video link; it is never opened.
    private const val PROBE_URL = "https://www.youtube.com/watch?v=jNQXAC9IJRE"

    /**
     * Pure (unit-tested): the list to show. No duplicates, never [exclude] (the app itself), the default app first, the
     * others by name.
     */
    fun order(found: List<YoutubeApp>, defaultPackage: String, exclude: String? = null): List<YoutubeApp> {
        val unique = LinkedHashMap<String, YoutubeApp>()
        for (app in found) {
            if (app.packageName != exclude) unique.putIfAbsent(app.packageName, app)
        }
        return unique.values.sortedWith(
            compareByDescending<YoutubeApp> { it.packageName == defaultPackage }.thenBy { it.label.lowercase() }
        )
    }

    /** The apps that can open a YouTube link (YouTube itself, alternative clients, browsers), the default first. */
    fun candidates(context: Context): List<YoutubeApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())
        val found = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            YoutubeApp(packageName, info.loadLabel(pm).toString())
        }.toMutableList()
        // The official app is always offered when it is installed, even if it does not answer the probe.
        if (found.none { it.packageName == DEFAULT_PACKAGE } && isInstalled(context, DEFAULT_PACKAGE)) {
            val label = runCatching {
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(DEFAULT_PACKAGE, 0)).toString()
            }.getOrDefault("YouTube")
            found += YoutubeApp(DEFAULT_PACKAGE, label)
        }
        return order(found, DEFAULT_PACKAGE, exclude = context.packageName)
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** The app to use now: the chosen one if it is still installed, otherwise the official one, otherwise none. */
    fun chosenPackage(context: Context): String? {
        val stored = Settings.getYoutubeApp(context)
        if (isInstalled(context, stored)) return stored
        return DEFAULT_PACKAGE.takeIf { isInstalled(context, it) }
    }

    /**
     * Opens the chosen app. The official app (and any app that only has a launcher) is started the way it always was; any
     * other app is asked to open the YouTube site first, which is what a browser needs, and falls back to its launcher.
     */
    fun open(context: Context): Boolean {
        val packageName = chosenPackage(context) ?: return false
        val attempts = buildList<Pair<String, Intent>> {
            if (packageName != DEFAULT_PACKAGE) {
                add("view" to Intent(Intent.ACTION_VIEW, Uri.parse(HOME_URL)).setPackage(packageName))
            }
            context.packageManager.getLaunchIntentForPackage(packageName)?.let { add("launch" to it) }
        }
        for ((how, intent) in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                AppLog.i("YoutubeApps", "open via=$how default=${packageName == DEFAULT_PACKAGE}")
                return true
            }
        }
        AppLog.w("YoutubeApps", "open failed default=${packageName == DEFAULT_PACKAGE}")
        return false
    }
}
