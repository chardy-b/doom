package com.chardy.doom

import android.content.Context
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Field

/** Runtime action-order tests use the service's production platform boundary with fake side effects. */
class EntryGateServiceActionTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private enum class RootBehavior {
        INSTAGRAM,
        NULL_PACKAGE,
        FOREIGN,
        MISSING,
        THROW,
        REVOKE_CONSENT,
        REVOKE_GATE_CONSENT,
        REVOKE_CONNECTION,
    }

    private class FakePlatform(
        @Volatile var attached: Boolean,
        @Volatile var detachOnRemove: Boolean = true,
        var rootBehavior: RootBehavior = RootBehavior.INSTAGRAM,
        val routeResult: MessagesRouteResult = MessagesRouteResult.CLICKED,
        val stateAtRoute: MutableList<EntryGateState>,
    ) : OverlayPlatform {
        @Volatile var removeAttempts = 0
        @Volatile var currentRootCalls = 0
        @Volatile var recycledRoots = 0
        @Volatile var routeCalls = 0
        @Volatile var routeThrows = false
        @Volatile var homeCalls = 0
        lateinit var revokeInsideRoot: () -> Unit

        override fun isAttached(view: View) = attached

        override fun removeImmediate(manager: WindowManager, view: View) {
            removeAttempts++
            if (detachOnRemove) attached = false
        }

        override fun currentRoot(): AccessibilityNodeInfo? {
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
                else -> Unit
            }
            return AccessibilityNodeInfo.obtain().apply {
                packageName = when (rootBehavior) {
                    RootBehavior.NULL_PACKAGE -> null
                    RootBehavior.FOREIGN -> "com.example.foreign"
                    else -> "com.instagram.android"
                }
            }
        }

        override fun recycleRoot(root: AccessibilityNodeInfo) {
            recycledRoots++
            root.recycle()
        }

        override fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult {
            routeCalls++
            if (routeThrows) throw IllegalStateException("route unavailable")
            stateAtRoute += stateReader()
            return routeResult
        }

        override fun performHome(): Boolean {
            homeCalls++
            return true
        }

        lateinit var stateReader: () -> EntryGateState
    }

    private data class Fixture(
        val service: DoomAccessibilityService,
        val platform: FakePlatform,
        val ticket: GateTicket,
        val token: OverlayCallbackToken,
        val view: View,
        val activity: MainActivity,
    )

    @After fun cleanUp() {
        rule.scenario.onActivity {
            Observation.setGateConsent(it, false)
            Observation.accept(it, false)
            Observation.connected = false
            Observation.clear()
        }
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
    }

    @Test fun failedRouteLeavesSameSessionBypassedWithoutRegating() {
        val fixture = fixture(routeResult = MessagesRouteResult.FAILED)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        waitFor { fixture.platform.routeCalls == 1 }
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
        assertFalse(gate(fixture.service).observeInstagram(1L, fixture.ticket))
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
    }

    @Test fun missingRootFailsClosedWithoutRouteOrRootRecycle() {
        val fixture = fixture(rootBehavior = RootBehavior.MISSING)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(0, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun currentRootExceptionFailsClosedWithoutRouteOrRootRecycle() {
        val fixture = fixture(rootBehavior = RootBehavior.THROW)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(0, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun obtainedNullPackageRootIsNotMissingAndIsRecycledOnceWithoutRoute() {
        val fixture = fixture(rootBehavior = RootBehavior.NULL_PACKAGE)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun routeExceptionFailsClosedAndRecyclesObtainedRootExactlyOnce() {
        val fixture = fixture()
        fixture.platform.routeThrows = true
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(1, fixture.platform.currentRootCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(1, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun consentRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_CONSENT)
        fixture.platform.revokeInsideRoot = { Observation.accept(fixture.activity, false) }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun gateConsentRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_GATE_CONSENT)
        fixture.platform.revokeInsideRoot = { Observation.setGateConsent(fixture.activity, false) }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
    }

    @Test fun connectionRevokedInsideCurrentRootRecheckVetoesRouteAndRecyclesOnce() {
        val fixture = fixture(rootBehavior = RootBehavior.REVOKE_CONNECTION)
        fixture.platform.revokeInsideRoot = { Observation.connected = false }
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.recycledRoots)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
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
    }

    @Test fun staleOverlayCopyTokenCannotReachServiceCopyGuard() {
        val fixture = fixture()
        rule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val overlayUi = EntryGateOverlayViewFactory.create(activity, {}, {}, {})
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content.addView(overlayUi.root, ViewGroup.LayoutParams(-1, -1))
            field(fixture.service, "overlayUi").set(fixture.service, overlayUi)
            try {
                val report = SanitizedStructuralReport.Builder().apply {
                    add(0, "com.instagram.android:id/feed", "android.widget.TextView", 0, false, false, false, false, false)
                }.build()
                Observation.record(report)
                val expected = requireNotNull(Observation.report).text
                invoke(fixture.service, "handleOverlayCopy", fixture.ticket, fixture.token)
                assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())

                clipboard.setPrimaryClip(ClipData.newPlainText("test", "sentinel"))
                val guard = field(fixture.service, "callbackGuard").get(fixture.service) as OverlayCallbackGuard
                val currentToken = guard.open(fixture.ticket)
                field(fixture.service, "overlayToken").set(fixture.service, currentToken)
                val staleToken = fixture.token
                assertFalse(guard.acceptsVisible(staleToken))
                invoke(fixture.service, "handleOverlayCopy", fixture.ticket, staleToken)
                assertEquals("sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
            } finally {
                overlayUi.dispose()
                content.removeView(overlayUi.root)
                field(fixture.service, "overlayUi").set(fixture.service, null)
            }
        }
    }

    @Test fun staleCompletionAndRetryFromOverlayACannotRemoveOrRouteOverlayB() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        val oldToken = fixture.token
        request(fixture.service, OverlayRemovalAction.COMPLETE, oldToken)
        lateinit var newView: View
        rule.scenario.onActivity { activity ->
            val guard = field(fixture.service, "callbackGuard").get(fixture.service) as OverlayCallbackGuard
            newView = View(activity)
            val newToken = guard.open(fixture.ticket)
            field(fixture.service, "overlay").set(fixture.service, newView)
            field(fixture.service, "overlayToken").set(fixture.service, newToken)
            // Exercise the queued retry boundary deterministically on the main thread.
            invoke(fixture.service, "attemptOverlayRemoval", oldToken)
            requestOverlayRemovalWithToken(fixture.service, OverlayRemovalAction.COMPLETE, oldToken)
        }
        instrumentation.waitForIdleSync()
        assertSame(newView, field(fixture.service, "overlay").get(fixture.service))
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.removeAttempts)
    }

    @Test fun safetyInterruptAndReacceptCannotReleasePendingSkip() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity {
            fixture.service.onInterrupt()
            Observation.setGateConsent(it, true)
        }
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { !fixture.platform.attached }
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(EntryGateState.BYPASSED, gate(fixture.service).state)
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
    }

    @Test fun disconnectDuringPendingSkipVetoesRouteAndClearsConnection() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        rule.scenario.onActivity { fixture.service.onUnbind(null) }
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { !fixture.platform.attached }
        assertEquals(0, fixture.platform.routeCalls)
        assertFalse(Observation.connected)
    }

    @Test fun homeBeatsPendingSkipAndDoesNotRouteMessages() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        rule.scenario.onActivity { fixture.platform.detachOnRemove = true }
        waitFor { fixture.platform.homeCalls == 1 }
        assertEquals(0, fixture.platform.routeCalls)
        assertEquals(1, fixture.platform.homeCalls)
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
    }

    @Test fun foreignOrMissingForegroundSuppressesRouteAndHome() {
        listOf(RootBehavior.FOREIGN, RootBehavior.NULL_PACKAGE).forEach { behavior ->
            val fixture = fixture(rootBehavior = behavior)
            request(fixture.service, OverlayRemovalAction.NAVIGATE_MESSAGES, fixture.token)
            waitFor { !fixture.platform.attached }
            assertEquals(0, fixture.platform.routeCalls)
            assertEquals(0, fixture.platform.homeCalls)
            assertEquals(1, fixture.platform.recycledRoots)
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

    @Test fun removalExhaustionVetoesPendingHomeAndLateDetach() {
        val fixture = fixture(attached = true, detachOnRemove = false)
        request(fixture.service, OverlayRemovalAction.HOME, fixture.token)
        waitFor { fixture.platform.removeAttempts >= 20 }
        assertEquals(0, fixture.platform.homeCalls)
        rule.scenario.onActivity { fixture.platform.attached = false }
        rule.scenario.onActivity { invoke(fixture.service, "attemptOverlayRemoval", fixture.token) }
        assertEquals(0, fixture.platform.homeCalls)
    }

    private fun fixture(
        attached: Boolean = false,
        detachOnRemove: Boolean = true,
        rootBehavior: RootBehavior = RootBehavior.INSTAGRAM,
        routeResult: MessagesRouteResult = MessagesRouteResult.CLICKED,
    ): Fixture {
        lateinit var result: Fixture
        rule.scenario.onActivity { activity ->
            Observation.accept(activity, true)
            Observation.setGateConsent(activity, true)
            Observation.connected = true
            val service = DoomAccessibilityService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, activity.applicationContext)
            val entryGate = gate(service)
            val ticket = entryGate.beginInstagramSession()
            entryGate.observeInstagram(0L, ticket)
            entryGate.overlayShown(0L, ticket)
            val view = View(activity)
            val states = mutableListOf<EntryGateState>()
            val platform = FakePlatform(attached, detachOnRemove, rootBehavior, routeResult, states)
            platform.stateReader = { entryGate.state }
            platform.revokeInsideRoot = { }
            field(service, "ticket").set(service, ticket)
            field(service, "overlay").set(service, view)
            field(service, "windowManager").set(
                service,
                activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            )
            field(service, "overlayPlatform").set(service, platform)
            val guard = field(service, "callbackGuard").get(service) as OverlayCallbackGuard
            val token = guard.open(ticket)
            field(service, "overlayToken").set(service, token)
            result = Fixture(service, platform, ticket, token, view, activity)
        }
        instrumentation.waitForIdleSync()
        return result
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
        invoke(service, "requestOverlayRemoval", action, token)
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

    private fun field(target: Any, name: String): Field =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }
        method.invoke(target, *args)
    }

    private object SystemClockHolder {
        fun now() = android.os.SystemClock.elapsedRealtime()
    }
}
