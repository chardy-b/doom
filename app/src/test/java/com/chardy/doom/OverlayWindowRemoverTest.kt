package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class OverlayWindowRemoverTest {
    private class Port : OverlayWindowRemovalPort {
        var state = OverlayAttachment.ATTACHED
        val calls = mutableListOf<String>()
        var removes = 0
        var remove: (Int) -> Unit = { }
        var add: () -> Unit = { }
        override fun attachment(): OverlayAttachment { calls += "check"; return state }
        override fun removeImmediate() { calls += "remove"; remove(++removes) }
        override fun reAddForRemoval() { calls += "add"; add() }
    }
    @Test fun alreadyDetachedNeverReadds() {
        val p = Port().apply { state = OverlayAttachment.DETACHED }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(listOf("check"), p.calls)
    }
    @Test fun removeThrowsButDetachesIsSuccess() {
        val p = Port().apply { remove = { state = OverlayAttachment.DETACHED; throw IllegalStateException() } }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(listOf("check", "remove", "check"), p.calls)
    }
    @Test fun removeReturnsButStillAttachedRunsFallback() {
        val p = Port().apply { remove = { if (it == 2) state = OverlayAttachment.DETACHED } }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(listOf("check", "remove", "check", "add", "check", "remove", "check"), p.calls)
    }
    @Test fun removeFailureRunsAddThenSecondRemove() {
        val p = Port().apply { remove = { if (it == 1) throw IllegalStateException() else state = OverlayAttachment.DETACHED } }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(listOf("check", "remove", "check", "add", "check", "remove", "check"), p.calls)
    }
    @Test fun alreadyAddedExceptionStillRunsSecondRemove() {
        val p = Port().apply {
            add = { throw IllegalStateException("already added") }
            remove = { if (it == 2) state = OverlayAttachment.DETACHED }
        }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(2, p.removes)
        assertEquals(1, p.calls.count { it == "add" })
    }
    @Test fun addAttachesThenThrowsStillGetsRemoved() {
        val p = Port().apply {
            add = { state = OverlayAttachment.ATTACHED; throw IllegalStateException() }
            remove = { if (it == 2) state = OverlayAttachment.DETACHED }
        }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(2, p.removes)
    }
    @Test fun fallbackCannotReenableInteraction() {
        GateWindowRole.entries.forEach { role ->
            val descriptor = GateWindowDescriptor(role, GateWindowRect(1, 2, 100, 52)).recovery()
            assertFalse(descriptor.touchable)
            assertEquals(0f, descriptor.alpha)
            assertEquals(role, descriptor.role)
        }
    }
    @Test fun bothRemovesFailReturnsRetry() {
        val p = Port().apply { remove = { throw IllegalStateException() }; add = { throw IllegalStateException() } }
        assertEquals(OverlayRemovalResult.RETRY_REQUIRED, OverlayWindowRemover.attempt(p, true))
        assertEquals(2, p.removes)
        assertEquals(1, p.calls.count { it == "add" })
    }
    @Test fun unknownAttachmentNeverConfirms() {
        val p = Port().apply { state = OverlayAttachment.UNKNOWN }
        assertEquals(OverlayRemovalResult.RETRY_REQUIRED, OverlayWindowRemover.attempt(p, true))
        val throwing = object : OverlayWindowRemovalPort {
            override fun attachment(): OverlayAttachment = throw IllegalStateException()
            override fun removeImmediate() = Unit
            override fun reAddForRemoval() = Unit
        }
        assertEquals(OverlayRemovalResult.RETRY_REQUIRED, OverlayWindowRemover.attempt(throwing, true))
    }
    @Test fun unboundOwnerNeverReadds() {
        val p = Port()
        assertEquals(OverlayRemovalResult.RETRY_REQUIRED, OverlayWindowRemover.attempt(p, false))
        assertEquals(listOf("check", "remove", "check", "check"), p.calls)
    }
    @Test fun fallbackDetachDoesNotRemoveOrReaddAgain() {
        val p = Port().apply { add = { state = OverlayAttachment.DETACHED; throw IllegalStateException() } }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(p, true))
        assertEquals(1, p.removes)
    }
    @Test fun partialInstallFailureIsStillOwned() {
        val attempted = Port().apply {
            // The same retained port models an add that attached before throwing.
            state = OverlayAttachment.ATTACHED
            remove = { state = OverlayAttachment.DETACHED }
        }
        assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(attempted, true))
        assertEquals(listOf("check", "remove", "check"), attempted.calls)
    }
}
