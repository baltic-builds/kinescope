package com.kinescope.app

/**
 * Patch 35: pure ordering rules for the strategy chain. Unit-tested in ChainPlannerTest.
 *
 * The stored chain (primary + fallbacks) is what the last search found. On top of it:
 * - a strategy the user pinned goes first;
 * - a strategy that failed in real use recently ("quarantined") goes last, unless everything is
 *   quarantined, in which case the stored order stands (the network may simply be down).
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
     */
    fun merge(
        ranked: List<String>,
        failed: Set<String>,
        proven: String?,
        pinned: String?,
        keepProven: Boolean,
        max: Int,
        fill: List<String> = emptyList()
    ): List<String> {
        val lead = when {
            pinned != null -> pinned
            keepProven && proven != null && proven !in failed -> proven
            else -> null
        }
        return buildList {
            if (lead != null) add(lead)
            addAll(ranked)
            addAll(fill.filter { it !in failed })
        }.distinct().take(max)
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
