package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class StructuralMetadataTest {
    @Test fun unavailableReasonsAndBooleanMasksAreClosed() {
        assertEquals("free_form", MetadataUnavailableReason.FREE_FORM.wire)
        assertEquals("u:api", MetadataValue.Unavailable(MetadataUnavailableReason.API).wireValue { it.toString() })
        assertThrows(IllegalArgumentException::class.java) {
            StructuralBooleanMasks(known = 1L, value = 2L)
        }
    }

    @Test fun booleanGetterErrorKeepsKnownBitAndSerializesAsError() {
        val bit = 1L shl StructuralBooleanField.CLICKABLE.ordinal
        val masks = StructuralBooleanMasks(known = bit, error = bit)
        assertEquals(bit, masks.known)
        assertEquals(bit, masks.error)
        assertEquals(0L, masks.value)
        val report = SanitizedStructuralReport.Builder().apply {
            add(StructuralNodeMetadata(position = StructuralNodePosition(), resourceId = null, className = null, flags = masks))
        }.build()!!
        assertTrue(report.text.contains("flags=$bit,0,$bit"))
    }

    @Test fun syntheticContextHasExplicitEventAndDimensions() {
        val context = StructuralCaptureContext.synthetic(apiLevel = 26)
        assertEquals(26, context.apiLevel)
        assertEquals(1080, context.screenWidth)
        assertEquals(MetadataUnavailableReason.NOT_SUBSCRIBED,
            (context.event.windowChanges as MetadataValue.Unavailable).reason)
        assertEquals(MetadataUnavailableReason.NOT_APPLICABLE,
            (context.event.action as MetadataValue.Unavailable).reason)
    }

    @Test fun actionsUseClosedNamesAndUnknownDoesNotInspectLabels() {
        assertEquals("CLICK", StructuralActionNames.name(AccessibilityActionIds.CLICK))
        assertEquals("UNKNOWN", StructuralActionNames.name(0x7fff1234))
    }

    @Test fun uniqueIdWireReasonsAreClosedAndExact() {
        mapOf(
            MetadataUnavailableReason.FREE_FORM to "u:free_form",
            MetadataUnavailableReason.ABSENT to "u:absent",
            MetadataUnavailableReason.API to "u:api",
            MetadataUnavailableReason.READ_ERROR to "u:read_error",
        ).forEach { (reason, expected) ->
            assertEquals(expected, MetadataValue.Unavailable(reason).wireValue { it.toString() })
        }
    }

    private object AccessibilityActionIds {
        const val CLICK = 16
    }
}
