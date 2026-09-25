package com.kinescope.app

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal data class BypassDiagnosis(
    val strategy: String,
    val engineStarted: Boolean,
    val reports: List<NetworkCheckReport>
)

/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /** How many working strategies the search keeps looking for: one primary + fallbacks. */
    private const val MAX_VERIFIED_STRATEGIES = 4

    /**
     * Starts the engine for a download when the bypass is switched on. Tries the verified
     * strategy, then its verified fallbacks in order, and returns the first one that actually
     * starts. Returns null when the bypass is off, nothing is verified, or none of the
     * verified strategies could start; the download then simply proceeds on the direct
     * connection.
     */
    fun startIfEnabled(context: Context): BypassSession? {
        val requestedByYouTubeJourney = BypassVpnController.state.value.active
        if (!DpiPrefs.isEnabled(context) && !requestedByYouTubeJourney) return null
        val chain = DpiStrategyStore.verifiedChain(context)
        if (chain.isEmpty()) {
            AppLog.w("DpiBypass", "No verified strategy; continuing without bypass")
            return null
        }
        for (line in chain) {
            val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
            val session = DpiEngine.start(context, parsed.args)
            if (session != null) {
                AppLog.i("DpiBypass", "Engine ready; strategy=\"$line\"")
                return session
            }
            AppLog.w("DpiBypass", "Strategy did not start, trying the next verified one")
        }
        AppLog.w("DpiBypass", "No verified strategy could start; continuing without bypass")
        return null
    }

    /** Direct-only check of every probe host. True when nothing on this network needs bypassing. */
    internal fun directConnectionWorks(hosts: List<String> = DpiStrategySearch.DEFAULT_HOSTS): Boolean {
        val check = NetworkCheck()
        return inParallel(hosts) { host -> check.checkDirect(host).ok }.all { it }
    }

    /** Direct and bypassed check of every probe host with the currently selected strategy. */
    internal fun diagnose(context: Context, hosts: List<String> = DpiStrategySearch.DEFAULT_HOSTS): BypassDiagnosis {
        val line = DpiStrategyStore.selected(context)
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok
        val session = parsed?.let { DpiEngine.start(context, it.args) }
        try {
            val endpoint = session?.let { SocksEndpoint(DpiEngine.HOST, it.port) }
            val check = NetworkCheck()
            val reports = inParallel(hosts) { host -> check.run(endpoint, host) }
            return BypassDiagnosis(line, engineStarted = session != null, reports = reports)
        } finally {
            session?.close()
        }
    }

    /** Runs the strategy search over [DpiStrategyStore.candidates]. Blocking; see [DpiStrategySearch]. */
    internal fun search(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit
    ): List<StrategyResult> {
        val check = NetworkCheck(stageTimeoutMs = 2_500)
        val search = DpiStrategySearch(
            startEngine = { args -> DpiEngine.start(context, args) },
            probeHost = { port, host -> check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host).ok }
        )
        return search.run(
            DpiStrategyStore.candidates(context),
            isCancelled,
            onProgress,
            stopAfterFullPasses = MAX_VERIFIED_STRATEGIES
        )
    }

    private fun <T> inParallel(hosts: List<String>, block: (String) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(hosts.size)
        try {
            return hosts.map { host -> pool.submit(Callable { block(host) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
