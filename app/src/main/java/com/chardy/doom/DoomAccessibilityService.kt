package com.chardy.doom

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

private const val SAFE_FOREIGN_PACKAGE = "__foreign__"

/** Copy only the closed package categories used by authority checks; never retain free-form IDs. */
private fun safePackageToken(
    value: CharSequence?,
    doomPackage: String? = null,
    recognizedImePackages: Set<String> = emptySet(),
): String? = when {
    StructuralSanitizer.isExactAscii(value, "com.instagram.android") -> "com.instagram.android"
    doomPackage != null && StructuralSanitizer.isExactAscii(value, doomPackage) -> doomPackage
    StructuralSanitizer.isExactAscii(value, "com.android.systemui") -> SAFE_SYSTEM_UI_PACKAGE
    recognizedImePackages.any { StructuralSanitizer.isExactAscii(value, it) } -> SAFE_RECOGNIZED_IME_PACKAGE
    value == null -> null
    else -> SAFE_FOREIGN_PACKAGE
}

internal interface OverlayPlatform : OverlayPhysicalPlatform {
    val physical: OverlayPhysicalPlatform get() = this
    fun currentRoot(): AccessibilityNodeInfo?
    /** Event-root seam; production reads the same root property used by the baseline path. */
    fun eventRoot(): AccessibilityNodeInfo? = currentRoot()
    /** Package-only seam; production does not inspect any other root property here. */
    fun readRootPackage(root: AccessibilityNodeInfo): String? = safePackageToken(root.packageName)
    fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult
    fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
    fun performHome(): Boolean
}

/** Diagnostic-only, default-off entry pause. A user tap may best-effort route to Instagram messages. */
class DoomAccessibilityService : AccessibilityService() {
    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val WATCHDOG_INTERVAL_MS = 50L
        private const val WATCHDOG_UNCERTAINTY_GRACE_MS = 150L
        private const val REMOVAL_RETRY_INTERVAL_MS = 50L
        private const val MAX_REMOVAL_ATTEMPTS = 20
        private const val REMOVAL_SLOW_RETRY_INTERVAL_MS = 1_000L
        private var instance: DoomAccessibilityService? = null

        fun cancelEntryGate() {
            instance?.cancelAndBypass()
            if (instance == null) Observation.entryGateState = EntryGateState.OUTSIDE
        }

        fun sessionTimerPreferenceChanged(enabled: Boolean) {
            instance?.onTimerPreferenceChanged(enabled)
        }

