package com.chardy.doom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread state. Only fresh consent is persisted; reports and UI state are process-local. */
object Observation {
    private const val CONSENT_KEY = "sanitized_structural_report_v1"
    private const val ENTRY_GATE_CONSENT_KEY = "instagram_diagnostic_entry_gate_v1"
    var gateConsent by mutableStateOf(false)
        private set
    var entryGateState by mutableStateOf(EntryGateState.OUTSIDE)
        internal set
    var connected by mutableStateOf(false)
        internal set
    var report by mutableStateOf<SanitizedStructuralReport?>(null)
        private set
    var revealed by mutableStateOf(false)
        private set
    var copied by mutableStateOf(false)
        private set
    var consent by mutableStateOf(false)
        private set
    val canReveal get() = consent && connected && report != null
    val canCopy get() = canReveal && revealed
    val shadowPrediction: InstagramSurface
        get() = InstagramSurfaceShadowClassifier.classify(report)

    fun overlayDiagnosticStatus(): OverlayDiagnosticStatus {
        val eligible = consent && connected && report != null
        return OverlayDiagnosticStatus(
            surface = when (shadowPrediction) {
                InstagramSurface.FEED -> EntryGateSurface.FEED
                InstagramSurface.REELS -> EntryGateSurface.REELS
                InstagramSurface.STORIES -> EntryGateSurface.STORIES
                InstagramSurface.MESSAGING -> EntryGateSurface.MESSAGING
                InstagramSurface.UNKNOWN -> EntryGateSurface.UNKNOWN
            },
            reportStatus = if (eligible) OverlayReportStatus.CAPTURED else OverlayReportStatus.UNAVAILABLE,
            canCopyCurrentReport = eligible
        )
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)
        consent = prefs.getBoolean(CONSENT_KEY, false)
        gateConsent = prefs.getBoolean(ENTRY_GATE_CONSENT_KEY, false)
        if (!consent) clear()
    }

    fun setGateConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .putBoolean(ENTRY_GATE_CONSENT_KEY, accepted).apply()
        gateConsent = accepted
        if (!accepted) DoomAccessibilityService.cancelEntryGate()
    }

    fun accept(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .remove("accepted").remove("structural_fingerprints_v1").putBoolean(CONSENT_KEY, accepted).apply()
        if (consent != accepted) clear()
        consent = accepted
        if (!accepted) DoomAccessibilityService.disableObservation()
    }

    internal fun record(sample: SanitizedStructuralReport?) {
        if (!consent || !connected) return
        clear()
        report = sample
    }

    fun revealReport() {
        if (canReveal) revealed = true
    }

    fun copyReport(context: Context) {
        if (!canCopy) return
        val current = report ?: return
        copied = false
        writeClipboard(context, current, markReviewedCopy = true)
    }

    /** Explicit overlay action. It never reveals the report or changes the reviewed-copy state. */
    fun copyCurrentReportFromOverlay(context: Context): OverlayCopyResult {
        if (!consent || !connected) return OverlayCopyResult.UNAVAILABLE
        val current = report ?: return OverlayCopyResult.UNAVAILABLE
        return if (writeClipboard(context, current, markReviewedCopy = false)) OverlayCopyResult.COPIED
        else OverlayCopyResult.UNAVAILABLE
    }

    private fun writeClipboard(
        context: Context,
        current: SanitizedStructuralReport,
        markReviewedCopy: Boolean
    ): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText("Doom sanitized structural report", current.text)
        // Suppress supported system clipboard previews; this does not keep the copy in Doom.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        if (markReviewedCopy) copied = true
        true
    } catch (_: RuntimeException) {
        false
    }

    fun clear() {
        report = null
        revealed = false
        copied = false
    }
}
