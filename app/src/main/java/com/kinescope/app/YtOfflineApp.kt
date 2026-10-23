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
        // The ":dpi" and ":dpi_vpn" engine-host processes (DpiEngineService /
        // DpiVpnEngineService) also run Application.onCreate(). Without this guard they
        // repeated queue recovery, MediaStore cleanup and the yt-dlp updater every time an
        // engine process spawned, racing the real download worker in the main process over
        // the shared job journal. Only the main process continues past this point.
        if (Application.getProcessName() != packageName) return
        AppLog.init(this)
        installCrashLogger()
        // Patch 51: a fresh install, an update or the same build (see AppUpdate). An update refreshes yt-dlp and the
        // community strategy list below and raises a notice on Home; a fresh install is handled by MainActivity.
        val launch = AppUpdate.onProcessStart(this)

        appScope.launch {
            // Rebuild the visible queue without doing journal/MediaStore I/O
            // on the main thread. Any job that died mid-execution is exposed
            // as INTERRUPTED and requires an explicit retry.
            DownloadJobStore.restoreToBus(this@YtOfflineApp)
            isReady = EngineController.ensureReady(this@YtOfflineApp)
            if (isReady) {
                // Best-effort freshness without hitting the updater on every
                // process start. Patch 46: every 25 days (MaintenanceSchedule),
                // not every 12 hours. Recovery inside DownloadService can still
                // force an immediate nightly refresh after a YouTube block, and
                // Settings has a manual Update.
                val lastUpdate = Settings.getLastUpdateTimestamp(this@YtOfflineApp)
                // Patch 51: an app update refreshes yt-dlp at once, whatever the calendar says.
                if (launch == LaunchKind.UPDATED || MaintenanceSchedule.isStale(lastUpdate, System.currentTimeMillis())) {
                    YtDlpUpdater.updateBlocking(this@YtOfflineApp)
                }
            }
            // ... and the strategy list from the repository; then Home recommends re-checking the strategies.
            if (launch == LaunchKind.UPDATED) AppUpdate.refreshStrategyListAfterUpdate(this@YtOfflineApp)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "Uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
