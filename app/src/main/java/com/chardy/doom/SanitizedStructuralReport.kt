package com.chardy.doom

/** Resource-table names from viewIdResourceName, never UI text or account/content fields.
 * Admit previously unknown static Instagram names by exact ASCII grammar; classes stay closed.
 * Copy validated characters into a new String, never retain caller-owned metadata.
 */
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
        "androidx.recyclerview.widget.RecyclerView" to "RecyclerView"
    )

    fun resourceId(value: CharSequence?): String? {
        if (value == null) return null
        val length = value.length
        if (length > MAX_RAW_VALUE || length - RESOURCE_PREFIX.length !in 1..MAX_RESOURCE_NAME) return null
        val normalized = CharArray(length)
        for (index in 0 until length) {
            // Read each character once: the caller may supply a mutable CharSequence.
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
}

/** One immutable ASCII report. No raw tree, UI content, history or hashes. */
class SanitizedStructuralReport private constructor(val text: String, val truncated: Boolean) {
    private data class Row(
        val id: String?, val klass: String?, val depth: Int, val children: Int,
        val clickable: Boolean, val scrollable: Boolean, val editable: Boolean,
        val selected: Boolean, val checked: Boolean
    ) {
        fun line(count: Int) = "id=${id ?: "-"} class=${klass ?: "-"} depth=$depth children=$children " +
            "clickable=$clickable scrollable=$scrollable editable=$editable selected=$selected checked=$checked count=$count\n"
    }

    // Smaller limits are useful for boundary tests; callers cannot exceed production hard limits.
    class Builder internal constructor(
        private val maxChars: Int = MAX_CHARS,
        private val maxUniqueTokens: Int = MAX_UNIQUE_TOKENS
    ) {
        private val rows = mutableMapOf<Row, Int>()
        private var nodes = 0
        private var truncated = false

        init {
            require(maxChars in HEADER_SIZE..MAX_CHARS)
            require(maxUniqueTokens in 1..MAX_UNIQUE_TOKENS)
        }

        fun markTruncated() { truncated = true }

        fun add(depth: Int, resourceId: CharSequence?, className: CharSequence?, childCount: Int,
                clickable: Boolean, scrollable: Boolean, editable: Boolean, selected: Boolean, checked: Boolean) {
            require(depth >= 0 && childCount >= 0)
            if (nodes == MAX_NODES || depth > MAX_DEPTH) { markTruncated(); return }
            if (childCount > MAX_CHILDREN || (depth == MAX_DEPTH && childCount > 0)) markTruncated()
            val row = Row(StructuralSanitizer.resourceId(resourceId), StructuralSanitizer.className(className),
                depth, childCount.coerceAtMost(MAX_CHILDREN), clickable, scrollable, editable, selected, checked)
            rows[row] = (rows[row] ?: 0) + 1
            nodes++
        }

        fun build(): SanitizedStructuralReport? {
            if (nodes == 0) return null
            // Working state: <=128 rows, each with one copied resource name and one safe class.
            // At most 128 resource names plus the closed class map, before output admission.
            // Select tokens lexically before limiting output, so traversal order cannot bias it.
            val tokens = rows.keys.flatMap { listOfNotNull(it.id, it.klass) }.toSortedSet()
            val admitted = tokens.take(maxUniqueTokens).toSet()
            var omitted = truncated || tokens.size > maxUniqueTokens
            val lines = rows.entries.mapNotNull { (row, count) ->
                if ((row.id != null && row.id !in admitted) || (row.klass != null && row.klass !in admitted)) {
                    omitted = true
                    null
                } else row.line(count)
            }.sorted()
            val body = StringBuilder()
            for (line in lines) {
                if (HEADER_SIZE + body.length + line.length > maxChars) { omitted = true; break }
                body.append(line)
            }
            // Every emitted character is ASCII: the UTF-8 byte and character caps are identical.
            return SanitizedStructuralReport(header(omitted) + body, omitted)
        }
    }

    companion object {
        const val MAX_NODES = 128
        const val MAX_DEPTH = 8
        const val MAX_CHILDREN = 16
        const val MAX_UNIQUE_TOKENS = 64
        const val MAX_CHARS = 8192
        private fun header(truncated: Boolean) = "sanitized-structure-v1\ntruncated=${if (truncated) 1 else 0}\n"
        val HEADER_SIZE = header(false).length
    }
}
