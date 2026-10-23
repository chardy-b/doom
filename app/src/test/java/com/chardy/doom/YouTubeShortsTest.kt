package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class YouTubeShortsTest {
    private class Node(
        val id: String? = null,
        val children: List<Node> = emptyList(),
        val owner: String? = YOUTUBE_PACKAGE,
    ) : YouTubeIdNode {
        var releases = 0
        override fun packageName(): CharSequence? = owner
        override fun resourceId(): CharSequence? = id
        override fun childCount() = children.size
        override fun child(index: Int): YouTubeIdNode = children[index]
        override fun recycle() { releases++ }
    }
    private fun id(name: String) = "$YOUTUBE_PACKAGE:id/$name"
    private fun sample(vararg names: String) = YouTubeIdCollector.collect(
        Node(children = names.map { Node(id(it)) }), { 0L },
    )

    @Test fun everyClosedPlayerMarkerRequiresACompleteShelfFreeScan() {
        for (name in listOf("reel_watch_fragment_root", "reel_recycler", "reel_watch_player",
            "reel_player_page_container", "reel_player_underlay", "shorts_video_pager")) {
            assertTrue(YouTubeShortsClassifier.isPlayer(sample(name)))
            for (shelf in listOf("shorts_shelf", "reel_shelf")) {
                assertFalse(YouTubeShortsClassifier.isPlayer(sample(name, shelf)))
                assertFalse(YouTubeShortsClassifier.isPlayer(sample(shelf, name)))
            }
        }
    }

    @Test fun ordinaryYouTubeAndUnknownIdsNeverAdmit() {
        for (name in listOf("home", "search", "subscriptions", "watch_player", "shorts_shelf", "reel_shelf"))
            assertFalse(YouTubeShortsClassifier.isPlayer(sample(name)))
        assertFalse(YouTubeShortsClassifier.isPlayer(sample()))
        assertFalse(YouTubeShortsClassifier.isPlayer(null))
    }

    @Test fun sanitizerRejectsMalformedAndForeignIdsWithoutRetainingThem() {
        for (raw in listOf("reel_watch_player", "com.instagram.android:id/reel_watch_player",
            id("reel_watch_player "), id("Reel_watch_player"), id("reel_watch_player/extra"),
            id("x".repeat(65)), id("reel_watch_player\u0000"))) {
            val report = YouTubeIdCollector.collect(Node(children = listOf(Node(id("reel_watch_player")), Node(raw))), { 0L })
            assertFalse(report.complete)
            assertFalse(YouTubeShortsClassifier.isPlayer(report))
        }
    }

    @Test fun boundsAndForeignChildrenInvalidateAndReleaseEveryAcquiredNode() {
        val children = List(128) { Node(id("reel_watch_player")) }
        val root = Node(children = children)
        assertFalse(YouTubeIdCollector.collect(root, { 0L }).complete)
        assertEquals(1, root.releases)
        assertTrue(children.all { it.releases in 0..1 })
        val foreign = Node(id("reel_watch_player"), owner = "com.instagram.android")
        assertFalse(YouTubeIdCollector.collect(foreign, { 0L }).complete)
        assertEquals(1, foreign.releases)
        var deep = Node(id("reel_watch_player"))
        repeat(9) { deep = Node(children = listOf(deep)) }
        assertFalse(YouTubeIdCollector.collect(deep, { 0L }).complete)
    }

    @Test fun timeoutRollbackMissingChildAndReadFailuresFailOpen() {
        var time = 0L
        assertFalse(YouTubeIdCollector.collect(Node(id("reel_watch_player")), { time.also { time += 100L } }).complete)
        time = 2L
        assertFalse(YouTubeIdCollector.collect(Node(id("reel_watch_player")), { time-- }).complete)
        var recycled = 0
        val broken = object : YouTubeIdNode {
            override fun packageName(): CharSequence = YOUTUBE_PACKAGE
            override fun resourceId(): CharSequence = id("reel_watch_player")
            override fun childCount() = 1
            override fun child(index: Int): YouTubeIdNode? = null
            override fun recycle() { recycled++ }
        }
        assertFalse(YouTubeIdCollector.collect(broken, { 0L }).complete)
        assertEquals(1, recycled)
        assertFalse(YouTubeIdCollector.collect(null, { 0L }).complete)
    }

    @Test fun completionNeedsShownDurationAndPhysicalDetachWithSnapshotSettings() {
        val gate = YouTubeShortsGate()
        val ticket = gate.begin(0, 10_000, 60_000)!!
        assertFalse(gate.completeDetached(ticket, 20_000, true, true))
        assertTrue(gate.shown(ticket, 100))
        assertFalse(gate.completeDetached(ticket, 10_099, true, true))
        assertFalse(gate.completeDetached(ticket, 10_100, false, true))
        assertFalse(gate.completeDetached(ticket, 10_100, true, false))
        assertEquals(10_000L, gate.duration(ticket))
        assertTrue(gate.completeDetached(ticket, 10_100, true, true))
        assertNull(gate.begin(70_099, 20_000, 300_000))
        assertNotNull(gate.begin(70_100, 20_000, 300_000))
    }

    @Test fun exceptionalReadsReleaseCurrentAndEveryQueuedNode() {
        val queued = Node()
        var releases = 0
        val broken = object : YouTubeIdNode {
            override fun packageName(): CharSequence = YOUTUBE_PACKAGE
            override fun resourceId(): CharSequence = error("synthetic read failure")
            override fun childCount() = error("must not descend after failed ID read")
            override fun child(index: Int): YouTubeIdNode? = error("must not descend")
            override fun recycle() { releases++ }
        }
        var rootReleases = 0
        val root = object : YouTubeIdNode {
            override fun packageName(): CharSequence = YOUTUBE_PACKAGE
            override fun resourceId(): CharSequence? = null
            override fun childCount() = 2
            override fun child(index: Int): YouTubeIdNode = if (index == 0) broken else queued
            override fun recycle() { rootReleases++ }
        }
        assertFalse(YouTubeIdCollector.collect(root, { 0L }).complete)
        assertEquals(1, rootReleases)
        assertEquals(1, releases)
        assertEquals(1, queued.releases)
    }

    @Test fun cancellationStaleAndOtherOwnerTicketsCannotEarnCooldown() {
        val gate = YouTubeShortsGate()
        val other = YouTubeShortsGate()
        val old = gate.begin(0, 10_000, 60_000)!!
        val foreign = other.begin(0, 10_000, 60_000)!!
        gate.shown(old, 0)
        assertFalse(gate.shown(foreign, 0))
        gate.cancel()
        val replacement = gate.begin(0, 20_000, 300_000)!!
        gate.shown(replacement, 0)
        assertFalse(gate.completeDetached(old, 30_000, true, true))
        assertFalse(gate.completeDetached(foreign, 30_000, true, true))
        assertTrue(gate.current(replacement))
        gate.cancel()
        assertNotNull(gate.begin(30_000, 10_000, 60_000))
    }

    @Test fun youtubeAndInstagramCooldownsAreIndependentAndProcessLocal() {
        val youtube = YouTubeShortsGate()
        val ticket = youtube.begin(0, 10_000, 60_000)!!
        youtube.shown(ticket, 0)
        assertTrue(youtube.completeDetached(ticket, 10_000, true, true))
        val instagram = InstagramEntryGate(enabled = { true }, monotonicNowMs = { 10_000L })
        assertNotNull(instagram.beginInstagramSessionIfEligible())
        assertNotNull(YouTubeShortsGate().begin(10_000, 10_000, 60_000))
    }
}
