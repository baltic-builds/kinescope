package com.kinescope.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostics with privacy redaction. Logs are useful enough to
 * diagnose state transitions but intentionally never retain URLs, cookies,
 * private app paths, or raw yt-dlp dumps.
 *
 * Patch 34: the stored format is compact and AI-first, one line per event:
 * `HH:mm:ss.d L tag message | error-summary` (see LogFormat). Job ids are shortened to 8 hex
 * digits, exceptions become a one-line summary instead of a stack, and a failed yt-dlp run is
 * reduced to its one error. DiagnosticReport adds the header used by Copy / Share.
 *
 * Patch 63: the journal keeps the last 240 minutes (see [LogRetention]) instead of a size-limited tail. Each stored line carries its epoch
 * time in front of the text, so older lines are dropped by age; [readLines] returns the text only, as before.
 */
object AppLog {
    private const val TAG = "Kinescope"
    private const val FILE_NAME = "kinescope-log-v2.txt"

    /** The journal of builds before patch 63: its lines have no date, so their age is unknown; it is removed once. */
    private const val LEGACY_FILE_NAME = "kinescope.log"

    /** How often a write looks for lines that have aged out of the window. */
    private const val PRUNE_EVERY_MS = 5L * 60L * 1000L
    private val lock = Any()
    private var appContext: Context? = null
    private var lastPruneAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { File(context.applicationContext.filesDir, LEGACY_FILE_NAME).delete() }
        synchronized(lock) { prune(System.currentTimeMillis()) }
        i("App", "boot")
    }

    fun i(component: String, message: String) = write("INFO", component, message, null)
    fun w(component: String, message: String) = write("WARN", component, message, null)
    fun e(component: String, message: String, error: Throwable? = null) =
        write("ERROR", component, message, error)

    /** The text of the journal lines of the last 240 minutes, oldest first, at most the newest [limit] (patch 63: 3000, was 300). */
    fun readLines(limit: Int = DEFAULT_READ_LINES): List<String> = synchronized(lock) {
        val file = logFile() ?: return@synchronized emptyList()
        if (!file.exists()) return@synchronized emptyList()
        runCatching { LogRetention.visible(file.readLines(), System.currentTimeMillis(), limit) }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(lock) {
        logFile()?.let { file -> runCatching { file.writeText("") } }
    }

    private fun write(level: String, component: String, message: String, error: Throwable?) {
        val tag = LogFormat.shortTag(DiagnosticSanitizer.sanitize(component).take(48))
        val body = LogFormat.compact(DiagnosticSanitizer.sanitize(message))
        val tail = error
            ?.let { " | " + LogFormat.compact(DiagnosticSanitizer.sanitize(LogFormat.errorSummary(it)), 260) }
            .orEmpty()
        val now = System.currentTimeMillis()
        val line = LogRetention.encode(now, "${timestamp(now)} ${level.first()} $tag $body$tail") + "\n"

        when (level) {
            "ERROR" -> Log.e(TAG, "$tag $body$tail")
            "WARN" -> Log.w(TAG, "$tag $body")
            else -> Log.i(TAG, "$tag $body")
        }

        synchronized(lock) {
            val file = logFile() ?: return
            runCatching {
                if (now - lastPruneAt >= PRUNE_EVERY_MS || (file.exists() && file.length() >= LogRetention.MAX_BYTES)) prune(now)
                file.appendText(line)
            }.onFailure { Log.e(TAG, "Could not persist app log (${it.javaClass.simpleName})") }
        }
    }

    /** Drops the lines older than the window and, if the file is still too big, its older half. Call with [lock] held. */
    private fun prune(now: Long) {
        lastPruneAt = now
        val file = logFile() ?: return
        if (!file.exists()) return
        runCatching {
            val all = file.readLines()
            var kept = LogRetention.keep(all, now)
            if (file.length() >= LogRetention.MAX_BYTES) kept = kept.takeLast((kept.size / 2).coerceAtLeast(1))
            if (kept.size != all.size) file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        }
    }

    /** `HH:mm:ss.d`: seconds plus one decimal is enough to order and time events. */
    private fun timestamp(now: Long): String {
        val base = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        return "$base.${(now % 1000) / 100}"
    }

    private const val DEFAULT_READ_LINES = 3_000

    private fun logFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }
}
