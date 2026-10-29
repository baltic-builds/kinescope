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
        // Patch 53: the strategy lines describe the connection class the phone is on now (Wi-Fi or mobile data).
        val cls = NetworkState.currentClass(context)
        val chain = DpiStrategyStore.chain(context, cls)
        val ytdlp = runCatching { YoutubeDL.getInstance().versionName(context.applicationContext) }.getOrNull()
        val now = System.currentTimeMillis()
        val learned = DpiPrefs.learned(context, cls)
        val ratings = DpiPrefs.ratings(context, cls, now)
        val two = { value: Double -> String.format(Locale.US, "%.2f", value) }
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
            append(" cls=").append(cls.key)
            append(" seeded=").append(if (DpiPrefs.isSeeded(context, cls)) 1 else 0)
            append(" vpn=").append(if (network.vpn) 1 else 0)
            append(" bypass=").append(if (DpiPrefs.isEnabled(context)) "on" else "off")
            append(" search_done=").append(if (DpiPrefs.hasRunInitialSearch(context)) 1 else 0)
            append(" chain=").append(chain.size)
            append(" pin=").append(DpiPrefs.pinned(context, cls)?.let { pinned -> chain.indexOf(pinned).let { if (it >= 0) "s${it + 1}" else "gone" } } ?: "none")
            append(" proven=").append(DpiPrefs.provenLine(context, cls)?.let { proven -> chain.indexOf(proven).let { if (it >= 0) "s${it + 1}" else "other" } } ?: "none")
            append(" quar=").append(DpiPrefs.quarantined(context, cls).size)
            // Patch 54: whether the engine runs IPv4-only on this class (learned). Patch 55: whether this class has been checked for
            // the running version of the app, and the hours since its last two-a-day random check.
            append(" v4only=").append(if (DpiPrefs.ipv4Only(context, cls)) 1 else 0)
            append(" chk=").append(if (DpiPrefs.checkedVersion(context, cls) == DpiSearchService.appVersionCode(context)) "ok" else "due")
            append(" rand=").append(
                DpiPrefs.randomCheckAt(context, cls).let { at ->
                    if (at <= 0L) "none" else "${(now - at).coerceAtLeast(0L) / 3_600_000L}h"
                }
            )
            append(" search_ver=").append(DpiPrefs.lastSearchVersion(context))
            // Patch 46: the age of the last finished search (the 25-day calendar). Patch 53: and how many strategies the
            // current connection rates as worth using (rating at least StrategyRating.ELIGIBLE), and the other class's chain.
            append(" rated=").append(StrategyRating.eligible(ratings).size)
            append(" search_age=").append(
                DpiPrefs.lastSearchAt(context).let { at ->
                    if (at <= 0L) "none" else "${(now - at).coerceAtLeast(0L) / MaintenanceSchedule.DAY_MS}d"
                }
            )
            append('\n')
            chain.forEachIndexed { index, line -> append("#s").append(index + 1).append(' ').append(line).append('\n') }
            // Patch 53: the best rated strategies of this connection: the place in the chain ("out" when it is not in it), the
            // rating, and what it is made of: the last test (score, "-" when untested) and the real use (good and bad
            // evidence, 1.0 = a finished download).
            val tests = DpiResultsStore.load(context, cls)
            ratings.entries.sortedByDescending { it.value }.take(4).forEach { (line, rating) ->
                val place = chain.indexOf(line).let { if (it >= 0) "s${it + 1}" else "out" }
                val faded = learned[line]?.let { StrategyMemory.faded(it, now) }
                append("#rate pos=").append(place)
                append(" rt=").append(rating)
                append(" test=").append(tests[line]?.let { if (it.passed) it.score.toString() else "fail" } ?: "-")
                append(" ok=").append(two(faded?.okWeight ?: 0.0))
                append(" bad=").append(two(faded?.badWeight ?: 0.0))
                append(" s=").append(line.take(60)).append('\n')
            }
            val otherClass = cls.other
            append("#other net=").append(otherClass.key)
            append(" chain=").append(DpiStrategyStore.verifiedChain(context, otherClass).size)
            append(" seeded=").append(if (DpiPrefs.isSeeded(context, otherClass)) 1 else 0)
            append(" pin=").append(if (DpiPrefs.pinned(context, otherClass) != null) 1 else 0)
            append('\n')
            // Patch 56: both strategy groups in one line each, as the Home card shows them: the connection the phone is on (cur=1),
            // the ways in the router (chain), the ways rated as working (known), whether the list is borrowed (seeded), the hours
            // since the group's chain was set (checked) and since its last random check (rand), and what that check found (last =
            // working/tested).
            val hours = { at: Long -> if (at <= 0L) "none" else "${(now - at).coerceAtLeast(0L) / 3_600_000L}h" }
            for (group in groupOrder(cls)) {
                val last = DpiPrefs.randomLast(context, group)
                append("#group net=").append(group.key)
                append(" cur=").append(if (group == cls) 1 else 0)
                append(" chain=").append(DpiStrategyStore.verifiedChain(context, group).size)
                append(" known=").append(StrategyRating.eligible(DpiPrefs.ratings(context, group, now)).size)
                append(" seeded=").append(if (DpiPrefs.isSeeded(context, group)) 1 else 0)
                append(" checked=").append(hours(DpiPrefs.verifiedAt(context, group)))
                append(" rand=").append(hours(DpiPrefs.randomCheckAt(context, group)))
                append(" last=").append(last?.let { "${it.works}/${it.tested}" } ?: "-")
                append('\n')
            }
            tests.values.filter { it.passed }.sortedByDescending { it.score }.take(3).forEach {
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
                    "cls=connection class(wifi|mobile) all strategy data below is for it seeded=chain borrowed from the other class, untested here " +
                    "rated=strategies rated >=50 #rate=best rated(pos in chain, rt rating 0-100, test=last test score, ok/bad=real use) " +
                    "#other=the other class(chain size, seeded, pinned) " +
                    "#group=one strategy group (cur=the phone is on it, chain=ways in the router, known=rated working, checked/rand=hours since, last=working/tested of the last random check) " +
                    "transport stopped: engine/bridge=which side died, run=seconds it ran, deaths=early deaths of the strategy, fate=restart_same|rotate|stopped_by_user " +
                    "rotate why=probe|one_way|transport|network|update " +
                    "search_age=days since the last finished search v4only=engine runs IPv4-only on this class (learned, -X) " +
                    "chk=ok|due this class was (not yet) checked for the running app version rand=hours since the last two-a-day random check " +
                    "(log lines: srch rand, byp reach) rc=SOCKS reply of a failed CONNECT\n"
            )
        }
        return header + AppLog.readLines(maxLines).joinToString("\n")
    }
}
