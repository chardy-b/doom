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
    fun currentRoot(): AccessibilityNodeInfo?
    /** Event-root seam; production reads the same root property used by the baseline path. */
    fun eventRoot(): AccessibilityNodeInfo? = currentRoot()
    /** Package-only seam; production does not inspect any other root property here. */
    fun readRootPackage(root: AccessibilityNodeInfo): String? = root.packageName?.toString()
    fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult
    fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
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
            RemovalTraceStore.process.clear()
            instance?.disableSelf()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }
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
    // Private, production-default seam used only by instrumentation to keep the real install path.
    private var overlayWindowInstaller: (WindowManager, View, WindowManager.LayoutParams) -> Unit =
        { manager, view, parameters -> manager.addView(view, parameters) }
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
        override fun currentRoot(): AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }
        override fun eventRoot(): AccessibilityNodeInfo? = rootInActiveWindow
        override fun readRootPackage(root: AccessibilityNodeInfo): String? = root.packageName?.toString()
        override fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult =
            InstagramMessagesRouter.route(root)
        override fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
        override fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        resetOutside(cause = RemovalTraceMark.SERVICE_CONNECTED_RESET)
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
            val traceCapturing = RemovalTraceStore.process.isCapturing()
            val eventKind = if (traceCapturing) traceEventKind(event) else RemovalTraceEvent.NA
            val owner = if (traceCapturing) traceOwner(packageName) else RemovalTraceOwner.NA
            if (overlay != null &&
                (!Observation.consent || !Observation.gateConsent || !Observation.connected)
            ) {
                requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS,
                    RemovalTraceMark.EVENT_DENIED,
                    eventKind,
                    owner,
                )
                return
            }
            // Doom's own accessibility-overlay updates are not app-session transitions.
            if (packageName == applicationContext.packageName) {
                traceRecord(RemovalTraceMark.EVENT_IGNORED_OWN, eventKind, owner)
                if (isMainActivityReturn(event)) {
                    mainActivityReturnObserved = true
                    // This also resets a completed/granted session when no overlay remains.
                    if (overlay == null) {
                        entryGate.leaveInstagram()
                        ticket = null
                        mainActivityReturnObserved = false
                        publishGateState()
                    } else requestOverlayRemoval(
                        OverlayRemovalAction.PRESERVE_REPORT,
                        overlayToken,
                        RemovalTraceMark.APP_RETURN
                    )
                }
                // Ignore all other Doom-owned events, including overlay updates.
                return
            }
            if (packageName != INSTAGRAM) {
                val visibleView = overlay
                val visibleToken = overlayToken
                val visibleTicket = ticket
                if (visibleView == null) {
                    resetOutside(cause = RemovalTraceMark.EVENT_PACKAGE_RESET,
                        event = eventKind, owner = owner)
                    return
                }
                // A non-null view with no current token/ticket is already closing or stale;
                // preserve the old safety veto without reading a root or restarting it.
                if (visibleToken == null || visibleTicket == null) {
                    resetOutside(
                        cause = RemovalTraceMark.EVENT_PACKAGE_RESET,
                        event = eventKind,
                        owner = owner,
                    )
                    return
                }
                if (!callbackGuard.acceptsVisible(visibleToken) ||
                    visibleTicket != ticket ||
                    visibleTicket.generation != entryGate.generation ||
                    overlay !== visibleView ||
                    entryGate.state != EntryGateState.GATING
                ) {
                    resetOutside(
                        cause = RemovalTraceMark.EVENT_PACKAGE_RESET,
                        event = eventKind,
                        owner = owner,
                    )
                    return
                }

                val sample = readPackageRoot { overlayPlatform.eventRoot() }
                val now = monotonicClock()
                // A root read is synchronous in production, but test/lifecycle seams may revoke
                // or replace this episode while it is in progress. Never apply its result to a
                // newer visible gate.
                if (!Observation.consent || !Observation.gateConsent || !Observation.connected ||
                    !callbackGuard.acceptsVisible(visibleToken) ||
                    overlay !== visibleView || overlayToken != visibleToken ||
                    ticket != visibleTicket || visibleTicket.generation != entryGate.generation ||
                    entryGate.state != EntryGateState.GATING
                ) return

                val decision = foregroundWatchdog.observe(now, sample.packageName, verifiedDoomReturn = false)
                when (decision) {
                    OverlayForegroundDecision.KEEP -> {
                        traceRecord(RemovalTraceMark.EVENT_ROOT_SAFE, eventKind, owner, sample.root, now = now)
                    }
                    OverlayForegroundDecision.KEEP_UNCERTAIN -> {
                        traceRecord(RemovalTraceMark.EVENT_ROOT_UNCERTAIN, eventKind, owner, sample.root, now = now)
                    }
                    OverlayForegroundDecision.FAIL_OPEN -> {
                        val mark = when (foregroundWatchdog.lastFailureReason) {
                            OverlayForegroundFailureReason.FOREIGN -> RemovalTraceMark.EVENT_ROOT_MISMATCH
                            OverlayForegroundFailureReason.UNCERTAINTY_EXPIRED -> RemovalTraceMark.EVENT_UNCERTAINTY_EXPIRED
                            OverlayForegroundFailureReason.ROLLBACK -> RemovalTraceMark.EVENT_ROLLBACK
                            OverlayForegroundFailureReason.NO_SAFE_ANCHOR -> RemovalTraceMark.EVENT_NO_SAFE_ANCHOR
                            OverlayForegroundFailureReason.NONE -> RemovalTraceMark.EVENT_FAILURE
                        }
                        if (foregroundWatchdog.lastFailureReason == OverlayForegroundFailureReason.FOREIGN) {
                            resetOutside(
                                cause = mark,
                                event = eventKind,
                                owner = owner,
                                root = sample.root,
                                now = now,
                            )
                        } else {
                            requestSafetyCleanup(
                                OverlayRemovalAction.BYPASS,
                                mark,
                                eventKind,
                                owner,
                                sample.root,
                                now,
                            )
                        }
                    }
                }
                return
            }
            // Suppression is checked before ticket creation, root access, and collection. It is
            // deliberately a no-op so navigation and transient Instagram events cannot restart
            // presentation or mutate the bounded report while the cooldown is active.
            if (Observation.gateConsent && entryGate.cooldownActive()) {
                traceRecord(RemovalTraceMark.EVENT_SUPPRESSED_COOLDOWN, eventKind, owner)
                return
            }
            if (!Observation.consent || !Observation.connected) {
                requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_DENIED,
                    eventKind, owner)
                return
            }

            // An installed or closing gate already owns this episode. Instagram event roots
            // can be transient; leave package-only foreground validation to the bounded
            // watchdog instead of recollecting or vetoing the pending terminal action.
            if (overlay != null) return

            val activeTicket = if (Observation.gateConsent) {
                ticket ?: entryGate.beginInstagramSessionIfEligible()?.also {
                    ticket = it
                    traceBeginEpisode()
                    publishGateState()
                }
            } else {
                null
            }

            val root = try {
                overlayPlatform.eventRoot()
            } catch (_: RuntimeException) {
                requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS,
                    RemovalTraceMark.EVENT_FAILURE,
                    eventKind,
                    owner,
                    RemovalTraceRoot.READ_FAILURE,
                )
                return
            }
            if (root == null) {
                Observation.record(null)
                if (activeTicket != null) requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_ROOT_MISSING,
                    eventKind, owner, RemovalTraceRoot.NO_ROOT
                )
                return
            }
            val rootPackage = root.packageName?.toString()
            if (rootPackage != INSTAGRAM) {
                root.recycle()
                Observation.record(null)
                if (activeTicket != null) requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_ROOT_MISMATCH,
                    eventKind, owner, traceRoot(rootPackage)
                )
                return
            }

            traceRecord(RemovalTraceMark.EVENT_OBSERVED, eventKind, owner, RemovalTraceRoot.IG)

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
                requestOverlayRemoval(
                    OverlayRemovalAction.BYPASS,
                    cause = RemovalTraceMark.ADMISSION_REJECTED,
                    event = eventKind,
                    owner = owner,
                    root = RemovalTraceRoot.IG,
                )
            }
        } catch (_: RuntimeException) {
            failOpen(RemovalTraceMark.EVENT_FAILURE)
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
                    requestOverlayRemoval(
                        OverlayRemovalAction.NAVIGATE_MESSAGES,
                        token,
                        RemovalTraceMark.USER_MESSAGES
                    )
                },
                onLeaveInstagram = {
                    requestOverlayRemoval(OverlayRemovalAction.HOME, token, RemovalTraceMark.USER_HOME)
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
            overlayWindowInstaller(manager, box, parameters)
            windowManager = manager
            overlay = box
            overlayUi = ui
            overlayToken = token

            val shownAt = monotonicClock()
            if (!entryGate.overlayShown(shownAt, activeTicket)) {
                requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.SHOWN_REJECTED)
                return
            }
            traceRecord(RemovalTraceMark.SHOWN, now = shownAt)
            foregroundWatchdog.reset(shownAt)
            renderOverlay(activeTicket, token)
            publishGateState()
            completion = Runnable {
                if (callbackGuard.acceptsVisible(token)) {
                    requestOverlayRemoval(
                        OverlayRemovalAction.COMPLETE,
                        token,
                        RemovalTraceMark.TIMER_COMPLETE
                    )
                }
            }.also { handler.postDelayed(it, GATE_DURATION_MS) }
            watchdog = object : Runnable {
                override fun run() {
                    runWatchdogTick(activeTicket, token, this)
                }
            }.also { handler.post(it) }
        } catch (_: RuntimeException) {
            callbackGuard.invalidateVisible()
            requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.INSTALL_FAILURE)
        }
    }

    /** The scheduled Runnable and instrumentation both use this production tick boundary. */
    private fun runWatchdogTick(
        activeTicket: GateTicket,
        token: OverlayCallbackToken,
        next: Runnable,
    ) {
        if (overlay == null || !callbackGuard.acceptsVisible(token)) return
        try {
            val sample = readWatchdogRoot()
            val now = monotonicClock()
            val decision = foregroundWatchdog.observe(
                now,
                sample.packageName,
                sample.packageName == applicationContext.packageName && mainActivityReturnObserved,
            )
            val mark = when (decision) {
                OverlayForegroundDecision.KEEP -> RemovalTraceMark.WATCHDOG_SAFE
                OverlayForegroundDecision.KEEP_UNCERTAIN -> RemovalTraceMark.WATCHDOG_UNCERTAIN
                OverlayForegroundDecision.FAIL_OPEN -> when (foregroundWatchdog.lastFailureReason) {
                    OverlayForegroundFailureReason.ROLLBACK -> RemovalTraceMark.WATCHDOG_ROLLBACK
                    OverlayForegroundFailureReason.FOREIGN -> RemovalTraceMark.WATCHDOG_FOREIGN
                    OverlayForegroundFailureReason.UNCERTAINTY_EXPIRED -> RemovalTraceMark.WATCHDOG_UNCERTAINTY_EXPIRED
                    OverlayForegroundFailureReason.NO_SAFE_ANCHOR -> RemovalTraceMark.WATCHDOG_NO_SAFE_ANCHOR
                    OverlayForegroundFailureReason.NONE -> RemovalTraceMark.WATCHDOG_FAILURE
                }
            }
            traceRecord(mark, now = now, owner = sample.owner, root = sample.root)
            if (decision == OverlayForegroundDecision.FAIL_OPEN) {
                failOpenCauseAlreadyRecorded()
                return
            }
            renderOverlay(activeTicket, token)
            handler.postDelayed(next, WATCHDOG_INTERVAL_MS)
        } catch (_: RuntimeException) {
            requestSafetyCleanup(
                OverlayRemovalAction.BYPASS,
                RemovalTraceMark.WATCHDOG_FAILURE,
                root = RemovalTraceRoot.NOT_READ,
            )
        }
    }

    private fun requestOverlayRemoval(
        action: OverlayRemovalAction,
        token: OverlayCallbackToken? = null,
        cause: RemovalTraceMark = RemovalTraceMark.SAFETY_OVERRIDE,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
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
        traceRecord(cause, event = event, owner = owner, root = root, action = traceAction(action))
        traceRecord(RemovalTraceMark.CLOSING, action = traceAction(action))
        removalPolicy.request(action)
        attemptOverlayRemoval(currentToken)
    }

    private fun requestSafetyCleanup(
        action: OverlayRemovalAction,
        cause: RemovalTraceMark? = null,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        now: Long? = null,
    ) {
        callbackGuard.invalidateVisible()
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        overlayUi?.dispose()
        if (cause != null && (overlay != null || ticket != null)) {
            traceRecord(cause, event = event, owner = owner, root = root, action = traceAction(action), now = now)
        }
        if (overlay != null) traceRecord(RemovalTraceMark.CLOSING, action = traceAction(action))
        removalPolicy.requestSafetyCleanup(action)
        attemptOverlayRemoval(overlayToken)
    }

    private fun attemptOverlayRemoval(token: OverlayCallbackToken? = overlayToken) {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        val view = overlay
        if (view == null || !overlayPlatform.isAttached(view)) {
            confirmOverlayRemoved(token, observedBeforeRemove = true)
            return
        }
        if (token != null && !callbackGuard.acceptsRemoval(token)) return

        try {
            windowManager?.let { overlayPlatform.removeImmediate(it, view) }
        } catch (_: RuntimeException) {
            // Attachment state below is the postcondition; an exception alone is not success.
        }
        if (!overlayPlatform.isAttached(view)) {
            confirmOverlayRemoved(token, observedBeforeRemove = false)
            return
        }

        when (removalPolicy.failedAttempt()) {
            OverlayRemovalDecision.RETRY -> {
                traceRecord(RemovalTraceMark.REMOVAL_RETRY, action = RemovalTraceAction.NONE)
                removalRetry = Runnable {
                    if (token == null || callbackGuard.acceptsRemoval(token)) attemptOverlayRemoval(token)
                }
                    .also { handler.postDelayed(it, REMOVAL_RETRY_INTERVAL_MS) }
            }
            OverlayRemovalDecision.DISABLE_SERVICE -> {
                // Retain the attached view and manager references; never release a pending action.
                // Android service teardown is the final platform-owned removal path.
                traceFinish(
                    terminalMark = RemovalTraceMark.REMOVAL_EXHAUSTED,
                    action = RemovalTraceAction.NONE,
                    detached = false,
                    policyReleased = false,
                    vetoedAction = removalPolicy.vetoedExternalAction()?.let(::traceAction)
                        ?: RemovalTraceAction.NONE,
                )
                removalPolicy.requestSafetyCleanup(OverlayRemovalAction.BYPASS)
                disableSelf()
            }
        }
    }

    private fun confirmOverlayRemoved(
        detachedToken: OverlayCallbackToken? = overlayToken,
        observedBeforeRemove: Boolean = true,
    ) {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        val detachedView = overlay
        if (detachedView != null && overlayPlatform.isAttached(detachedView)) return
        if (detachedToken != null && overlayToken != detachedToken) return
        val ownsDetachedEpisode = detachedView != null && detachedToken != null &&
            overlayToken == detachedToken && callbackGuard.acceptsRemoval(detachedToken)
        if (detachedToken != null) callbackGuard.detached(detachedToken)
        overlayUi?.dispose()
        overlayUi = null
        overlay = null
        windowManager = null
        overlayToken = null
        val vetoedAction = removalPolicy.vetoedExternalAction()?.let(::traceAction)
            ?: RemovalTraceAction.NONE
        val action = removalPolicy.confirmedDetached()
        val hadOverlay = detachedView != null
        // ALREADY_DETACHED only means the final check found no attachment; after a retry it does
        // not distinguish an external detach from our earlier removeImmediate attempt.
        traceFinish(
            terminalMark = when {
                !hadOverlay -> RemovalTraceMark.NO_OVERLAY_RELEASED
                observedBeforeRemove -> RemovalTraceMark.ALREADY_DETACHED
                else -> RemovalTraceMark.DETACHED
            },
            action = action?.let(::traceAction) ?: RemovalTraceAction.NONE,
            detached = hadOverlay,
            policyReleased = true,
            vetoedAction = vetoedAction,
        )
        if (action != OverlayRemovalAction.PRESERVE_REPORT) mainActivityReturnObserved = false
        when (action) {
            OverlayRemovalAction.BYPASS -> {
                entryGate.cancel()
                publishGateState()
                Observation.clear()
            }
            OverlayRemovalAction.NAVIGATE_MESSAGES -> {
                val routeTicket = detachedToken?.ticket
                if (routeTicket == null || !ownsDetachedEpisode ||
                    !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING)
                ) {
                    routeTicket?.let(::cancelCurrentGatingTicket)
                    publishGateState()
                    Observation.clear()
                    detachedToken?.let(callbackGuard::consumeDetached)
                    return
                }
                val root = try { overlayPlatform.currentRoot() } catch (_: RuntimeException) { null }
                if (root == null) {
                    cancelCurrentGatingTicket(routeTicket)
                    publishGateState()
                    Observation.clear()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                try {
                    val instagramForeground = root.packageName?.toString() == INSTAGRAM
                    if (!instagramForeground ||
                        !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                        !entryGate.beginMessagesRoute(routeTicket)
                    ) {
                        cancelCurrentGatingTicket(routeTicket)
                        publishGateState()
                        Observation.clear()
                        return
                    }
                    publishGateState()
                    Observation.clear()
                    // The router is deliberately one-shot. It either observes the selected tab,
                    // clicks the one exact actionable match, or fails closed.
                    val result = overlayPlatform.routeMessages(root)
                    if (hasDetachedTerminalAuthority(detachedToken, EntryGateState.BYPASSED)) {
                        entryGate.finishMessagesRoute(monotonicClock(), routeTicket, result)
                    } else {
                        entryGate.bypass(routeTicket)
                    }
                    publishGateState()
                } catch (_: RuntimeException) {
                    if (hasDetachedTerminalAuthority(detachedToken, EntryGateState.BYPASSED)) {
                        entryGate.finishMessagesRoute(
                            monotonicClock(), routeTicket, MessagesRouteResult.FAILED
                        )
                    } else if (routeTicket == ticket && entryGate.state == EntryGateState.BYPASSED) {
                        entryGate.bypass(routeTicket)
                    } else {
                        cancelCurrentGatingTicket(routeTicket)
                    }
                    publishGateState()
                    Observation.clear()
                } finally {
                    try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }
                    callbackGuard.consumeDetached(detachedToken)
                }
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
                val completeTicket = detachedToken?.ticket
                if (completeTicket == null || !ownsDetachedEpisode ||
                    !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING)
                ) {
                    completeTicket?.let(::cancelCurrentGatingTicket)
                    publishGateState()
                    detachedToken?.let(callbackGuard::consumeDetached)
                    return
                }
                val root = try { overlayPlatform.currentRoot() } catch (_: RuntimeException) { null }
                if (root == null) {
                    cancelCurrentGatingTicket(completeTicket)
                    publishGateState()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                try {
                    val validRoot = root.packageName?.toString() == INSTAGRAM
                    if (!validRoot || !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                        !entryGate.complete(monotonicClock(), completeTicket)
                    ) {
                        cancelCurrentGatingTicket(completeTicket)
                    }
                    publishGateState()
                } catch (_: RuntimeException) {
                    cancelCurrentGatingTicket(completeTicket)
                    publishGateState()
                } finally {
                    try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }
                    callbackGuard.consumeDetached(detachedToken)
                }
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

    private fun hasDetachedTerminalAuthority(
        token: OverlayCallbackToken,
        expectedState: EntryGateState,
    ): Boolean = callbackGuard.acceptsDetached(token) && overlay == null && overlayToken == null &&
        token.ticket == ticket && token.ticket.generation == entryGate.generation &&
        Observation.consent && Observation.gateConsent && Observation.connected &&
        entryGate.state == expectedState

    /** Abandon only the detached action's own still-current gate after authority is lost. */
    private fun cancelCurrentGatingTicket(candidate: GateTicket) {
        if (candidate != ticket || entryGate.state != EntryGateState.GATING) return
        entryGate.cancel()
    }

    private fun cancelAndBypass() {
        requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.SAFETY_OVERRIDE)
    }

    private fun failOpen(cause: RemovalTraceMark = RemovalTraceMark.WATCHDOG_FAILURE) {
        requestSafetyCleanup(OverlayRemovalAction.BYPASS, cause)
    }

    private fun failOpenCauseAlreadyRecorded() {
        requestSafetyCleanup(OverlayRemovalAction.BYPASS)
    }

    private fun resetOutside(
        cause: RemovalTraceMark = RemovalTraceMark.EVENT_PACKAGE_RESET,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        now: Long? = null,
    ) {
        requestSafetyCleanup(
            OverlayRemovalAction.RESET_OUTSIDE,
            cause,
            event = event,
            owner = owner,
            root = root,
            now = now,
        )
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

    private fun publishGateState() {
        Observation.entryGateState = entryGate.state
    }

    private data class WatchdogRootSample(
        val packageName: String?,
        val owner: RemovalTraceOwner,
        val root: RemovalTraceRoot
    )

    private fun readPackageRoot(acquireRoot: () -> AccessibilityNodeInfo?): WatchdogRootSample {
        var activeRoot: AccessibilityNodeInfo? = null
        return try {
            activeRoot = acquireRoot()
            val root = activeRoot ?: return WatchdogRootSample(
                null, RemovalTraceOwner.UNATTRIBUTED, RemovalTraceRoot.NO_ROOT
            )
            val packageName = try {
                overlayPlatform.readRootPackage(root)
            } catch (_: RuntimeException) {
                return WatchdogRootSample(null, RemovalTraceOwner.UNATTRIBUTED, RemovalTraceRoot.READ_FAILURE)
            }
            WatchdogRootSample(packageName, traceOwner(packageName), traceRoot(packageName))
        } catch (_: RuntimeException) {
            WatchdogRootSample(null, RemovalTraceOwner.UNATTRIBUTED, RemovalTraceRoot.READ_FAILURE)
        } finally {
            activeRoot?.let {
                try { overlayPlatform.recycleRoot(it) } catch (_: RuntimeException) { }
            }
        }
    }

    private fun readWatchdogRoot(): WatchdogRootSample =
        readPackageRoot { overlayPlatform.currentRoot() }

    private fun traceRecord(
        mark: RemovalTraceMark,
        event: RemovalTraceEvent = RemovalTraceEvent.NA,
        owner: RemovalTraceOwner = RemovalTraceOwner.NA,
        root: RemovalTraceRoot = RemovalTraceRoot.NOT_READ,
        action: RemovalTraceAction = RemovalTraceAction.NONE,
        now: Long? = null
    ) {
        if (!RemovalTraceStore.process.isCapturing()) return
        try {
            RemovalTraceStore.process.record(now ?: monotonicClock(), mark, event, owner, root, action)
        } catch (_: RuntimeException) {
            // Diagnostics are strictly fail-safe and cannot affect overlay decisions.
        }
    }

    private fun traceBeginEpisode() {
        try {
            RemovalTraceStore.process.beginEligibleEpisode(monotonicClock())
        } catch (_: RuntimeException) {
            // Diagnostics are strictly fail-safe and cannot affect overlay decisions.
        }
    }

    private fun traceFinish(
        terminalMark: RemovalTraceMark,
        action: RemovalTraceAction,
        detached: Boolean,
        policyReleased: Boolean = detached,
        vetoedAction: RemovalTraceAction = RemovalTraceAction.NONE,
    ) {
        if (!RemovalTraceStore.process.isCapturing()) return
        try {
            RemovalTraceStore.process.finish(
                now = monotonicClock(),
                terminalMark = terminalMark,
                action = action,
                detached = detached,
                policyReleased = policyReleased,
                vetoedAction = vetoedAction,
            )
        } catch (_: RuntimeException) {
            // Diagnostics are strictly fail-safe and cannot affect overlay decisions.
        }
    }

    private fun traceEventKind(event: AccessibilityEvent?): RemovalTraceEvent = when {
        event == null -> RemovalTraceEvent.NULL_EVENT
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RemovalTraceEvent.STATE
        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> RemovalTraceEvent.CONTENT
        else -> RemovalTraceEvent.OTHER
    }

    private fun traceOwner(packageName: String?): RemovalTraceOwner = when (packageName) {
        null -> RemovalTraceOwner.UNATTRIBUTED
        INSTAGRAM -> RemovalTraceOwner.IG
        applicationContext.packageName -> RemovalTraceOwner.DOOM
        else -> RemovalTraceOwner.OTHER
    }

    private fun traceRoot(packageName: String?): RemovalTraceRoot = when (packageName) {
        null -> RemovalTraceRoot.NO_PACKAGE
        INSTAGRAM -> RemovalTraceRoot.IG
        applicationContext.packageName -> RemovalTraceRoot.DOOM
        else -> RemovalTraceRoot.FOREIGN
    }

    private fun traceAction(action: OverlayRemovalAction): RemovalTraceAction = when (action) {
        OverlayRemovalAction.HOME -> RemovalTraceAction.HOME
        OverlayRemovalAction.COMPLETE -> RemovalTraceAction.COMPLETE
        OverlayRemovalAction.NAVIGATE_MESSAGES -> RemovalTraceAction.NAVIGATE_MESSAGES
        OverlayRemovalAction.BYPASS -> RemovalTraceAction.BYPASS
        OverlayRemovalAction.PRESERVE_REPORT -> RemovalTraceAction.PRESERVE_REPORT
        OverlayRemovalAction.RESET_OUTSIDE -> RemovalTraceAction.RESET_OUTSIDE
    }

    private fun isMainActivityReturn(event: AccessibilityEvent?): Boolean =
        event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString() == MainActivity::class.java.name

    override fun onInterrupt() {
        traceRecord(RemovalTraceMark.SERVICE_INTERRUPTED, action = RemovalTraceAction.BYPASS)
        requestSafetyCleanup(OverlayRemovalAction.BYPASS)
        Observation.connected = false
        Observation.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        traceRecord(RemovalTraceMark.SERVICE_UNBOUND, action = RemovalTraceAction.BYPASS)
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        traceRecord(RemovalTraceMark.SERVICE_DESTROYED, action = RemovalTraceAction.BYPASS)
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
