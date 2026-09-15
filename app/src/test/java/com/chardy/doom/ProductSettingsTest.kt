package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class ProductSettingsTest {
    @Test fun defaultsAndPresetsAreExact() {
        assertEquals(ReminderSettings(false, 10, 1), ReminderSettings())
        assertEquals(listOf(10, 20, 30), listOf(10, 20, 30))
        assertEquals(listOf(1, 5, 15), listOf(1, 5, 15))
    }

    @Test fun customDurationRoundsUpAndClamps() {
        mapOf("1" to 10, "10" to 10, "11" to 20, "119" to 120, Long.MAX_VALUE.toString() to 120)
            .forEach { (raw, expected) -> assertEquals(expected, ReminderSettingsStore.normalizeDuration(raw)) }
    }

    @Test fun invalidCustomAndStoredValuesAreRejected() {
        listOf("", "no", "0", "-1", "999999999999999999999999").forEach {
            assertNull(ReminderSettingsStore.normalizeDuration(it))
        }
        listOf(null, "9", "11", "121", Int.MAX_VALUE.toString()).forEach {
            assertNull(ReminderSettingsStore.validDuration(it))
        }
        assertEquals(1, ReminderSettingsStore.validSuppression("1"))
        assertEquals(1440, ReminderSettingsStore.validSuppression("1440"))
        listOf(null, "0", "1441", Long.MAX_VALUE.toString(), "x").forEach {
            assertNull(ReminderSettingsStore.validSuppression(it))
        }
    }
}
