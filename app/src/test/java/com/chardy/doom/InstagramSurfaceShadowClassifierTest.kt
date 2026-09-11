package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramSurfaceShadowClassifierTest {
    private fun report(
        vararg rows: Triple<String, Boolean, Boolean>,
        truncated: Boolean = false,
        editableIds: Set<String> = emptySet()
    ): SanitizedStructuralReport {
        val b = SanitizedStructuralReport.Builder()
        rows.forEach { (id, selected, scrollable) ->
            b.add(0, "com.instagram.android:id/$id", "android.view.View", 0, false,
                scrollable, id in editableIds, selected, false)
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
