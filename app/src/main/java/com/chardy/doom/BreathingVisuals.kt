package com.chardy.doom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

internal enum class BreathPhase { IN, OUT }
internal data class BreathingFrame(
    val phase: BreathPhase,
    val label: String,
    val bloom: Float,
    val segments: List<Float>,
)

internal const val BREATH_MS = 10_000L

internal object BreathingVisuals {
    const val INK = 0xFF241326.toInt()
    const val PANEL = 0xFF351B32.toInt()
    const val ORANGE = 0xFFC75B32.toInt()
    const val GOLD = 0xFFD6A84B.toInt()
    const val PAPER = 0xFFFFF1D6.toInt()

    data class Cell(val x: Int, val y: Int, val layer: Int)

    fun frame(elapsedMs: Long, durationMs: Long): BreathingFrame {
        val duration = durationMs.coerceAtLeast(BREATH_MS)
        val elapsed = elapsedMs.coerceIn(0L, duration)
        val cycleElapsed = if (elapsed == duration) BREATH_MS else elapsed % BREATH_MS
        val phase = if (cycleElapsed < 4_000L) BreathPhase.IN else BreathPhase.OUT
        val local = if (phase == BreathPhase.IN) cycleElapsed / 4_000f else (cycleElapsed - 4_000L) / 6_000f
        val eased = ((1.0 - cos(local.coerceIn(0f, 1f) * PI)) / 2.0).toFloat()
        val bloom = if (phase == BreathPhase.IN) eased else 1f - eased
        val count = (duration / BREATH_MS).toInt()
        val segments = List(count) { index ->
            ((elapsed - index * BREATH_MS).toFloat() / BREATH_MS).coerceIn(0f, 1f)
        }
        return BreathingFrame(phase, if (phase == BreathPhase.IN) "Breathe in" else "Breathe out", bloom, segments)
    }

    fun cells(progress: Float): List<Cell> {
        val radius = 3 + (progress.coerceIn(0f, 1f) * 3).toInt()
        return (-radius..radius).flatMap { x ->
            (-radius..radius).mapNotNull { y ->
                val distance = abs(x) + abs(y)
                if (distance <= radius + 1) Cell(x, y, when {
                    distance <= 1 -> 3
                    distance <= radius / 2 + 1 -> 2
                    distance <= radius -> 1
                    else -> 0
                }) else null
            }
        }
    }

    fun staticProgress() = 0.72f
    fun contrastRatio(foreground: Int, background: Int): Double {
        fun channel(value: Int): Double { val c=(value and 0xFF)/255.0; return if(c<=.03928)c/12.92 else Math.pow((c+.055)/1.055,2.4) }
        fun luminance(color:Int)=.2126*channel(color shr 16)+.7152*channel(color shr 8)+.0722*channel(color)
        val a=luminance(foreground); val b=luminance(background)
        return (maxOf(a,b)+.05)/(minOf(a,b)+.05)
    }
}
