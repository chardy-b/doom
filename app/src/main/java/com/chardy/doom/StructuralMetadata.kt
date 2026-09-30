package com.chardy.doom

/** Closed reasons for values that cannot safely enter the bounded report. */
internal enum class MetadataUnavailableReason(val wire: String) {
    API("api"),
    ABSENT("absent"),
    INVALID("invalid"),
    READ_ERROR("read_error"),
    CLOCK_ROLLBACK("clock_rollback"),
    TIMEOUT("timeout"),
    NOT_APPLICABLE("not_applicable"),
    NOT_SUBSCRIBED("not_subscribed"),
    ROOT("root"),
    DIMENSIONS("dimensions"),
    FREE_FORM("free_form"),
    TOKEN_LIMIT("token_limit"),
}

/** A scalar value or one of the closed unavailable markers. */
internal sealed interface MetadataValue<out T> {
    data class Present<T>(val value: T) : MetadataValue<T>
    data class Unavailable(val reason: MetadataUnavailableReason) : MetadataValue<Nothing>
}

internal fun MetadataValue<*>.wireValue(value: (Any) -> String): String = when (this) {
    is MetadataValue.Present<*> -> value(requireNotNull(this.value))
    is MetadataValue.Unavailable -> "u:${reason.wire}"
}

internal data class BoundsPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid() = left <= right && top <= bottom
}

internal data class NormalizedBounds(val left: Long, val top: Long, val right: Long, val bottom: Long)

internal data class StructuralCaptureEvent(
    val type: Int,
    val contentChangeTypes: Int,
    val windowChanges: MetadataValue<Int>,
    val action: MetadataValue<Int>,
    val movementGranularity: MetadataValue<Int>,
)

internal data class StructuralCaptureContext(
    val apiLevel: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val startedElapsedMs: Long,
    val event: StructuralCaptureEvent,
) {
    companion object {
        fun synthetic(
            apiLevel: Int = 35,
            width: Int = 1080,
            height: Int = 2400,
            densityDpi: Int = 420,
            startedElapsedMs: Long = 1_000L,
        ) = StructuralCaptureContext(
            apiLevel, width, height, densityDpi, startedElapsedMs,
            StructuralCaptureEvent(
                type = 32,
                contentChangeTypes = 0,
                windowChanges = MetadataValue.Unavailable(MetadataUnavailableReason.NOT_SUBSCRIBED),
                action = MetadataValue.Unavailable(MetadataUnavailableReason.NOT_APPLICABLE),
                movementGranularity = MetadataValue.Unavailable(MetadataUnavailableReason.NOT_APPLICABLE),
            ),
        )
    }
}

internal data class StructuralNodePosition(
    val index: Int = 0,
    val parentIndex: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.ROOT),
    val depth: Int = 0,
    val bfsOrdinal: Int = 0,
    val siblingSlot: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.ROOT),
)

/** Fixed-position masks. Bit order is the order in this enum and is part of v2. */
internal enum class StructuralBooleanField {
    CHECKABLE, CHECKED, CLICKABLE, ENABLED, FOCUSABLE, FOCUSED, LONG_CLICKABLE, PASSWORD,
    SCROLLABLE, SELECTED, ACCESSIBILITY_FOCUSED, VISIBLE_TO_USER, EDITABLE, CAN_OPEN_POPUP,
    CONTENT_INVALID, DISMISSABLE, MULTI_LINE, CONTEXT_CLICKABLE, IMPORTANT_FOR_ACCESSIBILITY,
    SHOWING_HINT_TEXT, HEADING, SCREEN_READER_FOCUSABLE, TEXT_ENTRY_KEY, TEXT_SELECTABLE,
    ACCESSIBILITY_DATA_SENSITIVE, GRANULAR_SCROLLING_SUPPORTED,
}

internal data class StructuralBooleanMasks(
    val known: Long = 0L,
    val value: Long = 0L,
    val error: Long = 0L,
) {
    init {
        require(value and known.inv() == 0L)
        require(error and known.inv() == 0L)
    }
}

internal data class StructuralAction(val id: Int, val name: String)

internal data class StructuralCollection(
    val rows: MetadataValue<Int>,
    val columns: MetadataValue<Int>,
    val hierarchical: MetadataValue<Boolean>,
    val selectionMode: MetadataValue<Int>,
    val itemCount: MetadataValue<Int>,
    val importantItemCount: MetadataValue<Int>,
)

