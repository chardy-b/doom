package com.chardy.doom

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only consent is persisted. Samples and tester labels exist only in this process. */
object Observation {
    // Count-only consent did not cover this diagnostic; require a fresh explicit opt-in.
    private const val CONSENT_KEY = "structural_fingerprints_v1"
    var connected by mutableStateOf(false)
        internal set
    var samples by mutableStateOf(StructuralSamples())
        private set
    var consent by mutableStateOf(false)
        private set

    fun load(context: Context) {
        consent = context.getSharedPreferences("consent", Context.MODE_PRIVATE).getBoolean(CONSENT_KEY, false)
        if (!consent) clear()
    }

    fun accept(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit()
            .remove("accepted").putBoolean(CONSENT_KEY, accepted).apply()
        consent = accepted
        if (!accepted) {
            clear()
            DoomAccessibilityService.disableObservation()
        }
    }

    internal fun record(sample: StructuralFingerprint?) {
        if (consent && connected) samples = samples.withCurrent(sample)
    }

    fun label(label: SampleLabel) {
        if (consent && connected) samples = samples.label(label)
    }

    fun clear() { samples = samples.clear() }
}
