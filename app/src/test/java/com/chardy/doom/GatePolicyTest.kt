package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class GatePolicyTest {
    @Test fun feedUnlocksOnlyAfterFiveVisibleSeconds() {
        val gate = DemoGate()
        val token = gate.start(100)
        assertFalse(gate.tick(5_099, token))
        assertEquals(1L, gate.remaining(5_099))
        assertTrue(gate.tick(5_100, token))
        assertEquals(DemoGate.Screen.FEED, gate.screen)
    }

    @Test fun messagesAbortWithoutWaitingAndStaleCompletionCannotReturnToFeed() {
        val gate = DemoGate()
        val token = gate.start(0)
        gate.leave(DemoGate.Screen.MESSAGES)
        assertFalse(gate.tick(9_000, token))
        assertEquals(DemoGate.Screen.MESSAGES, gate.screen)
    }

    @Test fun backgroundAbortsAndRestartNeedsFullDuration() {
        val gate = DemoGate()
        val old = gate.start(0)
        gate.background()
        val fresh = gate.start(8_000)
        assertFalse(gate.tick(13_000, old))
        assertFalse(gate.tick(12_999, fresh))
        assertTrue(gate.tick(13_000, fresh))
    }

    @Test fun backwardClockDoesNotGrantTime() {
        val gate = DemoGate()
        val token = gate.start(100)
        assertEquals(5_000L, gate.remaining(0))
        assertFalse(gate.tick(0, token))
    }

    @Test fun leaveAndDuplicateCallbacksNeverRestartGate() {
        val gate = DemoGate()
        val token = gate.start(0)
        gate.leave()
        repeat(3) { assertFalse(gate.tick(6_000, token)) }
        assertEquals(DemoGate.Screen.HOME, gate.screen)
    }
}
