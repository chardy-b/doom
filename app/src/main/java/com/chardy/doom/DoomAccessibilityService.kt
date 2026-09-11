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
            // A delayed Instagram event can arrive after Doom is foreground again. Preserve
            // the valid report; a Doom root cannot be an Instagram sample.
            if (root.packageName?.toString() == applicationContext.packageName) return
            // Any other foreign root is unavailable, never a valid Instagram sample.
            if (root.packageName?.toString() != "com.instagram.android") {
                Observation.record(null)
                return
            }
            val builder = SanitizedStructuralReport.Builder()
            var nodes = 0
            while (queue.isNotEmpty() && nodes < SanitizedStructuralReport.MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                try {
                    nodes++ // Skipped foreign nodes still consume the traversal budget.
                    if (node.packageName?.toString() != "com.instagram.android") {
                        builder.markTruncated()
                        continue
                    }
                    val children = node.childCount
                    // Only these metadata getters are allowed. Sanitize before retaining any value.
                    builder.add(depth, node.viewIdResourceName, node.className, children,
                        node.isClickable, node.isScrollable, node.isEditable, node.isSelected, node.isChecked)
                    if (depth < SanitizedStructuralReport.MAX_DEPTH) {
                        val limit = minOf(children, SanitizedStructuralReport.MAX_NODES - nodes - queue.size)
                        if (limit < children) builder.markTruncated()
                        repeat(limit.coerceAtLeast(0)) { index ->
                            val child = node.getChild(index)
                            if (child == null) builder.markTruncated() else queue.add(child to depth + 1)
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
