package com.chardy.doom

/** Resource-table names from viewIdResourceName, never UI text or account/content fields. */
internal object StructuralSanitizer {
    private const val RESOURCE_PREFIX = "com.instagram.android:id/"
    private const val MAX_RESOURCE_NAME = 64
    private const val MAX_RAW_VALUE = 96

    val classTokens: Map<String, String> = mapOf(
        "android.view.View" to "View", "android.view.ViewGroup" to "ViewGroup",
        "android.widget.TextView" to "TextView", "android.widget.EditText" to "EditText",
        "android.widget.Button" to "Button", "android.widget.ImageButton" to "ImageButton",
        "android.widget.ImageView" to "ImageView", "android.widget.FrameLayout" to "FrameLayout",
        "android.widget.LinearLayout" to "LinearLayout", "android.widget.RelativeLayout" to "RelativeLayout",
        "android.widget.ScrollView" to "ScrollView", "android.widget.HorizontalScrollView" to "HorizontalScrollView",
        "android.widget.ListView" to "ListView", "android.widget.CheckBox" to "CheckBox",
        "android.widget.Switch" to "Switch", "android.widget.RadioButton" to "RadioButton",
        "androidx.recyclerview.widget.RecyclerView" to "RecyclerView",
    )

    fun resourceId(value: CharSequence?): String? {
        if (value == null) return null
        val length = value.length
        if (length > MAX_RAW_VALUE || length - RESOURCE_PREFIX.length !in 1..MAX_RESOURCE_NAME) return null
        val normalized = CharArray(length)
        for (index in 0 until length) {
            val char = value[index]
            val valid = when {
                index < RESOURCE_PREFIX.length -> char == RESOURCE_PREFIX[index]
                index == RESOURCE_PREFIX.length -> char in 'a'..'z'
                else -> char in 'a'..'z' || char in '0'..'9' || char == '_'
            }
            if (!valid) return null
            normalized[index] = char
        }
        return String(normalized)
    }

    fun className(value: CharSequence?): String? {
        if (value == null || value.length !in 1..96) return null
        return classTokens.entries.firstOrNull { it.key.contentEquals(value) }?.value
    }

    fun isExactAscii(value: CharSequence?, expected: String): Boolean {
        if (value == null || value.length != expected.length) return false
        for (index in expected.indices) if (value[index] != expected[index]) return false
        return true
    }
}

internal data class ShadowClassificationInput(
    val resourceIds: Set<String>,
    val selectedResourceIds: Set<String>,
    val scrollableResourceIds: Set<String>,
    val truncated: Boolean,
)

