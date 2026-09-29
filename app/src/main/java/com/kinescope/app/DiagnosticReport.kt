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
        val chain = DpiStrategyStore.verifiedChain(context)
        val ytdlp = runCatching { YoutubeDL.getInstance().versionName(context.applicationContext) }.getOrNull()
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
            append(" chain=").append(chain.size).append('\n')
            chain.forEachIndexed { index, line -> append("#s").append(index + 1).append(' ').append(line).append('\n') }
            append(
                "#legend t=HH:mm:ss.d L=I/W/E tag=dl|byp|srch|vpn|job|eng|upd|auth|ui|lib|media " +
                    "j=job(8) p=yt-dlp profile s=strategy(pos/total, see #s lines) r=route " +
                    "chk=health check st=stage why=reason ms=elapsed |=error summary\n"
            )
        }
        return header + AppLog.readLines(maxLines).joinToString("\n")
    }
}
