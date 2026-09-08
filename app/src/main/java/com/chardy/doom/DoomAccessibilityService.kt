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
            instance?.disableSelf()
            Observation.connected = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Observation.load(this)
        if (!Observation.consent) { disableSelf(); return }
        Observation.connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Observation.consent || !Observation.connected || event?.packageName?.toString() != "com.instagram.android") return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.instagram.android") return
        // Traverse a bounded current tree. Never access text/contentDescription or retain nodes/IDs.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var nodes = 0
        var ids = 0
        var clickable = 0
        var truncated = false
        while (queue.isNotEmpty() && nodes < 128) {
            val (node, depth) = queue.removeFirst()
            nodes++
            if (node.viewIdResourceName != null) ids++
            if (node.isClickable) clickable++
            if (depth < 8) {
                val limit = minOf(node.childCount, 128 - nodes - queue.size)
                if (limit < node.childCount) truncated = true
                repeat(limit.coerceAtLeast(0)) { index -> node.getChild(index)?.let { queue.add(it to depth + 1) } }
            } else if (node.childCount > 0) truncated = true
        }
        Observation.counts = Observation.Counts(nodes, ids, clickable, truncated || queue.isNotEmpty())
    }

    override fun onInterrupt() { Observation.clear() }
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
