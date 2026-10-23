package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSurfaceShadowClassifierTest {
    private fun report(
        vararg rows: Triple<String, Boolean, Boolean>,
        truncated: Boolean = false,
        editableIds: Set<String> = emptySet(),
        hiddenIds: Set<String> = emptySet(),
        visibleIds: Set<String> = emptySet(),
        visibilityErrors: Set<String> = emptySet(),
    ): SanitizedStructuralReport {
        val b = SanitizedStructuralReport.Builder()
        rows.forEachIndexed { index, (id, selected, scrollable) ->
            val selectedBit = if (selected) 1L shl StructuralBooleanField.SELECTED.ordinal else 0L
            val scrollableBit = if (scrollable) 1L shl StructuralBooleanField.SCROLLABLE.ordinal else 0L
            val editableBit = if (id in editableIds) 1L shl StructuralBooleanField.EDITABLE.ordinal else 0L
            val visibilityBit = 1L shl StructuralBooleanField.VISIBLE_TO_USER.ordinal
            b.add(StructuralNodeMetadata(
                position = StructuralNodePosition(index = index, bfsOrdinal = index),
                resourceId = "com.instagram.android:id/$id",
                className = "View",
                flags = StructuralBooleanMasks(
                    known = (1L shl StructuralBooleanField.SELECTED.ordinal) or
                        (1L shl StructuralBooleanField.SCROLLABLE.ordinal) or
                        (1L shl StructuralBooleanField.EDITABLE.ordinal) or
                        (if (id in hiddenIds || id in visibleIds || id in visibilityErrors) visibilityBit else 0L),
                    value = selectedBit or scrollableBit or editableBit or (if (id in visibleIds) visibilityBit else 0L),
                    error = if (id in visibilityErrors) visibilityBit else 0L,
                ),
            ))
        }
        if (truncated) b.markTruncated()
        return b.build()!!
    }

    @Test fun builderPathClassifiesRealSignals() {
        assertEquals(InstagramSurface.FEED, InstagramSurfaceShadowClassifier.classify(report(Triple("feed_tab", true, false), Triple("row_feed_media", false, false))))
        assertEquals(InstagramSurface.REELS, InstagramSurfaceShadowClassifier.classify(report(Triple("clips_tab", true, false), Triple("clips_viewer_view_pager", false, true))))
        assertEquals(InstagramSurface.STORIES, InstagramSurfaceShadowClassifier.classify(report(Triple("reel_viewer_root", false, false), Triple("reel_viewer_header", false, false))))
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(report(Triple("message_list", false, true), Triple("message_composer_bar", false, false))))
    }

    @Test fun hiddenMessagingDoesNotOverrideVisibleReels() {
        val candidate = report(Triple("clips_tab", true, false), Triple("clips_viewer_view_pager", false, true),
            Triple("message_list", false, true), hiddenIds = setOf("message_list"))
        assertEquals(InstagramSurface.REELS, InstagramSurfaceShadowClassifier.classify(candidate))
        assertTrue(candidate.shadowInput.resourceIds.contains("com.instagram.android:id/message_list"))
    }

    @Test fun hiddenMessagingInTruncatedReportLeavesUnknownRatherThanMessaging() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(report(
            Triple("message_list", false, true), truncated = true, hiddenIds = setOf("message_list"))))
    }

    @Test fun visibleUnknownAndUnreadableMessagingStillWinTruncatedReports() {
        listOf(
            report(Triple("message_list", false, true), truncated = true, visibleIds = setOf("message_list")),
            report(Triple("message_list", false, true), truncated = true),
            report(Triple("message_list", false, true), truncated = true, visibilityErrors = setOf("message_list")),
        ).forEach { assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(it)) }
    }

    @Test fun hiddenSelectedReelsCannotAuthorizeMessagingReadmission() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(report(
            Triple("clips_tab", true, false), Triple("clips_viewer_view_pager", false, true),
            hiddenIds = setOf("clips_tab", "clips_viewer_view_pager"))))
    }

    @Test fun visibleDuplicateMessagingStillWinsOverHiddenInstance() {
        val builder = SanitizedStructuralReport.Builder()
        val bit = 1L shl StructuralBooleanField.VISIBLE_TO_USER.ordinal
        listOf(0L, bit).forEachIndexed { index, value -> builder.add(StructuralNodeMetadata(
            position = StructuralNodePosition(index = index, bfsOrdinal = index),
            resourceId = "com.instagram.android:id/message_list", className = "View",
            flags = StructuralBooleanMasks(known = bit, value = value),
        )) }
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(builder.build()))
    }

    @Test fun messagingVisibilitySurvivesTextByteBoundaryWithoutExportingOmittedRows() {
        val bit = 1L shl StructuralBooleanField.VISIBLE_TO_USER.ordinal
        listOf(
            StructuralBooleanMasks(known = bit),
            StructuralBooleanMasks(known = bit, value = bit),
            StructuralBooleanMasks(),
            StructuralBooleanMasks(known = bit, error = bit),
        ).forEachIndexed { mode, flags ->
            val builder = SanitizedStructuralReport.Builder()
            repeat(64) { index -> builder.add(StructuralNodeMetadata(
                position = StructuralNodePosition(index = index, bfsOrdinal = index),
                resourceId = "com.instagram.android:id/clips_tab", className = "View",
            )) }
            builder.add(StructuralNodeMetadata(
                position = StructuralNodePosition(index = 64, bfsOrdinal = 64),
                resourceId = "com.instagram.android:id/message_list", className = "View", flags = flags,
            ))
            val candidate = requireNotNull(builder.build())
            assertTrue(candidate.truncated)
            assertFalse(candidate.text.contains("message_list"))
            assertEquals(if (mode == 0) InstagramSurface.UNKNOWN else InstagramSurface.MESSAGING,
                InstagramSurfaceShadowClassifier.classify(candidate))
        }
    }

    @Test fun inboxReportClassifiesAsMessaging() {
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(
            report(Triple("inbox_refreshable_thread_list_recyclerview", false, true))
        ))
    }

    @Test fun notificationClickDmThreadClassifiesAsMessaging() {
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(
            report(
                Triple("thread_fragment_container", false, false),
                Triple("message_list", false, true),
                Triple("message_composer_bar", false, false),
                Triple("row_thread_composer_edittext", false, false),
                editableIds = setOf("row_thread_composer_edittext")
            )
        ))
    }

    @Test fun truncatedNonMessagingReportFailsOpenAsUnknown() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(
            report(
                Triple("feed_tab", true, false),
                Triple("row_feed_media", false, false),
                truncated = true
            )
        ))
    }

    @Test fun messagingWinsMixedAndTruncatedReports() {
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(report(Triple("feed_tab", true, false), Triple("row_feed_media", false, false), Triple("thread_fragment_container", false, false), truncated = true)))
    }

    @Test fun reportedTruncatedThreadSignatureKeepsMessagingPrecedence() {
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(
            report(
                Triple("thread_fragment_container", false, false),
                Triple("message_list", false, true),
                Triple("message_composer_bar", false, false),
                truncated = true,
            )
        ))
    }

    @Test fun unselectedOrUncorroboratedAndAmbiguousReportsAreUnknown() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(report(Triple("feed_tab", false, false), Triple("row_feed_media", false, false))))
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(report(Triple("clips_tab", true, false))))
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(report(Triple("feed_tab", true, false), Triple("clips_tab", true, false), Triple("row_feed_media", false, false), Triple("clips_viewer_view_pager", false, true))))
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(null))
    }

    @Test fun selectedFeedWithGenericMediaOptionButtonIsUnknown() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(
            report(Triple("feed_tab", true, false), Triple("media_option_button", false, false))
        ))
    }

    @Test fun selectedReelsWithNonScrollablePagerIsUnknown() {
        assertEquals(InstagramSurface.UNKNOWN, InstagramSurfaceShadowClassifier.classify(
            report(Triple("clips_tab", true, false), Triple("clips_viewer_view_pager", false, false))
        ))
    }
}
