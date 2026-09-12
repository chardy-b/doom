package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class BreathingVisualsTest {
    @Test fun geometryIsBoundedAndClampsProgress() {
        assertEquals(BreathingVisuals.cells(0f), BreathingVisuals.cells(-3f))
        assertEquals(BreathingVisuals.cells(1f), BreathingVisuals.cells(4f))
        assertTrue(BreathingVisuals.cells(0.5f).all { it.x in -5..5 && it.y in -5..5 })
    }

    @Test fun reducedMotionUsesFixedMiddlePhase() {
        assertEquals(0.5f, BreathingVisuals.staticProgress())
        assertEquals(BreathingVisuals.cells(0.5f), BreathingVisuals.cells(BreathingVisuals.staticProgress()))
    }

    @Test fun countdownHasNoPrematureCompletion() {
        assertEquals(1, BreathingVisuals.countdownSeconds(1))
        assertEquals(0, BreathingVisuals.countdownSeconds(0))
    }

    @Test fun chosenDoomColorsMeetTextContrast() {
        assertTrue(BreathingVisuals.contrastRatio(BreathingVisuals.PAPER, BreathingVisuals.INK) >= 4.5)
        assertTrue(BreathingVisuals.contrastRatio(BreathingVisuals.JADE, BreathingVisuals.INK) >= 4.5)
        assertTrue(BreathingVisuals.contrastRatio(BreathingVisuals.INK, BreathingVisuals.JADE) >= 3.0)
    }
}
