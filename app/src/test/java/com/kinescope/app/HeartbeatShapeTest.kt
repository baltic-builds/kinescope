package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 55: the shape of one beat of the dashboard's heartbeat line. */
class HeartbeatShapeTest {
    private val samples = (0..999).map { it / 1000f }

    @Test
    fun theSpikeIsTheHighestPointAndComesInTheMiddleOfTheBeat() {
        val peak = samples.maxByOrNull { heartbeatSample(it) }!!
        assertTrue("peak at $peak", peak in 0.38f..0.42f)
        assertEquals(1.0f, heartbeatSample(peak), 0.1f)
    }

    @Test
    fun theLineStaysWithinTheBoxItIsDrawnIn() {
        for (t in samples) {
            val value = heartbeatSample(t)
            assertTrue("t=$t value=$value", value in -0.5f..1.1f)
        }
    }

    @Test
    fun theBeatRestsOnTheBaselineAtItsEnds() {
        // The end of a beat and the start of the next join without a jump.
        assertEquals(0f, heartbeatSample(0f), 0.02f)
        assertEquals(heartbeatSample(0f), heartbeatSample(0.9999f), 0.02f)
        assertEquals(0f, heartbeatSample(0.9f), 0.02f)
    }

    @Test
    fun thereIsADipAfterTheSpikeBelowTheBaseline() {
        assertTrue(heartbeatSample(0.44f) < -0.2f)
    }
}
