package com.chardy.doom

import android.content.ComponentName
import android.content.Context
import android.provider.Settings


data class ReminderSettings(
    val enabled: Boolean = false,
    val durationSeconds: Int = 10,
    val suppressionMinutes: Int = 1,
)

internal object ReminderSettingsStore {
    val durationPresets = listOf(10, 20, 30)
    val suppressionPresets = listOf(1, 5, 15)

    private const val FILE = "reminder_settings_v1"
    private const val ENABLED = "enabled"
    private const val DURATION = "duration_seconds"
    private const val SUPPRESSION = "suppression_minutes"

    fun read(context: Context): ReminderSettings {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ReminderSettings(
            enabled = preferences.getBoolean(ENABLED, false),
            durationSeconds = validDuration(preferences.getString(DURATION, null)) ?: 10,
            suppressionMinutes = validSuppression(preferences.getString(SUPPRESSION, null)) ?: 1,
        )
    }

    fun write(context: Context, settings: ReminderSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, settings.enabled)
            .putString(DURATION, settings.durationSeconds.toString())
            .putString(SUPPRESSION, settings.suppressionMinutes.toString())
            .apply()
    }

    fun normalizeDuration(raw: String): Int? {
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds <= 0L) return null
        return (((seconds.coerceAtMost(120L) + 9L) / 10L) * 10L).toInt()
    }

    fun validDuration(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it in 10..120 && it % 10 == 0 }
    fun validSuppression(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it in 1..1440 }
}

internal fun isAccessibilityServiceEnabled(context: Context, service: ComponentName): Boolean {
    val setting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return setting.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == service }
}
