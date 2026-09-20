package com.kinescope.app

import android.content.Context

/** Serializes extractor updates with active downloads through EngineController. */
object YtDlpUpdater {
    fun updateBlocking(context: Context): String {
        return try {
            AppLog.i("Updater", "Checking yt-dlp nightly channel")
            val status = EngineController.updateNightly(context)
            AppLog.i("Updater", "yt-dlp update completed: $status")
            status
        } catch (e: Exception) {
            AppLog.e("Updater", "yt-dlp update failed", e)
            "ERROR:${e.message.orEmpty()}"
        }
    }
}
