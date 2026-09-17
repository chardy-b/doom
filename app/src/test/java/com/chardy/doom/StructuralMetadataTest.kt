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

    private object AccessibilityActionIds {
        const val CLICK = 16
    }
}
