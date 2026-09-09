package com.chardy.doom

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** No overlays or live navigation: Instagram remains accessible on every code path. */
class DoomAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: DoomAccessibilityService? = null
        fun disableObservation() {
            Observation.connected = false
            Observation.clear()
            instance?.disableSelf()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Observation.connected = false
        Observation.clear()
        Observation.load(this)
        if (!Observation.consent) { disableSelf(); return }
        Observation.connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Observation.consent || !Observation.connected || event?.packageName?.toString() != "com.instagram.android") return
        try {
            val root = rootInActiveWindow
            if (root == null) { Observation.record(null); return }
            collect(root)
        } catch (_: RuntimeException) {
            // Stale/inaccessible trees are unavailable samples, never a reason to obstruct Instagram.
            Observation.record(null)
        }
    }

    @Suppress("DEPRECATION") // Release transient nodes on older supported Android versions too.
    private fun collect(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        try {
            // Non-Instagram events are ignored above, preserving normal return-to-Doom labeling.
            // A delayed Instagram event can see another app's root: invalidate the stale current sample.
            if (root.packageName?.toString() != "com.instagram.android") {
                Observation.record(null)
                return
            }
            val builder = StructuralFingerprint.Builder()
            var nodes = 0
            while (queue.isNotEmpty() && nodes < StructuralFingerprint.MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                try {
                    val children = node.childCount
                    // Presence only: values never enter the hasher, vector, UI, or application state.
                    // Never access node text/contentDescription. Hash this feature before continuing.
                    builder.add(depth, node.viewIdResourceName != null, node.className != null, node.isClickable, children)
                    nodes++
                    if (depth < StructuralFingerprint.MAX_DEPTH) {
                        val limit = minOf(children, StructuralFingerprint.MAX_NODES - nodes - queue.size)
                        repeat(limit.coerceAtLeast(0)) { index ->
                            node.getChild(index)?.let { queue.add(it to depth + 1) }
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
            Observation.record(builder.build())
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().first.recycle()
        }
    }

    override fun onInterrupt() {
        Observation.connected = false
        Observation.clear()
    }
    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (instance === this) instance = null
        Observation.connected = false
        Observation.clear()
    }
}
