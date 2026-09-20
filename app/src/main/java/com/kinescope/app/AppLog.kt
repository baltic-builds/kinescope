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
 */
object AppLog {
    private const val TAG = "Kinescope"
    private const val FILE_NAME = "kinescope.log"
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_STACK_FRAMES = 6
    private val lock = Any()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("App", "Logging initialized")
    }

    fun i(component: String, message: String) = write("INFO", component, message, null)
    fun w(component: String, message: String) = write("WARN", component, message, null)
    fun e(component: String, message: String, error: Throwable? = null) =
        write("ERROR", component, message, error)

    fun readLines(limit: Int = 800): List<String> = synchronized(lock) {
        val file = logFile() ?: return@synchronized emptyList()
        if (!file.exists()) return@synchronized emptyList()
        runCatching { file.readLines().takeLast(limit) }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(lock) {
        logFile()?.let { file -> runCatching { file.writeText("") } }
    }

    private fun write(level: String, component: String, message: String, error: Throwable?) {
        val safeComponent = DiagnosticSanitizer.sanitize(component).take(48)
        val safeMessage = DiagnosticSanitizer.sanitize(message)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val errorBlock = error?.let { sanitizedError(it) }.orEmpty()
        val line = "$timestamp $level/$safeComponent: $safeMessage$errorBlock\n"

        when (level) {
            "ERROR" -> Log.e(TAG, "$safeComponent: $safeMessage${error?.let { " (${it.javaClass.simpleName})" }.orEmpty()}")
            "WARN" -> Log.w(TAG, "$safeComponent: $safeMessage")
            else -> Log.i(TAG, "$safeComponent: $safeMessage")
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

    private fun sanitizedError(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }?.let { ": ${DiagnosticSanitizer.sanitize(it)}" }.orEmpty()
        val frames = error.stackTrace.take(MAX_STACK_FRAMES).joinToString(separator = "\n") {
            "    at ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})"
        }
        return buildString {
            append("\n")
            append(error.javaClass.simpleName)
            append(message)
            if (frames.isNotBlank()) {
                append("\n")
                append(frames)
            }
        }
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }
}
