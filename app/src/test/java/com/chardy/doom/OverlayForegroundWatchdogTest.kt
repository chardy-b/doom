package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayForegroundWatchdogTest {
    private fun policy(shownAtMs: Long = 1_000L) = OverlayForegroundWatchdog(
        instagramPackage = "com.instagram.android",
        doomPackage = "com.chardyb.doom",
    ).also { it.reset(shownAtMs) }

    @Test fun nullImmediatelyAfterShowUsesTheShowTimestamp() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_000L, null, verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_001L, null, verifiedDoomReturn = false),
        )
    }

    @Test fun uncertaintyFailsOpenAtTheExactBoundFromLastSafeTime() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_149L, null, verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(1_150L, null, verifiedDoomReturn = false),
        )
    }

    @Test fun confirmedInstagramRefreshesTheSafeMoment() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.KEEP,
            watchdog.observe(1_100L, "com.instagram.android", verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_249L, null, verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(1_250L, null, verifiedDoomReturn = false),
        )
    }

    @Test fun attributedForeignRootFailsOpenImmediately() {
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            policy().observe(1_050L, "com.example.foreign", verifiedDoomReturn = false),
        )
    }

    @Test fun foreignRootDuringUncertaintyAndUnverifiedDoomFailImmediately() {
        val foreignWatchdog = policy()

        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            foreignWatchdog.observe(1_050L, "com.example.foreign", verifiedDoomReturn = false),
        )
        val doomWatchdog = policy()
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            doomWatchdog.observe(1_050L, "com.chardyb.doom", verifiedDoomReturn = false),
        )
    }

    @Test fun verifiedDoomReturnRefreshesTheSafeMoment() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.KEEP,
            watchdog.observe(1_100L, "com.chardyb.doom", verifiedDoomReturn = true),
        )
        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_249L, null, verifiedDoomReturn = false),
        )
    }

    @Test fun rollbackAndNegativeTimeFailOpenWithoutRefreshingSafety() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(999L, null, verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(-1L, "com.instagram.android", verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(1_149L, null, verifiedDoomReturn = false),
        )
    }

    @Test fun resetStartsASeparateEpisodeFromItsNewShownAt() {
        val watchdog = policy()

        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(1_150L, null, verifiedDoomReturn = false),
        )
        watchdog.reset(2_000L)
        assertEquals(
            OverlayForegroundDecision.KEEP_UNCERTAIN,
            watchdog.observe(2_149L, null, verifiedDoomReturn = false),
        )
        assertEquals(
            OverlayForegroundDecision.FAIL_OPEN,
            watchdog.observe(2_150L, null, verifiedDoomReturn = false),
        )
    }
}
