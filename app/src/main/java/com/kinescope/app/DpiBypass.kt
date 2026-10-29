package com.kinescope.app

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Patch 54: whether the engine can reach the hosts at all on a connection, as found by [DpiBypass.reach].
 * [OK] it can; [IPV4_ONLY] it can with the engine's IPv4-only option (now remembered for the class); [NO_ROUTE] it cannot, with or
 * without it (the connection itself, not the strategy, is the problem); [BLOCKED] it reaches the host and the strategy is
 * what fails (the ordinary case a strategy search is for).
 */
internal enum class Reach { OK, IPV4_ONLY, NO_ROUTE, BLOCKED }

/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /**
     * How many strategies the chain keeps: one primary + fallbacks. Patch 46: six instead of four, so the strategies
     * that carried real traffic before (see StrategyMemory) fit beside the best of the last search. Patch 47: the one
     * definition is ChainPlanner.MAX_CHAIN, which DpiPrefs derives its fallback cap from too.
     */
    private const val MAX_VERIFIED_STRATEGIES = ChainPlanner.MAX_CHAIN

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

    /** Patch 54: per-stage timeout of the direct DNS and TCP picture logged when the engine cannot reach anything. */
    private const val FAMILY_STAGE_TIMEOUT_MS = 3_000

    /**
     * Patch 33: the strategies a download may use, best first -- empty when the bypass is off or
     * nothing is verified, in which case the download simply goes direct. Patch 53: the chain of the connection class
     * [cls] the download runs on (see [StrategyRouter]).
     */
    internal fun activeChain(context: Context, cls: NetClass): List<String> {
        val requestedByYouTubeJourney = BypassVpnController.state.value.active
        if (!DpiPrefs.isEnabled(context) && !requestedByYouTubeJourney) return emptyList()
        val chain = DpiStrategyStore.chain(context, cls)
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
    internal data class HealthResult(
        val ok: Boolean,
        val stage: String,
        val why: String,
        val ms: Long,
        /** Patch 54: the SOCKS reply code of a failed CONNECT, null when the failure was something else. */
        val replyCode: Int? = null,
        /** Patch 54: the engine could not reach the host at all (`StageResult.isReachFault`). */
        val reachFault: Boolean = false
    )

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
            last = HealthResult(false, failed.stage.name, LogFormat.shortReason(failed.detail), ms, failed.replyCode, failed.isReachFault)
            if (attempt < attempts) Thread.sleep(HEALTH_RETRY_PAUSE_MS)
        }
        return last
    }

    /**
     * Patch 53: stores what a search found on [cls] -- the results, which are the test half of every strategy's rating --
     * unless the connection changed to the other class while the search ran: results gathered partly on one network and
     * partly on another describe neither, so they are dropped. True when they were stored.
     */
    internal fun recordSearch(context: Context, cls: NetClass, results: List<StrategyResult>): Boolean {
        val now = NetworkState.connectionClass(context)
        if (now != null && now != cls) {
            AppLog.w("DpiBypass", "search net-changed from=${cls.key} to=${now.key}; results dropped")
            return false
        }
        // Patch 54: a strategy the engine could not even connect with says nothing about the strategy: it is not a failed test.
        val usable = withoutReachFaults(results)
        if (usable.size != results.size) {
            AppLog.w("DpiBypass", "search net=${cls.key} dropped=${results.size - usable.size} (the engine could not reach the host)")
        }
        DpiResultsStore.save(context, cls, usable)
        return true
    }

    /** Patch 54: [results] without the ones whose every failed probe was a reach fault (see [StrategyResult.connectFault]). */
    internal fun withoutReachFaults(results: List<StrategyResult>): List<StrategyResult> = results.filterNot { it.connectFault }

    /**
     * Patch 54: can the engine reach the hosts on [cls] at all? One engine start and one live check with [line] (any strategy: the
     * CONNECT stage is reached before a strategy does anything). A failure at the TLS or HTTP stage means the host was reached
     * and the strategy is what is blocked ([Reach.BLOCKED]). A failure at CONNECT with "unreachable" is the route: the same
     * strategy is tried once more with the engine's IPv4-only option, and when that gets through the class is remembered as
     * IPv4-only ([Reach.IPV4_ONLY]); when it does not either, nothing a strategy can do helps ([Reach.NO_ROUTE]) and the
     * log gets the direct DNS and TCP picture per IP family. An engine that cannot be started (it is busy) is "unknown": [Reach.OK].
     */
    internal fun reach(context: Context, cls: NetClass, line: String, isCancelled: () -> Boolean = { false }): Reach {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: return Reach.OK
        val already = DpiPrefs.ipv4Only(context, cls)
        val first = reachProbe(context, parsed.args, already) ?: return Reach.OK
        if (first.ok) return Reach.OK
        if (!first.reachFault) return Reach.BLOCKED
        if (isCancelled()) return Reach.OK
        if (!already) {
            val second = reachProbe(context, parsed.args, true) ?: return Reach.OK
            if (second.ok || !second.reachFault) {
                DpiPrefs.setIpv4Only(context, cls, true)
                AppLog.i(
                    "DpiBypass",
                    "reach net=${cls.key} v4only=learned rc=${first.replyCode ?: "-"} then=${second.stage}/${second.why}"
                )
                return Reach.IPV4_ONLY
            }
        }
        val family = NetworkCheck(stageTimeoutMs = FAMILY_STAGE_TIMEOUT_MS).familyReport(NetworkCheck.DEFAULT_HOST).format()
        AppLog.w("DpiBypass", "reach net=${cls.key} none rc=${first.replyCode ?: "-"} v4only=${if (already) 1 else 0} $family")
        return Reach.NO_ROUTE
    }

    private fun reachProbe(context: Context, args: List<String>, ipv4Only: Boolean): HealthResult? {
        val session = DpiEngine.start(context, args, ipv4Only = ipv4Only) ?: return null
        return try {
            checkHealth(session.port, tries = 1)
        } finally {
            session.close()
        }
    }

    /**
     * Patch 33: keeps the best [MAX_VERIFIED_STRATEGIES] of a finished search (primary first,
     * the rest as fallbacks). False when nothing fully passed, in which case nothing changes.
     *
     * Patch 53: the chain of [cls], built by [StrategyRouter.afterSearch] from the search's passes and the class's
     * ratings. The results must have been stored first ([recordSearch]) so that the ratings already include them; a
     * search that was started on a class the chain has no record of replaces any borrowed chain (see
     * `DpiStrategyStore.ensureSeeded`).
     */
    internal fun applySearchResults(
        context: Context,
        cls: NetClass,
        results: List<StrategyResult>,
        keepProven: Boolean = false,
        partial: Boolean = false
    ): Boolean {
        val ranked = DpiStrategySearch.ranked(results)
        if (ranked.isEmpty()) return false
        // Patch 54: a strategy the engine could not connect with is neither a pass nor a failure.
        val failed = results.filter { !it.fullPass && !it.connectFault }.map { it.line }.toSet()
        val now = System.currentTimeMillis()
        val ratings = DpiPrefs.ratings(context, cls, now)
        val pinned = DpiPrefs.pinned(context, cls)
        val merged = StrategyRouter.afterSearch(
            ranked = ranked.map { it.line },
            failed = failed,
            ratings = ratings,
            proven = DpiPrefs.provenLine(context, cls),
            pinned = pinned,
            keepProven = keepProven,
            // A search that was stopped early only saw part of the list: keep the old chain behind what it found.
            fill = if (partial) DpiStrategyStore.verifiedChain(context, cls) else emptyList(),
            max = MAX_VERIFIED_STRATEGIES
        )
        val primary = merged.firstOrNull() ?: return false
        DpiPrefs.markStrategiesVerified(context, cls, primary, merged.drop(1))
        val best = ranked.first()
        AppLog.i(
            "DpiBypass",
            "kept net=${cls.key} n=${merged.size} partial=$partial keep=$keepProven pin=${pinned != null} " +
                "rated=${StrategyRating.eligible(ratings).size} top sc=${best.score()} kbps=${best.throughputKbps} " +
                "load=${best.loadPassed}/${best.loadTotal}"
        )
        return true
    }

    /** Direct-only check of every probe host. True when nothing on this network needs bypassing. */
    internal fun directConnectionWorks(hosts: List<String> = DpiStrategySearch.DEFAULT_HOSTS): Boolean {
        val check = NetworkCheck()
        return inParallel(hosts) { host -> check.checkDirect(host).ok }.all { it }
    }

    /**
     * Runs the strategy search over [DpiStrategyStore.candidates]. Blocking; see [DpiStrategySearch]. Patch 53: on the
     * connection class [cls] the caller read when it started (the chain of that class is tried first).
     */
    internal fun search(
        context: Context,
        cls: NetClass,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit,
        /** Patch 35: test exactly these strategies (Settings "Test", the random check) instead of the whole list. */
        only: List<String>? = null,
        /** Patch 35: blocks while a download needs the engine. */
        waitIfPaused: () -> Unit = {},
        /**
         * Patch 55: when the search has found enough and stops ([SearchGoal.STANDARD] for a check, [SearchGoal.QUICK] for a
         * tunnel that is starting). Null tests every one of [only], or the whole list.
         */
        goal: SearchGoal? = null
    ): List<StrategyResult> {
        val startedAt = System.nanoTime()
        val check = NetworkCheck(stageTimeoutMs = SEARCH_STAGE_TIMEOUT_MS)
        // Patch 34: failures are counted by host, stage and reason instead of one log line per
        // probe (about 200 lines per full scan); logSearchSummary prints the totals once.
        val fails = ConcurrentHashMap<String, AtomicInteger>()
        // Patch 54: probes per engine port that failed because the engine could not reach the host.
        val reachFaults = ConcurrentHashMap<Int, AtomicInteger>()
        val search = DpiStrategySearch(
            startEngine = { args -> startWhenFree(context, args, isCancelled) },
            probeHost = { port, host ->
                val result = check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host)
                result.failed?.let { failed ->
                    if (failed.isReachFault) reachFaults.computeIfAbsent(port) { AtomicInteger() }.incrementAndGet()
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
            loadProbe = { port -> check.loadViaBypass(SocksEndpoint(DpiEngine.HOST, port)) },
            faults = { port -> reachFaults.remove(port)?.get() ?: 0 }
        )
        val candidates = only ?: searchOrder(context, cls)
        val results = search.run(
            candidates,
            isCancelled,
            onProgress,
            stopAfterFullPasses = Int.MAX_VALUE,
            waitIfPaused = waitIfPaused,
            goal = goal
        )
        logSearchSummary(results, fails, startedAt)
        return results
    }

    /**
     * Patch 55: the order a search of [cls] walks down. The current chain first (a check confirms or replaces it early, as since
     * patch 35), then every strategy in the order of [StrategyRank]: the ones with good evidence, the ones never tested here, and
     * last the ones that did badly, the frozen ones at the very end (they are tried only when the rest could not give enough).
     */
    internal fun searchOrder(context: Context, cls: NetClass, now: Long = System.currentTimeMillis()): List<String> {
        val order = StrategyRank.order(
            DpiStrategyStore.candidates(context),
            DpiPrefs.ratings(context, cls, now),
            DpiResultsStore.load(context, cls)
        )
        return (DpiStrategyStore.verifiedChain(context, cls) + order).distinct()
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
            "done n=${results.size} pass=${passes.size} nostart=${results.count { !it.started }} " +
                "fault=${results.count { it.connectFault }} ms=$ms fails=[$failText]"
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
