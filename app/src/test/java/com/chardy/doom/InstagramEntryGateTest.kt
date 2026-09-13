package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramEntryGateTest {
    @Test fun defaultOffFailsOpen() {
        val gate = InstagramEntryGate()
        val ticket = gate.beginInstagramSession()

        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.observeInstagram(0L, ticket))
    }

    @Test fun instagramDetectionNeedsFiveVisibleSecondsAfterOverlayIsShown() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()

        assertTrue(gate.observeInstagram(100L, ticket))
        assertFalse(gate.complete(10_000L, ticket))
        assertTrue(gate.overlayShown(10_000L, ticket))
        assertEquals(5_000L, gate.remainingMs(10_000L, ticket))
        assertFalse(gate.complete(14_999L, ticket))
        assertEquals(1L, gate.remainingMs(14_999L, ticket))
        assertTrue(gate.complete(15_000L, ticket))
        assertEquals(EntryGateState.GRANTED, gate.state)
        assertFalse(gate.complete(20_000L, ticket))
    }

    @Test fun messagingFirstStartsDiagnosticGate() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()

        assertTrue(gate.observeInstagram(0L, ticket))
        assertEquals(EntryGateState.GATING, gate.state)
        assertTrue(gate.observeInstagram(1L, ticket))
    }

    @Test fun repeatedInstagramSamplesKeepDiagnosticGateVisible() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()
        assertTrue(gate.observeInstagram(0L, ticket))
        assertTrue(gate.overlayShown(0L, ticket))

        assertTrue(gate.observeInstagram(1L, ticket))
        assertEquals(EntryGateState.GATING, gate.state)
        assertTrue(gate.bypass(ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.complete(5_000L, ticket))
    }

    @Test fun repeatedInstagramSamplesKeepSameVisibleDeadline() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()
        assertTrue(gate.observeInstagram(0L, ticket))
        assertTrue(gate.overlayShown(100L, ticket))

        assertTrue(gate.observeInstagram(4_000L, ticket))
        assertTrue(gate.overlayShown(4_000L, ticket))
        assertEquals(1_000L, gate.remainingMs(4_100L, ticket))
        assertTrue(gate.complete(5_100L, ticket))
    }

    @Test fun unknownFirstStartsDiagnosticGate() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()

        assertTrue(gate.observeInstagram(0L, ticket))
        assertEquals(EntryGateState.GATING, gate.state)
    }

    @Test fun leavingResetsAndStaleCompletionCannotGrant() {
        val gate = InstagramEntryGate(enabled = { true })
        val old = gate.beginInstagramSession()
        gate.observeInstagram(0L, old)
        gate.overlayShown(0L, old)
        gate.leaveInstagram()
        val fresh = gate.beginInstagramSession()

        assertFalse(gate.complete(5_000L, old))
        assertEquals(EntryGateState.AWAITING, gate.state)
        assertTrue(gate.observeInstagram(10L, fresh))
        assertTrue(gate.overlayShown(10L, fresh))
        assertFalse(gate.complete(5_009L, fresh))
    }

    @Test fun optOutOrCancellationInvalidatesTicketIdempotently() {
        var optedIn = true
        val gate = InstagramEntryGate(enabled = { optedIn })
        val ticket = gate.beginInstagramSession()
        gate.observeInstagram(0L, ticket)
        gate.overlayShown(0L, ticket)

        optedIn = false
        assertFalse(gate.observeInstagram(1L, ticket))
        gate.cancel()
        gate.cancel()

        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.complete(5_000L, ticket))
    }

    @Test fun backwardsTimeAndInvalidDurationsFailSafely() {
        assertThrows(IllegalArgumentException::class.java) { InstagramEntryGate(0L) }
        assertThrows(IllegalArgumentException::class.java) { InstagramEntryGate(120_001L) }
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()
        assertFalse(gate.observeInstagram(-1L, ticket))
        assertEquals(EntryGateState.AWAITING, gate.state)
        gate.cancel()
        assertEquals(EntryGateState.BYPASSED, gate.state)
    }

    @Test fun messagesSuccessRequiresCurrentVisibleGateAndSuccessfulResult() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()
        assertFalse(gate.beginMessagesRoute(ticket))
        assertTrue(gate.observeInstagram(0L, ticket))
        assertFalse(gate.beginMessagesRoute(ticket))
        assertTrue(gate.overlayShown(0L, ticket))
        assertTrue(gate.beginMessagesRoute(ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.cooldownActive())
        assertFalse(gate.finishMessagesRoute(1L, ticket, MessagesRouteResult.FAILED))
        assertFalse(gate.finishMessagesRoute(2L, ticket, MessagesRouteResult.CLICKED))
        assertFalse(gate.cooldownActive())
    }

    @Test fun explicitLeaveBypassCannotRegateWithoutForeignSessionReset() {
        val gate = InstagramEntryGate(enabled = { true })
        val ticket = gate.beginInstagramSession()
        assertTrue(gate.observeInstagram(0L, ticket))

        assertTrue(gate.bypass(ticket))
        assertEquals(EntryGateState.BYPASSED, gate.state)
        assertFalse(gate.observeInstagram(1L, ticket))
    }

    @Test fun cooldownStartsOnlyAfterCompletionAndHasExactTerminalBoundary() {
        var nowMs = 10_000L
        val gate = InstagramEntryGate(enabled = { true }, monotonicNowMs = { nowMs })
        val ticket = gate.beginInstagramSessionIfEligible()!!

        assertFalse(gate.cooldownActive())
        assertTrue(gate.observeInstagram(nowMs, ticket))
        assertTrue(gate.overlayShown(nowMs, ticket))
        assertFalse(gate.cooldownActive())
        nowMs = 15_000L
        assertTrue(gate.complete(nowMs, ticket))

        gate.leaveInstagram()
        nowMs += INSTAGRAM_ENTRY_COOLDOWN_MS - 1L
        assertTrue(gate.cooldownActive())
        assertNull(gate.beginInstagramSessionIfEligible())

        nowMs += 1L
        assertFalse(gate.cooldownActive())
        assertNotNull(gate.beginInstagramSessionIfEligible())
    }

    @Test fun sessionResetsAndExplicitActionsCannotBypassCooldownOrAllocateTicket() {
        var nowMs = 0L
        var consented = true
        val gate = InstagramEntryGate(enabled = { consented }, monotonicNowMs = { nowMs })
        val ticket = gate.beginInstagramSessionIfEligible()!!
        gate.observeInstagram(nowMs, ticket)
        gate.overlayShown(nowMs, ticket)
        nowMs = 5_000L
        assertTrue(gate.complete(nowMs, ticket))

        val generationAfterSkip = gate.generation
        gate.cancel()
        gate.leaveInstagram()
        consented = false
        gate.cancel()
        consented = true
        nowMs = 5_000L + INSTAGRAM_ENTRY_COOLDOWN_MS - 1L

        assertTrue(gate.cooldownActive())
        assertNull(gate.beginInstagramSessionIfEligible())
        assertEquals(generationAfterSkip + 3L, gate.generation)
        assertFalse(gate.observeInstagram(nowMs, ticket))
    }

    @Test fun rejectedAndStaleTerminalAttemptsNeverStartCooldown() {
        var nowMs = 1L
        val gate = InstagramEntryGate(enabled = { true }, monotonicNowMs = { nowMs })
        val stale = gate.beginInstagramSessionIfEligible()!!
        gate.observeInstagram(nowMs, stale)
        gate.overlayShown(nowMs, stale)
        gate.leaveInstagram()

        assertFalse(gate.complete(5_001L, stale))
        assertFalse(gate.cooldownActive())

        val current = gate.beginInstagramSessionIfEligible()!!
        assertFalse(gate.complete(5_001L, current))
        assertFalse(gate.cooldownActive())
    }

    @Test fun messagesSuccessEnumsArmAtResultTimeAndPreserveExactBoundary() {
        for (result in listOf(MessagesRouteResult.CLICKED, MessagesRouteResult.ALREADY_SELECTED)) {
            var nowMs = 10_000L
            val gate = InstagramEntryGate(enabled = { true }, monotonicNowMs = { nowMs })
            val ticket = gate.beginInstagramSessionIfEligible()!!
            assertTrue(gate.observeInstagram(nowMs, ticket))
            assertTrue(gate.overlayShown(nowMs, ticket))
            assertTrue(gate.beginMessagesRoute(ticket))
            assertFalse(gate.cooldownActive())
            nowMs = 12_750L
            assertTrue(gate.finishMessagesRoute(nowMs, ticket, result))
            gate.leaveInstagram()
            nowMs = 72_749L
            assertTrue(gate.cooldownActive())
            assertNull(gate.beginInstagramSessionIfEligible())
            nowMs = 72_750L
            assertFalse(gate.cooldownActive())
            assertNotNull(gate.beginInstagramSessionIfEligible())
        }
    }

    @Test fun displayBackBypassAndWaitingNeverArmCooldown() {
        var nowMs = 0L
        val gate = InstagramEntryGate(enabled = { true }, monotonicNowMs = { nowMs })
        val ticket = gate.beginInstagramSessionIfEligible()!!
        assertTrue(gate.observeInstagram(nowMs, ticket))
        assertTrue(gate.overlayShown(nowMs, ticket))
        assertTrue(gate.overlayShown(4_000L, ticket))
        nowMs = 120_000L
        assertFalse(gate.cooldownActive())
        assertTrue(gate.bypass(ticket))
        gate.leaveInstagram()
        assertNotNull(gate.beginInstagramSessionIfEligible())
    }
}
