package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeJudgeTest {
    private fun never(): Boolean = throw AssertionError("the lookup must not be made")

    @Test
    fun twoOfThreeIsAPass() {
        assertTrue(ProbeJudge.passes(2, 3))
        assertFalse(ProbeJudge.passes(1, 3))
        assertEquals(ProbeVerdict.OK, ProbeJudge.judge(3, 3, null, ::never))
    }

    @Test
    fun theReportedProbeIsIgnoredWhileRealDataFlows() {
        // 18:43:51.9: 1/3 passed, and the tunnel had carried 4 MB ten seconds before.
        assertEquals(ProbeVerdict.IGNORED_TRAFFIC, ProbeJudge.judge(1, 3, 9_000L, ::never))
    }

    @Test
    fun oldTrafficDoesNotExcuseAProbe() {
        assertEquals(ProbeVerdict.FAIL, ProbeJudge.judge(1, 3, ProbeJudge.TRAFFIC_GRACE_MS + 1L) { true })
        assertEquals(ProbeVerdict.FAIL, ProbeJudge.judge(0, 3, null) { true })
    }

    @Test
    fun aDeadLookupIsNotTheStrategysFault() {
        assertEquals(ProbeVerdict.IGNORED_DNS, ProbeJudge.judge(0, 3, null) { false })
    }

    @Test
    fun aClockThatWentBackIsNotTrafficProof() {
        assertEquals(ProbeVerdict.FAIL, ProbeJudge.judge(1, 3, -5L) { true })
    }
}
