package com.chardy.doom

internal enum class OverlayRemovalAction { HOME, COMPLETE, BYPASS, RESET_OUTSIDE }
internal enum class OverlayRemovalDecision { RETRY, DISABLE_SERVICE }

/** Pure retry/action policy: no post-removal action is released before confirmed detachment. */
internal class OverlayRemovalPolicy(private val maxAttempts: Int = 20) {
    private var attempts = 0
    private var pending: OverlayRemovalAction? = null

    init {
        require(maxAttempts > 0)
    }

    fun request(action: OverlayRemovalAction) {
        val current = pending
        if (current == null || priority(action) > priority(current)) pending = action
    }

    fun failedAttempt(): OverlayRemovalDecision {
        attempts++
        return if (attempts < maxAttempts) {
            OverlayRemovalDecision.RETRY
        } else {
            OverlayRemovalDecision.DISABLE_SERVICE
        }
    }

    fun confirmedDetached(): OverlayRemovalAction? {
        val action = pending
        attempts = 0
        pending = null
        return action
    }

    private fun priority(action: OverlayRemovalAction) = when (action) {
        OverlayRemovalAction.HOME -> 0
        OverlayRemovalAction.COMPLETE -> 1
        OverlayRemovalAction.BYPASS -> 2
        OverlayRemovalAction.RESET_OUTSIDE -> 3
    }
}
