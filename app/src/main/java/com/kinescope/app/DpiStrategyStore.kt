package com.kinescope.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Preferences of the network bypass. Kept apart from [Settings] so this feature stays self-contained.
 *
 * Patch 53: everything the app knows about strategies is kept per [NetClass] (Wi-Fi, mobile data): the chain, the pin, the
 * strategy that last carried a download, the quarantine, and what real use has taught it (the last tests are in
 * `DpiResultsStore`, per class too). A class-scoped key is its name plus [NetClass.suffix]; the Wi-Fi class has no suffix,
 * so the data of builds from before patch 53 is the Wi-Fi record. The switches, the calendars and the names are shared.
 */
object DpiPrefs {
    private const val FILE = "kinescope_dpi"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LIST_UPDATED_AT = "list_updated_at"
    private const val KEY_INITIAL_SEARCH_DONE = "initial_search_done"
    private const val KEY_SEARCH_VERSION = "search_version"
    private const val KEY_SEARCH_AT = "search_at"
    private const val KEY_NAMES = "strategy_names"
    private const val KEY_SEEN_VERSION = "seen_version"
    private const val KEY_LIST_VERSION = "list_version"

    // Class-scoped (see NetClass.suffix).
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_VERIFIED_STRATEGY = "verified_strategy"
    private const val KEY_VERIFIED_AT = "verified_at"
    private const val KEY_VERIFIED_FALLBACKS = "verified_fallbacks"
    private const val KEY_PINNED = "pinned_strategy"
    private const val KEY_PROVEN = "proven_strategy"
    private const val KEY_PROVEN_AT = "proven_at"
    private const val KEY_QUARANTINE = "quarantine"
    private const val KEY_LEARNED = "learned"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_IPV4_ONLY = "ipv4_only"
    private const val KEY_CHECKED_VERSION = "checked_version"
    private const val KEY_CHECK_TRY_AT = "check_try_at"
    private const val KEY_RANDOM_AT = "random_check_at"
    private const val KEY_RANDOM_LAST = "random_check_last"
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

    private fun key(base: String, cls: NetClass) = base + cls.suffix

    /**
     * Patch 47: runs [block] while no change to the chain, the quarantine or the record is half written. A chain is read
     * from three preference keys, and a read between the two writes of a promotion saw a primary that did not match its
     * verified mark and reported no strategies for that instant.
     */
    internal fun <T> consistently(block: () -> T): T = synchronized(writeLock) { block() }

    /**
     * Whether Kinescope's own downloads go through a verified strategy. Patch 51: ON by default (it only does anything
     * once a strategy is verified, so a fresh install loses nothing); the switch lives in Settings.
     */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal fun storedStrategy(context: Context, cls: NetClass): String? =
        prefs(context).getString(key(KEY_STRATEGY, cls), null)

    internal fun isStrategyVerified(context: Context, cls: NetClass, line: String): Boolean =
        prefs(context).getString(key(KEY_VERIFIED_STRATEGY, cls), null) == line

    /** When the chain of [cls] was last set by a search (0 = never: no search has run on this class, or the chain is borrowed). */
    internal fun verifiedAt(context: Context, cls: NetClass): Long = prefs(context).getLong(key(KEY_VERIFIED_AT, cls), 0L)

    /** Patch 53: the chain of [cls] was borrowed from the other class and no search has run on this one yet. */
    internal fun isSeeded(context: Context, cls: NetClass): Boolean = prefs(context).getBoolean(key(KEY_SEEDED, cls), false)

