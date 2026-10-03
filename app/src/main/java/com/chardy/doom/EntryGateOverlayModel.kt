package com.chardy.doom

internal data class EntryGateOverlayModel(
    val frame: BreathingFrame,
    val reduceMotion: Boolean,
    val elapsedMs: Long,
    val durationMs: Long,
) {
    companion object {
        fun from(remainingMs: Long, durationMs: Long, reduceMotion: Boolean): EntryGateOverlayModel {
            val bounded=remainingMs.coerceIn(0L,durationMs)
            val elapsed = durationMs - bounded
            return EntryGateOverlayModel(
                BreathingVisuals.frame(elapsed, durationMs), reduceMotion, elapsed, durationMs,
            )
        }
    }
}

internal object BreathingAnimationTimeline {
    fun elapsedAt(anchorElapsedMs: Long, anchorNanos: Long, frameNanos: Long, durationMs: Long): Long {
        val deltaMs = ((frameNanos - anchorNanos).coerceAtLeast(0L) / 1_000_000L)
        return (anchorElapsedMs + deltaMs).coerceIn(0L, durationMs)
    }
}

// Diagnostic state remains available on the Debug destination, never on the production overlay.
internal enum class OverlayReportStatus { CAPTURED, UNAVAILABLE }
internal enum class OverlayCopyResult { COPIED, UNAVAILABLE }
internal data class OverlayDiagnosticStatus(val surface:EntryGateSurface,val reportStatus:OverlayReportStatus,val canCopyCurrentReport:Boolean)
