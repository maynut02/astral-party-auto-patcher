package com.maynutlab.astralpatcher.ui

import org.junit.Assert.*
import org.junit.Test

class LatestRequestGateTest {
    @Test fun refreshDuringSamePackageInspectionDiscardsOldResultAndRunsNewestRequest() {
        val gate = LatestRequestGate()
        gate.request()
        val old = gate.startIfIdle(false)!!
        gate.request()
        gate.request()
        assertFalse(gate.isCurrent(old))
        assertNull(gate.startIfIdle(false))
        gate.finish(old)
        val latest = gate.startIfIdle(false)!!
        assertEquals(3L, latest)
        assertTrue(gate.isCurrent(latest))
        gate.finish(latest)
        assertNull(gate.startIfIdle(false))
    }

    @Test fun requestDuringFileWorkSurvivesFailureOrCancellationUntilWorkEnds() {
        val gate = LatestRequestGate()
        gate.request()
        assertNull(gate.startIfIdle(true))
        gate.request()
        assertNull(gate.startIfIdle(true))
        val latest = gate.startIfIdle(false)!!
        assertEquals(2L, latest)
        gate.finish(latest)
        assertNull(gate.startIfIdle(false))
    }
}