    /**
     * Records the strategy to use plus, when the search found more than one working
     * strategy, up to [MAX_FALLBACKS] more to fall back to if the primary one fails to
     * start at run time (see [DpiStrategyStore.verifiedChain]). Patch 53: for one class; [seeded] marks a chain borrowed from
     * the other class, which has not been tested here (its time stays 0, see [verifiedAt]).
     */
    internal fun markStrategiesVerified(
        context: Context,
        cls: NetClass,
        primary: String,
        fallbacks: List<String> = emptyList(),
        seeded: Boolean = false
    ) {
        require(DpiStrategyParser.parse(primary) is DpiStrategyParser.Parsed.Ok)
        val validFallbacks = fallbacks
            .filter { it != primary && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
            .distinct()
            .take(MAX_FALLBACKS)
        // Patch 47: under the lock, so a download's promotion cannot write an older chain over a fresh search result.
        synchronized(writeLock) {
            prefs(context).edit()
                .putString(key(KEY_STRATEGY, cls), primary)
                .putString(key(KEY_VERIFIED_STRATEGY, cls), primary)
                .putLong(key(KEY_VERIFIED_AT, cls), if (seeded) 0L else System.currentTimeMillis())
                .putString(key(KEY_VERIFIED_FALLBACKS, cls), validFallbacks.joinToString("\n"))
                .putBoolean(key(KEY_SEEDED, cls), seeded)
                .apply()
        }
    }

    internal fun verifiedFallbacks(context: Context, cls: NetClass): List<String> {
        val raw = prefs(context).getString(key(KEY_VERIFIED_FALLBACKS, cls), null) ?: return emptyList()
        return raw.split("\n")
            .filter { it.isNotBlank() && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
    }

    /** Writes a re-ordered chain without touching its time or its seeded mark. Call with [writeLock] held. */
    private fun writeChain(context: Context, cls: NetClass, ordered: List<String>) {
        prefs(context).edit()
            .putString(key(KEY_STRATEGY, cls), ordered.first())
            .putString(key(KEY_VERIFIED_STRATEGY, cls), ordered.first())
            .putString(key(KEY_VERIFIED_FALLBACKS, cls), ordered.drop(1).joinToString("\n"))
            .apply()
    }

    /**
     * Patch 54: the engine runs IPv4-only (`-X`) on [cls]. Learned, never assumed: set by `DpiBypass.reach` when a strategy
     * could not reach the host with the engine's default name lookup and reached it with IPv4 only.
     */
    internal fun ipv4Only(context: Context, cls: NetClass): Boolean = prefs(context).getBoolean(key(KEY_IPV4_ONLY, cls), false)

    internal fun setIpv4Only(context: Context, cls: NetClass, value: Boolean) {
        prefs(context).edit().putBoolean(key(KEY_IPV4_ONLY, cls), value).apply()
    }

    /**
     * Patch 55: the app version code under which the strategies of [cls] were last checked (0 = never). A fresh install or an
     * update makes the stored value differ from the running version, which is what asks for one check per class
     * ([ClassCheckPolicy]); a check that reaches its goal, or runs the list out, records the version. The Wi-Fi class is stamped
     * when the install happens on Wi-Fi, the mobile class the first time the phone is on mobile data with the app open.
     */
    internal fun checkedVersion(context: Context, cls: NetClass): Long = prefs(context).getLong(key(KEY_CHECKED_VERSION, cls), 0L)

    internal fun setCheckedVersion(context: Context, cls: NetClass, version: Long) {
        prefs(context).edit().putLong(key(KEY_CHECKED_VERSION, cls), version).apply()
    }

    /** Patch 55: when a check of [cls] last started (0 = never), so one that is stopped or fails is not restarted at once. */
    internal fun checkTriedAt(context: Context, cls: NetClass): Long = prefs(context).getLong(key(KEY_CHECK_TRY_AT, cls), 0L)

    internal fun markCheckTried(context: Context, cls: NetClass, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(key(KEY_CHECK_TRY_AT, cls), at).apply()
    }

    /** Patch 55: when the last two-a-day random check of [cls] started (0 = never). Each class keeps its own calendar. */
    internal fun randomCheckAt(context: Context, cls: NetClass): Long = prefs(context).getLong(key(KEY_RANDOM_AT, cls), 0L)

    internal fun setRandomCheckAt(context: Context, cls: NetClass, at: Long) {
        prefs(context).edit().putLong(key(KEY_RANDOM_AT, cls), at).apply()
    }

    /** Patch 56: what the last random check of [cls] found (null = none yet), for the groups card on Home. */
    internal fun randomLast(context: Context, cls: NetClass): RandomLast? =
        RandomLast.parse(prefs(context).getString(key(KEY_RANDOM_LAST, cls), null))

    internal fun setRandomLast(context: Context, cls: NetClass, last: RandomLast) {
        prefs(context).edit().putString(key(KEY_RANDOM_LAST, cls), last.serialize()).apply()
    }

    /** Patch 55: the app version code under which the community strategy list was last refreshed at a launch (0 = never). */
    fun listRefreshedVersion(context: Context): Long = prefs(context).getLong(KEY_LIST_VERSION, 0L)

    fun setListRefreshedVersion(context: Context, version: Long) {
        prefs(context).edit().putLong(KEY_LIST_VERSION, version).apply()
    }

    /**
     * Patch 55: takes [lines] out of the chain of [cls], the rest keeping their order (the next one becomes the primary). Used when
     * a random check found that a strategy of the chain no longer works: its failed test is now the newest on record, which puts it
     * at the very bottom of the ranking ([StrategyRank]). A chain left empty is cleared, so the class borrows the other one's
     * until it is checked ([DpiStrategyStore.ensureSeeded]). A pinned strategy stays: that is the user's choice.
     */
    internal fun removeFromChain(context: Context, cls: NetClass, lines: Collection<String>) {
        if (lines.isEmpty()) return
        synchronized(writeLock) {
            val primary = prefs(context).getString(key(KEY_VERIFIED_STRATEGY, cls), null) ?: return
            val chain = (listOf(primary) + verifiedFallbacks(context, cls)).distinct()
            val pin = pinned(context, cls)
            val remaining = chain.filter { it !in lines || it == pin }
            if (remaining == chain) return
            if (remaining.isEmpty()) {
                prefs(context).edit()
                    .remove(key(KEY_STRATEGY, cls))
                    .remove(key(KEY_VERIFIED_STRATEGY, cls))
                    .remove(key(KEY_VERIFIED_FALLBACKS, cls))
                    .remove(key(KEY_VERIFIED_AT, cls))
                    .remove(key(KEY_SEEDED, cls))
                    .apply()
            } else {
                writeChain(context, cls, remaining)
            }
        }
    }

    /**
     * Patch 54: puts the chain of [cls] in the order of its current ratings, after a check changed them. A pinned strategy keeps
     * the lead: that is the user's choice, not the ratings'. Only the order changes: nothing joins or leaves the chain (a check
     * that found something that works goes through `DpiBypass.applySearchResults`, one that found something broken through
     * [removeFromChain]), and the chain's time and seeded mark stay as they are.
     */
    internal fun reorderByRating(context: Context, cls: NetClass, now: Long = System.currentTimeMillis()) {
        synchronized(writeLock) {
            val primary = prefs(context).getString(key(KEY_VERIFIED_STRATEGY, cls), null) ?: return
            val chain = (listOf(primary) + verifiedFallbacks(context, cls)).distinct()
            val ordered = ChainPlanner.byScore(chain, StrategyRouter.scorer(ratings(context, cls, now)))
            val pin = pinned(context, cls)
            val result = if (pin != null && pin in ordered) listOf(pin) + ordered.filter { it != pin } else ordered
            if (result != chain) writeChain(context, cls, result)
        }
    }

    /** True once the one-time automatic first-run strategy search has been attempted. */
    fun hasRunInitialSearch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INITIAL_SEARCH_DONE, false)

    fun setHasRunInitialSearch(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_INITIAL_SEARCH_DONE, done).apply()
    }

