package com.kinescope.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel

/**
 * One synchronization boundary for yt-dlp/ffmpeg initialization, extraction,
 * and self-update. Updating the executable while a download is using it is
 * intentionally impossible.
 */
object EngineController {
    private val lock = Any()

    @Volatile
    private var initialized = false

    fun ensureReady(context: Context): Boolean = synchronized(lock) {
        ensureReadyLocked(context.applicationContext)
    }

    fun <T> withEngine(context: Context, block: () -> T): T = synchronized(lock) {
        check(ensureReadyLocked(context.applicationContext)) { "yt-dlp engine is not ready" }
        block()
    }

    fun updateNightly(context: Context): String = synchronized(lock) {
        val appContext = context.applicationContext
        if (!ensureReadyLocked(appContext)) {
            throw IllegalStateException("yt-dlp engine initialization failed")
        }
        val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, UpdateChannel.NIGHTLY)
        Settings.setLastUpdateTimestamp(appContext, System.currentTimeMillis())
        status.toString()
    }

    private fun ensureReadyLocked(context: Context): Boolean {
        if (initialized) return true
        return try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
            AppLog.i("Engine", "yt-dlp and ffmpeg initialized")
            true
        } catch (e: Exception) {
            AppLog.e("Engine", "Engine initialization failed", e)
            false
        }
    }
}
