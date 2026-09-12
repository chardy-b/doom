package com.chardy.doom

internal enum class OverlayRemovalAction {
    HOME, COMPLETE, NAVIGATE_MESSAGES, BYPASS, PRESERVE_REPORT, RESET_OUTSIDE
}
internal enum class OverlayRemovalDecision { RETRY, DISABLE_SERVICE }

/** Pure retry/action policy: no post-removal action is released before confirmed detachment. */
internal class OverlayRemovalPolicy(private val maxAttempts: Int = 20) {
    private var attempts = 0
    private var pending: OverlayRemovalAction? = null
    private var externalActionVetoed = false

    init {
        require(maxAttempts > 0)
    }

    fun request(action: OverlayRemovalAction) {
        if (externalActionVetoed && action != OverlayRemovalAction.BYPASS &&
            action != OverlayRemovalAction.RESET_OUTSIDE
        ) return
        val current = pending
        if (current == null || priority(action) > priority(current)) pending = action
    }

    /** Irreversible for this physical-removal episode: no external action may escape safety cleanup. */
    fun requestSafetyCleanup(action: OverlayRemovalAction) {
        require(action == OverlayRemovalAction.BYPASS || action == OverlayRemovalAction.RESET_OUTSIDE)
        externalActionVetoed = true
        if (action == OverlayRemovalAction.RESET_OUTSIDE ||
            pending == null || pending == OverlayRemovalAction.COMPLETE ||
            pending == OverlayRemovalAction.NAVIGATE_MESSAGES ||
            pending == OverlayRemovalAction.PRESERVE_REPORT
        ) pending = action
    }

    fun failedAttempt(): OverlayRemovalDecision {
        attempts++
        return if (attempts < maxAttempts) {
            OverlayRemovalDecision.RETRY
        } else {
            externalActionVetoed = true
            pending = when (pending) {
                OverlayRemovalAction.RESET_OUTSIDE -> OverlayRemovalAction.RESET_OUTSIDE
                else -> OverlayRemovalAction.BYPASS
            }
            OverlayRemovalDecision.DISABLE_SERVICE
        }
    }

    fun confirmedDetached(): OverlayRemovalAction? {
        val action = if (externalActionVetoed) {
            if (pending == OverlayRemovalAction.RESET_OUTSIDE) {
                OverlayRemovalAction.RESET_OUTSIDE
            } else if (pending == null) null else OverlayRemovalAction.BYPASS
        } else pending
        attempts = 0
        pending = null
        externalActionVetoed = false
        return action
    }

    /** Safety/explicit actions beat preservation; a verified app return beats timer completion. */
    private fun priority(action: OverlayRemovalAction) = when (action) {
        OverlayRemovalAction.COMPLETE -> 0
        OverlayRemovalAction.NAVIGATE_MESSAGES -> 1
        OverlayRemovalAction.PRESERVE_REPORT -> 2
        OverlayRemovalAction.BYPASS -> 3
        OverlayRemovalAction.HOME -> 4
        OverlayRemovalAction.RESET_OUTSIDE -> 5
    }
}