    /** Patch 35: the strategy the user pinned in Settings or on Home; it always leads the chain. Patch 53: per class. */
    internal fun pinned(context: Context, cls: NetClass): String? =
        prefs(context).getString(key(KEY_PINNED, cls), null)?.takeIf { it.isNotBlank() }

    /** Pins [line] for [cls]: it becomes the verified primary (the previous chain follows it) and the bypass is switched on. */
    internal fun pin(context: Context, cls: NetClass, line: String) {
        // A class that was never tested borrows the other one's chain first, so the pin leads a full chain and not a lone line.
        DpiStrategyStore.ensureSeeded(context, cls)
        val existing = DpiStrategyStore.verifiedChain(context, cls)
        markStrategiesVerified(context, cls, line, existing.filter { it != line }, seeded = isSeeded(context, cls))
        prefs(context).edit().putString(key(KEY_PINNED, cls), line).putBoolean(KEY_ENABLED, true).apply()
        clearQuarantine(context, cls, line)
    }

    internal fun unpin(context: Context, cls: NetClass) {
        prefs(context).edit().remove(key(KEY_PINNED, cls)).apply()
    }

    /**
     * Patch 51: the colour names of the strategies. Assigns a colour to every strategy of [wanted] that has none (the rule
     * is in [StrategyNames]) and returns the book. A list that could not be read ([wanted] empty) changes nothing, so a
     * failed read never frees every colour.
     */
    internal fun nameBook(
        context: Context,
        palette: List<String>,
        wanted: List<String>,
        keep: Set<String>
    ): StrategyNameBook {
        val store = prefs(context)
        synchronized(writeLock) {
            val stored = StrategyNames.parse(store.getString(KEY_NAMES, null))
            if (wanted.isEmpty()) return StrategyNameBook(stored, palette)
            val assigned = StrategyNames.assign(stored, wanted, keep)
            if (assigned != stored) store.edit().putString(KEY_NAMES, StrategyNames.serialize(assigned)).apply()
            return StrategyNameBook(assigned, palette)
        }
    }

