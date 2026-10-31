package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Patch 56: what the tunnel does when its transport stops. Read from a device report: a stop the user asked for was taken for a
 * failure and the strategy in use was blamed; a transport that died three seconds after a start, on a strategy that had just passed
 * its live check, put that strategy at the bottom of the chain.
 */
class TransportRecoveryTest {
    private val short = 3_000L
    private val long = TransportRecovery.LONG_RUN_MS

    @Test
    fun aStopTheUserAskedForIsNeverAFailureWhateverTheHistory() {
        assertEquals(TransportFate.STOPPED_BY_USER, TransportRecovery.decide(true, short, 0))
        assertEquals(TransportFate.STOPPED_BY_USER, TransportRecovery.decide(true, short, 5))
        assertEquals(TransportFate.STOPPED_BY_USER, TransportRecovery.decide(true, long * 10, 0))
    }

    @Test
    fun theFirstEarlyDeathOfAStrategyIsAnsweredWithAReconnectOnTheSameStrategy() {
        assertEquals(TransportFate.RESTART_SAME, TransportRecovery.decide(false, short, 0))
    }

    @Test
    fun aSecondEarlyDeathOfTheSameStrategyBlamesItAndMovesOn() {
        assertEquals(TransportFate.ROTATE, TransportRecovery.decide(false, short, TransportRecovery.FREE_EARLY_DEATHS))
        assertEquals(TransportFate.ROTATE, TransportRecovery.decide(false, 10_000L, 3))
    }

    @Test
    fun aTransportThatRanForTwoMinutesIsReconnectedAndNeverCounted() {
        assertEquals(TransportFate.RESTART_SAME, TransportRecovery.decide(false, long, 0))
        assertEquals(TransportFate.RESTART_SAME, TransportRecovery.decide(false, long + 1L, 4))
        assertEquals(TransportFate.ROTATE, TransportRecovery.decide(false, long - 1L, 1))
    }

    @Test
    fun onlyAFreeReconnectAfterAShortRunCountsAsAnEarlyDeath() {
        assertEquals(1, TransportRecovery.deathsAfter(0, short, TransportFate.RESTART_SAME))
        assertEquals(0, TransportRecovery.deathsAfter(0, long, TransportFate.RESTART_SAME))
        assertEquals(2, TransportRecovery.deathsAfter(2, short, TransportFate.ROTATE))
        assertEquals(2, TransportRecovery.deathsAfter(2, short, TransportFate.STOPPED_BY_USER))
    }

    @Test
    fun aFailureDrivenRestartCannotSelectTheSameStrategyAgain() {
        val chain = listOf("pinned", "second", "third")
        assertEquals(listOf("second", "third"), TunnelRotation.withoutFailed(chain, "pinned"))
        assertEquals(chain, TunnelRotation.withoutFailed(chain, null))
        assertEquals(emptyList<String>(), TunnelRotation.withoutFailed(listOf("only"), "only"))
    }

    @Test
    fun theStoryOfTheReportPlaysOutAsOneReconnectAndNoBlame() {
        // 17:32:30 in the report: dead after three seconds, first death of that strategy.
        var deaths = 0
        val first = TransportRecovery.decide(false, short, deaths)
        deaths = TransportRecovery.deathsAfter(deaths, short, first)
        assertEquals(TransportFate.RESTART_SAME, first)
        assertEquals(1, deaths)
        // It then carried traffic for minutes: the counter is cleared by the service ("tun proven"), and a later death is free again.
        deaths = 0
        assertEquals(TransportFate.RESTART_SAME, TransportRecovery.decide(false, 5 * 60_000L, deaths))
    }
}
