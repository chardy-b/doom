package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class SanitizedStructuralReportTest {
    private val id = "com.instagram.android:id/feed_tab"
    private fun append(builder: SanitizedStructuralReport.Builder, depth: Int = 0, resource: CharSequence? = id,
                    klass: CharSequence? = "android.widget.TextView", children: Int = 0,
                    clickable: Boolean = false, scrollable: Boolean = false, editable: Boolean = false,
                    selected: Boolean = false, checked: Boolean = false) =
        builder.add(depth, resource, klass, children, clickable, scrollable, editable, selected, checked)
    private fun report(depth: Int = 0) = SanitizedStructuralReport.Builder().apply { append(this, depth) }.build()!!

    @Test fun previouslyUnknownStaticNamesAreAdmittedAsNewStrings() {
        // Synthetic names model current resource-table discovery without guessing a vocabulary.
        listOf("clips_viewer_video_layout_v2", "a", "a0_b", "a".repeat(63), "a".repeat(64),
            "feed_tab_123", "user_alice", "abcdefabcdefabcdef").forEach { name ->
            val raw = "com.instagram.android:id/$name"
            val normalized = StructuralSanitizer.resourceId(raw)
            assertEquals(raw, normalized)
            assertNotSame(raw, normalized)
            assertTrue(SanitizedStructuralReport.Builder().apply {
                append(this, resource = raw)
            }.build()!!.text.contains("id=$raw "))
        }
        StructuralSanitizer.classTokens.forEach { (raw, normalized) ->
            assertEquals(normalized, StructuralSanitizer.className(raw))
        }
        assertNull(StructuralSanitizer.resourceId(null))
        assertNull(StructuralSanitizer.className(null))
    }

    @Test fun mutableResourceMetadataIsCopiedWithoutStringifyingOrRetainingIt() {
        val source = StringBuilder("com.instagram.android:id/new_static_2026")
        val original = source.toString()
        val metadata = object : CharSequence {
            override val length get() = source.length
            override fun get(index: Int) = source[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must not slice")
            override fun toString(): String = error("must not stringify")
        }
        val normalized = StructuralSanitizer.resourceId(metadata)
        val builder = SanitizedStructuralReport.Builder().apply { append(this, resource = metadata) }
        source.setCharAt(source.lastIndex, '!')
        assertEquals(original, normalized)
        assertTrue(builder.build()!!.text.contains("id=$original "))
        assertNull(StructuralSanitizer.resourceId(metadata))
        val hostile = object : CharSequence {
            override val length = 97
            override fun get(index: Int): Char = error("overlong input must not be read")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must not slice")
            override fun toString(): String = error("must not stringify")
        }
        assertNull(StructuralSanitizer.resourceId(hostile))
    }

    @Test fun asciiResourceGrammarIsExactAtBothNamePositions() {
        for (code in 0..127) {
            val char = code.toChar()
            val first = "com.instagram.android:id/" + char + "name"
            val tail = "com.instagram.android:id/name" + char
            assertEquals(if (char in 'a'..'z') first else null, StructuralSanitizer.resourceId(first))
            assertEquals(if (char in 'a'..'z' || char in '0'..'9' || char == '_') tail else null,
                StructuralSanitizer.resourceId(tail))
        }
    }

    @Test fun malformedDynamicAndMaliciousStringsNeverEnterReports() {
        val malicious = listOf("", " ", "$id\n", " $id", "$id/../secret", "$id?account=private",
            "com.other:id/feed_tab", "com.instagram.android:id/", "com.instagram.android:id/_feed",
            "com.instagram.android:id/1feed", "com.instagram.android:id/Feed_tab",
            "com.instagram.android:id/" + "a".repeat(65),
            "com.instagram.android:id/" + "a".repeat(70), // Total raw lengths 95, 96 and 97.
            "com.instagram.android:id/" + "a".repeat(71),
            "com.instagram.android:id/" + "a".repeat(72),
            "$id#alice", "$id@alice", "$id=alice", "$id:alice", "$id%2falice",
            "$id\\alice", "$id.alice", "$id-alice", "$id alice", "$id\t", "$id\r",
            "$id\u007f", "$id\u0085", "$id\u00a0", "$id\u200b", "$id\u202e", "$id\ud800",
            "com.instagram.android:id/ｆeed", "com.instagram.android:id/feed_١", "COM.INSTAGRAM.ANDROID:id/feed_tab",
            "com.instagram.android:id/fееd_tab", "com.instagram.android:id/feed\u0000tab",
            "android.widget.TextView\nsecret", "android.widget.TextView\$123", "com.private.alice.View",
            "TextView", "android.widget.User_123", "SECRET_MESSAGE".repeat(10000))
        malicious.forEach { raw ->
            assertNull(StructuralSanitizer.resourceId(raw))
            assertNull(StructuralSanitizer.className(raw))
            val output = SanitizedStructuralReport.Builder().apply { append(this, resource = raw, klass = raw) }.build()!!
            assertEquals(SanitizedStructuralReport.Builder().apply { append(this, resource = null, klass = null) }.build()!!.text, output.text)
            if (raw.isNotEmpty() && raw != " ") assertFalse(output.text.contains(raw))
        }
        // Reject overlong class metadata before allocating its String representation.
        val hostile = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char = error("must not read")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must not read")
            override fun toString(): String = error("must not stringify")
        }
        assertNull(StructuralSanitizer.className(hostile))
    }

    @Test fun aggregationIsSortedDeterministicAndCountsDuplicates() {
        fun build(depths: List<Int>) = SanitizedStructuralReport.Builder().apply {
            depths.forEach { append(this, it) }
        }.build()!!
        val a = build(listOf(2, 1, 1, 0))
        assertEquals(a.text, build(listOf(1, 0, 2, 1)).text)
        assertEquals(3, a.text.lineSequence().count { it.startsWith("id=") })
        assertTrue(a.text.contains("depth=1 children=0 clickable=false scrollable=false editable=false selected=false checked=false count=2"))
        assertFalse(a.truncated)
        val rows = a.text.lines().filter { it.startsWith("id=") }
        assertEquals(rows.sorted(), rows)
    }

    @Test fun everyAllowedDimensionIsReportedWithoutHashing() {
        val base = report().text
        val variants = listOf<(SanitizedStructuralReport.Builder) -> Unit>(
            { append(it, depth = 1) }, { append(it, resource = null) }, { append(it, klass = "android.widget.Button") },
            { append(it, children = 1) }, { append(it, clickable = true) }, { append(it, scrollable = true) },
            { append(it, editable = true) }, { append(it, selected = true) }, { append(it, checked = true) })
        variants.forEach { change -> assertNotEquals(base, SanitizedStructuralReport.Builder().also(change).build()!!.text) }
        assertTrue(base.contains("id=$id class=TextView"))
    }

    @Test fun emptyIsUnavailableAndBuiltReportsDoNotChange() {
        val builder = SanitizedStructuralReport.Builder()
        assertNull(builder.build())
        append(builder)
        val first = builder.build()!!
        append(builder, 1)
        assertEquals(report().text, first.text)
        assertNotEquals(first.text, builder.build()!!.text)
    }

    @Test fun nodeLimitIsInclusiveAndOverflowIsMarkedWithoutRetainingIt() {
        val builder = SanitizedStructuralReport.Builder()
        repeat(127) { append(builder) }
        assertFalse(builder.build()!!.truncated)
        append(builder)
        assertFalse(builder.build()!!.truncated)
        assertTrue(builder.build()!!.text.contains("count=128"))
        repeat(1000) { append(builder, 7) }
        val result = builder.build()!!
        assertTrue(result.truncated)
        assertTrue(result.text.contains("count=128"))
        assertFalse(result.text.contains("depth=7"))
    }

    @Test fun depthAndChildCapsAreInclusiveAndHonest() {
        assertFalse(report(7).truncated)
        assertFalse(report(8).truncated)
        val builder = SanitizedStructuralReport.Builder()
        append(builder, 8, children = 1) // Children below the maximum depth cannot be visited.
        assertTrue(builder.build()!!.truncated)
        append(builder, 9)
        assertFalse(builder.build()!!.text.contains("depth=9"))
        val beyond = SanitizedStructuralReport.Builder().apply {
            append(this)
            append(this, 9)
        }.build()!!
        assertTrue(beyond.truncated)
        assertFalse(beyond.text.contains("depth=9"))
        listOf(15, 16).forEach { children ->
            assertFalse(SanitizedStructuralReport.Builder().apply { append(this, children = children) }.build()!!.truncated)
        }
        listOf(17, Int.MAX_VALUE).forEach { children ->
            val result = SanitizedStructuralReport.Builder().apply { append(this, children = children) }.build()!!
            assertTrue(result.truncated)
            assertTrue(result.text.contains("children=16"))
        }
        assertThrows(IllegalArgumentException::class.java) { append(builder, -1) }
        assertThrows(IllegalArgumentException::class.java) { append(builder, children = -1) }
    }

    @Test fun unavailableChildrenOrUnvisitedQueueMarkTruncation() {
        val builder = SanitizedStructuralReport.Builder()
        append(builder)
        builder.markTruncated()
        assertTrue(builder.build()!!.truncated)
        assertTrue(builder.build()!!.text.contains("truncated=1"))
    }

    @Test fun uniqueTokenLimitIncludesClassesAndIsDeterministicAt63And64And65() {
        assertEquals(64, SanitizedStructuralReport.MAX_UNIQUE_TOKENS)
        val classes = StructuralSanitizer.classTokens.keys.sorted()
        assertEquals(17, classes.size)
        fun build(tokenCount: Int, reverse: Boolean = false) = SanitizedStructuralReport.Builder().apply {
            val indices = (0 until tokenCount - classes.size).toList()
            (if (reverse) indices.reversed() else indices).forEach { index ->
                append(this, resource = "com.instagram.android:id/r" + index.toString().padStart(2, '0'),
                    klass = classes[index % classes.size])
            }
        }.build()!!
        for (count in 63..65) {
            val result = build(count)
            assertEquals(count > 64, result.truncated)
            assertEquals(result.text, build(count, reverse = true).text)
            assertEquals(if (count > 64) 47 else count - 17,
                result.text.lines().count { it.startsWith("id=") })
            val emittedTokens = result.text.lines().filter { it.startsWith("id=") }
                .flatMap { it.split(' ').take(2).map { field -> field.substringAfter('=') } }.toSet()
            assertEquals(minOf(count, 64), emittedTokens.size)
            assertTrue(result.text.length < 8192) // Isolate token admission from the byte cap.
        }
        val classAndId = SanitizedStructuralReport.Builder(maxUniqueTokens = 1).apply { append(this) }.build()!!
        assertTrue(classAndId.truncated)
    }

    @Test fun fortyFivePreviouslyUnknownIdsFitWithOneSafeClass() {
        val result = SanitizedStructuralReport.Builder().apply {
            repeat(45) { append(this, resource = "com.instagram.android:id/current_static_$it") }
        }.build()!!
        assertFalse(result.truncated)
        assertEquals(45, result.text.lines().count { it.startsWith("id=") })
    }

    @Test fun productionByteBoundaryIsInclusiveAndNeverSplitsRows() {
        assertEquals(8192, SanitizedStructuralReport.MAX_CHARS)
        fun build(target: Int, reverse: Boolean = false): SanitizedStructuralReport {
            val names = (0 until 40).map { "r" + it.toString().padStart(2, '0') }.toMutableList()
            fun line(name: String) = "id=com.instagram.android:id/$name class=- depth=0 children=0 " +
                "clickable=false scrollable=false editable=false selected=false checked=false count=1\n"
            var padding = target - SanitizedStructuralReport.HEADER_SIZE - names.sumOf { line(it).length }
            assertTrue(padding in 0..40 * 61)
            for (index in names.indices) {
                val extra = minOf(padding, 61)
                names[index] += "a".repeat(extra)
                padding -= extra
            }
            assertEquals(0, padding)
            return SanitizedStructuralReport.Builder().apply {
                (if (reverse) names.reversed() else names).forEach {
                    append(this, resource = "com.instagram.android:id/$it", klass = null)
                }
            }.build()!!
        }
        for (size in 8191..8193) {
            val result = build(size)
            assertEquals(size > 8192, result.truncated)
            assertEquals(if (size > 8192) 39 else 40, result.text.lines().count { it.startsWith("id=") })
            if (size <= 8192) assertEquals(size, result.text.toByteArray(Charsets.UTF_8).size)
            assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 8192)
            assertEquals(result.text, build(size, reverse = true).text)
            assertTrue(result.text.endsWith("count=1\n"))
        }
    }

    @Test fun outputContainsOnlyAggregateMetadataFields() {
        val fields = report().text.lines().filter { it.startsWith("id=") }.single()
            .split(' ').map { it.substringBefore('=') }
        assertEquals(listOf("id", "class", "depth", "children", "clickable", "scrollable", "editable",
            "selected", "checked", "count"), fields)
    }

    @Test fun productionSizeCapAloneTruncatesDeterministically() {
        fun build(indices: List<Int>) = SanitizedStructuralReport.Builder().apply {
            indices.forEach { append(this, depth = it / 16, children = it % 16) }
        }.build()!!
        val result = build((0..127).toList())
        assertTrue(result.truncated)
        assertTrue(result.text.length <= 8192)
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 8192)
        assertEquals(result.text, build((0..127).reversed().toList()).text)
        assertTrue(result.text.lines().count { it.startsWith("id=") } in 1..127)
    }

    @Test fun characterAndUtf8BudgetsNeverSplitRowsAndMarkOmissions() {
        val length = report().text.length
        listOf(length, length + 1).forEach { limit ->
            val result = SanitizedStructuralReport.Builder(maxChars = limit).apply { append(this) }.build()!!
            assertFalse(result.truncated)
            assertEquals(report().text, result.text)
        }
        val short = SanitizedStructuralReport.Builder(maxChars = length - 1).apply { append(this) }.build()!!
        assertTrue(short.truncated)
        assertFalse(short.text.contains("id="))
        for (limit in SanitizedStructuralReport.HEADER_SIZE..8192) {
            val result = SanitizedStructuralReport.Builder(maxChars = limit).apply {
                repeat(128) { index -> append(this, index % 9, children = index % 17, clickable = index % 2 == 0) }
            }.build()!!
            assertTrue(result.text.length <= limit)
            assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= limit)
            assertTrue(result.text.all { it == '\n' || it.code in 32..126 })
            assertTrue(result.text.endsWith("\n"))
        }
        assertThrows(IllegalArgumentException::class.java) { SanitizedStructuralReport.Builder(maxChars = 8193) }
        assertThrows(IllegalArgumentException::class.java) { SanitizedStructuralReport.Builder(maxChars = SanitizedStructuralReport.HEADER_SIZE - 1) }
        assertThrows(IllegalArgumentException::class.java) { SanitizedStructuralReport.Builder(maxUniqueTokens = 65) }
        assertThrows(IllegalArgumentException::class.java) { SanitizedStructuralReport.Builder(maxUniqueTokens = 0) }
    }
}
