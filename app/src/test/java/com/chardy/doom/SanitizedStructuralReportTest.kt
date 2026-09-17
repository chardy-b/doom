package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class SanitizedStructuralReportTest {
    private fun node(
        index: Int = 0,
        parent: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.ROOT),
        depth: Int = 0,
        ordinal: Int = index,
        slot: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.ROOT),
        id: String? = "com.instagram.android:id/feed_tab",
        klass: String? = "View",
        children: Int = 0,
        selected: Boolean = false,
        scrollable: Boolean = false,
    ) = StructuralNodeMetadata(
        position = StructuralNodePosition(index, parent, depth, ordinal, slot),
        resourceId = id,
        className = klass,
        reportedChildCount = children.coerceAtMost(16),
        rawChildCount = children,
        childCountCapped = children > 16,
        flags = StructuralBooleanMasks(
            known = (1L shl StructuralBooleanField.SELECTED.ordinal) or
                (1L shl StructuralBooleanField.SCROLLABLE.ordinal),
            value = (if (selected) 1L shl StructuralBooleanField.SELECTED.ordinal else 0L) or
                (if (scrollable) 1L shl StructuralBooleanField.SCROLLABLE.ordinal else 0L),
        ),
    )

    private fun report(vararg nodes: StructuralNodeMetadata, maxChars: Int = SanitizedStructuralReport.MAX_CHARS): SanitizedStructuralReport {
        val builder = SanitizedStructuralReport.Builder(maxChars = maxChars)
        nodes.forEach(builder::add)
        return requireNotNull(builder.build())
    }

    @Test fun v2HeaderAndCompleteRowContractAreStable() {
        val result = report(node())
        assertTrue(result.text.startsWith("sanitized-structure-v2\n"))
        assertTrue(result.text.contains("schema=2 tuple=v1 flags=known,value,error"))
        assertTrue(result.text.contains("fields=n,p,d,t,s,draw,children,id,class,win,uid,screen,window,norm,flags"))
        assertTrue(result.text.contains("n=0 p=u:root d=0 t=0 s=u:root draw=u:api children=0"))
        assertTrue(result.text.contains("id=com.instagram.android:id/feed_tab class=View"))
        assertTrue(result.text.contains("collection=u:absent item=u:absent range=u:absent"))
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 8_192)
    }

    @Test fun duplicateNodesRemainIndexedAndParentOrderIsPreserved() {
        val result = report(
            node(index = 0, ordinal = 0),
            node(index = 2, parent = MetadataValue.Present(0), depth = 1, ordinal = 1,
                slot = MetadataValue.Present(2), id = "com.instagram.android:id/child"),
            node(index = 3, parent = MetadataValue.Present(0), depth = 1, ordinal = 2,
                slot = MetadataValue.Present(3), id = "com.instagram.android:id/child"),
        )
        val rows = result.text.lines().filter { it.startsWith("n=") }
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("n=2 p=0"))
        assertTrue(rows[2].startsWith("n=3 p=0"))
        assertNotEquals(rows[1], rows[2])
    }

    @Test fun sanitizerCopiesSafeIdsAndRejectsFreeFormValues() {
        val mutable = StringBuilder("com.instagram.android:id/new_static_2026")
        val metadata = object : CharSequence {
            override val length get() = mutable.length
            override fun get(index: Int) = mutable[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("no slice")
            override fun toString(): String = error("no stringify")
        }
        val safe = StructuralSanitizer.resourceId(metadata)
        mutable.setCharAt(mutable.lastIndex, '!')
        assertEquals("com.instagram.android:id/new_static_2026", safe)
        assertNull(StructuralSanitizer.resourceId(metadata))
        assertNull(StructuralSanitizer.resourceId("com.instagram.android:id/_private"))
        assertNull(StructuralSanitizer.resourceId("com.instagram.android:id/Name"))
        assertEquals("TextView", StructuralSanitizer.className("android.widget.TextView"))
        assertNull(StructuralSanitizer.className("private.Content"))
    }

    @Test fun tokenLimitKeepsRowsAndMarksOnlyTheField() {
        val result = SanitizedStructuralReport.Builder(maxUniqueTokens = 1).apply {
            add(node(id = "com.instagram.android:id/a", klass = null))
            add(node(index = 1, id = "com.instagram.android:id/b", klass = null,
                parent = MetadataValue.Present(0), depth = 1, slot = MetadataValue.Present(0)))
        }.build()!!
        assertTrue(result.truncated)
        assertEquals(2, result.text.lines().count { it.startsWith("n=") })
        assertTrue(result.text.contains("id=u:token_limit"))
    }

    @Test fun nodeDepthChildAndActionBoundsAreExplicit() {
        assertTrue(report(node(children = 17)).truncated)
        val builder = SanitizedStructuralReport.Builder()
        repeat(128) { builder.add(node(index = it, ordinal = it)) }
        assertTrue(builder.build()!!.text.contains("visited=128"))
        builder.add(node(index = 128, ordinal = 128))
        assertTrue(builder.build()!!.text.contains("nodes"))
        assertNull(SanitizedStructuralReport.Builder().build())
    }

    @Test fun outputIsDeterministicAndWholeRowsOnlyAtByteBoundary() {
        val a = report(node(), node(index = 1, id = "com.instagram.android:id/second"))
        val b = report(node(index = 1, id = "com.instagram.android:id/second"), node())
        assertNotEquals(a.text, b.text)
        val rowLength = a.text.lines().first { it.startsWith("n=") }.length + 1
        val short = SanitizedStructuralReport.Builder(maxChars = SanitizedStructuralReport.HEADER_SIZE).apply {
            add(node())
        }.build()!!
        assertTrue(short.truncated)
        assertFalse(short.text.contains("n=0"))
        assertTrue(short.text.toByteArray(Charsets.UTF_8).size <= SanitizedStructuralReport.HEADER_SIZE + rowLength - 1)
    }

    @Test fun finalHeaderAndRichRowsStayWithinEveryInjectedByteLimit() {
        val allReasons = listOf("nodes", "depth", "children", "foreign", "missing_child", "tokens", "actions", "bytes", "time")
        val actions = (0 until 16).map { StructuralAction(it + 1, "UNKNOWN") }
        val rich = node(index = 1, parent = MetadataValue.Present(0), depth = 1,
            slot = MetadataValue.Present(0), id = "com.instagram.android:id/a_very_long_static_resource_name_2026")
            .copy(
                actions = actions,
                actionCount = actions.size,
                actionsTruncated = true,
                actionState = MetadataValue.Present(actions),
                screenBounds = MetadataValue.Present(BoundsPx(-1000, -1000, 9000, 9000)),
                windowBounds = MetadataValue.Present(BoundsPx(0, 0, 9000, 9000)),
                normalizedScreenBounds = MetadataValue.Present(NormalizedBounds(-10000, -10000, 90000, 90000)),
            )
        for (limit in SanitizedStructuralReport.HEADER_SIZE..SanitizedStructuralReport.MAX_CHARS) {
            val builder = SanitizedStructuralReport.Builder(maxChars = limit).apply {
                add(node())
                add(rich)
                allReasons.forEach(::markTruncated)
            }
            val result = requireNotNull(builder.build())
            assertTrue("limit=$limit", result.text.toByteArray(Charsets.UTF_8).size <= limit)
        }
    }

    @Test fun shadowInputIncludesSanitizedRowsOmittedByTheTextByteCutoff() {
        val result = SanitizedStructuralReport.Builder(maxChars = SanitizedStructuralReport.HEADER_SIZE + 1).apply {
            add(node(id = "com.instagram.android:id/unrelated", klass = null))
            add(node(index = 1, parent = MetadataValue.Present(0), depth = 1,
                slot = MetadataValue.Present(0), id = "com.instagram.android:id/message_list",
                selected = false, scrollable = true))
        }.build()!!
        assertFalse(result.text.contains("message_list"))
        assertEquals(InstagramSurface.MESSAGING, InstagramSurfaceShadowClassifier.classify(result))
    }

    @Test fun actionReadFailureUsesTypedUnavailableWireState() {
        val result = report(node().copy(
            actionState = MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR),
        ))
        assertTrue(result.text.contains("actions=u:read_error action_count=u:read_error actions_truncated=u:read_error"))
    }

    @Test fun uniqueIdUnavailableReasonsUseExactTypedWireTokens() {
        val reasons = listOf(
            MetadataUnavailableReason.FREE_FORM to "u:free_form",
            MetadataUnavailableReason.ABSENT to "u:absent",
            MetadataUnavailableReason.API to "u:api",
            MetadataUnavailableReason.READ_ERROR to "u:read_error",
        )
        reasons.forEach { (reason, wire) ->
            val result = report(node().copy(
                uniqueId = MetadataValue.Unavailable(reason),
            ))
            assertTrue("missing $wire", result.text.contains("uid=$wire"))
            assertFalse(result.text.contains("uid=-"))
        }
    }

    @Test fun uniqueIdOnlyRetainsTheAlreadyAcceptedResourceToken() {
        val accepted = report(node().copy(
            uniqueId = MetadataValue.Present("com.instagram.android:id/feed_tab"),
        ))
        assertTrue(accepted.text.contains("uid=com.instagram.android:id/feed_tab"))

        val unsafe = report(node().copy(
            uniqueId = MetadataValue.Present("free-form-public-value"),
        ))
        assertTrue(unsafe.text.contains("uid=u:free_form"))
        assertFalse(unsafe.text.contains("free-form-public-value"))
    }

    @Test fun danglingParentUsesClosedNodeOmissionReason() {
        val result = report(node(index = 3, parent = MetadataValue.Present(99), depth = 1))
        assertTrue(result.truncated)
        assertTrue(result.text.contains("reasons=[nodes]"))
        assertFalse(result.text.contains("reasons=[bytes]"))
    }

    @Test fun timeoutStopsBeforeRowAndRollbackInvalidatesBuilder() {
        val timeout = SanitizedStructuralReport.Builder().apply {
            add(node().copy(
                elapsedOffsetMs = MetadataValue.Unavailable(MetadataUnavailableReason.TIMEOUT),
            ))
        }.build()
        assertNull(timeout)

        val rollback = SanitizedStructuralReport.Builder().apply {
            add(node().copy(
                elapsedOffsetMs = MetadataValue.Unavailable(MetadataUnavailableReason.CLOCK_ROLLBACK),
            ))
            add(node(index = 1, id = "com.instagram.android:id/later"))
        }.build()
        assertNull(rollback)
    }

    @Test fun immutableReportDoesNotChangeAfterBuilderMutation() {
        val builder = SanitizedStructuralReport.Builder().apply { add(node()) }
        val first = builder.build()!!
        builder.add(node(index = 1, id = "com.instagram.android:id/second"))
        assertFalse(first.text.contains("second"))
    }
}
