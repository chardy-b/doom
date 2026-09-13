package com.chardy.doom

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramMessagesRouterTest {
    private val actionable = DirectTabActionability(
        visibleToUser = true,
        enabled = true,
        clickable = true,
    )

    @Test fun exactIdSuccessReadsStateInOrderClicksOnceAndRecyclesMatch() {
        val root = AccessibilityNodeInfo.obtain()
        val node = AccessibilityNodeInfo.obtain()
        val calls = mutableListOf<String>()
        var queriedId: String? = null
        var clickCalls = 0
        var recycled = 0
        try {
            assertEquals(
                MessagesRouteResult.CLICKED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, id -> queriedId = id; calls += "find"; listOf(node) },
                    readSelected = { calls += "selected"; false },
                    readActionability = { calls += "actionability"; actionable },
                    click = { calls += "click"; clickCalls++; true },
                    recycle = { calls += "recycle"; recycled++ },
                ),
            )
            assertEquals(INSTAGRAM_DIRECT_TAB_ID, queriedId)
            assertEquals(listOf("find", "selected", "actionability", "click", "recycle"), calls)
            assertEquals(1, clickCalls)
            assertEquals(1, recycled)
        } finally {
            root.recycle()
        }
    }

    @Test fun selectedMatchReadsSelectedOnlyAndCannotClick() {
        val root = AccessibilityNodeInfo.obtain()
        val node = AccessibilityNodeInfo.obtain()
        var selectedCalls = 0
        var actionabilityCalls = 0
        var clickCalls = 0
        var recycled = 0
        try {
            assertEquals(
                MessagesRouteResult.ALREADY_SELECTED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, _ -> listOf(node) },
                    readSelected = { selectedCalls++; true },
                    readActionability = { actionabilityCalls++; actionable },
                    click = { clickCalls++; true },
                    recycle = { recycled++ },
                ),
            )
            assertEquals(1, selectedCalls)
            assertEquals(0, actionabilityCalls)
            assertEquals(0, clickCalls)
            assertEquals(1, recycled)
        } finally {
            root.recycle()
        }
    }

    @Test fun zeroMatchReadsNoStateCannotClickAndRecyclesNothing() {
        val root = AccessibilityNodeInfo.obtain()
        var selectedCalls = 0
        var actionabilityCalls = 0
        var clickCalls = 0
        var recycled = 0
        try {
            assertEquals(
                MessagesRouteResult.FAILED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, _ -> emptyList() },
                    readSelected = { selectedCalls++; false },
                    readActionability = { actionabilityCalls++; actionable },
                    click = { clickCalls++; true },
                    recycle = { recycled++ },
                ),
            )
            assertEquals(0, selectedCalls)
            assertEquals(0, actionabilityCalls)
            assertEquals(0, clickCalls)
            assertEquals(0, recycled)
        } finally {
            root.recycle()
        }
    }

    @Test fun multipleDistinctMatchesReadNoStateCannotClickAndRecycleEachOnce() {
        val root = AccessibilityNodeInfo.obtain()
        val first = AccessibilityNodeInfo.obtain()
        val second = AccessibilityNodeInfo.obtain()
        var selectedCalls = 0
        var actionabilityCalls = 0
        var clickCalls = 0
        val recycled = mutableListOf<AccessibilityNodeInfo>()
        try {
            assertEquals(
                MessagesRouteResult.FAILED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, _ -> listOf(first, second) },
                    readSelected = { selectedCalls++; false },
                    readActionability = { actionabilityCalls++; actionable },
                    click = { clickCalls++; true },
                    recycle = { recycled += it },
                ),
            )
            assertEquals(0, selectedCalls)
            assertEquals(0, actionabilityCalls)
            assertEquals(0, clickCalls)
            assertEquals(listOf(first, second), recycled)
        } finally {
            root.recycle()
        }
    }

    @Test fun duplicateIdentitiesRecycleEachDistinctMatchOnce() {
        val root = AccessibilityNodeInfo.obtain()
        val first = AccessibilityNodeInfo.obtain()
        val second = AccessibilityNodeInfo.obtain()
        val recycled = mutableListOf<AccessibilityNodeInfo>()
        try {
            assertEquals(
                MessagesRouteResult.FAILED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, _ -> listOf(first, first, second) },
                    recycle = { recycled += it },
                ),
            )
            assertEquals(listOf(first, second), recycled)
        } finally {
            root.recycle()
        }
    }

    @Test fun everyNonActionableMatchReadsActionabilityButCannotClick() {
        listOf(
            actionable.copy(visibleToUser = false),
            actionable.copy(enabled = false),
            actionable.copy(clickable = false),
        ).forEach { state ->
            val root = AccessibilityNodeInfo.obtain()
            val node = AccessibilityNodeInfo.obtain()
            var selectedCalls = 0
            var actionabilityCalls = 0
            var clickCalls = 0
            var recycled = 0
            try {
                assertEquals(
                    MessagesRouteResult.FAILED,
                    InstagramMessagesRouter.route(
                        root,
                        findNodes = { _, _ -> listOf(node) },
                        readSelected = { selectedCalls++; false },
                        readActionability = { actionabilityCalls++; state },
                        click = { clickCalls++; true },
                        recycle = { recycled++ },
                    ),
                )
                assertEquals(1, selectedCalls)
                assertEquals(1, actionabilityCalls)
                assertEquals(0, clickCalls)
                assertEquals(1, recycled)
            } finally {
                root.recycle()
            }
        }
    }

    @Test fun falseOrThrowingClickFailsClosedAfterOneClickAndRecyclesMatch() {
        listOf<(AccessibilityNodeInfo) -> Boolean>({ false }, { throw IllegalStateException("stale") })
            .forEach { click ->
                val root = AccessibilityNodeInfo.obtain()
                val node = AccessibilityNodeInfo.obtain()
                var clickCalls = 0
                var recycled = 0
                try {
                    assertEquals(
                        MessagesRouteResult.FAILED,
                        InstagramMessagesRouter.route(
                            root,
                            findNodes = { _, _ -> listOf(node) },
                            readSelected = { false },
                            readActionability = { actionable },
                            click = { clickCalls++; click(it) },
                            recycle = { recycled++ },
                        ),
                    )
                    assertEquals(1, clickCalls)
                    assertEquals(1, recycled)
                } finally {
                    root.recycle()
                }
            }
    }

    @Test fun defaultAdapterOnUnattachedObtainedNodeFailsWithoutException() {
        val root = AccessibilityNodeInfo.obtain()
        val node = AccessibilityNodeInfo.obtain()
        try {
            assertEquals(
                MessagesRouteResult.FAILED,
                InstagramMessagesRouter.route(
                    root,
                    findNodes = { _, _ -> listOf(node) },
                ),
            )
        } finally {
            root.recycle()
        }
    }
}
