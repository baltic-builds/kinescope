package com.kinescope.app

import android.content.Context
import android.os.Build
import com.yausername.youtubedl_android.YoutubeDL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patch 34: the text behind "Copy report" and "Share". A three-line header with everything a
 * reader needs to interpret the lines below (build, device, network, the verified strategies by
 * number, and a legend), then the newest log lines. Built to paste into an AI chat: compact, one
 * event per line, no URLs, cookies or private paths (AppLog redacts those before storing).
 */
internal object DiagnosticReport {
    const val MAX_LINES = 150

    fun build(context: Context, maxLines: Int = MAX_LINES): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val network = NetworkState.snapshot(context)
        val chain = DpiStrategyStore.chain(context)
        val ytdlp = runCatching { YoutubeDL.getInstance().versionName(context.applicationContext) }.getOrNull()
        val now = System.currentTimeMillis()
        val learned = DpiPrefs.learned(context)
        val header = buildString {
            append("#kinescope-report fmt=2 app=")
            append(info?.versionName ?: "?").append('(').append(info?.longVersionCode ?: 0L).append(')')
            append(" ytdlp=").append(ytdlp ?: "?")
            append(" android=").append(Build.VERSION.RELEASE).append('/').append(Build.VERSION.SDK_INT)
            append(" dev=").append(Build.MODEL.replace(' ', '_'))
            append(" loc=").append(Locale.getDefault().language)
            append(" at=").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            append('\n')
            append("#state net=").append(network.kind)
            append(" vpn=").append(if (network.vpn) 1 else 0)
            append(" bypass=").append(if (DpiPrefs.isEnabled(context)) "on" else "off")
            append(" search_done=").append(if (DpiPrefs.hasRunInitialSearch(context)) 1 else 0)
            append(" chain=").append(chain.size)
            append(" pin=").append(DpiPrefs.pinned(context)?.let { pinned -> chain.indexOf(pinned).let { if (it >= 0) "s${it + 1}" else "gone" } } ?: "none")
            append(" proven=").append(DpiPrefs.provenLine(context)?.let { proven -> chain.indexOf(proven).let { if (it >= 0) "s${it + 1}" else "other" } } ?: "none")
            append(" quar=").append(DpiPrefs.quarantined(context).size)
            append(" search_ver=").append(DpiPrefs.lastSearchVersion(context))
            // Patch 46: strategies with real-use evidence, and the age of the last finished search (the 25-day calendar).
            append(" learned=").append(StrategyMemory.good(learned, now).size)
            append(" search_age=").append(
                DpiPrefs.lastSearchAt(context).let { at ->
                    if (at <= 0L) "none" else "${(now - at).coerceAtLeast(0L) / MaintenanceSchedule.DAY_MS}d"
                }
            )
            append('\n')
            chain.forEachIndexed { index, line -> append("#s").append(index + 1).append(' ').append(line).append('\n') }
            // Patch 46: the best of what the app has learned: its place in the chain ("out" when it is not in it), the
            // good and bad evidence (1.0 = a finished download, half of that for a search probe) and the score.
            learned.entries.sortedByDescending { StrategyMemory.score(it.value, now) }.take(4).forEach { (line, entry) ->
                val place = chain.indexOf(line).let { if (it >= 0) "s${it + 1}" else "out" }
                val faded = StrategyMemory.faded(entry, now)
                append("#learn pos=").append(place)
                append(" ok=").append(String.format(Locale.US, "%.2f", faded.okWeight))
                append(" bad=").append(String.format(Locale.US, "%.2f", faded.badWeight))
                append(" sc=").append(String.format(Locale.US, "%.2f", StrategyMemory.score(entry, now)))
                append(" s=").append(line.take(60)).append('\n')
            }
            DpiResultsStore.load(context).values.filter { it.passed }.sortedByDescending { it.score }.take(3).forEach {
                append("#top sc=").append(it.score).append(" kbps=").append(it.kbps)
                append(" load=").append(it.loadPassed).append('/').append(it.loadTotal)
                append(" hs=").append(it.handshakeMs).append(" s=").append(it.line.take(90)).append('\n')
            }
            append(
                "#legend t=HH:mm:ss.d L=I/W/E tag=dl|byp|srch|vpn|job|eng|upd|auth|ui|lib|media " +
                    "j=job(8) p=yt-dlp profile s=strategy(pos/total, see #s lines) r=route " +
                    "chk=health check st=stage why=reason ms=elapsed |=error summary " +
                    "hb=heartbeat(ph=start|js|dl|quiet pct kbps=yt-dlp speed rx=network KB/s idle last) " +
                    "end=run summary slow/stall=route abandoned rotate=tunnel strategy switch probe=tunnel load probe " +
                    "learned=strategies with real-use evidence #learn=their record(pos in chain, ok/bad weights, sc score) " +
                    "search_age=days since the last finished search\n"
            )
        }
        return header + AppLog.readLines(maxLines).joinToString("\n")
    }
}
