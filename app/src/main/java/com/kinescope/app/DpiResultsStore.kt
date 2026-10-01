package com.kinescope.app

import android.content.Context

/** Patch 35: what the last tests found about one strategy, shown in Settings and in the report. */
internal data class StoredResult(
    val line: String,
    /** 0-100, see [StrategyResult.score]; 0 for a strategy that did not pass. */
    val score: Int,
    val passed: Boolean,
    val kbps: Int,
    val loadPassed: Int,
    val loadTotal: Int,
    val handshakeMs: Int,
    val at: Long
)

internal object DpiResultsStore {
    private const val FILE = "kinescope_dpi_results"
    private const val KEY = "results"
    private const val MAX_ENTRIES = 200

    /** One entry per line: `score TAB passed TAB kbps TAB loadPassed TAB loadTotal TAB hsMs TAB at TAB strategy`. */
    fun serialize(entries: Collection<StoredResult>): String =
        entries.joinToString("\n") {
            listOf(it.score, if (it.passed) 1 else 0, it.kbps, it.loadPassed, it.loadTotal, it.handshakeMs, it.at, it.line)
                .joinToString("\t")
        }

    fun parse(text: String?): Map<String, StoredResult> {
        val out = LinkedHashMap<String, StoredResult>()
        for (row in text.orEmpty().lineSequence()) {
            val parts = row.split('\t', limit = 8)
            if (parts.size < 8) continue
            val numbers = parts.take(7).map { it.toLongOrNull() ?: return@map null }
            if (numbers.any { it == null }) continue
            val line = parts[7]
            if (line.isBlank()) continue
            out[line] = StoredResult(
                line = line,
                score = numbers[0]!!.toInt(),
                passed = numbers[1] == 1L,
                kbps = numbers[2]!!.toInt(),
                loadPassed = numbers[3]!!.toInt(),
                loadTotal = numbers[4]!!.toInt(),
                handshakeMs = numbers[5]!!.toInt(),
                at = numbers[6]!!
            )
        }
        return out
    }

    /** Newer results replace older ones for the same strategy; the oldest entries are dropped past the cap. */
    fun merge(old: Map<String, StoredResult>, fresh: Collection<StoredResult>): Map<String, StoredResult> {
        val merged = LinkedHashMap(old)
        for (entry in fresh) {
            merged.remove(entry.line)
            merged[entry.line] = entry
        }
        while (merged.size > MAX_ENTRIES) merged.remove(merged.keys.first())
        return merged
    }

    fun load(context: Context): Map<String, StoredResult> =
        parse(prefs(context).getString(KEY, null))

    fun save(context: Context, results: List<StrategyResult>, at: Long = System.currentTimeMillis()) {
        val fresh = results.filter { it.started || it.total > 0 }.map { it.toStored(at) }
        if (fresh.isEmpty()) return
        val merged = merge(load(context), fresh)
        prefs(context).edit().putString(KEY, serialize(merged.values)).apply()
    }

    private fun StrategyResult.toStored(at: Long) = StoredResult(
        line = line,
        score = if (fullPass) score() else 0,
        passed = fullPass,
        kbps = throughputKbps,
        loadPassed = loadPassed,
        loadTotal = loadTotal,
        handshakeMs = handshakeMs,
        at = at
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
