package com.kinescope.app

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class StrategyResult(
    val line: String,
    val started: Boolean,
    val passed: Int,
    val total: Int,
    /**
     * Whether every host in [DpiStrategySearch]'s `requiredHosts` passed -- not necessarily
     * every probed host. See CHANGELOG.md's Patch 30 entry: requiring the CDN redirector host
     * (`redirector.googlevideo.com`) to pass a bare synthetic probe alongside the two core hosts
     * was a likely source of false negatives, so it stays probed for diagnostics/ranking but is
     * no longer required for [fullPass].
     */
    val requiredPassed: Boolean = false,
    /** Patch 33: whether a real ~200 KB transfer through this strategy completed. */
    val deepOk: Boolean = false,
    /** Patch 33: measured speed of that transfer in KB/s (0 when it was not measured). */
    val throughputKbps: Int = 0,
    /** Patch 35: how many of [loadTotal] simultaneous connections through this strategy completed. */
    val loadPassed: Int = 0,
    val loadTotal: Int = 0,
    /** Patch 35: median time in ms to finish the TLS handshake under that load (0 = not measured). */
    val handshakeMs: Int = 0,
    val loadKbps: Int = 0
) {
    val fullPass: Boolean get() = started && total > 0 && requiredPassed

    /** Patch 35: at least four in five simultaneous connections completed. */
    val loadOk: Boolean get() = loadTotal > 0 && loadPassed * 5 >= loadTotal * 4

    /**
     * Patch 35: 0-100 quality score for display and tie-breaking: hosts through (25), a real
     * transfer (15), simultaneous connections that completed (45) and how quickly the handshakes
     * finished under that load (15). 0 for a strategy that did not fully pass.
     */
    fun score(): Int {
        if (!fullPass) return 0
        val hosts = if (total > 0) 25 * passed / total else 0
        val transfer = if (deepOk) 15 else 0
        val load = if (loadTotal > 0) 45 * loadPassed / loadTotal else 0
        val latency = when {
            loadTotal == 0 || loadPassed == 0 -> 0
            handshakeMs <= 500 -> 15
            handshakeMs <= 1_000 -> 10
            handshakeMs <= 2_000 -> 5
            else -> 0
        }
        return (hosts + transfer + load + latency).coerceIn(0, 100)
    }
}


internal data class SearchProgress(
    val index: Int,
    val total: Int,
    val current: String,
    val results: List<StrategyResult>
)

/**
 * Tries strategies one after another: start the engine with a strategy, check that every probe
 * host is reachable through it, stop the engine. The first strategy that gets every host through
 * ends the search, since nothing better can be found.
 *
 * Blocking. Run it on a background thread and stop it through [isCancelled].
 */
