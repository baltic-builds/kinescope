package com.kinescope.app

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    private const val HEALTH_STAGE_TIMEOUT_MS = 6_000

    /** Patch 34: pause between the two tries of a live check; passing strategies listed per search. */
    private const val HEALTH_RETRY_PAUSE_MS = 400L
    private const val START_WAIT_MS = 30_000L
    private const val MAX_PASS_LINES = 6

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
        val chain = DpiStrategyStore.chain(context)
        if (chain.isEmpty()) AppLog.w("DpiBypass", "No verified strategy; continuing without bypass")
        return chain
    }

    /**
     * Patch 34: starts the engine for one specific strategy line; null when it did not start.
     * [label] is the compact route name (`s2/4`) used in the log instead of the long argument
     * string, which DiagnosticReport lists once in its header.
     */
    fun startLine(context: Context, line: String, label: String = ""): BypassSession? {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: return null
        var session = DpiEngine.start(context, parsed.args)
        // Patch 35: a strategy check may hold the engine for a few seconds; wait for it instead of
        // reporting a start failure.
        var waited = 0L
        while (session == null && DpiEngine.regularBusy() && waited < START_WAIT_MS) {
            Thread.sleep(500L)
            waited += 500L
            session = DpiEngine.start(context, parsed.args)
        }
        AppLog.i("DpiBypass", if (session != null) "up s=$label" else "no-start s=$label")
        return session
    }

    /** Patch 34: outcome of one live connection check; [stage] and [why] are log-sized. */
    internal data class HealthResult(val ok: Boolean, val stage: String, val why: String, val ms: Long)

    /**
     * Patch 34: one real connection through a running engine, retried [tries] times. A strategy
     * that merely starts is not necessarily one that gets through right now, because the network
     * can change between the search and the moment it is used, and a single handshake can fail
     * where the next succeeds. The result says which stage failed and why, so a failed check
     * leaves evidence in the log instead of a bare "failed".
     */
    internal fun checkHealth(port: Int, tries: Int = 2): HealthResult {
        val check = NetworkCheck(stageTimeoutMs = HEALTH_STAGE_TIMEOUT_MS)
        val endpoint = SocksEndpoint(DpiEngine.HOST, port)
        val startedAt = System.nanoTime()
        var last = HealthResult(false, "?", "?", 0L)
        val attempts = tries.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            val failed = check.checkViaBypass(endpoint, NetworkCheck.DEFAULT_HOST).failed
            val ms = (System.nanoTime() - startedAt) / 1_000_000L
            if (failed == null) return HealthResult(true, "ok", "-", ms)
            last = HealthResult(false, failed.stage.name, LogFormat.shortReason(failed.detail), ms)
            if (attempt < attempts) Thread.sleep(HEALTH_RETRY_PAUSE_MS)
        }
        return last
    }

    internal fun isHealthy(port: Int): Boolean = checkHealth(port, tries = 1).ok

    /**
     * Patch 33: keeps the best [MAX_VERIFIED_STRATEGIES] of a finished search (primary first,
     * the rest as fallbacks). False when nothing fully passed, in which case nothing changes.
     */
    internal fun applySearchResults(
        context: Context,
        results: List<StrategyResult>,
        keepProven: Boolean = false,
        partial: Boolean = false
    ): Boolean {
        val ranked = DpiStrategySearch.ranked(results)
        if (ranked.isEmpty()) return false
        val failed = results.filter { !it.fullPass }.map { it.line }.toSet()
        val merged = ChainPlanner.merge(
            ranked = ranked.map { it.line },
            failed = failed,
            proven = DpiPrefs.provenLine(context),
            pinned = DpiPrefs.pinned(context),
            keepProven = keepProven,
            max = MAX_VERIFIED_STRATEGIES,
            // A search that was stopped early only saw part of the list: keep the old chain behind what it found.
            fill = if (partial) DpiStrategyStore.verifiedChain(context) else emptyList()
        )
        val primary = merged.firstOrNull() ?: return false
        DpiPrefs.markStrategiesVerified(context, primary, merged.drop(1))
        val best = ranked.first()
        AppLog.i(
            "DpiBypass",
            "kept n=${merged.size} partial=$partial keep=$keepProven pin=${DpiPrefs.pinned(context) != null} " +
                "top sc=${best.score()} kbps=${best.throughputKbps} load=${best.loadPassed}/${best.loadTotal}"
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
        fullScan: Boolean = false,
        /** Patch 35: test exactly these strategies (Settings "Test") instead of the whole list. */
        only: List<String>? = null,
        /** Patch 35: blocks while a download needs the engine. */
        waitIfPaused: () -> Unit = {}
    ): List<StrategyResult> {
        val startedAt = System.nanoTime()
        val check = NetworkCheck(stageTimeoutMs = SEARCH_STAGE_TIMEOUT_MS)
        // Patch 34: failures are counted by host, stage and reason instead of one log line per
        // probe (about 200 lines per full scan); logSearchSummary prints the totals once.
        val fails = ConcurrentHashMap<String, AtomicInteger>()
        val search = DpiStrategySearch(
            startEngine = { args -> startWhenFree(context, args, isCancelled) },
            probeHost = { port, host ->
                val result = check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host)
                result.failed?.let { failed ->
                    val key = "${hostTag(host)}:${failed.stage}:${LogFormat.shortReason(failed.detail)}"
                    fails.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                }
                result.ok
            },
            // Patch 30: only the two core hosts gate whether a strategy counts as verified.
            // redirector.googlevideo.com (a CDN redirector) stays probed for the ranking/
            // diagnostic count but is no longer required -- see CHANGELOG.md.
            requiredHosts = DpiStrategySearch.REQUIRED_HOSTS,
            // Patch 33: rank by a real transfer, not only by the light probe.
            deepProbe = { port -> check.throughputViaBypass(SocksEndpoint(DpiEngine.HOST, port)) },
            // Patch 35: and by how it behaves with several connections at once.
            loadProbe = { port -> check.loadViaBypass(SocksEndpoint(DpiEngine.HOST, port)) }
        )
        // Patch 35: the current chain goes first, so a re-check confirms or replaces it early.
        val candidates = only ?: (DpiStrategyStore.verifiedChain(context) + DpiStrategyStore.candidates(context)).distinct()
        val results = search.run(
            candidates,
            isCancelled,
            onProgress,
            stopAfterFullPasses = if (fullScan || only != null) Int.MAX_VALUE else MAX_PASSES_TO_RANK,
            waitIfPaused = waitIfPaused
        )
        logSearchSummary(results, fails, startedAt)
        return results
    }

    /**
     * Patch 34: the whole search in a handful of lines: totals, the most common failures
     * (`host:stage:reason=count`) and one line per fully passing strategy. Replaces one line per
     * probe, which made a single scan about 200 lines long.
     */
    private fun logSearchSummary(results: List<StrategyResult>, fails: Map<String, AtomicInteger>, startedAt: Long) {
        val ms = (System.nanoTime() - startedAt) / 1_000_000L
        val passes = results.filter { it.fullPass }
        val failText = fails.entries.sortedByDescending { it.value.get() }.take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        AppLog.i(
            "DpiSearch",
            "done n=${results.size} pass=${passes.size} nostart=${results.count { !it.started }} ms=$ms fails=[$failText]"
        )
        for (pass in passes.take(MAX_PASS_LINES)) {
            val transfer = if (pass.deepOk) "${pass.throughputKbps}KB/s" else "no"
            AppLog.i(
                "DpiSearch",
                "pass sc=${pass.score()} h=${pass.passed}/${pass.total} xfer=$transfer load=${pass.loadPassed}/${pass.loadTotal} " +
                    "hs=${pass.handshakeMs}ms s=\"${pass.line.take(60)}\""
            )
        }
    }

    /** Starts the search engine, waiting while a download holds it; null on a real start failure or a stop. */
    private fun startWhenFree(context: Context, args: List<String>, isCancelled: () -> Boolean): BypassSession? {
        while (true) {
            val session = DpiEngine.start(context, args)
            if (session != null) return session
            if (!DpiEngine.regularBusy() || isCancelled()) return null
            Thread.sleep(500L)
        }
    }

    /**
     * Patch 35: several simultaneous connections through a running engine (the YouTube tunnel's
     * periodic probe). A lighter version of the search's load test: smaller transfers, fewer connections.
     */
    internal fun checkLoad(port: Int, connections: Int = 3): LoadResult =
        NetworkCheck(stageTimeoutMs = HEALTH_STAGE_TIMEOUT_MS)
            .loadViaBypass(SocksEndpoint(DpiEngine.HOST, port), connections = connections, maxBytes = 40_000, budgetMs = 4_000)

    private fun hostTag(host: String): String = when (host) {
        "www.youtube.com" -> "yt"
        "i.ytimg.com" -> "img"
        "redirector.googlevideo.com" -> "gv"
        else -> host.take(12)
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
