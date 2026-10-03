package com.chardy.doom

internal data class EntryGateOverlayModel(
    val frame: BreathingFrame,
    val reduceMotion: Boolean,
    val elapsedMs: Long,
    val durationMs: Long,
) {
    companion object {
        fun from(remainingMs: Long, durationMs: Long, reduceMotion: Boolean): EntryGateOverlayModel {
            val duration = BreathingVisuals.duration(durationMs)
            val bounded=remainingMs.coerceIn(0L,duration)
            val elapsed = duration - bounded
            return EntryGateOverlayModel(
                BreathingVisuals.frame(elapsed, duration), reduceMotion, elapsed, duration,
            )
        }
    }
}

internal object BreathingAnimationTimeline {
    fun elapsedAt(anchorElapsedMs: Long, anchorNanos: Long, frameNanos: Long, durationMs: Long): Long {
        val deltaMs = ((frameNanos - anchorNanos).coerceAtLeast(0L) / 1_000_000L)
        val duration = BreathingVisuals.duration(durationMs)
        val anchor = anchorElapsedMs.coerceIn(0L, duration)
        return if (deltaMs >= duration - anchor) duration else anchor + deltaMs
    }
}

// Diagnostic state remains available on the Debug destination, never on the production overlay.
internal enum class OverlayReportStatus { CAPTURED, UNAVAILABLE }
internal enum class OverlayCopyResult { COPIED, UNAVAILABLE }
internal data class OverlayDiagnosticStatus(val surface:EntryGateSurface,val reportStatus:OverlayReportStatus,val canCopyCurrentReport:Boolean)
