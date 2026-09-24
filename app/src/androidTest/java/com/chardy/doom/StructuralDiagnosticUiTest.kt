package com.chardy.doom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic structures only. No Instagram launch, real samples, or diagnostic screenshots. */
class StructuralDiagnosticUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val clipboard get() = requireNotNull(rule.activity.getSystemService(ClipboardManager::class.java))
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val manualServices = mutableListOf<DoomAccessibilityService>()

    @Test fun mergedPackageManagerActivitiesContainOnlyMainActivityAsExportedActivity() {
        val packageInfo = rule.activity.packageManager.getPackageInfo(
            rule.activity.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        val activities = packageInfo.activities.orEmpty().toList()
        val forbiddenActivityNames = setOf(
            "androidx.compose.ui.tooling.PreviewActivity",
            "androidx.activity.ComponentActivity"
        )
        activities.forEach { activity ->
            assertFalse(forbiddenActivityNames.contains(activity.name))
            assertFalse(activity.targetActivity?.let(forbiddenActivityNames::contains) == true)
        }

        val exportedActivities = activities.filter { it.exported }.map { it.name }
        assertEquals(listOf("com.chardy.doom.MainActivity"), exportedActivities)
    }

    @Test fun debugIntentIsExplicitInternalNavigationWithoutPayload() {
        val intent = MainActivity.debugIntent(rule.activity)
        assertEquals(MainActivity.ACTION_OPEN_DEBUG, intent.action)
        assertEquals(rule.activity.packageName, intent.component?.packageName)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.data)
        assertTrue(intent.categories.isNullOrEmpty())
        assertNull(intent.extras)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    @Test fun coldDebugIntentConsumesOnceAndTargetsReportControls() {
        // Keep the nested cold Activity in its own task so closing it cannot close the
        // ActivityScenarioRule-owned Activity.
        val coldIntent = MainActivity.debugIntent(rule.activity).apply {
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val scenario = ActivityScenario.launch<MainActivity>(coldIntent)
        try {
            scenario.onActivity { activity ->
                assertTrue(activity.currentDebugRequestSequence() > 0L)
            }
            instrumentation.waitForIdleSync()
            assertTrue(device.wait(Until.hasObject(By.text("REVEAL LOCAL REPORT")), 5_000))
            assertTrue(device.wait(Until.hasObject(By.text("COPY REVIEWED REPORT")), 5_000))
            scenario.onActivity { activity ->
                val sequence = activity.currentDebugRequestSequence()
                assertFalse(activity.consumeDebugRequest(sequence))
            }
        } finally {
            scenario.close()
        }
        // The rule's warm Activity remains the test surface; the fixed action's UI route is
        // verified separately below without using a private report or an Activity authority.
        deliverNewIntent(MainActivity.debugIntent(rule.activity))
        assertDebugControlsDisplayed()
    }

    @Test fun warmRepeatedDebugIntentTargetsControlsAndMalformedReplacementDoesNotReplay() {
        seed()
        val launchIdentity = rule.runOnIdle { Intent(rule.activity.intent) }
        rule.runOnIdle {
            val before = rule.activity.currentDebugRequestSequence()
            deliverNewIntent(MainActivity.debugIntent(rule.activity))
            deliverNewIntent(MainActivity.debugIntent(rule.activity))
            assertEquals(before + 2L, rule.activity.currentDebugRequestSequence())
            deliverNewIntent(MainActivity.debugIntent(rule.activity).putExtra("unexpected", 1))
            assertEquals(before + 2L, rule.activity.currentDebugRequestSequence())
        }
        assertDebugControlsDisplayed()
        rule.runOnIdle {
            assertTrue(launchIdentity.filterEquals(rule.activity.intent))
            assertTrue(Observation.report != null)
            // The malformed replacement must not leave a second request queued.
            val sequence = rule.activity.currentDebugRequestSequence()
            assertFalse(rule.activity.consumeDebugRequest(sequence))
        }
    }

    @Test fun leavingDebugThenStartingWarmDebugRequestReanchorsControls() {
        seed()
        rule.onNodeWithText("Home").performClick()
        rule.onNodeWithText("Debug").performClick()
        deliverNewIntent(MainActivity.debugIntent(rule.activity))
        assertDebugControlsDisplayed()
    }

    @Test fun previewCancelAndConsumedDebugRequestSurviveRotationWithoutPersistingReport() {
        rule.onNodeWithText("Home").performClick()
        rule.onNodeWithText("Preview breathing reminder").performScrollTo().performClick()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Preview breathing reminder").performScrollTo().assertIsDisplayed()
        seed()
        val rotationScenario = ActivityScenario.launch<MainActivity>(MainActivity.debugIntent(rule.activity).apply {
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        })
        try {
            waitForDebugControls()
            device.findObject(By.text("REVEAL LOCAL REPORT")).click()
            instrumentation.waitForIdleSync()
            rotationScenario.onActivity { assertTrue(Observation.revealed) }
            var before: SanitizedStructuralReport? = null
            rotationScenario.onActivity { before = Observation.report }
            rotationScenario.recreate()
            waitForDebugControls()
            assertTrue(device.wait(Until.hasObject(By.text("DOOM-OWNED QUICK DEMO")), 5_000))
            rotationScenario.onActivity {
                assertSame(before, Observation.report)
                assertTrue(Observation.revealed)
            }
        } finally {
            rotationScenario.close()
        }
    }

    @Before fun reset() = rule.runOnIdle {
        Observation.setSessionTimerEnabled(rule.activity, true)
        RemovalTraceStore.process.clear()
        Observation.setGateConsent(rule.activity, false)
        Observation.accept(rule.activity, false)
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
    }

    private fun deliverNewIntent(intent: Intent) {
        instrumentation.callActivityOnNewIntent(rule.activity, intent)
    }
    private fun waitForDebugControls() {
        assertTrue(device.wait(Until.hasObject(By.text("REVEAL LOCAL REPORT")), 5_000))
        assertTrue(device.wait(Until.hasObject(By.text("COPY REVIEWED REPORT")), 5_000))
    }
    private fun assertDebugControlsDisplayed() {
        rule.onNodeWithText("REVEAL LOCAL REPORT").assertIsDisplayed()
        rule.onNodeWithText("COPY REVIEWED REPORT").assertIsDisplayed()
    }
    @Before fun openDebug() {
        rule.onNodeWithText("Debug").performClick()
    }
    @After fun cleanup() = rule.runOnIdle {
        manualServices.forEach { service -> runCatching { service.onDestroy() } }
        manualServices.clear()
        Observation.setSessionTimerEnabled(rule.activity, true)
        RemovalTraceStore.process = RemovalTraceStore()
        Observation.setGateConsent(rule.activity, false)
        Observation.accept(rule.activity, false)
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    @Test fun sessionTimerSwitchPersistsRealIndependentState() {
        rule.onNodeWithText("Home").performClick()
        val timerSwitch = rule.onNodeWithTag("instagram_session_timer_switch")
        timerSwitch.performScrollTo().assertIsDisplayed().assertIsOn().performClick().assertIsOff()
        shown("Disabled")
        rule.runOnIdle {
            val prefs = rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean(Observation.SESSION_TIMER_ENABLED_KEY, true))
            assertEquals(3, prefs.all.size)
        }
        timerSwitch.performClick().assertIsOn()
        shown("Enabled")
    }

    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()
    private fun shown(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    private fun sample(depth: Int = 0) = SanitizedStructuralReport.Builder().apply {
        add(StructuralNodeMetadata(
            position = StructuralNodePosition(depth = depth, bfsOrdinal = 0),
            resourceId = "com.instagram.android:id/feed_tab", className = "TextView",
            flags = StructuralBooleanMasks(
                known = 1L shl StructuralBooleanField.SELECTED.ordinal,
                value = 1L shl StructuralBooleanField.SELECTED.ordinal,
            ),
        ))
        add(StructuralNodeMetadata(
            position = StructuralNodePosition(index = 1, depth = depth, bfsOrdinal = 1),
            resourceId = "com.instagram.android:id/row_feed_media", className = "View",
        ))
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

    private fun track(service: DoomAccessibilityService): DoomAccessibilityService {
        manualServices += service
        return service
    }

    @Test fun removalTraceControlsShowTruthfulStatesAndRefreshIsNonDestructive() {
        var now = 90_000L
        RemovalTraceStore.process = RemovalTraceStore({ now })
        rule.runOnIdle {
            Observation.accept(rule.activity, true)
            Observation.setGateConsent(rule.activity, true)
            RemovalTraceStore.process.arm(now)
        }
        rule.onNodeWithText("REFRESH TRACE STATUS").performScrollTo().performClick()
        shown("REMOVAL TRACE · ARMED")
        shown("Trace armed · waiting for one eligible episode")
        rule.onNodeWithText("ARM NEXT REMOVAL TRACE").performScrollTo().assertIsEnabled()

        rule.runOnIdle {
            RemovalTraceStore.process.beginEligibleEpisode(now + 1L)
            RemovalTraceStore.process.record(now + 2L, RemovalTraceMark.SHOWN)
        }
        rule.onNodeWithText("REFRESH TRACE STATUS").performScrollTo().performClick()
        shown("REMOVAL TRACE · RECORDING")
        rule.onNodeWithText("Trace recording · one episode only").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ARM NEXT REMOVAL TRACE").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(RemovalTraceAvailability.RECORDING, RemovalTraceStore.process.availability(now + 2L))
        }

        rule.runOnIdle {
            now += 1L
            RemovalTraceStore.process.finish(now, RemovalTraceMark.DETACHED, detached = true)
        }
        rule.onNodeWithText("REFRESH TRACE STATUS").performScrollTo().performClick()
        shown("REMOVAL TRACE · AVAILABLE")
        rule.onNodeWithText("Trace available · process-local evidence").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ARM NEXT REMOVAL TRACE").performScrollTo().assertIsNotEnabled()
        val beforeRefresh = rule.runOnIdle { RemovalTraceStore.process.snapshot() }
        rule.onNodeWithText("REFRESH TRACE STATUS").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(beforeRefresh, RemovalTraceStore.process.snapshot()) }
    }
    private fun revealAndCopy() {
        tap("REVEAL LOCAL REPORT")
        tap("COPY REVIEWED REPORT")
        rule.runOnIdle { assertTrue(Observation.revealed); assertTrue(Observation.copied) }
    }

    @Suppress("DEPRECATION")
    private fun sendEvent(
        service: DoomAccessibilityService,
        packageName: String?,
        className: String? = null
    ) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            event.className = className
            service.onAccessibilityEvent(event)
        } finally { event.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun collectSyntheticRoot(service: DoomAccessibilityService, packageName: String?) {
        val root = AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            viewIdResourceName = "com.instagram.android:id/feed_tab"
            className = "android.widget.TextView"
            text = "PROHIBITED_TEXT_CANARY"
            contentDescription = "PROHIBITED_DESCRIPTION_CANARY"
            hintText = "PROHIBITED_HINT_CANARY"
            error = "PROHIBITED_ERROR_CANARY"
        }
        // Collector owns/recycles this node, including on mismatched roots.
        val startedElapsedMs = SystemClock.elapsedRealtime()
        DoomAccessibilityService::class.java.getDeclaredMethod(
            "collect", AccessibilityNodeInfo::class.java, StructuralCaptureContext::class.java
        ).apply { isAccessible = true }.invoke(
            service, root, StructuralCaptureContext.synthetic(startedElapsedMs = startedElapsedMs),
        )
    }

    @Test fun disclosureAndControlsNeverClaimProtectionOrAuthorizeActions() {
        shown("INSTAGRAM · NOT PROTECTED")
        shown("SANITIZED STRUCTURAL REPORT")
        shown("Structure changes with scrolling and content. The sanitized report is separate from a diagnostic shadow prediction; neither blocks, protects, or controls actions; the optional entry pause is default-off and fail-open.")
        shown("Shadow prediction: UNKNOWN")
        shown("Diagnostic only — may show an optional entry pause; never protects or controls Instagram.")
        rule.onNodeWithText(
            "Returning directly to Doom preserves the latest hidden report for local review.",
            substring = true
        ).performScrollTo().assertIsDisplayed()
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

    @Test fun reportOnlyEventCollectsWithGateConsentOff() {
        val service = track(DoomAccessibilityService())
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
            Observation.accept(rule.activity, true)
            Observation.setGateConsent(rule.activity, false)
            Observation.connected = true
            val root = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.instagram.android"
                viewIdResourceName = "com.instagram.android:id/report_only_event_root"
                className = "android.view.View"
            }
            val platform = object : OverlayPlatform {
                override fun isAttached(view: android.view.View) = false
                override fun removeImmediate(manager: android.view.WindowManager, view: android.view.View) = Unit
                override fun currentRoot() = root
                override fun eventRoot() = root
                override fun routeMessages(root: AccessibilityNodeInfo) = MessagesRouteResult.FAILED
                override fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
                override fun performHome() = false
                override fun openDebug() = false
            }
            DoomAccessibilityService::class.java.getDeclaredField("overlayPlatform")
                .apply { isAccessible = true }.set(service, platform)
            sendEvent(service, "com.instagram.android")
            assertFalse(Observation.gateConsent)
            assertNotNull(Observation.report)
            assertEquals(EntryGateState.OUTSIDE, Observation.entryGateState)
        }
    }

    @Test fun captureRollbackClearsAndTimeoutStopsBeforeEmittingTimedOutRow() {
        val service = track(DoomAccessibilityService())
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
            Observation.accept(rule.activity, true)
            Observation.connected = true
            val clock = DoomAccessibilityService::class.java
                .getDeclaredField("monotonicClock").apply { isAccessible = true }
            fun capture(now: Long) {
                clock.set(service, { now })
                val root = AccessibilityNodeInfo.obtain().apply {
                    packageName = "com.instagram.android"
                    viewIdResourceName = "com.instagram.android:id/timing_canary"
                }
                DoomAccessibilityService::class.java.getDeclaredMethod(
                    "collect", AccessibilityNodeInfo::class.java, StructuralCaptureContext::class.java
                ).apply { isAccessible = true }.invoke(
                    service, root, StructuralCaptureContext.synthetic(startedElapsedMs = 1_000L),
                )
                assertNull(Observation.report)
            }
            capture(999L)
            capture(11_001L)
        }
    }

    @Test fun entryGateConsentIsSeparateDefaultOffAndRevocable() {
        val gateConsent = rule.onNodeWithContentDescription("Diagnostic Instagram entry gate opt in")
        gateConsent.performScrollTo().assertIsOff()
        rule.runOnIdle {
            assertFalse(Observation.gateConsent)
            assertFalse(Observation.consent)
        }

        gateConsent.performClick().assertIsOn()
        rule.runOnIdle {
            assertTrue(Observation.gateConsent)
            assertFalse(Observation.consent)
        }
        gateConsent.performClick().assertIsOff()
        rule.runOnIdle {
            assertFalse(Observation.gateConsent)
            assertEquals(EntryGateState.OUTSIDE, Observation.entryGateState)
        }
    }

    @Test fun overlayButtonsDispatchOnlyTheirExplicitCallbacks() {
        rule.runOnIdle {
            var skipCalls = 0
            var leaveCalls = 0
            var debugCalls = 0
            val overlay = EntryGateOverlayViewFactory.create(
                rule.activity,
                onSkipToMessages = { skipCalls++ },
                onLeaveInstagram = { leaveCalls++ },
                onDebugReport = { debugCalls++ },
            )

            assertTrue(overlay.skipToMessages.performClick())
            assertEquals(1, skipCalls)
            assertEquals(0, leaveCalls)
            assertTrue(overlay.leaveInstagram.performClick())
            assertEquals(1, skipCalls)
            assertEquals(1, leaveCalls)
            assertTrue(overlay.debugReport.performClick())
            assertEquals(1, debugCalls)
            assertEquals("Breathe in", overlay.phaseLabel.text.toString())
        }
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

    @Test fun overlayStatusIsCompactAndOverlayCopyDoesNotRevealReport() {
        seed()
        rule.runOnIdle {
            val status = Observation.overlayDiagnosticStatus()
            assertEquals(OverlayReportStatus.CAPTURED, status.reportStatus)
            assertEquals(EntryGateSurface.FEED, status.surface)
            assertTrue(status.canCopyCurrentReport)
            assertFalse(Observation.revealed)
            assertEquals(OverlayCopyResult.COPIED, Observation.copyCurrentReportFromOverlay(rule.activity))
            assertFalse(Observation.revealed)
            assertFalse(Observation.copied)
            assertEquals(Observation.report!!.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
            Observation.connected = false
            assertEquals(OverlayReportStatus.UNAVAILABLE, Observation.overlayDiagnosticStatus().reportStatus)
            assertFalse(Observation.overlayDiagnosticStatus().canCopyCurrentReport)
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
            assertEquals(OverlayCopyResult.UNAVAILABLE, Observation.copyCurrentReportFromOverlay(rule.activity))
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun overlayCopyUsesCurrentReportAndDoesNotSetReviewedCopyStatus() {
        seed()
        rule.runOnIdle {
            val first = Observation.report!!.text
            assertEquals(OverlayCopyResult.COPIED, Observation.copyCurrentReportFromOverlay(rule.activity))
            assertFalse(Observation.copied)
            Observation.record(sample(1))
            assertFalse(Observation.copied)
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
            assertEquals(OverlayCopyResult.COPIED, Observation.copyCurrentReportFromOverlay(rule.activity))
            assertNotEquals(first, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(Observation.report!!.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun overlayCopyDeniesMissingOrThrowingClipboardAndStaleRevokedAction() {
        seed()
        rule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
            val missing = object : ContextWrapper(rule.activity) {
                override fun getSystemService(name: String): Any? = null
            }
            assertEquals(OverlayCopyResult.UNAVAILABLE, Observation.copyCurrentReportFromOverlay(missing))
            assertFalse(Observation.copied)
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
            val throwing = object : ContextWrapper(rule.activity) {
                override fun getSystemService(name: String): Any? = throw IllegalStateException("test")
            }
            assertEquals(OverlayCopyResult.UNAVAILABLE, Observation.copyCurrentReportFromOverlay(throwing))
            assertFalse(Observation.copied)
            Observation.connected = false
            assertEquals(OverlayCopyResult.UNAVAILABLE, Observation.copyCurrentReportFromOverlay(rule.activity))
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())

            val overlay = EntryGateOverlayViewFactory.create(rule.activity, {}, {}, {})
            assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
            overlay.dispose()
        }
    }

    @Test fun doomEventsPreserveReportButForeignOrMissingRootInvalidatesAllReportState() {
        val service = track(DoomAccessibilityService())
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
        }
        seed()
        revealAndCopy()
        val first = rule.runOnIdle { Observation.report }
        rule.runOnIdle {
            sendEvent(service, rule.activity.packageName, MainActivity::class.java.name)
            assertSame(first, Observation.report)
            assertTrue(Observation.revealed)
            assertTrue(Observation.copied)
        }
        listOf("com.example.foreign", null).forEach { packageName ->
            seed(1)
            revealAndCopy()
            rule.runOnIdle { sendEvent(service, packageName) }
            assertEmpty()
            assertActionsDisabled()
            rule.runOnIdle { assertTrue(Observation.connected) }
        }
        seed(2)
        revealAndCopy()
        rule.runOnIdle {
            sendEvent(service, "com.instagram.android")
        }
        assertEmpty()
        assertActionsDisabled()
    }

    @Test fun onlyMainActivityWindowEventQualifiesAsDirectReturn() {
        val service = track(DoomAccessibilityService())
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
        }
        seed()
        val first = rule.runOnIdle { Observation.report }
        rule.runOnIdle {
            sendEvent(service, rule.activity.packageName, "android.widget.LinearLayout")
            assertSame(first, Observation.report)
            sendEvent(service, rule.activity.packageName, MainActivity::class.java.name)
            assertSame(first, Observation.report)
            assertEquals(EntryGateState.OUTSIDE, Observation.entryGateState)
        }
    }

    @Test fun strongerForeignCleanupClearsPendingMainActivityReturnMarker() {
        val service = track(DoomAccessibilityService())
        rule.runOnIdle {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, rule.activity.applicationContext)
            val marker = DoomAccessibilityService::class.java
                .getDeclaredField("mainActivityReturnObserved").apply { isAccessible = true }
            marker.setBoolean(service, true)
            sendEvent(service, "com.example.foreign")
            assertFalse(marker.getBoolean(service))
        }
    }

    @Test fun delayedInstagramEventWithNullActiveRootInvalidatesReport() {
        seed()
        revealAndCopy()
        // An unbound service has no active root; use the real event entry point.
        rule.runOnIdle { sendEvent(track(DoomAccessibilityService()), "com.instagram.android") }
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
                .clear().putBoolean("accepted", true).putBoolean("structural_fingerprints_v1", true)
                .putBoolean("sanitized_structural_report_v1", true).commit()
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

    @Test fun v2ConsentIsExplicitAndIndependentFromRemovedV1Authorization() {
        rule.runOnIdle {
            val prefs = rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE)
            prefs.edit().clear()
                .putBoolean("sanitized_structural_report_v1", true)
                .putBoolean("instagram_diagnostic_entry_gate_v1", true)
                .commit()
            Observation.load(rule.activity)
            assertFalse(Observation.consent)
            assertTrue(Observation.gateConsent)

            prefs.edit().putBoolean("sanitized_structural_report_v2", false).commit()
            Observation.load(rule.activity)
            assertFalse(Observation.consent)
            Observation.accept(rule.activity, true)
            assertTrue(Observation.consent)
            assertTrue(prefs.getBoolean("sanitized_structural_report_v2", false))
            Observation.accept(rule.activity, false)
            assertFalse(Observation.consent)
            assertFalse(prefs.getBoolean("sanitized_structural_report_v2", true))
        }
    }

    @Test fun interruptionDisconnectAndDestructionClearEverythingAndRejectDelayedCallbacks() {
        val service = track(DoomAccessibilityService())
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
        val service = track(DoomAccessibilityService())
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
                assertFalse(Observation.report!!.text.contains("PROHIBITED_TEXT_CANARY"))
                assertFalse(Observation.report!!.text.contains("PROHIBITED_DESCRIPTION_CANARY"))
                assertFalse(Observation.report!!.text.contains("PROHIBITED_HINT_CANARY"))
                assertFalse(Observation.report!!.text.contains("PROHIBITED_ERROR_CANARY"))
                assertFalse(Observation.revealed)
                assertFalse(Observation.copied)
            }
            shown("Observer connected · mapping unverified")
        } finally { rule.runOnIdle { service.onDestroy() } }
    }

    @Test fun onlyConsentBooleansArePersistedAndLoadingWithoutConsentClearsMemory() {
        seed()
        revealAndCopy()
        rule.runOnIdle {
            val prefs = rule.activity.getSharedPreferences("consent", Context.MODE_PRIVATE)
            assertEquals(
                mapOf(
                    "sanitized_structural_report_v2" to true,
                    "instagram_diagnostic_entry_gate_v1" to false,
                    "instagram_session_timer_enabled_v1" to true
                ),
                prefs.all
            )
            prefs.edit().clear().commit()
            Observation.load(rule.activity)
        }
        assertEmpty()
        assertActionsDisabled()
    }
}
