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
        guard.detached(token)
    }

    @Test fun staleDetachCannotInvalidateNewInstance() {
        val guard = OverlayCallbackGuard()
        val old = guard.open(GateTicket(1))
        val fresh = guard.open(GateTicket(1))
        guard.detached(old)
        assertTrue(guard.acceptsVisible(fresh))
    }
}
