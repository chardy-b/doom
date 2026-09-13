package com.chardy.doom

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal interface OverlayPlatform {
    fun isAttached(view: View): Boolean
    fun removeImmediate(manager: WindowManager, view: View)
    fun launchInbox(): InboxLaunchResult
    fun currentForegroundPackage(): String?
    fun performHome(): Boolean
}

/** Diagnostic-only, default-off entry pause. A user tap may best-effort route to Instagram messages. */
class DoomAccessibilityService : AccessibilityService() {
    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val GATE_DURATION_MS = 5_000L
        private const val WATCHDOG_INTERVAL_MS = 50L
        private const val WATCHDOG_UNCERTAINTY_GRACE_MS = 150L
        private const val REMOVAL_RETRY_INTERVAL_MS = 50L
        private const val MAX_REMOVAL_ATTEMPTS = 20
        private var instance: DoomAccessibilityService? = null

        fun cancelEntryGate() {
            instance?.cancelAndBypass()
            if (instance == null) Observation.entryGateState = EntryGateState.OUTSIDE
        }

        fun disableObservation() {
            instance?.cancelAndBypass()
            Observation.connected = false
            Observation.clear()
            instance?.disableSelf()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }
    private val entryGate = InstagramEntryGate(
        durationMs = GATE_DURATION_MS,
        enabled = { Observation.gateConsent && Observation.consent && Observation.connected },
        monotonicNowMs = monotonicClock
    )
    private var ticket: GateTicket? = null
    private var overlay: View? = null
    private var overlayUi: EntryGateOverlayUi? = null
    private var windowManager: WindowManager? = null
    private var watchdog: Runnable? = null
    private var completion: Runnable? = null
    private var removalRetry: Runnable? = null
    private var mainActivityReturnObserved = false
    private val foregroundWatchdog = OverlayForegroundWatchdog(
        instagramPackage = INSTAGRAM,
        doomPackage = "com.chardyb.doom",
        uncertaintyGraceMs = WATCHDOG_UNCERTAINTY_GRACE_MS,
    )
    private val removalPolicy = OverlayRemovalPolicy(MAX_REMOVAL_ATTEMPTS)
    private val callbackGuard = OverlayCallbackGuard()
    private var overlayToken: OverlayCallbackToken? = null
    private var overlayPlatform: OverlayPlatform = object : OverlayPlatform {
        override fun isAttached(view: View) = view.isAttachedToWindow
        override fun removeImmediate(manager: WindowManager, view: View) = manager.removeViewImmediate(view)
        override fun launchInbox(): InboxLaunchResult =
            AndroidInstagramInboxLauncher { intent -> this@DoomAccessibilityService.startActivity(intent) }.launch()
        override fun currentForegroundPackage(): String? {
            val root = try { rootInActiveWindow } catch (_: RuntimeException) { null } ?: return null
            return try { root.packageName?.toString() } finally { root.recycle() }
        }
        override fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        resetOutside()
        Observation.connected = false
        Observation.clear()
        Observation.load(this)
        if (!Observation.consent) {
            disableSelf()
            return
        }
        Observation.connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val packageName = event?.packageName?.toString()
            // Doom's own accessibility-overlay updates are not app-session transitions.
            if (packageName == applicationContext.packageName) {
                if (isMainActivityReturn(event)) {
                    mainActivityReturnObserved = true
                    // This also resets a completed/granted session when no overlay remains.
                    if (overlay == null) {
                        entryGate.leaveInstagram()
                        ticket = null
                        mainActivityReturnObserved = false
                        publishGateState()
                    } else requestOverlayRemoval(
                        OverlayRemovalAction.PRESERVE_REPORT, overlayToken
                    )
                }
                // Ignore all other Doom-owned events, including overlay updates.
                return
            }
            if (packageName != INSTAGRAM) {
                resetOutside()
                return
            }
            // Suppression is checked before ticket creation, root access, and collection. It is
            // deliberately a no-op so navigation and transient Instagram events cannot restart
            // presentation or mutate the bounded report while the cooldown is active.
            if (Observation.gateConsent && entryGate.cooldownActive()) return
            if (!Observation.consent || !Observation.connected) {
                cancelAndBypass()
                return
            }

            val activeTicket = if (Observation.gateConsent) {
                ticket ?: entryGate.beginInstagramSessionIfEligible()?.also {
                    ticket = it
                    publishGateState()
                }
            } else {
                null
            }

            val root = rootInActiveWindow
            if (root == null) {
                Observation.record(null)
                if (activeTicket != null) cancelAndBypass()
                return
            }
            if (root.packageName?.toString() != INSTAGRAM) {
                root.recycle()
                Observation.record(null)
                if (activeTicket != null) cancelAndBypass()
                return
            }

            collect(root)
            if (activeTicket == null) {
                if (overlay != null) cancelAndBypass()
                else Observation.entryGateState = EntryGateState.OUTSIDE
                return
            }
            if (entryGate.state != EntryGateState.AWAITING &&
                entryGate.state != EntryGateState.GATING
            ) return

            val shouldShow = entryGate.observeInstagram(monotonicClock(), activeTicket)
            publishGateState()
            if (shouldShow) {
                if (overlay == null) installOverlay(activeTicket)
                else overlayToken?.let { renderOverlay(activeTicket, it) }
            } else {
                requestOverlayRemoval(OverlayRemovalAction.BYPASS)
            }
        } catch (_: RuntimeException) {
            failOpen()
        }
    }

    @Suppress("DEPRECATION") // Release transient nodes on older supported Android versions too.
    private fun collect(root: AccessibilityNodeInfo) {
        val rootPackage = root.packageName?.toString()
        if (rootPackage == applicationContext.packageName) {
            root.recycle()
            return
        }
        if (rootPackage != INSTAGRAM) {
            root.recycle()
            Observation.record(null)
            return
        }

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        try {
            val builder = SanitizedStructuralReport.Builder()
            var nodes = 0
            while (queue.isNotEmpty() && nodes < SanitizedStructuralReport.MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                try {
                    nodes++
                    if (node.packageName?.toString() != INSTAGRAM) {
                        builder.markTruncated()
                        continue
                    }
                    val children = node.childCount
                    builder.add(
                        depth,
                        node.viewIdResourceName,
                        node.className,
                        children,
                        node.isClickable,
                        node.isScrollable,
                        node.isEditable,
                        node.isSelected,
                        node.isChecked
                    )
                    if (depth < SanitizedStructuralReport.MAX_DEPTH) {
                        val limit = minOf(
                            children,
                            SanitizedStructuralReport.MAX_NODES - nodes - queue.size
                        )
                        if (limit < children) builder.markTruncated()
                        repeat(limit.coerceAtLeast(0)) { index ->
                            val child = node.getChild(index)
                            if (child == null) builder.markTruncated()
                            else queue.add(child to depth + 1)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
            if (queue.isNotEmpty()) builder.markTruncated()
            Observation.record(builder.build())
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().first.recycle()
        }
    }

    private fun installOverlay(activeTicket: GateTicket) {
        if (overlay != null) return
        try {
            val token = callbackGuard.open(activeTicket)
            val ui = EntryGateOverlayViewFactory.create(
                this,
                onSkipToMessages = {
                    requestOverlayRemoval(OverlayRemovalAction.NAVIGATE_MESSAGES, token)
                },
                onLeaveInstagram = {
                    requestOverlayRemoval(OverlayRemovalAction.HOME, token)
                },
                onCopyCurrentReport = { handleOverlayCopy(activeTicket, token) }
            )
            val box = ui.root
            overlayToken = token

            val parameters = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            manager.addView(box, parameters)
            windowManager = manager
            overlay = box
            overlayUi = ui
            overlayToken = token

            val shownAt = monotonicClock()
            if (!entryGate.overlayShown(shownAt, activeTicket)) {
                requestSafetyCleanup(OverlayRemovalAction.BYPASS)
                return
            }
            if (!entryGate.admitForDisplay(activeTicket)) {
                requestSafetyCleanup(OverlayRemovalAction.BYPASS)
                return
            }
            foregroundWatchdog.reset(shownAt)
            renderOverlay(activeTicket, token)
            publishGateState()
            completion = Runnable {
                if (callbackGuard.acceptsVisible(token)) {
                    requestOverlayRemoval(OverlayRemovalAction.COMPLETE, token)
                }
            }.also { handler.postDelayed(it, GATE_DURATION_MS) }
            watchdog = object : Runnable {
                override fun run() {
                    if (overlay == null || !callbackGuard.acceptsVisible(token)) return
                    try {
                        val packageName = try {
                            val activeRoot = rootInActiveWindow
                            try {
                                activeRoot?.packageName?.toString()
                            } finally {
                                activeRoot?.recycle()
                            }
                        } catch (_: RuntimeException) {
                            null
                        }
                        val decision = foregroundWatchdog.observe(
                            monotonicClock(),
                            packageName,
                            packageName == applicationContext.packageName && mainActivityReturnObserved,
                        )
                        if (decision == OverlayForegroundDecision.FAIL_OPEN) {
                            failOpen()
                            return
                        }
                        renderOverlay(activeTicket, token)
                        handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    } catch (_: RuntimeException) {
                        failOpen()
                    }
                }
            }.also { handler.post(it) }
        } catch (_: RuntimeException) {
            callbackGuard.invalidateVisible()
            requestSafetyCleanup(OverlayRemovalAction.BYPASS)
        }
    }

    private fun requestOverlayRemoval(
        action: OverlayRemovalAction,
        token: OverlayCallbackToken? = null
    ) {
        val currentToken = overlayToken
        if (overlay == null) return
        val accepted = if (token != null) callbackGuard.beginClosing(token)
        else currentToken != null && callbackGuard.beginClosing(currentToken)
        if (!accepted) return
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        overlayUi?.dispose()
        removalPolicy.request(action)
        attemptOverlayRemoval(currentToken)
    }

    private fun requestSafetyCleanup(action: OverlayRemovalAction) {
        callbackGuard.invalidateVisible()
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        overlayUi?.dispose()
        removalPolicy.requestSafetyCleanup(action)
        attemptOverlayRemoval(overlayToken)
    }

    private fun attemptOverlayRemoval(token: OverlayCallbackToken? = overlayToken) {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        val view = overlay
        if (view == null || !overlayPlatform.isAttached(view)) {
            confirmOverlayRemoved(token)
            return
        }
        if (token != null && !callbackGuard.acceptsRemoval(token)) return

        try {
            windowManager?.let { overlayPlatform.removeImmediate(it, view) }
        } catch (_: RuntimeException) {
            // Attachment state below is the postcondition; an exception alone is not success.
        }
        if (!overlayPlatform.isAttached(view)) {
            confirmOverlayRemoved(token)
            return
        }

        when (removalPolicy.failedAttempt()) {
            OverlayRemovalDecision.RETRY -> {
                removalRetry = Runnable {
                    if (token == null || callbackGuard.acceptsRemoval(token)) attemptOverlayRemoval(token)
                }
                    .also { handler.postDelayed(it, REMOVAL_RETRY_INTERVAL_MS) }
            }
            OverlayRemovalDecision.DISABLE_SERVICE -> {
                // Retain the attached view and manager references; never release a pending action.
                // Android service teardown is the final platform-owned removal path.
                removalPolicy.requestSafetyCleanup(OverlayRemovalAction.BYPASS)
                disableSelf()
            }
        }
    }

    private fun confirmOverlayRemoved(detachedToken: OverlayCallbackToken? = overlayToken) {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        val detachedView = overlay
        if (detachedView != null && overlayPlatform.isAttached(detachedView)) return
        if (detachedToken != null && overlayToken != detachedToken) return
        val ownsDetachedEpisode = detachedToken != null && overlayToken == detachedToken
        if (detachedToken != null) callbackGuard.detached(detachedToken)
        overlayUi?.dispose()
        overlayUi = null
        overlay = null
        windowManager = null
        overlayToken = null
        val action = removalPolicy.confirmedDetached()
        if (action != OverlayRemovalAction.PRESERVE_REPORT) mainActivityReturnObserved = false
        when (action) {
            OverlayRemovalAction.BYPASS -> {
                entryGate.cancel()
                publishGateState()
                Observation.clear()
            }
            OverlayRemovalAction.NAVIGATE_MESSAGES -> {
                val routeTicket = detachedToken?.ticket
                val routeStillAuthorized = routeTicket != null &&
                    routeTicket == ticket &&
                    Observation.consent && Observation.gateConsent && Observation.connected &&
                    entryGate.state == EntryGateState.GATING &&
                    currentInstagramForeground()
                if (!routeStillAuthorized || !entryGate.bypass(routeTicket!!)) {
                    entryGate.cancel()
                    publishGateState()
                    Observation.clear()
                    return
                }
                publishGateState()
                Observation.clear()
                if (ownsDetachedEpisode) overlayPlatform.launchInbox()
            }
            OverlayRemovalAction.PRESERVE_REPORT -> {
                if (!mainActivityReturnObserved) return
                entryGate.leaveInstagram()
                ticket = null
                mainActivityReturnObserved = false
                publishGateState()
            }
            OverlayRemovalAction.RESET_OUTSIDE -> {
                entryGate.leaveInstagram()
                ticket = null
                publishGateState()
                Observation.clear()
            }
            OverlayRemovalAction.COMPLETE -> {
                val activeTicket = ticket
                if (activeTicket == null ||
                    !entryGate.complete(monotonicClock(), activeTicket)
                ) entryGate.cancel()
                publishGateState()
            }
            OverlayRemovalAction.HOME -> {
                val activeTicket = ticket
                if (activeTicket == null || !entryGate.bypass(activeTicket)) entryGate.cancel()
                publishGateState()
                Observation.clear()
                overlayPlatform.performHome()
            }
            null -> Unit
        }
    }

    private fun cancelAndBypass() {
        requestSafetyCleanup(OverlayRemovalAction.BYPASS)
    }

    private fun failOpen() {
        cancelAndBypass()
    }

    private fun resetOutside() {
        requestSafetyCleanup(OverlayRemovalAction.RESET_OUTSIDE)
    }

    private fun renderOverlay(activeTicket: GateTicket, token: OverlayCallbackToken) {
        if (!callbackGuard.acceptsVisible(token) || overlayToken != token) return
        val reduceMotion = try { !ValueAnimator.areAnimatorsEnabled() } catch (_: RuntimeException) { true }
        val model = EntryGateOverlayModel.from(
            entryGate.remainingMs(monotonicClock(), activeTicket),
            GATE_DURATION_MS,
            reduceMotion,
            Observation.overlayDiagnosticStatus()
        )
        overlayUi?.render(model)
    }

    private fun handleOverlayCopy(activeTicket: GateTicket, token: OverlayCallbackToken) {
        if (callbackGuard.acceptsVisible(token) && overlayToken == token) {
            overlayUi?.showCopyResult(Observation.copyCurrentReportFromOverlay(this))
            renderOverlay(activeTicket, token)
        }
    }

    private fun currentInstagramForeground(): Boolean {
        return overlayPlatform.currentForegroundPackage() == INSTAGRAM
    }

    private fun publishGateState() {
        Observation.entryGateState = entryGate.state
    }

    private fun isMainActivityReturn(event: AccessibilityEvent?): Boolean =
        event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString() == MainActivity::class.java.name

    override fun onInterrupt() {
        cancelAndBypass()
        Observation.connected = false
        Observation.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        cancelAndBypass()
        ticket = null
        if (instance === this) instance = null
        Observation.connected = false
        Observation.clear()
    }
}
