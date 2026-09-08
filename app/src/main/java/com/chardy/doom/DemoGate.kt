package com.chardy.doom

/** Public deterministic seam for the Doom-owned demo, not an Instagram policy. */
class DemoGate(private val durationMillis: Long = 5_000) {
    init { require(durationMillis in 1..120_000) }
    enum class Screen { HOME, BREATHING, FEED, MESSAGES }
    var screen = Screen.HOME
        private set
    var generation = 0L
        private set
    private var startedAt = 0L

    fun start(now: Long): Long {
        generation++
        startedAt = now
        screen = Screen.BREATHING
        return generation
    }

    fun remaining(now: Long): Long =
        (durationMillis - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)

    fun tick(now: Long, callbackGeneration: Long): Boolean {
        if (callbackGeneration != generation || screen != Screen.BREATHING) return false
        if (remaining(now) == 0L) screen = Screen.FEED
        return screen == Screen.FEED
    }

    fun leave(destination: Screen = Screen.HOME) {
        require(destination == Screen.HOME || destination == Screen.MESSAGES)
        generation++
        screen = destination
    }

    fun background() {
        if (screen == Screen.BREATHING) leave()
    }
}
