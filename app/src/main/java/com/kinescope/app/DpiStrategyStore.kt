package com.kinescope.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Preferences of the network bypass. Kept apart from [Settings] so this feature stays self-contained. */
object DpiPrefs {
    private const val FILE = "kinescope_dpi"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_LIST_UPDATED_AT = "list_updated_at"
    private const val KEY_VERIFIED_STRATEGY = "verified_strategy"
    private const val KEY_VERIFIED_AT = "verified_at"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal fun storedStrategy(context: Context): String? = prefs(context).getString(KEY_STRATEGY, null)

    fun setStrategy(context: Context, line: String) {
        val store = prefs(context)
        val editor = store.edit().putString(KEY_STRATEGY, line)
        if (store.getString(KEY_VERIFIED_STRATEGY, null) != line) {
            editor.remove(KEY_VERIFIED_STRATEGY).remove(KEY_VERIFIED_AT).putBoolean(KEY_ENABLED, false)
        }
        editor.apply()
    }

    fun isStrategyVerified(context: Context, line: String): Boolean =
        prefs(context).getString(KEY_VERIFIED_STRATEGY, null) == line

    fun verifiedAt(context: Context): Long = prefs(context).getLong(KEY_VERIFIED_AT, 0L)

    fun markStrategyVerified(context: Context, line: String) {
        require(DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Ok)
        prefs(context).edit()
            .putString(KEY_STRATEGY, line)
            .putString(KEY_VERIFIED_STRATEGY, line)
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    fun listUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_LIST_UPDATED_AT, 0L)

    internal fun setListUpdatedAt(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LIST_UPDATED_AT, timestamp).apply()
    }
}

sealed interface StrategyListUpdate {
    data class Updated(val added: Int, val total: Int) : StrategyListUpdate
    data class Unchanged(val total: Int) : StrategyListUpdate
    data class Failed(val reason: String) : StrategyListUpdate
}

/**
 * The strategies the user can pick from: the built-in ones plus an optional list downloaded from
 * the ByeByeDPI project. The engine itself is native code inside the APK and is updated with the
 * app; what "Update" refreshes is this list, since which strategies work changes over time.
 */
object DpiStrategyStore {
    /** Community-maintained list of ByeDPI command lines. Only ever read, never executed as text. */
    const val LIST_URL =
        "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list"

    private const val LIST_FILE = "dpi-strategies.txt"
    private const val MAX_DOWNLOAD_BYTES = 64 * 1024
    private const val TIMEOUT_MS = 8_000

    /** Built-in strategies first (fast to try and offline), then anything the last update added. */
    fun candidates(context: Context): List<String> =
        (DpiBuiltInStrategies.lines + downloaded(context)).distinct()

    fun downloaded(context: Context): List<String> {
        val file = listFile(context)
        if (!file.exists()) return emptyList()
        val text = try {
            String(AtomicFile(file).readFully(), Charsets.UTF_8)
        } catch (e: IOException) {
            return emptyList()
        }
        // Validated again on every read: the file is app-private, but the parser is the gatekeeper.
        return DpiStrategyParser.parseList(text)
    }

    /** The strategy to run: the user's choice if it is still valid, otherwise the first built-in. */
    fun selected(context: Context): String {
        val stored = DpiPrefs.storedStrategy(context)
        if (stored != null && DpiStrategyParser.parse(stored) is DpiStrategyParser.Parsed.Ok) return stored
        return DpiBuiltInStrategies.lines.first()
    }

    /** Blocking network call: run it on a background thread. */
    fun update(context: Context, url: String = LIST_URL): StrategyListUpdate {
        val text = try {
            download(url)
        } catch (e: IOException) {
            return StrategyListUpdate.Failed(e.message ?: e.javaClass.simpleName)
        }
        val lines = DpiStrategyParser.parseList(text)
        if (lines.isEmpty()) return StrategyListUpdate.Failed("no usable strategies in the downloaded list")

        val previous = downloaded(context)
        if (lines == previous) {
            DpiPrefs.setListUpdatedAt(context, System.currentTimeMillis())
            return StrategyListUpdate.Unchanged(candidates(context).size)
        }

        val atomic = AtomicFile(listFile(context))
        val stream = try {
            atomic.startWrite()
        } catch (e: IOException) {
            return StrategyListUpdate.Failed(e.message ?: "could not save the list")
        }
        try {
            stream.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: IOException) {
            atomic.failWrite(stream)
            return StrategyListUpdate.Failed(e.message ?: "could not save the list")
        }
        DpiPrefs.setListUpdatedAt(context, System.currentTimeMillis())
        return StrategyListUpdate.Updated(added = lines.count { it !in previous }, total = candidates(context).size)
    }

    private fun listFile(context: Context): File = File(context.filesDir, LIST_FILE)

    private fun download(url: String): String {
        if (!url.startsWith("https://")) throw IOException("only https downloads are allowed")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Kinescope")
            val code = connection.responseCode
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("redirected to a non-HTTPS URL")
            }
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_DOWNLOAD_BYTES) throw IOException("list is unexpectedly large")
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
