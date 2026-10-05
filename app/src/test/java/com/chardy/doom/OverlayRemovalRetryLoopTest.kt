package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class OverlayRemovalRetryLoopTest {
    private class Scheduler : OverlayRemovalScheduler {
        var now = 0L
        val queue = linkedMapOf<Runnable, Long>()
        val delays = mutableListOf<Long>()
        override fun post(task: Runnable, delayMs: Long) { queue[task] = now + delayMs; delays += delayMs }
        override fun cancel(task: Runnable) { queue.remove(task) }
        fun next() {
            val task = queue.entries.minByOrNull { it.value } ?: return
            now = task.value
            queue.remove(task.key)
            task.key.run()
        }
    }
    private class Port : OverlayWindowRemovalPort {
        var state = OverlayAttachment.UNKNOWN
        var removes = 0
        var adds = 0
        override fun attachment() = state
        override fun removeImmediate() { removes++ }
        override fun reAddForRemoval() { adds++ }
    }
    @Test fun oneQueuedRetryPerOwner() {
        val scheduler = Scheduler(); val loop = OverlayRemovalRetryLoop(scheduler)
        var calls = 0
        repeat(100) { loop.schedule(50, { true }) { calls++ } }
        assertEquals(1, scheduler.queue.size)
        scheduler.next()
        assertEquals(1, calls); assertFalse(loop.isPending)
    }
    @Test fun stormDoesNotPostponeRetryOrAccelerateSlowLoop() {
        val scheduler = Scheduler(); val loop = OverlayRemovalRetryLoop(scheduler)
        val policy = OverlayRemovalPolicy(2)
        var attempts = 0
        fun fail() {
            attempts++
            val delay = if (policy.failedAttempt() == OverlayRemovalDecision.RETRY_FAST) 50L else 1_000L
            loop.schedule(delay, { true }) { fail() }
        }
        fail()
        scheduler.next()
        assertEquals(2, attempts)
        val due = scheduler.queue.values.single()
        repeat(200) {
            policy.requestSafetyCleanup(OverlayRemovalAction.BYPASS)
            loop.schedule(50, { true }) { fail() }
        }
        assertEquals(due, scheduler.queue.values.single())
        assertEquals(2, attempts)
        scheduler.next()
        assertEquals(listOf(50L, 1_000L, 1_000L), scheduler.delays)
    }
    @Test fun staleRetryCannotCancelReplacementTask() {
        val scheduler = Scheduler(); val loop = OverlayRemovalRetryLoop(scheduler)
        loop.schedule(50, { true }) { fail("stale task") }
        val stale = scheduler.queue.keys.single()
        loop.cancel()
        var calls = 0
        loop.schedule(1_000, { true }) { calls++ }
        val fresh = scheduler.queue.keys.single()
        stale.run()
        assertSame(fresh, scheduler.queue.keys.single())
        assertTrue(loop.isPending)
        scheduler.next(); assertEquals(1, calls)
    }
    @Test fun lateDetachStopsQueueExactlyOnce() {
        val scheduler = Scheduler(); val barrier = OverlayCleanupBarrier(); val port = Port()
        var released = 0
        val owner = RetiringOverlayRemovalOwner(listOf(port), scheduler, barrier) { released++ }
        owner.takeOver(emptyList())
        scheduler.next(); assertTrue(barrier.retiring)
        port.state = OverlayAttachment.DETACHED
        val callback = scheduler.queue.keys.single()
        scheduler.next(); callback.run()
        assertEquals(1, released); assertFalse(barrier.retiring); assertTrue(scheduler.queue.isEmpty())
    }
    @Test fun safetyOverrideDuringSlowRetryIsIrreversible() {
        val scheduler = Scheduler(); val loop = OverlayRemovalRetryLoop(scheduler); val policy = OverlayRemovalPolicy(1)
        policy.request(OverlayRemovalAction.NAVIGATE_MESSAGES)
        assertEquals(OverlayRemovalDecision.RETRY_SLOW, policy.failedAttempt())
        var result: OverlayRemovalAction? = null
        loop.schedule(1_000, { true }) { result = policy.confirmedDetached() }
        policy.requestSafetyCleanup(OverlayRemovalAction.RESET_OUTSIDE)
        policy.request(OverlayRemovalAction.HOME)
        scheduler.next()
        assertEquals(OverlayRemovalAction.RESET_OUTSIDE, result)
    }
    @Test fun retirementTransfersRatherThanDuplicatesRetry() {
        val scheduler = Scheduler(); val old = OverlayRemovalRetryLoop(scheduler)
        old.schedule(50, { true }) { fail("old owner ran") }
        val stale = scheduler.queue.keys.single(); val port = Port(); val barrier = OverlayCleanupBarrier()
        RetiringOverlayRemovalOwner(listOf(port), scheduler, barrier) {}.takeOver(listOf(old))
        stale.run(); assertEquals(1, scheduler.queue.size); assertFalse(old.isPending)
        scheduler.next(); assertEquals(1, port.removes); assertEquals(0, port.adds)
        assertEquals(1, scheduler.queue.size)
    }
    @Test fun reconnectCannotAdmitOverRetiredWindows() {
        val barrier = OverlayCleanupBarrier(); val scheduler = Scheduler(); val port = Port()
        RetiringOverlayRemovalOwner(listOf(port), scheduler, barrier) {}.takeOver(emptyList())
        assertFalse(barrier.canAdmit())
        scheduler.next(); assertFalse(barrier.canAdmit())
        port.state = OverlayAttachment.DETACHED
        scheduler.next(); assertTrue(barrier.canAdmit())
    }
    @Test fun noWindowStopCanDisableButUnknownAttachmentCannot() {
        val barrier = OverlayCleanupBarrier()
        assertTrue(barrier.canDisable(false, false))
        assertFalse(barrier.canDisable(true, false)); assertFalse(barrier.canDisable(false, true))
        val scheduler = Scheduler(); val port = Port()
        RetiringOverlayRemovalOwner(listOf(port), scheduler, barrier) {}.takeOver(emptyList())
        scheduler.next(); assertFalse(barrier.canDisable(false, false))
        port.state = OverlayAttachment.DETACHED
        scheduler.next(); assertTrue(barrier.canDisable(false, false))
    }
}
