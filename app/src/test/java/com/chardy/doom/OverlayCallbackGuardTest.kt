package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class OverlayCallbackGuardTest {
    @Test fun oldInstanceCannotUseRepeatedTicketGeneration() {
        val guard = OverlayCallbackGuard()
        val ticket = GateTicket(7)
        val old = guard.open(ticket)
        val fresh = guard.open(ticket)
        assertFalse(guard.acceptsVisible(old))
        assertTrue(guard.acceptsVisible(fresh))
    }

    @Test fun closingStopsVisibleButKeepsRemovalAndDetachIsFinal() {
        val guard = OverlayCallbackGuard()
        val token = guard.open(GateTicket(1))
        assertTrue(guard.beginClosing(token))
        assertFalse(guard.acceptsVisible(token))
        assertTrue(guard.acceptsRemoval(token))
        guard.detached(token)
        assertFalse(guard.acceptsVisible(token))
        assertFalse(guard.acceptsRemoval(token))
        assertTrue(guard.acceptsDetached(token))
        guard.consumeDetached(token)
        assertFalse(guard.acceptsDetached(token))
        guard.detached(token)
    }

    @Test fun staleDetachCannotInvalidateNewInstance() {
        val guard = OverlayCallbackGuard()
        val old = guard.open(GateTicket(1))
        val fresh = guard.open(GateTicket(1))
        guard.detached(old)
        assertTrue(guard.acceptsVisible(fresh))
    }

    @Test fun newEpochAndSafetyInvalidationRevokeDetachedContinuation() {
        val guard = OverlayCallbackGuard()
        val old = guard.open(GateTicket(1))
        guard.beginClosing(old)
        guard.detached(old)
        assertTrue(guard.acceptsDetached(old))
        val fresh = guard.open(GateTicket(1))
        assertFalse(guard.acceptsDetached(old))
        guard.detached(old)
        guard.consumeDetached(old)
        assertTrue(guard.acceptsVisible(fresh))
        guard.beginClosing(fresh)
        guard.detached(fresh)
        guard.invalidateVisible()
        assertFalse(guard.acceptsDetached(fresh))
    }
}
