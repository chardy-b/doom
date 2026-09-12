package com.chardy.doom

internal data class OverlayCallbackToken(val ticket: GateTicket, val epoch: Long)

/** Main-thread-owned authority for one physical overlay instance. */
internal class OverlayCallbackGuard {
    private var nextEpoch = 0L
    private var current: OverlayCallbackToken? = null
    private var closing = false
    private var detached = true

    fun open(ticket: GateTicket): OverlayCallbackToken {
        val token = OverlayCallbackToken(ticket, ++nextEpoch)
        current = token
        closing = false
        detached = false
        return token
    }

    fun acceptsVisible(token: OverlayCallbackToken): Boolean =
        token == current && !closing && !detached

    fun acceptsRemoval(token: OverlayCallbackToken): Boolean =
        token == current && !detached

    fun beginClosing(token: OverlayCallbackToken): Boolean {
        if (token != current || detached) return false
        closing = true
        return true
    }

    fun detached(token: OverlayCallbackToken) {
        if (token == current) {
            detached = true
            closing = true
            current = null
        }
    }

    fun invalidateVisible() {
        if (!detached) closing = true
    }

}
