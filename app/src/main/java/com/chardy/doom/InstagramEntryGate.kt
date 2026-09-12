package com.chardy.doom

/** Pure, diagnostic-only policy for one Instagram foreground session. */
enum class EntryGateState { OUTSIDE, AWAITING, GATING, GRANTED, BYPASSED }
enum class EntryGateSurface { FEED, REELS, STORIES, MESSAGING, UNKNOWN }
data class GateTicket(val generation: Long)

internal const val INSTAGRAM_ENTRY_COOLDOWN_MS = 60_000L

/** Process-local monotonic cooldown for successfully admitted production Instagram gates. */
internal class InstagramGateCooldown(
    private val monotonicNowMs: () -> Long,
    private val durationMs: Long = INSTAGRAM_ENTRY_COOLDOWN_MS
) {
    private var admittedAtMs: Long? = null
    private var lastAdmittedGeneration: Long? = null

    init {
        require(durationMs > 0L)
    }

    fun isSuppressed(): Boolean = isSuppressed(monotonicNowMs())

    fun isSuppressed(nowMs: Long): Boolean {
        val admittedAt = admittedAtMs ?: return false
        if (nowMs < 0L || nowMs < admittedAt) return true
        return nowMs - admittedAt < durationMs
    }

    fun admit(ticket: GateTicket): Boolean = admit(ticket, monotonicNowMs())

    /** Records only a current, new gate admission; rejected or stale tickets cannot arm it. */
    fun admit(ticket: GateTicket, nowMs: Long): Boolean {
        if (nowMs < 0L || isSuppressed(nowMs)) return false
        if (lastAdmittedGeneration?.let { ticket.generation <= it } == true) return false
        admittedAtMs = nowMs
        lastAdmittedGeneration = ticket.generation
        return true
    }
}

class InstagramEntryGate(
    private val durationMs: Long = 5_000L,
    private val enabled: () -> Boolean = { false },
    monotonicNowMs: () -> Long = { 0L }
) {
    var state: EntryGateState = EntryGateState.OUTSIDE
        private set
    var generation: Long = 0
        private set
    private var visibleStartedAtMs: Long? = null
    private val cooldown = InstagramGateCooldown(monotonicNowMs)

    init {
        require(durationMs in 1L..120_000L)
    }

    /** Starts an Instagram session; surface classification happens separately in observe. */
    fun beginInstagramSession(): GateTicket {
        generation++
        visibleStartedAtMs = null
        state = if (enabled()) EntryGateState.AWAITING else EntryGateState.BYPASSED
        return GateTicket(generation)
    }

    /** Admission boundary used by the service before allocating a new production ticket. */
    fun beginInstagramSessionIfEligible(): GateTicket? {
        if (cooldown.isSuppressed()) return null
        return beginInstagramSession()
    }

    fun cooldownActive(): Boolean = cooldown.isSuppressed()

    /** Returns true only while an Instagram-triggered overlay should be or remain visible. */
    fun observeInstagram(nowMs: Long, ticket: GateTicket): Boolean {
        if (!enabled() || nowMs < 0L || !valid(ticket)) return false
        state = when (state) {
            EntryGateState.AWAITING, EntryGateState.GATING -> EntryGateState.GATING
            else -> state
        }
        return state == EntryGateState.GATING
    }

    /** Starts the five visible seconds only after WindowManager accepted the overlay. */
    fun overlayShown(nowMs: Long, ticket: GateTicket): Boolean {
        if (!enabled() || nowMs < 0L || !valid(ticket) || state != EntryGateState.GATING) return false
        if (visibleStartedAtMs == null) visibleStartedAtMs = nowMs
        return true
    }

    /** Arms the one-minute cooldown only after addView and overlayShown succeeded. */
    fun admitForDisplay(ticket: GateTicket): Boolean {
        if (!enabled() || !valid(ticket) || state != EntryGateState.GATING ||
            visibleStartedAtMs == null
        ) return false
        return cooldown.admit(ticket)
    }

    fun remainingMs(nowMs: Long, ticket: GateTicket): Long {
        val started = visibleStartedAtMs
        if (nowMs < 0L || !valid(ticket) || state != EntryGateState.GATING || started == null) return 0L
        if (nowMs < started) return durationMs
        return (durationMs - (nowMs - started)).coerceAtLeast(0L)
    }

    fun complete(nowMs: Long, ticket: GateTicket): Boolean {
        val started = visibleStartedAtMs
        if (!enabled() || nowMs < 0L || !valid(ticket) || state != EntryGateState.GATING ||
            started == null || nowMs < started || nowMs - started < durationMs
        ) return false
        visibleStartedAtMs = null
        state = EntryGateState.GRANTED
        return true
    }

    fun skipToMessages(ticket: GateTicket): Boolean {
        return bypass(ticket)
    }

    fun bypass(ticket: GateTicket): Boolean {
        if (!valid(ticket) || state != EntryGateState.GATING) return false
        visibleStartedAtMs = null
        state = EntryGateState.BYPASSED
        return true
    }

    fun leaveInstagram() {
        generation++
        visibleStartedAtMs = null
        state = EntryGateState.OUTSIDE
    }

    fun cancel() {
        generation++
        visibleStartedAtMs = null
        if (state != EntryGateState.OUTSIDE) state = EntryGateState.BYPASSED
    }

    private fun valid(ticket: GateTicket) = ticket.generation == generation
}
