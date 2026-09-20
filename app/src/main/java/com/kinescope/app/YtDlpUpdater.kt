package com.kinescope.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel

/** Keeps yt-dlp current without an app rebuild. */
object YtDlpUpdater {
    private val updateLock = Any()

    /**
     * Blocking; call from a background thread. Nightly is deliberate:
     * upstream yt-dlp recommends nightly for regular users because the
     * stable channel can lag behind site-side changes.
     */
    fun updateBlocking(
        context: Context,
        channel: UpdateChannel = UpdateChannel.NIGHTLY
    ): String = synchronized(updateLock) {
        try {
            // Safe and idempotent. This also closes the small race where
            // the user taps Update before Application's background init
            // has finished unpacking yt-dlp.
            YoutubeDL.getInstance().init(context.applicationContext)
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, channel)
            Settings.setLastUpdateTimestamp(context, System.currentTimeMillis())
            val message = when (status) {
                YoutubeDL.UpdateStatus.DONE -> context.getString(R.string.update_done)
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE, null -> context.getString(R.string.update_current)
            }
            AppLog.i("YtDlpUpdater", "yt-dlp update result=$status channel=${channel.javaClass.simpleName}")
            message
        } catch (e: Exception) {
            AppLog.e("YtDlpUpdater", "yt-dlp update failed", e)
            context.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
        }
    }
}
