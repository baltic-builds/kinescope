package com.kinescope.app

/**
 * Patch 62: what one strategy has done on one connection class, summed over the whole life of the install (no fading). The ledger is the
 * long memory behind three things: the cold-start order (which strategies a class starts from when it has no chain, and which a search walks
 * first when something went wrong), the "Strategies" section of the Logs screen, and the field data from which proven defaults can be chosen
 * after weeks of use on several devices.
 *
 * A TEST is one search result for the strategy ([testOk] = it carried real traffic, see [StrategyResult.works]; [scoreSum] adds the 0-100
 * scores, a failed test counting 0). REAL use is what [DpiPrefs.recordOutcome] sees: a finished download or the tunnel carrying the app
 * ([realOk]), a failure in real use ([realBad]) and a weak failure such as a seven-second stall ([softBad], patch 61). [usedMs] is the time
 * the YouTube tunnel ran on the strategy.
 */
internal data class LedgerEntry(
    val net: String,
    val line: String,
    val tests: Int = 0,
    val testOk: Int = 0,
    val scoreSum: Int = 0,
    val bestScore: Int = 0,
    val lastScore: Int = 0,
    val lastTestAt: Long = 0L,
    val realOk: Int = 0,
    val realBad: Int = 0,
    val softBad: Int = 0,
    val usedMs: Long = 0L,
    val firstAt: Long = 0L,
    val lastUsedAt: Long = 0L
) {
    /** Everything that was tried: tests and real outcomes. */
    val attempts: Int get() = tests + realOk + realBad + softBad

    /** Tests that worked plus real uses that worked. */
    val successes: Int get() = testOk + realOk

    /** Successes over attempts, 0-100 (0 when nothing was tried). */
    val ratePercent: Int get() = if (attempts == 0) 0 else successes * 100 / attempts

    /** The mean test score, a failed test counting 0 (0 when it was never tested). */
    val avgScore: Int get() = if (tests == 0) 0 else scoreSum / tests
}

/** Patch 62: the three kinds of real outcome ([StrategyOutcome] without the weights). */
internal enum class LedgerReal { OK, BAD, SOFT }

/** Patch 62: one finished test, as the ledger needs it. [score] is 0-100 and 0 for a test that did not work. */
internal data class LedgerTest(val line: String, val works: Boolean, val score: Int)

/**
 * Patch 62: the pure rules of the strategy ledger; the preferences file only stores the text [serialize] builds
 * ([DpiLedgerStore]). Unit-tested in `StrategyLedgerTest`. The ledger is a map from [key] (connection class and strategy) to its entry.
 */
internal object StrategyLedger {
    /** How many (class, strategy) pairs are kept; past it the entries with the weakest history are forgotten first. */
    const val MAX_ENTRIES = 400

    /** The lowest [history] a strategy needs to be offered as a cold start ([best]). */
    const val MIN_HISTORY = 60

    /** A tunnel run shorter than this is not counted as usage. */
    const val MIN_USE_MS = 1_000L

    /** A weak failure counts this much of a failure when [history] is computed (the same 0.4 as `StrategyOutcome.SOFT_BAD`). */
    private const val SOFT_WEIGHT = 0.4

    fun key(net: String, line: String): String = net + "\t" + line

    private fun storable(net: String, line: String): Boolean =
        net.isNotBlank() && line.isNotBlank() && net.none { it == '\t' || it == '\n' || it == '\r' } &&
            line.none { it == '\t' || it == '\n' || it == '\r' }

    /** The ledger after [tests] finished on class [net] at [now]. */
    fun recordTests(ledger: Map<String, LedgerEntry>, net: String, tests: List<LedgerTest>, now: Long): Map<String, LedgerEntry> {
        val touched = LinkedHashSet<String>()
        val out = LinkedHashMap(ledger)
        for (test in tests) {
            if (!storable(net, test.line)) continue
            val key = key(net, test.line)
            val old = out.remove(key) ?: LedgerEntry(net, test.line, firstAt = now)
            val score = if (test.works) test.score.coerceIn(0, 100) else 0
            out[key] = old.copy(
                tests = old.tests + 1,
                testOk = old.testOk + if (test.works) 1 else 0,
                scoreSum = old.scoreSum + score,
                bestScore = maxOf(old.bestScore, score),
                lastScore = score,
                lastTestAt = now,
                lastUsedAt = now
            )
            touched += key
        }
        return if (touched.isEmpty()) ledger else trim(out, touched)
    }

