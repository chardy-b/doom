package com.chardy.doom

import android.content.ClipData
import android.content.ClipboardManager
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

/** Synthetic structures only. No Instagram launch, real samples, or diagnostic screenshots. */
class StructuralDiagnosticUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val clipboard get() = requireNotNull(rule.activity.getSystemService(ClipboardManager::class.java))

    @Before fun reset() = rule.runOnIdle {
        Observation.accept(rule.activity, false)
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
    }
    @After fun cleanup() = rule.runOnIdle {
        Observation.accept(rule.activity, false)
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()
    private fun shown(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    private fun sample(depth: Int = 0) = SanitizedStructuralReport.Builder().apply {
        add(depth, "com.instagram.android:id/feed_tab", "android.widget.TextView", 0,
            false, false, false, false, false)
    }.build()!!
    private fun seed(depth: Int = 0) = rule.runOnIdle {
        Observation.accept(rule.activity, true)
        Observation.connected = true
        Observation.record(sample(depth))
    }
    private fun assertEmpty() = rule.runOnIdle {
        assertNull(Observation.report)
        assertFalse(Observation.revealed)
        assertFalse(Observation.copied)
        assertFalse(Observation.canReveal)
        assertFalse(Observation.canCopy)
    }
    private fun assertActionsDisabled() {
        rule.onNodeWithText("REVEAL LOCAL REPORT").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("COPY REVIEWED REPORT").performScrollTo().assertIsNotEnabled()
    }
    private fun revealAndCopy() {
        tap("REVEAL LOCAL REPORT")
        tap("COPY REVIEWED REPORT")
        rule.runOnIdle { assertTrue(Observation.revealed); assertTrue(Observation.copied) }
    }

    @Suppress("DEPRECATION")
    private fun sendEvent(service: DoomAccessibilityService, packageName: String) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            service.onAccessibilityEvent(event)
        } finally { event.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun collectSyntheticRoot(service: DoomAccessibilityService, packageName: String?) {
        val root = AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            viewIdResourceName = "com.instagram.android:id/feed_tab"
            className = "android.widget.TextView"
            text = "SECRET_MESSAGE"
            contentDescription = "SECRET_ACCOUNT"
            hintText = "SECRET_HINT"
            error = "SECRET_ERROR"
        }
        // Collector owns/recycles this node, including on mismatched roots.
        DoomAccessibilityService::class.java.getDeclaredMethod("collect", AccessibilityNodeInfo::class.java)
            .apply { isAccessible = true }.invoke(service, root)
    }

    @Test fun disclosureAndControlsNeverClaimScreenIdentityOrProtection() {
        shown("INSTAGRAM · NOT PROTECTED")
        shown("SANITIZED STRUCTURAL REPORT")
        shown("Structure changes with scrolling and content. This report does not identify a screen or provide prediction, blocking or protection.")
        shown("Copy leaves Doom process memory and enters the system clipboard. Review the revealed report before copying; upload privately, then clear the clipboard. Clearing or stopping Doom cannot recall copies outside the app.")
        rule.onNodeWithText("OPEN ACCESSIBILITY SETTINGS").performScrollTo().assertIsNotEnabled()
        assertActionsDisabled()
        seed()
        shown("Observer connected · mapping unverified")
        shown("INSTAGRAM · NOT PROTECTED")
        rule.onNodeWithText("INSTAGRAM · PROTECTED").assertDoesNotExist()
        rule.onNodeWithText("LABEL FEED").assertDoesNotExist()
        rule.onNodeWithText("similarity", substring = true).assertDoesNotExist()
    }

    @Test fun reportIsHiddenUntilExplicitRevealAndOnlyExplicitCopyChangesClipboard() {
        seed()
        val first = rule.runOnIdle { Observation.report!!.text }
        rule.onNodeWithText(first).assertDoesNotExist()
        rule.onNodeWithText("COPY REVIEWED REPORT").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            Observation.copyReport(rule.activity)
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        tap("REVEAL LOCAL REPORT")
        shown(first)
        rule.runOnIdle { assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        tap("COPY REVIEWED REPORT")
        rule.runOnIdle { assertEquals(first, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        shown("Copied to system clipboard. Upload privately, then clear the clipboard.")
        seed(1)
        rule.runOnIdle { assertFalse(Observation.revealed); assertFalse(Observation.copied) }
        rule.onNodeWithText(first).assertDoesNotExist()
        rule.onNodeWithText("COPY REVIEWED REPORT").performScrollTo().assertIsNotEnabled()
        tap("CLEAR REPORT")
        assertEmpty()
        assertActionsDisabled()
        rule.runOnIdle { assertTrue(Observation.consent) }
    }

    @Test fun doomEventsPreserveReportButWrongOrMissingRootInvalidatesAllReportState() {
        val service = DoomAccessibilityService()
        seed()
        revealAndCopy()
        val first = rule.runOnIdle { Observation.report }
        rule.runOnIdle {
            sendEvent(service, rule.activity.packageName)
            service.onAccessibilityEvent(null)
            assertSame(first, Observation.report)
            assertTrue(Observation.revealed)
            assertTrue(Observation.copied)
        }
        listOf(rule.activity.packageName, null).forEach { packageName ->
            seed(1)
            revealAndCopy()
            rule.runOnIdle { collectSyntheticRoot(service, packageName) }
            assertEmpty()
            assertActionsDisabled()
            rule.runOnIdle { assertTrue(Observation.connected) }
        }
        rule.runOnIdle {
            collectSyntheticRoot(service, "com.instagram.android")
            assertNotNull(Observation.report)
            assertFalse(Observation.report!!.text.contains("SECRET"))
            assertFalse(Observation.revealed)
            assertFalse(Observation.copied)
        }
    }

    @Test fun delayedInstagramEventWithNullActiveRootInvalidatesReport() {
        seed()
        revealAndCopy()
        // An unbound service has no active root; use the real event entry point.
        rule.runOnIdle { sendEvent(DoomAccessibilityService(), "com.instagram.android") }
        assertEmpty()
        assertActionsDisabled()
    }

    @Test fun revokeAndStopClearCurrentRevealAndCopyState() {
        seed()
        revealAndCopy()
        rule.onNodeWithContentDescription("Consent to sanitized structural report")
            .performScrollTo().performClick()
        assertEmpty()
        rule.runOnIdle { assertFalse(Observation.connected); assertFalse(Observation.consent) }
        seed()
        revealAndCopy()
        tap("STOP OBSERVATION")
        assertEmpty()
        shown("Observation off · consent required")
    }

    @Test fun bothOldConsentKeysCannotAuthorizeAndInvalidCopyNeverTouchesClipboard() {
        rule.runOnIdle {
            rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
                .clear().putBoolean("accepted", true).putBoolean("structural_fingerprints_v1", true).commit()
            Observation.load(rule.activity)
            assertFalse(Observation.consent)
            Observation.connected = true
            Observation.record(sample())
            Observation.revealReport()
            Observation.copyReport(rule.activity)
            assertNull(Observation.report)
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
            Observation.accept(rule.activity, true)
            Observation.record(sample())
            Observation.revealReport()
            DoomAccessibilityService.disableObservation()
            Observation.record(sample())
            Observation.revealReport()
            Observation.copyReport(rule.activity)
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertEmpty()
        shown("Observation off · service disconnected")
    }

    @Test fun interruptionDisconnectAndDestructionClearEverythingAndRejectDelayedCallbacks() {
        val service = DoomAccessibilityService()
        val endings: List<() -> Unit> = listOf(
            { service.onInterrupt() }, { service.onUnbind(null) },
            { service.onDestroy() }, { DoomAccessibilityService.disableObservation() })
        endings.forEach { end ->
            seed()
            revealAndCopy()
            rule.runOnIdle {
                end()
                assertFalse(Observation.connected)
                assertTrue(Observation.consent)
                clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
                sendEvent(service, "com.instagram.android")
                Observation.record(sample())
                Observation.revealReport()
                Observation.copyReport(rule.activity)
                assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            assertEmpty()
            assertActionsDisabled()
            shown("Observation off · service disconnected")
        }
    }

    @Test fun connectionAlwaysClearsStateAndInterruptedObserverRequiresConnectionCallback() {
        val service = DoomAccessibilityService()
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
        }
        fun connect() {
            // onServiceConnected is protected: invoke the real callback through test-only reflection.
            DoomAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(service)
        }
        try {
            seed()
            revealAndCopy()
            rule.runOnIdle { connect(); assertTrue(Observation.connected) }
            assertEmpty()
            seed()
            revealAndCopy()
            rule.runOnIdle {
                service.onInterrupt()
                Observation.record(sample())
                assertNull(Observation.report)
                connect()
                assertTrue(Observation.connected)
                assertNull(Observation.report)
                assertFalse(Observation.revealed)
                assertFalse(Observation.copied)
                collectSyntheticRoot(service, "com.instagram.android")
                assertNotNull(Observation.report)
                assertFalse(Observation.revealed)
                assertFalse(Observation.copied)
            }
            shown("Observer connected · mapping unverified")
        } finally { rule.runOnIdle { service.onDestroy() } }
    }

    @Test fun onlyConsentIsPersistedAndLoadingWithoutConsentClearsMemory() {
        seed()
        revealAndCopy()
        rule.runOnIdle {
            val prefs = rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE)
            assertEquals(mapOf("sanitized_structural_report_v1" to true), prefs.all)
            prefs.edit().clear().commit()
            Observation.load(rule.activity)
        }
        assertEmpty()
        assertActionsDisabled()
    }
}
