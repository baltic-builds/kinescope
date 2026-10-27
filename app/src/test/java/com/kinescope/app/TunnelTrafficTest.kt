package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 45, cut down in patch 55 (the tunnel's speed is no longer shown or measured). */
class TunnelTrafficTest {
    @Test
    fun aFreshTrafficSnapshotIsQuiet() {
        val fresh = TunnelTraffic()
        assertEquals(FlowVerdict.IDLE, fresh.verdict)
        assertEquals(0, fresh.position)
        assertEquals(0, fresh.total)
    }
}
