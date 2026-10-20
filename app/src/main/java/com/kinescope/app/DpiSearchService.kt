package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DpiSearchUiState(
    val running: Boolean = false,
    val progress: SearchProgress? = null,
    @StringRes val resultMessageRes: Int? = null,
    val resultDirectWorks: Boolean = false,
    /** Patch 35: the quiet 25-day re-check (patch 46); downloads keep using the stored strategies. */
    val background: Boolean = false
)

/**
 * Holds the strategy search's state outside any screen's lifecycle, so the search itself (run by
 * [DpiSearchService]) survives navigating away from Settings or backgrounding the app -- before
 * this it ran in the Settings screen's own coroutine scope and was cancelled the moment that
 * screen left composition (see CHANGELOG.md, patch 30).
 */
internal object DpiSearchController {
    private val mutableState = MutableStateFlow(DpiSearchUiState())
    val state = mutableState.asStateFlow()

    internal fun starting(background: Boolean = false) {
        mutableState.value = DpiSearchUiState(running = true, background = background)
    }

    // Patch 35: a running download needs the engine, so a search steps aside between two strategies.
    private val downloadHolds = java.util.concurrent.atomic.AtomicInteger(0)

    fun holdForDownload() {
        downloadHolds.incrementAndGet()
    }

    fun releaseDownload() {
        downloadHolds.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun isHeld(): Boolean = downloadHolds.get() > 0

    internal fun progress(progress: SearchProgress) {
        mutableState.value = mutableState.value.copy(running = true, progress = progress)
    }

    internal fun finished(@StringRes messageRes: Int, directWorks: Boolean = false) {
        mutableState.value = DpiSearchUiState(resultMessageRes = messageRes, resultDirectWorks = directWorks)
    }
}

/**
 * Runs the strategy search as a foreground service so it keeps testing every remaining candidate
 * when the user leaves Settings or backgrounds the app entirely, instead of the search being tied
 * to that screen's own composition.
 */
class DpiSearchService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinescope-strategy-search").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRequested.set(true)
            ACTION_START -> {
                if (!DpiSearchController.state.value.running) {
                    val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_MANUAL
                    val only = intent?.getStringArrayExtra(EXTRA_ONLY)?.toList()
                    stopRequested.set(false)
                    DpiSearchController.starting(background = mode == MODE_BACKGROUND)
                    // Patch 47: when the system refuses the foreground start (Android 12+ restricts it), nothing would
                    // ever end the "running" state: Home would show a re-check forever, downloads would wait for a
                    // search that never runs and no later search could start. Give the state back and stop instead.
                    val foreground = runCatching { startForegroundCompat(buildNotification(null)) }
                        .onFailure { AppLog.e("DpiSearchService", "Could not enter the foreground", it) }
                        .isSuccess
                    if (foreground) {
                        executor.execute { runSearch(mode, only) }
                    } else {
                        DpiSearchController.finished(R.string.bypass_operation_failed)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Patch 35: one strategy search. [mode] is manual, background (the quiet 25-day upkeep, patch 46) or test (one
     * strategy, [only]).
     */
    private fun runSearch(mode: String, only: List<String>?) {
        val context = applicationContext
        val testOnly = only != null
        val background = mode == MODE_BACKGROUND
        try {
            if (background) {
                // Patch 46: note the attempt first, so a run that is stopped or fails is not retried at once, then
                // fetch the community list so the re-check below looks at the current one.
                DpiPrefs.markMaintenanceTried(context)
                refreshStrategyList(context)
            }
            val directWorks = DpiBypass.directConnectionWorks()
            val results = DpiBypass.search(
                context,
                // The first launch, the quiet 25-day re-check and a strategy test look at everything.
                fullScan = testOnly || background || !DpiPrefs.hasRunInitialSearch(context),
                only = only,
                isCancelled = { stopRequested.get() },
                waitIfPaused = { awaitDownloadsIdle() },
                onProgress = { snapshot ->
                    DpiSearchController.progress(snapshot)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                }
            )
            DpiResultsStore.save(context, results)
            when {
                testOnly -> DpiSearchController.finished(R.string.bypass_strategy_tested)
                stopRequested.get() -> {
                    // Patch 35: a stopped search used to throw away everything it had found. Keep the
                    // passes (the old chain stays behind them), as long as something really carried traffic.
                    val kept = results.any { it.fullPass && it.deepOk } &&
                        DpiBypass.applySearchResults(context, results, keepProven = background, partial = true)
                    if (kept && !background) DpiPrefs.setEnabled(context, true)
                    DpiSearchController.finished(if (kept) R.string.bypass_search_partial else R.string.bypass_search_stopped)
                }
                else -> {
                    // A finished (not stopped) search counts as the one-time first check.
                    DpiPrefs.setHasRunInitialSearch(context, true)
                    DpiPrefs.setLastSearchVersion(context, appVersionCode(context))
                    // Patch 46: the 25-day upkeep counts from the last finished search, a manual one included.
                    DpiPrefs.setLastSearchAt(context, System.currentTimeMillis())
                    if (DpiBypass.applySearchResults(context, results, keepProven = background)) {
                        // A quiet re-check must not switch a bypass the user turned off back on.
                        if (!background) DpiPrefs.setEnabled(context, true)
                        DpiSearchController.finished(
                            if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found,
                            directWorks
                        )
                    } else {
                        DpiSearchController.finished(R.string.bypass_search_none)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("DpiSearchService", "Strategy search failed", e)
            DpiSearchController.finished(R.string.bypass_operation_failed)
        } finally {
            stopSelf()
        }
    }

    private fun awaitDownloadsIdle() {
        while (DpiSearchController.isHeld() && !stopRequested.get()) Thread.sleep(300L)
    }

    /** Patch 46: the community strategy list, refreshed as part of the quiet upkeep; a failure only goes to the log. */
    private fun refreshStrategyList(context: Context) {
        val outcome = when (val result = DpiStrategyStore.update(context)) {
            is StrategyListUpdate.Updated -> "updated added=${result.added} total=${result.total}"
            is StrategyListUpdate.Unchanged -> "same total=${result.total}"
            is StrategyListUpdate.Failed -> "failed ${LogFormat.shortReason(result.reason)}"
        }
        AppLog.i("DpiSearchService", "list $outcome")
    }

    override fun onDestroy() {
        stopRequested.set(true)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: SearchProgress?): Notification {
        val stopIntent = Intent(this, DpiSearchService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (progress == null) {
            getString(R.string.bypass_search_notification_starting)
        } else {
            getString(
                R.string.bypass_find_progress,
                (progress.index + 1).coerceAtMost(progress.total.coerceAtLeast(1)),
                progress.total
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kinescope)
            .setContentTitle(getString(R.string.bypass_search_notification_title))
            .setContentText(text)
            .setContentIntent(AppIntents.pendingOpenHome(this))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bypass_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val ACTION_START = "com.kinescope.app.ACTION_START_STRATEGY_SEARCH"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_STRATEGY_SEARCH"
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1102

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ONLY = "only"
        private const val MODE_MANUAL = "manual"
        private const val MODE_BACKGROUND = "background"
        private const val MODE_TEST = "test"

        fun start(context: Context) = dispatch(context, MODE_MANUAL, null)

        /**
         * Patch 35: a quiet full re-check; the stored and learned strategies keep working meanwhile. Patch 46: started by
         * the 25-day upkeep (`DpiPrefs.isMaintenanceDue`), no longer by an app update.
         */
        fun startBackground(context: Context) = dispatch(context, MODE_BACKGROUND, null)

        /** Patch 35: tests one strategy from Settings; the chain is not changed. */
        fun startTest(context: Context, line: String) = dispatch(context, MODE_TEST, listOf(line))

        private fun dispatch(context: Context, mode: String, only: List<String>?) {
            val intent = Intent(context, DpiSearchService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                if (only != null) putExtra(EXTRA_ONLY, only.toTypedArray())
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch start", it) }
        }

        fun appVersionCode(context: Context): Long = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

        fun stop(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch stop", it) }
        }
    }
}
