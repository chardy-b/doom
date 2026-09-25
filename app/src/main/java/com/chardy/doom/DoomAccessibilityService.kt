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

internal interface OverlayPlatform {
    fun isAttached(view: View): Boolean
    fun removeImmediate(manager: WindowManager, view: View)
    fun currentRoot(): AccessibilityNodeInfo?
    /** Event-root seam; production reads the same root property used by the baseline path. */
    fun eventRoot(): AccessibilityNodeInfo? = currentRoot()
    /** Package-only seam; production does not inspect any other root property here. */
    fun readRootPackage(root: AccessibilityNodeInfo): String? = safePackageToken(root.packageName)
    fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult
    fun recycleRoot(root: AccessibilityNodeInfo) = root.recycle()
    fun performHome(): Boolean
    fun openDebug(): Boolean = false
}

/** Diagnostic-only, default-off entry pause. A user tap may best-effort route to Instagram messages. */
class DoomAccessibilityService : AccessibilityService() {
    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val WATCHDOG_INTERVAL_MS = 50L
        private const val WATCHDOG_UNCERTAINTY_GRACE_MS = 150L
        private const val REMOVAL_RETRY_INTERVAL_MS = 50L
        private const val MAX_REMOVAL_ATTEMPTS = 20
        private var instance: DoomAccessibilityService? = null

        fun cancelEntryGate() {
            instance?.cancelAndBypass()
            if (instance == null) Observation.entryGateState = EntryGateState.OUTSIDE
        }

        fun sessionTimerPreferenceChanged(enabled: Boolean) {
            instance?.onTimerPreferenceChanged(enabled)
        }

