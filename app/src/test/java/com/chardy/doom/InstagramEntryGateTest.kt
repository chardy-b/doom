package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramEntryGateTest {
    @Test fun defaultOffFailsOpen() {
        val gate = InstagramEntryGate()
        val ticket = gate.foreground()

        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.observe(EntryGateSurface.FEED, 0L, ticket))
    }

    @Test fun confirmedScrollNeedsFiveVisibleSecondsAfterOverlayIsShown() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()

        assertTrue(gate.observe(EntryGateSurface.REELS, 100L, ticket))
        assertFalse(gate.complete(10_000L, ticket))
        assertTrue(gate.overlayShown(10_000L, ticket))
        assertEquals(5_000L, gate.remainingMs(10_000L, ticket))
        assertFalse(gate.complete(14_999L, ticket))
        assertEquals(1L, gate.remainingMs(14_999L, ticket))
        assertTrue(gate.complete(15_000L, ticket))
        assertEquals(EntryGateState.GRANTED, gate.state)
        assertFalse(gate.complete(20_000L, ticket))
    }

    @Test fun messagingFirstBypassesWholeSession() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()

        assertFalse(gate.observe(EntryGateSurface.MESSAGING, 0L, ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.observe(EntryGateSurface.FEED, 1L, ticket))
    }

    @Test fun messagingOrUnknownDuringGateRemovesAuthorityToRemainVisible() {
        EntryGateSurface.entries
            .filter { it == EntryGateSurface.MESSAGING || it == EntryGateSurface.UNKNOWN }
            .forEach { unsafe ->
                val gate = InstagramEntryGate(enabled = { true })
                val ticket = gate.foreground()
                assertTrue(gate.observe(EntryGateSurface.STORIES, 0L, ticket))
                assertTrue(gate.overlayShown(0L, ticket))

                assertFalse(gate.observe(unsafe, 1L, ticket))
                assertEquals(EntryGateState.GATING, gate.state)
                assertTrue(gate.bypass(ticket))
                assertEquals(EntryGateState.BYPASSED, gate.state)
                assertFalse(gate.complete(5_000L, ticket))
            }
    }

    @Test fun repeatedScrollingSamplesKeepSameVisibleDeadline() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()
        assertTrue(gate.observe(EntryGateSurface.FEED, 0L, ticket))
        assertTrue(gate.overlayShown(100L, ticket))

        assertTrue(gate.observe(EntryGateSurface.REELS, 4_000L, ticket))
        assertTrue(gate.overlayShown(4_000L, ticket))
        assertEquals(1_000L, gate.remainingMs(4_100L, ticket))
        assertTrue(gate.complete(5_100L, ticket))
    }

    @Test fun unknownFirstFailsOpen() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()

        assertFalse(gate.observe(EntryGateSurface.UNKNOWN, 0L, ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
    }

    @Test fun leavingResetsAndStaleCompletionCannotGrant() {
        val gate = InstagramEntryGate(enabled = { true })
        val old = gate.foreground()
        gate.observe(EntryGateSurface.FEED, 0L, old)
        gate.overlayShown(0L, old)
        gate.leaveInstagram()
        val fresh = gate.foreground()

        assertFalse(gate.complete(5_000L, old))
        assertEquals(EntryGateState.AWAITING, gate.state)
        assertTrue(gate.observe(EntryGateSurface.FEED, 10L, fresh))
        assertTrue(gate.overlayShown(10L, fresh))
        assertFalse(gate.complete(5_009L, fresh))
    }

    @Test fun optOutOrCancellationInvalidatesTicketIdempotently() {
        var optedIn = true
        val gate = InstagramEntryGate(enabled = { optedIn })
        val ticket = gate.foreground()
        gate.observe(EntryGateSurface.STORIES, 0L, ticket)
        gate.overlayShown(0L, ticket)

        optedIn = false
        assertFalse(gate.observe(EntryGateSurface.STORIES, 1L, ticket))
        gate.cancel()
        gate.cancel()

        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.complete(5_000L, ticket))
    }

    @Test fun backwardsTimeAndInvalidDurationsFailSafely() {
        assertThrows(IllegalArgumentException::class.java) { InstagramEntryGate(0L) }
        assertThrows(IllegalArgumentException::class.java) { InstagramEntryGate(120_001L) }
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()
        assertFalse(gate.observe(EntryGateSurface.FEED, -1L, ticket))
        assertEquals(EntryGateState.AWAITING, gate.state)
        gate.cancel()
        assertEquals(EntryGateState.BYPASSED, gate.state)
    }

    @Test fun dismissRequiresCurrentVisibleGate() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()
        assertFalse(gate.dismissForMessages(ticket))
        assertTrue(gate.observe(EntryGateSurface.FEED, 0L, ticket))
        assertTrue(gate.dismissForMessages(ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.dismissForMessages(ticket))
    }

    @Test fun explicitLeaveBypassCannotRegateWithoutForeignSessionReset() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.foreground()
        assertTrue(gate.observe(EntryGateSurface.FEED, 0L, ticket))

        assertTrue(gate.bypass(ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.observe(EntryGateSurface.FEED, 1L, ticket))
    }
}
