package com.pstv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStepTest {
    @Test fun noAccelerationKeepsTheConfiguredStep() = assertEquals(5_000L, SeekController.stepFor(5_000, 12, 0.0))
    @Test fun heldKeyGrowsButIsCapped() {
        val first = SeekController.stepFor(5_000, 1, 0.7); val later = SeekController.stepFor(5_000, 10, 0.7)
        assertTrue(first in 5_001..9_999); assertTrue(later > first)
        assertEquals(75_000L, SeekController.stepFor(5_000, 500, 1.4))      // 15 x the step
        assertEquals(600_000L, SeekController.stepFor(60_000, 500, 1.4))    // never more than 10 minutes
    }
}
