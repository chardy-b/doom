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
    @Test fun smootherstepHasQuinticQuarterSampleAndFlatEndpoints() {
        assertEquals(0.103515625f, BreathingVisuals.smootherstep(.25f), 0.000001f)
        assertEquals(0.5f, BreathingVisuals.smootherstep(.5f), 0.000001f)
        val epsilon = .001f
        assertTrue(BreathingVisuals.smootherstep(epsilon) < epsilon)
        assertTrue(1f - BreathingVisuals.smootherstep(1f - epsilon) < epsilon)
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
    @Test fun denseGeometryHasThirtyTwoPitchesAndContinuousTargetExtents() {
        val width = 320f
        val low = BreathingVisuals.geometry(0f, width, 240f)
        val high = BreathingVisuals.geometry(1f, width, 240f)
        fun extent(cells: List<BloomCell>) = cells.maxOf { it.right } - cells.minOf { it.left }
        assertEquals(width * .20f, extent(low), .001f)
        assertEquals(width * .875f, extent(high), .001f)
        assertTrue(high.map { it.gridX }.toSet().size >= 28)
        assertTrue(high.map { it.gridY }.toSet().size >= 20)
        assertTrue(BreathingVisuals.geometry(.35f, width, 240f).any { it.left % (width / 32f) != 0f })
    }

    @Test fun tallPortraitFitsTheBloomAndLandscapeIntentionallyCompressesVertically() {
        fun extents(cells: List<BloomCell>): Pair<Float, Float> =
            (cells.maxOf { it.right } - cells.minOf { it.left }) to
                (cells.maxOf { it.bottom } - cells.minOf { it.top })

        val tallPortrait = extents(BreathingVisuals.geometry(1f, 320f, 640f))
        val landscape = extents(BreathingVisuals.geometry(1f, 640f, 320f))

        assertEquals(280f, tallPortrait.first, .001f)
        assertEquals(280f, tallPortrait.second, .001f)
        assertEquals(560f, landscape.first, .001f)
        assertEquals(280f, landscape.second, .001f)
        assertTrue(landscape.first > landscape.second * 1.5f)
    }

    @Test fun visibleAlphaFloorPreservesTheMinimumAndMaximumExtents() {
        fun extent(progress: Float): Float = BreathingVisuals.geometry(progress, 320f, 240f)
            .filter { it.alpha >= 0.25f }
            .let { it.maxOf { cell -> cell.right } - it.minOf { cell -> cell.left } }
        assertEquals(64f, extent(0f), .001f)
        assertEquals(280f, extent(1f), .001f)
        assertTrue(BreathingVisuals.geometry(0f, 320f, 240f).any { it.alpha >= 0.25f && it.role == BloomColorRole.GOLD })
    }

    @Test fun warmPaletteIsContinuousAcrossGoldOrangeAndAubergineNeighbors() {
        val colors = (0..100).map { index -> BreathingVisuals.colorAt(index / 100f) }
        colors.zipWithNext().forEach { (first, second) ->
            val channelDelta = listOf(16, 8, 0).maxOf { shift ->
                kotlin.math.abs(((first ushr shift) and 0xFF) - ((second ushr shift) and 0xFF))
            }
            assertTrue("neighbor jump=$channelDelta", channelDelta <= 8)
        }
        assertEquals(BreathingVisuals.ORANGE, BreathingVisuals.colorAt(.34f))
        assertEquals(BreathingVisuals.AUBERGINE, BreathingVisuals.colorAt(.68f))
    }

    @Test fun geometryHasOneIvoryCenterAndClosedWarmPalette() {
        listOf(0f, .2f, .5f, .9f, 1f).forEach { progress ->
            val cells = BreathingVisuals.geometry(progress, 480f, 300f)
            assertEquals(1, cells.count { it.role == BloomColorRole.PAPER && it.alpha == 1f })
            assertTrue(cells.all { it.role != BloomColorRole.PAPER || (it.gridX == 16 && it.gridY == 16) })
            assertTrue(cells.all { it.color == BreathingVisuals.PAPER || it.color ushr 24 == 0xFF })
        }
    }

    @Test fun reducedMotionUsesOneSharedStaticGeometryValue() {
        val static = BreathingVisuals.geometry(BreathingVisuals.staticProgress(), 320f, 180f)
        assertEquals(static, BreathingVisuals.geometry(BreathingVisuals.staticProgress(), 320f, 180f))
        assertTrue(BreathingVisuals.staticProgress() in 0f..1f)
    }
}
