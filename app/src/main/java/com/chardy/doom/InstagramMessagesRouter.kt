package com.chardy.doom

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

internal const val INSTAGRAM_DIRECT_TAB_ID = "com.instagram.android:id/direct_tab"

internal data class DirectTabSnapshot(
    val selected: Boolean,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
)

internal data class DirectTabActionability(
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
)

internal enum class DirectTabDecision { REJECT, ALREADY_SELECTED, CLICK }

/** Pure policy for the one user-consented Instagram Messages-tab exception. */
internal object InstagramMessagesRoutingPolicy {
    fun decide(matches: List<DirectTabSnapshot>): DirectTabDecision {
        if (matches.size != 1) return DirectTabDecision.REJECT
        val match = matches.single()
        if (match.selected) return DirectTabDecision.ALREADY_SELECTED
        return if (match.visibleToUser && match.enabled && match.clickable) {
            DirectTabDecision.CLICK
        } else {
            DirectTabDecision.REJECT
        }
    }
}

internal enum class MessagesRouteResult { FAILED, ALREADY_SELECTED, CLICKED }

/** Exact-ID Android adapter. The caller owns and recycles the root. */
internal object InstagramMessagesRouter {
    fun route(
        root: AccessibilityNodeInfo,
        findNodes: (AccessibilityNodeInfo, String) -> List<AccessibilityNodeInfo> =
            { node, viewId -> node.findAccessibilityNodeInfosByViewId(viewId) },
        readSelected: (AccessibilityNodeInfo) -> Boolean = { node -> node.isSelected },
        readActionability: (AccessibilityNodeInfo) -> DirectTabActionability = { node ->
            DirectTabActionability(
                visibleToUser = node.isVisibleToUser,
                enabled = node.isEnabled,
                clickable = node.isClickable,
            )
        },
        click: (AccessibilityNodeInfo) -> Boolean = { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        },
        recycle: (AccessibilityNodeInfo) -> Unit = { node -> node.recycle() },
    ): MessagesRouteResult {
        var matches: List<AccessibilityNodeInfo> = emptyList()
        return try {
            matches = findNodes(root, INSTAGRAM_DIRECT_TAB_ID)
            var match: AccessibilityNodeInfo? = null
            val decision = when {
                matches.size != 1 -> DirectTabDecision.REJECT
                else -> {
                    match = matches.single()
                    if (readSelected(requireNotNull(match))) {
                        DirectTabDecision.ALREADY_SELECTED
                    } else {
                        val state = readActionability(requireNotNull(match))
                        InstagramMessagesRoutingPolicy.decide(listOf(DirectTabSnapshot(
                            selected = false,
                            visibleToUser = state.visibleToUser,
                            enabled = state.enabled,
                            clickable = state.clickable,
                        )))
                    }
                }
            }
            when (decision) {
                DirectTabDecision.REJECT -> MessagesRouteResult.FAILED
                DirectTabDecision.ALREADY_SELECTED -> MessagesRouteResult.ALREADY_SELECTED
                DirectTabDecision.CLICK -> if (click(requireNotNull(match))) {
                    MessagesRouteResult.CLICKED
                } else {
                    MessagesRouteResult.FAILED
                }
            }
        } catch (_: RuntimeException) {
            MessagesRouteResult.FAILED
        } finally {
            recycleDistinct(matches, root, recycle)
        }
    }

    private fun recycleDistinct(
        nodes: List<AccessibilityNodeInfo>,
        root: AccessibilityNodeInfo,
        recycle: (AccessibilityNodeInfo) -> Unit,
    ) {
        val seen = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        nodes.forEach { node ->
            if (node !== root && seen.add(node)) {
                try {
                    recycle(node)
                } catch (_: RuntimeException) {
                    // Continue releasing every other returned node.
                }
            }
        }
    }
}
