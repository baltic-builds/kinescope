package com.kinescope.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent application log for a single-user sideloaded app.
 * It intentionally records state transitions and errors, not every
 * Compose recomposition or every yt-dlp progress line.
 */
object AppLog {
    private const val TAG = "Kinescope"
    private const val FILE_NAME = "kinescope.log"
    private const val MAX_BYTES = 1_000_000L
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
        logFile()?.let { file ->
            runCatching { file.writeText("") }
        }
    }

    private fun write(level: String, component: String, message: String, error: Throwable?) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val suffix = error?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
        val line = "$timestamp $level/$component: $message$suffix\n"

        when (level) {
            "ERROR" -> Log.e(TAG, "$component: $message", error)
            "WARN" -> Log.w(TAG, "$component: $message")
            else -> Log.i(TAG, "$component: $message")
        }

        synchronized(lock) {
            val file = logFile() ?: return
            runCatching {
                if (file.exists() && file.length() >= MAX_BYTES) {
                    val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText("--- log rotated ---\n$keep")
                }
                file.appendText(line)
            }.onFailure { Log.e(TAG, "Could not persist app log", it) }
        }
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }
}
