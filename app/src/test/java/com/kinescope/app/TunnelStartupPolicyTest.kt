package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStartupPolicyTest {
    @Test fun engineStartupAloneNeverAcceptsAnUnhealthyTunnel() {
        assertFalse(TunnelStartupPolicy.accepts(false, false))
        assertFalse(TunnelStartupPolicy.accepts(true, true))
        assertTrue(TunnelStartupPolicy.accepts(true, false))
    }

    @Test fun failedCandidatesOnlyBecomeEvidenceAfterARealHealthyFallback() {
        val attempted = listOf("wifi-a", "wifi-b", "wifi-a")
        assertEquals(emptyList<String>(), TunnelStartupPolicy.failuresToLearn(attempted, null))
        assertEquals(listOf("wifi-a"), TunnelStartupPolicy.failuresToLearn(attempted, "wifi-b"))
    }
}
