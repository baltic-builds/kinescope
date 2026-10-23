package com.kinescope.app

import android.content.Context

/** Small SharedPreferences-backed settings store with migration guards. */
object Settings {
    private const val PREFS_NAME = "yt_offline_settings"
    private const val KEY_DEFAULT_QUALITY = "default_quality_index" // legacy migration source
    private const val KEY_DEFAULT_QUALITY_ID = "default_quality_id"
    private const val KEY_DOWNLOAD_SUBFOLDER = "download_subfolder"
    private const val KEY_KNOWN_SUBFOLDERS = "known_download_subfolders"
    private const val KEY_LAST_UPDATE_TIMESTAMP = "last_ytdlp_update_timestamp"
    private const val KEY_INTERFACE_MODE = "interface_mode"
    private const val KEY_OVERSCAN_PERCENT = "tv_overscan_percent"
    private const val KEY_YOUTUBE_APP = "youtube_app_package"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

    const val DEFAULT_SUBFOLDER = "Kinescope"
    private const val LEGACY_SUBFOLDER = "YTOffline"
    private const val MAX_SUBFOLDER_LENGTH = 48

    fun getDefaultQualityId(context: Context): QualityId {
        val preferences = prefs(context)
        QualityId.fromPersisted(preferences.getString(KEY_DEFAULT_QUALITY_ID, null))?.let { return it }

        val legacyIndex = preferences.getInt(KEY_DEFAULT_QUALITY, 0)
        val migrated = qualityPresets.getOrElse(legacyIndex) { qualityPresets[0] }.id
        preferences.edit().putString(KEY_DEFAULT_QUALITY_ID, migrated.persistedValue).apply()
        return migrated
    }

    fun getDefaultQualityIndex(context: Context): Int = qualityPresetIndex(getDefaultQualityId(context))

    fun setDefaultQualityIndex(context: Context, index: Int) {
        val id = qualityPresets.getOrElse(index) { qualityPresets[0] }.id
        prefs(context).edit()
            .putString(KEY_DEFAULT_QUALITY_ID, id.persistedValue)
            .remove(KEY_DEFAULT_QUALITY)
            .apply()
    }

    fun getDownloadSubfolder(context: Context): String {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER
        if (stored == LEGACY_SUBFOLDER) {
            // One-time upgrade for devices that still have the pre-rename default persisted;
            // a deliberate user choice of a different name is untouched.
            preferences.edit().putString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER).apply()
            return DEFAULT_SUBFOLDER
        }
        return sanitizeSubfolder(stored)
    }

    fun setDownloadSubfolder(context: Context, name: String) {
        val sanitized = sanitizeSubfolder(name)
        val known = getKnownDownloadSubfolders(context) + sanitized
        prefs(context).edit()
            .putString(KEY_DOWNLOAD_SUBFOLDER, sanitized)
            .putStringSet(KEY_KNOWN_SUBFOLDERS, known)
            .apply()
    }

    /**
     * Library reads every folder Kinescope has used so changing the destination
     * never makes previously downloaded media disappear from the app.
     */
    fun getKnownDownloadSubfolders(context: Context): Set<String> {
        val preferences = prefs(context)
        val persisted = preferences.getStringSet(KEY_KNOWN_SUBFOLDERS, emptySet()).orEmpty()
        return buildSet {
            add(DEFAULT_SUBFOLDER)
            add(LEGACY_SUBFOLDER)
            add(getDownloadSubfolder(context))
            persisted.mapTo(this) { sanitizeSubfolder(it) }
        }.filter { it.isNotBlank() }.toSet()
    }

    fun getLastUpdateTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE_TIMESTAMP, 0L)

    fun setLastUpdateTimestamp(context: Context, timestampMillis: Long) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_TIMESTAMP, timestampMillis).apply()
    }

    /** Patch 41: Automatic (decide from the device), TV or Phone and tablet. */
    internal fun getInterfaceMode(context: Context): InterfaceMode =
        InterfaceMode.fromPersisted(prefs(context).getString(KEY_INTERFACE_MODE, null))

    internal fun setInterfaceMode(context: Context, mode: InterfaceMode) {
        prefs(context).edit().putString(KEY_INTERFACE_MODE, mode.persistedValue).apply()
    }

    /** Patch 48: the TV edge margin, 0..5% of the window (Settings -> Interface, offered on a TV). */
    internal fun getOverscanPercent(context: Context): Int =
        prefs(context).getInt(KEY_OVERSCAN_PERCENT, DEFAULT_OVERSCAN_PERCENT).coerceIn(0, OVERSCAN_MAX_PERCENT)

    internal fun setOverscanPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_OVERSCAN_PERCENT, percent.coerceIn(0, OVERSCAN_MAX_PERCENT)).apply()
    }

    /** Patch 51: the app that "Unblock YouTube" opens and that the tunnel carries; the official YouTube app by default. */
    internal fun getYoutubeApp(context: Context): String =
        prefs(context).getString(KEY_YOUTUBE_APP, null)?.takeIf { it.isNotBlank() } ?: YoutubeApps.DEFAULT_PACKAGE

    internal fun setYoutubeApp(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_YOUTUBE_APP, packageName).apply()
    }

    /** Patch 51: counts the launches of the app (not the recreations of its window) for the power-saving pop-up. */
    internal fun noteLaunch(context: Context): Int {
        val next = getLaunchCount(context) + 1
        prefs(context).edit().putInt(KEY_LAUNCH_COUNT, next).apply()
        return next
    }

    internal fun getLaunchCount(context: Context): Int = prefs(context).getInt(KEY_LAUNCH_COUNT, 0)

    /** Patch 51: the power-saving pop-up is shown once; Settings keeps the permanent entry. */
    internal fun isBatteryPromptShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)

    internal fun setBatteryPromptShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, shown).apply()
    }

    private fun sanitizeSubfolder(name: String): String {
        val stripped = buildString {
            name.forEach { character ->
                if (character != '/' && character != '\\' && !character.isISOControl()) append(character)
            }
        }.trim().take(MAX_SUBFOLDER_LENGTH)

        return if (
            stripped.isEmpty() ||
            stripped == "." ||
            stripped == ".."
        ) {
            DEFAULT_SUBFOLDER
        } else {
            stripped
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
