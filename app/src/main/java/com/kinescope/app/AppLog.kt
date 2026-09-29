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
 */
object AppLog {
    private const val TAG = "Kinescope"
    private const val FILE_NAME = "kinescope.log"
    private const val MAX_BYTES = 1_000_000L
    private val lock = Any()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("App", "boot")
    }

    fun i(component: String, message: String) = write("INFO", component, message, null)
    fun w(component: String, message: String) = write("WARN", component, message, null)
    fun e(component: String, message: String, error: Throwable? = null) =
        write("ERROR", component, message, error)

    fun readLines(limit: Int = 300): List<String> = synchronized(lock) {
        val file = logFile() ?: return@synchronized emptyList()
        if (!file.exists()) return@synchronized emptyList()
        runCatching { file.readLines().takeLast(limit) }.getOrDefault(emptyList())
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
        val line = "${timestamp()} ${level.first()} $tag $body$tail\n"

        when (level) {
            "ERROR" -> Log.e(TAG, "$tag $body$tail")
            "WARN" -> Log.w(TAG, "$tag $body")
            else -> Log.i(TAG, "$tag $body")
        }

        synchronized(lock) {
            val file = logFile() ?: return
            runCatching {
                if (file.exists() && file.length() >= MAX_BYTES) {
                    val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText("--- log rotated ---\n$keep")
                }
                file.appendText(line)
            }.onFailure { Log.e(TAG, "Could not persist app log (${it.javaClass.simpleName})") }
        }
    }

    /** `HH:mm:ss.d`: seconds plus one decimal is enough to order and time events. */
    private fun timestamp(): String {
        val now = System.currentTimeMillis()
        val base = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        return "$base.${(now % 1000) / 100}"
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }
}
