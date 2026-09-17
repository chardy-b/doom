package com.chardy.doom

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
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
}
