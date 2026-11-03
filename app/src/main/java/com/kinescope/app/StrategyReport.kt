package com.kinescope.app

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patch 62: the text behind the Strategies section of the Logs screen and its Copy button: a header that says which build and device
 * the numbers come from, then one line per strategy that was ever tested or used ([StrategyLedger.report]). Built to be pasted into a
 * chat and merged with the same report from another device: no URLs, no account or network names, only the strategies themselves.
 */
internal object StrategyReport {
    fun build(context: Context, now: Long = System.currentTimeMillis()): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val header = buildString {
            append("#kinescope-strategies fmt=1 app=")
            append(info?.versionName ?: "?").append('(').append(info?.longVersionCode ?: 0L).append(')')
            append(" android=").append(Build.VERSION.RELEASE).append('/').append(Build.VERSION.SDK_INT)
            append(" dev=").append(Build.MODEL.replace(' ', '_'))
            append(" loc=").append(Locale.getDefault().language)
            append(" at=").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(now)))
        }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val ledger = runCatching { DpiLedgerStore.load(context) }.getOrDefault(emptyMap())
        return StrategyLedger.report(header, ledger) { at -> if (at <= 0L) "-" else day.format(Date(at)) }
    }
}