    /** Patch 51: the app version code that `AppUpdate` last saw (0 = never). */
    fun seenVersion(context: Context): Long = prefs(context).getLong(KEY_SEEN_VERSION, 0L)

    fun setSeenVersion(context: Context, version: Long) {
        prefs(context).edit().putLong(KEY_SEEN_VERSION, version).apply()
    }

    /** The strategy that last carried a real download on [cls], if that was within two weeks. */
    internal fun provenLine(context: Context, cls: NetClass, now: Long = System.currentTimeMillis()): String? {
        val store = prefs(context)
        val age = now - store.getLong(key(KEY_PROVEN_AT, cls), 0L)
        return if (age in 0..PROVEN_MAX_AGE_MS) store.getString(key(KEY_PROVEN, cls), null) else null
    }

    /**
     * Keeps [line] out of the front of the chain of [cls] for ten minutes after it failed in real use.
     *
     * Patch 46: a failure in real use also goes into the long-term record ([StrategyMemory]). Patch 53: the record is the
     * class's, and the stored chain is re-ordered at once ([StrategyRouter.afterFailure]), so the strategy is not back in
     * front when the ten minutes are over unless its rating still puts it there.
     */
    internal fun quarantine(context: Context, cls: NetClass, line: String, forMs: Long = QUARANTINE_MS) {
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val current = QuarantineList.parse(prefs(context).getString(key(KEY_QUARANTINE, cls), null), now)
            val next = QuarantineList.add(current, line, now + forMs)
            prefs(context).edit().putString(key(KEY_QUARANTINE, cls), QuarantineList.serialize(next)).apply()
            recordOutcome(context, cls, line, StrategyOutcome.REAL_BAD, now)
            val primary = prefs(context).getString(key(KEY_VERIFIED_STRATEGY, cls), null) ?: return
            val chain = (listOf(primary) + verifiedFallbacks(context, cls)).distinct()
            val ordered = StrategyRouter.afterFailure(chain, line, ratings(context, cls, now))
            if (ordered != chain) writeChain(context, cls, ordered)
        }
    }

    internal fun quarantined(context: Context, cls: NetClass): Set<String> =
        QuarantineList.parse(prefs(context).getString(key(KEY_QUARANTINE, cls), null), System.currentTimeMillis()).keys

    internal fun clearQuarantine(context: Context, cls: NetClass, line: String? = null) {
        val store = prefs(context)
        if (line == null) {
            store.edit().remove(key(KEY_QUARANTINE, cls)).apply()
            return
        }
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val next = QuarantineList.parse(store.getString(key(KEY_QUARANTINE, cls), null), now) - line
            store.edit().putString(key(KEY_QUARANTINE, cls), QuarantineList.serialize(next)).apply()
        }
    }

    /** The app version code at the last finished strategy search. Diagnostics only: since patch 46 a new build does not trigger a re-check. */
    fun lastSearchVersion(context: Context): Long = prefs(context).getLong(KEY_SEARCH_VERSION, 0L)

    fun setLastSearchVersion(context: Context, version: Long) {
        prefs(context).edit().putLong(KEY_SEARCH_VERSION, version).apply()
    }

    /** Patch 46: when the last strategy search finished (0 = never). Patch 55: diagnostics only (`search_age`). */
    fun lastSearchAt(context: Context): Long = prefs(context).getLong(KEY_SEARCH_AT, 0L)

    fun setLastSearchAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_SEARCH_AT, at).apply()
    }

    /**
     * Patch 46: what the app has learned about strategies from real use (see [StrategyMemory]). Patch 53: the record of
     * [cls]. Validated again on every read, like the rest of the stored strategies: the parser is the gatekeeper.
     */
    internal fun learned(context: Context, cls: NetClass): Map<String, LearnedStrategy> =
        StrategyMemory.parse(prefs(context).getString(key(KEY_LEARNED, cls), null))
            .filterKeys { DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }

    /**
     * Patch 53: the rating (0-100) of every strategy [cls] has a test or a real record for, as of [now]: the last test on
     * that class and real use together ([StrategyRating]).
     */
    internal fun ratings(context: Context, cls: NetClass, now: Long = System.currentTimeMillis()): Map<String, Int> =
        StrategyRating.ratings(DpiResultsStore.load(context, cls), learned(context, cls), now)

    internal fun recordOutcome(context: Context, cls: NetClass, line: String, outcome: StrategyOutcome, now: Long = System.currentTimeMillis()) {
        synchronized(writeLock) {
            val memory = StrategyMemory.record(learned(context, cls), line, outcome, now)
            if (DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Ok) {
                prefs(context).edit().putString(key(KEY_LEARNED, cls), StrategyMemory.serialize(memory)).apply()
            }
        }
    }

    /**
     * Patch 33: after a download really completed through [line], make it the primary strategy
     * (the previous primary and the rest stay behind it as fallbacks). A search only proves a
     * strategy passes a probe; a finished download proves it carries real traffic right now.
     *
     * Patch 46: the same call is how real traffic is remembered (also from the YouTube tunnel, see
     * `TunnelFlow.proves`): the success goes into the long-term record, and the fallbacks behind the winner are
     * ordered by their score there instead of staying in the order of the last search. Patch 53: for the class the
     * traffic went through, and the order is the rating (test and real use) of that class.
     */
    internal fun promoteVerified(context: Context, cls: NetClass, line: String) {
        val store = prefs(context)
        val now = System.currentTimeMillis()
        // Patch 47: one turn for the whole read-order-write, because a download and the tunnel can finish together.
        synchronized(writeLock) {
            recordOutcome(context, cls, line, StrategyOutcome.REAL_OK, now)
            // Patch 35: a download really finished through this strategy; a background re-check keeps it in front.
            store.edit().putString(key(KEY_PROVEN, cls), line).putLong(key(KEY_PROVEN_AT, cls), now).apply()
            val primary = store.getString(key(KEY_VERIFIED_STRATEGY, cls), null) ?: return
            val chain = (listOf(primary) + verifiedFallbacks(context, cls)).distinct()
            // Patch 46: the winner leads, the others follow by their score instead of staying in the order of the last search.
            val ordered = StrategyRouter.afterSuccess(chain, line, ratings(context, cls, now), MAX_FALLBACKS + 1)
            if (ordered != chain) writeChain(context, cls, ordered)
        }
    }

    fun listUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_LIST_UPDATED_AT, 0L)

    internal fun setListUpdatedAt(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LIST_UPDATED_AT, timestamp).apply()
    }
}

