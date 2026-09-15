package com.chardy.doom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread state. Only two consents and the timer-enabled preference are persisted. */
object Observation {
    private const val CONSENT_KEY = "sanitized_structural_report_v1"
    private const val ENTRY_GATE_CONSENT_KEY = "instagram_diagnostic_entry_gate_v1"
    internal const val SESSION_TIMER_ENABLED_KEY = "instagram_session_timer_enabled_v1"
    var gateConsent by mutableStateOf(false)
        private set
    var sessionTimerEnabled by mutableStateOf(true)
    var reminderSettings by mutableStateOf(ReminderSettings())
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

    internal fun overlayDiagnosticStatus(): OverlayDiagnosticStatus {
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
        reminderSettings = ReminderSettingsStore.read(context)
        val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)
        consent = prefs.getBoolean(CONSENT_KEY, false)
        gateConsent = prefs.getBoolean(ENTRY_GATE_CONSENT_KEY, false)
        sessionTimerEnabled = prefs.getBoolean(SESSION_TIMER_ENABLED_KEY, true)
        if (!consent) clear()
    }

    fun setSessionTimerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .putBoolean(SESSION_TIMER_ENABLED_KEY, enabled).apply()
        sessionTimerEnabled = enabled
        DoomAccessibilityService.sessionTimerPreferenceChanged(enabled)
    }

    fun updateReminderSettings(context: Context, settings: ReminderSettings) {
        val disableLiveReminder = reminderSettings.enabled && !settings.enabled
        ReminderSettingsStore.write(context, settings)
        reminderSettings = settings
        if (disableLiveReminder) DoomAccessibilityService.cancelEntryGate()
    }

    fun setGateConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .putBoolean(ENTRY_GATE_CONSENT_KEY, accepted).apply()
        gateConsent = accepted
        if (!accepted) {
            RemovalTraceStore.process.clear()
            DoomAccessibilityService.cancelEntryGate()
        }
    }

    fun accept(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .remove("accepted").remove("structural_fingerprints_v1").putBoolean(CONSENT_KEY, accepted).apply()
        if (consent != accepted) clear()
        consent = accepted
        if (!accepted) {
            RemovalTraceStore.process.clear()
            DoomAccessibilityService.disableObservation()
        }
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

    internal enum class RemovalTraceCopyResult { COPIED, UNAVAILABLE }

    /** Explicit, typed trace copy. It does not require service connectivity. */
    internal fun copyRemovalTrace(context: Context): RemovalTraceCopyResult {
        if (!consent || !gateConsent) return RemovalTraceCopyResult.UNAVAILABLE
        val snapshot = RemovalTraceStore.process.snapshot() ?: return RemovalTraceCopyResult.UNAVAILABLE
        return if (writeSensitiveClipboard(context, "Doom removal trace", snapshot.serializeAscii())) {
            RemovalTraceCopyResult.COPIED
        } else {
            RemovalTraceCopyResult.UNAVAILABLE
        }
    }

    /** Explicit overlay action. It never reveals the report or changes the reviewed-copy state. */
    internal fun copyCurrentReportFromOverlay(context: Context): OverlayCopyResult {
        if (!consent || !connected) return OverlayCopyResult.UNAVAILABLE
        val current = report ?: return OverlayCopyResult.UNAVAILABLE
        return if (writeClipboard(context, current, markReviewedCopy = false)) OverlayCopyResult.COPIED
        else OverlayCopyResult.UNAVAILABLE
    }

    private fun writeClipboard(
        context: Context,
        current: SanitizedStructuralReport,
        markReviewedCopy: Boolean
    ): Boolean = writeSensitiveClipboard(context, "Doom sanitized structural report", current.text).also {
        if (it && markReviewedCopy) copied = true
    }

    private fun writeSensitiveClipboard(context: Context, label: String, text: String): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(label, text)
        // Suppress supported system clipboard previews; this does not keep the copy in Doom.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
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
