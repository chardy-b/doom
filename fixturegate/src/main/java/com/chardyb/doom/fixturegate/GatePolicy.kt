package com.chardyb.doom.fixturegate

/** Main-thread policy. Time is monotonic and injected; no Android or test-only state. */
class GatePolicy {
    enum class Surface { FEED, DM, UNKNOWN, OUTSIDE }
    data class Ticket(val generation: Long, val deadlineMs: Long)
    var pending: Ticket? = null
        private set
    var granted = false
        private set
    private var generation = 0L
    private var surface = Surface.OUTSIDE
    private var navigating = false

    fun observe(next: Surface, nowMs: Long): Ticket? {
        if (next != Surface.FEED) {
            cancel()
            surface = next
            navigating = false
            return null
        }
        surface = next
        if (!granted && !navigating && pending == null) {
            pending = Ticket(++generation, nowMs + WAIT_MS)
        }
        return pending
    }

    fun complete(ticket: Ticket, nowMs: Long): Boolean {
        if (pending != ticket || ticket.generation != generation ||
            surface != Surface.FEED || navigating || nowMs < ticket.deadlineMs) return false
        pending = null
        granted = true
        return true
    }

    fun cancel() {
        generation++
        pending = null
        granted = false
        surface = Surface.OUTSIDE
        navigating = false
    }

    /** Prevent an overlay redraw between removal and the resulting navigation event. */
    fun beginNavigation() { cancel(); navigating = true }

    companion object { const val WAIT_MS = 5_000L }
}
