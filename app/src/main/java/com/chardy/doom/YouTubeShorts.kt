package com.chardy.doom

internal const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** This boundary deliberately cannot read content, bounds, actions, or Instagram reports. */
internal interface YouTubeIdNode {
    fun packageName(): CharSequence?
    fun resourceId(): CharSequence?
    fun childCount(): Int
    fun child(index: Int): YouTubeIdNode?
    fun recycle()
}

internal enum class YouTubeMarker { PLAYER, SHELF, OTHER, INVALID }
internal data class YouTubeIdReport(val complete: Boolean, val player: Boolean, val shelf: Boolean)

internal object YouTubeIdSanitizer {
    private val players = listOf("reel_watch_fragment_root", "reel_recycler", "reel_watch_player",
        "reel_player_page_container", "reel_player_underlay", "shorts_video_pager")
    private val shelves = listOf("shorts_shelf", "reel_shelf")
    private const val PREFIX = "$YOUTUBE_PACKAGE:id/"

    fun exact(value: CharSequence?, expected: String): Boolean =
        value != null && value.length == expected.length && expected.indices.all { value[it] == expected[it] }

    /** Return a closed category; never retain caller-owned strings or unknown resource names. */
    fun marker(raw: CharSequence?): YouTubeMarker {
        if (raw == null) return YouTubeMarker.OTHER
        if (raw.length !in PREFIX.length + 1..PREFIX.length + 64 ||
            PREFIX.indices.any { raw[it] != PREFIX[it] }) return YouTubeMarker.INVALID
        for (i in PREFIX.length until raw.length) {
            val c = raw[i]
            if (if (i == PREFIX.length) c !in 'a'..'z' else c !in 'a'..'z' && c !in '0'..'9' && c != '_')
                return YouTubeMarker.INVALID
        }
        if (players.any { exact(raw, PREFIX + it) }) return YouTubeMarker.PLAYER
        if (shelves.any { exact(raw, PREFIX + it) }) return YouTubeMarker.SHELF
        return YouTubeMarker.OTHER
    }
}

internal object YouTubeIdCollector {
    private const val MAX_NODES = 128
    private const val MAX_DEPTH = 8
    private const val TIMEOUT_MS = 100L

    /** Takes ownership of root and every acquired child, including all exceptional exits. */
    fun collect(root: YouTubeIdNode?, now: () -> Long): YouTubeIdReport {
        val queue = ArrayDeque<Pair<YouTubeIdNode, Int>>()
        var complete = root != null
        var player = false
        var shelf = false
        var acquired = 0
        var previous = -1L
        fun release(node: YouTubeIdNode) {
            try { node.recycle() } catch (_: RuntimeException) { complete = false }
        }
        if (root != null) { queue.add(root to 0); acquired++ }
        try {
            val start = now()
            previous = start
            if (start < 0) complete = false
            while (complete && queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                try {
                    val clock = now()
                    if (clock < previous || clock - start >= TIMEOUT_MS) { complete = false; break }
                    previous = clock
                    if (!YouTubeIdSanitizer.exact(node.packageName(), YOUTUBE_PACKAGE)) { complete = false; break }
                    when (YouTubeIdSanitizer.marker(node.resourceId())) {
                        YouTubeMarker.PLAYER -> player = true
                        YouTubeMarker.SHELF -> shelf = true
                        YouTubeMarker.INVALID -> { complete = false; break }
                        YouTubeMarker.OTHER -> Unit
                    }
                    val children = node.childCount()
                    if (children < 0 || children > MAX_NODES - acquired || (depth >= MAX_DEPTH && children > 0)) {
                        complete = false; break
                    }
                    repeat(children) { index ->
                        val child = node.child(index)
                        if (child == null) complete = false
                        else { acquired++; queue.add(child to depth + 1) }
                    }
                } finally { release(node) }
            }
            val end = now()
            if (end < previous || end - start >= TIMEOUT_MS) complete = false
        } catch (_: RuntimeException) { complete = false }
        finally { while (queue.isNotEmpty()) release(queue.removeFirst().first) }
        return YouTubeIdReport(complete, player, shelf)
    }
}

internal object YouTubeShortsClassifier {
    fun isPlayer(report: YouTubeIdReport?) = report?.let { it.complete && it.player && !it.shelf } == true
}

/** Identity is intentionally referential: generations from another controller have no authority. */
internal class YouTubeGateTicket(val durationMs: Long, val spacingMs: Long)

/** Independent process-local policy. Only a completed, physically detached breath earns spacing. */
internal class YouTubeShortsGate {
    private var active: YouTubeGateTicket? = null
    private var shownAt: Long? = null
    private var completedAt: Long? = null
    private var spacing = 0L
    fun current(ticket: YouTubeGateTicket) = active === ticket
    fun begin(now: Long, durationMs: Long, spacingMs: Long): YouTubeGateTicket? {
        if (now < 0 || active != null || durationMs !in 10_000..120_000 || spacingMs !in 60_000..86_400_000) return null
        completedAt?.let { if (now < it || now - it < spacing) return null }
        return YouTubeGateTicket(durationMs, spacingMs).also { active = it; shownAt = null }
    }
    fun shown(ticket: YouTubeGateTicket, now: Long): Boolean {
        if (!current(ticket) || shownAt != null || now < 0) return false
        shownAt = now
        return true
    }
    fun duration(ticket: YouTubeGateTicket) = if (current(ticket)) ticket.durationMs else 0L
    fun remaining(ticket: YouTubeGateTicket, now: Long): Long? {
        val start = shownAt ?: return null
        if (!current(ticket) || now < start) return null
        return (ticket.durationMs - (now - start)).coerceAtLeast(0)
    }
    fun completeDetached(ticket: YouTubeGateTicket, now: Long, detached: Boolean, authorized: Boolean): Boolean {
        if (!detached || !authorized || remaining(ticket, now) != 0L) return false
        completedAt = now
        spacing = ticket.spacingMs
        cancel()
        return true
    }
    fun cancel() { active = null; shownAt = null }
}
