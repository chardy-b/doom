package com.chardy.doom

internal interface OverlayRemovalScheduler {
    fun post(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/** Main-thread single-task queue. A stale task cannot consume or cancel its replacement. */
internal class OverlayRemovalRetryLoop(private val scheduler: OverlayRemovalScheduler) {
    private var pending: Runnable? = null
    val isPending: Boolean get() = pending != null
    fun schedule(delayMs: Long, authorized: () -> Boolean, attempt: () -> Unit) {
        if (pending != null) return
        val task = object : Runnable {
            override fun run() {
                if (pending !== this || !authorized()) return
                pending = null
                attempt()
            }
        }
        pending = task
        scheduler.post(task, delayMs)
    }
    /** Caller proves current ownership before cancellation; only detach/transfer cancels cleanup. */
    fun cancel() {
        pending?.let(scheduler::cancel)
        pending = null
    }
}

/** Process-local admission/disable barrier, shared by current and retiring owners. */
internal class OverlayCleanupBarrier {
    var retiring: Boolean = false
        private set
    fun retire(): Boolean {
        if (retiring) return false
        retiring = true
        return true
    }
    fun released() { retiring = false }
    fun canAdmit() = !retiring
    fun canDisable(gateOwned: Boolean, timerOwned: Boolean) = !retiring && !gateOwned && !timerOwned
}

/** Removal-only transfer. It retains no action policy or service continuation. */
internal class RetiringOverlayRemovalOwner(
    private val ports: List<OverlayWindowRemovalPort>,
    scheduler: OverlayRemovalScheduler,
    private val barrier: OverlayCleanupBarrier,
    private val released: () -> Unit,
) {
    private val loop = OverlayRemovalRetryLoop(scheduler)
    private var active = false
    fun takeOver(previousLoops: List<OverlayRemovalRetryLoop>) {
        check(!active && barrier.retire())
        active = true
        previousLoops.forEach { it.cancel() }
        if (!reconcileDetached()) schedule()
    }
    private fun schedule() { loop.schedule(1_000L, { active }) { attempt() } }
    private fun attempt() {
        ports.forEach { OverlayWindowRemover.attempt(it, mayReAdd = false) }
        if (!reconcileDetached()) schedule()
    }
    fun reconcileDetached(): Boolean {
        if (!active) return true
        if (ports.any { OverlayWindowRemover.attachment(it) != OverlayAttachment.DETACHED }) return false
        active = false
        loop.cancel()
        barrier.released()
        released()
        return true
    }
}
