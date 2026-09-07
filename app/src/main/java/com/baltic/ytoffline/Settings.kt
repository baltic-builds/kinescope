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
        sanitizeSubfolder(prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER)

    fun setDownloadSubfolder(context: Context, name: String) {
        prefs(context).edit().putString(KEY_DOWNLOAD_SUBFOLDER, sanitizeSubfolder(name)).apply()
    }

    /**
     * ROADMAP.md Step 4 [MEDIUM, fixed]: this value flows straight
     * into `MediaStore.Downloads.RELATIVE_PATH` in MediaStorage.kt for
     * both insert and query, so it needs to behave like a single flat
     * folder name, not a path -- path separators are stripped
     * entirely (not just rejected), and the pathological "." / ".."
     * cases fall back to the default instead of being let through as
     * literal (harmless but confusing) folder names. Sanitizing on
     * both read and write means even a value stored before this fix
     * existed comes out clean.
     */
    private fun sanitizeSubfolder(name: String): String {
        val stripped = name.replace("/", "").replace("\\", "").trim()
        return if (stripped.isEmpty() || stripped == "." || stripped == "..") DEFAULT_SUBFOLDER else stripped
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
