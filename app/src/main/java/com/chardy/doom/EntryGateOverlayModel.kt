package com.chardy.doom

internal enum class OverlayReportStatus { CAPTURED, UNAVAILABLE }
internal enum class OverlayCopyResult { COPIED, UNAVAILABLE }

internal data class OverlayDiagnosticStatus(
    val surface: EntryGateSurface,
    val reportStatus: OverlayReportStatus,
    val canCopyCurrentReport: Boolean
)

internal data class EntryGateOverlayModel(
    val remainingSeconds: Int,
    val progress: Float,
    val reduceMotion: Boolean,
    val diagnostic: OverlayDiagnosticStatus
) {
    companion object {
        fun from(
            remainingMs: Long,
            durationMs: Long,
            reduceMotion: Boolean,
            diagnostic: OverlayDiagnosticStatus
        ): EntryGateOverlayModel {
            val boundedRemaining = remainingMs.coerceIn(0L, durationMs)
            val progress = if (durationMs == 0L) 1f
            else (1f - boundedRemaining.toFloat() / durationMs).coerceIn(0f, 1f)
            val seconds = if (boundedRemaining == 0L) 0 else ((boundedRemaining + 999L) / 1000L).toInt()
            return EntryGateOverlayModel(seconds, progress, reduceMotion, diagnostic)
        }
    }
}
