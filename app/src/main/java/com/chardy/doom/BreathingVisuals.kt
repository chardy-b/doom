package com.chardy.doom

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

internal object BreathingVisuals {
    const val INK = 0xFF171B25.toInt()
    const val PAPER = 0xFFF3E7CF.toInt()
    const val JADE = 0xFF73B39C.toInt()
    const val PANEL = 0xFF252B37.toInt()

    data class Cell(val x: Int, val y: Int, val center: Boolean)

    fun clampedProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

    fun radius(progress: Float): Int =
        2 + (sin(clampedProgress(progress) * PI).toFloat() * 2).toInt()

    fun cells(progress: Float): List<Cell> {
        val radius = radius(progress)
        return (-radius..radius).flatMap { x ->
            (-radius..radius).mapNotNull { y ->
                if (abs(x) + abs(y) <= radius + 1) Cell(x, y, x == 0 && y == 0) else null
            }
        }
    }

    fun staticProgress(): Float = 0.5f

    fun countdownSeconds(remainingMs: Long, durationMs: Long = 5_000L): Int {
        val bounded = remainingMs.coerceIn(0L, durationMs)
        return if (bounded == 0L) 0 else ((bounded + 999L) / 1000L).toInt()
    }

    fun contrastRatio(foreground: Int, background: Int): Double {
        fun channel(value: Int): Double {
            val c = (value and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(foreground shr 16) + 0.7152 * channel(foreground shr 8) +
            0.0722 * channel(foreground)
        val backgroundLuminance = 0.2126 * channel(background shr 16) +
            0.7152 * channel(background shr 8) + 0.0722 * channel(background)
        val lighter = maxOf(luminance, backgroundLuminance)
        val darker = minOf(luminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
