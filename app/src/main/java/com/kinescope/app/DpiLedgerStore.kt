package com.kinescope.app

import android.annotation.SuppressLint
import android.content.Context

/**
 * Patch 62: the preferences file behind [StrategyLedger]. Every write is a read, a pure change and a write under one lock, and a failure
 * here is logged and swallowed: the ledger is a record and a hint, and must never break a search, a download or the tunnel.
 */
internal object DpiLedgerStore {
    private const val FILE = "kinescope_dpi_ledger"
    private const val KEY = "ledger"
    private val lock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Map<String, LedgerEntry> = StrategyLedger.parse(prefs(context).getString(KEY, null))

    @SuppressLint("ApplySharedPref")
    private fun update(context: Context, change: (Map<String, LedgerEntry>) -> Map<String, LedgerEntry>) {
        try {
            synchronized(lock) {
                val old = load(context)
                val next = change(old)
                // Patch 63: committed to the disk at once (see DpiResultsStore.save); every caller is a background thread.
                if (next !== old) prefs(context).edit().putString(KEY, StrategyLedger.serialize(next.values)).commit()
            }
        } catch (e: Exception) {
            AppLog.w("DpiLedgerStore", "ledger not updated: ${e.javaClass.simpleName}")
        }
    }

    /** What a search found on [cls]: every strategy that really started counts as one test. */
    fun recordTests(context: Context, cls: NetClass, results: List<StrategyResult>, now: Long = System.currentTimeMillis()) {
        val tests = results.filter { it.started }.map { LedgerTest(it.line, it.works, if (it.fullPass) it.score() else 0) }
        if (tests.isEmpty()) return
        update(context) { StrategyLedger.recordTests(it, cls.key, tests, now) }
    }

    /** A real outcome of [line] on [cls] (see `DpiPrefs.recordOutcome`). */
    fun recordReal(context: Context, cls: NetClass, line: String, outcome: StrategyOutcome, now: Long = System.currentTimeMillis()) {
        val kind = when (outcome) {
            StrategyOutcome.REAL_OK -> LedgerReal.OK
            StrategyOutcome.REAL_BAD -> LedgerReal.BAD
            StrategyOutcome.SOFT_BAD -> LedgerReal.SOFT
        }
        update(context) { StrategyLedger.recordReal(it, cls.key, line, kind, now) }
    }

    /** The tunnel ran [ms] on [line] of [cls]. */
    fun recordUse(context: Context, cls: NetClass, line: String, ms: Long, now: Long = System.currentTimeMillis()) {
        update(context) { StrategyLedger.recordUse(it, cls.key, line, ms, now) }
    }

    /** The long-run score (0-100) of every strategy recorded on [cls]. */
    fun historyScores(context: Context, cls: NetClass): Map<String, Int> =
        try {
            StrategyLedger.historyScores(load(context), cls.key)
        } catch (e: Exception) {
            emptyMap()
        }

    /** The best strategies of [cls] for a cold start, best first (see [StrategyLedger.best]). */
    fun best(context: Context, cls: NetClass, max: Int, offered: Set<String>): List<String> =
        try {
            StrategyLedger.best(load(context), cls.key, max, offered)
        } catch (e: Exception) {
            emptyList()
        }
}