internal data class StructuralCollectionItem(
    val rowIndex: MetadataValue<Int>,
    val rowSpan: MetadataValue<Int>,
    val columnIndex: MetadataValue<Int>,
    val columnSpan: MetadataValue<Int>,
    val heading: MetadataValue<Boolean>,
    val selected: MetadataValue<Boolean>,
)

internal data class StructuralRange(
    val type: MetadataValue<Int>,
    val min: MetadataValue<Float>,
    val max: MetadataValue<Float>,
    val current: MetadataValue<Float>,
)

/** Android-free, scalar-only snapshot of one visited node. */
internal data class StructuralNodeMetadata(
    val position: StructuralNodePosition,
    val resourceId: String?,
    val className: String?,
    val windowId: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val uniqueId: MetadataValue<String> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val screenBounds: MetadataValue<BoundsPx> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val windowBounds: MetadataValue<BoundsPx> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val normalizedScreenBounds: MetadataValue<NormalizedBounds> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val drawingOrder: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val rawChildCount: Int = 0,
    val reportedChildCount: Int = 0,
    val childCountCapped: Boolean = false,
    val flags: StructuralBooleanMasks = StructuralBooleanMasks(),
    val actions: List<StructuralAction> = emptyList(),
    val actionCount: Int = 0,
    val actionsTruncated: Boolean = false,
    /** Present distinguishes an empty action list from a failed action-list read. */
    val actionState: MetadataValue<List<StructuralAction>> = MetadataValue.Present(actions),
    val collection: MetadataValue<StructuralCollection> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val collectionItem: MetadataValue<StructuralCollectionItem> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val range: MetadataValue<StructuralRange> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
    val inputType: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val liveRegion: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val movementGranularities: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val selectionStart: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val selectionEnd: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val maxTextLength: MetadataValue<Int> = MetadataValue.Unavailable(MetadataUnavailableReason.API),
    val elapsedOffsetMs: MetadataValue<Long> = MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT),
) {
    init {
        require(rawChildCount >= 0)
        require(reportedChildCount in 0..SanitizedStructuralReport.MAX_CHILDREN)
        require(actions.size <= SanitizedStructuralReport.MAX_ACTIONS)
        require(actionCount >= 0)
        require(actionState is MetadataValue.Unavailable || actionState is MetadataValue.Present)
    }
}

internal object StructuralActionNames {
    // Numeric IDs are copied from the public SDK-35 AccessibilityAction constants.
    private val names = mapOf(
        1 to "FOCUS", 2 to "CLEAR_FOCUS", 4 to "SELECT", 8 to "CLEAR_SELECTION",
        16 to "CLICK", 32 to "LONG_CLICK", 64 to "ACCESSIBILITY_FOCUS",
        128 to "CLEAR_ACCESSIBILITY_FOCUS", 256 to "NEXT_AT_MOVEMENT_GRANULARITY",
        512 to "PREVIOUS_AT_MOVEMENT_GRANULARITY", 1024 to "NEXT_HTML_ELEMENT",
        2048 to "PREVIOUS_HTML_ELEMENT", 4096 to "SCROLL_FORWARD", 8192 to "SCROLL_BACKWARD",
        16384 to "COPY", 32768 to "PASTE", 65536 to "CUT", 131072 to "SET_SELECTION",
        262144 to "EXPAND", 524288 to "COLLAPSE", 1048576 to "DISMISS",
        2097152 to "SET_TEXT", 16908342 to "SHOW_ON_SCREEN", 16908343 to "SCROLL_TO_POSITION",
        16908344 to "SCROLL_UP", 16908345 to "SCROLL_LEFT", 16908346 to "SCROLL_DOWN",
        16908347 to "SCROLL_RIGHT", 16908348 to "CONTEXT_CLICK", 16908349 to "SET_PROGRESS",
        16908354 to "IME_ENTER", 16908355 to "MOVE_WINDOW", 16908356 to "SHOW_TOOLTIP",
        16908357 to "HIDE_TOOLTIP", 16908358 to "PAGE_UP", 16908359 to "PAGE_DOWN",
        16908360 to "PAGE_LEFT", 16908361 to "PAGE_RIGHT", 16908362 to "PRESS_AND_HOLD",
        16908372 to "IME_ACTION", 16908373 to "DRAG_START", 16908374 to "DRAG_DROP",
        16908375 to "DRAG_CANCEL", 16908376 to "IME_ACTION_NONE", 16908377 to "IME_ACTION_UNSPECIFIED",
    )

    fun name(id: Int): String = names[id] ?: "UNKNOWN"
}
