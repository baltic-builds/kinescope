package com.kinescope.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

/** Initializes bundled yt-dlp + ffmpeg once, off the main thread. */
class YtOfflineApp : Application() {

    @Volatile
    var isReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
        AppLog.i("App", "Kinescope process started")
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                isReady = true
                AppLog.i("App", "yt-dlp + ffmpeg initialized")
            } catch (e: YoutubeDLException) {
                AppLog.e("App", "Failed to initialize yt-dlp/ffmpeg", e)
            } catch (e: Exception) {
                AppLog.e("App", "Unexpected initialization failure", e)
            }
        }.start()
    }
}
