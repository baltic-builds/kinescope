package com.kinescope.app

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class StrategyResult(val line: String, val started: Boolean, val passed: Int, val total: Int) {
    val fullPass: Boolean get() = started && total > 0 && passed == total
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
    private val hosts: List<String> = DEFAULT_HOSTS
) {
    fun run(
        candidates: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
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
            if (result.fullPass) break
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
            val checks = hosts.map { host -> pool.submit(Callable { probeHost(session.port, host) }) }
            val passed = checks.count { check ->
                try {
                    check.get(PROBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            return StrategyResult(line, started = true, passed = passed, total = hosts.size)
        } finally {
            pool.shutdownNow()
            session.close()
        }
    }

    companion object {
        /** A site page, an image host and a video host: the three kinds of traffic a download needs. */
        val DEFAULT_HOSTS = listOf("www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com")
        private const val PROBE_DEADLINE_SECONDS = 20L

        /** The result to select: most hosts through wins, earlier candidates win ties. */
        fun best(results: List<StrategyResult>): StrategyResult? =
            results.filter { it.started && it.passed > 0 }.maxByOrNull { it.passed }
    }
}
