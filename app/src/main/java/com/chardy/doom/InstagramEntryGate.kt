package com.chardy.doom

/** Pure, diagnostic-only policy for one Instagram foreground session. */
enum class EntryGateState { OUTSIDE, AWAITING, GATING, GRANTED, BYPASSED }
enum class EntryGateSurface { FEED, REELS, STORIES, MESSAGING, UNKNOWN }
data class GateTicket(val generation: Long)
internal enum class MessagesRouteResult { FAILED, ALREADY_SELECTED, CLICKED }

internal const val INSTAGRAM_ENTRY_COOLDOWN_MS = 60_000L

/** Process-local monotonic cooldown for successfully admitted production Instagram gates. */
internal class InstagramGateCooldown(
    private val monotonicNowMs: () -> Long,
    private val defaultDurationMs: Long = INSTAGRAM_ENTRY_COOLDOWN_MS,
) {
    private var durationMs: Long = defaultDurationMs
    private var terminalAtMs: Long? = null
    private var lastTerminalGeneration: Long? = null


    init {
        require(defaultDurationMs > 0L)
    }

    fun isSuppressed(): Boolean = isSuppressed(monotonicNowMs())

    fun isSuppressed(nowMs: Long): Boolean {
        val terminalAt = terminalAtMs ?: return false
        if (nowMs < 0L || nowMs < terminalAt) return true
        return nowMs - terminalAt < durationMs
    }

    /** Records only a new terminal success; rejected or stale tickets cannot arm it. */
    fun recordTerminal(
        ticket: GateTicket,
        nowMs: Long,
        durationSnapshotMs: Long = defaultDurationMs,
    ): Boolean {
        if (nowMs < 0L || isSuppressed(nowMs)) return false
        if (lastTerminalGeneration?.let { ticket.generation <= it } == true) return false
        durationMs = durationSnapshotMs.takeIf { it > 0L } ?: defaultDurationMs
        terminalAtMs = nowMs
        lastTerminalGeneration = ticket.generation
        return true
    }
}

class InstagramEntryGate(
    private val durationMs: Long = 5_000L,
    private val enabled: () -> Boolean = { false },
    monotonicNowMs: () -> Long = { 0L },
    private val durationProvider: (() -> Long)? = null,
    private val cooldownDurationProvider: (() -> Long)? = null,
) {
    var state: EntryGateState = EntryGateState.OUTSIDE
        private set
    var generation: Long = 0
        private set
    private var visibleStartedAtMs: Long? = null
    private var activeDurationMs = durationMs
    private var activeCooldownDurationMs = INSTAGRAM_ENTRY_COOLDOWN_MS
    private var pendingMessagesAttempt: Pair<GateTicket, Long>? = null
    private val cooldown = InstagramGateCooldown(monotonicNowMs)

    init {
        require(durationMs in 1L..120_000L)
    }

    /** Starts an Instagram session; surface classification happens separately in observe. */
    fun beginInstagramSession(): GateTicket {
        generation++
        activeDurationMs = durationProvider?.invoke()?.takeIf { it in 1L..120_000L } ?: durationMs
        activeCooldownDurationMs = cooldownDurationProvider?.invoke()?.takeIf { it > 0L }
            ?: INSTAGRAM_ENTRY_COOLDOWN_MS
        visibleStartedAtMs = null
        pendingMessagesAttempt = null
        state = if (enabled()) EntryGateState.AWAITING else EntryGateState.BYPASSED
        return GateTicket(generation)
    }

    /** Admission boundary used by the service before allocating a new production ticket. */
    fun beginInstagramSessionIfEligible(): GateTicket? {
        if (cooldown.isSuppressed()) return null
        return beginInstagramSession()
    }

    fun cooldownActive(): Boolean = cooldown.isSuppressed()
    internal fun durationSnapshotMs(): Long = activeDurationMs

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

    fun remainingMs(nowMs: Long, ticket: GateTicket): Long {
        val started = visibleStartedAtMs
        if (nowMs < 0L || !valid(ticket) || state != EntryGateState.GATING || started == null) return 0L
        if (nowMs < started) return activeDurationMs
        return (activeDurationMs - (nowMs - started)).coerceAtLeast(0L)
    }

    fun complete(nowMs: Long, ticket: GateTicket): Boolean {
        val started = visibleStartedAtMs
        if (!enabled() || nowMs < 0L || !valid(ticket) || state != EntryGateState.GATING ||
            started == null || nowMs < started || nowMs - started < activeDurationMs
        ) return false
        if (!cooldown.recordTerminal(ticket, nowMs, activeCooldownDurationMs)) return false
        visibleStartedAtMs = null
        pendingMessagesAttempt = null
        state = EntryGateState.GRANTED
        return true
    }

    internal fun beginMessagesRoute(ticket: GateTicket): Boolean {
        val started = visibleStartedAtMs
        if (!enabled() || !valid(ticket) || state != EntryGateState.GATING || started == null) return false
        pendingMessagesAttempt = ticket to started
        visibleStartedAtMs = null
        state = EntryGateState.BYPASSED
        return true
    }

    internal fun finishMessagesRoute(nowMs: Long, ticket: GateTicket, result: MessagesRouteResult): Boolean {
        val attempt = pendingMessagesAttempt
        if (attempt?.first != ticket || !valid(ticket) || state != EntryGateState.BYPASSED) return false
        pendingMessagesAttempt = null
        if (!enabled() || nowMs < 0L || nowMs < attempt.second || result == MessagesRouteResult.FAILED) return false
        return cooldown.recordTerminal(ticket, nowMs, activeCooldownDurationMs)
    }

    fun skipToMessages(ticket: GateTicket): Boolean = bypass(ticket)

    fun bypass(ticket: GateTicket): Boolean {
        if (!valid(ticket)) return false
        if (state == EntryGateState.BYPASSED) {
            pendingMessagesAttempt = null
            return false
        }
        if (state != EntryGateState.GATING) return false
        visibleStartedAtMs = null
        pendingMessagesAttempt = null
        state = EntryGateState.BYPASSED
        return true
    }

    fun leaveInstagram() {
        generation++
        visibleStartedAtMs = null
        pendingMessagesAttempt = null
        state = EntryGateState.OUTSIDE
    }

    fun cancel() {
        generation++
        visibleStartedAtMs = null
        pendingMessagesAttempt = null
        if (state != EntryGateState.OUTSIDE) state = EntryGateState.BYPASSED
    }

    private fun valid(ticket: GateTicket) = ticket.generation == generation
}
