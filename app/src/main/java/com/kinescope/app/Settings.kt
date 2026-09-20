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

    fun getDownloadSubfolder(context: Context): String =
        sanitizeSubfolder(prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER)

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
