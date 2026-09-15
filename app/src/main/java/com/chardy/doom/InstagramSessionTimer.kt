package com.chardy.doom

internal data class InstagramTimerModel(
    val text: String,
    val contentDescription: String,
    val collapsed: Boolean,
    val reduceMotion: Boolean,
)

/** Process-local continuous-session clock. It deliberately owns no Android View state. */
internal class InstagramSessionTimer(private val monotonicNowMs: () -> Long) {
    private var startedAtMs: Long? = null
    private var lastNowMs: Long? = null
    private var lastRenderedSecond: Long? = null
    var collapsed: Boolean = false
        private set

    val running: Boolean get() = startedAtMs != null

    fun observeVerifiedInstagram(consent: Boolean, gateConsent: Boolean, connected: Boolean): Boolean {
        if (!consent || !gateConsent || !connected) return false
        val now = safeNow() ?: return false
        if (startedAtMs == null) startedAtMs = now
        return true
    }

    fun endSession() {
        startedAtMs = null
        lastNowMs = null
        lastRenderedSecond = null
        collapsed = false
    }

    fun toggle() { if (running) collapsed = !collapsed }

    fun elapsedSeconds(): Long? {
        val start = startedAtMs ?: return null
        val now = safeNow() ?: return null
        return ((now - start) / 1_000L).coerceAtLeast(0L)
    }

    fun model(reduceMotion: Boolean, force: Boolean = false): InstagramTimerModel? {
        val seconds = elapsedSeconds() ?: return null
        if (!force && lastRenderedSecond == seconds) return null
        lastRenderedSecond = seconds
        val text = format(seconds)
        fun unit(value: Long, name: String) = "$value $name" + if (value == 1L) "" else "s"
        val spoken = buildList {
            if (seconds >= 3600) add(unit(seconds / 3600, "hour"))
            if (seconds >= 60) add(unit((seconds % 3600) / 60, "minute"))
            add(unit(seconds % 60, "second"))
        }.joinToString(", ")
        return InstagramTimerModel(text, "Instagram time, $spoken", collapsed, reduceMotion)
    }

    fun delayToNextSecond(): Long {
        val start = startedAtMs ?: return 1_000L
        val now = safeNow() ?: return 1_000L
        val remainder = (now - start) % 1_000L
        return if (remainder == 0L) 1_000L else 1_000L - remainder
    }

    private fun safeNow(): Long? {
        val now = try { monotonicNowMs() } catch (_: RuntimeException) { endSession(); return null }
        if (now < 0L || lastNowMs?.let { now < it } == true) {
            endSession()
            return null
        }
        lastNowMs = now
        return now
    }

    companion object {
        fun format(seconds: Long): String {
            val safe = seconds.coerceAtLeast(0L)
            val hours = safe / 3600
            val minutes = (safe % 3600) / 60
            val remainder = safe % 60
            return if (hours == 0L) "%d:%02d".format(minutes, remainder)
            else "%d:%02d:%02d".format(hours, minutes, remainder)
        }
    }
}
