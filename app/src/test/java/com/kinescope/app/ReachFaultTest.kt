package com.kinescope.app

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 54: telling an engine that cannot reach the host from a strategy that is blocked. */
class ReachFaultTest {
    @Test
    fun aSocksUnreachableReplyAtTheConnectStageIsAReachFault() {
        assertTrue(StageResult(CheckStage.CONNECT, false, "reply 0x04 (host unreachable)", 4).isReachFault)
        assertTrue(StageResult(CheckStage.CONNECT, false, "reply 0x03 (network unreachable)", 3).isReachFault)
    }

    @Test
    fun otherFailuresAreNotReachFaults() {
        assertFalse(StageResult(CheckStage.CONNECT, false, "reply 0x05 (connection refused)", 5).isReachFault)
        assertFalse(StageResult(CheckStage.CONNECT, false, "timeout", null).isReachFault)
        assertFalse(StageResult(CheckStage.TLS, false, "handshake", 4).isReachFault)
        assertFalse(StageResult(CheckStage.CONNECT, true, "connected", 0).isReachFault)
    }

    @Test
    fun theFamilyReportIsOneCompactLine() {
        assertEquals(
            "dns=ok a4=2 a6=1 tcp4=ok tcp6=unreach",
            FamilyReport("ok", 2, 1, "ok", "unreach").format()
        )
        assertEquals("dns=nohost a4=0 a6=0 tcp4=- tcp6=-", FamilyReport("nohost", 0, 0, "-", "-").format())
    }

    @Test
    fun theEnginesIpv4OnlyOptionIsAddedOnlyWhenAsked() {
        assertEquals(listOf("-i127.0.0.1", "-p1080", "-d1"), dpiLaunchArguments("127.0.0.1", 1080, listOf("-d1")))
        assertEquals(
            listOf("-i127.0.0.1", "-p1080", "-X", "-d1"),
            dpiLaunchArguments("127.0.0.1", 1080, listOf("-d1"), ipv4Only = true)
        )
    }

    private fun result(started: Boolean = true, passed: Int, faults: Int, required: Boolean = false) =
        StrategyResult("-d1", started = started, passed = passed, total = 3, requiredPassed = required, connectFaults = faults)

    @Test
    fun aStrategyWhoseEveryFailedProbeWasAReachFaultSaysNothingAboutTheStrategy() {
        assertTrue(result(passed = 0, faults = 3).connectFault)
        assertTrue(result(passed = 1, faults = 2).connectFault)
    }

    @Test
    fun aStrategyThatFailedAtALaterStageOrPassedIsJudgedAsBefore() {
        assertFalse(result(passed = 0, faults = 0).connectFault)
        assertFalse(result(passed = 0, faults = 2).connectFault)
        assertFalse(result(passed = 3, faults = 0, required = true).connectFault)
        assertFalse(result(started = false, passed = 0, faults = 3).connectFault)
    }

    @Test
    fun reachFaultsAreKeptOutOfTheRecords() {
        val kept = StrategyResult("-d2", started = true, passed = 3, total = 3, requiredPassed = true)
        val dropped = result(passed = 0, faults = 3)
        val failed = result(passed = 0, faults = 0)
        assertEquals(listOf(kept, failed), DpiBypass.withoutReachFaults(listOf(kept, dropped, failed)))
    }

    private class FakeSession(override val port: Int) : BypassSession {
        override fun close() = Unit
    }

    @Test
    fun theSearchReadsTheEnginesReachFaultsAfterTheProbesOfOneStrategy() {
        val asked = CopyOnWriteArrayList<Int>()
        val search = DpiStrategySearch(
            startEngine = { FakeSession(41000) },
            probeHost = { _, _ -> false },
            hosts = listOf("a.example", "b.example", "c.example"),
            faults = { port ->
                asked += port
                3
            }
        )
        val results = search.run(listOf("-d1"), isCancelled = { false }, onProgress = {})
        assertEquals(listOf(41000), asked.toList())
        assertEquals(3, results.single().connectFaults)
        assertTrue(results.single().connectFault)
    }
}
