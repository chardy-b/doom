package com.chardy.doom

/**
 * Pure policy for the active-root safety check while the diagnostic overlay is visible.
 *
 * A package-attributed foreign root is confirmed immediately. A null package is only
 * unattributed platform uncertainty, so it receives a short monotonic grace period before
 * fail-open cleanup. The grace period is measured from the last confirmed-safe moment, which
 * is initialized at successful overlay admission and refreshed by a known Instagram or
 * verified Doom root.
 */
internal enum class OverlayForegroundDecision {
    KEEP,
    KEEP_UNCERTAIN,
    FAIL_OPEN,
}

internal class OverlayForegroundWatchdog(
    private val instagramPackage: String,
    private val doomPackage: String,
    private val uncertaintyGraceMs: Long = 150L,
) {
    init {
        require(instagramPackage.isNotEmpty())
        require(doomPackage.isNotEmpty())
        require(uncertaintyGraceMs in 1L..249L)
    }

    private var lastSafeAtMs: Long? = null

    fun reset(shownAtMs: Long) {
        lastSafeAtMs = shownAtMs.takeIf { it >= 0L }
    }

    fun observe(
        nowMs: Long,
        packageName: String?,
        verifiedDoomReturn: Boolean,
    ): OverlayForegroundDecision {
        if (nowMs < 0L) return OverlayForegroundDecision.FAIL_OPEN
        if (packageName == instagramPackage ||
            (packageName == doomPackage && verifiedDoomReturn)
        ) {
            val lastSafe = lastSafeAtMs
            if (lastSafe != null && nowMs < lastSafe) return OverlayForegroundDecision.FAIL_OPEN
            lastSafeAtMs = nowMs
            return OverlayForegroundDecision.KEEP
        }
        if (packageName != null) {
            return OverlayForegroundDecision.FAIL_OPEN
        }

        val lastSafe = lastSafeAtMs ?: return OverlayForegroundDecision.FAIL_OPEN
        if (nowMs < lastSafe) return OverlayForegroundDecision.FAIL_OPEN
        return if (nowMs - lastSafe < uncertaintyGraceMs) {
            OverlayForegroundDecision.KEEP_UNCERTAIN
        } else {
            OverlayForegroundDecision.FAIL_OPEN
        }
    }
}