internal class DpiStrategySearch(
    private val startEngine: (List<String>) -> BypassSession?,
    private val probeHost: (port: Int, host: String) -> Boolean,
    private val hosts: List<String> = DEFAULT_HOSTS,
    /**
     * Hosts that must pass for [StrategyResult.fullPass]; defaults to every host (the original
     * "every host must pass" behavior, and what every existing caller/test still gets). A caller
     * can name a stricter core subset and still probe extra hosts for diagnostic/ranking purposes
     * only -- see [DpiBypass.search].
     */
    private val requiredHosts: Set<String> = hosts.toSet(),
    /**
     * Patch 33: optional real-traffic test, run only for a strategy that already passed the light
     * probe. Returns the measured speed in KB/s, or null when the transfer failed or stalled. The
     * light probe (a TLS handshake and one HEAD request) cannot tell a strategy that carries real
     * traffic from one that dies after the first packets, which is how a strategy that "passed"
     * could still fail every real download.
     */
    private val deepProbe: ((port: Int) -> Int?)? = null,
    /**
     * Patch 35: optional simultaneous-connection test, run only for a strategy whose single transfer
     * worked. The YouTube app opens many connections at once and pays a strategy's per-connection
     * cost every time; one connection at a time cannot show that.
     */
    private val loadProbe: ((port: Int) -> LoadResult?)? = null
) {
    /**
     * [stopAfterFullPasses] bounds how many fully-passing strategies to collect before
     * stopping early (the default of 1 reproduces the original "stop at the first working
     * strategy" behavior). The caller that wants a primary strategy plus fallbacks passes a
     * higher value; the extra full passes, if any, become the fallbacks.
     */
    fun run(
        candidates: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit,
        stopAfterFullPasses: Int = 1,
        /** Patch 35: called before each strategy; blocks while a download needs the engine. */
        waitIfPaused: () -> Unit = {}
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
        var fullPasses = 0
        for ((index, line) in candidates.withIndex()) {
            if (isCancelled()) break
            waitIfPaused()
            if (isCancelled()) break
            onProgress(SearchProgress(index, candidates.size, line, results.toList()))
            val result = try {
                evaluate(line)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            results += result
            onProgress(SearchProgress(index + 1, candidates.size, line, results.toList()))
            if (result.fullPass) {
                fullPasses++
                if (fullPasses >= stopAfterFullPasses) break
            }
        }
        return results
    }

    private fun evaluate(line: String): StrategyResult {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val session = startEngine(parsed.args)
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val pool = Executors.newFixedThreadPool(hosts.size)
        try {
            val checks = hosts.map { host -> host to pool.submit(Callable { probeHost(session.port, host) }) }
            val outcomes = checks.map { (host, future) ->
                host to try {
                    future.get(PROBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            val passed = outcomes.count { it.second }
            val requiredPassed = outcomes.filter { it.first in requiredHosts }.all { it.second }
            var deepOk = false
            var throughputKbps = 0
            if (requiredPassed && deepProbe != null) {
                val measured = try {
                    deepProbe.invoke(session.port)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (measured != null) {
                    deepOk = true
                    throughputKbps = measured
                }
            }
            // Patch 35: several connections at once, only for a strategy whose single transfer worked.
            var load: LoadResult? = null
            if (deepOk && loadProbe != null) {
                load = try {
                    loadProbe.invoke(session.port)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            return StrategyResult(
                line,
                started = true,
                passed = passed,
                total = hosts.size,
                requiredPassed = requiredPassed,
                deepOk = deepOk,
                throughputKbps = throughputKbps,
                loadPassed = load?.passed ?: 0,
                loadTotal = load?.total ?: 0,
                handshakeMs = load?.medianHandshakeMs ?: 0,
                loadKbps = load?.kbps ?: 0
            )
        } finally {
            pool.shutdownNow()
            session.close()
        }
    }

    companion object {
        /** A site page, an image host and a video host: the three kinds of traffic a download needs. */
        val DEFAULT_HOSTS = listOf("www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com")

        /**
         * The two hosts required for [StrategyResult.fullPass] in [DpiBypass.search]. Patch 30:
         * `redirector.googlevideo.com` (a CDN redirector) stays probed for diagnostics/ranking
         * but is no longer required to pass -- a bare synthetic TLS+HTTP/1.1 probe against a CDN
         * redirector is a plausible false-negative source independent of whether a strategy
         * actually works for real YouTube traffic. See CHANGELOG.md's Patch 30 entry.
         */
        val REQUIRED_HOSTS = setOf("www.youtube.com", "i.ytimg.com")
        private const val PROBE_DEADLINE_SECONDS = 25L

        /** The result to select: most hosts through wins, earlier candidates win ties. */
        fun best(results: List<StrategyResult>): StrategyResult? =
            results.filter { it.started && it.passed > 0 }.maxByOrNull { it.passed }

        /**
         * Patch 35: the fully passing strategies, best first. Simultaneous connections that
         * completed (what the YouTube app really does) rank first, then a real single transfer,
         * then the overall score (hosts, load, handshake latency), then single-transfer speed.
         * The sort is stable, so the search order breaks the remaining ties.
         */
        fun ranked(results: List<StrategyResult>): List<StrategyResult> =
            results.filter { it.fullPass }.sortedWith(
                compareByDescending<StrategyResult> { it.loadOk }
                    .thenByDescending { it.deepOk }
                    .thenByDescending { it.score() }
                    .thenByDescending { it.throughputKbps }
            )
    }
}
