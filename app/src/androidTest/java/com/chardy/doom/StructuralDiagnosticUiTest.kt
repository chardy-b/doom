package com.chardy.doom

import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic structures only. These tests never open or capture Instagram. */
class StructuralDiagnosticUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun reset() = rule.runOnIdle { Observation.accept(rule.activity, false) }
    @After fun cleanup() = rule.runOnIdle { Observation.accept(rule.activity, false) }

    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()
    private fun shown(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    private fun seed(depth: Int = 0) = rule.runOnIdle {
        Observation.accept(rule.activity, true)
        Observation.connected = true
        Observation.record(StructuralFingerprint.Builder().apply {
            add(depth, true, true, false, 0)
        }.build())
    }
    private fun assertEmpty() = rule.runOnIdle {
        assertNull(Observation.samples.current)
        assertTrue(Observation.samples.baselines.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun sendEvent(service: DoomAccessibilityService, packageName: String) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            service.onAccessibilityEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSyntheticRoot(service: DoomAccessibilityService, packageName: String?) {
        val root = AccessibilityNodeInfo.obtain().apply { this.packageName = packageName }
        // Exercise the delayed-event collection boundary without reading a real active window.
        // The collector owns and recycles this synthetic node, including on mismatch.
        DoomAccessibilityService::class.java.getDeclaredMethod("collect", AccessibilityNodeInfo::class.java)
            .apply { isAccessible = true }.invoke(service, root)
    }

    @Test fun doomEventsPreserveLabelableSampleButDelayedInstagramRootMismatchInvalidatesIt() {
        val service = DoomAccessibilityService()
        seed()
        tap("LABEL FEED")
        val first = rule.runOnIdle { Observation.samples.current }
        rule.runOnIdle {
            sendEvent(service, rule.activity.packageName)
            assertSame(first, Observation.samples.current)
        }
        tap("LABEL INBOX")
        rule.runOnIdle { assertSame(first, Observation.samples.baselines[SampleLabel.INBOX]) }

        // A queued Instagram event can arrive after Doom becomes the active root.
        // Missing package metadata must invalidate the current sample in the same way.
        listOf(rule.activity.packageName, null).forEach { packageName ->
            seed(1)
            rule.runOnIdle {
                collectSyntheticRoot(service, packageName)
                assertNull(Observation.samples.current)
                SampleLabel.entries.forEach { Observation.label(it) }
                assertEquals(2, Observation.samples.baselines.size)
                assertSame(first, Observation.samples.baselines[SampleLabel.FEED])
                assertSame(first, Observation.samples.baselines[SampleLabel.INBOX])
                assertTrue(Observation.connected)
            }
            shown("Feed: labeled · no current sample")
            SampleLabel.entries.forEach {
                rule.onNodeWithText("LABEL ${it.title.uppercase()}").performScrollTo().assertIsNotEnabled()
            }
        }
        rule.runOnIdle { collectSyntheticRoot(service, "com.instagram.android") }
        tap("LABEL THREAD")
        rule.runOnIdle {
            assertNotNull(Observation.samples.current)
            assertSame(Observation.samples.current, Observation.samples.baselines[SampleLabel.THREAD])
            assertSame(first, Observation.samples.baselines[SampleLabel.FEED])
        }
    }

    @Test fun disclosureAndLabelsNeverClaimProtectionOrPrediction() {
        shown("INSTAGRAM · NOT PROTECTED")
        shown("SEPARABILITY RESEARCH ONLY")
        rule.onNodeWithText("Counts overlapped on Pixel 11 Pro / Android 17 / Instagram 445.0.0.45.83.")
            .performScrollTo().assertIsDisplayed()
        shown("Similarity is structural overlap, not a prediction or protection. Labels are yours; no thresholds or live gate.")
        rule.onNodeWithText("OPEN ACCESSIBILITY SETTINGS").performScrollTo().assertIsNotEnabled()
        SampleLabel.entries.forEach {
            rule.onNodeWithText("LABEL ${it.title.uppercase()}").performScrollTo().assertIsNotEnabled()
        }
        seed()
        shown("Observer connected · mapping unverified")
        shown("INSTAGRAM · NOT PROTECTED")
        rule.onNodeWithText("INSTAGRAM · PROTECTED").assertDoesNotExist()
    }

    @Test fun explicitLabelsCompareReplaceAndClearEverySample() {
        seed()
        val first = rule.runOnIdle { Observation.samples.current!!.opaque }
        shown("Opaque fingerprint: $first")
        SampleLabel.entries.forEach {
            shown("${it.title}: not labeled")
            tap("LABEL ${it.title.uppercase()}")
            shown("${it.title}: 100.0% similarity")
        }
        seed(1)
        SampleLabel.entries.forEach { shown("${it.title}: 0.0% similarity") }
        tap("LABEL FEED")
        shown("Feed: 100.0% similarity")
        shown("Inbox: 0.0% similarity")
        tap("CLEAR SAMPLES & LABELS")
        assertEmpty()
        SampleLabel.entries.forEach {
            shown("${it.title}: not labeled")
            rule.onNodeWithText("LABEL ${it.title.uppercase()}").performScrollTo().assertIsNotEnabled()
        }
        // Consent remains on; only a later observed event may supply a fresh sample.
        rule.runOnIdle { assertTrue(Observation.consent) }
    }

    @Test fun revocationAndStopClearCurrentAndAllLabels() {
        seed()
        SampleLabel.entries.forEach { tap("LABEL ${it.title.uppercase()}") }
        rule.onNodeWithContentDescription("Consent to local structural fingerprints")
            .performScrollTo().performClick()
        assertEmpty()
        rule.runOnIdle { assertFalse(Observation.connected); assertFalse(Observation.consent) }
        seed()
        tap("LABEL THREAD")
        tap("STOP OBSERVATION")
        assertEmpty()
        shown("Observation off · consent required")
    }

    @Test fun oldConsentDoesNotAuthorizeSamplesAndStoppedCallbacksCannotRestoreThem() {
        rule.runOnIdle {
            rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
                .clear().putBoolean("accepted", true).commit()
            Observation.load(rule.activity)
            assertFalse(Observation.consent)
            val sample = StructuralFingerprint.Builder().apply { add(0, true, true, false, 0) }.build()
            Observation.connected = true
            Observation.record(sample)
            Observation.label(SampleLabel.FEED)
            assertNull(Observation.samples.current)
            assertTrue(Observation.samples.baselines.isEmpty())
            Observation.accept(rule.activity, true)
            DoomAccessibilityService.disableObservation()
            Observation.record(sample)
            Observation.label(SampleLabel.FEED)
        }
        assertEmpty()
        shown("Observation off · service disconnected")
    }

    @Test fun serviceInterruptionDisconnectAndDestructionClearEverything() {
        val service = DoomAccessibilityService()
        val endings: List<() -> Unit> = listOf(
            { service.onInterrupt() },
            { service.onUnbind(null) },
            { service.onDestroy() },
            { DoomAccessibilityService.disableObservation() }
        )
        endings.forEach { end ->
            seed()
            SampleLabel.entries.forEach { tap("LABEL ${it.title.uppercase()}") }
            rule.runOnIdle {
                val sample = Observation.samples.current
                end()
                assertFalse(Observation.connected)
                assertTrue(Observation.consent)
                // Both a queued callback and direct recording must stay inert after disconnection.
                sendEvent(service, "com.instagram.android")
                Observation.record(sample)
                Observation.label(SampleLabel.FEED)
            }
            assertEmpty()
            shown("Observation off · service disconnected")
            SampleLabel.entries.forEach {
                rule.onNodeWithText("LABEL ${it.title.uppercase()}").performScrollTo().assertIsNotEnabled()
            }
        }
    }

    @Test fun interruptedObserverCanRecordOnlyAfterServiceConnectionCallback() {
        val service = DoomAccessibilityService()
        rule.runOnIdle {
            // Supply only a Context for the real connection callback's consent load; no OS binding.
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
        }
        try {
            seed()
            tap("LABEL FEED")
            rule.runOnIdle {
                val sample = Observation.samples.current
                service.onInterrupt()
                assertFalse(Observation.connected)
                Observation.record(sample)
                assertNull(Observation.samples.current)
                service.onServiceConnected()
                assertTrue(Observation.connected)
                assertNull(Observation.samples.current)
                assertTrue(Observation.samples.baselines.isEmpty())
                collectSyntheticRoot(service, "com.instagram.android")
                assertNotNull(Observation.samples.current)
                assertTrue(Observation.samples.baselines.isEmpty())
            }
            shown("Observer connected · mapping unverified")
            tap("LABEL FEED")
            shown("Feed: 100.0% similarity")
        } finally {
            rule.runOnIdle { service.onDestroy() }
        }
    }
}
