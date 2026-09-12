package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryGateOverlayModelTest {
    private val diagnostic = OverlayDiagnosticStatus(
        EntryGateSurface.UNKNOWN, OverlayReportStatus.UNAVAILABLE, false
    )

    @Test fun countdownUsesCeilingAndNeverRunsPastBounds() {
        assertEquals(5, EntryGateOverlayModel.from(5_000, 5_000, false, diagnostic).remainingSeconds)
        assertEquals(1, EntryGateOverlayModel.from(1, 5_000, false, diagnostic).remainingSeconds)
        assertEquals(0, EntryGateOverlayModel.from(0, 5_000, false, diagnostic).remainingSeconds)
        assertEquals(1f, EntryGateOverlayModel.from(-1, 5_000, false, diagnostic).progress)
        assertEquals(0f, EntryGateOverlayModel.from(9_000, 5_000, false, diagnostic).progress)
    }

    @Test fun capturedStatusDoesNotDependOnClassifier() {
        val captured = diagnostic.copy(
            reportStatus = OverlayReportStatus.CAPTURED,
            canCopyCurrentReport = true
        )
        assertTrue(captured.reportStatus == OverlayReportStatus.CAPTURED)
        assertEquals(EntryGateSurface.UNKNOWN, captured.surface)
    }
}
