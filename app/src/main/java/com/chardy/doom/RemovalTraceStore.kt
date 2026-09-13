package com.chardy.doom

import android.os.SystemClock

internal enum class RemovalTraceAvailability { NONE, ARMED, RECORDING, AVAILABLE, EXPIRED }

/** Process-memory owner for one explicitly armed episode; all callers own the main thread. */
internal class RemovalTraceStore(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val armLifetimeMs: Long = 120_000L,
    private val retentionMs: Long = 600_000L
) {
    companion object {
        /** Internal test seam only; production never replaces this main-thread store. */
        @Volatile internal var process = RemovalTraceStore()
    }

    private var armedAtMs: Long? = null
    private var armedUntilMs: Long? = null
    private var recorder: RemovalTraceRecorder? = null
    private var frozen: RemovalTraceSnapshot? = null
    private var frozenAtMs: Long? = null
    private var expired = false

    init {
        require(armLifetimeMs > 0L)
        require(retentionMs > 0L)
    }

    /** Fast, clock-free guard used before event category/root work. */
    @Synchronized fun isCapturing(): Boolean = armedAtMs != null || recorder != null

    @Synchronized fun arm(now: Long = nowMs()): Boolean {
        if (now < 0L) return false
        clearLocked()
        armedAtMs = now
        armedUntilMs = now + armLifetimeMs
        expired = false
        return true
    }

    @Synchronized fun beginEligibleEpisode(now: Long = nowMs()): Boolean {
        expireLocked(now)
        val armedAt = armedAtMs ?: return false
        if (armedUntilMs == null) return false
        if (now < armedAt || now - armedAt >= armLifetimeMs) {
            expireLocked(now)
            return false
        }
        armedAtMs = null
        armedUntilMs = null
        recorder = RemovalTraceRecorder(now).also { it.record(now, RemovalTraceMark.ATTEMPT) }
        return true
    }

    @Synchronized fun record(
        now: Long = nowMs(),
        mark: RemovalTraceMark,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        action: RemovalTraceAction = RemovalTraceAction.NONE
    ): Boolean {
        expireLocked(now)
        return recorder?.record(now, mark, event, owner, root, action) ?: false
    }

    /** Freeze after physical detach/no-overlay release and policy action selection. */
    @Synchronized fun finish(
        now: Long = nowMs(),
        terminalMark: RemovalTraceMark,
        action: RemovalTraceAction = RemovalTraceAction.NONE,
        detached: Boolean,
        policyReleased: Boolean = detached,
        vetoedAction: RemovalTraceAction = RemovalTraceAction.NONE,
    ): Boolean {
        expireLocked(now)
        val current = recorder ?: return false
        current.record(now, terminalMark, action = if (policyReleased) action else RemovalTraceAction.NONE)
        if (policyReleased && action != RemovalTraceAction.NONE) {
            current.record(now, RemovalTraceMark.ACTION_RELEASED, action = action)
        }
        if (vetoedAction != RemovalTraceAction.NONE) {
            current.record(now, RemovalTraceMark.ACTION_VETOED, action = vetoedAction)
        }
        current.freeze()
        frozenAtMs = now.coerceAtLeast(0L)
        frozen = current.snapshot()
        recorder = null
        return true
    }

    @Synchronized fun clear() { clearLocked() }

    @Synchronized fun availability(now: Long = nowMs()): RemovalTraceAvailability {
        expireLocked(now)
        return when {
            armedAtMs != null -> RemovalTraceAvailability.ARMED
            recorder != null -> RemovalTraceAvailability.RECORDING
            frozen != null -> RemovalTraceAvailability.AVAILABLE
            expired -> RemovalTraceAvailability.EXPIRED
            else -> RemovalTraceAvailability.NONE
        }
    }

    @Synchronized fun snapshot(now: Long = nowMs()): RemovalTraceSnapshot? {
        expireLocked(now)
        return frozen
    }

    private fun clearLocked() {
        armedAtMs = null
        armedUntilMs = null
        recorder = null
        frozen = null
        frozenAtMs = null
        expired = false
    }

    private fun expireLocked(now: Long) {
        if (now < 0L) return
        val armedAt = armedAtMs
        if (armedAt != null && now >= armedAt && now - armedAt >= armLifetimeMs && recorder == null) {
            clearLocked()
            expired = true
            return
        }
        val frozenAt = frozenAtMs
        if (frozenAt != null && now >= frozenAt && now - frozenAt >= retentionMs) {
            clearLocked()
            expired = true
        }
    }
}
