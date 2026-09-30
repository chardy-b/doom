package com.chardy.doom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerDismissalRootTest {
    @Test fun onlyAnOrdinaryForeignRootClearsVisitDismissal() {
        val keyboards = setOf("com.example.keyboard")
        assertFalse(isOrdinaryForeignForTimerDismissal("com.instagram.android", "com.chardyb.doom", keyboards))
        assertFalse(isOrdinaryForeignForTimerDismissal("com.chardyb.doom", "com.chardyb.doom", keyboards))
        assertFalse(isOrdinaryForeignForTimerDismissal("com.android.systemui", "com.chardyb.doom", keyboards))
        assertFalse(isOrdinaryForeignForTimerDismissal(SAFE_SYSTEM_UI_PACKAGE, "com.chardyb.doom", keyboards))
        assertFalse(isOrdinaryForeignForTimerDismissal(SAFE_RECOGNIZED_IME_PACKAGE, "com.chardyb.doom", keyboards))
        assertFalse(isOrdinaryForeignForTimerDismissal("com.example.keyboard", "com.chardyb.doom", keyboards))
        assertTrue(isOrdinaryForeignForTimerDismissal("com.example.browser", "com.chardyb.doom", keyboards))
    }
}