        fun disableObservation() {
            instance?.requestDisable()
            Observation.connected = false
            Observation.clear()
            RemovalTraceStore.process.clear()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }
    private val entryGate = InstagramEntryGate(
        durationMs = 10_000L,
        enabled = { Observation.reminderSettings.enabled && Observation.gateConsent && Observation.consent && Observation.connected },
        monotonicNowMs = monotonicClock,
        durationProvider = { Observation.reminderSettings.durationSeconds * 1_000L },
        cooldownDurationProvider = { Observation.reminderSettings.suppressionMinutes * 60_000L },
    )
    private var ticket: GateTicket? = null
    private var gateWindows: GateOverlayWindows? = null
    private var bound = false
    private var disableWhenDetached = false
    private var behaviorStopped = false
    private var disableService: () -> Unit = { disableSelf() }
    private var overlayUi: EntryGateOverlayUi? = null
    private var windowManager: WindowManager? = null
    private var watchdog: Runnable? = null
    private var completion: Runnable? = null
    private val removalLoop = OverlayRemovalRetryLoop(HandlerOverlayRemovalScheduler(handler))
    private val sessionTimer = InstagramSessionTimer { monotonicClock() }
    private val timerForeground = OverlayForegroundWatchdog(
        INSTAGRAM, BuildConfig.APPLICATION_ID, WATCHDOG_UNCERTAINTY_GRACE_MS
    )
    private var timerView: View? = null
    private var timerUi: InstagramTimerOverlayUi? = null
    private var timerManager: WindowManager? = null
    private var timerParams: WindowManager.LayoutParams? = null
    private var sessionBoundaryCheck: Runnable? = null
    private var timerLastSafeAtMs = -1L
    private var timerTick: Runnable? = null
    private val timerRemovalLoop = OverlayRemovalRetryLoop(HandlerOverlayRemovalScheduler(handler))
    private var timerWindow: OwnedOverlayWindow? = null
    private var timerRemoving = false
    private var timerLayoutListener: View.OnLayoutChangeListener? = null
    private var timerWatchdog: Runnable? = null
    private var timerSessionEpoch = 0L
    /** Irreversible process-lifetime removal-exhaustion veto; never reflects user preference. */
    private var timerSafetyVeto = false
    private var timerDismissedThisVisit = false
    private var timerAwaitingFreshObservation = false
    private var timerX = 0f
    private var timerY = 0f
    private var timerEdge = TimerEdge.RIGHT
    private var terminalGateSucceeded = false
    private var timerEpoch = 0L
    private var timerClosing = false
    private var timerTerminalReset = false
    private var gateAfterTimerDetach: GateTicket? = null
    private var cancelledGateAfterTimerDetach: GateTicket? = null
    private var timerRemovalAttempts = 0
    private var mainActivityReturnObserved = false
    private var captureEventProvenance: StructuralCaptureEvent? = null
    // Production-no-op instrumentation hooks. They expose lifecycle ordering, never node data.
    private var collectorReadHook: () -> Unit = {}
    private var collectorRecycleHook: () -> Unit = {}
    private var copyHook: (SanitizedStructuralReport) -> OverlayCopyResult = { candidate ->
        Observation.replaceAndCopyFreshReport(this, candidate)
    }
    // Private, production-default seam used only by instrumentation to keep the real install path.
    private var overlayWindowInstaller: (WindowManager, View, WindowManager.LayoutParams) -> Unit =
        { manager, view, parameters -> manager.addView(view, parameters) }
    private var overlayWindowUpdater: (WindowManager, View, WindowManager.LayoutParams) -> Unit =
        { manager, view, parameters -> manager.updateViewLayout(view, parameters) }
    private val foregroundWatchdog = OverlayForegroundWatchdog(
        instagramPackage = INSTAGRAM,
        doomPackage = BuildConfig.APPLICATION_ID,
        uncertaintyGraceMs = WATCHDOG_UNCERTAINTY_GRACE_MS,
    )
    private val removalPolicy = OverlayRemovalPolicy(MAX_REMOVAL_ATTEMPTS)
    private val callbackGuard = OverlayCallbackGuard()
    private var overlayToken: OverlayCallbackToken? = null
    private var overlayPlatform: OverlayPlatform = object : OverlayPlatform {
        override val physical = AndroidOverlayPhysicalPlatform
        override fun isAttached(view: View) = view.isAttachedToWindow
        override fun removeImmediate(manager: WindowManager, view: View) = manager.removeViewImmediate(view)
        override fun currentRoot(): AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }
        override fun eventRoot(): AccessibilityNodeInfo? = rootInActiveWindow
        override fun readRootPackage(root: AccessibilityNodeInfo): String? = safePackageToken(
            root.packageName, applicationContext.packageName, timerImePackages,
        )
        override fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult =
            InstagramMessagesRouter.route(root)
        override fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
        override fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = true
        if (!disableWhenDetached) behaviorStopped = false
        RetiringOverlayCleanup.reconcile()
        instance = this
        resetOutside(cause = RemovalTraceMark.SERVICE_CONNECTED_RESET)
        Observation.connected = false
        Observation.clear()
        Observation.load(this)
        // Android owns whether the service is enabled. A fresh install has no report consent yet;
        // remain connected but inert until Doom receives that explicit in-app consent.
        Observation.connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (behaviorStopped || !RetiringOverlayCleanup.barrier.canAdmit()) return
            val packageName = safePackageToken(event?.packageName, applicationContext.packageName)
            val traceCapturing = RemovalTraceStore.process.isCapturing()
            val eventKind = if (traceCapturing) traceEventKind(event) else RemovalTraceEvent.NA
            val owner = if (traceCapturing) traceOwner(packageName) else RemovalTraceOwner.NA
            if ((gateWindows != null || sessionTimer.running) &&
                (!Observation.reminderSettings.enabled || !Observation.consent ||
                    !Observation.gateConsent || !Observation.connected)
            ) {
                endTimerSession()
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
                    timerDismissedThisVisit = false
                    timerEdge = TimerEdge.RIGHT
                    endTimerSession()
                    mainActivityReturnObserved = true
                    // This also resets a completed/granted session when no overlay remains.
                    if (gateWindows == null) {
                        entryGate.leaveInstagram()
                        ticket = null
                        mainActivityReturnObserved = false
                        publishGateState()
                    } else requestOverlayRemoval(
                        OverlayRemovalAction.PRESERVE_REPORT,
                        null,
                        RemovalTraceMark.APP_RETURN
                    )
                }
                // Ignore all other Doom-owned events, including overlay updates.
                return
            }
            if (packageName != INSTAGRAM) {
                val visibleView = gateWindows
                val visibleToken = overlayToken
                val visibleTicket = ticket
                if (visibleView == null) {
                    val session = timerSessionEpoch
                    if (sessionTimer.running && verifyTimerAuthority()) {
                        if (session == timerSessionEpoch) scheduleSessionBoundaryCheck()
                        return
                    }
                    if (session != timerSessionEpoch) return
                    endTimerSession()
                    // Dismissal is timer-owned: event attribution alone is never enough to end
                    // the visit. System UI and keyboards commonly cover Instagram temporarily.
                    if (timerDismissedThisVisit && confirmedOrdinaryForeignRoot()) {
                        timerDismissedThisVisit = false
                        timerEdge = TimerEdge.RIGHT
                    }
                    // Preserve the baseline gate reset for every non-Instagram/null event.
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
                    gateWindows !== visibleView ||
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
                    gateWindows !== visibleView || overlayToken != visibleToken ||
                    ticket != visibleTicket || visibleTicket.generation != entryGate.generation ||
                    entryGate.state != EntryGateState.GATING
                ) return

                val decision = foregroundWatchdog.observe(now, sample.packageName, verifiedDoomReturn = false)
                when (decision) {
                    OverlayForegroundDecision.KEEP -> {
                        timerLastSafeAtMs = now
                        timerForeground.observe(now, INSTAGRAM, false)
                        scheduleSessionBoundaryCheck()
                        traceRecord(RemovalTraceMark.EVENT_ROOT_SAFE, eventKind, owner, sample.root, now = now)
                    }
                    OverlayForegroundDecision.KEEP_UNCERTAIN -> {
                        scheduleSessionBoundaryCheck()
                        traceRecord(RemovalTraceMark.EVENT_ROOT_UNCERTAIN, eventKind, owner, sample.root, now = now)
                    }
                    OverlayForegroundDecision.FAIL_OPEN -> {
                        endTimerSession()
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
            // Suppression is checked before new gate tickets or report collection.
            // Only package attribution is sampled here for the separately owned timer.
            if (Observation.gateConsent && entryGate.cooldownActive()) {
                when (sampleTimerAuthority()) {
                    OverlayForegroundDecision.KEEP -> {
                        // Preference re-enable is deliberately event driven. A settings call,
                        // package attribution, or uncertain root cannot consume this latch.
                        timerAwaitingFreshObservation = false
                        observeTimerInstagram()
                        attachTimerIfAllowed()
                    }
                    OverlayForegroundDecision.KEEP_UNCERTAIN -> Unit
                    OverlayForegroundDecision.FAIL_OPEN -> endTimerSession()
                }
                traceRecord(RemovalTraceMark.EVENT_SUPPRESSED_COOLDOWN, eventKind, owner)
                return
            }
            // Report-only collection remains available with fresh report consent and a live
            // connection. Gate consent is optional; it is required only for the overlay/timer.
            if (!Observation.consent || !Observation.connected) {
                endTimerSession()
                requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_DENIED,
                    eventKind, owner)
                return
            }

            // A closing gate owns its pending action. A still-visible gate may be removed only
            // from a fresh, bounded, current-episode candidate that confirms messaging.
            if (gateWindows != null) {
                val visibleView = gateWindows ?: return
                val visibleToken = overlayToken ?: return
                val visibleTicket = ticket ?: return
                if (!callbackGuard.acceptsVisible(visibleToken) ||
                    visibleTicket != visibleToken.ticket ||
                    visibleTicket.generation != entryGate.generation ||
                    entryGate.state != EntryGateState.GATING
                ) return
                val context = captureContext(event) ?: return
                val visibleRoot = try { overlayPlatform.eventRoot() } catch (_: RuntimeException) { null }
                    ?: return
                val candidate = try { collectCandidate(visibleRoot, context) } catch (_: RuntimeException) { null }
                    ?: return
                if (!Observation.consent || !Observation.gateConsent || !Observation.connected ||
                    gateWindows !== visibleView || overlayToken !== visibleToken || ticket != visibleTicket ||
                    !callbackGuard.acceptsVisible(visibleToken) ||
                    visibleTicket.generation != entryGate.generation ||
                    entryGate.state != EntryGateState.GATING
                ) return
                Observation.record(candidate)
                if (InstagramSurfaceShadowClassifier.classify(candidate) == InstagramSurface.MESSAGING) {
                    requestSafetyCleanup(
                        OverlayRemovalAction.BYPASS,
                        RemovalTraceMark.SAFETY_OVERRIDE,
                        eventKind,
                        owner,
                        RemovalTraceRoot.IG,
                    )
                }
                return
            }
            if (timerClosing && timerTerminalReset) return

            // Only a verified event after a successful terminal cooldown may retire
            // that completed episode. Safety/cancel bypasses keep their ticket until
            // a confirmed foreign transition or MainActivity return resets the session.
            if (ticket != null && terminalGateSucceeded) {
                when (sampleTimerAuthority()) {
                    OverlayForegroundDecision.KEEP -> {
                        entryGate.leaveInstagram()
                        ticket = null
                        terminalGateSucceeded = false
                    }
                    OverlayForegroundDecision.KEEP_UNCERTAIN -> return
                    OverlayForegroundDecision.FAIL_OPEN -> { resetOutside(); return }
                }
            }
            val activeTicket = if (Observation.gateConsent) {
                ticket ?: entryGate.beginInstagramSessionIfEligible()?.also {
                    ticket = it
                    terminalGateSucceeded = false
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
            val rootPackage = safePackageToken(root.packageName)
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
            if (timerAwaitingFreshObservation && Observation.sessionTimerEnabled &&
                !timerDismissedThisVisit && !timerSafetyVeto) timerAwaitingFreshObservation = false
            if (timerSpecificAllowed()) observeTimerInstagram()

            val captureContext = captureContext(event)
            if (captureContext == null) {
                root.recycle()
                Observation.record(null)
                if (activeTicket != null) requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_FAILURE)
                return
            }
            captureEventProvenance = captureContext.event
            val candidate = collect(root, captureContext)
            if (candidate == null) {
                if (activeTicket != null) requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_FAILURE,
                    eventKind, owner, RemovalTraceRoot.READ_FAILURE,
                ) else {
                    Observation.entryGateState = EntryGateState.OUTSIDE
                }
                return
            }
            if (activeTicket == null) {
                if (gateWindows != null) cancelAndBypass()
                else Observation.entryGateState = EntryGateState.OUTSIDE
                return
            }
            val observedTicket = resumeMessagingAdmission(activeTicket, candidate)
            if (entryGate.state != EntryGateState.AWAITING &&
                entryGate.state != EntryGateState.GATING
            ) return

            val shouldShow = entryGate.observeInstagram(
                monotonicClock(), observedTicket, InstagramSurfaceShadowClassifier.classify(candidate)
            )
            publishGateState()
            if (shouldShow) {
                if (timerView != null || timerClosing) suspendTimerForGate(observedTicket)
                else if (gateWindows == null) installOverlay(observedTicket)
                else overlayToken?.let { renderOverlay(observedTicket, it) }
            } else if (entryGate.state != EntryGateState.BYPASSED) {
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

    private fun resumeMessagingAdmission(activeTicket: GateTicket, candidate: SanitizedStructuralReport): GateTicket {
        if (entryGate.state != EntryGateState.BYPASSED || !bound || behaviorStopped || disableWhenDetached || !timerConsentAllowed() ||
            ticket != activeTicket || gateWindows != null || overlayToken != null || timerClosing ||
            !RetiringOverlayCleanup.barrier.canAdmit()
        ) return activeTicket
        val resumed = entryGate.resumeMessagingAdmission(
            monotonicClock(), activeTicket, InstagramSurfaceShadowClassifier.classify(candidate)
        ) ?: return activeTicket
        ticket = resumed
        terminalGateSucceeded = false
        traceBeginEpisode()
        publishGateState()
        return resumed
    }

    @Suppress("DEPRECATION") // Release transient nodes on older supported Android versions too.
    private fun collect(
        root: AccessibilityNodeInfo,
        context: StructuralCaptureContext,
    ): SanitizedStructuralReport? {
        val candidate = collectCandidate(root, context)
        Observation.record(candidate)
        return candidate
    }

    @Suppress("DEPRECATION")
    private fun collectCandidate(
        root: AccessibilityNodeInfo,
        context: StructuralCaptureContext,
    ): SanitizedStructuralReport? {
        if (root.packageName?.let { StructuralSanitizer.isExactAscii(it, AndroidStructuralMetadataReader.INSTAGRAM_PACKAGE) } != true) {
            try { root.recycle() } finally { collectorRecycleHook() }
            return null
        }
        data class QueueEntry(val node: AccessibilityNodeInfo, val position: StructuralNodePosition)
        val queue = ArrayDeque<QueueEntry>()
        queue.add(QueueEntry(root, StructuralNodePosition()))
        try {
            val builder = SanitizedStructuralReport.Builder(context)
            val reader = AndroidStructuralMetadataReader(nowMs = monotonicClock)
            var visited = 0
            var nextIndex = 1
            while (queue.isNotEmpty() && visited < SanitizedStructuralReport.MAX_NODES) {
                val entry = queue.removeFirst()
                val node = entry.node
                try {
                    visited++
                    if (node.packageName?.let { StructuralSanitizer.isExactAscii(it, AndroidStructuralMetadataReader.INSTAGRAM_PACKAGE) } != true) {
                        builder.markTruncated("foreign")
                        continue
                    }
                    collectorReadHook()
                    val metadata = reader.read(node, entry.position.copy(bfsOrdinal = visited - 1), context)
                    val elapsedOffset = metadata.elapsedOffsetMs
                    if (elapsedOffset is MetadataValue.Unavailable) when (elapsedOffset.reason) {
                        MetadataUnavailableReason.CLOCK_ROLLBACK -> {
                            // A backward elapsedRealtime sample invalidates this capture; never
                            // publish a partial report assembled across an invalid clock.
                            return null
                        }
                        MetadataUnavailableReason.TIMEOUT -> {
                            // A timed-out node is not a trustworthy structural observation. Stop
                            // before adding it so the report never emits a misleading final row.
                            builder.markTruncated("time")
                            break
                        }
                        MetadataUnavailableReason.READ_ERROR -> builder.markTruncated("time")
                        else -> Unit
                    }
                    builder.add(metadata)
                    val depth = entry.position.depth
                    if (depth >= SanitizedStructuralReport.MAX_DEPTH) {
                        if (metadata.rawChildCount > 0) builder.markTruncated("depth")
                        continue
                    }
                    val available = SanitizedStructuralReport.MAX_NODES - visited - queue.size
                    val childLimit = minOf(metadata.rawChildCount, available.coerceAtLeast(0))
                    if (childLimit < metadata.rawChildCount) builder.markTruncated("nodes")
                    repeat(childLimit) { slot ->
                        val child = try { node.getChild(slot) } catch (_: RuntimeException) { null }
                        if (child == null) {
                            builder.markTruncated("missing_child")
                        } else {
                            queue.add(QueueEntry(
                                child,
                                StructuralNodePosition(
                                    index = nextIndex++,
                                    parentIndex = MetadataValue.Present(entry.position.index),
                                    depth = depth + 1,
                                    bfsOrdinal = 0,
                                    siblingSlot = MetadataValue.Present(slot),
                                ),
                            ))
                        }
                    }
                } finally {
                    try { node.recycle() } finally { collectorRecycleHook() }
                }
            }
            if (queue.isNotEmpty()) builder.markTruncated("nodes")
            return builder.build()
        } finally {
            while (queue.isNotEmpty()) {
                try { queue.removeFirst().node.recycle() } finally { collectorRecycleHook() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun tapCaptureContext(): StructuralCaptureContext? {
        val event = captureEventProvenance ?: return null
        return try {
            val metrics = android.util.DisplayMetrics()
            val display = (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay ?: return null
            display.getRealMetrics(metrics)
            StructuralCaptureContext(
                apiLevel = android.os.Build.VERSION.SDK_INT,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                startedElapsedMs = monotonicClock(),
                event = event,
            )
        } catch (_: RuntimeException) { null }
    }

    @Suppress("DEPRECATION")
    private fun captureContext(event: AccessibilityEvent?): StructuralCaptureContext? {
        if (event == null) return null
        return try {
            val metrics = android.util.DisplayMetrics()
            val display = (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay ?: return null
            display.getRealMetrics(metrics)
            AndroidStructuralMetadataReader.contextFromEvent(
                event, width = metrics.widthPixels, height = metrics.heightPixels,
                densityDpi = metrics.densityDpi, startedElapsedMs = monotonicClock(),
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Shared gate authority: deliberately independent of timer preference/dismissal/veto. */
    private fun timerConsentAllowed(): Boolean =
        Observation.consent && Observation.gateConsent && Observation.connected &&
            !behaviorStopped && !disableWhenDetached && RetiringOverlayCleanup.barrier.canAdmit()

    private fun timerSpecificAllowed(): Boolean = timerConsentAllowed() &&
        Observation.sessionTimerEnabled && !timerDismissedThisVisit && !timerSafetyVeto

    /** A dismissal-only root classification; never uses the accessibility event package. */
    private fun confirmedOrdinaryForeignRoot(): Boolean {
        val rootPackage = readPackageRoot { overlayPlatform.currentRoot() }.packageName ?: return false
        return isOrdinaryForeignForTimerDismissal(
            rootPackage, applicationContext.packageName, timerImePackages
        )
    }

    /** Process-local platform metadata only; no UI content and no persistence. */
    private val timerImePackages: Set<String> by lazy {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList
                .mapTo(mutableSetOf()) { it.packageName }
        } catch (_: RuntimeException) { emptySet() }
    }

    private fun observeTimerInstagram() {
        if (!timerSpecificAllowed() || timerAwaitingFreshObservation || (!sessionTimer.running && timerClosing)) return
        if (!sessionTimer.running) {
            timerSessionEpoch++
            timerForeground.reset(-1L)
        }
        if (sessionTimer.observeVerifiedInstagram(
                Observation.consent, Observation.gateConsent, Observation.connected
            )) {
            timerLastSafeAtMs = monotonicClock()
            timerForeground.observe(timerLastSafeAtMs, INSTAGRAM, false)
            cancelSessionBoundaryCheck()
        } else endTimerSession()
    }

    private fun cancelSessionBoundaryCheck() {
        sessionBoundaryCheck?.let(handler::removeCallbacks)
        sessionBoundaryCheck = null
    }

    /** One bounded follow-up, independent of full-window removal and timer window epochs. */
    private fun scheduleSessionBoundaryCheck() {
        if (!sessionTimer.running || sessionBoundaryCheck != null || timerWatchdog != null) return
        val session = timerSessionEpoch
        val delay = (WATCHDOG_UNCERTAINTY_GRACE_MS -
            (monotonicClock() - timerLastSafeAtMs)).coerceIn(0L, WATCHDOG_UNCERTAINTY_GRACE_MS)
        sessionBoundaryCheck = object : Runnable {
            override fun run() {
                if (sessionBoundaryCheck !== this || session != timerSessionEpoch) return
                if (!timerSpecificAllowed()) {
                    endTimerSession()
                    resetOutside()
                    return
                }
                val sample = readPackageRoot { overlayPlatform.currentRoot() }
                if (sessionBoundaryCheck !== this || session != timerSessionEpoch) return
                cancelSessionBoundaryCheck()
                if (!timerSpecificAllowed() || sample.packageName != INSTAGRAM) {
                    endTimerSession()
                    resetOutside()
                } else {
                    observeTimerInstagram()
                    attachTimerIfAllowed()
                }
            }
        }.also { handler.postDelayed(it, delay) }
    }

    private fun sampleTimerAuthority(): OverlayForegroundDecision {
        if (!timerConsentAllowed()) return OverlayForegroundDecision.FAIL_OPEN
        val epoch = timerSessionEpoch
        val sample = readPackageRoot { overlayPlatform.currentRoot() }
        if (epoch != timerSessionEpoch || !timerConsentAllowed()) return OverlayForegroundDecision.FAIL_OPEN
        // A timer accessibility window can itself own the root during TalkBack taps.
        // It is uncertainty, never fresh authority; MainActivity returns end the session.
        val attributed = if (sample.packageName == applicationContext.packageName &&
            timerView != null && !mainActivityReturnObserved) null else sample.packageName
        val now = try { monotonicClock() } catch (_: RuntimeException) {
            return OverlayForegroundDecision.FAIL_OPEN
        }
        return timerForeground.observe(now, attributed, false).also {
            if (it == OverlayForegroundDecision.KEEP) {
                timerLastSafeAtMs = now
                cancelSessionBoundaryCheck()
            }
        }
    }

    private fun verifyTimerAuthority(): Boolean =
        sampleTimerAuthority() != OverlayForegroundDecision.FAIL_OPEN

    private fun timerWindowCurrent(epoch: Long): Boolean = epoch == timerEpoch &&
        sessionTimer.running && !timerClosing && timerView != null && gateWindows == null &&
        timerSpecificAllowed()

    private fun timerWindowAuthorized(epoch: Long): Boolean {
        if (epoch != timerEpoch || timerClosing || timerView == null) return false
        val session = timerSessionEpoch
        val allowed = timerWindowCurrent(epoch) && verifyTimerAuthority()
        if (epoch != timerEpoch || session != timerSessionEpoch) return false
        if (!allowed || !timerWindowCurrent(epoch)) { endTimerSession(); return false }
        return true
    }

    private fun freshTimerAuthority(): Boolean {
        val session = timerSessionEpoch
        val epoch = timerEpoch
        val decision = sampleTimerAuthority()
        if (session != timerSessionEpoch || epoch != timerEpoch) return false
        if (decision == OverlayForegroundDecision.FAIL_OPEN) endTimerSession()
        if (decision == OverlayForegroundDecision.KEEP_UNCERTAIN) scheduleSessionBoundaryCheck()
        return decision == OverlayForegroundDecision.KEEP
    }

    private fun attachTimerIfAllowed() {
        if (!sessionTimer.running || timerView != null || timerClosing || gateWindows != null ||
            !timerSpecificAllowed()) return
        if (!freshTimerAuthority()) return
        val epoch = ++timerEpoch
        val session = timerSessionEpoch
        var created: InstagramTimerOverlayUi? = null
        try {
            created = InstagramTimerOverlayViewFactory.create(this, onToggle = {
                if (timerWindowAuthorized(epoch)) {
                    sessionTimer.toggle()
                    renderTimer(epoch, true)
                    created?.root?.post { settleTimer(epoch, false) }
                }
            }, onDismiss = { dismissTimer(epoch) }, onMove = { dx, dy -> moveTimer(epoch, dx, dy) },
                onSettle = { chooseEdge -> settleTimer(epoch, chooseEdge) })
            val initialModel = sessionTimer.model(
                try { !ValueAnimator.areAnimatorsEnabled() } catch (_: RuntimeException) { true }, true
            )
            if (initialModel == null) {
                created.dispose()
                return
            }
            created.render(initialModel)
            created.root.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                val b = timerBounds(manager, null, created.root.measuredWidth.coerceAtLeast(56.dp()),
                    created.root.measuredHeight.coerceAtLeast(56.dp()))
                x = b.snapX(timerEdge); y = b.top + ((b.bottom - b.top) * .42f).toInt()
                timerX = x.toFloat(); timerY = y.toFloat()
            }
            // Exact fresh attribution immediately before addView, never uncertainty.
            if (!freshTimerAuthority() || epoch != timerEpoch ||
                session != timerSessionEpoch || !sessionTimer.running || !timerSpecificAllowed() ||
                gateWindows != null || timerClosing) {
                created.dispose(); return
            }
            // Own the window before the platform call: addView may attach and then throw.
            timerManager = manager; timerUi = created; timerView = created.root; timerParams = params
            created.root.setOnApplyWindowInsetsListener { _, insets ->
                updateTimerLayoutForInsets(epoch, insets)
                insets
            }
            timerWindow = ownedWindow(created.root, manager, GateWindowRole.DEBUG, params).also { it.addAttempted = true }
            overlayWindowInstaller(manager, created.root, params)
            if (epoch != timerEpoch || session != timerSessionEpoch) return
            if (!timerWindowCurrent(epoch)) { endTimerSession(); return }
            renderTimer(epoch, true)
            timerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
                if (created.root.width != oldRight-oldLeft || created.root.height != oldBottom-oldTop) settleTimer(epoch, false)
            }.also { created.root.addOnLayoutChangeListener(it) }
            if (timerWindowCurrent(epoch)) {
                cancelSessionBoundaryCheck()
                timerWatchdog = object : Runnable {
                    override fun run() {
                        if (timerWatchdog !== this || !timerWindowAuthorized(epoch)) return
                        val view = timerView ?: return
                        if (!overlayPlatform.physical.isRegistered(view)) { endTimerSession(); return }
                        handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    }
                }.also { handler.postDelayed(it, WATCHDOG_INTERVAL_MS) }
            }
        } catch (failure: RuntimeException) {
            created?.dispose()
            if (failure is WindowManager.BadTokenException) timerWindow?.mayReAdd = false
            if (epoch == timerEpoch) endTimerSession()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTimerLayout(timerEpoch)
        gateWindows?.let { updateGateLayout(it) }
    }

    private fun updateTimerLayout(epoch: Long) {
        updateTimerLayoutForInsets(epoch, timerView?.rootWindowInsets)
    }

    private fun updateTimerLayoutForInsets(epoch: Long, insets: android.view.WindowInsets?) {
        if (epoch != timerEpoch || timerClosing || timerView == null) return
        val session = timerSessionEpoch
        try {
            if (!timerWindowAuthorized(epoch)) return
            val manager = timerManager ?: return
            val view = timerView ?: return
            val params = timerParams ?: return
            if (!overlayPlatform.physical.isRegistered(view)) { endTimerSession(); return }
            val bounds = timerBounds(manager, insets, view.measuredWidth.coerceAtLeast(56.dp()), view.measuredHeight.coerceAtLeast(56.dp()))
            if (session != timerSessionEpoch || !timerWindowCurrent(epoch)) return
            timerX = bounds.snapX(timerEdge).toFloat(); timerY = bounds.clampY(timerY)
            params.x = timerX.toInt(); params.y = timerY.toInt()
            overlayWindowUpdater(manager, view, params)
        } catch (_: RuntimeException) {
            if (epoch == timerEpoch && session == timerSessionEpoch) endTimerSession()
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun timerBounds(manager: WindowManager, insets: android.view.WindowInsets?, width: Int, height: Int): TimerBounds {
        val frame = if (android.os.Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return InstagramTimerOverlayViewFactory.bounds(frame,
            InstagramTimerOverlayViewFactory.safeInsets(manager, insets), width, height, resources.displayMetrics.density)
    }

    private fun moveTimer(epoch: Long, dx: Float, dy: Float) {
        if (epoch != timerEpoch || timerClosing || timerView == null || !timerSpecificAllowed()) return
        val manager=timerManager?:return; val view=timerView?:return; val params=timerParams?:return
        val bounds=timerBounds(manager,view.rootWindowInsets,view.width.coerceAtLeast(56.dp()),view.height.coerceAtLeast(56.dp()))
        timerX=bounds.clampX(timerX+dx); timerY=bounds.clampY(timerY+dy)
        params.x=timerX.toInt(); params.y=timerY.toInt()
        try { overlayWindowUpdater(manager,view,params) } catch (_:RuntimeException) { endTimerSession() }
    }

    private fun settleTimer(epoch: Long, chooseEdge: Boolean) {
        if (epoch != timerEpoch || timerClosing || timerView == null) return
        val manager=timerManager?:return; val view=timerView?:return; val params=timerParams?:return
        val bounds=timerBounds(manager,view.rootWindowInsets,view.width.coerceAtLeast(56.dp()),view.height.coerceAtLeast(56.dp()))
        if (chooseEdge) timerEdge = if (timerX <= (bounds.left+bounds.right)/2f) TimerEdge.LEFT else TimerEdge.RIGHT
        timerX=bounds.snapX(timerEdge).toFloat(); timerY=bounds.clampY(timerY); params.x=timerX.toInt(); params.y=timerY.toInt()
        try { overlayWindowUpdater(manager,view,params) } catch (_:RuntimeException) { endTimerSession() }
    }

    /** Destructive state is recorded before any foreground/root authority query. */
    private fun dismissTimer(epoch: Long) {
        if (epoch != timerEpoch || timerView == null || timerClosing) return
        timerDismissedThisVisit=true
        timerAwaitingFreshObservation=false
        endTimerSession()
    }

    private fun onTimerPreferenceChanged(enabled: Boolean) {
        if (!enabled) {
            timerAwaitingFreshObservation=false
            if (gateAfterTimerDetach != null || timerClosing && !timerTerminalReset) {
                cancelSessionBoundaryCheck()
                timerTick?.let(handler::removeCallbacks); timerTick = null
                timerWatchdog?.let(handler::removeCallbacks); timerWatchdog = null
                sessionTimer.endSession()
            } else endTimerSession()
        } else {
            timerAwaitingFreshObservation=true
            // The irreversible safety veto is intentionally untouched.
        }
    }

    private fun renderTimer(epoch: Long, force: Boolean = false) {
        if (!timerWindowAuthorized(epoch)) return
        val view = timerView ?: return
        if (!overlayPlatform.physical.isRegistered(view)) { endTimerSession(); return }
        val reduce = try { !ValueAnimator.areAnimatorsEnabled() } catch (_: RuntimeException) { true }
        sessionTimer.model(reduce, force)?.let { timerUi?.render(it) }
        if (!sessionTimer.running) { endTimerSession(); return }
        timerTick?.let(handler::removeCallbacks)
        timerTick = Runnable { renderTimer(epoch) }.also {
            handler.postDelayed(it, sessionTimer.delayToNextSecond())
        }
    }

    private fun suspendTimerForGate(activeTicket: GateTicket) {
        gateAfterTimerDetach = activeTicket
        beginTimerRemoval(reset = false)
    }

    private fun endTimerSession() {
        cancelSessionBoundaryCheck()
        timerLastSafeAtMs = -1L
        gateAfterTimerDetach?.let { cancelledGateAfterTimerDetach = it }
        gateAfterTimerDetach = null
        timerSessionEpoch++
        sessionTimer.endSession()
        timerForeground.reset(-1L)
        beginTimerRemoval(reset = true)
    }

    private fun stopTimerCallbacks() {
        timerTick?.let(handler::removeCallbacks); timerTick = null
        timerWatchdog?.let(handler::removeCallbacks); timerWatchdog = null
    }

    private fun beginTimerRemoval(reset: Boolean) {
        timerTerminalReset = timerTerminalReset || reset
        if (timerClosing) return
        stopTimerCallbacks()
        if (timerView == null) { finishTimerDetach(timerEpoch); return }
        timerClosing = true
        timerWindow?.params = timerParams?.let { saved -> WindowManager.LayoutParams().apply { copyFrom(saved) } }
        timerLayoutListener?.let { timerView?.removeOnLayoutChangeListener(it) }; timerLayoutListener = null
        timerUi?.dispose()
        timerRemovalAttempts = 0
        attemptTimerRemoval(timerEpoch)
    }

    private fun attemptTimerRemoval(epoch: Long) {
        if (epoch != timerEpoch || !timerClosing || timerRemovalLoop.isPending || timerRemoving) return
        val record = timerWindow ?: timerView?.let { view ->
            OwnedOverlayWindow(view, timerManager, GateWindowRole.DEBUG, timerParams,
                overlayPlatform.physical, overlayWindowInstaller, overlayWindowUpdater).also {
                it.addAttempted = true
                it.mayReAdd = false
                timerWindow = it
            }
        } ?: return finishTimerDetach(epoch)
        timerRemoving = true
        val result = try {
            record.closeInteraction()
            OverlayWindowRemover.attempt(record, bound && record.mayReAdd)
        } finally { timerRemoving = false }
        if (epoch != timerEpoch || timerWindow !== record) return
        if (result == OverlayRemovalResult.DETACHED) return finishTimerDetach(epoch)
        if (timerRemovalAttempts < MAX_REMOVAL_ATTEMPTS) timerRemovalAttempts++
        val slow = timerRemovalAttempts >= MAX_REMOVAL_ATTEMPTS
        if (slow && !timerSafetyVeto) {
            timerSafetyVeto = true
            endTimerSession()
            publishGateState()
        }
        timerRemovalLoop.schedule(if (slow) REMOVAL_SLOW_RETRY_INTERVAL_MS else REMOVAL_RETRY_INTERVAL_MS,
            { epoch == timerEpoch && timerWindow === record }) { attemptTimerRemoval(epoch) }
    }

    private fun finishTimerDetach(epoch: Long) {
        if (epoch != timerEpoch) return
        val old = timerView
        if (old != null && timerWindow == null) return
        if (timerWindow?.let { OverlayWindowRemover.attachment(it) != OverlayAttachment.DETACHED } == true) return
        timerRemovalLoop.cancel()
        timerWindow?.release(); timerWindow = null
        stopTimerCallbacks()
        old?.setOnApplyWindowInsetsListener(null)
        timerUi?.dispose(); timerUi = null; timerView = null; timerManager = null; timerParams = null
        timerEpoch++
        timerClosing = false
        maybeDisableAfterDetach()
        val pendingGate = gateAfterTimerDetach
        gateAfterTimerDetach = null
        if (timerTerminalReset || timerSafetyVeto) {
            cancelledGateAfterTimerDetach?.let(::cancelCurrentGatingTicket)
            cancelledGateAfterTimerDetach = null
            publishGateState()
            timerTerminalReset = false
            sessionTimer.endSession()
            return
        }
        if (pendingGate != null && !behaviorStopped && !disableWhenDetached) installOverlay(pendingGate)
    }

    private fun ownedWindow(view: View, manager: WindowManager, role: GateWindowRole,
                            params: WindowManager.LayoutParams) = OwnedOverlayWindow(
        view, manager, role, params, overlayPlatform.physical, overlayWindowInstaller, overlayWindowUpdater)

    private fun gateInstallAuthorized(episode: GateOverlayWindows, activeTicket: GateTicket): Boolean =
        gateWindows === episode && overlayToken === episode.token && callbackGuard.acceptsVisible(episode.token) &&
            ticket == activeTicket && activeTicket.generation == entryGate.generation &&
            entryGate.state == EntryGateState.GATING && timerConsentAllowed() &&
            sampleTimerAuthority() == OverlayForegroundDecision.KEEP && timerView == null && !timerClosing &&
            gateWindows === episode && callbackGuard.acceptsVisible(episode.token) && timerConsentAllowed()

    private fun gateLayout(manager: WindowManager, ui: EntryGateOverlayUi,
                           insets: android.view.WindowInsets? = ui.visualRoot.rootWindowInsets): List<GateWindowDescriptor>? {
        val frame = if (android.os.Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val safe = InstagramTimerOverlayViewFactory.safeInsets(manager, insets)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val types = android.view.WindowInsets.Type.systemGestures() or android.view.WindowInsets.Type.mandatorySystemGestures()
            val gestures = manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types)
            val delivered = insets?.getInsetsIgnoringVisibility(types)
            safe.set(maxOf(safe.left, gestures.left, delivered?.left ?: 0),
                maxOf(safe.top, gestures.top, delivered?.top ?: 0),
                maxOf(safe.right, gestures.right, delivered?.right ?: 0),
                maxOf(safe.bottom, gestures.bottom, delivered?.bottom ?: 0))
        } else if (android.os.Build.VERSION.SDK_INT >= 29 && insets != null) {
            val gestures = insets.systemGestureInsets
            safe.set(maxOf(safe.left, gestures.left), maxOf(safe.top, gestures.top),
                maxOf(safe.right, gestures.right), maxOf(safe.bottom, gestures.bottom))
        }
        val margins = GateSafeInsets(safe.left, safe.top, safe.right, safe.bottom)
        val density = resources.displayMetrics.density
        val width = GateOverlayWindowLayout.actionWidth(frame.width(), margins, density)
        if (width <= 0) return null
        val heights = ui.windowRoots.drop(1).map { button ->
            button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            button.measuredHeight
        }
        val layout = GateOverlayWindowLayout.calculate(frame.width(), frame.height(), margins, density, heights) ?: return null
        val decorationWidth = minOf(240.dp(), frame.width() - safe.left - safe.right - 32.dp()).coerceAtLeast(1)
        val decorationHeight = minOf(240.dp(), layout[1].bounds.y - safe.top - 32.dp()).coerceAtLeast(1)
        ui.layoutDecoration(safe.left + (frame.width() - safe.left - safe.right - decorationWidth) / 2,
            safe.top + 16.dp(), decorationWidth, decorationHeight)
        return layout
    }

    private fun updateGateLayout(episode: GateOverlayWindows, insets: android.view.WindowInsets? = null) {
        if (gateWindows !== episode) return
        if (!callbackGuard.acceptsVisible(episode.token)) return
        if (episode.installing) return
        try {
            if (overlayToken !== episode.token || ticket != episode.token.ticket || !timerConsentAllowed())
                throw IllegalStateException("Stale gate layout")
            val manager = windowManager ?: throw IllegalStateException("Missing gate manager")
            val layout = gateLayout(manager, episode.ui, insets) ?: throw IllegalStateException("Gate does not fit")
            episode.records.zip(layout).forEach { (record, descriptor) ->
                if (gateWindows !== episode || !callbackGuard.acceptsVisible(episode.token)) return
                if (OverlayWindowRemover.attachment(record) != OverlayAttachment.ATTACHED)
                    throw IllegalStateException("Gate window no longer attached")
                val params = GateOverlayWindows.parameters(descriptor)
                record.params = WindowManager.LayoutParams().apply { copyFrom(params) }
                overlayWindowUpdater(manager, record.view, params)
            }
        } catch (_: RuntimeException) {
            if (gateWindows === episode) requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.INSTALL_FAILURE)
        }
    }

    private fun installOverlay(activeTicket: GateTicket) {
        if (gateWindows != null || timerView != null || timerClosing || !timerConsentAllowed()) return
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
                onDebugReport = {
                    requestDebugReport(token)
                }
            )
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (activeTicket != ticket || activeTicket.generation != entryGate.generation ||
                entryGate.state != EntryGateState.GATING || !timerConsentAllowed() ||
                sampleTimerAuthority() != OverlayForegroundDecision.KEEP ||
                timerView != null || timerClosing || gateWindows != null) {
                ui.dispose()
                if (activeTicket == ticket && activeTicket.generation == entryGate.generation) {
                    cancelCurrentGatingTicket(activeTicket)
                    endTimerSession()
                    publishGateState()
                }
                return
            }
            windowManager = manager
            val episode = GateOverlayWindows(token, ui)
            gateWindows = episode
            overlayUi = ui
            overlayToken = token
            try {
                val layout = gateLayout(manager, ui) ?: throw IllegalStateException("Gate does not fit")
                layout.zip(ui.windowRoots).forEach { (descriptor, root) ->
                    if (!gateInstallAuthorized(episode, activeTicket)) throw IllegalStateException("Stale gate install")
                    val parameters = GateOverlayWindows.parameters(descriptor)
                    val record = ownedWindow(root, manager, descriptor.role, parameters)
                    episode.records += record
                    record.addAttempted = true
                    overlayWindowInstaller(manager, root, parameters)
                    if (OverlayWindowRemover.attachment(record) != OverlayAttachment.ATTACHED)
                        throw IllegalStateException("Gate add did not register")
                    if (!gateInstallAuthorized(episode, activeTicket)) throw IllegalStateException("Stale gate install")
                }
            } finally { episode.installing = false }
            ui.visualRoot.setOnApplyWindowInsetsListener { _, insets ->
                updateGateLayout(episode, insets)
                insets
            }
            if (!gateInstallAuthorized(episode, activeTicket)) {
                requestSafetyCleanup(OverlayRemovalAction.BYPASS, RemovalTraceMark.INSTALL_FAILURE)
                return
            }

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
            }.also { handler.postDelayed(it, entryGate.durationSnapshotMs()) }
            watchdog = object : Runnable {
                override fun run() {
                    runWatchdogTick(activeTicket, token, this)
                }
            }.also { handler.post(it) }
        } catch (failure: RuntimeException) {
            if (failure is WindowManager.BadTokenException) gateWindows?.records?.forEach { it.mayReAdd = false }
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
        if (gateWindows == null || !callbackGuard.acceptsVisible(token)) return
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
            if (decision == OverlayForegroundDecision.KEEP && sessionTimer.running) observeTimerInstagram()
            handler.postDelayed(next, WATCHDOG_INTERVAL_MS)
        } catch (_: RuntimeException) {
            requestSafetyCleanup(
                OverlayRemovalAction.BYPASS,
                RemovalTraceMark.WATCHDOG_FAILURE,
                root = RemovalTraceRoot.NOT_READ,
            )
        }
    }

    /** Debug navigation is an external action and therefore follows the same detach transaction. */
    private fun requestDebugReport(token: OverlayCallbackToken) {
        if (gateWindows == null || overlayToken !== token || ticket != token.ticket ||
            token.ticket.generation != entryGate.generation ||
            !callbackGuard.acceptsVisible(token) || entryGate.state != EntryGateState.GATING ||
            !Observation.reminderSettings.enabled || !Observation.consent ||
            !Observation.gateConsent || !Observation.connected
        ) return
        requestOverlayRemoval(OverlayRemovalAction.CAPTURE_DEBUG, token, RemovalTraceMark.USER_DEBUG)
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
        if (gateWindows == null) return
        val accepted = if (token != null) callbackGuard.acceptsVisible(token) && callbackGuard.beginClosing(token)
        else currentToken != null && callbackGuard.beginClosing(currentToken)
        if (!accepted) return
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        overlayUi?.closeInteraction()
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
        endTimerSession()
        captureEventProvenance = null
        callbackGuard.invalidateVisible()
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        overlayUi?.closeInteraction()
        if (cause != null && (gateWindows != null || ticket != null)) {
            traceRecord(cause, event = event, owner = owner, root = root, action = traceAction(action), now = now)
        }
        if (gateWindows != null) traceRecord(RemovalTraceMark.CLOSING, action = traceAction(action))
        removalPolicy.requestSafetyCleanup(action)
        attemptOverlayRemoval(overlayToken)
    }

    private fun attemptOverlayRemoval(token: OverlayCallbackToken? = overlayToken) {
        if (token != overlayToken || token != null && !callbackGuard.acceptsRemoval(token)) return
        val episode = gateWindows
        if (episode == null) { confirmOverlayRemoved(token); return }
        if (episode.installing || episode.removing || removalLoop.isPending) return
        episode.removing = true
        try {
            episode.removalOrder().forEach { record ->
                if (gateWindows !== episode || !callbackGuard.acceptsRemoval(episode.token)) return
                record.closeInteraction()
                OverlayWindowRemover.attempt(record, bound && record.mayReAdd)
                if (!record.mayReAdd) episode.records.forEach { it.mayReAdd = false }
            }
        } finally { episode.removing = false }
        if (gateWindows !== episode || !callbackGuard.acceptsRemoval(episode.token)) return
        if (episode.allDetached()) { confirmOverlayRemoved(token, observedBeforeRemove = false); return }
        val decision = removalPolicy.failedAttempt()
        if (decision == OverlayRemovalDecision.RETRY_SLOW && !episode.degraded) {
            episode.degraded = true
            traceFinish(RemovalTraceMark.REMOVAL_EXHAUSTED, RemovalTraceAction.NONE,
                detached = false, policyReleased = false,
                vetoedAction = removalPolicy.vetoedExternalAction()?.let(::traceAction) ?: RemovalTraceAction.NONE)
            endTimerSession()
        } else if (!episode.degraded) traceRecord(RemovalTraceMark.REMOVAL_RETRY)
        removalLoop.schedule(
            if (decision == OverlayRemovalDecision.RETRY_SLOW) REMOVAL_SLOW_RETRY_INTERVAL_MS else REMOVAL_RETRY_INTERVAL_MS,
            { gateWindows === episode && callbackGuard.acceptsRemoval(episode.token) }
        ) { attemptOverlayRemoval(token) }
    }

    private fun confirmOverlayRemoved(
        detachedToken: OverlayCallbackToken? = overlayToken,
        observedBeforeRemove: Boolean = true,
    ) {
        if (detachedToken != overlayToken || detachedToken != null && !callbackGuard.acceptsRemoval(detachedToken)) return
        val detachedEpisode = gateWindows
        if (detachedEpisode != null && (detachedEpisode.installing || !detachedEpisode.allDetached())) return
        removalLoop.cancel()
        val ownsDetachedEpisode = detachedEpisode != null && detachedToken != null
        if (detachedToken != null) callbackGuard.detached(detachedToken)
        detachedEpisode?.dispose()
        overlayUi = null
        gateWindows = null
        windowManager = null
        overlayToken = null
        maybeDisableAfterDetach()
        val vetoedAction = removalPolicy.vetoedExternalAction()?.let(::traceAction)
            ?: RemovalTraceAction.NONE
        val action = removalPolicy.confirmedDetached()
        val hadOverlay = detachedEpisode != null
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
                endTimerSession()
                entryGate.cancel()
                publishGateState()
                Observation.clear()
            }
            OverlayRemovalAction.CAPTURE_DEBUG -> {
                val captureTicket = detachedToken?.ticket
                if (captureTicket == null || !ownsDetachedEpisode ||
                    !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                    !Observation.reminderSettings.enabled || !Observation.consent ||
                    !Observation.gateConsent || !Observation.connected
                ) {
                    captureTicket?.let(::cancelCurrentGatingTicket)
                    publishGateState()
                    Observation.clear()
                    detachedToken?.let(callbackGuard::consumeDetached)
                    return
                }
                val context = tapCaptureContext()
                val traversalRoot = try { overlayPlatform.currentRoot() } catch (_: RuntimeException) { null }
                if (context == null || traversalRoot == null) {
                    traversalRoot?.let { try { it.recycle() } catch (_: RuntimeException) { } }
                    cancelCurrentGatingTicket(captureTicket)
                    publishGateState()
                    Observation.clear()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                val candidate = try { collectCandidate(traversalRoot, context) } catch (_: RuntimeException) { null }
                if (candidate == null) {
                    cancelCurrentGatingTicket(captureTicket)
                    publishGateState()
                    Observation.clear()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                val watchdogRoot = try { overlayPlatform.currentRoot() } catch (_: RuntimeException) { null }
                if (watchdogRoot == null) {
                    cancelCurrentGatingTicket(captureTicket)
                    publishGateState()
                    Observation.clear()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                try {
                    val packageName = try { overlayPlatform.readRootPackage(watchdogRoot) } catch (_: RuntimeException) { null }
                    if (packageName != INSTAGRAM ||
                        !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                        !Observation.reminderSettings.enabled || !Observation.consent ||
                        !Observation.gateConsent || !Observation.connected
                    ) {
                        cancelCurrentGatingTicket(captureTicket)
                        publishGateState()
                        Observation.clear()
                        return
                    }
                    endTimerSession()
                    if (!entryGate.bypass(captureTicket)) {
                        cancelCurrentGatingTicket(captureTicket)
                        publishGateState()
                        Observation.clear()
                        return
                    }
                    terminalGateSucceeded = false
                    publishGateState()
                    if (!hasDetachedTerminalAuthority(detachedToken, EntryGateState.BYPASSED) ||
                        !Observation.reminderSettings.enabled || !Observation.consent ||
                        !Observation.gateConsent || !Observation.connected
                    ) {
                        Observation.clear()
                        return
                    }
                    if (copyHook(candidate) != OverlayCopyResult.COPIED) Observation.clear()
                } finally {
                    try { overlayPlatform.recycleRoot(watchdogRoot) } catch (_: RuntimeException) { }
                    callbackGuard.consumeDetached(detachedToken)
                }
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
                    val instagramForeground = safePackageToken(root.packageName) == INSTAGRAM
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
                    val succeeded = if (hasDetachedTerminalAuthority(detachedToken, EntryGateState.BYPASSED)) {
                        entryGate.finishMessagesRoute(monotonicClock(), routeTicket, result)
                    } else {
                        entryGate.bypass(routeTicket)
                        false
                    }
                    publishGateState()
                    if (succeeded) {
                        terminalGateSucceeded = true
                        attachTimerIfAllowed()
                    } else recoverFailedMessagesRoute(detachedToken)
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
                    recoverFailedMessagesRoute(detachedToken)
                } finally {
                    try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }
                    callbackGuard.consumeDetached(detachedToken)
                }
            }
            OverlayRemovalAction.PRESERVE_REPORT -> {
                if (!mainActivityReturnObserved) return
                endTimerSession()
                entryGate.leaveInstagram()
                ticket = null
                captureEventProvenance = null
                mainActivityReturnObserved = false
                publishGateState()
            }
            OverlayRemovalAction.RESET_OUTSIDE -> {
                endTimerSession()
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
                    val validRoot = safePackageToken(root.packageName) == INSTAGRAM
                    if (!validRoot || !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                        !entryGate.complete(monotonicClock(), completeTicket)
                    ) {
                        cancelCurrentGatingTicket(completeTicket)
                    }
                    publishGateState()
                    if (entryGate.state == EntryGateState.GRANTED) {
                        terminalGateSucceeded = true
                        attachTimerIfAllowed()
                    }
                } catch (_: RuntimeException) {
                    cancelCurrentGatingTicket(completeTicket)
                    publishGateState()
                } finally {
                    try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }
                    callbackGuard.consumeDetached(detachedToken)
                }
            }
            OverlayRemovalAction.HOME -> {
                if (detachedToken == null || !ownsDetachedEpisode ||
                    !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING)) {
                    detachedToken?.ticket?.let(::cancelCurrentGatingTicket)
                    detachedToken?.let(callbackGuard::consumeDetached)
                    return
                }
                endTimerSession()
                val activeTicket = ticket
                if (activeTicket == null || !entryGate.bypass(activeTicket)) entryGate.cancel()
                publishGateState()
                Observation.clear()
                overlayPlatform.performHome()
                callbackGuard.consumeDetached(detachedToken)
            }
            null -> Unit
        }
    }

    private fun recoverFailedMessagesRoute(token: OverlayCallbackToken) {
        if (token.ticket != ticket) return
        val session = timerSessionEpoch
        val authorized = hasDetachedTerminalAuthority(token, EntryGateState.BYPASSED)
        if (!authorized) { endTimerSession(); return }
        val sample = readPackageRoot { overlayPlatform.currentRoot() }
        if (session != timerSessionEpoch || token.ticket != ticket) return
        if (!hasDetachedTerminalAuthority(token, EntryGateState.BYPASSED) ||
            sample.packageName != INSTAGRAM) {
            endTimerSession()
            return
        }
        entryGate.leaveInstagram()
        ticket = null
        terminalGateSucceeded = false
        publishGateState()
        if (sessionTimer.running) observeTimerInstagram()
        attachTimerIfAllowed()
    }

    private fun hasDetachedTerminalAuthority(
        token: OverlayCallbackToken,
        expectedState: EntryGateState,
    ): Boolean = callbackGuard.acceptsDetached(token) && gateWindows == null && overlayToken == null &&
        token.ticket == ticket && token.ticket.generation == entryGate.generation &&
        timerConsentAllowed() &&
        entryGate.state == expectedState

    /** Abandon only the detached action's own still-current gate after authority is lost. */
    private fun cancelCurrentGatingTicket(candidate: GateTicket) {
        if (candidate != ticket || entryGate.state != EntryGateState.GATING) return
        entryGate.cancel()
        endTimerSession()
    }

    private fun cancelAndBypass() {
        endTimerSession()
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
        val remaining = entryGate.remainingMs(monotonicClock(), activeTicket)
        val duration = entryGate.durationSnapshotMs()
        val model = EntryGateOverlayModel.from(remaining, duration, reduceMotion)
        overlayUi?.render(model)
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
        OverlayRemovalAction.CAPTURE_DEBUG -> RemovalTraceAction.CAPTURE_DEBUG
        OverlayRemovalAction.RESET_OUTSIDE -> RemovalTraceAction.RESET_OUTSIDE
    }

    private fun isMainActivityReturn(event: AccessibilityEvent?): Boolean =
        event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            StructuralSanitizer.isExactAscii(event.className, MainActivity::class.java.name)

    override fun onInterrupt() {
        endTimerSession()
        timerSafetyVeto = true
        stopTimerCallbacks()
        traceRecord(RemovalTraceMark.SERVICE_INTERRUPTED, action = RemovalTraceAction.BYPASS)
        requestSafetyCleanup(OverlayRemovalAction.BYPASS)
        Observation.connected = false
        Observation.clear()
        behaviorStopped = true
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

    private fun requestDisable() {
        disableWhenDetached = true
        behaviorStopped = true
        cancelAndBypass()
        maybeDisableAfterDetach()
    }

    private fun maybeDisableAfterDetach() {
        if (!disableWhenDetached || !bound || gateWindows != null || timerView != null) return
        if (!RetiringOverlayCleanup.barrier.canDisable(false, false)) {
            // This current service owns the explicit Stop request. The retired owner never
            // retains a service continuation; this queue only waits for its physical barrier.
            removalLoop.schedule(REMOVAL_SLOW_RETRY_INTERVAL_MS,
                { bound && disableWhenDetached && gateWindows == null && timerView == null }) {
                maybeDisableAfterDetach()
            }
            return
        }
        removalLoop.cancel()
        disableWhenDetached = false
        disableService()
    }

    private fun disconnect() {
        bound = false
        behaviorStopped = true
        endTimerSession()
        timerSafetyVeto = true
        stopTimerCallbacks()
        cancelAndBypass()
        val records = gateWindows?.removalOrder().orEmpty() + listOfNotNull(timerWindow)
        // Transfer exactly once. Retirement owns only physical handles, never action continuations.
        gateWindows?.dispose()
        timerUi?.dispose()
        RetiringOverlayCleanup.retire(records, listOf(removalLoop, timerRemovalLoop))
        gateWindows = null; overlayUi = null; overlayToken = null; windowManager = null
        timerWindow = null; timerView = null; timerUi = null; timerManager = null; timerParams = null
        ticket = null
        if (instance === this) instance = null
        Observation.connected = false
        Observation.clear()
    }
}
