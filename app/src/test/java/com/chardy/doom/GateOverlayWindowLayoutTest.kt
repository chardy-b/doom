package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class GateOverlayWindowLayoutTest {
    private fun layout(w: Int = 400, h: Int = 800, insets: GateSafeInsets = GateSafeInsets(0, 24, 0, 32)) =
        GateOverlayWindowLayout.calculate(w, h, insets, 1f, listOf(52, 48, 48))
    @Test fun onlyThreeBoundedButtonRegionsAreTouchable() {
        val windows = requireNotNull(layout())
        assertEquals(4, windows.size)
        assertEquals(3, windows.count { it.touchable })
        windows.filter { it.touchable }.forEach { assertEquals(360, it.bounds.width); assertTrue(it.bounds.height in 48..52) }
    }
    @Test fun visualRoleAlwaysPassesInput() {
        val visual = requireNotNull(layout()).first()
        assertEquals(GateWindowRole.VISUAL, visual.role)
        assertFalse(visual.touchable)
    }
    @Test fun gapsAndSystemInsetsAreOutsideButtonRegions() {
        listOf(GateSafeInsets(20, 60, 30, 40), GateSafeInsets(0, 24, 0, 300)).forEach { safe ->
            val buttons = requireNotNull(layout(insets = safe)).drop(1)
            buttons.forEach { (_, b) ->
                assertTrue(b.x >= safe.left + 16)
                assertTrue(b.x + b.width <= 400 - safe.right - 16)
                assertTrue(b.y >= safe.top)
                assertTrue(b.y + b.height <= 800 - safe.bottom - 16)
            }
            buttons.zipWithNext().forEach { (a, b) -> assertTrue(a.bounds.y + a.bounds.height < b.bounds.y) }
        }
    }
    @Test fun largeFontLandscapeFailsOpenInsteadOfFullscreen() {
        assertNull(GateOverlayWindowLayout.calculate(640, 320, GateSafeInsets(), 1f, listOf(100, 100, 100)))
        assertNull(layout(0, 800)); assertNull(layout(400, 0)); assertNull(layout(400, 100))
        assertNull(GateOverlayWindowLayout.calculate(400, 800, GateSafeInsets(), 1f, listOf(500, 500, 500)))
    }
    @Test fun rotationRecomputesWithinSafeArea() {
        val portrait = requireNotNull(layout())
        val landscape = requireNotNull(layout(800, 400, GateSafeInsets(32, 0, 32, 0)))
        assertTrue(portrait[1].bounds.x != landscape[1].bounds.x)
        assertTrue(landscape.drop(1).all { it.bounds.y + it.bounds.height < 400 })
    }
    @Test fun recoveryRoleIsInvisibleAndNonTouchable() {
        requireNotNull(layout()).forEach { original ->
            val recovery = original.recovery()
            assertEquals(original.bounds, recovery.bounds)
            assertFalse(recovery.touchable)
            assertEquals(0f, recovery.alpha)
        }
    }
}