/** One immutable, ASCII-only v2 report. It contains copied scalar metadata only. */
class SanitizedStructuralReport private constructor(
    val text: String,
    val truncated: Boolean,
    internal val shadowInput: ShadowClassificationInput,
) {
    class Builder internal constructor(
        private val context: StructuralCaptureContext = StructuralCaptureContext.synthetic(),
        private val maxChars: Int = MAX_CHARS,
        private val maxUniqueTokens: Int = MAX_UNIQUE_TOKENS,
    ) {
        private val nodes = ArrayList<StructuralNodeMetadata>(MAX_NODES)
        private val reasons = linkedSetOf<String>()
        private var visited = 0

        init {
            require(maxChars in HEADER_SIZE..MAX_CHARS)
            require(maxUniqueTokens in 1..MAX_UNIQUE_TOKENS)
        }

        fun markTruncated(reason: String = "nodes") {
            require(reason in TRUNCATION_REASONS)
            reasons += reason
        }

        internal fun add(node: StructuralNodeMetadata) {
            visited++
            if (nodes.size >= MAX_NODES) {
                markTruncated("nodes")
                return
            }
            if (node.position.depth > MAX_DEPTH) {
                markTruncated("depth")
                return
            }
            if (node.childCountCapped) markTruncated("children")
            if (node.actionsTruncated) markTruncated("actions")
            val elapsed = node.elapsedOffsetMs
            if (elapsed is MetadataValue.Unavailable &&
                elapsed.reason == MetadataUnavailableReason.READ_ERROR
            ) markTruncated("time")
            nodes += node
        }

        fun build(): SanitizedStructuralReport? {
            if (nodes.isEmpty()) return null
            val allTokens = nodes.flatMap { listOfNotNull(it.resourceId, it.className, uidToken(it.uniqueId)) }
                .toSortedSet()
            val admitted = allTokens.take(maxUniqueTokens).toSet()
            if (allTokens.size > maxUniqueTokens) markTruncated("tokens")

            val lines = nodes.map { line(it, admitted) }
            val outputRows = ArrayList<String>()
            var omittedForBytes = false
            var emitted = 0
            val emittedIndexes = HashSet<Int>()
            for (line in lines) {
                val candidateNode = nodes[emitted]
                val parent = candidateNode.position.parentIndex
                if (parent is MetadataValue.Present && parent.value !in emittedIndexes) {
                    markTruncated("bytes")
                    break
                }
                val candidateHeader = header(context, reasons, visited, emitted + 1)
                val candidateSize = candidateHeader.length + outputRows.sumOf { it.length } + line.length
                if (candidateSize > maxChars) {
                    omittedForBytes = true
                    break
                }
                outputRows += line
                emittedIndexes += candidateNode.position.index
                emitted++
            }
            if (omittedForBytes) markTruncated("bytes")
            val finalHeader = header(context, reasons, visited, emitted)
            val text = finalHeader + outputRows.joinToString("")
            val included = nodes.take(emitted)
            val shadowRows = included.mapNotNull { node ->
                val id = node.resourceId?.takeIf { it in admitted } ?: return@mapNotNull null
                node to id
            }
            return SanitizedStructuralReport(
                text,
                reasons.isNotEmpty(),
                ShadowClassificationInput(
                    shadowRows.map { it.second }.toSet(),
                    shadowRows.filter { it.first.hasFlag(StructuralBooleanField.SELECTED) }.map { it.second }.toSet(),
                    shadowRows.filter { it.first.hasFlag(StructuralBooleanField.SCROLLABLE) }.map { it.second }.toSet(),
                    reasons.isNotEmpty(),
                ),
            )
        }

        private fun line(node: StructuralNodeMetadata, admitted: Set<String>): String {
            fun token(value: String?) = when {
                value == null -> "-"
                value in admitted -> value
                else -> "u:token_limit"
            }
            fun tuple(value: MetadataValue<*>): String = value.wireValue { scalar(it) }
            fun bounds(value: MetadataValue<*>): String = value.wireValue {
                val b = it as BoundsPx
                "${b.left},${b.top},${b.right},${b.bottom}"
            }
            fun normalized(value: MetadataValue<*>): String = value.wireValue {
                val b = it as NormalizedBounds
                "${b.left},${b.top},${b.right},${b.bottom}"
            }
            fun collection(value: MetadataValue<StructuralCollection>): String = value.wireValue {
                val c = it as StructuralCollection
                listOf(c.rows, c.columns, c.hierarchical, c.selectionMode, c.itemCount, c.importantItemCount)
                    .joinToString(",") { tuple(it) }
            }
            fun item(value: MetadataValue<StructuralCollectionItem>): String = value.wireValue {
                val c = it as StructuralCollectionItem
                listOf(c.rowIndex, c.rowSpan, c.columnIndex, c.columnSpan, c.heading, c.selected)
                    .joinToString(",") { tuple(it) }
            }
            fun range(value: MetadataValue<StructuralRange>): String = value.wireValue {
                val r = it as StructuralRange
                listOf(r.type, r.min, r.max, r.current).joinToString(",") { tuple(it) }
            }
            val p = node.position
            val actions = node.actions.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.name}" }
            return buildString {
                append("n=${p.index} p=").append(p.parentIndex.wireValue { scalar(it) })
                append(" d=${p.depth} t=${p.bfsOrdinal} s=").append(p.siblingSlot.wireValue { scalar(it) })
                append(" draw=${tuple(node.drawingOrder)} children=${node.reportedChildCount}")
                append(" id=${token(node.resourceId)} class=${token(node.className)}")
                append(" win=${tuple(node.windowId)} uid=${token(uidToken(node.uniqueId))}")
                append(" screen=${bounds(node.screenBounds)} window=${bounds(node.windowBounds)}")
                append(" norm=${normalized(node.normalizedScreenBounds)}")
                append(" flags=${node.flags.known},${node.flags.value},${node.flags.error}")
                append(" actions=[$actions] action_count=${node.actionCount}")
                append(" actions_truncated=${if (node.actionsTruncated) 1 else 0}")
                append(" collection=${collection(node.collection)} item=${item(node.collectionItem)}")
                append(" range=${range(node.range)} input=${tuple(node.inputType)}")
                append(" live=${tuple(node.liveRegion)} movement=${tuple(node.movementGranularities)}")
                append(" selection=${tuple(node.selectionStart)},${tuple(node.selectionEnd)}")
                append(" max_length=${tuple(node.maxTextLength)} dt_ms=${tuple(node.elapsedOffsetMs)}\n")
            }
        }

        private fun scalar(value: Any): String = when (value) {
            is Boolean -> if (value) "1" else "0"
            is Float -> formatFloat(value)
            is Double -> formatFloat(value.toFloat())
            else -> value.toString()
        }

        private fun uidToken(value: MetadataValue<String>): String? = when (value) {
            is MetadataValue.Present -> value.value
            is MetadataValue.Unavailable -> null
        }

        private fun StructuralNodeMetadata.hasFlag(field: StructuralBooleanField): Boolean {
            val bit = 1L shl field.ordinal
            return flags.known and bit != 0L && flags.value and bit != 0L
        }
    }

    companion object {
        const val MAX_NODES = 128
        const val MAX_DEPTH = 8
        const val MAX_CHILDREN = 16
        const val MAX_ACTIONS = 16
        const val MAX_UNIQUE_TOKENS = 64
        const val MAX_CHARS = 8_192
        private val TRUNCATION_REASONS = setOf(
            "nodes", "depth", "children", "foreign", "missing_child", "tokens", "actions", "bytes", "time",
        )

        private fun header(context: StructuralCaptureContext, reasons: Set<String>, visited: Int, emitted: Int): String = buildString {
            append("sanitized-structure-v2\n")
            append("schema=2 tuple=v1 flags=known,value,error\n")
            append("truncated=${if (reasons.isEmpty()) 0 else 1} reasons=[${reasons.sorted().joinToString(",")}] ")
            append("visited=$visited emitted=$emitted api=${context.apiLevel} ")
            append("screen=${context.screenWidth},${context.screenHeight},${context.densityDpi}\n")
            val e = context.event
            append("event=${e.type},${e.contentChangeTypes},")
            append(e.windowChanges.wireValue { it.toString() }).append(',')
            append(e.action.wireValue { it.toString() }).append(',')
            append(e.movementGranularity.wireValue { it.toString() }).append('\n')
            append("package=com.instagram.android\n")
            append("fields=n,p,d,t,s,draw,children,id,class,win,uid,screen,window,norm,flags,")
            append("actions,action_count,actions_truncated,collection,item,range,input,live,movement,selection,max_length,dt_ms\n")
        }

        val HEADER_SIZE: Int = header(StructuralCaptureContext.synthetic(), emptySet(), 0, 0).length

        private fun formatFloat(value: Float): String {
            if (!value.isFinite()) return "u:invalid"
            if (value == 0f) return "0"
            return "%.6g".format(java.util.Locale.ROOT, value)
        }
    }
}
