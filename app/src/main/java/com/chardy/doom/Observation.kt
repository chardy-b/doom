package com.chardy.doom

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only consent is persisted. A single bounded count snapshot stays in memory. */
object Observation {
    data class Counts(val nodes: Int, val resourceIds: Int, val clickable: Int, val truncated: Boolean)
    var connected by mutableStateOf(false)
        internal set
    var counts by mutableStateOf<Counts?>(null)
        internal set
    var consent by mutableStateOf(false)
        private set

    fun load(context: Context) {
        consent = context.getSharedPreferences("consent", Context.MODE_PRIVATE).getBoolean("accepted", false)
    }

    fun accept(context: Context, accepted: Boolean) {
        context.getSharedPreferences("consent", Context.MODE_PRIVATE).edit().putBoolean("accepted", accepted).apply()
        consent = accepted
        if (!accepted) {
            clear()
            DoomAccessibilityService.disableObservation()
        }
    }

    fun clear() { counts = null }
}
