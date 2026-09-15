package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class BreathingVisualsTest {
    @Test fun exactPhaseBoundariesUseFourInSixOut() {
        assertEquals(BreathPhase.IN, BreathingVisuals.frame(0, 10_000).phase)
        assertEquals(BreathPhase.IN, BreathingVisuals.frame(3_999, 10_000).phase)
        assertEquals(BreathPhase.OUT, BreathingVisuals.frame(4_000, 10_000).phase)
        assertEquals(BreathPhase.OUT, BreathingVisuals.frame(9_999, 20_000).phase)
        assertEquals(BreathPhase.IN, BreathingVisuals.frame(10_000, 20_000).phase)
    }
    @Test fun easingMovesLessAtEndpointsThanMiddle() {
        fun b(t:Long)=BreathingVisuals.frame(t,10_000).bloom
        assertTrue(b(100)-b(0) < b(2_100)-b(2_000))
        assertTrue(b(4_000)-b(3_900) < b(2_100)-b(2_000))
    }
    @Test fun segmentMappingAndCompletionAreExact() {
        assertEquals(listOf(0f), BreathingVisuals.frame(0,10_000).segments)
        assertEquals(listOf(1f, .5f), BreathingVisuals.frame(15_000,20_000).segments)
        assertEquals(listOf(1f,1f,1f), BreathingVisuals.frame(30_000,30_000).segments)
    }
    @Test fun paletteContrastAndLayersMeetContract() {
        assertTrue(BreathingVisuals.contrastRatio(BreathingVisuals.PAPER,BreathingVisuals.INK)>=4.5)
        assertTrue(BreathingVisuals.cells(.5f).map { it.layer }.toSet().size >= 4)
    }
}
