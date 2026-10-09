package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Patch 40: everything the download service puts in the notification shade: the foreground
 * notification (with its Stop action and throttled progress) and the completion notification.
 * Split out of DownloadService so the service keeps one job: queue and lifecycle. It is a
 * [ContextWrapper] around the service so the notification code runs unchanged.
 */
internal class DownloadNotifier(private val service: Service) : ContextWrapper(service) {
    private var lastNotificationAt = 0L
    private var lastNotificationPercent = -1

    fun startForeground(text: String, jobId: String?) {
        service.startForeground(
            NOTIFICATION_ID,
            buildNotification(text, jobId, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    fun update(text: String, jobId: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, jobId, null))
    }

    fun updateProgress(jobId: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (percent == lastNotificationPercent && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        if (now - lastNotificationAt < NOTIFICATION_THROTTLE_MS && percent !in setOf(0, 100)) return
        lastNotificationAt = now
        lastNotificationPercent = percent
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_progress, percent), jobId, percent)
        )
    }

    private fun buildNotification(text: String, jobId: String?, progress: Int?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_kinescope)
            .setContentIntent(AppIntents.pendingOpenHome(this))
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (progress != null) builder.setProgress(100, progress.coerceIn(0, 100), false)
        if (!jobId.isNullOrBlank()) {
            val stopIntent = Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_STOP
                putExtra(DownloadService.EXTRA_JOB_ID, jobId)
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                jobId.hashCode() and 0x7fffffff,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.stop), stopPendingIntent)
        }
        return builder.build()
    }

    fun showCompletion(jobId: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_complete))
            .setSmallIcon(R.drawable.ic_stat_kinescope)
            .setContentIntent(AppIntents.pendingOpenHome(this))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            COMPLETION_NOTIFICATION_BASE + abs(jobId.hashCode() % 10_000),
            notification
        )
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_BASE = 2_000
        private const val NOTIFICATION_THROTTLE_MS = 750L
    }
}