        fun disableObservation() {
            instance?.endTimerSession()
            instance?.cancelAndBypass()
            Observation.connected = false
            Observation.clear()
            RemovalTraceStore.process.clear()
            instance?.stopTimerCallbacks()
            instance?.disableSelf()
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
    private var overlay: View? = null
    private var overlayUi: EntryGateOverlayUi? = null
    private var windowManager: WindowManager? = null
    private var watchdog: Runnable? = null
    private var completion: Runnable? = null
    private var removalRetry: Runnable? = null
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
    private var timerRetry: Runnable? = null
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
    private var debugDepartureTicket: GateTicket? = null
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
        override fun openDebug(): Boolean = try {
            startActivity(MainActivity.debugIntent(this@DoomAccessibilityService))
            true
        } catch (_: RuntimeException) {
            false
        }
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
            val packageName = safePackageToken(event?.packageName, applicationContext.packageName)
            val traceCapturing = RemovalTraceStore.process.isCapturing()
            val eventKind = if (traceCapturing) traceEventKind(event) else RemovalTraceEvent.NA
            val owner = if (traceCapturing) traceOwner(packageName) else RemovalTraceOwner.NA
            if ((overlay != null || sessionTimer.running) &&
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
                    if (overlay == null) {
                        entryGate.leaveInstagram()
                        ticket = null
                        debugDepartureTicket = null
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

            // A successful Debug departure keeps the current report but must not recollect or
            // restart a timer until the verified Doom return clears this process-local latch.
            if (debugDepartureTicket != null && debugDepartureTicket == ticket &&
                entryGate.state == EntryGateState.BYPASSED
            ) return

            // An installed or closing gate already owns this episode. Instagram event roots
            // can be transient; leave package-only foreground validation to the bounded
            // watchdog instead of recollecting or vetoing the pending terminal action.
            if (overlay != null) return
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
                    debugDepartureTicket = null
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
            if (!collect(root, captureContext)) {
                if (activeTicket != null) requestSafetyCleanup(
                    OverlayRemovalAction.BYPASS, RemovalTraceMark.EVENT_FAILURE,
                    eventKind, owner, RemovalTraceRoot.READ_FAILURE,
                ) else {
                    Observation.entryGateState = EntryGateState.OUTSIDE
                }
                return
            }
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
                if (timerView != null || timerClosing) suspendTimerForGate(activeTicket)
                else if (overlay == null) installOverlay(activeTicket)
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
    private fun collect(root: AccessibilityNodeInfo, context: StructuralCaptureContext): Boolean {
        if (root.packageName?.let { StructuralSanitizer.isExactAscii(it, AndroidStructuralMetadataReader.INSTAGRAM_PACKAGE) } != true) {
            root.recycle()
            Observation.record(null)
            return false
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
                    val metadata = reader.read(node, entry.position.copy(bfsOrdinal = visited - 1), context)
                    val elapsedOffset = metadata.elapsedOffsetMs
                    if (elapsedOffset is MetadataValue.Unavailable) when (elapsedOffset.reason) {
                        MetadataUnavailableReason.CLOCK_ROLLBACK -> {
                            // A backward elapsedRealtime sample invalidates this capture; never
                            // publish a partial report assembled across an invalid clock.
                            Observation.record(null)
                            return false
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
                    node.recycle()
                }
            }
            if (queue.isNotEmpty()) builder.markTruncated("nodes")
            Observation.record(builder.build())
            return true
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
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
        Observation.consent && Observation.gateConsent && Observation.connected

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
        sessionTimer.running && !timerClosing && timerView != null && overlay == null &&
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
        if (!sessionTimer.running || timerView != null || timerClosing || overlay != null ||
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
                overlay != null || timerClosing) {
                created.dispose(); return
            }
            // Own the window before the platform call: addView may attach and then throw.
            timerManager = manager; timerUi = created; timerView = created.root; timerParams = params
            created.root.setOnApplyWindowInsetsListener { _, insets ->
                updateTimerLayoutForInsets(epoch, insets)
                insets
            }
            overlayWindowInstaller(manager, created.root, params)
            if (epoch != timerEpoch || session != timerSessionEpoch) return
            if (!timerWindowCurrent(epoch)) { endTimerSession(); return }
            renderTimer(epoch, true)
            created.root.addOnLayoutChangeListener { _, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
                if (created.root.width != oldRight-oldLeft || created.root.height != oldBottom-oldTop) settleTimer(epoch, false)
            }
            if (timerWindowCurrent(epoch)) {
                cancelSessionBoundaryCheck()
                timerWatchdog = object : Runnable {
                    override fun run() {
                        if (timerWatchdog !== this || !timerWindowAuthorized(epoch)) return
                        val view = timerView ?: return
                        if (!overlayPlatform.isAttached(view)) { endTimerSession(); return }
                        handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    }
                }.also { handler.postDelayed(it, WATCHDOG_INTERVAL_MS) }
            }
        } catch (_: RuntimeException) {
            created?.dispose()
            if (epoch == timerEpoch) endTimerSession()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTimerLayout(timerEpoch)
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
            if (!overlayPlatform.isAttached(view)) { endTimerSession(); return }
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
        if (!overlayPlatform.isAttached(view)) { endTimerSession(); return }
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
        timerRetry?.let(handler::removeCallbacks); timerRetry = null
    }

    private fun beginTimerRemoval(reset: Boolean) {
        timerTerminalReset = timerTerminalReset || reset
        if (timerClosing) return
        stopTimerCallbacks()
        if (timerView == null) { finishTimerDetach(timerEpoch); return }
        timerClosing = true
        timerUi?.dispose()
        timerRemovalAttempts = 0
        attemptTimerRemoval(timerEpoch)
    }

    private fun attemptTimerRemoval(epoch: Long) {
        if (epoch != timerEpoch || !timerClosing) return
        timerRetry?.let(handler::removeCallbacks); timerRetry = null
        val view = timerView ?: return finishTimerDetach(epoch)
        if (!overlayPlatform.isAttached(view)) return finishTimerDetach(epoch)
        try { timerManager?.let { overlayPlatform.removeImmediate(it, view) } } catch (_: RuntimeException) {}
        if (!overlayPlatform.isAttached(view)) return finishTimerDetach(epoch)
        timerRemovalAttempts++
        if (timerRemovalAttempts >= MAX_REMOVAL_ATTEMPTS) {
            timerSafetyVeto = true
            endTimerSession()
            stopTimerCallbacks()
            Observation.connected = false
            publishGateState()
            disableSelf()
            return
        }
        timerRetry = Runnable { attemptTimerRemoval(epoch) }.also {
            handler.postDelayed(it, REMOVAL_RETRY_INTERVAL_MS)
        }
    }

    private fun finishTimerDetach(epoch: Long) {
        if (epoch != timerEpoch) return
        val old = timerView
        if (old != null && overlayPlatform.isAttached(old)) return
        stopTimerCallbacks()
        old?.setOnApplyWindowInsetsListener(null)
        timerUi?.dispose(); timerUi = null; timerView = null; timerManager = null; timerParams = null
        timerEpoch++
        timerClosing = false
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
        if (pendingGate != null) installOverlay(pendingGate)
    }

    private fun installOverlay(activeTicket: GateTicket) {
        if (overlay != null || timerView != null || timerClosing) return
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
            val box = ui.root

            val parameters = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (activeTicket != ticket || activeTicket.generation != entryGate.generation ||
                entryGate.state != EntryGateState.GATING || !timerConsentAllowed() ||
                sampleTimerAuthority() != OverlayForegroundDecision.KEEP ||
                timerView != null || timerClosing || overlay != null) {
                ui.dispose()
                if (activeTicket == ticket && activeTicket.generation == entryGate.generation) {
                    cancelCurrentGatingTicket(activeTicket)
                    endTimerSession()
                    publishGateState()
                }
                return
            }
            windowManager = manager
            overlay = box
            overlayUi = ui
            overlayToken = token
            overlayWindowInstaller(manager, box, parameters)

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
            if (decision == OverlayForegroundDecision.KEEP && sessionTimer.running) observeTimerInstagram()
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

    /** Debug navigation is an external action and therefore follows the same detach transaction. */
    private fun requestDebugReport(token: OverlayCallbackToken) {
        if (overlay == null || overlayToken !== token || ticket != token.ticket ||
            token.ticket.generation != entryGate.generation ||
            !callbackGuard.acceptsVisible(token) || entryGate.state != EntryGateState.GATING ||
            !Observation.reminderSettings.enabled || !Observation.consent ||
            !Observation.gateConsent || !Observation.connected
        ) return
        requestOverlayRemoval(OverlayRemovalAction.OPEN_DEBUG, token, RemovalTraceMark.USER_DEBUG)
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
        endTimerSession()
        debugDepartureTicket = null
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
                endTimerSession()
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
                endTimerSession()
                entryGate.cancel()
                publishGateState()
                Observation.clear()
            }
            OverlayRemovalAction.OPEN_DEBUG -> {
                val departureTicket = detachedToken?.ticket
                if (departureTicket == null || !ownsDetachedEpisode ||
                    !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                    !Observation.reminderSettings.enabled || !Observation.consent ||
                    !Observation.gateConsent || !Observation.connected
                ) {
                    departureTicket?.let(::cancelCurrentGatingTicket)
                    publishGateState()
                    Observation.clear()
                    detachedToken?.let(callbackGuard::consumeDetached)
                    return
                }
                val root = try { overlayPlatform.currentRoot() } catch (_: RuntimeException) { null }
                if (root == null) {
                    cancelCurrentGatingTicket(departureTicket)
                    publishGateState()
                    Observation.clear()
                    callbackGuard.consumeDetached(detachedToken)
                    return
                }
                try {
                    val packageName = try { overlayPlatform.readRootPackage(root) } catch (_: RuntimeException) { null }
                    val verifiedForeground = packageName == INSTAGRAM ||
                        (packageName == applicationContext.packageName && mainActivityReturnObserved)
                    if (!verifiedForeground || !hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING) ||
                        !Observation.reminderSettings.enabled || !Observation.consent ||
                        !Observation.gateConsent || !Observation.connected ||
                        !entryGate.bypass(departureTicket)
                    ) {
                        cancelCurrentGatingTicket(departureTicket)
                        publishGateState()
                        Observation.clear()
                        return
                    }
                    endTimerSession()
                    terminalGateSucceeded = false
                    debugDepartureTicket = departureTicket
                    Observation.hideReport()
                    publishGateState()
                    try { overlayPlatform.openDebug() } catch (_: RuntimeException) { /* detached, preserved */ }
                } finally {
                    try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }
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
                debugDepartureTicket = null
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
                endTimerSession()
                val activeTicket = ticket
                if (activeTicket == null || !entryGate.bypass(activeTicket)) entryGate.cancel()
                publishGateState()
                Observation.clear()
                overlayPlatform.performHome()
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
    ): Boolean = callbackGuard.acceptsDetached(token) && overlay == null && overlayToken == null &&
        token.ticket == ticket && token.ticket.generation == entryGate.generation &&
        Observation.consent && Observation.gateConsent && Observation.connected &&
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
        OverlayRemovalAction.OPEN_DEBUG -> RemovalTraceAction.OPEN_DEBUG
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
        handler.removeCallbacksAndMessages(null)
        removalRetry = null
        if (timerView != null || overlay != null) disableSelf()
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
        endTimerSession()
        timerSafetyVeto = true
        stopTimerCallbacks()
        cancelAndBypass()
        ticket = null
        if (instance === this) instance = null
        Observation.connected = false
        Observation.clear()
        handler.removeCallbacksAndMessages(null)
        removalRetry = null
    }
}
