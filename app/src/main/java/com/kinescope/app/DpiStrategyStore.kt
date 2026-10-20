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
    private const val KEY_VERIFIED_FALLBACKS = "verified_fallbacks"
    private const val KEY_INITIAL_SEARCH_DONE = "initial_search_done"
    private const val KEY_PINNED = "pinned_strategy"
    private const val KEY_PROVEN = "proven_strategy"
    private const val KEY_PROVEN_AT = "proven_at"
    private const val KEY_QUARANTINE = "quarantine"
    private const val KEY_SEARCH_VERSION = "search_version"
    private const val KEY_SEARCH_AT = "search_at"
    private const val KEY_MAINTENANCE_TRY_AT = "maintenance_try_at"
    private const val KEY_LEARNED = "learned"
    private const val PROVEN_MAX_AGE_MS = 14L * MaintenanceSchedule.DAY_MS
    private const val QUARANTINE_MS = 10L * 60L * 1000L

    // Patch 46: the chain holds six strategies (the primary and five fallbacks), up from four: the best of the last
    // search plus the ones that carried real traffic before (StrategyMemory). Patch 47: the one definition of the size is
    // ChainPlanner.MAX_CHAIN.
    private const val MAX_FALLBACKS = ChainPlanner.MAX_CHAIN - 1

    // Patch 47: the chain, the quarantine and the learned record are changed from several threads (the download
    // ladder, the tunnel monitor, the search) and every change reads and then writes, so the changes take turns.
    private val writeLock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Patch 47: runs [block] while no change to the chain, the quarantine or the record is half written. A chain is read
     * from three preference keys, and a read between the two writes of a promotion saw a primary that did not match its
     * verified mark and reported no strategies for that instant.
     */
    internal fun <T> consistently(block: () -> T): T = synchronized(writeLock) { block() }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal fun storedStrategy(context: Context): String? = prefs(context).getString(KEY_STRATEGY, null)

    fun isStrategyVerified(context: Context, line: String): Boolean =
        prefs(context).getString(KEY_VERIFIED_STRATEGY, null) == line

    fun verifiedAt(context: Context): Long = prefs(context).getLong(KEY_VERIFIED_AT, 0L)

    /**
     * Records the strategy to use plus, when the search found more than one working
     * strategy, up to [MAX_FALLBACKS] more to fall back to if the primary one fails to
     * start at run time (see [DpiStrategyStore.verifiedChain]).
     */
    fun markStrategiesVerified(context: Context, primary: String, fallbacks: List<String> = emptyList()) {
        require(DpiStrategyParser.parse(primary) is DpiStrategyParser.Parsed.Ok)
        val validFallbacks = fallbacks
            .filter { it != primary && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
            .distinct()
            .take(MAX_FALLBACKS)
        // Patch 47: under the lock, so a download's promotion cannot write an older chain over a fresh search result.
        synchronized(writeLock) {
            prefs(context).edit()
                .putString(KEY_STRATEGY, primary)
                .putString(KEY_VERIFIED_STRATEGY, primary)
                .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
                .putString(KEY_VERIFIED_FALLBACKS, validFallbacks.joinToString("\n"))
                .apply()
        }
    }

    fun verifiedFallbacks(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_VERIFIED_FALLBACKS, null) ?: return emptyList()
        return raw.split("\n")
            .filter { it.isNotBlank() && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
    }

    /** True once the one-time automatic first-run strategy search has been attempted. */
    fun hasRunInitialSearch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INITIAL_SEARCH_DONE, false)

    fun setHasRunInitialSearch(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_INITIAL_SEARCH_DONE, done).apply()
    }

    /** Patch 35: the strategy the user pinned in Settings; it always leads the chain. */
    fun pinned(context: Context): String? = prefs(context).getString(KEY_PINNED, null)?.takeIf { it.isNotBlank() }

    /** Pins [line]: it becomes the verified primary (the previous chain follows it) and the bypass is switched on. */
    fun pin(context: Context, line: String) {
        val existing = DpiStrategyStore.verifiedChain(context)
        markStrategiesVerified(context, line, existing.filter { it != line })
        prefs(context).edit().putString(KEY_PINNED, line).putBoolean(KEY_ENABLED, true).apply()
        clearQuarantine(context, line)
    }

    fun unpin(context: Context) {
        prefs(context).edit().remove(KEY_PINNED).apply()
    }

    /** The strategy that last carried a real download, if that was within two weeks. */
    fun provenLine(context: Context, now: Long = System.currentTimeMillis()): String? {
        val store = prefs(context)
        val age = now - store.getLong(KEY_PROVEN_AT, 0L)
        return if (age in 0..PROVEN_MAX_AGE_MS) store.getString(KEY_PROVEN, null) else null
    }

    /** Keeps [line] out of the front of the chain for ten minutes after it failed in real use. */
    fun quarantine(context: Context, line: String, forMs: Long = QUARANTINE_MS) {
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val current = QuarantineList.parse(prefs(context).getString(KEY_QUARANTINE, null), now)
            val next = QuarantineList.add(current, line, now + forMs)
            prefs(context).edit().putString(KEY_QUARANTINE, QuarantineList.serialize(next)).apply()
            // Patch 46: a failure in real use also goes into the long-term record (StrategyMemory), where it lowers the
            // strategy's place among the fallbacks long after the ten minutes are over.
            recordOutcome(context, line, StrategyOutcome.REAL_BAD, now)
        }
    }

    fun quarantined(context: Context): Set<String> =
        QuarantineList.parse(prefs(context).getString(KEY_QUARANTINE, null), System.currentTimeMillis()).keys

    fun clearQuarantine(context: Context, line: String? = null) {
        val store = prefs(context)
        if (line == null) {
            store.edit().remove(KEY_QUARANTINE).apply()
            return
        }
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val next = QuarantineList.parse(store.getString(KEY_QUARANTINE, null), now) - line
            store.edit().putString(KEY_QUARANTINE, QuarantineList.serialize(next)).apply()
        }
    }

    /** The app version code at the last finished strategy search. Diagnostics only: since patch 46 a new build does not trigger a re-check. */
    fun lastSearchVersion(context: Context): Long = prefs(context).getLong(KEY_SEARCH_VERSION, 0L)

    fun setLastSearchVersion(context: Context, version: Long) {
        prefs(context).edit().putLong(KEY_SEARCH_VERSION, version).apply()
    }

    /** Patch 46: when the last strategy search finished (0 = never). The 25-day upkeep counts from here. */
    fun lastSearchAt(context: Context): Long = prefs(context).getLong(KEY_SEARCH_AT, 0L)

    fun setLastSearchAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_SEARCH_AT, at).apply()
    }

    /** Patch 46: notes that a quiet re-check was started now, so one that fails or is stopped is not retried at once. */
    fun markMaintenanceTried(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_MAINTENANCE_TRY_AT, at).apply()
    }

    /**
     * Patch 46: whether the quiet strategy re-check is due: 25 days since the last finished search and no attempt in
     * the last day (see [MaintenanceSchedule]). Never before the first search has finished. A build from before this
     * patch has no search time yet; its calendar starts the first time it is asked, so installing this patch does not
     * itself start a re-check.
     */
    fun isMaintenanceDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!hasRunInitialSearch(context)) return false
        var doneAt = lastSearchAt(context)
        if (doneAt <= 0L) {
            doneAt = now
            setLastSearchAt(context, now)
        }
        return MaintenanceSchedule.isDue(doneAt, prefs(context).getLong(KEY_MAINTENANCE_TRY_AT, 0L), now)
    }

    /**
     * Patch 46: what the app has learned about strategies from real use and from searches (see [StrategyMemory]).
     * Validated again on every read, like the rest of the stored strategies: the parser is the gatekeeper.
     */
    internal fun learned(context: Context): Map<String, LearnedStrategy> =
        StrategyMemory.parse(prefs(context).getString(KEY_LEARNED, null))
            .filterKeys { DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }

    /** Patch 46: the score of a strategy from its record as of [now] ([StrategyMemory.NEUTRAL] when nothing is known). */
    internal fun scorer(context: Context, now: Long = System.currentTimeMillis()): (String) -> Double {
        val memory = learned(context)
        return { line -> StrategyMemory.score(memory[line], now) }
    }

    /** Patch 46: the strategies with real evidence in their favour, best first (see [StrategyMemory.good]). */
    internal fun learnedGood(context: Context, now: Long = System.currentTimeMillis()): List<String> =
        StrategyMemory.good(learned(context), now)

    internal fun recordOutcome(context: Context, line: String, outcome: StrategyOutcome, now: Long = System.currentTimeMillis()) {
        recordOutcomes(context, listOf(line), outcome, now)
    }

    /**
     * Patch 46: records [outcome] for every strategy in [lines]. A line the strategy parser would reject is ignored;
     * with [onlyIfKnown] a strategy with no record yet is skipped.
     */
    internal fun recordOutcomes(
        context: Context,
        lines: List<String>,
        outcome: StrategyOutcome,
        now: Long = System.currentTimeMillis(),
        onlyIfKnown: Boolean = false
    ) {
        if (lines.isEmpty()) return
        synchronized(writeLock) {
            var memory = learned(context)
            for (line in lines) {
                if (DpiStrategyParser.parse(line) !is DpiStrategyParser.Parsed.Ok) continue
                memory = StrategyMemory.record(memory, line, outcome, now, onlyIfKnown)
            }
            prefs(context).edit().putString(KEY_LEARNED, StrategyMemory.serialize(memory)).apply()
        }
    }

    /**
     * Patch 33: after a download really completed through [line], make it the primary strategy
     * (the previous primary and the rest stay behind it as fallbacks). A search only proves a
     * strategy passes a probe; a finished download proves it carries real traffic right now.
     *
     * Patch 46: the same call is how real traffic is remembered (also from the YouTube tunnel, see
     * `TunnelFlow.proves`): the success goes into the long-term record, and the fallbacks behind the winner are
     * ordered by their score there instead of staying in the order of the last search.
     */
    fun promoteVerified(context: Context, line: String) {
        val store = prefs(context)
        val now = System.currentTimeMillis()
        // Patch 47: one turn for the whole read-order-write, because a download and the tunnel can finish together.
        synchronized(writeLock) {
            recordOutcome(context, line, StrategyOutcome.REAL_OK, now)
            // Patch 35: a download really finished through this strategy; a background re-check keeps it in front.
            store.edit().putString(KEY_PROVEN, line).putLong(KEY_PROVEN_AT, now).apply()
            val primary = store.getString(KEY_VERIFIED_STRATEGY, null) ?: return
            val chain = (listOf(primary) + verifiedFallbacks(context)).distinct()
            // Patch 46: the winner leads, the others follow by their score instead of staying in the order of the last search.
            val ordered = ChainPlanner.promote(chain, line, scorer(context, now), MAX_FALLBACKS + 1)
            if (ordered == chain) return
            store.edit()
                .putString(KEY_STRATEGY, ordered.first())
                .putString(KEY_VERIFIED_STRATEGY, ordered.first())
                .putString(KEY_VERIFIED_FALLBACKS, ordered.drop(1).joinToString("\n"))
                .apply()
        }
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
 * The strategies the user can pick from: the built-in ones, a bundled snapshot of the ByeByeDPI
 * community list, and an optional fresher copy of that list downloaded on "Update". The engine
 * itself is native code inside the APK and is updated with the app; what "Update" refreshes is
 * this list, since which strategies work changes over time.
 */
object DpiStrategyStore {
    /** Community-maintained list of ByeDPI command lines. Only ever read, never executed as text. */
    const val LIST_URL =
        "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list"

    private const val LIST_FILE = "dpi-strategies.txt"

    /** Snapshot of the community list shipped inside the APK (see the file's own header). */
    private const val BUNDLED_LIST_ASSET = "dpi_strategies_bundled.txt"
    private const val MAX_DOWNLOAD_BYTES = 64 * 1024
    private const val TIMEOUT_MS = 8_000

    /**
     * Hand-picked built-ins first (fast to try, known good), then the bundled snapshot of the
     * community list (everything, offline, from the first launch), then whatever the last
     * "Update" downloaded on top. Duplicates across the three collapse to one entry.
     */
    fun candidates(context: Context): List<String> =
        (DpiBuiltInStrategies.lines + bundled(context) + downloaded(context)).distinct()

    /** The community-list snapshot shipped in the APK, validated by the same strict parser. */
    fun bundled(context: Context): List<String> {
        val text = try {
            context.applicationContext.assets.open(BUNDLED_LIST_ASSET).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            return emptyList()
        }
        // Same gatekeeper as the downloaded file: nothing reaches the engine unparsed.
        return DpiStrategyParser.parseList(text)
    }

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

    /**
     * The verified strategy to try first, followed by its verified fallbacks -- empty when
     * nothing has been verified yet. Callers that actually start the engine should try each
     * line in order and use the first one that starts (see [DpiBypass] and
     * `BypassVpnService`); callers that only need "is the feature usable right now" should
     * check whether this list is empty.
     */
    /**
     * Patch 35: the chain to use right now: the verified strategies, the pinned one first, and
     * strategies that failed in real use in the last ten minutes last. Empty when nothing is verified.
     */
    fun chain(context: Context): List<String> {
        val stored = verifiedChain(context)
        val pinned = DpiPrefs.pinned(context)
        if (stored.isEmpty()) {
            return if (pinned != null && DpiPrefs.isStrategyVerified(context, pinned)) listOf(pinned) else emptyList()
        }
        return ChainPlanner.order(stored, pinned, DpiPrefs.quarantined(context))
    }

    // Patch 47: read under the lock the writers take, so a promotion that is half written is never seen.
    fun verifiedChain(context: Context): List<String> = DpiPrefs.consistently {
        val primary = selected(context)
        if (!DpiPrefs.isStrategyVerified(context, primary)) return@consistently emptyList<String>()
        (listOf(primary) + DpiPrefs.verifiedFallbacks(context)).distinct()
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
