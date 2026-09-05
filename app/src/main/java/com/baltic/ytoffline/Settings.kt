package com.baltic.ytoffline

import android.content.Context

/**
 * Thin wrapper around SharedPreferences for the few settings this
 * app has. Deliberately not using DataStore or anything fancier —
 * SharedPreferences is built into the platform (zero extra
 * dependency) and plenty for two values.
 */
object Settings {
    private const val PREFS_NAME = "yt_offline_settings"
    private const val KEY_DEFAULT_QUALITY = "default_quality_index"
    private const val KEY_DOWNLOAD_SUBFOLDER = "download_subfolder"
    const val DEFAULT_SUBFOLDER = "YTOffline"

    fun getDefaultQualityIndex(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_QUALITY, 0)

    fun setDefaultQualityIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_QUALITY, index).apply()
    }

    fun getDownloadSubfolder(context: Context): String =
        prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER

    fun setDownloadSubfolder(context: Context, name: String) {
        val safeName = name.ifBlank { DEFAULT_SUBFOLDER }
        prefs(context).edit().putString(KEY_DOWNLOAD_SUBFOLDER, safeName).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
