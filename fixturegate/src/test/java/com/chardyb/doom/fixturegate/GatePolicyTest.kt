package com.chardyb.doom.fixturegate

import org.junit.Assert.*
import org.junit.Test
import com.chardyb.doom.fixturegate.GatePolicy.Surface

class GatePolicyTest {
    @Test fun exactDeadlineAndSameSessionCredit() {
        val policy = GatePolicy()
        val ticket = policy.observe(Surface.FEED, 100)!!
        assertEquals(5_100L, ticket.deadlineMs)
        assertEquals(ticket, policy.observe(Surface.FEED, 200))
        assertFalse(policy.complete(ticket, 5_099))
        assertTrue(policy.complete(ticket, 5_100))
        assertTrue(policy.granted)
        assertNull(policy.observe(Surface.FEED, 9_000))
        assertFalse(policy.complete(ticket, 9_000))
        policy.observe(Surface.OUTSIDE, 9_001)
        assertFalse(policy.granted)
        assertNotNull(policy.observe(Surface.FEED, 9_002))
    }

    @Test fun everyNonFeedSurfaceAbortsWithoutCredit() {
        for (surface in listOf(Surface.DM, Surface.UNKNOWN, Surface.OUTSIDE)) {
            val policy = GatePolicy()
            val old = policy.observe(Surface.FEED, 0)!!
            assertNull(policy.observe(surface, 4_999))
            assertFalse(policy.complete(old, 5_000))
            val next = policy.observe(Surface.FEED, 5_001)!!
            assertNotEquals(old.generation, next.generation)
            assertFalse(policy.complete(old, 20_000))
            assertFalse(policy.complete(next, 10_000))
            assertTrue(policy.complete(next, 10_001))
        }
    }

    @Test fun staleCompletionCannotCompleteReplacementAtItsDeadline() {
        val policy = GatePolicy()
        val old = policy.observe(Surface.FEED, 0)!!
        policy.cancel() // service disconnect, permission loss, screen off or destroy
        val replacement = policy.observe(Surface.FEED, 1)!!
        assertFalse(policy.complete(old, 5_001))
        assertEquals(replacement, policy.pending)
        assertFalse(policy.granted)
        assertTrue(policy.complete(replacement, 5_001))
    }

    @Test fun escapeDoesNotRedrawBeforeNavigationAndDoesNotGrantCredit() {
        val policy = GatePolicy()
        val old = policy.observe(Surface.FEED, 0)!!
        policy.beginNavigation()
        assertNull(policy.observe(Surface.FEED, 1))
        assertFalse(policy.complete(old, 10_000))
        policy.observe(Surface.DM, 10_001)
        assertNotNull(policy.observe(Surface.FEED, 10_002))
        assertFalse(policy.granted)
    }

    @Test fun cancellationAlsoClearsNavigationSuppression() {
        val policy = GatePolicy()
        policy.observe(Surface.FEED, 0)
        policy.beginNavigation()
        policy.cancel()
        assertNotNull(policy.observe(Surface.FEED, 1))
    }

    @Test fun repeatedEventsCannotExtendDeadlineOrShortenNextGate() {
        val policy = GatePolicy()
        val ticket = policy.observe(Surface.FEED, 0)!!
        for (now in 1L..4_999L) assertEquals(ticket, policy.observe(Surface.FEED, now))
        policy.cancel()
        policy.cancel()
        val next = policy.observe(Surface.FEED, 5_000)!!
        assertEquals(10_000L, next.deadlineMs)
        assertFalse(policy.complete(ticket, 10_000))
        assertTrue(policy.complete(next, 10_000))
    }
}
