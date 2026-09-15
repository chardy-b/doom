package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class InstagramSessionTimerTest {
    @Test fun collapseAndSpokenUnitsRemainMeaningful() {
        var now = 0L
        val timer = InstagramSessionTimer { now }
        timer.toggle()
        assertFalse(timer.collapsed)
        timer.observeVerifiedInstagram(true, true, true)
        now = 1_000L
        assertEquals("Instagram time, 1 second", timer.model(false)?.contentDescription)
        timer.toggle()
        assertTrue(timer.model(false, true)!!.collapsed)
        timer.toggle()
        assertFalse(timer.model(false, true)!!.collapsed)
        now = 3_661_000L
        assertEquals("Instagram time, 1 hour, 1 minute, 1 second", timer.model(false)?.contentDescription)
        now = 7_322_000L
        assertEquals("Instagram time, 2 hours, 2 minutes, 2 seconds", timer.model(false)?.contentDescription)
        timer.endSession()
        assertFalse(timer.collapsed)
    }

    @Test fun formatsBoundariesAndCountsBreathingTime() {
        val now = longArrayOf(1_000L)
        val timer = InstagramSessionTimer { now[0] }
        assertTrue(timer.observeVerifiedInstagram(true, true, true))
        assertEquals("0:00", timer.model(false, false)?.text)
        now[0] += 3_599_000L
        assertEquals("59:59", timer.model(false, false)?.text)
        now[0] += 1_000L
        assertEquals("1:00:00", timer.model(false, false)?.text)
    }

    @Test fun authorityIsRequiredAndTerminalPathsResetSynchronously() {
        val now = longArrayOf(10L)
        val timer = InstagramSessionTimer { now[0] }
        assertFalse(timer.observeVerifiedInstagram(true, false, true))
        assertFalse(timer.running)
        assertTrue(timer.observeVerifiedInstagram(true, true, true))
        now[0] = 1_010L
        timer.endSession()
        assertFalse(timer.running)
        now[0] = 2_000L
        assertTrue(timer.observeVerifiedInstagram(true, true, true))
        assertEquals("0:00", timer.model(false, true)?.text)
    }

    @Test fun rollbackFailsClosedAndNavigationDoesNotReset() {
        val now = longArrayOf(100L)
        val timer = InstagramSessionTimer { now[0] }
        timer.observeVerifiedInstagram(true, true, true)
        now[0] = 2_100L
        timer.observeVerifiedInstagram(true, true, true)
        assertEquals("0:02", timer.model(true, false)?.text)
        now[0] = 99L
        assertNull(timer.model(false, false))
        assertFalse(timer.running)
    }

    @Test fun displayCadenceUsesNextBoundaryAndDoesNotDuplicate() {
        val now = longArrayOf(5_250L)
        val timer = InstagramSessionTimer { now[0] }
        timer.observeVerifiedInstagram(true, true, true)
        assertEquals(1_000L, timer.delayToNextSecond())
        assertNotNull(timer.model(false, false))
        assertNull(timer.model(false, false))
        now[0] = 5_999L
        assertEquals(251L, timer.delayToNextSecond())
        now[0] = 6_250L
        assertEquals("0:01", timer.model(false, false)?.text)
    }
}
