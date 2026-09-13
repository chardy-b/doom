package com.chardy.doom

import java.util.EnumSet

/** Closed, content-free vocabulary for the one-episode removal diagnosis. */
internal enum class RemovalTraceMark {
    ATTEMPT, SHOWN, EVENT_OBSERVED, EVENT_IGNORED_OWN, EVENT_SUPPRESSED_COOLDOWN,
    WATCHDOG_SAFE, WATCHDOG_UNCERTAIN, EVENT_PACKAGE_RESET, EVENT_ROOT_MISSING,
    EVENT_ROOT_MISMATCH, EVENT_DENIED, EVENT_FAILURE, WATCHDOG_FOREIGN,
    WATCHDOG_UNCERTAINTY_EXPIRED, WATCHDOG_ROLLBACK,
    WATCHDOG_NO_SAFE_ANCHOR, WATCHDOG_FAILURE, INSTALL_FAILURE, SHOWN_REJECTED,
    ADMISSION_REJECTED, TIMER_COMPLETE, USER_MESSAGES, USER_HOME, APP_RETURN,
    SERVICE_CONNECTED_RESET, SERVICE_INTERRUPTED, SERVICE_UNBOUND, SERVICE_DESTROYED,
    CLOSING, REMOVAL_RETRY, SAFETY_OVERRIDE, REMOVAL_EXHAUSTED, DETACHED,
    ALREADY_DETACHED, NO_OVERLAY_RELEASED, ACTION_RELEASED, ACTION_VETOED,
    INVALID_CLOCK, TIME_CAPPED, TRUNCATED
}

internal enum class RemovalTraceEvent { NA, STATE, CONTENT, OTHER, NULL_EVENT }
internal enum class RemovalTraceOwner { NA, IG, DOOM, OTHER, UNATTRIBUTED }
internal enum class RemovalTraceRoot {
    NOT_READ, IG, DOOM, FOREIGN, NO_ROOT, NO_PACKAGE, READ_FAILURE
}
internal enum class RemovalTraceAction {
    NONE, BYPASS, RESET_OUTSIDE, COMPLETE, PRESERVE_REPORT, NAVIGATE_MESSAGES, HOME
}

internal data class RemovalTraceRecord(
    val dtMs: Long,
    val mark: RemovalTraceMark,
    val event: RemovalTraceEvent,
    val owner: RemovalTraceOwner,
    val root: RemovalTraceRoot,
    val action: RemovalTraceAction
)

internal data class RemovalTraceSnapshot(val records: List<RemovalTraceRecord>) {
    init {
        require(records.size <= RemovalTraceRecorder.MAX_RECORDS)
        require(records.all { it.dtMs in 0L..RemovalTraceRecorder.MAX_OFFSET_MS })
    }

    /** ACTION_RELEASED is a policy release after detach, not proof that the action executed. */
    fun serializeAscii(): String {
        val out = StringBuilder("WIL182_REMOVAL_TRACE_V1\n")
        records.forEach { record ->
            val row = buildString {
                append(record.dtMs)
                append('|').append(record.mark.name)
                append('|').append(record.event.name)
                append('|').append(record.owner.name)
                append('|').append(record.root.name)
                append('|').append(record.action.name).append('\n')
            }
            if (out.length + row.length <= RemovalTraceRecorder.MAX_ASCII_BYTES) out.append(row)
        }
        return out.toString()
    }
}

