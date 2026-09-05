package com.baltic.ytoffline

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

/**
 * Unpacks and initializes the bundled yt-dlp + ffmpeg binaries once,
 * off the main thread, before any screen tries to use them.
 *
 * NOTE for whoever continues this: the exact package for
 * YoutubeDL / YoutubeDLException / YoutubeDLRequest is this Claude's
 * best-effort recollection of the yausername/youtubedl-android
 * library (`com.yausername.youtubedl_android`, `com.yausername.ffmpeg`).
 * It was not verified against a real Gradle sync (no Android SDK in
 * the sandbox this was written in). If the import doesn't resolve in
 * the Codespace, check the actual class names inside the downloaded
 * AAR (Gradle caches it under ~/.gradle/caches, or unzip it directly)
 * and fix the import — the call pattern below should otherwise match
 * the library's README.
 */
class YtOfflineApp : Application() {

    @Volatile
    var isReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                isReady = true
                Log.i(TAG, "yt-dlp + ffmpeg initialized")

                // Phase 5: keep the extractor current without needing
                // an app rebuild. Non-fatal if this fails (e.g. no
                // network yet at startup) — the bundled version still
                // works either way.
                YtDlpUpdater.updateBlocking(this)
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Failed to initialize yt-dlp/ffmpeg", e)
            }
        }.start()
    }

    companion object {
        private const val TAG = "YtOfflineApp"
    }
}
