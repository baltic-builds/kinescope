package com.kinescope.app

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiStrategySearchTest {
    private class FakeSession(override val port: Int, private val log: CopyOnWriteArrayList<String>) : BypassSession {
        override fun close() {
            log += "close:$port"
        }
    }

    private val hosts = listOf("a.example", "b.example", "c.example")

    /** Engine starts unless the strategy is in [failToStart]; probes pass according to [passes] by strategy text. */
    private fun search(
        log: CopyOnWriteArrayList<String>,
        failToStart: Set<String> = emptySet(),
        passes: Map<String, Int>
    ): DpiStrategySearch {
        var nextPort = 40000
        return DpiStrategySearch(
            startEngine = { args ->
                val key = args.joinToString(" ")
                log += "start:$key"
                if (key in failToStart) null else FakeSession(nextPort++, log)
            },
            probeHost = { port, host ->
                val key = log.last { it.startsWith("start:") }.removePrefix("start:")
                hosts.indexOf(host) < (passes[key] ?: 0)
            },
            hosts = hosts
        )
    }

    @Test
    fun stopsAtTheFirstStrategyThatGetsEveryHostThrough() {
        val log = CopyOnWriteArrayList<String>()
        val results = search(log, passes = mapOf("-d1" to 0, "-s1" to 3, "-f-1" to 3)).run(
            listOf("-d1", "-s1", "-f-1"), { false }, {}
        )
        assertEquals(listOf("-d1", "-s1"), results.map { it.line })
        assertTrue(results.last().fullPass)
        assertEquals(listOf("start:-d1", "close:40000", "start:-s1", "close:40001"), log.toList())
    }

    @Test
    fun withoutAFullPassItPicksTheStrategyWithMostHostsAndEarlierWinsTies() {
        val log = CopyOnWriteArrayList<String>()
        val results = search(log, passes = mapOf("-d1" to 1, "-s1" to 2, "-f-1" to 2, "-o1" to 0)).run(
            listOf("-d1", "-s1", "-f-1", "-o1"), { false }, {}
        )
        assertEquals(4, results.size)
        assertFalse(results.any { it.fullPass })
        assertEquals("-s1", DpiStrategySearch.best(results)?.line)
    }

    @Test
    fun anEngineThatDoesNotStartIsRecordedAndNeverSelected() {
        val log = CopyOnWriteArrayList<String>()
        val results = search(log, failToStart = setOf("-d1"), passes = mapOf("-s1" to 1)).run(
            listOf("-d1", "-s1"), { false }, {}
        )
        assertFalse(results[0].started)
        assertEquals("-s1", DpiStrategySearch.best(results)?.line)
        assertEquals(null, DpiStrategySearch.best(results.take(1)))
    }

    @Test
    fun invalidStrategiesAreSkippedWithoutStartingTheEngine() {
        val log = CopyOnWriteArrayList<String>()
        val results = search(log, passes = emptyMap()).run(listOf("-p1081", "--ip 0.0.0.0"), { false }, {})
        assertEquals(2, results.size)
        assertTrue(results.none { it.started })
        assertTrue(log.isEmpty())
    }

    @Test
    fun cancellationStopsBeforeTheNextStrategyAndReportsProgress() {
        val log = CopyOnWriteArrayList<String>()
        val progress = CopyOnWriteArrayList<Int>()
        var cancelled = false
        val results = search(log, passes = mapOf("-d1" to 1, "-s1" to 1, "-f-1" to 1)).run(
            listOf("-d1", "-s1", "-f-1"),
            { cancelled },
            { progress += it.index; if (it.results.size == 1) cancelled = true }
        )
        assertEquals(1, results.size)
        assertEquals(listOf(0, 1), progress.toList())
        assertEquals(listOf("start:-d1", "close:40000"), log.toList())
    }
}
