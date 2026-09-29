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
     * Patch 33: after the first launch (which scans every candidate) a manual search stops once
     * this many strategies passed, so it does not always take minutes. Ranking still applies.
     */
    private const val MAX_PASSES_TO_RANK = 12

    /** Patch 33: per-stage timeout of the quick live check made right before a strategy is used. */
    private const val HEALTH_STAGE_TIMEOUT_MS = 5_000

    /**
     * Per-stage socket timeout used only while searching (Patch 30). A working desync strategy
     * adds real round-trip latency -- fragmented or delayed TCP segments, sometimes a fake
     * packet -- so the previous 2.5s budget was tight enough to plausibly time out a strategy
     * that would otherwise have passed. The one-off "Test selected" connection check keeps
     * NetworkCheck()'s own default budget; this only affects the multi-candidate search.
     */
    private const val SEARCH_STAGE_TIMEOUT_MS = 4_000

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

    /**
     * Patch 33: the strategies a download may use, best first -- empty when the bypass is off or
     * nothing is verified, in which case the download simply goes direct.
     */
    fun activeChain(context: Context): List<String> {
        val requestedByYouTubeJourney = BypassVpnController.state.value.active
        if (!DpiPrefs.isEnabled(context) && !requestedByYouTubeJourney) return emptyList()
        val chain = DpiStrategyStore.verifiedChain(context)
        if (chain.isEmpty()) AppLog.w("DpiBypass", "No verified strategy; continuing without bypass")
        return chain
    }

    /** Patch 33: starts the engine for one specific strategy line; null when it did not start. */
    fun startLine(context: Context, line: String): BypassSession? {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: return null
        val session = DpiEngine.start(context, parsed.args)
        if (session != null) AppLog.i("DpiBypass", "Engine ready; strategy=\"$line\"")
        return session
    }

    /**
     * Patch 33: one real connection through a running engine. A strategy that merely starts is
     * not necessarily one that gets through right now, because the network can change between
     * the search and the moment it is used.
     */
    internal fun isHealthy(port: Int): Boolean =
        NetworkCheck(stageTimeoutMs = HEALTH_STAGE_TIMEOUT_MS)
            .checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), NetworkCheck.DEFAULT_HOST)
            .ok

    /**
     * Patch 33: keeps the best [MAX_VERIFIED_STRATEGIES] of a finished search (primary first,
     * the rest as fallbacks). False when nothing fully passed, in which case nothing changes.
     */
    internal fun applySearchResults(context: Context, results: List<StrategyResult>): Boolean {
        val ranked = DpiStrategySearch.ranked(results).take(MAX_VERIFIED_STRATEGIES)
        val primary = ranked.firstOrNull() ?: return false
        DpiPrefs.markStrategiesVerified(context, primary.line, ranked.drop(1).map { it.line })
        AppLog.i(
            "DpiBypass",
            "Search kept ${ranked.size} strategies; primary transfer=${primary.deepOk} speed=${primary.throughputKbps}KB/s"
        )
        return true
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
        onProgress: (SearchProgress) -> Unit,
        fullScan: Boolean = false
    ): List<StrategyResult> {
        val check = NetworkCheck(stageTimeoutMs = SEARCH_STAGE_TIMEOUT_MS)
        val search = DpiStrategySearch(
            startEngine = { args -> DpiEngine.start(context, args) },
            probeHost = { port, host ->
                val result = check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host)
                logProbeOutcome(host, result)
                result.ok
            },
            // Patch 30: only the two core hosts gate whether a strategy counts as verified.
            // redirector.googlevideo.com (a CDN redirector) stays probed for the ranking/
            // diagnostic count but is no longer required -- see CHANGELOG.md.
            requiredHosts = DpiStrategySearch.REQUIRED_HOSTS,
            // Patch 33: rank by a real transfer, not only by the light probe.
            deepProbe = { port -> check.throughputViaBypass(SocksEndpoint(DpiEngine.HOST, port)) }
        )
        return search.run(
            DpiStrategyStore.candidates(context),
            isCancelled,
            onProgress,
            stopAfterFullPasses = if (fullScan) Int.MAX_VALUE else MAX_PASSES_TO_RANK
        )
    }

    /**
     * Logs which stage failed for a probe host during the strategy search, so a real device run
     * leaves the actual per-host, per-stage detail (DNS/TCP/PROXY/CONNECT/TLS/HTTP) in the hidden
     * diagnostic log journal instead of only a bare pass/fail count. See CHANGELOG.md's Patch 30
     * entry: this is exactly the missing evidence the prior investigation asked for.
     */
    private fun logProbeOutcome(host: String, result: PathResult) {
        val failed = result.failed
        if (failed != null) {
            AppLog.i("DpiSearch", "probe failed host=$host stage=${failed.stage} detail=${failed.detail}")
        } else {
            AppLog.i("DpiSearch", "probe passed host=$host")
        }
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