/**
 * Patch 51: the strategies on offer: the built-ins, then the community list, which is the downloaded one when there is one
 * and the bundled snapshot otherwise (never both). Duplicates collapse to one entry. Pure, so it is unit-tested.
 */
internal fun mergeStrategyLists(builtIn: List<String>, bundled: List<String>, downloaded: List<String>): List<String> =
    (builtIn + downloaded.ifEmpty { bundled }).distinct()

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
     * Hand-picked built-ins first (fast to try, known good), then the community list. Patch 51: the community list is the
     * downloaded one when there is one, otherwise the bundled snapshot; it REPLACES the snapshot instead of being stacked
     * on top of it. Stacking kept every old variant next to its fixed successor (the upstream flag fix of 2026-10-14 made
     * the app show 76 strategies for a source of 60), so the app now mirrors the source: built-ins + the source's list.
     */
    fun candidates(context: Context): List<String> =
        mergeStrategyLists(DpiBuiltInStrategies.lines, bundled(context), downloaded(context))

    /**
     * Patch 51: the colour names of the strategies on offer, in the language of the device (the rule is in
     * [StrategyNames]). Strategies in the chain or pinned keep a name even when the list dropped them.
     */
    internal fun names(context: Context): StrategyNameBook {
        val palette = context.applicationContext.resources.getStringArray(R.array.strategy_colors).toList()
        val keep = NetClass.entries.flatMap { cls -> verifiedChain(context, cls) + listOfNotNull(DpiPrefs.pinned(context, cls)) }.toSet()
        return DpiPrefs.nameBook(context, palette, candidates(context), keep)
    }

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

    /** The strategy to run on [cls]: the stored choice if it is still valid, otherwise the first built-in. */
    internal fun selected(context: Context, cls: NetClass): String {
        val stored = DpiPrefs.storedStrategy(context, cls)
        if (stored != null && DpiStrategyParser.parse(stored) is DpiStrategyParser.Parsed.Ok) return stored
        return DpiBuiltInStrategies.lines.first()
    }

    /**
     * Patch 35: the chain to use right now on [cls]: the verified strategies, the pinned one first, and
     * strategies that failed in real use in the last ten minutes last. Empty when nothing is verified.
     * Patch 53: a class with no chain of its own first borrows the other class's ([ensureSeeded]), so a connection that
     * has never been tested still has strategies to try; the first real outcomes and the live checks reorder them.
     */
    internal fun chain(context: Context, cls: NetClass): List<String> {
        ensureSeeded(context, cls)
        val stored = verifiedChain(context, cls)
        val pinned = DpiPrefs.pinned(context, cls)
        if (stored.isEmpty()) {
            return if (pinned != null && DpiPrefs.isStrategyVerified(context, cls, pinned)) listOf(pinned) else emptyList()
        }
        return ChainPlanner.order(stored, pinned, DpiPrefs.quarantined(context, cls))
    }

    // Patch 47: read under the lock the writers take, so a promotion that is half written is never seen.
    // Patch 53: the stored chain of one class, without the borrowing of [chain].
    internal fun verifiedChain(context: Context, cls: NetClass): List<String> = DpiPrefs.consistently {
        val primary = selected(context, cls)
        if (!DpiPrefs.isStrategyVerified(context, cls, primary)) return@consistently emptyList<String>()
        (listOf(primary) + DpiPrefs.verifiedFallbacks(context, cls)).distinct()
    }

    /**
     * Patch 53: gives [cls] a first chain when it has none: the other class's, marked as borrowed (`DpiPrefs.isSeeded`,
     * and its time stays 0). A real search on the class replaces it. Does nothing when [cls] has a chain or the other
     * class has none either (a fresh install).
     */
    internal fun ensureSeeded(context: Context, cls: NetClass) {
        DpiPrefs.consistently {
            if (verifiedChain(context, cls).isNotEmpty()) return@consistently
            val seed = StrategyRouter.seed(emptyList(), verifiedChain(context, cls.other), ChainPlanner.MAX_CHAIN)
            if (seed.isEmpty()) return@consistently
            DpiPrefs.markStrategiesVerified(context, cls, seed.first(), seed.drop(1), seeded = true)
            AppLog.i("DpiStrategyStore", "seed net=${cls.key} from=${cls.other.key} n=${seed.size}")
        }
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

        // Patch 51: compared with the list in use (the downloaded one, or the bundled snapshot when there is none).
        val previous = downloaded(context).ifEmpty { bundled(context) }
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
