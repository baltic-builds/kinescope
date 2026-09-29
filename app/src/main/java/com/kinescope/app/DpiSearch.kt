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
    val throughputKbps: Int = 0
) {
    val fullPass: Boolean get() = started && total > 0 && requiredPassed
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
    private val deepProbe: ((port: Int) -> Int?)? = null
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
        stopAfterFullPasses: Int = 1
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
        var fullPasses = 0
        for ((index, line) in candidates.withIndex()) {
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
            return StrategyResult(
                line,
                started = true,
                passed = passed,
                total = hosts.size,
                requiredPassed = requiredPassed,
                deepOk = deepOk,
                throughputKbps = throughputKbps
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
         * Patch 33: the fully passing strategies, best first -- those whose real transfer
         * completed, then more hosts through, then higher measured speed. The sort is stable, so
         * the search order (hand-picked built-ins first) settles the remaining ties.
         */
        fun ranked(results: List<StrategyResult>): List<StrategyResult> =
            results.filter { it.fullPass }.sortedWith(
                compareByDescending<StrategyResult> { it.deepOk }
                    .thenByDescending { it.passed }
                    .thenByDescending { it.throughputKbps }
            )
    }
}
