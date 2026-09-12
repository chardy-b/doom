package com.chardy.doom

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** Diagnostic-only, default-off entry pause. Never navigates or acts on Instagram nodes. */
class DoomAccessibilityService : AccessibilityService() {
    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val GATE_DURATION_MS = 5_000L
        private const val WATCHDOG_INTERVAL_MS = 50L
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
    private val entryGate = InstagramEntryGate(
        durationMs = GATE_DURATION_MS,
        enabled = { Observation.gateConsent && Observation.consent && Observation.connected }
    )
    private var ticket: GateTicket? = null
    private var overlay: View? = null
    private var countdownView: TextView? = null
    private var windowManager: WindowManager? = null
    private var watchdog: Runnable? = null
    private var completion: Runnable? = null
    private var removalRetry: Runnable? = null
    private var mainActivityReturnObserved = false
    private val removalPolicy = OverlayRemovalPolicy(MAX_REMOVAL_ATTEMPTS)

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
                    requestOverlayRemoval(OverlayRemovalAction.PRESERVE_REPORT)
                }
                // Ignore all other Doom-owned events, including overlay updates.
                return
            }
            if (packageName != INSTAGRAM) {
                resetOutside()
                return
            }
            if (!Observation.consent || !Observation.connected) {
                cancelAndBypass()
                return
            }

            val activeTicket = if (Observation.gateConsent) {
                ticket ?: entryGate.beginInstagramSession().also {
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

            val surface = InstagramSurfaceShadowClassifier.classify(Observation.report).toGateSurface()
            val shouldShow = entryGate.observeInstagram(SystemClock.elapsedRealtime(), activeTicket)
            publishGateState()
            if (shouldShow) {
                if (overlay == null) installOverlay(activeTicket, surface)
            } else {
                requestOverlayRemoval(OverlayRemovalAction.BYPASS)
            }
        } catch (_: RuntimeException) {
            failOpen()
        }
    }

    private fun InstagramSurface.toGateSurface() = when (this) {
        InstagramSurface.FEED -> EntryGateSurface.FEED
        InstagramSurface.REELS -> EntryGateSurface.REELS
        InstagramSurface.STORIES -> EntryGateSurface.STORIES
        InstagramSurface.MESSAGING -> EntryGateSurface.MESSAGING
        InstagramSurface.UNKNOWN -> EntryGateSurface.UNKNOWN
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

    private fun installOverlay(activeTicket: GateTicket, surface: EntryGateSurface) {
        if (overlay != null) return
        try {
            val overlayUi = EntryGateOverlayViewFactory.create(
                this,
                surface = surface,
                onDismissForMessages = {
                    requestOverlayRemoval(OverlayRemovalAction.BYPASS)
                },
                onLeaveInstagram = {
                    requestOverlayRemoval(OverlayRemovalAction.HOME)
                }
            )
            val box = overlayUi.root
            val countdown = overlayUi.countdown

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
            countdownView = countdown

            val shownAt = SystemClock.elapsedRealtime()
            if (!entryGate.overlayShown(shownAt, activeTicket)) {
                requestOverlayRemoval(OverlayRemovalAction.BYPASS)
                return
            }
            publishGateState()
            completion = Runnable {
                requestOverlayRemoval(OverlayRemovalAction.COMPLETE)
            }.also { handler.postDelayed(it, GATE_DURATION_MS) }
            watchdog = object : Runnable {
                override fun run() {
                    if (overlay == null) return
                    try {
                        val activeRoot = rootInActiveWindow
                        val packageName = try {
                            activeRoot?.packageName?.toString()
                        } finally {
                            activeRoot?.recycle()
                        }
                        // if (packageName != INSTAGRAM) fails open unless this is a verified Doom return.
                        if (packageName != INSTAGRAM &&
                            !(packageName == applicationContext.packageName && mainActivityReturnObserved)
                        ) {
                            failOpen()
                            return
                        }
                        val remaining = entryGate.remainingMs(
                            SystemClock.elapsedRealtime(),
                            activeTicket
                        )
                        countdownView?.text = "${(remaining + 999L) / 1_000L}s remaining"
                        handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    } catch (_: RuntimeException) {
                        failOpen()
                    }
                }
            }.also { handler.post(it) }
        } catch (_: RuntimeException) {
            failOpen()
        }
    }

    private fun requestOverlayRemoval(action: OverlayRemovalAction) {
        completion?.let(handler::removeCallbacks)
        watchdog?.let(handler::removeCallbacks)
        completion = null
        watchdog = null
        countdownView = null
        removalPolicy.request(action)
        attemptOverlayRemoval()
    }

    private fun attemptOverlayRemoval() {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        val view = overlay
        if (view == null || !view.isAttachedToWindow) {
            confirmOverlayRemoved()
            return
        }

        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // Attachment state below is the postcondition; an exception alone is not success.
        }
        if (!view.isAttachedToWindow) {
            confirmOverlayRemoved()
            return
        }

        when (removalPolicy.failedAttempt()) {
            OverlayRemovalDecision.RETRY -> {
                removalRetry = Runnable { attemptOverlayRemoval() }
                    .also { handler.postDelayed(it, REMOVAL_RETRY_INTERVAL_MS) }
            }
            OverlayRemovalDecision.DISABLE_SERVICE -> {
                // Retain the attached view and manager references; never release a pending action.
                // Android service teardown is the final platform-owned removal path.
                disableSelf()
            }
        }
    }

    private fun confirmOverlayRemoved() {
        removalRetry?.let(handler::removeCallbacks)
        removalRetry = null
        overlay = null
        windowManager = null
        val action = removalPolicy.confirmedDetached()
        if (action != OverlayRemovalAction.PRESERVE_REPORT) mainActivityReturnObserved = false
        when (action) {
            OverlayRemovalAction.BYPASS -> {
                entryGate.cancel()
                publishGateState()
                Observation.clear()
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
                    !entryGate.complete(SystemClock.elapsedRealtime(), activeTicket)
                ) entryGate.cancel()
                publishGateState()
            }
            OverlayRemovalAction.HOME -> {
                val activeTicket = ticket
                if (activeTicket == null || !entryGate.bypass(activeTicket)) entryGate.cancel()
                publishGateState()
                Observation.clear()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            null -> Unit
        }
    }

    private fun cancelAndBypass() {
        requestOverlayRemoval(OverlayRemovalAction.BYPASS)
    }

    private fun failOpen() {
        cancelAndBypass()
    }

    private fun resetOutside() {
        requestOverlayRemoval(OverlayRemovalAction.RESET_OUTSIDE)
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
