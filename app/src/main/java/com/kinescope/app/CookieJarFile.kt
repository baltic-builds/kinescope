package com.kinescope.app

import java.io.File

/**
 * The one place that turns a WebView `Cookie:` header into the Netscape cookie file yt-dlp reads
 * with `--cookies`. Shared by [YouTubeAuth] and [InstagramAuth]; it uses no Android classes, so it
 * is covered by plain JVM tests.
 */
internal object CookieJarFile {
    /** `a=1; b=2` -> [(a, 1), (b, 2)], first value wins when a name repeats. */
    fun parse(header: String): List<Pair<String, String>> =
        header.split(';')
            .map { it.trim() }
            .mapNotNull { part ->
                val split = part.indexOf('=')
                if (split <= 0) null else part.substring(0, split) to part.substring(split + 1)
            }
            .distinctBy { it.first }

    /**
     * Writes [cookies] for [domain] (for example `.instagram.com`) to [target]. The file is written
     * next to the target and renamed, so a crash never leaves half a cookie jar. A cookie whose name
     * or value contains a tab or a line break would corrupt the format and is skipped.
     */
    fun write(target: File, domain: String, comment: String, cookies: List<Pair<String, String>>) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.bufferedWriter().use { out ->
            out.appendLine("# Netscape HTTP Cookie File")
            out.appendLine("# $comment")
            cookies.forEach { (name, value) ->
                if (isSafe(name) && isSafe(value)) {
                    out.appendLine("$domain\tTRUE\t/\tTRUE\t0\t$name\t$value")
                }
            }
        }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun isSafe(text: String): Boolean = text.none { it == '\t' || it == '\n' || it == '\r' }
}
