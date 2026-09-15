package com.chardy.doom

internal data class EntryGateOverlayModel(
    val frame: BreathingFrame,
    val reduceMotion: Boolean,
) {
    companion object {
        fun from(remainingMs: Long, durationMs: Long, reduceMotion: Boolean): EntryGateOverlayModel {
            val bounded=remainingMs.coerceIn(0L,durationMs)
            return EntryGateOverlayModel(BreathingVisuals.frame(durationMs-bounded,durationMs),reduceMotion)
        }
    }
}

// Diagnostic state remains available on the Debug destination, never on the production overlay.
internal enum class OverlayReportStatus { CAPTURED, UNAVAILABLE }
internal enum class OverlayCopyResult { COPIED, UNAVAILABLE }
internal data class OverlayDiagnosticStatus(val surface:EntryGateSurface,val reportStatus:OverlayReportStatus,val canCopyCurrentReport:Boolean)
