package com.chardyb.doom.fixturegate

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque

/** Fixed, consented test-package support only. No event text, descriptions, logs, or persistence. */
class FixtureGateService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = GatePolicy()
    private var overlay: View? = null
    private var targetWindowId = -1
    private var scheduled: GatePolicy.Ticket? = null
    private var completion: Runnable? = null
    private var registered = false
    private val overlayWindowIds = linkedSetOf<Int>()
    private lateinit var preferences: SharedPreferences
    private val manager get() = getSystemService(WindowManager::class.java)
    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!consented()) { stopGate(); disableSelf() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopGate() }
    }
    // Window metadata is also checked while idle/granted so a session cannot survive Home.
    // The watchdog catches dropped events and checks only the confirmed fixture tree.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!connected) return
            reconcile()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onServiceConnected() {
        preferences = getSharedPreferences(ConsentActivity.CONSENT_FILE, MODE_PRIVATE)
        if (!consented()) { connected = false; disableSelf(); return }
        preferences.registerOnSharedPreferenceChangeListener(consentListener)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        registered = true
        connected = true
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected) return
        // Only package/window/type metadata is read. Never event.source/text/contentDescription.
        if (event.packageName?.toString() == TARGET && event.windowId >= 0) {
            targetWindowId = event.windowId
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val presentOverlay = windows.any {
                it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
            if (presentOverlay && event.packageName?.toString() == packageName) rememberOverlay(event.windowId)
            val ownOverlay = event.packageName?.toString() == packageName &&
                (presentOverlay || event.windowId in overlayWindowIds)
            if (!ownOverlay) { stopGate(); return }
        }
        reconcile()
    }

    private fun consented() = ::preferences.isInitialized &&
        preferences.getBoolean(ConsentActivity.CONSENT_KEY, false)

    private fun fixtureWindow(): AccessibilityWindowInfo? {
        if (!consented() || !getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        // Do not use rootInActiveWindow: an accessibility overlay can become the active window.
        val focused = windows.firstOrNull { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        return focused?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id == targetWindowId }
    }

    private data class Scan(val surface: GatePolicy.Surface, val dm: AccessibilityNodeInfo?)

    @Suppress("DEPRECATION")
    private fun scan(root: AccessibilityNodeInfo, windowId: Int): Scan {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        var feed = false
        var dmMarker = false
        var unknown = false
        var dmButton: AccessibilityNodeInfo? = null
        var invalid = false
        try {
            while (queue.isNotEmpty() && count < MAX_NODES) {
                val node = queue.removeFirst()
                try {
                    count++
                    if (node.packageName?.toString() != TARGET || node.windowId != windowId) {
                        invalid = true
                        continue // Never descend into a different package.
                    }
                    if (node.isVisibleToUser) {
                        when (node.viewIdResourceName) {
                            "$TARGET:id/fixture_feed_marker" -> feed = true
                            "$TARGET:id/fixture_dm_marker" -> dmMarker = true
                            "$TARGET:id/fixture_unknown_marker" -> unknown = true
                            "$TARGET:id/fixture_dm_button" -> if (node.isClickable && node.isEnabled) {
                                dmButton?.recycle()
                                dmButton = AccessibilityNodeInfo.obtain(node)
                            }
                        }
                    }
                    val children = node.childCount
                    if (children > MAX_NODES - count - queue.size) { invalid = true; break }
                    for (index in 0 until children) node.getChild(index)?.let(queue::addLast)
                } finally { node.recycle() }
            }
            if (queue.isNotEmpty()) invalid = true
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
        val surface = when {
            invalid -> GatePolicy.Surface.UNKNOWN
            dmMarker -> GatePolicy.Surface.DM
            unknown -> GatePolicy.Surface.UNKNOWN
            feed && dmButton != null -> GatePolicy.Surface.FEED
            else -> GatePolicy.Surface.UNKNOWN
        }
        return Scan(surface, dmButton)
    }

    @Suppress("DEPRECATION")
    private fun inspect(): Scan? {
        val window = fixtureWindow() ?: return null
        // Root access occurs only for a focused application window confirmed by fixture metadata.
        val root = window.root ?: return null
        if (root.packageName?.toString() != TARGET || root.windowId != targetWindowId) {
            root.recycle()
            return null
        }
        return scan(root, window.id)
    }

    @Suppress("DEPRECATION")
    private fun reconcile() {
        val scan = try { inspect() } catch (_: RuntimeException) { null }
        if (scan == null) { stopGate(); return }
        scan.dm?.recycle()
        val ticket = policy.observe(scan.surface, SystemClock.elapsedRealtime())
        if (ticket == null) { removeOverlay(); cancelCallback(); return }
        if (overlay == null && !showOverlay()) { stopGate(); return }
        if (scheduled != ticket) {
            cancelCallback()
            scheduled = ticket
            val callback = Runnable {
                // Revalidate foreground, consent and actual surface before giving any credit.
                reconcile()
                if (policy.complete(ticket, SystemClock.elapsedRealtime())) {
                    removeOverlay()
                    cancelCallback()
                }
            }
            completion = callback
            handler.postDelayed(callback, (ticket.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun showOverlay(): Boolean {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setBackgroundColor(android.graphics.Color.rgb(244, 240, 229))
        }
        panel.addView(TextView(this).apply {
            id = R.id.fixture_gate_marker; setText(R.string.gate); textSize = 26f
            setTextColor(android.graphics.Color.BLACK)
        })
        panel.addView(TextView(this).apply { setText(R.string.waiting); setTextColor(android.graphics.Color.BLACK) })
        panel.addView(Button(this).apply {
            id = R.id.fixture_escape; setText(R.string.messages); setOnClickListener { messages() }
        })
        panel.addView(Button(this).apply {
            id = R.id.fixture_leave; setText(R.string.leave)
            setOnClickListener { leave() }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply { title = "TEST FIXTURE accessibility overlay" }
        return try { manager.addView(panel, params); overlay = panel; true }
        catch (_: RuntimeException) { false }
    }

    private fun leave() {
        policy.beginNavigation()
        cancelCallback()
        removeOverlay()
        val homeRequested = try { performGlobalAction(GLOBAL_ACTION_HOME) }
        catch (_: RuntimeException) { false }
        if (!homeRequested) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {
                // Keep navigation suppression and the window removed if Home is unavailable.
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun messages() {
        policy.beginNavigation()
        cancelCallback()
        removeOverlay() // Mandatory ordering: remove the real window before ACTION_CLICK.
        val scan = try { inspect() } catch (_: RuntimeException) { null }
        val node = scan?.dm
        try {
            if (scan?.surface == GatePolicy.Surface.FEED && node != null &&
                node.refresh() && node.packageName?.toString() == TARGET &&
                node.windowId == targetWindowId && fixtureWindow()?.id == targetWindowId &&
                node.viewIdResourceName == "$TARGET:id/fixture_dm_button" &&
                node.isVisibleToUser && node.isEnabled && node.isClickable) {
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) stopGate()
            } else stopGate()
        } finally { node?.recycle() }
    }

    private fun cancelCallback() {
        completion?.let(handler::removeCallbacks)
        completion = null
        scheduled = null
    }

    private fun removeOverlay() {
        if (overlay != null) windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .forEach { rememberOverlay(it.id) }
        overlay?.let { try { manager.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        overlay = null
    }

    private fun rememberOverlay(id: Int) {
        overlayWindowIds.add(id)
        if (overlayWindowIds.size > 16) overlayWindowIds.remove(overlayWindowIds.first())
    }

    private fun stopGate() {
        targetWindowId = -1
        policy.cancel()
        cancelCallback()
        removeOverlay()
    }

    override fun onInterrupt() { stopGate() }
    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }
    private fun shutdown() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        stopGate()
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(consentListener)
        if (registered) { unregisterReceiver(screenReceiver); registered = false }
    }

    companion object {
        const val TARGET = "com.chardyb.doom.testfixture"
        private const val MAX_NODES = 128
        private const val WATCHDOG_MS = 200L
        @Volatile var connected = false
            private set
    }
}
