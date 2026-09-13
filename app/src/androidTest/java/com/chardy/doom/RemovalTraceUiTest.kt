package com.chardy.doom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Clipboard and lifecycle-access checks for the explicit, process-local trace control. */
class RemovalTraceUiTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun clearTraceAndConsent() {
        rule.scenario.onActivity {
            RemovalTraceStore.process.clear()
            Observation.setGateConsent(it, false)
            Observation.accept(it, false)
            Observation.connected = false
            RemovalTraceStore.process = RemovalTraceStore()
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun noPassiveWriteAndExplicitCopyWorksAfterServiceDisconnect() {
        lateinit var clipboard: ClipboardManager
        lateinit var before: ClipData
        var now = 86_400_000L
        RemovalTraceStore.process = RemovalTraceStore({ now })
        rule.scenario.onActivity { activity ->
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            before = ClipData.newPlainText("test", "sentinel")
            clipboard.setPrimaryClip(before)
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            now += 1L
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            now += 1L
            RemovalTraceStore.process.record(now, RemovalTraceMark.EVENT_PACKAGE_RESET,
                event = RemovalTraceEvent.CONTENT,
                owner = RemovalTraceOwner.OTHER,
                action = RemovalTraceAction.RESET_OUTSIDE)
            RemovalTraceStore.process.finish(
                now + 1L,
                RemovalTraceMark.DETACHED,
                RemovalTraceAction.RESET_OUTSIDE,
                detached = true
            )
            Observation.connected = false
        }
        instrumentation.waitForIdleSync()
        assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())

        rule.scenario.recreate()
        instrumentation.waitForIdleSync()
        assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())

        rule.scenario.onActivity { activity ->
            assertEquals(
                Observation.RemovalTraceCopyResult.COPIED,
                Observation.copyRemovalTrace(activity)
            )
        }
        instrumentation.waitForIdleSync()
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.startsWith("WIL182_REMOVAL_TRACE_V1\n"))
        assertTrue(copied.contains("EVENT_PACKAGE_RESET"))
        assertTrue(copied.length <= RemovalTraceRecorder.MAX_ASCII_BYTES)
    }

    @Test fun clearedOrRevokedTraceCannotCopy() {
        var now = 10_000L
        RemovalTraceStore.process = RemovalTraceStore({ now })
        rule.scenario.onActivity { activity ->
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.finish(now + 1L, RemovalTraceMark.DETACHED, detached = true)
            RemovalTraceStore.process.clear()
            assertEquals(
                Observation.RemovalTraceCopyResult.UNAVAILABLE,
                Observation.copyRemovalTrace(activity)
            )
            now += 20L
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.finish(now + 1L, RemovalTraceMark.DETACHED, detached = true)
            assertEquals(RemovalTraceAvailability.AVAILABLE, RemovalTraceStore.process.availability(now + 1L))
            Observation.setGateConsent(activity, false)
            assertEquals(RemovalTraceAvailability.NONE, RemovalTraceStore.process.availability(now + 1L))
            assertEquals(
                Observation.RemovalTraceCopyResult.UNAVAILABLE,
                Observation.copyRemovalTrace(activity)
            )
        }
    }
}
