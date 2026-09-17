package com.chardy.doom

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** API-ceiling and scalar-copy checks. This fixture contains no private or user-authored content. */
class StructuralMetadataApiTest {
    @Test fun lowerApiCeilingEmitsUnavailableLaterFieldsWithoutRetainingTheNode() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            viewIdResourceName = "com.instagram.android:id/feed_tab"
            className = "android.widget.Button"
            setBoundsInScreen(Rect(10, 20, 210, 220))
            isClickable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        try {
            val metadata = AndroidStructuralMetadataReader(
                requestedApiCeiling = 26,
                nowMs = { 1_100L },
            ).read(node, StructuralNodePosition(), StructuralCaptureContext.synthetic(startedElapsedMs = 1_000L))

            assertEquals("com.instagram.android:id/feed_tab", metadata.resourceId)
            assertEquals("Button", metadata.className)
            assertTrue(metadata.screenBounds is MetadataValue.Present)
            assertTrue(metadata.windowBounds is MetadataValue.Unavailable)
            assertEquals(MetadataUnavailableReason.API,
                (metadata.windowBounds as MetadataValue.Unavailable).reason)
            assertEquals(MetadataUnavailableReason.API,
                (metadata.uniqueId as MetadataValue.Unavailable).reason)
            assertEquals(MetadataUnavailableReason.ABSENT,
                (metadata.collection as MetadataValue.Unavailable).reason)
            assertTrue(metadata.actions.any { it.name == "CLICK" })
            assertTrue(metadata.elapsedOffsetMs is MetadataValue.Present)
        } finally {
            node.recycle()
        }
    }

    @Test fun uniqueIdIsOnlyAdmittedWhenItMatchesAnAcceptedResourceToken() {
        if (Build.VERSION.SDK_INT < 33) return
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            viewIdResourceName = "com.instagram.android:id/feed_tab"
            className = "android.view.View"
            uniqueId = "free-form-public-value"
        }
        try {
            val metadata = AndroidStructuralMetadataReader(35).read(
                node, StructuralNodePosition(), StructuralCaptureContext.synthetic(),
            )
            assertEquals(MetadataUnavailableReason.FREE_FORM,
                (metadata.uniqueId as MetadataValue.Unavailable).reason)
        } finally {
            node.recycle()
        }
    }

    @Test fun clockRollbackInvalidatesTheOffsetWhileTimeoutIsTypedAndBounded() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            viewIdResourceName = "com.instagram.android:id/clock_test"
        }
        try {
            val rollback = AndroidStructuralMetadataReader(nowMs = { 999L }).read(
                node, StructuralNodePosition(), StructuralCaptureContext.synthetic(startedElapsedMs = 1_000L),
            )
            assertEquals(MetadataUnavailableReason.CLOCK_ROLLBACK,
                (rollback.elapsedOffsetMs as MetadataValue.Unavailable).reason)
            val timeout = AndroidStructuralMetadataReader(nowMs = { 11_001L }).read(
                node, StructuralNodePosition(), StructuralCaptureContext.synthetic(startedElapsedMs = 1_000L),
            )
            assertEquals(MetadataUnavailableReason.TIMEOUT,
                (timeout.elapsedOffsetMs as MetadataValue.Unavailable).reason)
        } finally {
            node.recycle()
        }
    }

    @Test fun intermediateApiCeilingsKeepEachGetterBoundaryTyped() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            viewIdResourceName = "com.instagram.android:id/api_boundary"
            setBoundsInScreen(Rect(0, 0, 10, 10))
        }
        try {
            listOf(27, 32, 33, 34).forEach { requested ->
                val ceiling = minOf(requested, Build.VERSION.SDK_INT)
                val metadata = AndroidStructuralMetadataReader(ceiling).read(
                    node, StructuralNodePosition(), StructuralCaptureContext.synthetic(),
                )
                if (ceiling >= 34) assertTrue(metadata.windowBounds is MetadataValue.Present)
                else assertEquals(
                    MetadataUnavailableReason.API,
                    (metadata.windowBounds as MetadataValue.Unavailable).reason,
                )
                if (ceiling >= 33) assertEquals(
                    MetadataUnavailableReason.ABSENT,
                    (metadata.uniqueId as MetadataValue.Unavailable).reason,
                ) else assertEquals(
                    MetadataUnavailableReason.API,
                    (metadata.uniqueId as MetadataValue.Unavailable).reason,
                )
                val headingBit = 1L shl StructuralBooleanField.HEADING.ordinal
                val granularBit = 1L shl StructuralBooleanField.GRANULAR_SCROLLING_SUPPORTED.ordinal
                assertEquals(ceiling >= 28, metadata.flags.known and headingBit != 0L)
                assertEquals(ceiling >= 35, metadata.flags.known and granularBit != 0L)
            }
        } finally {
            node.recycle()
        }
    }

    @Test fun customActionLabelAndExtrasAreNotSerializedOrRead() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            viewIdResourceName = "com.instagram.android:id/canary"
            addAction(AccessibilityNodeInfo.AccessibilityAction(0x7fff1234, "CANARY_ACTION_LABEL"))
            extras.putString("canary_key", "CANARY_EXTRA_VALUE")
        }
        try {
            val metadata = AndroidStructuralMetadataReader(35).read(
                node, StructuralNodePosition(), StructuralCaptureContext.synthetic(),
            )
            val report = SanitizedStructuralReport.Builder().apply { add(metadata) }.build()!!
            assertTrue(report.text.contains("UNKNOWN"))
            assertFalse(report.text.contains("CANARY_ACTION_LABEL"))
            assertFalse(report.text.contains("CANARY_EXTRA_VALUE"))
            assertFalse(report.text.contains("canary_key"))
        } finally {
            node.recycle()
        }
    }
}
