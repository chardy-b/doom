package com.chardy.doom

/**
 * Conservative, non-blocking prediction from sanitized structural tokens.
 * UNKNOWN is intentional: this classifier never authorizes an action or gate.
 */
enum class InstagramSurface { FEED, REELS, STORIES, MESSAGING, UNKNOWN }

object InstagramSurfaceShadowClassifier {
    private val messaging = setOf(
        "inbox_refreshable_thread_list_recyclerview", "thread_fragment_container", "message_list",
        "message_composer_bar", "row_thread_composer_edittext"
    )
    private const val feedTab = "feed_tab"
    private const val clipsTab = "clips_tab"
    private val reelsContent = setOf("swipeable_tab_view_pager", "clips_viewer_view_pager")
    private val storyControls = setOf("reel_viewer_header", "reel_viewer_close_button", "reel_viewer_progress_bar")

    internal fun classify(input: ShadowClassificationInput): InstagramSurface {
        // Messaging precedence is deliberate, including mixed messaging/surface reports.
        if (input.resourceIds.any { it.substringAfterLast('/') in messaging }) return InstagramSurface.MESSAGING
        if (input.truncated) return InstagramSurface.UNKNOWN
        val ids = input.resourceIds.map { it.substringAfterLast('/') }.toSet()
        val selected = input.selectedResourceIds.map { it.substringAfterLast('/') }.toSet()
        val candidates = buildList {
            if (feedTab in selected && ids.any { it.startsWith("row_feed_") }) add(InstagramSurface.FEED)
            if (clipsTab in selected && reelsContent.any { it in input.scrollableResourceIds.map { id -> id.substringAfterLast('/') } }) add(InstagramSurface.REELS)
            if ("reel_viewer_root" in ids && ids.any { it in storyControls }) add(InstagramSurface.STORIES)
        }
        return candidates.singleOrNull() ?: InstagramSurface.UNKNOWN
    }

    internal fun classify(report: SanitizedStructuralReport?): InstagramSurface =
        report?.let { classify(it.shadowInput) } ?: InstagramSurface.UNKNOWN
}
