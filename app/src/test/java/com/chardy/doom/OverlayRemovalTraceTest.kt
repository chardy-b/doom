package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRemovalTraceTest {
    @Test fun recorderHasExactCapacityAndPinsEpisodeBoundaries() {
        val recorder = RemovalTraceRecorder(100L, capacity = 8)
        recorder.record(100L, RemovalTraceMark.ATTEMPT)
        recorder.record(101L, RemovalTraceMark.SHOWN)
        repeat(100) { recorder.record(102L + it, RemovalTraceMark.WATCHDOG_SAFE) }
        recorder.record(300L, RemovalTraceMark.EVENT_PACKAGE_RESET, action = RemovalTraceAction.RESET_OUTSIDE)
        recorder.record(301L, RemovalTraceMark.CLOSING, action = RemovalTraceAction.RESET_OUTSIDE)
        recorder.record(302L, RemovalTraceMark.DETACHED)
        recorder.record(302L, RemovalTraceMark.ACTION_RELEASED, action = RemovalTraceAction.RESET_OUTSIDE)

        assertTrue(recorder.size <= 8)
        assertEquals(RemovalTraceMark.EVENT_PACKAGE_RESET, recorder.firstCause)
        assertTrue(recorder.entries.any { it.mark == RemovalTraceMark.ATTEMPT })
        assertTrue(recorder.entries.any { it.mark == RemovalTraceMark.SHOWN })
        assertTrue(recorder.entries.any { it.mark == RemovalTraceMark.CLOSING })
        assertTrue(recorder.entries.any { it.mark == RemovalTraceMark.ACTION_RELEASED })
        assertTrue(recorder.entries.any { it.mark == RemovalTraceMark.TRUNCATED })
    }

    @Test fun repeatedTicksCoalesceAndSerializerIsDeterministicAsciiAndBounded() {
        val first = RemovalTraceRecorder(10L)
        first.record(10L, RemovalTraceMark.WATCHDOG_SAFE)
        first.record(20L, RemovalTraceMark.WATCHDOG_SAFE)
        first.record(30L, RemovalTraceMark.WATCHDOG_SAFE)
        first.record(40L, RemovalTraceMark.EVENT_ROOT_MISSING, root = RemovalTraceRoot.NO_ROOT)
        first.freeze()
        val snapshot = first.snapshot()
        val again = snapshot.serializeAscii()

        assertEquals(3, snapshot.records.size)
        assertEquals(0L, snapshot.records[0].dtMs)
        assertEquals(20L, snapshot.records[1].dtMs)
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.EVENT_ROOT_MISSING })
        assertEquals(again, snapshot.serializeAscii())
        assertTrue(again.all { it.code in 0..127 })
        assertTrue(again.length <= RemovalTraceRecorder.MAX_ASCII_BYTES)
        assertFalse(again.contains("com."))
    }

    @Test fun freezePinsFirstCauseAndRejectsPostFreezeMutation() {
        val recorder = RemovalTraceRecorder(0L)
        recorder.record(0L, RemovalTraceMark.ATTEMPT)
        recorder.record(1L, RemovalTraceMark.EVENT_PACKAGE_RESET)
        recorder.record(2L, RemovalTraceMark.CLOSING, action = RemovalTraceAction.RESET_OUTSIDE)
        recorder.record(3L, RemovalTraceMark.DETACHED)
        recorder.record(3L, RemovalTraceMark.ACTION_RELEASED, action = RemovalTraceAction.RESET_OUTSIDE)
        recorder.freeze()
        val before = recorder.entries

        assertFalse(recorder.record(4L, RemovalTraceMark.WATCHDOG_FOREIGN))
        assertEquals(before, recorder.entries)
    }

    @Test fun storeArmingIsOneShotAndExpiresWithoutEpisode() {
        var now = 1_000L
        val store = RemovalTraceStore({ now })
        assertTrue(store.arm())
        assertEquals(RemovalTraceAvailability.ARMED, store.availability())
        now += 120_000L
        assertEquals(RemovalTraceAvailability.EXPIRED, store.availability())
        assertFalse(store.beginEligibleEpisode())
        assertTrue(store.arm())
        assertTrue(store.beginEligibleEpisode())
        assertEquals(RemovalTraceAvailability.RECORDING, store.availability())
        assertFalse(store.beginEligibleEpisode())
    }

    @Test fun storeFreezeRetentionClearAndRollbackAreFailClosed() {
        var now = 5_000L
        val store = RemovalTraceStore({ now })
        store.arm()
        assertTrue(store.beginEligibleEpisode())
        now = 4_000L
        store.record(mark = RemovalTraceMark.EVENT_PACKAGE_RESET)
        assertTrue(store.finish(
            terminalMark = RemovalTraceMark.DETACHED,
            action = RemovalTraceAction.COMPLETE,
            detached = true
        ))
        val frozen = store.snapshot()!!
        assertTrue(frozen.records.any { it.mark == RemovalTraceMark.INVALID_CLOCK })
        now = 5_000L
        assertEquals(frozen, store.snapshot())
        now = 605_001L
        assertEquals(RemovalTraceAvailability.EXPIRED, store.availability())

        store.arm(now)
        assertTrue(store.beginEligibleEpisode(now))
        store.clear()
        assertEquals(RemovalTraceAvailability.NONE, store.availability(now))
        assertEquals(null, store.snapshot(now))
    }

    @Test fun largeUptimeUsesOneElapsedRealtimeDomainForOffsetsAndExpiry() {
        var now = 86_400_000L
        val store = RemovalTraceStore({ now })
        assertTrue(store.arm())
        assertTrue(store.beginEligibleEpisode())
        now += 1_000L
        assertTrue(store.record(mark = RemovalTraceMark.SHOWN))
        now += 2_000L
        assertTrue(store.finish(
            terminalMark = RemovalTraceMark.DETACHED,
            action = RemovalTraceAction.COMPLETE,
            detached = true
        ))

        val snapshot = store.snapshot()!!
        assertEquals(listOf(0L, 1_000L, 3_000L, 3_000L), snapshot.records.map { it.dtMs })
        now += 599_999L
        assertEquals(RemovalTraceAvailability.AVAILABLE, store.availability())
        now += 1L
        assertEquals(RemovalTraceAvailability.EXPIRED, store.availability())
    }

    @Test fun terminalRowsSurviveRetryAndEventStormWithSingleCauseAndClosing() {
        val recorder = RemovalTraceRecorder(10L)
        recorder.record(10L, RemovalTraceMark.ATTEMPT)
        recorder.record(11L, RemovalTraceMark.EVENT_PACKAGE_RESET)
        repeat(200) { index ->
            recorder.record(12L + index, RemovalTraceMark.EVENT_ROOT_MISSING)
            recorder.record(12L + index, RemovalTraceMark.REMOVAL_RETRY)
        }
        recorder.record(250L, RemovalTraceMark.CLOSING)
        recorder.record(251L, RemovalTraceMark.REMOVAL_EXHAUSTED)
        recorder.record(251L, RemovalTraceMark.ACTION_VETOED, action = RemovalTraceAction.HOME)
        recorder.freeze()

        val marks = recorder.snapshot().records.map { it.mark }
        assertTrue(marks.size <= RemovalTraceRecorder.MAX_RECORDS)
        assertEquals(1, marks.count { it == RemovalTraceMark.EVENT_PACKAGE_RESET })
        assertEquals(1, marks.count { it == RemovalTraceMark.CLOSING })
        assertTrue(marks.contains(RemovalTraceMark.REMOVAL_EXHAUSTED))
        assertTrue(marks.contains(RemovalTraceMark.ACTION_VETOED))
        assertTrue(marks.contains(RemovalTraceMark.TRUNCATED))
    }

    @Test fun noOverlayIsAReleasedPolicyAndExhaustionDoesNotInventVetoAction() {
        val released = RemovalTraceStore({ 10L })
        released.arm(10L)
        released.beginEligibleEpisode(10L)
        released.finish(
            now = 11L,
            terminalMark = RemovalTraceMark.NO_OVERLAY_RELEASED,
            action = RemovalTraceAction.RESET_OUTSIDE,
            detached = false,
            policyReleased = true,
        )
        val releasedMarks = released.snapshot(11L)!!.records
        assertTrue(releasedMarks.any {
            it.mark == RemovalTraceMark.ACTION_RELEASED && it.action == RemovalTraceAction.RESET_OUTSIDE
        })
        assertFalse(releasedMarks.any { it.mark == RemovalTraceMark.ACTION_VETOED })

        val exhausted = RemovalTraceStore({ 20L })
        exhausted.arm(20L)
        exhausted.beginEligibleEpisode(20L)
        exhausted.finish(
            now = 21L,
            terminalMark = RemovalTraceMark.REMOVAL_EXHAUSTED,
            detached = false,
            policyReleased = false,
        )
        assertFalse(exhausted.snapshot(21L)!!.records.any { it.mark == RemovalTraceMark.ACTION_VETOED })
    }
}
