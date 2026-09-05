package com.baltic.ytoffline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Runs queued downloads one at a time in a foreground service, so
 * they survive the user leaving the app. See ROADMAP.md Phase 4.
 *
 * Communicates progress back to the UI via DownloadQueueBus rather
 * than binding — simplest thing that works for a single-process
 * personal app.
 *
 * All extraction is delegated to the bundled yt-dlp binary via the
 * youtubedl-android library (see CLAUDE.md — we never write our own
 * extractor).
 */
class DownloadService : Service() {

    private val queue = LinkedBlockingQueue<DownloadJob>()
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENQUEUE) {
            val url = intent.getStringExtra(EXTRA_URL)
            val qualityIndex = intent.getIntExtra(EXTRA_QUALITY_INDEX, 0)
            if (!url.isNullOrBlank()) {
                val job = DownloadJob(id = UUID.randomUUID().toString(), url = url, qualityIndex = qualityIndex)
                queue.add(job)
                DownloadQueueBus.upsert(
                    DownloadJobStatus(
                        id = job.id,
                        url = job.url,
                        qualityLabel = qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }.label,
                        state = JobState.QUEUED,
                        progressText = "Queued"
                    )
                )
                ensureWorkerRunning()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureWorkerRunning() {
        if (workerThread?.isAlive == true) return
        workerThread = Thread {
            startForegroundWithNotification("Starting downloads\u2026")
            var job = queue.poll()
            while (job != null) {
                runJob(job)
                job = queue.poll()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.also { it.start() }
    }

    private fun runJob(job: DownloadJob) {
        val preset = qualityPresets.getOrElse(job.qualityIndex) { qualityPresets[0] }
        DownloadQueueBus.update(job.id) { it.copy(state = JobState.RUNNING, progressText = "Starting\u2026") }
        updateNotification("Downloading: ${job.url}")

        if (!hasNetwork()) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = "No internet connection")
            }
            return
        }

        val tempBaseName = "download_${job.id}"
        val tempFile = File(cacheDir, "$tempBaseName.${preset.expectedExtension}")

        try {
            val request = YoutubeDLRequest(job.url).apply {
                addOption("-o", "${cacheDir.absolutePath}/$tempBaseName.%(ext)s")
                preset.apply(this)
            }

            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds ->
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = "$progress% (ETA ${etaInSeconds}s)")
                }
                updateNotification("${job.url}: $progress%")
            }

            if (!tempFile.exists()) {
                // See ROADMAP.md "open risks": yt-dlp may pick a
                // different extension than QualityPreset assumes.
                DownloadQueueBus.update(job.id) {
                    it.copy(state = JobState.FAILED, progressText = "Output file not found (${tempFile.name})")
                }
                return
            }

            val publishedUri = MediaStorage.publish(this, tempFile, preset.mimeType)
            DownloadQueueBus.update(job.id) {
                it.copy(
                    state = if (publishedUri != null) JobState.DONE else JobState.FAILED,
                    progressText = if (publishedUri != null) "Saved to Downloads/${Settings.getDownloadSubfolder(this@DownloadService)}" else "Failed to save"
                )
            }
        } catch (e: YoutubeDLException) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = friendlyError(e.message))
            }
        } catch (e: InterruptedException) {
            DownloadQueueBus.update(job.id) {
                it.copy(state = JobState.FAILED, progressText = "Cancelled")
            }
        }
    }

    /** True if there's some network with general internet capability. */
    private fun hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * yt-dlp error messages are often long technical dumps. This maps
     * the common, recognizable cases (from the second AI consultation
     * the user shared: bot detection, age restriction, private/
     * unavailable videos) to a short human-readable line, and falls
     * back to a truncated version of the raw message otherwise.
     */
    private fun friendlyError(raw: String?): String {
        val message = raw.orEmpty()
        val lower = message.lowercase()
        return when {
            lower.contains("sign in to confirm") || lower.contains("not a bot") ->
                "YouTube flagged this as a bot request. More common from " +
                    "cloud/VPN networks than from home \u2014 try again from " +
                    "home, or tap Update to refresh yt-dlp."
            lower.contains("private video") ->
                "This video is private."
            lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict")) ->
                "Age-restricted video \u2014 not supported yet (would need " +
                    "account cookies, which this app doesn't handle)."
            lower.contains("unavailable") ->
                "Video unavailable \u2014 removed, region-blocked, or a bad link."
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "No internet connection."
            message.isBlank() -> "Unknown error."
            message.length > 220 -> message.take(220) + "\u2026"
            else -> message
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YT Offline")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class DownloadJob(val id: String, val url: String, val qualityIndex: Int)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ENQUEUE = "com.baltic.ytoffline.ACTION_ENQUEUE"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_QUALITY_INDEX = "extra_quality_index"

        /** Adds a download to the queue and starts the service if needed. */
        fun enqueue(context: Context, url: String, qualityIndex: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUALITY_INDEX, qualityIndex)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
