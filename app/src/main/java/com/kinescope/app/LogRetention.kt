package com.kinescope.app

/**
 * Patch 63: the log keeps the last [WINDOW_MS] (240 minutes) and nothing older. A stored line is `epoch-ms TAB text`; the text is what
 * the screen and the report show (`HH:mm:ss.d L tag message`, unchanged). Pure, unit-tested in `LogRetentionTest`.
 */
internal object LogRetention {
    const val WINDOW_MS = 240L * 60L * 1000L

    /** The size at which the file is trimmed to its newest half even inside the window (a runaway logger must not fill the phone). */
    const val MAX_BYTES = 3_000_000L

    fun encode(epochMs: Long, text: String): String = "$epochMs\t$text"

    /** The time and the text of a stored line; null for a line that has no time (an old-format line or a marker). */
    fun decode(stored: String): Pair<Long, String>? {
        val cut = stored.indexOf('\t')
        if (cut <= 0) return null
        val at = stored.substring(0, cut).toLongOrNull() ?: return null
        return at to stored.substring(cut + 1)
    }

    /** [stored] without the lines older than the window (and without lines that carry no time). A clock moved back keeps its lines. */
    fun keep(stored: List<String>, now: Long): List<String> =
        stored.filter { line -> decode(line)?.let { (at, _) -> now - at <= WINDOW_MS } ?: false }

    /** The text of the stored lines inside the window, oldest first, at most the newest [limit]. */
    fun visible(stored: List<String>, now: Long, limit: Int): List<String> =
        keep(stored, now).mapNotNull { decode(it)?.second }.takeLast(limit)
}
