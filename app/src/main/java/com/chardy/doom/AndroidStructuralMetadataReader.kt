package com.chardy.doom

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.round

/**
 * The only Android-to-v2 adapter. Every value is copied into the closed DTO before the
 * framework object is released. This class deliberately has no serializer or action authority.
 */
internal class AndroidStructuralMetadataReader(
    requestedApiCeiling: Int = Build.VERSION.SDK_INT,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val apiCeiling = minOf(requestedApiCeiling, Build.VERSION.SDK_INT)

    fun read(node: AccessibilityNodeInfo, position: StructuralNodePosition, context: StructuralCaptureContext): StructuralNodeMetadata {
        require(StructuralSanitizer.isExactAscii(node.packageName, INSTAGRAM_PACKAGE))
        val resource = StructuralSanitizer.resourceId(node.viewIdResourceName)
        val klass = StructuralSanitizer.className(node.className)
        val rawChildren = node.childCount.coerceAtLeast(0)
        val cappedChildren = rawChildren > SanitizedStructuralReport.MAX_CHILDREN
        val screen = readBounds(node, inWindow = false)
        val window = if (atLeast(34)) readBounds(node, inWindow = true)
        else MetadataValue.Unavailable(MetadataUnavailableReason.API)
        val unique = readUniqueId(node, resource)
        val flags = readFlags(node)
        val actionSnapshot = readActions(node)
        val collection = readCollection(node)
        val item = readCollectionItem(node)
        val range = readRange(node)
        val screenNorm = normalize(screen, context.screenWidth, context.screenHeight)
        val elapsed = elapsedOffset(context.startedElapsedMs)
        return StructuralNodeMetadata(
            position = position,
            resourceId = resource,
            className = klass,
            windowId = readValue { node.windowId.takeIf { it >= 0 } },
            uniqueId = unique,
            screenBounds = screen,
            windowBounds = window,
            normalizedScreenBounds = screenNorm,
            drawingOrder = apiValue(24) { node.drawingOrder.takeIf { it >= 0 } },
            rawChildCount = rawChildren,
            reportedChildCount = rawChildren.coerceAtMost(SanitizedStructuralReport.MAX_CHILDREN),
            childCountCapped = cappedChildren,
            flags = flags,
            actions = actionSnapshot.first,
            actionCount = actionSnapshot.second,
            actionsTruncated = actionSnapshot.third,
            actionState = actionSnapshot.fourth,
            collection = collection,
            collectionItem = item,
            range = range,
            inputType = apiValue(19) { node.inputType },
            liveRegion = apiValue(19) { node.liveRegion },
            movementGranularities = apiValue(16) { node.movementGranularities },
            selectionStart = apiValue(18) { node.textSelectionStart.takeIf { it >= 0 } },
            selectionEnd = apiValue(18) { node.textSelectionEnd.takeIf { it >= 0 } },
            maxTextLength = apiValue(21) { node.maxTextLength.takeIf { it >= 0 } },
            elapsedOffsetMs = elapsed,
        )
    }

    @SuppressLint("NewApi")
    private fun readUniqueId(node: AccessibilityNodeInfo, resource: String?): MetadataValue<String> {
        if (!atLeast(33)) return MetadataValue.Unavailable(MetadataUnavailableReason.API)
        return try {
            val supplied = node.uniqueId ?: return MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT)
            if (resource != null && supplied == resource) MetadataValue.Present(resource.toCharArray().concatToString())
            else MetadataValue.Unavailable(MetadataUnavailableReason.FREE_FORM)
        } catch (_: RuntimeException) {
            MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
        }
    }

    private fun readBounds(node: AccessibilityNodeInfo, inWindow: Boolean): MetadataValue<BoundsPx> {
        val rect = Rect()
        return try {
            if (inWindow) readWindowBounds(node, rect) else node.getBoundsInScreen(rect)
            val bounds = BoundsPx(rect.left, rect.top, rect.right, rect.bottom)
            if (bounds.valid()) MetadataValue.Present(bounds)
            else MetadataValue.Unavailable(MetadataUnavailableReason.INVALID)
        } catch (_: RuntimeException) {
            MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
        }
    }

    @SuppressLint("NewApi") // Called only after the API-34 ceiling/SDK guard in read().
    private fun readWindowBounds(node: AccessibilityNodeInfo, rect: Rect) = node.getBoundsInWindow(rect)

    private fun normalize(value: MetadataValue<BoundsPx>, width: Int, height: Int): MetadataValue<NormalizedBounds> {
        if (width <= 0 || height <= 0) return MetadataValue.Unavailable(MetadataUnavailableReason.DIMENSIONS)
        return when (value) {
            is MetadataValue.Unavailable -> MetadataValue.Unavailable(value.reason)
            is MetadataValue.Present -> MetadataValue.Present(
                NormalizedBounds(
                    scale(value.value.left, width), scale(value.value.top, height),
                    scale(value.value.right, width), scale(value.value.bottom, height),
                ),
            )
        }
    }

    private fun scale(value: Int, dimension: Int): Long = round(value.toDouble() * 10_000.0 / dimension).toLong()

    private fun elapsedOffset(start: Long): MetadataValue<Long> = try {
        val elapsed = nowMs() - start
        when {
            elapsed < 0L -> MetadataValue.Unavailable(MetadataUnavailableReason.CLOCK_ROLLBACK)
            elapsed <= 10_000L -> MetadataValue.Present(elapsed)
            else -> MetadataValue.Unavailable(MetadataUnavailableReason.TIMEOUT)
        }
    } catch (_: RuntimeException) {
        MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
    }

    private fun readActions(node: AccessibilityNodeInfo): Quadruple<List<StructuralAction>, Int, Boolean, MetadataValue<List<StructuralAction>>> =
        if (!atLeast(21)) Quadruple(emptyList(), 0, false, MetadataValue.Unavailable(MetadataUnavailableReason.API)) else try {
            val list = node.actionList
            val truncated = list.size > SanitizedStructuralReport.MAX_ACTIONS
            val actions = list.take(SanitizedStructuralReport.MAX_ACTIONS)
                .map { StructuralAction(it.id, StructuralActionNames.name(it.id)) }
                .distinctBy { it.id }
                .sortedBy { it.id }
            Quadruple(actions, list.size, truncated, MetadataValue.Present(actions))
        } catch (_: RuntimeException) {
            Quadruple(emptyList(), 0, true, MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR))
        }

    private fun readCollection(node: AccessibilityNodeInfo): MetadataValue<StructuralCollection> =
        if (!atLeast(19)) MetadataValue.Unavailable(MetadataUnavailableReason.API) else try {
            val info = node.collectionInfo ?: return MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT)
            val itemCounts = readCollectionItemCounts(info)
            MetadataValue.Present(
                StructuralCollection(
                    apiValue(19) { info.rowCount }, apiValue(19) { info.columnCount },
                    apiValue(19) { info.isHierarchical }, apiValue(21) { info.selectionMode },
                    itemCounts.first, itemCounts.second,
                ),
            )
        } catch (_: RuntimeException) {
            MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
        }

    @SuppressLint("NewApi") // Both getters are reached only when the requested ceiling is 35+.
    private fun readCollectionItemCounts(info: AccessibilityNodeInfo.CollectionInfo): Pair<MetadataValue<Int>, MetadataValue<Int>> =
        apiValue(35) { info.itemCount } to apiValue(35) { info.importantForAccessibilityItemCount }

    private fun readCollectionItem(node: AccessibilityNodeInfo): MetadataValue<StructuralCollectionItem> =
        if (!atLeast(19)) MetadataValue.Unavailable(MetadataUnavailableReason.API) else try {
            val info = node.collectionItemInfo ?: return MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT)
            MetadataValue.Present(
                StructuralCollectionItem(
                    apiValue(19) { info.rowIndex }, apiValue(19) { info.rowSpan },
                    apiValue(19) { info.columnIndex }, apiValue(19) { info.columnSpan },
                    apiValue(19) { info.isHeading }, apiValue(21) { info.isSelected },
                ),
            )
        } catch (_: RuntimeException) {
            MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
        }

    private fun readRange(node: AccessibilityNodeInfo): MetadataValue<StructuralRange> =
        if (!atLeast(19)) MetadataValue.Unavailable(MetadataUnavailableReason.API) else try {
            val info = node.rangeInfo ?: return MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT)
            val min = finiteValue { info.min }
            val max = finiteValue { info.max }
            val current = finiteValue { info.current }
            val type = apiValue(19) { info.type }
            MetadataValue.Present(StructuralRange(type, min, max, current))
        } catch (_: RuntimeException) {
            MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
        }

    private fun finiteValue(read: () -> Float): MetadataValue<Float> = try {
        read().takeIf { it.isFinite() }?.let { MetadataValue.Present(it) }
            ?: MetadataValue.Unavailable(MetadataUnavailableReason.INVALID)
    } catch (_: RuntimeException) {
        MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
    }

    private fun readFlags(node: AccessibilityNodeInfo): StructuralBooleanMasks {
        var known = 0L
        var value = 0L
        var error = 0L
        fun read(field: StructuralBooleanField, available: Boolean, getter: () -> Boolean) {
            if (!available) return
            val bit = 1L shl field.ordinal
            try {
                known = known or bit
                if (getter()) value = value or bit
            } catch (_: RuntimeException) {
                // The field was eligible and attempted; keep it in known while marking the
                // value unavailable through the parallel error mask.
                error = error or bit
            }
        }
        read(StructuralBooleanField.CHECKABLE, true) { node.isCheckable }
        read(StructuralBooleanField.CHECKED, true) { node.isChecked }
        read(StructuralBooleanField.CLICKABLE, true) { node.isClickable }
        read(StructuralBooleanField.ENABLED, true) { node.isEnabled }
        read(StructuralBooleanField.FOCUSABLE, true) { node.isFocusable }
        read(StructuralBooleanField.FOCUSED, true) { node.isFocused }
        read(StructuralBooleanField.LONG_CLICKABLE, true) { node.isLongClickable }
        read(StructuralBooleanField.PASSWORD, true) { node.isPassword }
        read(StructuralBooleanField.SCROLLABLE, true) { node.isScrollable }
        read(StructuralBooleanField.SELECTED, true) { node.isSelected }
        read(StructuralBooleanField.ACCESSIBILITY_FOCUSED, atLeast(16)) { node.isAccessibilityFocused }
        read(StructuralBooleanField.VISIBLE_TO_USER, atLeast(16)) { node.isVisibleToUser }
        read(StructuralBooleanField.EDITABLE, atLeast(18)) { node.isEditable }
        read(StructuralBooleanField.CAN_OPEN_POPUP, atLeast(19)) { node.canOpenPopup() }
        read(StructuralBooleanField.CONTENT_INVALID, atLeast(19)) { node.isContentInvalid }
        read(StructuralBooleanField.DISMISSABLE, atLeast(19)) { node.isDismissable }
        read(StructuralBooleanField.MULTI_LINE, atLeast(19)) { node.isMultiLine }
        read(StructuralBooleanField.CONTEXT_CLICKABLE, atLeast(23)) { node.isContextClickable }
        read(StructuralBooleanField.IMPORTANT_FOR_ACCESSIBILITY, atLeast(24)) { node.isImportantForAccessibility }
        read(StructuralBooleanField.SHOWING_HINT_TEXT, atLeast(26)) { node.isShowingHintText }
        readApi28Flags(node, ::read)
        return StructuralBooleanMasks(known, value, error)
    }

    @SuppressLint("NewApi") // Each read below is additionally gated by the requested API ceiling.
    private fun readApi28Flags(node: AccessibilityNodeInfo, read: (StructuralBooleanField, Boolean, () -> Boolean) -> Unit) {
        read(StructuralBooleanField.HEADING, atLeast(28)) { node.isHeading }
        read(StructuralBooleanField.SCREEN_READER_FOCUSABLE, atLeast(28)) { node.isScreenReaderFocusable }
        read(StructuralBooleanField.TEXT_ENTRY_KEY, atLeast(29)) { node.isTextEntryKey }
        read(StructuralBooleanField.TEXT_SELECTABLE, atLeast(33)) { node.isTextSelectable }
        read(StructuralBooleanField.ACCESSIBILITY_DATA_SENSITIVE, atLeast(34)) { node.isAccessibilityDataSensitive }
        read(StructuralBooleanField.GRANULAR_SCROLLING_SUPPORTED, atLeast(35)) { node.isGranularScrollingSupported }
    }

    private fun <T> apiValue(api: Int, read: () -> T?): MetadataValue<T> =
        if (!atLeast(api)) MetadataValue.Unavailable(MetadataUnavailableReason.API)
        else readValue(read)

    private fun <T> readValue(read: () -> T?): MetadataValue<T> = try {
        read()?.let { MetadataValue.Present(it) }
            ?: MetadataValue.Unavailable(MetadataUnavailableReason.ABSENT)
    } catch (_: RuntimeException) {
        MetadataValue.Unavailable(MetadataUnavailableReason.READ_ERROR)
    }

    private fun atLeast(api: Int) = apiCeiling >= api

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"

        fun contextFromEvent(
            event: AccessibilityEvent,
            apiLevel: Int = Build.VERSION.SDK_INT,
            width: Int,
            height: Int,
            densityDpi: Int,
            startedElapsedMs: Long,
        ): StructuralCaptureContext {
            val ceiling = minOf(apiLevel, Build.VERSION.SDK_INT)
            val windowChanges = readWindowChanges(event, ceiling)
            // The subscribed state/content events do not carry a meaningful action or movement
            // record. Keep the public fields closed and unavailable rather than treating zero as
            // a reported action.
            val action = if (ceiling >= 16 && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) MetadataValue.Present(event.action)
            else MetadataValue.Unavailable(
                if (ceiling >= 16) MetadataUnavailableReason.NOT_APPLICABLE
                else MetadataUnavailableReason.API,
            )
            val movement = if (ceiling >= 16 && event.eventType ==
                AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
            ) MetadataValue.Present(event.movementGranularity)
            else MetadataValue.Unavailable(
                if (ceiling >= 16) MetadataUnavailableReason.NOT_APPLICABLE
                else MetadataUnavailableReason.API,
            )
            return StructuralCaptureContext(
                ceiling, width, height, densityDpi, startedElapsedMs,
                StructuralCaptureEvent(
                    event.eventType, event.contentChangeTypes, windowChanges, action, movement,
                ),
            )
        }

        @SuppressLint("NewApi") // The caller supplies the explicit requested/actual API ceiling.
        private fun readWindowChanges(event: AccessibilityEvent, ceiling: Int): MetadataValue<Int> =
            if (ceiling >= 28 && event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                MetadataValue.Present(event.windowChanges)
            } else {
                MetadataValue.Unavailable(MetadataUnavailableReason.NOT_SUBSCRIBED)
            }
    }
}
