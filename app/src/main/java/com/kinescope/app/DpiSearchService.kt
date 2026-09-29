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
    val resultDirectWorks: Boolean = false
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

    internal fun starting() {
        mutableState.value = DpiSearchUiState(running = true)
    }

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
                    stopRequested.set(false)
                    DpiSearchController.starting()
                    startForegroundCompat(buildNotification(null))
                    executor.execute { runSearch() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runSearch() {
        try {
            val directWorks = DpiBypass.directConnectionWorks()
            val results = DpiBypass.search(
                applicationContext,
                // Patch 33: the first launch checks all 72; later searches may stop earlier.
                fullScan = !DpiPrefs.hasRunInitialSearch(applicationContext),
                isCancelled = { stopRequested.get() },
                onProgress = { snapshot ->
                    DpiSearchController.progress(snapshot)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                }
            )
            if (stopRequested.get()) {
                DpiSearchController.finished(R.string.bypass_search_stopped)
            } else {
                // Patch 33: a finished (not stopped) search counts as the one-time first check,
                // and the best strategies win instead of the first four that happened to pass.
                DpiPrefs.setHasRunInitialSearch(applicationContext, true)
                if (DpiBypass.applySearchResults(applicationContext, results)) {
                    DpiPrefs.setEnabled(applicationContext, true)
                    DpiSearchController.finished(
                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found,
                        directWorks
                    )
                } else {
                    DpiSearchController.finished(R.string.bypass_search_none)
                }
            }
        } catch (e: Exception) {
            AppLog.e("DpiSearchService", "Strategy search failed", e)
            DpiSearchController.finished(R.string.bypass_operation_failed)
        } finally {
            stopSelf()
        }
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
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
            .setContentIntent(contentIntent)
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

        fun start(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_START }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch start", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch stop", it) }
        }
    }
}