/** Pure fixed-capacity recorder. It accepts only the closed trace types above. */
internal class RemovalTraceRecorder(
    private val startMs: Long,
    private val capacity: Int = MAX_RECORDS
) {
    companion object {
        const val MAX_RECORDS = 64
        const val MAX_OFFSET_MS = 10_000L
        const val MAX_ASCII_BYTES = 8_192
        private const val TERMINAL_RESERVE = 8

        private val causeMarks: Set<RemovalTraceMark> = EnumSet.of(
            RemovalTraceMark.EVENT_PACKAGE_RESET, RemovalTraceMark.EVENT_ROOT_MISSING,
            RemovalTraceMark.EVENT_ROOT_MISMATCH, RemovalTraceMark.EVENT_DENIED,
            RemovalTraceMark.EVENT_FAILURE, RemovalTraceMark.WATCHDOG_FOREIGN,
            RemovalTraceMark.WATCHDOG_UNCERTAINTY_EXPIRED,
            RemovalTraceMark.WATCHDOG_ROLLBACK, RemovalTraceMark.WATCHDOG_NO_SAFE_ANCHOR,
            RemovalTraceMark.WATCHDOG_FAILURE, RemovalTraceMark.INSTALL_FAILURE,
            RemovalTraceMark.SHOWN_REJECTED, RemovalTraceMark.ADMISSION_REJECTED,
            RemovalTraceMark.TIMER_COMPLETE, RemovalTraceMark.USER_MESSAGES,
            RemovalTraceMark.USER_HOME, RemovalTraceMark.APP_RETURN,
            RemovalTraceMark.SAFETY_OVERRIDE, RemovalTraceMark.SERVICE_CONNECTED_RESET,
            RemovalTraceMark.SERVICE_INTERRUPTED, RemovalTraceMark.SERVICE_UNBOUND,
            RemovalTraceMark.SERVICE_DESTROYED,
        )

        private val terminalMarks: Set<RemovalTraceMark> = EnumSet.of(
            RemovalTraceMark.DETACHED, RemovalTraceMark.ALREADY_DETACHED,
            RemovalTraceMark.NO_OVERLAY_RELEASED, RemovalTraceMark.REMOVAL_EXHAUSTED,
            RemovalTraceMark.ACTION_RELEASED, RemovalTraceMark.ACTION_VETOED,
            RemovalTraceMark.INVALID_CLOCK, RemovalTraceMark.TIME_CAPPED,
            RemovalTraceMark.TRUNCATED,
        )
    }

    private val records = ArrayList<RemovalTraceRecord>(capacity)
    private val terminalReserve = minOf(TERMINAL_RESERVE, capacity / 2)
    private var lastNowMs: Long? = null
    private var lastOffsetMs = 0L
    private var timeCapped = false
    private var frozen = false

    init {
        require(startMs >= 0L)
        require(capacity in 1..MAX_RECORDS)
    }

    val isFrozen: Boolean get() = frozen
    val size: Int get() = records.size
    val firstCause: RemovalTraceMark?
        get() = records.firstOrNull { isCause(it.mark) }?.mark
    val entries: List<RemovalTraceRecord> get() = records.toList()

    fun record(
        nowMs: Long,
        mark: RemovalTraceMark,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        action: RemovalTraceAction = RemovalTraceAction.NONE
    ): Boolean {
        if (frozen) return false
        var offset = offsetFor(nowMs)
        if (nowMs < startMs || lastNowMs?.let { nowMs < it } == true) {
            append(offset, RemovalTraceMark.INVALID_CLOCK, event, owner, root, RemovalTraceAction.NONE)
            offset = lastOffsetMs
        }
        if (!timeCapped && nowMs >= startMs && nowMs - startMs > MAX_OFFSET_MS) {
            append(MAX_OFFSET_MS, RemovalTraceMark.TIME_CAPPED)
            timeCapped = true
        }
        lastNowMs = maxOf(lastNowMs ?: startMs, nowMs)
        lastOffsetMs = maxOf(lastOffsetMs, offset)
        if (timeCapped && !isEssential(mark)) return false
        return append(offset, mark, event, owner, root, action)
    }

    fun freeze() { frozen = true }

    fun snapshot(): RemovalTraceSnapshot {
        check(frozen)
        return RemovalTraceSnapshot(records.toList())
    }

    private fun offsetFor(nowMs: Long): Long = when {
        nowMs < startMs -> lastOffsetMs
        nowMs - startMs > MAX_OFFSET_MS -> MAX_OFFSET_MS
        else -> maxOf(lastOffsetMs, nowMs - startMs)
    }

    private fun append(
        offset: Long,
        mark: RemovalTraceMark,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        action: RemovalTraceAction = RemovalTraceAction.NONE
    ): Boolean {
        val row = RemovalTraceRecord(offset.coerceIn(0L, MAX_OFFSET_MS), mark, event, owner, root, action)
        val previous = records.lastOrNull()
        if (previous == row) return false
        // Retain the first and latest sample for a repeated run; do not allocate a row per tick.
        if (previous != null && sameCategory(previous, row) && records.size > 1 &&
            sameCategory(records[records.lastIndex - 1], row)
        ) {
            records[records.lastIndex] = row
            return true
        }

        val terminal = mark in terminalMarks
        val historyLimit = (capacity - terminalReserve).coerceAtLeast(1)
        if (!terminal && records.size >= historyLimit) {
            val removable = records.indexOfFirst { !isProtected(it) && it.mark !in terminalMarks }
            if (removable < 0) {
                noteTruncated(offset)
                return false
            }
            records.removeAt(removable)
            noteTruncated(offset)
        } else if (records.size >= capacity) {
            val removable = records.indexOfFirst { !isProtected(it) }
            if (removable < 0) {
                noteTruncated(offset)
                return false
            }
            records.removeAt(removable)
            noteTruncated(offset)
        }
        records += row
        return true
    }

    private fun noteTruncated(offset: Long) {
        if (records.any { it.mark == RemovalTraceMark.TRUNCATED }) return
        if (records.size < capacity) records += RemovalTraceRecord(
            offset.coerceIn(0L, MAX_OFFSET_MS), RemovalTraceMark.TRUNCATED,
            RemovalTraceEvent.NA, RemovalTraceOwner.NA, RemovalTraceRoot.NOT_READ,
            RemovalTraceAction.NONE
        )
    }

    private fun isProtected(record: RemovalTraceRecord): Boolean = when {
        record.mark in terminalMarks -> true
        record.mark == RemovalTraceMark.ATTEMPT &&
            records.firstOrNull { it.mark == RemovalTraceMark.ATTEMPT } == record -> true
        record.mark == RemovalTraceMark.SHOWN &&
            records.firstOrNull { it.mark == RemovalTraceMark.SHOWN } == record -> true
        record.mark == RemovalTraceMark.CLOSING &&
            records.firstOrNull { it.mark == RemovalTraceMark.CLOSING } == record -> true
        isCause(record.mark) && firstCause == record.mark -> true
        else -> false
    }

    private fun isEssential(mark: RemovalTraceMark): Boolean =
        mark in terminalMarks ||
            (mark == RemovalTraceMark.ATTEMPT && records.none { it.mark == mark }) ||
            (mark == RemovalTraceMark.SHOWN && records.none { it.mark == mark }) ||
            (isCause(mark) && firstCause == null) ||
            (mark == RemovalTraceMark.CLOSING && records.none { it.mark == RemovalTraceMark.CLOSING })

    private fun isCause(mark: RemovalTraceMark): Boolean = mark in causeMarks

    private fun sameCategory(first: RemovalTraceRecord, second: RemovalTraceRecord): Boolean =
        first.mark == second.mark && first.event == second.event && first.owner == second.owner &&
            first.root == second.root && first.action == second.action
}