    /** The ledger after one real outcome of [line] on class [net]. */
    fun recordReal(ledger: Map<String, LedgerEntry>, net: String, line: String, kind: LedgerReal, now: Long): Map<String, LedgerEntry> {
        if (!storable(net, line)) return ledger
        val key = key(net, line)
        val out = LinkedHashMap(ledger)
        val old = out.remove(key) ?: LedgerEntry(net, line, firstAt = now)
        out[key] = when (kind) {
            LedgerReal.OK -> old.copy(realOk = old.realOk + 1, lastUsedAt = now)
            LedgerReal.BAD -> old.copy(realBad = old.realBad + 1, lastUsedAt = now)
            LedgerReal.SOFT -> old.copy(softBad = old.softBad + 1, lastUsedAt = now)
        }
        return trim(out, setOf(key))
    }

    /** The ledger after the tunnel ran [ms] on [line] of class [net]; a run shorter than [MIN_USE_MS] changes nothing. */
    fun recordUse(ledger: Map<String, LedgerEntry>, net: String, line: String, ms: Long, now: Long): Map<String, LedgerEntry> {
        if (ms < MIN_USE_MS || !storable(net, line)) return ledger
        val key = key(net, line)
        val out = LinkedHashMap(ledger)
        val old = out.remove(key) ?: LedgerEntry(net, line, firstAt = now)
        out[key] = old.copy(usedMs = old.usedMs + ms, lastUsedAt = now)
        return trim(out, setOf(key))
    }

    /** Drops the weakest entries past [MAX_ENTRIES], never the ones in [keep]. */
    private fun trim(ledger: LinkedHashMap<String, LedgerEntry>, keep: Set<String>): Map<String, LedgerEntry> {
        while (ledger.size > MAX_ENTRIES) {
            val weakest = ledger.entries.filter { it.key !in keep }
                .minWithOrNull(compareBy<Map.Entry<String, LedgerEntry>> { history(it.value) }.thenBy { it.value.lastUsedAt })
                ?: break
            ledger.remove(weakest.key)
        }
        return ledger
    }

    /**
     * How well the strategy has done over its whole record, 0-100: 70% the share of successes (a failure counts one, a weak failure 0.4,
     * with a little doubt added so one lucky test is not a record: `(ok + 1) / (ok + failures + 2)`) and 30% the mean test score.
     * A strategy nothing is known about is in the middle for the first part and 0 for the second, so it never beats one with a good record.
     */
    fun history(entry: LedgerEntry): Int {
        val failures = (entry.tests - entry.testOk) + entry.realBad + SOFT_WEIGHT * entry.softBad
        val rate = (entry.successes + 1.0) / (entry.successes + failures + 2.0)
        return Math.round(100.0 * (0.7 * rate + 0.3 * entry.avgScore / 100.0)).toInt().coerceIn(0, 100)
    }

    /** The [history] of every strategy recorded on class [net]. */
    fun historyScores(ledger: Map<String, LedgerEntry>, net: String): Map<String, Int> =
        ledger.values.filter { it.net == net }.associate { it.line to history(it) }

    /**
     * The best strategies of class [net] for a cold start, best first: at least one success, a [history] of [MIN_HISTORY] or more, and
     * still on offer ([offered]: a strategy the community list dropped is not started). Ties go to the one used last.
     */
    fun best(ledger: Map<String, LedgerEntry>, net: String, max: Int, offered: Set<String>): List<String> =
        ledger.values
            .filter { it.net == net && it.successes >= 1 && it.line in offered && history(it) >= MIN_HISTORY }
            .sortedWith(compareByDescending<LedgerEntry> { history(it) }.thenByDescending { it.lastUsedAt })
            .take(max)
            .map { it.line }

