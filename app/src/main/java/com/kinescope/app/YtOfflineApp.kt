package com.kinescope.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YtOfflineApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashLogger()

        appScope.launch {
            // Rebuild the visible queue without doing journal/MediaStore I/O
            // on the main thread. Any job that died mid-execution is exposed
            // as INTERRUPTED and requires an explicit retry.
            DownloadJobStore.restoreToBus(this@YtOfflineApp)
            isReady = EngineController.ensureReady(this@YtOfflineApp)
            if (isReady) {
                // Best-effort freshness without hitting the updater on every
                // process start. Recovery inside DownloadService can still
                // force an immediate nightly refresh after a YouTube block.
                val lastUpdate = Settings.getLastUpdateTimestamp(this@YtOfflineApp)
                if (System.currentTimeMillis() - lastUpdate >= STARTUP_UPDATE_INTERVAL_MS) {
                    YtDlpUpdater.updateBlocking(this@YtOfflineApp)
                }
            }
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "Uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val STARTUP_UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1_000L
    }
}
