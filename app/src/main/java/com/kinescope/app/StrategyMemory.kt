package com.kinescope.app

/**
 * Patch 46: one thing that happened to a strategy in real use. Patch 53: only real use is recorded here; what a search
 * found is kept apart, per connection class, in `DpiResultsStore`, and [StrategyRating] blends the two. (The two probe
 * outcomes of patch 46 counted a search's findings twice, once there and once here, and are gone.)
 */
internal enum class StrategyOutcome(val weight: Double, val good: Boolean) {
    /** A download finished through it, or the YouTube tunnel carried real data through it. */
    REAL_OK(1.0, true),

    /** It failed in real use although another route then finished the same job. */
    REAL_BAD(1.0, false)
}

/**
 * What the app has learned about one strategy. The weights are sums of outcome weights that fade with time, so
 * [updatedAt] says when they were last brought up to date; [lastRealOkAt] is the last time it carried real traffic.
 */
internal data class LearnedStrategy(
    val okWeight: Double,
    val badWeight: Double,
    val updatedAt: Long,
    val lastRealOkAt: Long
)

/**
 * Patch 46: the app's memory of which strategies really carried traffic, and how well. Pure: the preferences file
 * only stores the text this builds (`DpiPrefs.recordOutcomes`). Unit-tested in `StrategyMemoryTest`. Patch 53: one
 * memory per connection class (Wi-Fi, mobile data), and the real half of [StrategyRating].
 *
 * The score of a strategy is the share of good evidence with a little doubt added, so a strategy with no history
 * sits exactly in the middle ([NEUTRAL]), one that worked a few times is above it and one that kept failing below:
 * `(ok + 1) / (ok + BAD_FACTOR * bad + 2)`. Failures count double (one failure that another route survived is
 * stronger evidence than one more success), and all evidence halves every [HALF_LIFE_MS], so a strategy that worked
 * a month ago on another network does not outrank one that works today.
 */
internal object StrategyMemory {
    /** The score of a strategy nothing is known about. */
    const val NEUTRAL = 0.5

    /** Evidence halves every week. */
    const val HALF_LIFE_MS = 7L * MaintenanceSchedule.DAY_MS

    /** How many strategies are remembered; the weakest are forgotten first. */
    const val MAX_ENTRIES = 40

    private const val BAD_FACTOR = 2.0

    /** Entries whose evidence has faded below this are dropped. */
    private const val NEGLIGIBLE = 0.05

    private fun fadeFactor(from: Long, now: Long): Double {
        val age = (now - from).coerceAtLeast(0L)
        return Math.pow(0.5, age.toDouble() / HALF_LIFE_MS.toDouble())
    }

    /** The entry with its weights faded up to [now]. */
    fun faded(entry: LearnedStrategy, now: Long): LearnedStrategy {
        val factor = fadeFactor(entry.updatedAt, now)
        return entry.copy(okWeight = entry.okWeight * factor, badWeight = entry.badWeight * factor, updatedAt = now.coerceAtLeast(entry.updatedAt))
    }

    /** The score of a strategy: [NEUTRAL] when nothing is known, above it with good evidence, below it with bad. */
    fun score(entry: LearnedStrategy?, now: Long): Double {
        if (entry == null) return NEUTRAL
        val f = faded(entry, now)
        return (f.okWeight + 1.0) / (f.okWeight + BAD_FACTOR * f.badWeight + 2.0)
    }

    /** Adds one outcome for [line]. A line that cannot be stored in the text form is ignored. */
    fun record(
        memory: Map<String, LearnedStrategy>,
        line: String,
        outcome: StrategyOutcome,
        now: Long
    ): Map<String, LearnedStrategy> {
        if (line.isBlank() || line.any { it == '\t' || it == '\n' || it == '\r' }) return memory
        val old = memory[line]
        val base = if (old != null) faded(old, now) else LearnedStrategy(0.0, 0.0, now, 0L)
        val next = base.copy(
            okWeight = base.okWeight + if (outcome.good) outcome.weight else 0.0,
            badWeight = base.badWeight + if (outcome.good) 0.0 else outcome.weight,
            updatedAt = now,
            lastRealOkAt = if (outcome == StrategyOutcome.REAL_OK) now else base.lastRealOkAt
        )
        val out = LinkedHashMap(memory)
        out.remove(line)
        out[line] = next
        return trim(out, keep = line, now = now)
    }

    /** Drops what has faded away and, past [MAX_ENTRIES], the weakest entries (never [keep], the one just recorded). */
    private fun trim(memory: LinkedHashMap<String, LearnedStrategy>, keep: String?, now: Long): Map<String, LearnedStrategy> {
        memory.entries.removeAll { (line, entry) ->
            val f = faded(entry, now)
            line != keep && f.okWeight + f.badWeight < NEGLIGIBLE
        }
        while (memory.size > MAX_ENTRIES) {
            val weakest = memory.entries.filter { it.key != keep }
                .minWithOrNull(compareBy<Map.Entry<String, LearnedStrategy>> { score(it.value, now) }.thenBy { it.value.updatedAt })
                ?: break
            memory.remove(weakest.key)
        }
        return memory
    }

    /** One entry per line: `strategy TAB ok TAB bad TAB updatedAt TAB lastRealOkAt`. */
    fun serialize(memory: Map<String, LearnedStrategy>): String =
        memory.entries.joinToString("\n") { (line, e) ->
            listOf(line, e.okWeight.toString(), e.badWeight.toString(), e.updatedAt.toString(), e.lastRealOkAt.toString())
                .joinToString("\t")
        }

    /** Malformed rows, negative or non-finite numbers and duplicate lines are dropped; the last duplicate wins. */
    fun parse(text: String?): Map<String, LearnedStrategy> {
        val out = LinkedHashMap<String, LearnedStrategy>()
        for (row in text.orEmpty().lineSequence()) {
            val parts = row.split('\t')
            if (parts.size != 5 || parts[0].isBlank()) continue
            val ok = parts[1].toDoubleOrNull()
            val bad = parts[2].toDoubleOrNull()
            val updated = parts[3].toLongOrNull()
            val lastOk = parts[4].toLongOrNull()
            if (ok == null || bad == null || updated == null || lastOk == null) continue
            if (!ok.isFinite() || !bad.isFinite() || ok < 0.0 || bad < 0.0 || updated < 0L || lastOk < 0L) continue
            out.remove(parts[0])
            out[parts[0]] = LearnedStrategy(ok, bad, updated, lastOk)
        }
        return out
    }
}