    /** One entry per row: `net TAB tests TAB testOk TAB scoreSum TAB best TAB last TAB lastTestAt TAB realOk TAB realBad TAB softBad TAB usedMs TAB firstAt TAB lastUsedAt TAB strategy`. */
    fun serialize(entries: Collection<LedgerEntry>): String =
        entries.joinToString("\n") {
            listOf(
                it.net, it.tests, it.testOk, it.scoreSum, it.bestScore, it.lastScore, it.lastTestAt,
                it.realOk, it.realBad, it.softBad, it.usedMs, it.firstAt, it.lastUsedAt, it.line
            ).joinToString("\t")
        }

    /** Malformed rows, negative numbers and rows without a class or a strategy are dropped; the last duplicate wins. */
    fun parse(text: String?): Map<String, LedgerEntry> {
        val out = LinkedHashMap<String, LedgerEntry>()
        for (row in text.orEmpty().lineSequence()) {
            val parts = row.split('\t', limit = 14)
            if (parts.size < 14) continue
            val net = parts[0]
            val line = parts[13]
            if (!storable(net, line)) continue
            val n = parts.subList(1, 13).map { it.toLongOrNull() ?: return@map null }
            if (n.any { it == null || it < 0L }) continue
            val key = key(net, line)
            out.remove(key)
            out[key] = LedgerEntry(
                net = net, line = line,
                tests = n[0]!!.toInt(), testOk = n[1]!!.toInt(), scoreSum = n[2]!!.toInt(), bestScore = n[3]!!.toInt(),
                lastScore = n[4]!!.toInt(), lastTestAt = n[5]!!, realOk = n[6]!!.toInt(), realBad = n[7]!!.toInt(),
                softBad = n[8]!!.toInt(), usedMs = n[9]!!, firstAt = n[10]!!, lastUsedAt = n[11]!!
            )
        }
        return out
    }

    /** `<1m`, `12m`, `1h05m`; `0m` for none. */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val minutes = ms / 60_000L
        return when {
            minutes < 1L -> "<1m"
            minutes < 60L -> "${minutes}m"
            else -> "${minutes / 60L}h${(minutes % 60L).toString().padStart(2, '0')}m"
        }
    }

    /**
     * The rows of the Strategies section, best first within each class: success rate, then successes, then mean score, then the one
     * used last. One line per strategy with the whole strategy at the end, so a pasted report can be merged across devices by that text.
     */
    fun rows(ledger: Map<String, LedgerEntry>, dateOf: (Long) -> String): List<String> =
        ledger.values
            .sortedWith(
                compareBy<LedgerEntry> { it.net }
                    .thenByDescending { it.ratePercent }
                    .thenByDescending { it.successes }
                    .thenByDescending { it.avgScore }
                    .thenByDescending { it.lastUsedAt }
            )
            .map { e ->
                "net=${e.net} rate=${e.ratePercent}% test=${e.testOk}/${e.tests} real=${e.realOk}/${e.realBad} stall=${e.softBad} " +
                    "avg=${e.avgScore} best=${e.bestScore} last=${e.lastScore} used=${duration(e.usedMs)} " +
                    "first=${dateOf(e.firstAt)} seen=${dateOf(e.lastUsedAt)} s=${e.line}"
            }

    const val LEGEND: String =
        "#legend net=connection class (wifi|mobile) rate=successes/attempts test=worked/tests real=ok/failed in real use " +
            "stall=weak failures (the app waited 7 s) avg=mean test score, a failed test counts 0 best/last=test scores " +
            "used=time the tunnel ran on it first/seen=first and latest day s=the whole strategy"

    /** The whole text: [header], the legend, a summary line and [rows]. */
    fun report(header: String, ledger: Map<String, LedgerEntry>, dateOf: (Long) -> String): String {
        val entries = ledger.values
        val summary = "#summary strategies=${entries.size} successful=${entries.count { it.successes >= 1 }} " +
            "tests=${entries.sumOf { it.tests }} real_ok=${entries.sumOf { it.realOk }} real_bad=${entries.sumOf { it.realBad }} " +
            "used=${duration(entries.sumOf { it.usedMs })}"
        return buildString {
            append(header.trimEnd()).append('\n')
            append(LEGEND).append('\n')
            append(summary).append('\n')
            if (entries.isEmpty()) {
                append("#empty no strategy has been tested or used on this device yet\n")
            } else {
                rows(ledger, dateOf).forEach { append(it).append('\n') }
            }
        }
    }
}
