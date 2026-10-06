package com.chardy.doom

import android.content.Context
import android.content.res.Configuration
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Field
import java.util.Collections

/** Runtime action-order tests use the service's production platform boundary with fake side effects. */
class EntryGateServiceActionTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manualServices = mutableListOf<DoomAccessibilityService>()
    private val manualFixtures = mutableListOf<Fixture>()

    @Test fun timerPreferenceDismissalAndSafetyVetoAreIndependentOfGateAuthority() {
        rule.scenario.onActivity { activity ->
            val service = track(DoomAccessibilityService())
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            Observation.connected = true
            Observation.setSessionTimerEnabled(activity, false)
            assertTrue(invokeResult<Boolean>(service, "timerConsentAllowed"))
            assertFalse(invokeResult<Boolean>(service, "timerSpecificAllowed"))
            field(service, "timerDismissedThisVisit").setBoolean(service, true)
            assertTrue(invokeResult<Boolean>(service, "timerConsentAllowed"))
            field(service, "timerSafetyVeto").setBoolean(service, true)
            Observation.setSessionTimerEnabled(activity, true)
            assertFalse(invokeResult<Boolean>(service, "timerSpecificAllowed"))
        }
    }

    private enum class RootBehavior {
        INSTAGRAM,
        MESSAGING,
        NULL_PACKAGE,
        FOREIGN,
        SYSTEM_UI,
        RECOGNIZED_IME,
        DOOM,
        MISSING,
        THROW,
        PACKAGE_THROW,
        REPLACE_TOKEN_DURING_READ,
        REPLACE_TICKET_DURING_READ,
        REVOKE_CONSENT,
        REVOKE_GATE_CONSENT,
        REVOKE_CONNECTION,
    }

    private class FakePlatform(
        attached: Boolean,
        @Volatile var detachOnRemove: Boolean = true,
        var rootBehavior: RootBehavior = RootBehavior.INSTAGRAM,
        var routeResult: MessagesRouteResult = MessagesRouteResult.CLICKED,
        val stateAtRoute: MutableList<EntryGateState>,
    ) : OverlayPlatform {
        @Volatile var removeAttempts = 0
        @Volatile var currentRootCalls = 0
        @Volatile var recycledRoots = 0
        @Volatile var recycleThrows = false
        @Volatile var routeCalls = 0
        @Volatile var routeThrows = false
        @Volatile var homeCalls = 0
        @Volatile var homeResult = true
        @Volatile var copiedCandidate: SanitizedStructuralReport? = null
        var copyHook: (SanitizedStructuralReport) -> OverlayCopyResult = { candidate ->
            copiedCandidate = candidate
            OverlayCopyResult.COPIED
        }
        val platformCalls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var revokeInsideRoot: () -> Unit
        var rootReadHook: () -> Unit = {}
        var beforeRouteReturns: () -> Unit = {}
        var afterPackageRead: () -> Unit = {}

        var attachmentThrows = false
        var heldView: View? = null
        var removeScript: ((View) -> Unit)? = null
        private val attachments = java.util.IdentityHashMap<View, Boolean>()
        private val pendingRegistrations = Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        private var initialAttachment = attached
        var attached: Boolean
            get() = attachments.values.any { it } || (attachments.isEmpty() && initialAttachment)
            set(value) {
                initialAttachment = value
                attachments.keys.toList().forEach { attachments[it] = value }
                pendingRegistrations.clear()
            }
        fun attach(view: View) {
            check(attachments[view] != true && view !in pendingRegistrations) { "already added" }
            attachments[view] = true
        }
        fun registerPending(view: View) {
            check(!isRegistered(view)) { "already added" }
            attachments[view] = false
            pendingRegistrations += view
        }
        fun register(view: View, attached: Boolean) { attachments[view] = attached }
        override fun isAttached(view: View): Boolean {
            if (attachmentThrows) throw IllegalStateException("unknown attachment")
            return attachments[view] ?: false
        }
        override fun isRegistered(view: View): Boolean = isAttached(view) || view in pendingRegistrations

        override fun removeImmediate(manager: WindowManager, view: View) {
            platformCalls += "removeImmediate"
            removeAttempts++
            removeScript?.let { it(view); return }
            if (detachOnRemove && view !== heldView) {
                attachments[view] = false
                pendingRegistrations -= view
            }
        }

        override fun currentRoot(): AccessibilityNodeInfo? {
            platformCalls += "currentRoot"
            currentRootCalls++
            when (rootBehavior) {
                RootBehavior.MISSING -> return null
                RootBehavior.THROW -> throw IllegalStateException("root unavailable")
                else -> Unit
            }
            when (rootBehavior) {
                RootBehavior.REVOKE_CONSENT,
                RootBehavior.REVOKE_GATE_CONSENT,
                RootBehavior.REVOKE_CONNECTION -> revokeInsideRoot()
                RootBehavior.REPLACE_TOKEN_DURING_READ,
                RootBehavior.REPLACE_TICKET_DURING_READ -> rootReadHook()
                else -> Unit
            }
            return AccessibilityNodeInfo.obtain().apply {
                packageName = when (rootBehavior) {
                    RootBehavior.NULL_PACKAGE -> null
                    RootBehavior.SYSTEM_UI -> "com.android.systemui"
                    RootBehavior.RECOGNIZED_IME -> "com.example.keyboard"
                    RootBehavior.FOREIGN -> "com.example.foreign"
                    RootBehavior.DOOM -> "com.chardyb.doom"
                    else -> "com.instagram.android"
                }
                if (rootBehavior == RootBehavior.MESSAGING) {
                    viewIdResourceName = "com.instagram.android:id/message_list"
                }
            }
        }

        override fun readRootPackage(root: AccessibilityNodeInfo): String? {
            if (rootBehavior == RootBehavior.PACKAGE_THROW) throw IllegalStateException("package unavailable")
            val packageName = when {
                StructuralSanitizer.isExactAscii(root.packageName, "com.instagram.android") -> "com.instagram.android"
                StructuralSanitizer.isExactAscii(root.packageName, "com.chardyb.doom") -> "com.chardyb.doom"
                StructuralSanitizer.isExactAscii(root.packageName, "com.android.systemui") -> SAFE_SYSTEM_UI_PACKAGE
                StructuralSanitizer.isExactAscii(root.packageName, "com.example.keyboard") -> SAFE_RECOGNIZED_IME_PACKAGE
                root.packageName == null -> null
                else -> "__foreign__"
            }
            afterPackageRead()
            return packageName
        }

        override fun recycleRoot(root: AccessibilityNodeInfo) {
            recycledRoots++
            root.recycle()
            if (recycleThrows) throw IllegalStateException("recycle unavailable")
        }

        override fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult {
            platformCalls += "routeMessages"
            routeCalls++
            if (routeThrows) throw IllegalStateException("route unavailable")
            stateAtRoute += stateReader()
            beforeRouteReturns()
            return routeResult
        }

        override fun performHome(): Boolean {
            platformCalls += "performHome"
            homeCalls++
            return homeResult
        }


        lateinit var stateReader: () -> EntryGateState
    }

    private data class Fixture(
        val service: DoomAccessibilityService,
        val platform: FakePlatform,
        val ticket: GateTicket,
        val token: OverlayCallbackToken,
        val view: View,
        val ui: EntryGateOverlayUi,
        val activity: MainActivity,
    )

    private enum class InstallMode { SUCCEED, FAIL, REJECT_CONNECTION }

    private data class FreshService(
        val service: DoomAccessibilityService,
        val platform: FakePlatform,
        val now: LongArray,
        var installMode: InstallMode = InstallMode.SUCCEED,
        var installs: Int = 0,
        var disableCalls: Int = 0,
        val added: MutableList<Pair<View, WindowManager.LayoutParams>> = mutableListOf(),
    )

    private data class Outcome(
        val attached: Boolean,
        val removes: Int,
        val roots: Int,
        val recycledRoots: Int,
        val state: EntryGateState,
        val routes: Int,
        val homes: Int,
        val action: RemovalTraceAction,
        val calls: List<String>,
    )

    @After fun cleanUp() {
        rule.scenario.onActivity {
            manualFixtures.forEach { fixture ->
                runCatching { fixture.ui.dispose() }
                fixture.platform.attached = false
                runCatching { fixture.service.onDestroy() }
            }
            manualServices.forEach { service -> runCatching { service.onDestroy() } }
            Observation.setGateConsent(it, false)
            Observation.accept(it, false)
            Observation.setSessionTimerEnabled(it, true)
            Observation.updateReminderSettings(it, ReminderSettings())
            Observation.connected = false
            Observation.clear()
            RemovalTraceStore.process.clear()
        }
        DoomAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
        manualFixtures.clear()
        manualServices.clear()
        instrumentation.waitForIdleSync()
    }

    @Test fun skipNeverRoutesWhileAttachedAndRoutesExactlyOnceAfterDetachAndBypass() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        waitFor { fixture.platform.removeAttempts > 0 }
        assertEquals(0, fixture.platform.routeCalls)

        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { fixture.platform.routeCalls == 1 }
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(listOf(EntryGateState.BYPASSED), fixture.platform.stateAtRoute)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertTrue(gate(fixture.service).cooldownActive())
    }

    @Test fun captureDebugDetachesCollectsFreshThenBypassesAndCopiesExactlyOnce() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        val copyCalls = java.util.concurrent.atomic.AtomicInteger()
        rule.scenario.onActivity {
            field(fixture.service, "captureEventProvenance").set(
                fixture.service,
                StructuralCaptureContext.synthetic().event,
            )
            field(fixture.service, "copyHook").set(
                fixture.service,
                { candidate: SanitizedStructuralReport ->
                    // Fake assignment happens before the awaited counter observes completion.
                    val result = fixture.platform.copyHook(candidate)
                    copyCalls.incrementAndGet()
                    result
                },
            )
        }
        request(fixture.service, OverlayRemovalAction.CAPTURE_DEBUG, fixture.token)
        assertEquals(0, copyCalls.get())

        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { copyCalls.get() == 1 }

        assertTrue(fixture.platform.copiedCandidate != null)
        assertEquals(2, fixture.platform.currentRootCalls)
        // The collector owns its root; only the separate attribution root uses this seam.
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(0, fixture.platform.homeCalls)
    }

    @Test fun unavailableDebugCopyLeavesNoReportStateAndNeverRetries() {
        val fixture = fixture()
        val copyCalls = java.util.concurrent.atomic.AtomicInteger()
        rule.scenario.onActivity {
            val prior = SanitizedStructuralReport.Builder().apply {
                add(StructuralNodeMetadata(
                    position = StructuralNodePosition(),
                    resourceId = "com.instagram.android:id/prior",
                    className = "View",
                ))
            }.build()!!
            Observation.record(prior)
            field(fixture.service, "captureEventProvenance").set(
                fixture.service,
                StructuralCaptureContext.synthetic().event,
            )
            field(fixture.service, "copyHook").set(
                fixture.service,
                { _: SanitizedStructuralReport ->
                    copyCalls.incrementAndGet()
                    OverlayCopyResult.UNAVAILABLE
                },
            )
        }
        request(fixture.service, OverlayRemovalAction.CAPTURE_DEBUG, fixture.token)
        waitFor { copyCalls.get() == 1 }
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertNull(Observation.report)
        assertFalse(Observation.revealed)
        assertFalse(Observation.copied)
        assertFalse(gate(fixture.service).cooldownActive())
        assertEquals(1, copyCalls.get())
    }

    @Test fun disablingRemindersRemovesLiveOverlayWithoutCooldownCredit() {
        val fixture = fixture(attached = true, detachOnRemove = true)

        rule.scenario.onActivity {
            Observation.updateReminderSettings(it, ReminderSettings(enabled = false))
        }
        waitFor { !fixture.platform.attached }

        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(0, fixture.platform.homeCalls)
    }

    @Test fun alreadySelectedMessagesResultArmsCooldownOnlyAfterRoutingReturns() {
        val fixture = fixture(routeResult = MessagesRouteResult.ALREADY_SELECTED)
        fixture.platform.beforeRouteReturns = {
            assertFalse(gate(fixture.service).cooldownActive())
        }
        assertFalse(gate(fixture.service).cooldownActive())
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.routeCalls)
        assertTrue(gate(fixture.service).cooldownActive())
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun failedRouteRetiresDetachedEpisodeWithoutCooldown() {
        val fixture = fixture(routeResult = MessagesRouteResult.FAILED)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        waitFor { fixture.platform.routeCalls == 1 }
        assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
        assertFalse(gate(fixture.service).observeInstagram(1L, fixture.ticket))
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(2, fixture.platform.recycledRoots)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun missingRootFailsClosedWithoutRouteOrRootRecycle() {
        val fixture = fixture(rootBehavior = RootBehavior.MISSING)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(0, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun currentRootExceptionFailsClosedWithoutRouteOrRootRecycle() {
        val fixture = fixture(rootBehavior = RootBehavior.THROW)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(0, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun obtainedNullPackageRootIsNotMissingAndIsRecycledOnceWithoutRoute() {
        val fixture = fixture(rootBehavior = RootBehavior.NULL_PACKAGE)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun routeExceptionRetiresEpisodeAndRecyclesBothPackageRoots() {
        val fixture = fixture()
        fixture.platform.routeThrows = true
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(2, fixture.platform.currentRootCalls)
        assertEquals(2, fixture.platform.recycledRoots)
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun consentRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_CONSENT)
        fixture.platform.revokeInsideRoot = { Observation.accept(fixture.activity, false) }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun gateConsentRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_GATE_CONSENT)
        fixture.platform.revokeInsideRoot = { Observation.setGateConsent(fixture.activity, false) }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun connectionRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_CONNECTION)
        fixture.platform.revokeInsideRoot = { Observation.connected = false }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun closingForeignEventVetoesPendingHomeWithoutReadingARoot() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
        }
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        rule.scenario.onActivity {
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.example.foreign")
            fixture.platform.detachOnRemove = true
        }
        waitFor { !fixture.platform.attached }

        assertEquals(0, fixture.platform.homeCalls)
        assertEquals(0, fixture.platform.currentRootCalls)
        assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
        assertTrue(requireNotNull(RemovalTraceStore.process.snapshot()).records.any {
            it.mark == RemovalTraceMark.ACTION_VETOED && it.action == RemovalTraceAction.HOME
        })
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun staleSkipTokenCannotReachMessagesRoute() {
        val fixture = fixture(attached = true, detachOnRemove = true)
        rule.scenario.onActivity {
            val guard = field(fixture.service, "callbackGuard").get(fixture.service) as OverlayCallbackGuard
            val replacement = guard.open(fixture.ticket)
            field(fixture.service, "overlayToken").set(fixture.service, replacement)
            requestOverlayRemovalWithToken(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        }
        instrumentation.waitForIdleSync()
        assertTrue(fixture.platform.attached)
        assertEquals(0, fixture.platform.routeCalls)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun staleCompletionAndRetryFromOverlayACannotRemoveOrRouteOverlayB() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        val oldToken = fixture.token
        request(fixture.service, OverlayRemovalAction.COMPLETE, oldToken)
        lateinit var newView: View
        lateinit var newEpisode: GateOverlayWindows
        lateinit var newToken: OverlayCallbackToken
        var oldRemovalAttempts = 0
        rule.scenario.onActivity { activity ->
            val guard = field(fixture.service, "callbackGuard").get(fixture.service) as OverlayCallbackGuard
            newView = View(activity)
            fixture.platform.register(newView, true)
            newToken = guard.open(fixture.ticket)
            (field(fixture.service, "removalLoop").get(fixture.service) as OverlayRemovalRetryLoop).cancel()
            fixture.platform.register(fixture.view, false)
            newEpisode = GateOverlayWindows(newToken, fixture.ui).apply {
                installing = false
                records += OwnedOverlayWindow(newView,
                    activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager, GateWindowRole.VISUAL,
                    GateOverlayWindows.parameters(GateWindowDescriptor(GateWindowRole.VISUAL, GateWindowRect(0, 0, 100, 100))),
                    fixture.platform, { _, root, _ -> fixture.platform.attach(root) }, { _, _, _ -> }).also { it.addAttempted = true }
            }
            field(fixture.service, "gateWindows").set(fixture.service, newEpisode)
            field(fixture.service, "overlayToken").set(fixture.service, newToken)
            oldRemovalAttempts = fixture.platform.removeAttempts
            assertTrue(oldRemovalAttempts > 0)
            // Exercise the queued retry boundary deterministically on the main thread.
            invoke(fixture.service, "attemptOverlayRemoval", oldToken)
            requestOverlayRemovalWithToken(fixture.service, OverlayRemovalAction.COMPLETE, oldToken)
        }
        instrumentation.waitForIdleSync()
        assertSame(newEpisode, field(fixture.service, "gateWindows").get(fixture.service))
        assertSame(newToken, field(fixture.service, "overlayToken").get(fixture.service))
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(oldRemovalAttempts, fixture.platform.removeAttempts)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun safetyInterruptAndReacceptCannotReleasePendingSkip() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity {
            fixture.service.onInterrupt()
            Observation.setGateConsent(it, true)
        }
        rule.scenario.onActivity {
            fixture.platform.attached = false
            runRemovalRetry(fixture.service, fixture.token)
        }
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun consentRevocationDuringPendingSkipVetoesRouteEvenAfterReacceptance() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity {
            Observation.setGateConsent(it, false)
            invoke(fixture.service, "cancelAndBypass")
            Observation.setGateConsent(it, true)
        }
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { !fixture.platform.attached }
        assertEquals(0, fixture.platform.routeCalls)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun disconnectDuringPendingSkipVetoesRouteAndClearsConnection() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity { fixture.service.onUnbind(null) }
        rule.scenario.onActivity {
            fixture.platform.attached = false
            runRemovalRetry(fixture.service, fixture.token)
        }
        assertEquals(0, fixture.platform.routeCalls)
        assertFalse(Observation.connected)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun closingHomeTapCannotReplaceAcceptedSkip() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        assertEquals(0, fixture.platform.homeCalls)
        assertEquals(0, fixture.platform.routeCalls)
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { fixture.platform.routeCalls == 1 }
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(0, fixture.platform.homeCalls)
        assertTrue(fixture.platform.platformCalls.indexOf("removeImmediate") <
            fixture.platform.platformCalls.indexOf("routeMessages"))
        assertTrue(gate(fixture.service).cooldownActive())
    }

    @Test fun directDoomReturnBeatsSkipAndPreservesReportWithoutRoute() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity {
            field(fixture.service, "mainActivityReturnObserved").setBoolean(fixture.service, true)
        }
        request(fixture.service, OverlayRemovalAction.PRESERVE_REPORT, fixture.token)
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { !fixture.platform.attached }
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun messagesRouteRejectsForeignOrUnattributedRootAfterDetach() {
        // This is a Messages-only foreground recheck; HOME has a separate release path.
        listOf(RootBehavior.FOREIGN, RootBehavior.NULL_PACKAGE).forEach { behavior ->
            val fixture = fixture(rootBehavior = behavior)
            request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
            waitFor { !fixture.platform.attached }
            assertEquals(0, fixture.platform.routeCalls)
            assertEquals(0, fixture.platform.homeCalls)
            assertEquals(1, fixture.platform.recycledRoots)
            assertFalse(gate(fixture.service).cooldownActive())
        }
    }

    @Test fun serviceCooldownSurvivesDetachAndReentryUntilExactBoundary() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                startBubble(fresh)
                assertTrue(gate(fresh.service).cooldownActive())
                val oldTicket = field(fresh.service, "ticket").get(fresh.service)
                fresh.now[0] = 79_999L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(oldTicket, field(fresh.service, "ticket").get(fresh.service))
                assertEquals(2, fresh.installs)
                fresh.now[0] = 80_000L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(3, fresh.installs)
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun preAdmissionMessagingNeverInstallsOrArmsOrReleasesActions() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                fresh.platform.rootBehavior = RootBehavior.MESSAGING
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")

                assertEquals(0, fresh.installs)
                assertFalse(fresh.platform.attached)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(0, fresh.platform.homeCalls)
                assertEquals(0, fresh.platform.routeCalls)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun visibleGateMessagingWaitsForPhysicalDetachAndClosingEventsDoNotRecollect() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(fresh.platform.attached)
                var reads = 0
                field(fresh.service, "collectorReadHook").set(fresh.service, { reads++ })
                fresh.platform.detachOnRemove = false
                fresh.platform.rootBehavior = RootBehavior.MESSAGING

                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android")
                assertTrue(fresh.platform.attached)
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                assertTrue(reads > 0)
                val readsAtClosing = reads
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android")
                assertEquals(readsAtClosing, reads)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(0, fresh.platform.homeCalls)
                assertEquals(0, fresh.platform.routeCalls)

                fresh.platform.attached = false
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                runRemovalRetry(fresh.service, token)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun homeAfterDisplayedOverlayDoesNotArmCooldownAndAllowsReentry() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.HOME, token)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(1, fresh.platform.homeCalls)
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(2, fresh.installs)
                assertTrue(fresh.platform.attached)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun failedHomeKeepsBypassedEpisodeUntilAConfirmedForeignTransition() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                fresh.platform.homeResult = false
                requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.HOME, token)
                assertFalse(fresh.platform.attached)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                assertEquals(1, fresh.installs)

                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(1, fresh.installs)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)

                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                fresh.platform.homeResult = true
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(2, fresh.installs)
                assertTrue(fresh.platform.attached)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun failedServiceInstallDoesNotArmCooldown() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L, InstallMode.FAIL)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertFalse(gate(fresh.service).cooldownActive())
                assertFalse(fresh.platform.attached)
                assertEquals(0, fresh.platform.homeCalls)
                assertEquals(0, fresh.platform.routeCalls)
                fresh.installMode = InstallMode.SUCCEED
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(1, fresh.installs)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(2, fresh.installs)
                assertTrue(fresh.platform.attached)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun revokedAdmissionDoesNotArmCooldown() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L, InstallMode.REJECT_CONNECTION)
            try {
                // Model admission revocation without setGateConsent's nested cleanup: the
                // synthetic installer makes the wired service disconnected before the
                // post-install overlayShown/admission checks run.
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertFalse(fresh.platform.attached)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(0, fresh.platform.homeCalls)
                assertEquals(0, fresh.platform.routeCalls)
                Observation.connected = true
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                fresh.installMode = InstallMode.SUCCEED
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(fresh.platform.attached)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun recreatedServiceStartsWithoutCooldown() {
        rule.scenario.onActivity { activity ->
            val first = freshService(activity, 10_000L)
            var second: FreshService? = null
            try {
                sendEvent(first.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                first.now[0] += field(gate(first.service), "activeDurationMs").getLong(gate(first.service))
                (field(first.service, "completion").get(first.service) as Runnable).run()
                assertTrue(gate(first.service).cooldownActive())
                destroyFresh(first)
                second = freshService(activity, 10_000L)
                sendEvent(second.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(1, second.installs)
                assertTrue(second.platform.attached)
                assertFalse(gate(second.service).cooldownActive())
            } finally { second?.let(::destroyFresh) }
        }
    }

    @Test fun watchdogUsesExactShownAtBoundaryAndVetoesForeignRoot() {
        val fixture = fixture()
        val shownAt = 1_000L
        rule.scenario.onActivity {
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(shownAt)
            assertEquals(
                OverlayForegroundDecision.KEEP_UNCERTAIN,
                watchdog.observe(shownAt + 149L, null, verifiedDoomReturn = false),
            )
            assertEquals(
                OverlayForegroundDecision.FAIL_OPEN,
                watchdog.observe(shownAt + 150L, null, verifiedDoomReturn = false),
            )
            assertEquals(
                OverlayForegroundDecision.FAIL_OPEN,
                watchdog.observe(shownAt + 151L, "com.example.foreign", verifiedDoomReturn = false),
            )
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun actualAccessibilityEventPathsAreIdenticalWithTraceArmedOrUnarmed() {
        fun run(trace: Boolean): Outcome {
            val fixture = fixture(attached = true, detachOnRemove = true)
            rule.scenario.onActivity {
                val now = android.os.SystemClock.elapsedRealtime()
                if (trace) {
                    RemovalTraceStore.process.arm(now)
                    RemovalTraceStore.process.beginEligibleEpisode(now)
                } else {
                    RemovalTraceStore.process.clear()
                }
                val watchdog = field(fixture.service, "foregroundWatchdog")
                    .get(fixture.service) as OverlayForegroundWatchdog
                watchdog.reset(now)
                assertEquals(
                    OverlayForegroundDecision.KEEP,
                    watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false),
                )
                sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    "com.example.foreign")
            }
            instrumentation.waitForIdleSync()
            return outcome(fixture.service, fixture.platform)
        }

        val unarmed = run(false)
        val armed = run(true)
        assertEquals(unarmed, armed)
        assertTrue(unarmed.attached)
        assertEquals(EntryGateState.GATING, unarmed.state)
        assertEquals(1, unarmed.roots)
        assertEquals(1, unarmed.recycledRoots)
        assertTrue(armed.attached)
        assertEquals(EntryGateState.GATING, armed.state)
        assertEquals(1, armed.roots)
        assertEquals(1, armed.recycledRoots)
    }

    @Test fun actualInstagramEventMissingForeignAndThrowRootsMatchArmedAndUnarmed() {
        listOf(RootBehavior.MISSING, RootBehavior.FOREIGN, RootBehavior.THROW).forEach { behavior ->
            val unarmed = runEventOutcome(trace = false, behavior)
            val armed = runEventOutcome(trace = true, behavior)
            assertEquals(unarmed, armed)
            assertEquals(EntryGateState.BYPASSED, armed.state)
            assertEquals(RemovalTraceAction.BYPASS, armed.action)
            val snapshot = requireNotNull(RemovalTraceStore.process.snapshot())
            val expectedMark = when (behavior) {
                RootBehavior.MISSING -> RemovalTraceMark.EVENT_ROOT_MISSING
                RootBehavior.FOREIGN -> RemovalTraceMark.EVENT_ROOT_MISMATCH
                RootBehavior.THROW -> RemovalTraceMark.EVENT_FAILURE
                else -> error("unexpected root behavior")
            }
            assertTrue(snapshot.records.any { it.mark == expectedMark })
            if (behavior == RootBehavior.THROW) {
                assertEquals(
                    RemovalTraceRoot.READ_FAILURE,
                    snapshot.records.last { it.mark == RemovalTraceMark.EVENT_FAILURE }.root,
                )
            }
        }
    }

    @Test fun actualWatchdogForeignTickMatchesArmedAndUnarmed() {
        val unarmed = runWatchdogOutcome(trace = false)
        val armed = runWatchdogOutcome(trace = true)
        assertEquals(unarmed, armed)
        assertEquals(RemovalTraceAction.BYPASS, armed.action)
        assertTrue(requireNotNull(RemovalTraceStore.process.snapshot()).records.any {
            it.mark == RemovalTraceMark.WATCHDOG_FOREIGN
        })
    }

    @Test fun actualEventInstallAndHomeMatchesArmedAndUnarmedAndPreservesClipboard() {
        val unarmed = runInstallHomeOutcome(trace = false)
        val armed = runInstallHomeOutcome(trace = true)
        assertEquals(unarmed, armed)
        assertEquals(RemovalTraceAction.HOME, armed.action)
        assertEquals(1, armed.homes)
        assertTrue(requireNotNull(RemovalTraceStore.process.snapshot()).records.any {
            it.mark == RemovalTraceMark.ACTION_RELEASED && it.action == RemovalTraceAction.HOME
        })
    }

    @Test fun actualAccessibilityEventVisibleGateAndOwnEventGuardsRemainFailOpen() {
        listOf(RootBehavior.MISSING, RootBehavior.NULL_PACKAGE, RootBehavior.FOREIGN, RootBehavior.THROW)
            .forEach { behavior ->
                val fixture = fixture(attached = true, rootBehavior = behavior)
                rule.scenario.onActivity {
                    sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        "com.instagram.android")
                }
                assertTrue(fixture.platform.attached)
                // A visible gate now samples each fresh Instagram event so a confirmed
                // messaging surface can trigger detach-confirmed bypass. Uncertain roots
                // still fail open and leave the gate attached.
                assertEquals(1, fixture.platform.currentRootCalls)
                assertEquals(0, fixture.platform.removeAttempts)
                assertEquals(0, fixture.platform.routeCalls)
                assertEquals(EntryGateState.GATING, gate(fixture.service).state)
            }

        val own = fixture(attached = true)
        rule.scenario.onActivity {
            sendEvent(own.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.chardyb.doom")
        }
        assertTrue(own.platform.attached)
        assertEquals(0, own.platform.removeAttempts)
    }

    @Test fun actualAccessibilityCooldownSamplesPackageWithoutTicketOrCollection() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                startBubble(fresh)
                val ticket = field(fresh.service, "ticket").get(fresh.service)
                val report = requireNotNull(Observation.report)
                val roots = fresh.platform.currentRootCalls
                fresh.platform.rootBehavior = RootBehavior.THROW
                repeat(3) {
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android")
                }
                assertEquals(roots + 3, fresh.platform.currentRootCalls)
                assertEquals(2, fresh.installs)
                assertEquals(ticket, field(fresh.service, "ticket").get(fresh.service))
                assertSame(report, Observation.report)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun installedGateSamplesRepeatedInstagramRootsWhileVisibleButNotClosing() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val view = requireNotNull(field(fresh.service, "gateWindows").get(fresh.service))
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                requireNotNull(Observation.report)
                val completion = field(fresh.service, "completion").get(fresh.service)
                fun repeatedEvents(expectReads: Boolean) {
                    listOf(RootBehavior.INSTAGRAM, RootBehavior.MISSING, RootBehavior.FOREIGN,
                        RootBehavior.NULL_PACKAGE, RootBehavior.THROW).forEach { behavior ->
                        val roots = fresh.platform.currentRootCalls
                        val report = Observation.report
                        fresh.platform.rootBehavior = behavior
                        repeat(3) {
                            sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android")
                            sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                        }
                        assertEquals(roots + if (expectReads) 6 else 0, fresh.platform.currentRootCalls)
                        assertSame(view, field(fresh.service, "gateWindows").get(fresh.service))
                        assertEquals(token, field(fresh.service, "overlayToken").get(fresh.service))
                        assertEquals(token.ticket, field(fresh.service, "ticket").get(fresh.service))
                        if (!expectReads || behavior != RootBehavior.INSTAGRAM) {
                            assertSame(report, Observation.report)
                        }
                        assertTrue(fresh.platform.attached)
                        assertEquals(1, fresh.installs)
                        assertFalse(gate(fresh.service).cooldownActive())
                    }
                }
                repeatedEvents(expectReads = true)
                assertSame(completion, field(fresh.service, "completion").get(fresh.service))
                assertEquals(0, fresh.platform.removeAttempts)
                fresh.platform.detachOnRemove = false
                requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.NAVIGATE_MESSAGES, token)
                val removes = fresh.platform.removeAttempts
                repeatedEvents(expectReads = false)
                assertEquals(removes, fresh.platform.removeAttempts)
                assertEquals(0, fresh.platform.routeCalls)
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                fresh.platform.detachOnRemove = true
                runRemovalRetry(fresh.service, token)
                assertTrue((view as GateOverlayWindows).allDetached())
                assertEquals(1, fresh.platform.routeCalls)
                assertTrue(gate(fresh.service).cooldownActive())
            } finally {
                fresh.platform.detachOnRemove = true
                destroyFresh(fresh)
            }
        }
    }

    @Test fun installedGateWatchdogOwnsMissingRootBound() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                fresh.platform.rootBehavior = RootBehavior.MISSING
                fresh.now[0] = 10_149L
                invoke(fresh.service, "runWatchdogTick", token.ticket, token, Runnable {})
                assertTrue(fresh.platform.attached)
                fresh.now[0] = 10_150L
                invoke(fresh.service, "runWatchdogTick", token.ticket, token, Runnable {})
                assertFalse(fresh.platform.attached)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(0, fresh.platform.routeCalls)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(1, fresh.installs)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun clickedResultWithAuthorityLostDuringRoutingNeverArmsCooldown() {
        listOf("report consent", "gate consent", "replace token", "invalidate token").forEach { loss ->
            rule.scenario.onActivity { activity ->
                val fresh = freshService(activity, 10_000L)
                try {
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                    val recycledBeforeRoute = fresh.platform.recycledRoots
                    fresh.platform.beforeRouteReturns = {
                        assertFalse(fresh.platform.attached)
                        assertFalse(gate(fresh.service).cooldownActive())
                        val guard = field(fresh.service, "callbackGuard").get(fresh.service) as OverlayCallbackGuard
                        when (loss) {
                            "report consent" -> Observation.accept(activity, false)
                            "gate consent" -> Observation.setGateConsent(activity, false)
                            "replace token" -> guard.open(token.ticket)
                            "invalidate token" -> guard.invalidateVisible()
                            else -> error("unexpected authority loss")
                        }
                    }
                    requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.NAVIGATE_MESSAGES, token)
                    assertEquals(1, fresh.platform.routeCalls)
                    assertEquals(recycledBeforeRoute + 1, fresh.platform.recycledRoots)
                    assertFalse(fresh.platform.attached)
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertEquals(0, fresh.platform.homeCalls)
                    requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.NAVIGATE_MESSAGES, token)
                    assertEquals(1, fresh.platform.routeCalls)
                    assertFalse(gate(fresh.service).cooldownActive())
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun completionRejectsMissingForeignAndInvalidRootsWithoutCooldown() {
        listOf(RootBehavior.MISSING, RootBehavior.FOREIGN, RootBehavior.NULL_PACKAGE,
            RootBehavior.THROW).forEach { behavior ->
            rule.scenario.onActivity { activity ->
                val fresh = freshService(activity, 10_000L)
                try {
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    val rootsBeforeCompletion = fresh.platform.currentRootCalls
                    val recycledBeforeCompletion = fresh.platform.recycledRoots
                    fresh.platform.rootBehavior = behavior
                    fresh.now[0] += field(gate(fresh.service), "activeDurationMs").getLong(gate(fresh.service))
                    (field(fresh.service, "completion").get(fresh.service) as Runnable).run()
                    assertFalse(fresh.platform.attached)
                    assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertEquals(rootsBeforeCompletion + 1, fresh.platform.currentRootCalls)
                    assertEquals(recycledBeforeCompletion + if (behavior == RootBehavior.MISSING || behavior == RootBehavior.THROW) 0 else 1,
                        fresh.platform.recycledRoots)
                    assertEquals(0, fresh.platform.routeCalls)
                    assertEquals(0, fresh.platform.homeCalls)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun foreignAppSwitchThenImmediateInstagramReentryInstallsAnotherGate() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val firstToken = field(fresh.service, "overlayToken").get(fresh.service)
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                assertFalse(fresh.platform.attached)
                assertEquals(EntryGateState.OUTSIDE, gate(fresh.service).state)
                assertFalse(gate(fresh.service).cooldownActive())
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(2, fresh.installs)
                assertTrue(fresh.platform.attached)
                assertFalse(firstToken == field(fresh.service, "overlayToken").get(fresh.service))
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun otherStateOverInstagramRootKeepsTheCurrentAdmittedGate() {
        val fixture = fixture(attached = true)
        lateinit var trace: List<RemovalTraceRecord>
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            field(fixture.service, "monotonicClock").set(fixture.service, { now + 10L })
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.example.system-ui")
            val recorder = RemovalTraceStore::class.java.getDeclaredField("recorder")
                .apply { isAccessible = true }
                .get(RemovalTraceStore.process) as RemovalTraceRecorder
            trace = recorder.entries
        }
        instrumentation.waitForIdleSync()

        assertTrue(fixture.platform.attached)
        assertEquals(0, fixture.platform.removeAttempts)
        assertEquals(2, fixture.platform.currentRootCalls)
        assertEquals(2, fixture.platform.recycledRoots)
        assertEquals(fixture.ticket, field(fixture.service, "ticket").get(fixture.service))
        assertEquals(EntryGateState.GATING, gate(fixture.service).state)
        assertTrue(trace.any {
            it.mark == RemovalTraceMark.EVENT_ROOT_SAFE &&
                it.event == RemovalTraceEvent.STATE &&
                it.owner == RemovalTraceOwner.OTHER &&
                it.root == RemovalTraceRoot.IG &&
                it.action == RemovalTraceAction.NONE
        })
        assertFalse(trace.any { it.mark == RemovalTraceMark.CLOSING })
    }

    @Test fun admittedInstagramRootEventHasArmedAndUnarmedEquivalentBehavior() {
        fun run(trace: Boolean): Outcome {
            val fixture = fixture(attached = true)
            rule.scenario.onActivity {
                val now = android.os.SystemClock.elapsedRealtime()
                if (trace) {
                    RemovalTraceStore.process.arm(now)
                    RemovalTraceStore.process.beginEligibleEpisode(now)
                    RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
                } else RemovalTraceStore.process.clear()
                val watchdog = field(fixture.service, "foregroundWatchdog")
                    .get(fixture.service) as OverlayForegroundWatchdog
                watchdog.reset(now)
                watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false)
                sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    "com.example.system-ui")
            }
            instrumentation.waitForIdleSync()
            return outcome(fixture.service, fixture.platform)
        }

        val unarmed = run(false)
        val armed = run(true)
        assertEquals(unarmed, armed)
        listOf(unarmed, armed).forEach { result ->
            assertTrue(result.attached)
            assertEquals(EntryGateState.GATING, result.state)
            assertEquals(1, result.roots)
            assertEquals(1, result.recycledRoots)
        }
    }

    @Test fun watchdogInstagramRootAnchorsAnUncertainEvent() {
        val fixture = fixture(attached = true)
        val base = 1_000L
        rule.scenario.onActivity {
            field(fixture.service, "monotonicClock").set(fixture.service, { base + 10L })
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
            fixture.platform.rootBehavior = RootBehavior.NULL_PACKAGE
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.example.system-ui")
        }
        instrumentation.waitForIdleSync()

        assertTrue(fixture.platform.attached)
        assertEquals(EntryGateState.GATING, gate(fixture.service).state)
        assertEquals(2, fixture.platform.currentRootCalls)
        assertEquals(2, fixture.platform.recycledRoots)
    }

    @Test fun eventInstagramRootAnchorsAnUncertainWatchdogTick() {
        val fixture = fixture(attached = true, rootBehavior = RootBehavior.INSTAGRAM)
        val base = 1_000L
        rule.scenario.onActivity {
            field(fixture.service, "monotonicClock").set(fixture.service, { base + 10L })
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.example.system-ui")
            fixture.platform.rootBehavior = RootBehavior.NULL_PACKAGE
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
        }
        instrumentation.waitForIdleSync()

        assertTrue(fixture.platform.attached)
        assertEquals(EntryGateState.GATING, gate(fixture.service).state)
        assertEquals(2, fixture.platform.currentRootCalls)
        assertEquals(2, fixture.platform.recycledRoots)
    }

    @Test fun nullEventOverInstagramRootKeepsTheCurrentGateWithoutCollection() {
        val fixture = fixture(attached = true)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false)
            fixture.service.onAccessibilityEvent(null)
        }
        instrumentation.waitForIdleSync()
        assertTrue(fixture.platform.attached)
        assertEquals(0, fixture.platform.removeAttempts)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
    }

    @Test fun nullPackageNameEventOverInstagramRootKeepsTheCurrentGate() {
        val fixture = fixture(attached = true)
        lateinit var records: List<RemovalTraceRecord>
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false)
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null)
            records = (RemovalTraceStore::class.java.getDeclaredField("recorder")
                .apply { isAccessible = true }
                .get(RemovalTraceStore.process) as RemovalTraceRecorder).entries
        }
        instrumentation.waitForIdleSync()

        assertTrue(fixture.platform.attached)
        assertEquals(EntryGateState.GATING, gate(fixture.service).state)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertTrue(records.any {
            it.mark == RemovalTraceMark.EVENT_ROOT_SAFE &&
                it.event == RemovalTraceEvent.CONTENT &&
                it.owner == RemovalTraceOwner.UNATTRIBUTED &&
                it.root == RemovalTraceRoot.IG &&
                it.action == RemovalTraceAction.NONE
        })
    }

    @Test fun foreignEventStillResetsImmediatelyAfterFreshRootRevalidation() {
        listOf(RootBehavior.FOREIGN, RootBehavior.DOOM).forEach { behavior ->
            val fixture = fixture(attached = true, rootBehavior = behavior)
            rule.scenario.onActivity {
                val watchdog = field(fixture.service, "foregroundWatchdog")
                    .get(fixture.service) as OverlayForegroundWatchdog
                val now = android.os.SystemClock.elapsedRealtime()
                watchdog.reset(now)
                watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false)
                sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    "com.example.system-ui")
            }
            waitFor { !fixture.platform.attached }
            assertEquals(1, fixture.platform.currentRootCalls)
            assertEquals(1, fixture.platform.recycledRoots)
            assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
        }
    }

    @Test fun unknownEventRootUsesTheSharedAnchorAtTheExactServiceBoundary() {
        listOf(RootBehavior.MISSING, RootBehavior.THROW, RootBehavior.NULL_PACKAGE,
            RootBehavior.PACKAGE_THROW).forEach { behavior ->
            val fixture = fixture(attached = true, rootBehavior = behavior)
            val base = 1_000L
            rule.scenario.onActivity {
                field(fixture.service, "monotonicClock").set(fixture.service, { base + 149L })
                val watchdog = field(fixture.service, "foregroundWatchdog")
                    .get(fixture.service) as OverlayForegroundWatchdog
                watchdog.reset(base)
                watchdog.observe(base, "com.instagram.android", verifiedDoomReturn = false)
                sendEvent(fixture.service, AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.system-ui")
            }
            assertTrue(fixture.platform.attached)
            assertEquals(1, fixture.platform.currentRootCalls)
            assertEquals(if (behavior == RootBehavior.MISSING || behavior == RootBehavior.THROW) 0 else 1,
                fixture.platform.recycledRoots)

            rule.scenario.onActivity {
                field(fixture.service, "monotonicClock").set(fixture.service, { base + 150L })
                sendEvent(fixture.service, AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.system-ui")
            }
            waitFor { !fixture.platform.attached }
            assertEquals(2, fixture.platform.currentRootCalls)
            assertEquals(if (behavior == RootBehavior.MISSING || behavior == RootBehavior.THROW) 0 else 2,
                fixture.platform.recycledRoots)
            assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        }
    }

    @Test fun foreignClassificationSurvivesARecycleExceptionAndAttemptsRecycleOnce() {
        val fixture = fixture(attached = true, rootBehavior = RootBehavior.FOREIGN)
        fixture.platform.recycleThrows = true
        rule.scenario.onActivity {
            sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.example.system-ui")
        }
        waitFor { !fixture.platform.attached }
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.OUTSIDE, gate(fixture.service).state)
    }

    @Test fun eventTraceRecordsForeignResetOutsideAndExpiredUncertainty() {
        val foreign = fixture(attached = true, rootBehavior = RootBehavior.FOREIGN)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            val watchdog = field(foreign.service, "foregroundWatchdog")
                .get(foreign.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            watchdog.observe(now, "com.instagram.android", verifiedDoomReturn = false)
            sendEvent(foreign.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.example.system-ui")
        }
        waitFor { !foreign.platform.attached }
        val foreignSnapshot = requireNotNull(RemovalTraceStore.process.snapshot())
        assertTrue(foreignSnapshot.records.any {
            it.mark == RemovalTraceMark.EVENT_ROOT_MISMATCH &&
                it.root == RemovalTraceRoot.FOREIGN &&
                it.action == RemovalTraceAction.RESET_OUTSIDE
        })

        val uncertain = fixture(attached = true, rootBehavior = RootBehavior.NULL_PACKAGE)
        rule.scenario.onActivity {
            RemovalTraceStore.process.clear()
            val base = 1_000L
            field(uncertain.service, "monotonicClock").set(uncertain.service, { base + 150L })
            RemovalTraceStore.process.arm(base)
            RemovalTraceStore.process.beginEligibleEpisode(base)
            RemovalTraceStore.process.record(base, RemovalTraceMark.SHOWN)
            val watchdog = field(uncertain.service, "foregroundWatchdog")
                .get(uncertain.service) as OverlayForegroundWatchdog
            watchdog.reset(base)
            watchdog.observe(base, "com.instagram.android", verifiedDoomReturn = false)
            sendEvent(uncertain.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.example.system-ui")
        }
        waitFor { !uncertain.platform.attached }
        val uncertainSnapshot = requireNotNull(RemovalTraceStore.process.snapshot(1_150L))
        assertTrue(uncertainSnapshot.records.any {
            it.mark == RemovalTraceMark.EVENT_UNCERTAINTY_EXPIRED &&
                it.root == RemovalTraceRoot.NO_PACKAGE &&
                it.action == RemovalTraceAction.BYPASS
        })
    }

    @Test fun staleTokenOrTicketReplacementDuringRootReadDiscardsTheSample() {
        listOf(RootBehavior.REPLACE_TOKEN_DURING_READ, RootBehavior.REPLACE_TICKET_DURING_READ)
            .forEach { behavior ->
                val fixture = fixture(attached = true, rootBehavior = behavior)
                rule.scenario.onActivity {
                    RemovalTraceStore.process.clear()
                    val now = android.os.SystemClock.elapsedRealtime()
                    RemovalTraceStore.process.arm(now)
                    RemovalTraceStore.process.beginEligibleEpisode(now)
                    RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
                    fixture.platform.rootReadHook = {
                        when (behavior) {
                            RootBehavior.REPLACE_TOKEN_DURING_READ -> {
                                val guard = field(fixture.service, "callbackGuard")
                                    .get(fixture.service) as OverlayCallbackGuard
                                field(fixture.service, "overlayToken")
                                    .set(fixture.service, guard.open(fixture.ticket))
                            }
                            RootBehavior.REPLACE_TICKET_DURING_READ -> {
                                field(fixture.service, "ticket")
                                    .set(fixture.service, gate(fixture.service).beginInstagramSession())
                            }
                            else -> error("unexpected root replacement")
                        }
                    }
                    sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        "com.example.system-ui")
                }
                instrumentation.waitForIdleSync()
                assertTrue(fixture.platform.attached)
                assertEquals(1, fixture.platform.currentRootCalls)
                assertEquals(1, fixture.platform.recycledRoots)
                val records = (RemovalTraceStore::class.java.getDeclaredField("recorder")
                    .apply { isAccessible = true }
                    .get(RemovalTraceStore.process) as RemovalTraceRecorder).entries
                assertFalse(records.any {
                    it.mark == RemovalTraceMark.EVENT_ROOT_SAFE ||
                        it.mark == RemovalTraceMark.EVENT_ROOT_UNCERTAIN ||
                        it.mark == RemovalTraceMark.EVENT_ROOT_MISMATCH
                })
            }
    }

    @Test fun revocationDuringRootReadCleansCurrentEpisodeAndDiscardsTheSample() {
        val fixture = fixture(attached = true, rootBehavior = RootBehavior.REVOKE_CONNECTION)
        rule.scenario.onActivity {
            fixture.platform.revokeInsideRoot = { fixture.service.onInterrupt() }
            sendEvent(fixture.service, AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.system-ui")
        }
        waitFor { !fixture.platform.attached }
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.homeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun realConsentRevocationDuringEventRootReadUsesTheWiredService() {
        listOf(RootBehavior.REVOKE_CONSENT, RootBehavior.REVOKE_GATE_CONSENT).forEach { behavior ->
            val fixture = fixture(attached = true, rootBehavior = behavior)
            fixture.platform.revokeInsideRoot = {
                if (behavior == RootBehavior.REVOKE_CONSENT) {
                    Observation.accept(fixture.activity, false)
                } else {
                    Observation.setGateConsent(fixture.activity, false)
                }
            }
            rule.scenario.onActivity {
                sendEvent(fixture.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    "com.example.system-ui")
            }
            waitFor { !fixture.platform.attached }
            assertEquals(1, fixture.platform.currentRootCalls)
            assertEquals(1, fixture.platform.recycledRoots)
            assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
            assertEquals(0, fixture.platform.homeCalls)
        }
    }

    @Test fun actualAccessibilityEventDrivesInstallShownAdmissionAndRemoval() {
        lateinit var service: DoomAccessibilityService
        lateinit var platform: FakePlatform
        lateinit var clipboard: ClipboardManager
        rule.scenario.onActivity { activity ->
            Observation.updateReminderSettings(activity, ReminderSettings(enabled = true))
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            Observation.connected = true
            service = track(DoomAccessibilityService())
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, activity.applicationContext)
            platform = FakePlatform(false, true, RootBehavior.INSTAGRAM, MessagesRouteResult.CLICKED,
                mutableListOf())
            platform.stateReader = { gate(service).state }
            platform.revokeInsideRoot = { }
            clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
            field(service, "overlayPlatform").set(service, platform)
            field(service, "windowManager").set(
                service,
                activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            )
            field(service, "overlayWindowInstaller").set(
                service,
                { _: WindowManager, view: View, _: WindowManager.LayoutParams -> platform.attach(view) }
            )
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            sendEvent(service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
        }
        instrumentation.waitForIdleSync()
        val token = field(service, "overlayToken").get(service) as OverlayCallbackToken
        rule.scenario.onActivity {
            invoke(
                service,
                "requestOverlayRemoval",
                OverlayRemovalAction.HOME,
                token,
                RemovalTraceMark.USER_HOME,
                RemovalTraceEvent.NA,
                RemovalTraceOwner.NA,
                RemovalTraceRoot.NOT_READ,
            )
        }
        waitFor { RemovalTraceStore.process.availability() == RemovalTraceAvailability.AVAILABLE }
        assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        val snapshot = RemovalTraceStore.process.snapshot()!!
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.SHOWN })
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.DETACHED })
        assertTrue(snapshot.records.any {
            it.mark == RemovalTraceMark.ACTION_RELEASED && it.action == RemovalTraceAction.HOME
        })
        assertEquals(1, platform.homeCalls)
    }

    @Test fun actualWatchdogTickUsesTheRunnableProductionBoundaryAndPreciseMarks() {
        val fixture = fixture(attached = true)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
            fixture.platform.rootBehavior = RootBehavior.FOREIGN
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
        }
        waitFor { !fixture.platform.attached }
        assertEquals(2, fixture.platform.recycledRoots)
        val marks = RemovalTraceStore.process.snapshot()!!.records.map { it.mark }
        assertTrue(marks.contains(RemovalTraceMark.WATCHDOG_SAFE))
        assertTrue(marks.contains(RemovalTraceMark.WATCHDOG_FOREIGN))
        assertTrue(
            marks.contains(RemovalTraceMark.DETACHED) ||
                marks.contains(RemovalTraceMark.ALREADY_DETACHED)
        )
    }

    @Test fun actualWatchdogNullPackageAndReadFailureKeepBaselineUncertaintyMapping() {
        listOf(
            RootBehavior.MISSING to RemovalTraceRoot.NO_ROOT,
            RootBehavior.NULL_PACKAGE to RemovalTraceRoot.NO_PACKAGE,
            RootBehavior.THROW to RemovalTraceRoot.READ_FAILURE,
        ).forEach { (behavior, expectedRoot) ->
            val fixture = fixture(attached = true, rootBehavior = behavior)
            rule.scenario.onActivity {
                val now = android.os.SystemClock.elapsedRealtime()
                RemovalTraceStore.process.arm(now)
                RemovalTraceStore.process.beginEligibleEpisode(now)
                RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
                val watchdog = field(fixture.service, "foregroundWatchdog")
                    .get(fixture.service) as OverlayForegroundWatchdog
                watchdog.reset(now)
                invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
                RemovalTraceStore.process.finish(
                    android.os.SystemClock.elapsedRealtime(),
                    RemovalTraceMark.NO_OVERLAY_RELEASED,
                    detached = false,
                    policyReleased = false,
                )
            }
            assertTrue(fixture.platform.attached)
            assertEquals(
                expectedRoot,
                RemovalTraceStore.process.snapshot()!!.records.last { it.mark == RemovalTraceMark.WATCHDOG_UNCERTAIN }.root
            )
        }
    }

    @Test fun removalExhaustionVetoesPendingHomeAndLateDetach() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        waitFor { (field(fixture.service, "gateWindows").get(fixture.service) as? GateOverlayWindows)?.degraded == true }
        assertEquals(0, fixture.platform.homeCalls)
        rule.scenario.onActivity { fixture.platform.attached = false }
        rule.scenario.onActivity { runRemovalRetry(fixture.service, fixture.token) }
        assertEquals(0, fixture.platform.homeCalls)
        assertFalse(gate(fixture.service).cooldownActive())
    }

    @Test fun enabledTraceUsesTheRealRemovalBoundaryWithoutChangingReleasedAction() {
        val fixture = fixture(attached = true, detachOnRemove = true)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now + 1L, RemovalTraceMark.SHOWN)
        }
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        waitFor { fixture.platform.homeCalls == 1 }

        val snapshot = RemovalTraceStore.process.snapshot()!!
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.ATTEMPT })
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.SHOWN })
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.CLOSING })
        assertTrue(snapshot.records.any { it.mark == RemovalTraceMark.DETACHED })
        assertTrue(snapshot.records.any {
            it.mark == RemovalTraceMark.ACTION_RELEASED && it.action == RemovalTraceAction.HOME
        })
    }

    @Test fun serviceInterruptionDoesNotEraseAnAlreadyFrozenTrace() {
        val fixture = fixture(attached = true, detachOnRemove = true)
        rule.scenario.onActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            RemovalTraceStore.process.arm(now)
            RemovalTraceStore.process.beginEligibleEpisode(now)
            RemovalTraceStore.process.record(now + 1L, RemovalTraceMark.SHOWN)
        }
        request(fixture.service, OverlayRemovalAction.COMPLETE, fixture.token)
        waitFor { RemovalTraceStore.process.availability() == RemovalTraceAvailability.AVAILABLE }
        val frozen = RemovalTraceStore.process.snapshot()
        rule.scenario.onActivity {
            fixture.service.onInterrupt()
            fixture.service.onUnbind(null)
            fixture.service.onDestroy()
            invoke(fixture.service, "onServiceConnected")
        }
        assertEquals(RemovalTraceAvailability.AVAILABLE, RemovalTraceStore.process.availability())
        assertEquals(frozen, RemovalTraceStore.process.snapshot())
    }

    private fun runEventOutcome(trace: Boolean, behavior: RootBehavior): Outcome {
        lateinit var result: Outcome
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 10_000L)
            try {
                RemovalTraceStore.process.clear()
                if (trace) RemovalTraceStore.process.arm(fresh.now[0])
                fresh.platform.rootBehavior = behavior
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    "com.instagram.android")
                assertEquals(0, fresh.installs)
                assertFalse(fresh.platform.attached)
                assertFalse(gate(fresh.service).cooldownActive())
                result = outcome(fresh.service, fresh.platform)
            } finally { destroyFresh(fresh) }
        }
        return result
    }

    private fun runWatchdogOutcome(trace: Boolean): Outcome {
        val fixture = fixture(attached = true)
        rule.scenario.onActivity {
            RemovalTraceStore.process.clear()
            val now = android.os.SystemClock.elapsedRealtime()
            if (trace) {
                RemovalTraceStore.process.arm(now)
                RemovalTraceStore.process.beginEligibleEpisode(now)
                RemovalTraceStore.process.record(now, RemovalTraceMark.SHOWN)
            }
            val watchdog = field(fixture.service, "foregroundWatchdog")
                .get(fixture.service) as OverlayForegroundWatchdog
            watchdog.reset(now)
            fixture.platform.rootBehavior = RootBehavior.FOREIGN
            invoke(fixture.service, "runWatchdogTick", fixture.ticket, fixture.token, Runnable {})
        }
        waitFor { !fixture.platform.attached }
        return outcome(fixture.service, fixture.platform)
    }

    private fun runInstallHomeOutcome(trace: Boolean): Outcome {
        lateinit var service: DoomAccessibilityService
        lateinit var platform: FakePlatform
        lateinit var clipboard: ClipboardManager
        rule.scenario.onActivity { activity ->
            RemovalTraceStore.process.clear()
            Observation.updateReminderSettings(activity, ReminderSettings(enabled = true))
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            Observation.connected = true
            service = track(DoomAccessibilityService())
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, activity.applicationContext)
            platform = FakePlatform(false, true, RootBehavior.INSTAGRAM, MessagesRouteResult.CLICKED,
                mutableListOf())
            platform.stateReader = { gate(service).state }
            platform.revokeInsideRoot = { }
            clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
            field(service, "overlayPlatform").set(service, platform)
            field(service, "windowManager").set(
                service,
                activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            )
            field(service, "overlayWindowInstaller").set(
                service,
                { _: WindowManager, view: View, _: WindowManager.LayoutParams -> platform.attach(view) }
            )
            if (trace) {
                val now = android.os.SystemClock.elapsedRealtime()
                RemovalTraceStore.process.arm(now)
            }
            sendEvent(service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
            // Stay in this main-thread callback so the posted watchdog cannot add
            // timing-dependent root reads before HOME is requested.
            val token = field(service, "overlayToken").get(service) as OverlayCallbackToken
            invoke(
                service,
                "requestOverlayRemoval",
                OverlayRemovalAction.HOME,
                token,
                RemovalTraceMark.USER_HOME,
                RemovalTraceEvent.NA,
                RemovalTraceOwner.NA,
                RemovalTraceRoot.NOT_READ,
            )
        }
        waitFor { platform.homeCalls == 1 }
        if (trace) waitFor { RemovalTraceStore.process.availability() == RemovalTraceAvailability.AVAILABLE }
        assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        return outcome(service, platform)
    }

    private fun outcome(service: DoomAccessibilityService, platform: FakePlatform): Outcome {
        val state = gate(service).state
        val action = when {
            platform.homeCalls > 0 -> RemovalTraceAction.HOME
            platform.routeCalls > 0 -> RemovalTraceAction.NAVIGATE_MESSAGES
            state == EntryGateState.BYPASSED -> RemovalTraceAction.BYPASS
            state == EntryGateState.OUTSIDE -> RemovalTraceAction.RESET_OUTSIDE
            else -> RemovalTraceAction.NONE
        }
        return Outcome(
            attached = platform.attached,
            removes = platform.removeAttempts,
            roots = platform.currentRootCalls,
            recycledRoots = platform.recycledRoots,
            state = state,
            routes = platform.routeCalls,
            homes = platform.homeCalls,
            action = action,
            calls = platform.platformCalls.toList(),
        )
    }

    @Test fun timerSessionStartsWhileBreathingOwnsWindowAndHomeEndsSynchronously() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                assertTrue(timer.running)
                assertTrue(field(fresh.service, "gateWindows").get(fresh.service) != null)
                val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                requestOverlayRemovalWithToken(fresh.service, OverlayRemovalAction.HOME, token)
                assertFalse(timer.running)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerFreshRootRaceAndNullGraceFailClosedWithoutMutation() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                timer.observeVerifiedInstagram(true, true, true)
                invoke(fresh.service, "attachTimerIfAllowed")
                assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                fresh.now[0]++
                invoke(fresh.service, "renderTimer", field(fresh.service, "timerEpoch").getLong(fresh.service), false)
                assertFalse(timer.running)

                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                fresh.now[0] = 2_000L
                timer.observeVerifiedInstagram(true, true, true)
                assertTrue(invokeResult<Boolean>(fresh.service, "verifyTimerAuthority"))
                fresh.platform.rootBehavior = RootBehavior.MISSING
                fresh.now[0] += 149L
                assertTrue(invokeResult<Boolean>(fresh.service, "verifyTimerAuthority"))
                fresh.now[0] += 1L
                assertFalse(invokeResult<Boolean>(fresh.service, "verifyTimerAuthority"))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerDisconnectDestroyAndDisableBeginPhysicalCleanup() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
            timer.observeVerifiedInstagram(true, true, true)
            invoke(fresh.service, "attachTimerIfAllowed")
            assertTrue(timer.running)
            fresh.service.onInterrupt()
            assertFalse(timer.running)
            assertTrue(fresh.platform.removeAttempts > 0)
            fresh.service.onDestroy()
        }
    }

    @Test fun timerAttachNeedsExactRootAndRetainsOwnershipAfterAttachedException() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                timer.observeVerifiedInstagram(true, true, true)
                assertTrue(invokeResult<Boolean>(fresh.service, "verifyTimerAuthority"))
                fresh.platform.rootBehavior = RootBehavior.MISSING
                invoke(fresh.service, "attachTimerIfAllowed")
                assertEquals(0, fresh.installs)
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                fresh.platform.detachOnRemove = false
                field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                    { _: WindowManager, view: View, _: WindowManager.LayoutParams ->
                        fresh.platform.attach(view)
                        throw IllegalStateException("attached then failed")
                    })
                invoke(fresh.service, "attachTimerIfAllowed")
                val view = field(fresh.service, "timerView").get(fresh.service) as View
                assertTrue(fresh.platform.isAttached(view))
                assertTrue(field(fresh.service, "timerClosing").getBoolean(fresh.service))
                assertFalse(timer.running)
                val retry = pendingRetry(fresh.service, "timerRemovalLoop")
                fresh.platform.detachOnRemove = true
                retry.run()
                assertEquals(null, field(fresh.service, "timerView").get(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerFinalAttachSampleAndTapRecheckRevocationBeforeMutation() {
        rule.scenario.onActivity { activity ->
            for (revoke in listOf(false, true)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                    timer.observeVerifiedInstagram(true, true, true)
                    var samples = 0
                    fresh.platform.afterPackageRead = {
                        samples++
                        if (samples == 1) fresh.platform.rootBehavior = RootBehavior.MISSING
                    }
                    invoke(fresh.service, "attachTimerIfAllowed")
                    assertEquals(0, fresh.installs)
                    fresh.platform.afterPackageRead = {}
                    fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                    invoke(fresh.service, "attachTimerIfAllowed")
                    val view = field(fresh.service, "timerView").get(fresh.service) as View
                    fresh.platform.afterPackageRead = {
                        if (revoke) Observation.setGateConsent(activity, false)
                        else Observation.accept(activity, false)
                    }
                    view.performClick()
                    assertFalse(timer.running)
                    assertFalse(timer.collapsed)
                    assertFalse(fresh.platform.attached)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun timerHandoffAuthorityFailureCancelsGateAfterPhysicalDetach() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                startBubble(fresh)
                fresh.platform.detachOnRemove = false
                fresh.now[0] += 60_000L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                val retry = pendingRetry(fresh.service, "timerRemovalLoop")
                fresh.platform.rootBehavior = RootBehavior.MISSING
                fresh.platform.detachOnRemove = true
                retry.run()
                assertFalse(fresh.platform.attached)
                assertEquals(EntryGateState.BYPASSED, gate(fresh.service).state)
                assertEquals(null, field(fresh.service, "gateAfterTimerDetach").get(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun noBubbleForeignTransitionRechecksWithoutAnotherEvent() {
        rule.scenario.onActivity { activity ->
            for (fullGate in listOf(false, true)) for (initial in listOf(RootBehavior.INSTAGRAM, RootBehavior.MISSING)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    if (fullGate) sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    else invoke(fresh.service, "observeTimerInstagram")
                    val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                    fresh.platform.rootBehavior = initial
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                    assertTrue(timer.running)
                    val check = field(fresh.service, "sessionBoundaryCheck").get(fresh.service) as Runnable
                    fresh.platform.rootBehavior = RootBehavior.MISSING
                    fresh.now[0] = 1_150L
                    check.run()
                    assertFalse(timer.running)
                    assertEquals(EntryGateState.OUTSIDE, gate(fresh.service).state)
                    assertEquals(null, field(fresh.service, "ticket").get(fresh.service))
                    fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    val replacement = field(fresh.service, "ticket").get(fresh.service)
                    check.run()
                    assertTrue(timer.running)
                    assertEquals(replacement, field(fresh.service, "ticket").get(fresh.service))
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun noBubbleBoundaryAcceptsExactInstagramAndVetoesStaleReadAndConsent() {
        rule.scenario.onActivity { activity ->
            for (boundary in listOf(RootBehavior.INSTAGRAM, RootBehavior.NULL_PACKAGE, RootBehavior.FOREIGN)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    invoke(fresh.service, "observeTimerInstagram")
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                    val check = field(fresh.service, "sessionBoundaryCheck").get(fresh.service) as Runnable
                    fresh.platform.rootBehavior = boundary
                    fresh.now[0] += 150L
                    check.run()
                    val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                    assertEquals(boundary == RootBehavior.INSTAGRAM, timer.running)
                    if (boundary == RootBehavior.INSTAGRAM)
                        assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
                    assertEquals(null, field(fresh.service, "sessionBoundaryCheck").get(fresh.service))
                } finally { destroyFresh(fresh) }
            }
            val fresh = freshService(activity, 1_000L)
            try {
                invoke(fresh.service, "observeTimerInstagram")
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                val stale = field(fresh.service, "sessionBoundaryCheck").get(fresh.service) as Runnable
                fresh.platform.afterPackageRead = {
                    fresh.platform.afterPackageRead = {}
                    invoke(fresh.service, "endTimerSession")
                    invoke(fresh.service, "observeTimerInstagram")
                }
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                fresh.now[0] += 150L
                stale.run()
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                assertTrue(timer.running)
                // Confirmed foreign attribution ends immediately, without a deferred check.
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                assertFalse(timer.running)
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                invoke(fresh.service, "observeTimerInstagram")
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                val denied = field(fresh.service, "sessionBoundaryCheck").get(fresh.service) as Runnable
                Observation.connected = false
                val reads = fresh.platform.currentRootCalls
                denied.run()
                assertFalse(timer.running)
                assertEquals(reads, fresh.platform.currentRootCalls)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun failedMessagesResumesBubbleWithoutCooldownAndNextEventGates() {
        rule.scenario.onActivity { activity ->
            for (throws in listOf(false, true)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    val oldTicket = field(fresh.service, "ticket").get(fresh.service)
                    fresh.platform.routeResult = MessagesRouteResult.FAILED
                    fresh.platform.routeThrows = throws
                    val token = field(fresh.service, "overlayToken").get(fresh.service) as OverlayCallbackToken
                    invoke(fresh.service, "requestOverlayRemoval", OverlayRemovalAction.NAVIGATE_MESSAGES,
                        token, RemovalTraceMark.USER_MESSAGES, RemovalTraceEvent.NA, RemovalTraceOwner.NA,
                        RemovalTraceRoot.NOT_READ)
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertEquals(null, field(fresh.service, "ticket").get(fresh.service))
                    assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
                    assertEquals(2, fresh.installs)
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                    assertTrue(oldTicket != field(fresh.service, "ticket").get(fresh.service))
                    assertEquals(null, field(fresh.service, "timerView").get(fresh.service))
                    assertEquals(3, fresh.installs)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun timerConfigurationUpdatesCurrentWindowAndRejectsStaleEpoch() {
        rule.scenario.onActivity { activity ->
            var layoutContext = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                densityDpi = 160
            })
            val context = object : ContextWrapper(activity.applicationContext) {
                override fun getResources() = layoutContext.resources
            }
            val fresh = freshService(activity, 1_000L, serviceContext = context)
            try {
                val timer = startBubble(fresh)
                val view = field(fresh.service, "timerView").get(fresh.service)
                val epoch = field(fresh.service, "timerEpoch").getLong(fresh.service)
                val params = field(fresh.service, "timerParams").get(fresh.service) as WindowManager.LayoutParams
                var updates = 0
                field(fresh.service, "overlayWindowUpdater").set(fresh.service,
                    { manager: WindowManager, target: View, changed: WindowManager.LayoutParams ->
                        val density = layoutContext.resources.displayMetrics.density
                        val frame = manager.currentWindowMetrics.bounds
                        val safe = InstagramTimerOverlayViewFactory.safeInsets(manager, target.rootWindowInsets)
                        val bounds = InstagramTimerOverlayViewFactory.bounds(frame, safe,
                            target.measuredWidth.coerceAtLeast((56 * density).toInt()),
                            target.measuredHeight.coerceAtLeast((56 * density).toInt()), density)
                        assertSame(view, target); assertSame(params, changed)
                        assertTrue(changed.x == bounds.left || changed.x == bounds.right)
                        assertTrue(changed.y in bounds.top..bounds.bottom)
                        updates += 1
                    })
                val landscape = Configuration(layoutContext.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    densityDpi = 320
                }
                layoutContext = activity.createConfigurationContext(landscape)
                params.x = -100_000
                params.y = -100_000
                fresh.service.onConfigurationChanged(landscape)
                assertEquals(1, updates)
                assertEquals(2, fresh.installs)
                invoke(fresh.service, "updateTimerLayout", epoch - 1)
                assertEquals(1, updates)
                field(fresh.service, "overlayWindowUpdater").set(fresh.service,
                    { _: WindowManager, _: View, _: WindowManager.LayoutParams -> throw IllegalStateException("update failed") })
                params.x = -1
                invoke(fresh.service, "updateTimerLayout", epoch)
                assertFalse(timer.running)
                assertEquals(null, field(fresh.service, "timerParams").get(fresh.service))
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val replacement = field(fresh.service, "timerView").get(fresh.service)
                assertTrue(replacement != null)
                invoke(fresh.service, "updateTimerLayout", epoch)
                assertSame(replacement, field(fresh.service, "timerView").get(fresh.service))
                assertTrue(timer.running)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerConfigurationRootRaceCannotUpdateOrEndReplacement() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                val epoch = field(fresh.service, "timerEpoch").getLong(fresh.service)
                var updates = 0
                field(fresh.service, "overlayWindowUpdater").set(fresh.service,
                    { _: WindowManager, _: View, _: WindowManager.LayoutParams -> updates += 1 })
                fresh.platform.afterPackageRead = {
                    fresh.platform.afterPackageRead = {}
                    invoke(fresh.service, "endTimerSession")
                    fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                    invoke(fresh.service, "observeTimerInstagram")
                    invoke(fresh.service, "attachTimerIfAllowed")
                }
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                invoke(fresh.service, "updateTimerLayout", epoch)
                assertTrue(timer.running)
                assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
                assertEquals(0, updates)
            } finally { destroyFresh(fresh) }
        }
    }

    private fun startBubble(fresh: FreshService): InstagramSessionTimer {
        sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
        fresh.now[0] += field(gate(fresh.service), "activeDurationMs").getLong(gate(fresh.service))
        (field(fresh.service, "completion").get(fresh.service) as Runnable).run()
        assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
        return field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
    }

    @Test fun timerForeignEventsPreserveSafeSessionAndWatchdogExpiresAtExactGrace() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                val view = field(fresh.service, "timerView").get(fresh.service)
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.android.systemui")
                assertTrue(timer.running)
                assertSame(view, field(fresh.service, "timerView").get(fresh.service))
                val watchdog = field(fresh.service, "timerWatchdog").get(fresh.service) as Runnable
                assertEquals(null, field(fresh.service, "sessionBoundaryCheck").get(fresh.service))
                fresh.platform.rootBehavior = RootBehavior.MISSING
                fresh.now[0] += 149L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.example.keyboard")
                watchdog.run()
                assertTrue(timer.running)
                fresh.now[0]++
                watchdog.run()
                assertFalse(timer.running)
                assertFalse(fresh.platform.attached)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerDismissalSurvivesSystemUiAndRecognizedImeButOrdinaryForeignClearsIt() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                startBubble(fresh)
                val epoch = field(fresh.service, "timerEpoch").getLong(fresh.service)
                invoke(fresh.service, "dismissTimer", epoch)
                assertTrue(field(fresh.service, "timerDismissedThisVisit").getBoolean(fresh.service))

                fresh.platform.rootBehavior = RootBehavior.SYSTEM_UI
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui")
                assertTrue(field(fresh.service, "timerDismissedThisVisit").getBoolean(fresh.service))

                fresh.platform.rootBehavior = RootBehavior.RECOGNIZED_IME
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.keyboard")
                assertTrue(field(fresh.service, "timerDismissedThisVisit").getBoolean(fresh.service))

                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                assertFalse(field(fresh.service, "timerDismissedThisVisit").getBoolean(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerCooldownReentryRequiresVerifiedRootAndNewClock() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                assertFalse(timer.running)
                fresh.platform.rootBehavior = RootBehavior.MISSING
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertFalse(timer.running)
                assertFalse(fresh.platform.attached)
                fresh.now[0] += 1_000L
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(timer.running)
                assertEquals(0L, timer.elapsedSeconds())
                assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerCooldownExpiryHandoffWaitsForDetachAndResumesSameClock() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                val oldEpoch = field(fresh.service, "timerEpoch").getLong(fresh.service)
                val oldTick = field(fresh.service, "timerTick").get(fresh.service) as Runnable
                val oldWatchdog = field(fresh.service, "timerWatchdog").get(fresh.service) as Runnable
                fresh.platform.detachOnRemove = false
                fresh.now[0] += 60_000L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                assertEquals(null, field(fresh.service, "gateWindows").get(fresh.service))
                assertTrue(timer.running)
                val retry = pendingRetry(fresh.service, "timerRemovalLoop")
                fresh.platform.detachOnRemove = true
                retry.run()
                assertEquals(null, field(fresh.service, "timerView").get(fresh.service))
                assertTrue(field(fresh.service, "gateWindows").get(fresh.service) != null)
                fresh.now[0] += field(gate(fresh.service), "activeDurationMs").getLong(gate(fresh.service))
                (field(fresh.service, "completion").get(fresh.service) as Runnable).run()
                val resumed = field(fresh.service, "timerView").get(fresh.service)
                assertTrue(resumed != null)
                // The timer includes the first 10s gate, 60s elapsed, and the second 10s gate.
                assertEquals(80L, timer.elapsedSeconds())
                oldTick.run(); oldWatchdog.run(); retry.run()
                invoke(fresh.service, "finishTimerDetach", oldEpoch)
                assertSame(resumed, field(fresh.service, "timerView").get(fresh.service))
                assertTrue(timer.running)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerDisableAndDismissDuringClosingPreserveGateHandoff() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                fresh.platform.detachOnRemove = false
                fresh.now[0] += 60_000L
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(field(fresh.service, "timerClosing").getBoolean(fresh.service))
                val pendingHandoff = field(fresh.service, "gateAfterTimerDetach").get(fresh.service)
                val currentTicket = field(fresh.service, "ticket").get(fresh.service)
                val liveEpoch = field(fresh.service, "timerEpoch").getLong(fresh.service)
                assertTrue(pendingHandoff != null)

                Observation.setSessionTimerEnabled(activity, false)
                invoke(fresh.service, "dismissTimer", liveEpoch)
                assertFalse(timer.running)
                assertEquals(null, field(fresh.service, "timerTick").get(fresh.service))
                assertEquals(null, field(fresh.service, "timerWatchdog").get(fresh.service))
                assertFalse(field(fresh.service, "timerDismissedThisVisit").getBoolean(fresh.service))
                assertEquals(null, field(fresh.service, "cancelledGateAfterTimerDetach").get(fresh.service))
                assertSame(pendingHandoff, field(fresh.service, "gateAfterTimerDetach").get(fresh.service))
                assertSame(currentTicket, field(fresh.service, "ticket").get(fresh.service))

                val retry = pendingRetry(fresh.service, "timerRemovalLoop")
                fresh.platform.detachOnRemove = true
                retry.run()
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                assertTrue(field(fresh.service, "gateWindows").get(fresh.service) != null)
                assertEquals(null, field(fresh.service, "timerView").get(fresh.service))
                assertTrue(fresh.platform.attached)
            } finally {
                Observation.setSessionTimerEnabled(activity, true)
                destroyFresh(fresh)
            }
        }
    }

    @Test fun timerReenableWaitsThroughNoiseForOneFreshVerifiedInstagramEvent() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                Observation.setSessionTimerEnabled(activity, false)
                assertFalse(timer.running)
                Observation.setSessionTimerEnabled(activity, true)
                assertTrue(field(fresh.service, "timerAwaitingFreshObservation").getBoolean(fresh.service))
                assertFalse(timer.running)

                for (owner in listOf("com.chardyb.doom", "com.android.systemui", "com.example.keyboard", null)) {
                    fresh.platform.rootBehavior = RootBehavior.MISSING
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, owner)
                    assertTrue(field(fresh.service, "timerAwaitingFreshObservation").getBoolean(fresh.service))
                    assertFalse(timer.running)
                }
                fresh.platform.rootBehavior = RootBehavior.NULL_PACKAGE
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(field(fresh.service, "timerAwaitingFreshObservation").getBoolean(fresh.service))
                assertFalse(timer.running)

                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertFalse(field(fresh.service, "timerAwaitingFreshObservation").getBoolean(fresh.service))
                assertTrue(timer.running)
                assertEquals(0L, timer.elapsedSeconds())
                val installs = fresh.installs
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android")
                assertEquals(installs, fresh.installs)
                assertEquals(0L, timer.elapsedSeconds())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerTapUsesBoundedOwnOverlayAuthorityAndRevokeStopsCallbacks() {
        rule.scenario.onActivity { activity ->
            for (gateConsent in listOf(false, true)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    val timer = startBubble(fresh)
                    val view = field(fresh.service, "timerView").get(fresh.service) as View
                    fresh.platform.rootBehavior = RootBehavior.DOOM
                    fresh.now[0] += 149L
                    view.performClick()
                    assertTrue(timer.running)
                    assertTrue(timer.collapsed)
                    assertTrue(view.contentDescription.toString().contains("Instagram time"))
                    if (gateConsent) Observation.setGateConsent(activity, false)
                    else Observation.accept(activity, false)
                    assertFalse(timer.running)
                    assertEquals(null, field(fresh.service, "timerTick").get(fresh.service))
                    assertEquals(null, field(fresh.service, "timerWatchdog").get(fresh.service))
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun timerTerminalRemovalCannotResetAReplacementSession() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                fresh.platform.detachOnRemove = false
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                val retry = pendingRetry(fresh.service, "timerRemovalLoop")
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertFalse(timer.running)
                fresh.platform.detachOnRemove = true
                retry.run()
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                assertTrue(timer.running)
                val replacement = field(fresh.service, "timerView").get(fresh.service)
                retry.run()
                assertTrue(timer.running)
                assertSame(replacement, field(fresh.service, "timerView").get(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerStaleAttachSampleCannotEndReplacementSession() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                timer.observeVerifiedInstagram(true, true, true)
                fresh.platform.afterPackageRead = {
                    fresh.platform.afterPackageRead = {}
                    invoke(fresh.service, "endTimerSession")
                    invoke(fresh.service, "observeTimerInstagram")
                    invoke(fresh.service, "attachTimerIfAllowed")
                }
                invoke(fresh.service, "attachTimerIfAllowed")
                assertTrue(timer.running)
                assertEquals(1, fresh.installs)
                assertTrue(fresh.platform.attached)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerMainActivityReturnEndsImmediatelyEvenInsideOwnRootGrace() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                val timer = startBubble(fresh)
                fresh.platform.rootBehavior = RootBehavior.DOOM
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                try {
                    event.packageName = "com.chardyb.doom"
                    event.className = MainActivity::class.java.name
                    fresh.service.onAccessibilityEvent(event)
                } finally { event.recycle() }
                assertFalse(timer.running)
                assertFalse(fresh.platform.attached)
                assertEquals(EntryGateState.OUTSIDE, gate(fresh.service).state)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun timerRemovalExhaustionAndLifecycleVetoAllLaterCallbacks() {
        rule.scenario.onActivity { activity ->
            for (terminal in listOf("exhaust", "interrupt", "unbind", "destroy")) {
                val fresh = freshService(activity, 1_000L)
                try {
                    val timer = startBubble(fresh)
                    fresh.platform.detachOnRemove = false
                    if (terminal == "exhaust") {
                        invoke(fresh.service, "endTimerSession")
                        repeat(19) { pendingRetry(fresh.service, "timerRemovalLoop").run() }
                        assertTrue(field(fresh.service, "timerView").get(fresh.service) != null)
                        assertTrue(Observation.connected)
                        assertTrue((field(fresh.service, "timerRemovalLoop").get(fresh.service) as OverlayRemovalRetryLoop).isPending)
                    } else when (terminal) {
                        "interrupt" -> fresh.service.onInterrupt()
                        "unbind" -> fresh.service.onUnbind(null)
                        else -> fresh.service.onDestroy()
                    }
                    assertFalse(timer.running)
                    for (name in listOf("timerTick", "timerWatchdog"))
                        assertEquals(null, field(fresh.service, name).get(fresh.service))
                    fresh.platform.attached = false
                    RetiringOverlayCleanup.reconcile()
                    invoke(fresh.service, "finishTimerDetach", field(fresh.service, "timerEpoch").getLong(fresh.service))
                    Observation.connected = true
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    assertFalse(fresh.platform.attached)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun realInstallCreatesOnePassThroughVisualAndThreeBoundedActionWindows() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                assertEquals(4, episode.records.size)
                assertEquals(4, fresh.added.size)
                assertEquals(episode.ui.windowRoots, episode.records.map { it.view })
                fresh.added.forEachIndexed { index, (_, p) ->
                    assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, p.type)
                    assertEquals(android.graphics.PixelFormat.TRANSLUCENT, p.format)
                    assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
                    assertEquals(index == 0, p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                    assertTrue(p.width > 0 && p.height > 0)
                    if (index > 0) assertTrue(p.width < fresh.added[0].second.width)
                }
                assertTrue(field(fresh.service, "completion").get(fresh.service) != null)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun layoutUpdateFailureClosesAllWindowsWithoutNewAdmission() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                var updates = 0
                var fail = false
                field(fresh.service, "overlayWindowUpdater").set(fresh.service,
                    { _: WindowManager, _: View, _: WindowManager.LayoutParams ->
                        updates++
                        if (fail) throw IllegalStateException("synthetic layout failure")
                    })
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                invoke(fresh.service, "updateGateLayout", episode, null)
                assertEquals(4, updates)
                fail = true
                invoke(fresh.service, "updateGateLayout", episode, null)
                assertTrue(episode.allDetached())
                assertNull(field(fresh.service, "gateWindows").get(fresh.service))
                assertEquals(1, fresh.installs)
                assertEquals(0, fresh.platform.routeCalls + fresh.platform.homeCalls)
                assertFalse(gate(fresh.service).cooldownActive())
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun registeredTimerSurvivesImmediateRenderBeforeFirstTraversal() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                Observation.setSessionTimerEnabled(activity, true)
                field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                    { _: WindowManager, view: View, _: WindowManager.LayoutParams -> fresh.platform.registerPending(view) })
                val timer = field(fresh.service, "sessionTimer").get(fresh.service) as InstagramSessionTimer
                timer.observeVerifiedInstagram(true, true, true)
                invoke(fresh.service, "attachTimerIfAllowed")
                val view = field(fresh.service, "timerView").get(fresh.service) as View
                assertTrue(timer.running)
                assertFalse(fresh.platform.isAttached(view))
                assertTrue(fresh.platform.isRegistered(view))
                assertEquals(0, fresh.platform.removeAttempts)
                invoke(fresh.service, "endTimerSession")
                assertFalse(fresh.platform.isRegistered(view))
                assertNull(field(fresh.service, "timerView").get(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun registeredGateBeforeFirstTraversalRemainsVisibleUntilAnAction() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                Observation.setSessionTimerEnabled(activity, false)
                field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                    { _: WindowManager, view: View, _: WindowManager.LayoutParams -> fresh.platform.registerPending(view) })
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                assertEquals(4, episode.records.size)
                assertEquals(EntryGateState.GATING, gate(fresh.service).state)
                assertFalse(episode.allDetached())
                episode.records.forEach {
                    assertFalse(fresh.platform.isAttached(it.view))
                    assertTrue(fresh.platform.isRegistered(it.view))
                }
                assertEquals(0, fresh.platform.removeAttempts)
                assertTrue(episode.ui.skipToMessages.performClick())
                assertTrue(episode.allDetached())
                assertEquals(4, fresh.platform.removeAttempts)
                assertEquals(1, fresh.platform.routeCalls)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun messagesWaitForRegisteredButNotYetAttachedLastButton() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                Observation.setSessionTimerEnabled(activity, false)
                field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                    { _: WindowManager, view: View, _: WindowManager.LayoutParams -> fresh.platform.registerPending(view) })
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                fresh.platform.heldView = episode.ui.leaveInstagram
                assertTrue(episode.ui.skipToMessages.performClick())
                assertFalse(fresh.platform.isAttached(episode.ui.leaveInstagram))
                assertTrue(fresh.platform.isRegistered(episode.ui.leaveInstagram))
                assertSame(episode, field(fresh.service, "gateWindows").get(fresh.service))
                assertEquals(0, fresh.platform.routeCalls)
                assertFalse(gate(fresh.service).cooldownActive())
                fresh.platform.heldView = null
                pendingRetry(fresh.service, "removalLoop").run()
                assertTrue(episode.allDetached())
                assertEquals(1, fresh.platform.routeCalls)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun partialPendingAddsAreRemovedBeforeOwnershipIsReleased() {
        rule.scenario.onActivity { activity ->
            for (position in 1..4) {
                val fresh = freshService(activity, 1_000L)
                try {
                    val attempted = mutableListOf<View>()
                    field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                        { _: WindowManager, view: View, _: WindowManager.LayoutParams ->
                            attempted += view
                            fresh.platform.registerPending(view)
                            if (attempted.size == position) throw IllegalStateException("synthetic pending add failure")
                        })
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    assertEquals(position, attempted.size)
                    assertEquals(position, fresh.platform.removeAttempts)
                    attempted.forEach { assertFalse(fresh.platform.isRegistered(it)) }
                    assertNull(field(fresh.service, "gateWindows").get(fresh.service))
                    assertEquals(0, fresh.platform.routeCalls + fresh.platform.homeCalls)
                    assertFalse(gate(fresh.service).cooldownActive())
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun realWindowManagerPendingRootIsRemovedBeforeFirstTraversal() {
        rule.scenario.onActivity { activity ->
            val view = View(activity)
            val manager = activity.windowManager
            val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                token = activity.window.decorView.windowToken
                alpha = 0f
            }
            val record = OwnedOverlayWindow(view, manager, GateWindowRole.VISUAL, params,
                AndroidOverlayPhysicalPlatform, { wm, root, layout -> wm.addView(root, layout) },
                { wm, root, layout -> wm.updateViewLayout(root, layout) })
            try {
                record.addAttempted = true
                manager.addView(view, params)
                assertFalse(view.isAttachedToWindow)
                assertTrue(view.parent != null)
                assertEquals(OverlayAttachment.ATTACHED, record.attachment())
                assertEquals(OverlayRemovalResult.DETACHED, OverlayWindowRemover.attempt(record, false))
                assertNull(view.parent)
            } finally {
                if (AndroidOverlayPhysicalPlatform.isRegistered(view)) manager.removeViewImmediate(view)
            }
        }
    }

    @Test fun partialInstallFailureIsStillOwned() {
        rule.scenario.onActivity { activity ->
            for (position in 1..4) for (attachThenThrow in listOf(false, true)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    var adds = 0
                    val attempted = mutableListOf<View>()
                    field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                        { _: WindowManager, view: View, _: WindowManager.LayoutParams ->
                            adds++; attempted += view
                            val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                            assertSame(view, episode.records.last().view)
                            assertTrue(episode.records.last().addAttempted)
                            assertNull(field(fresh.service, "completion").get(fresh.service))
                            if (adds != position || attachThenThrow) fresh.platform.attach(view)
                            if (adds == position) throw IllegalStateException("synthetic add failure")
                        })
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    assertEquals(position, adds)
                    attempted.forEach { assertFalse(fresh.platform.isAttached(it)) }
                    assertNull(field(fresh.service, "gateWindows").get(fresh.service))
                    assertNull(field(fresh.service, "completion").get(fresh.service))
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertEquals(0, fresh.platform.homeCalls + fresh.platform.routeCalls + fresh.disableCalls)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun partialAddsRecheckAuthorityBeforeArmingCompletion() {
        rule.scenario.onActivity { activity ->
            for (position in 1..4) {
                val fresh = freshService(activity, 1_000L)
                try {
                    var adds = 0
                    field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                        { _: WindowManager, view: View, _: WindowManager.LayoutParams ->
                            fresh.platform.attach(view)
                            assertNull(field(fresh.service, "completion").get(fresh.service))
                            if (++adds == position) Observation.connected = false
                        })
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    assertEquals(position, adds)
                    assertFalse(fresh.platform.attached)
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertNull(field(fresh.service, "completion").get(fresh.service))
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun eachWiredButtonAndCompletionWaitForTheLastButtonWindow() {
        rule.scenario.onActivity { activity ->
            for (action in listOf("messages", "home", "debug", "complete")) {
                val fresh = freshService(activity, 1_000L)
                try {
                    var copies = 0
                    field(fresh.service, "copyHook").set(fresh.service,
                        { _: SanitizedStructuralReport -> copies++; OverlayCopyResult.COPIED })
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                    fresh.platform.heldView = episode.ui.leaveInstagram
                    when (action) {
                        "messages" -> assertTrue(episode.ui.skipToMessages.performClick())
                        "home" -> assertTrue(episode.ui.leaveInstagram.performClick())
                        "debug" -> assertTrue(episode.ui.debugReport.performClick())
                        else -> {
                            fresh.now[0] += 10_000L
                            (field(fresh.service, "completion").get(fresh.service) as Runnable).run()
                        }
                    }
                    assertFalse(fresh.platform.isAttached(episode.ui.visualRoot))
                    assertTrue(fresh.platform.isAttached(episode.ui.leaveInstagram))
                    assertSame(episode, field(fresh.service, "gateWindows").get(fresh.service))
                    assertEquals(0, fresh.platform.routeCalls + fresh.platform.homeCalls + copies)
                    assertFalse(gate(fresh.service).cooldownActive())
                    assertNull(field(fresh.service, "timerView").get(fresh.service))
                    val retry = pendingRetry(fresh.service, "removalLoop")
                    fresh.platform.heldView = null
                    retry.run(); retry.run()
                    assertEquals(if (action == "messages") 1 else 0, fresh.platform.routeCalls)
                    assertEquals(if (action == "home") 1 else 0, fresh.platform.homeCalls)
                    assertEquals(if (action == "debug") 1 else 0, copies)
                    assertEquals(action == "messages" || action == "complete", gate(fresh.service).cooldownActive())
                } finally { destroyFresh(fresh) }
            }
        }
    }

    @Test fun fallbackUsesSavedIdentityAndInertParamsDespiteUpdateAndAddExceptions() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                lateinit var episode: GateOverlayWindows
                val calls = mutableListOf<Pair<View, String>>()
                val counts = java.util.IdentityHashMap<View, Int>()
                fresh.platform.removeScript = { view ->
                    calls += view to "remove"
                    val count = (counts[view] ?: 0) + 1; counts[view] = count
                    if (count == 1) throw IllegalStateException("first remove")
                    fresh.platform.register(view, false)
                }
                field(fresh.service, "overlayWindowUpdater").set(fresh.service,
                    { _: WindowManager, _: View, _: WindowManager.LayoutParams -> throw IllegalStateException("update") })
                field(fresh.service, "overlayWindowInstaller").set(fresh.service,
                    { _: WindowManager, view: View, p: WindowManager.LayoutParams ->
                        if (p.alpha != 0f) {
                            fresh.platform.attach(view)
                        } else {
                        calls += view to "add"
                        assertTrue(episode.records.any { it.view === view })
                        assertEquals(0f, p.alpha)
                        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                        assertFalse(view.isEnabled)
                        throw IllegalStateException("already added")
                        }
                    })
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                assertTrue(episode.ui.skipToMessages.performClick())
                episode.records.forEach { record ->
                    assertEquals(listOf("remove", "add", "remove"), calls.filter { it.first === record.view }.map { it.second })
                }
                assertEquals(1, fresh.platform.routeCalls)
                assertEquals(0, fresh.disableCalls)
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun prolongedSlowClosingSurvivesMessagingStormWithoutDisableOrNewAuthority() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                RemovalTraceStore.process.arm(1_000L)
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val episode = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                fresh.platform.detachOnRemove = false
                episode.ui.skipToMessages.performClick()
                repeat(19) { pendingRetry(fresh.service, "removalLoop").run() }
                assertTrue(episode.degraded)
                val slow = pendingRetry(fresh.service, "removalLoop")
                val roots = fresh.platform.currentRootCalls
                val adds = fresh.added.size
                val removes = fresh.platform.removeAttempts
                val snapshot = RemovalTraceStore.process.snapshot(1_000L)
                fresh.platform.rootBehavior = RootBehavior.MESSAGING
                repeat(200) { sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.instagram.android") }
                assertSame(slow, pendingRetry(fresh.service, "removalLoop"))
                assertEquals(roots, fresh.platform.currentRootCalls)
                assertEquals(adds, fresh.added.size); assertEquals(removes, fresh.platform.removeAttempts)
                assertEquals(0, fresh.disableCalls + fresh.platform.routeCalls + fresh.platform.homeCalls)
                assertTrue(Observation.connected)
                assertFalse(gate(fresh.service).cooldownActive())
                fresh.platform.detachOnRemove = true
                slow.run()
                assertNull(field(fresh.service, "gateWindows").get(fresh.service))
                assertEquals(0, fresh.platform.routeCalls)
                assertFalse(gate(fresh.service).cooldownActive())
                assertEquals(snapshot, RemovalTraceStore.process.snapshot(1_000L))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun staleCallbacksCannotCancelReplacementEpisodesPendingRemoval() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val old = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                val completion = field(fresh.service, "completion").get(fresh.service) as Runnable
                fresh.platform.detachOnRemove = false
                old.ui.skipToMessages.performClick()
                val retry = pendingRetry(fresh.service, "removalLoop")
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                fresh.platform.detachOnRemove = true
                retry.run()
                // Reset the old session using a confirmed foreign root, then admit a new episode.
                fresh.platform.rootBehavior = RootBehavior.FOREIGN
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.foreign")
                fresh.platform.rootBehavior = RootBehavior.INSTAGRAM
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                val replacement = field(fresh.service, "gateWindows").get(fresh.service) as GateOverlayWindows
                assertTrue(old.token != replacement.token)
                fresh.platform.detachOnRemove = false
                replacement.ui.leaveInstagram.performClick()
                val pending = pendingRetry(fresh.service, "removalLoop")
                val attempts = fresh.platform.removeAttempts
                completion.run(); retry.run()
                invoke(fresh.service, "attemptOverlayRemoval", old.token)
                invoke(fresh.service, "confirmOverlayRemoved", old.token, true)
                assertFalse(old.ui.skipToMessages.performClick())
                assertSame(pending, pendingRetry(fresh.service, "removalLoop"))
                assertEquals(attempts, fresh.platform.removeAttempts)
                assertSame(replacement, field(fresh.service, "gateWindows").get(fresh.service))
            } finally { destroyFresh(fresh) }
        }
    }

    @Test fun explicitStopDefersDisableUntilAllWindowsDetach() {
        rule.scenario.onActivity { activity ->
            val fresh = freshService(activity, 1_000L)
            try {
                sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                fresh.platform.detachOnRemove = false
                DoomAccessibilityService.disableObservation()
                assertEquals(0, fresh.disableCalls)
                assertTrue((field(fresh.service, "removalLoop").get(fresh.service) as OverlayRemovalRetryLoop).isPending)
                fresh.platform.detachOnRemove = true
                pendingRetry(fresh.service, "removalLoop").run()
                assertEquals(1, fresh.disableCalls)
                assertEquals(0, fresh.platform.routeCalls + fresh.platform.homeCalls)
            } finally { destroyFresh(fresh) }
            val empty = freshService(activity, 1_000L)
            try { DoomAccessibilityService.disableObservation(); assertEquals(1, empty.disableCalls) }
            finally { destroyFresh(empty) }
        }
    }

    @Test fun stopOnReconnectedServiceWaitsForRetiredOwnerWithoutRetiredActionContinuation() {
        rule.scenario.onActivity { activity ->
            val old = freshService(activity, 1_000L)
            var replacement: FreshService? = null
            try {
                sendEvent(old.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                old.platform.detachOnRemove = false
                old.service.onUnbind(null)
                val fresh = freshService(activity, 2_000L)
                replacement = fresh
                DoomAccessibilityService.disableObservation()
                assertEquals(0, fresh.disableCalls)
                pendingRetry(fresh.service, "removalLoop").run()
                assertEquals(0, fresh.disableCalls)
                old.platform.attached = false
                RetiringOverlayCleanup.reconcile()
                pendingRetry(fresh.service, "removalLoop").run()
                assertEquals(1, fresh.disableCalls)
                assertEquals(0, old.platform.routeCalls + old.platform.homeCalls)
            } finally {
                old.platform.attached = false
                RetiringOverlayCleanup.reconcile()
                replacement?.let(::destroyFresh)
                destroyFresh(old)
            }
        }
    }

    @Test fun unknownGateOrTimerAttachmentCannotReleaseDeferredDisable() {
        rule.scenario.onActivity { activity ->
            for (timerOnly in listOf(false, true)) {
                val fresh = freshService(activity, 1_000L)
                try {
                    if (timerOnly) startBubble(fresh)
                    else sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    fresh.platform.attachmentThrows = true
                    DoomAccessibilityService.disableObservation()
                    assertEquals(0, fresh.disableCalls)
                    val loop = if (timerOnly) "timerRemovalLoop" else "removalLoop"
                    val pending = pendingRetry(fresh.service, loop)
                    pending.run()
                    assertEquals(0, fresh.disableCalls)
                    fresh.platform.attachmentThrows = false
                    pendingRetry(fresh.service, loop).run()
                    assertEquals(1, fresh.disableCalls)
                } finally { fresh.platform.attachmentThrows = false; destroyFresh(fresh) }
            }
        }
    }

    @Test fun lifecycleRetainsOrTransfersExactlyOneRemovalOnlyChainAndBlocksReconnect() {
        rule.scenario.onActivity { activity ->
            for (terminal in listOf("interrupt", "unbind", "destroy")) {
                val fresh = freshService(activity, 1_000L)
                try {
                    sendEvent(fresh.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                    fresh.platform.detachOnRemove = false
                    when (terminal) {
                        "interrupt" -> fresh.service.onInterrupt()
                        "unbind" -> fresh.service.onUnbind(null)
                        else -> fresh.service.onDestroy()
                    }
                    assertEquals(0, fresh.disableCalls)
                    val loop = field(fresh.service, "removalLoop").get(fresh.service) as OverlayRemovalRetryLoop
                    assertEquals(terminal == "interrupt", loop.isPending)
                    if (terminal != "interrupt") {
                        assertTrue(RetiringOverlayCleanup.barrier.retiring)
                        assertNull(field(fresh.service, "gateWindows").get(fresh.service))
                        val replacement = freshService(activity, 2_000L)
                        try {
                            sendEvent(replacement.service, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.instagram.android")
                            assertEquals(0, replacement.installs)
                            assertEquals(0, replacement.platform.currentRootCalls)
                        } finally { destroyFresh(replacement) }
                    }
                    fresh.platform.attached = false
                    RetiringOverlayCleanup.reconcile()
                    if (terminal == "interrupt") pendingRetry(fresh.service, "removalLoop").run()
                    assertFalse(RetiringOverlayCleanup.barrier.retiring)
                    assertEquals(0, fresh.platform.routeCalls + fresh.platform.homeCalls)
                } finally { destroyFresh(fresh) }
            }
        }
    }

    private fun freshService(
        activity: MainActivity,
        startMs: Long,
        initialMode: InstallMode = InstallMode.SUCCEED,
        serviceContext: Context = activity.applicationContext,
    ): FreshService {
        Observation.updateReminderSettings(activity, ReminderSettings(enabled = true))
        Observation.accept(activity, true)
        Observation.setGateConsent(activity, true)
        Observation.connected = true
        val service = DoomAccessibilityService()
        field(service, "bound").setBoolean(service, true)
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, serviceContext)
        DoomAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, service)
        val states = mutableListOf<EntryGateState>()
        val platform = FakePlatform(false, true, RootBehavior.INSTAGRAM,
            MessagesRouteResult.CLICKED, states)
        platform.stateReader = { gate(service).state }
        platform.revokeInsideRoot = { }
        field(service, "overlayPlatform").set(service, platform)
        val now = longArrayOf(startMs)
        val clock: () -> Long = { now[0] }
        field(service, "monotonicClock").set(service, clock)
        val cooldown = field(gate(service), "cooldown").get(gate(service))
        field(cooldown, "monotonicNowMs").set(cooldown, clock)
        lateinit var fresh: FreshService
        fresh = FreshService(service, platform, now, initialMode)
        field(service, "overlayWindowInstaller").set(
            service,
            { _: WindowManager, view: View, params: WindowManager.LayoutParams ->
                fresh.added += view to WindowManager.LayoutParams().apply { copyFrom(params) }
                // Count visible episodes (visual gate or timer), not recovery or button windows.
                if (params.alpha != 0f && view !is android.widget.Button) fresh.installs++
                when (fresh.installMode) {
                    InstallMode.SUCCEED -> platform.attach(view)
                    InstallMode.FAIL -> throw IllegalStateException("synthetic install failure")
                    InstallMode.REJECT_CONNECTION -> {
                        platform.attach(view)
                        Observation.connected = false
                    }
                }
            }
        )
        field(service, "overlayWindowUpdater").set(service,
            { _: WindowManager, _: View, _: WindowManager.LayoutParams -> })
        field(service, "disableService").set(service, { fresh.disableCalls++ })
        return fresh
    }

    private fun destroyFresh(fresh: FreshService) {
        try {
            fresh.platform.attached = false
            RetiringOverlayCleanup.reconcile()
            invoke(fresh.service, "cancelAndBypass")
        } finally {
            try {
                fresh.service.onDestroy()
            } finally {
                DoomAccessibilityService::class.java.getDeclaredField("instance")
                    .apply { isAccessible = true }.set(null, null)
            }
        }
    }

    private fun fixture(
        attached: Boolean = false,
        detachOnRemove: Boolean = true,
        rootBehavior: RootBehavior = RootBehavior.INSTAGRAM,
        routeResult: MessagesRouteResult = MessagesRouteResult.CLICKED,
    ): Fixture {
        lateinit var result: Fixture
        rule.scenario.onActivity { activity ->
            Observation.updateReminderSettings(activity, ReminderSettings(enabled = true))
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            Observation.connected = true
            val service = DoomAccessibilityService()
            field(service, "bound").setBoolean(service, true)
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, activity.applicationContext)
            DoomAccessibilityService::class.java.getDeclaredField("instance")
                .apply { isAccessible = true }.set(null, service)
            val entryGate = gate(service)
            val ticket = entryGate.beginInstagramSession()
            entryGate.observeInstagram(0L, ticket)
            entryGate.overlayShown(0L, ticket)
            val view = View(activity)
            val states = mutableListOf<EntryGateState>()
            val platform = FakePlatform(attached, detachOnRemove, rootBehavior, routeResult, states)
            platform.register(view, attached)
            platform.stateReader = { entryGate.state }
            platform.revokeInsideRoot = { }
            field(service, "ticket").set(service, ticket)
            field(service, "windowManager").set(
                service,
                activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            )
            field(service, "overlayPlatform").set(service, platform)
            val guard = field(service, "callbackGuard").get(service) as OverlayCallbackGuard
            val token = guard.open(ticket)
            field(service, "overlayToken").set(service, token)
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {
                invoke(service, "requestDebugReport", token)
            })
            field(service, "overlayUi").set(service, ui)
            val manager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val episode = GateOverlayWindows(token, ui).apply {
                installing = false
                records += OwnedOverlayWindow(view, manager, GateWindowRole.VISUAL,
                    GateOverlayWindows.parameters(GateWindowDescriptor(GateWindowRole.VISUAL, GateWindowRect(0, 0, 100, 100))),
                    platform, { _, root, _ -> platform.attach(root) }, { _, _, _ -> }).also { it.addAttempted = true }
            }
            field(service, "gateWindows").set(service, episode)
            result = Fixture(service, platform, ticket, token, view, ui, activity)
            manualFixtures += result
        }
        instrumentation.waitForIdleSync()
        return result
    }

    private fun runRemovalRetry(service: DoomAccessibilityService, token: OverlayCallbackToken) {
        val loop = field(service, "removalLoop").get(service) as OverlayRemovalRetryLoop
        val pending = field(loop, "pending").get(loop) as? Runnable
        if (pending != null) pending.run() else invoke(service, "attemptOverlayRemoval", token)
        RetiringOverlayCleanup.reconcile()
    }

    private fun pendingRetry(service: DoomAccessibilityService, name: String): Runnable {
        val loop = field(service, name).get(service) as OverlayRemovalRetryLoop
        return field(loop, "pending").get(loop) as Runnable
    }

    private fun request(
        service: DoomAccessibilityService,
        action: OverlayRemovalAction,
        token: OverlayCallbackToken,
    ) {
        rule.scenario.onActivity { requestOverlayRemovalWithToken(service, action, token) }
        instrumentation.waitForIdleSync()
    }

    private fun requestOverlayRemovalWithToken(
        service: DoomAccessibilityService,
        action: OverlayRemovalAction,
        token: OverlayCallbackToken,
    ) {
        val cause = when (action) {
            OverlayRemovalAction.HOME -> RemovalTraceMark.USER_HOME
            OverlayRemovalAction.NAVIGATE_MESSAGES -> RemovalTraceMark.USER_MESSAGES
            OverlayRemovalAction.COMPLETE -> RemovalTraceMark.TIMER_COMPLETE
            OverlayRemovalAction.PRESERVE_REPORT -> RemovalTraceMark.APP_RETURN
            OverlayRemovalAction.CAPTURE_DEBUG -> RemovalTraceMark.USER_DEBUG
            OverlayRemovalAction.BYPASS -> RemovalTraceMark.SAFETY_OVERRIDE
            OverlayRemovalAction.RESET_OUTSIDE -> RemovalTraceMark.EVENT_PACKAGE_RESET
        }
        invoke(
            service,
            "requestOverlayRemoval",
            action,
            if (action == OverlayRemovalAction.PRESERVE_REPORT) null else token,
            cause,
            RemovalTraceEvent.NA,
            RemovalTraceOwner.NA,
            RemovalTraceRoot.NOT_READ,
        )
    }

    @Suppress("DEPRECATION")
    private fun sendEvent(service: DoomAccessibilityService, type: Int, packageName: String?) {
        val event = AccessibilityEvent.obtain(type)
        try {
            event.packageName = packageName
            service.onAccessibilityEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClockHolder.now() + 2_500L
        while (!condition() && SystemClockHolder.now() < deadline) {
            Thread.sleep(25)
            instrumentation.waitForIdleSync()
        }
        assertTrue("timed out waiting for service action", condition())
    }

    private fun gate(service: DoomAccessibilityService): InstagramEntryGate =
        field(service, "entryGate").get(service) as InstagramEntryGate

    private fun track(service: DoomAccessibilityService): DoomAccessibilityService {
        manualServices += service
        return service
    }

    private fun field(target: Any, name: String): Field =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }
        method.invoke(target, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeResult(target: Any, name: String, vararg args: Any?): T {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }
        return method.invoke(target, *args) as T
    }

    private object SystemClockHolder {
        fun now() = android.os.SystemClock.elapsedRealtime()
    }
}
