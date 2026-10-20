package com.kinescope.app

/**
 * Patch 35: pure ordering rules for the strategy chain. Unit-tested in ChainPlannerTest.
 *
 * The stored chain (primary + fallbacks) is what the last search found. On top of it:
 * - a strategy the user pinned goes first;
 * - a strategy that failed in real use recently ("quarantined") goes last, unless everything is
 *   quarantined, in which case the stored order stands (the network may simply be down).
 *
 * Patch 46: what a search stores and what a finished download promotes are ordered by what each strategy has done in
 * real use ([StrategyMemory]), not only by the last search's ranking; see [byScore], [merge] and [promote].
 */
internal object ChainPlanner {
    fun order(stored: List<String>, pinned: String?, quarantined: Set<String>): List<String> {
        val base = buildList {
            if (pinned != null) add(pinned)
            addAll(stored)
        }.distinct()
        if (quarantined.isEmpty() || base.all { it in quarantined }) return base
        return base.filter { it !in quarantined } + base.filter { it in quarantined }
    }

    /**
     * The chain a finished search stores. [ranked] is best first; [failed] are strategies that
     * were tested and did not pass. A pinned strategy always leads. With [keepProven] (background
     * re-checks) a strategy that carried a real download recently keeps the lead, unless this
     * search tested it and it failed. [fill] (the previous chain, for a search that was stopped
     * early) follows the ranked strategies, minus the ones that were tested and failed.
     *
     * Patch 46: [learned] are strategies that carried real traffic before; they join the chain even when this search
     * did not rank them (unless it tested them and they failed), and the whole pool -- ranked and learned -- is
     * ordered by [score], the search ranking breaking ties. With the default score every strategy is equal, so the
     * pool keeps the search's order, which is what the callers without a memory get.
     */
    fun merge(
        ranked: List<String>,
        failed: Set<String>,
        proven: String?,
        pinned: String?,
        keepProven: Boolean,
        max: Int,
        fill: List<String> = emptyList(),
        learned: List<String> = emptyList(),
        score: (String) -> Double = { StrategyMemory.NEUTRAL }
    ): List<String> {
        val lead = when {
            pinned != null -> pinned
            keepProven && proven != null && proven !in failed -> proven
            else -> null
        }
        val pool = byScore((ranked + learned.filter { it !in failed }).distinct(), score)
        return buildList {
            if (lead != null) add(lead)
            addAll(pool)
            addAll(fill.filter { it !in failed })
        }.distinct().take(max)
    }

    /** Patch 46: [lines] best score first. The sort is stable, so lines with equal scores keep their given order. */
    fun byScore(lines: List<String>, score: (String) -> Double): List<String> =
        lines.map { it to score(it) }.sortedByDescending { it.second }.map { it.first }

    /**
     * Patch 46: the chain after [winner] carried real traffic: it leads, the others follow by [score] (the previous
     * order breaks ties). Unchanged when [winner] is not in [chain].
     */
    fun promote(chain: List<String>, winner: String, score: (String) -> Double, max: Int): List<String> {
        if (winner !in chain) return chain
        return (listOf(winner) + byScore(chain.filter { it != winner }, score)).take(max)
    }
}

/** Patch 35: strategies that failed in real use, kept out of the front of the chain for a while. */
internal object QuarantineList {
    /** One entry per line: `strategy TAB until-epoch-ms`. Expired and malformed entries are dropped. */
    fun parse(text: String?, now: Long): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (row in text.orEmpty().lineSequence()) {
            val cut = row.lastIndexOf('\t')
            if (cut <= 0) continue
            val until = row.substring(cut + 1).toLongOrNull() ?: continue
            if (until > now) out[row.substring(0, cut)] = until
        }
        return out
    }

    fun serialize(entries: Map<String, Long>): String =
        entries.entries.joinToString("\n") { it.key + "\t" + it.value }

    fun add(current: Map<String, Long>, line: String, until: Long, max: Int = 30): Map<String, Long> {
        val out = LinkedHashMap(current)
        out.remove(line)
        out[line] = until
        while (out.size > max) out.remove(out.keys.first())
        return out
    }
}
