package com.kinescope.app

/**
 * Patch 51: every bypass strategy has a name, and the name is a colour.
 *
 * A strategy line such as `-d1 -Atorst -r1+s` means nothing to a person, and a position number (`#14`) changes when
 * the list changes. So each strategy gets one colour name from a fixed palette ([R.array.strategy_colors], English and
 * Russian, ordered from the most common colour in the world to the rarer ones: the eleven basic colour terms of
 * Berlin and Kay first, then the everyday ones).
 *
 * THE RULE (documented in `CLAUDE.md`, "Decision (patch 51)", and `HANDOFF.md`):
 * 1. A strategy gets the FIRST colour of the palette that no other known strategy has, in the order the strategies are
 *    first met (the built-in ones, then the community list). A name is never given twice at the same time.
 * 2. The assignment is stored and never changes while the strategy stays in the list, so "Crimson" keeps meaning the
 *    same line after a list update, an app update or a reorder.
 * 3. A strategy that leaves the list (and is not in the chain or pinned) frees its colour for a later one.
 * 4. The palette is append-only: never reorder or remove a colour (names would change under the user), only add at the
 *    end, in BOTH languages and with the same number of entries (`StrategyNamesTest` and lint check that).
 * 5. If the strategies ever outnumber the colours, the palette starts over with a number: "Red 2".
 *
 * Pure (no Android types) so it is unit-tested on the JVM; `DpiPrefs.nameBook` is the preference-backed glue.
 */
internal object StrategyNames {
    /** Strategy line -> index (0 = the first colour). Indexes of a book are unique. */
    fun parse(text: String?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val used = HashSet<Int>()
        for (row in text.orEmpty().lineSequence()) {
            val parts = row.split('\t', limit = 2)
            if (parts.size < 2) continue
            val index = parts[0].toIntOrNull() ?: continue
            val line = parts[1]
            if (index < 0 || line.isBlank() || line in out || !used.add(index)) continue
            out[line] = index
        }
        return out
    }

    fun serialize(book: Map<String, Int>): String =
        book.entries.joinToString("\n") { "${it.value}\t${it.key}" }

    /**
     * The book after meeting [wanted] (in order). Entries for lines in neither [wanted] nor [keep] are dropped, which
     * frees their colours; lines already in the book keep their index; a new line takes the lowest free index.
     */
    fun assign(existing: Map<String, Int>, wanted: List<String>, keep: Set<String>): Map<String, Int> {
        val live = wanted.toHashSet() + keep
        val book = LinkedHashMap<String, Int>()
        val used = HashSet<Int>()
        for ((line, index) in existing) {
            if (line in live && index >= 0 && used.add(index)) book[line] = index
        }
        var next = 0
        fun nextFree(): Int {
            while (next in used) next++
            return next.also { used.add(it) }
        }
        for (line in wanted) {
            if (line !in book) book[line] = nextFree()
        }
        for (line in keep) {
            if (line !in book) book[line] = nextFree()
        }
        return book
    }

    /** "Red" for the first round of the palette, "Red 2" for the second, and so on. */
    fun displayName(index: Int, palette: List<String>): String {
        if (palette.isEmpty() || index < 0) return ""
        val base = palette[index % palette.size]
        val round = index / palette.size
        return if (round == 0) base else "$base ${round + 1}"
    }
}

/** The names of the strategies as of one moment: ask it for the name of a strategy line. */
internal class StrategyNameBook(private val indexes: Map<String, Int>, private val palette: List<String>) {
    /** The colour name of [line]; empty for a line the book has never met. */
    fun nameOf(line: String): String = indexes[line]?.let { StrategyNames.displayName(it, palette) }.orEmpty()
}
