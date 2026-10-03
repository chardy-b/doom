package com.chardy.doom

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal enum class BreathPhase { IN, OUT }
internal data class BreathingFrame(
    val phase: BreathPhase,
    val label: String,
    val bloom: Float,
    val segments: List<Float>,
)

internal const val BREATH_MS = 10_000L

internal enum class BloomColorRole { PAPER, GOLD, BURNT_ORANGE, AUBERGINE }

/** A float geometry primitive shared by native and Compose renderers. */
internal data class BloomCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val alpha: Float,
    val role: BloomColorRole,
    val color: Int,
    val gridX: Int,
    val gridY: Int,
)

internal object BreathingVisuals {
    const val INK = 0xFF241326.toInt()
    const val PANEL = 0xFF351B32.toInt()
    const val ORANGE = 0xFFC75B32.toInt()
    const val GOLD = 0xFFD6A84B.toInt()
    const val PAPER = 0xFFFFF1D6.toInt()
    const val AUBERGINE = 0xFF6E294D.toInt()
    const val GRID_CELLS = 32

    /** Quintic smootherstep with a bounded input and zero first/second endpoint slopes. */
    fun smootherstep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun frame(elapsedMs: Long, durationMs: Long): BreathingFrame {
        val duration = durationMs.coerceAtLeast(BREATH_MS)
        val elapsed = elapsedMs.coerceIn(0L, duration)
        val cycleElapsed = if (elapsed == duration) BREATH_MS else elapsed % BREATH_MS
        val phase = if (cycleElapsed < 4_000L) BreathPhase.IN else BreathPhase.OUT
        val local = if (phase == BreathPhase.IN) cycleElapsed / 4_000f else (cycleElapsed - 4_000L) / 6_000f
        val eased = smootherstep(local)
        val bloom = if (phase == BreathPhase.IN) eased else 1f - eased
        val count = (duration / BREATH_MS).toInt()
        val segments = List(count) { index ->
            ((elapsed - index * BREATH_MS).toFloat() / BREATH_MS).coerceIn(0f, 1f)
        }
        return BreathingFrame(phase, if (phase == BreathPhase.IN) "Breathe in" else "Breathe out", bloom, segments)
    }

    /** Generates a fixed 32-by-32 pixel lattice inside the largest circular canvas envelope. */
    fun geometry(progress: Float, width: Float, height: Float): List<BloomCell> {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return emptyList()
        val bloom = if (progress.isFinite()) progress.coerceIn(0f, 1f) else staticProgress()
        val canvasDiameter = min(width, height)
        val pitch = canvasDiameter / GRID_CELLS
        if (pitch <= 0f || !pitch.isFinite()) return emptyList()
        val extentDiameter = canvasDiameter * (0.20f + 0.675f * bloom)
        val centerX = width / 2f
        val centerY = height / 2f
        val leftEdge = centerX - extentDiameter / 2f
        val rightEdge = centerX + extentDiameter / 2f
        val topEdge = centerY - extentDiameter / 2f
        val bottomEdge = centerY + extentDiameter / 2f
        val radius = max(extentDiameter / 2f, pitch / 2f)
        val cells = ArrayList<BloomCell>(GRID_CELLS * GRID_CELLS)

        for (gridY in 0 until GRID_CELLS) for (gridX in 0 until GRID_CELLS) {
            val logicalX = gridX - GRID_CELLS / 2
            val logicalY = gridY - GRID_CELLS / 2
            val cellCenterX = centerX + logicalX * pitch
            val cellCenterY = centerY + logicalY * pitch
            val rawLeft = cellCenterX - pitch / 2f
            val rawTop = cellCenterY - pitch / 2f
            val rawRight = cellCenterX + pitch / 2f
            val rawBottom = cellCenterY + pitch / 2f
            // Keep the outer envelope exact while leaving proportional gaps between interior
            // pixels. A fixed two-pixel gap made this lattice read as a solid diamond on small
            // and high-density displays alike.
            val gapHalf = pitch * 0.07f
            val clippedLeft = if (rawLeft <= leftEdge) max(rawLeft, leftEdge) else rawLeft + gapHalf
            val clippedTop = if (rawTop <= topEdge) max(rawTop, topEdge) else rawTop + gapHalf
            val clippedRight = if (rawRight >= rightEdge) min(rawRight, rightEdge) else rawRight - gapHalf
            val clippedBottom = if (rawBottom >= bottomEdge) min(rawBottom, bottomEdge) else rawBottom - gapHalf
            if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) continue

            val deltaX = cellCenterX - centerX
            val deltaY = cellCenterY - centerY
            val radial = sqrt(deltaX * deltaX + deltaY * deltaY) / radius
            val delay = if (logicalX == 0 && logicalY == 0) 0f else 0.04f * radial.coerceIn(0f, 1f)
            val delayed = if (delay == 0f) bloom else ((bloom - delay) / (1f - delay)).coerceIn(0f, 1f)
            val edge = smootherstep(((1.08f - radial) / 0.16f).coerceIn(0f, 1f))
            val center = logicalX == 0 && logicalY == 0
            // A faint, deterministic gold seed keeps the minimum 20% bloom visibly bounded;
            // it fades continuously as the inhale grows instead of popping a new outer ring.
            val seed = 0.30f * (1f - bloom)
            val alpha = if (center) 1f else {
                // Every visible pixel has a meaningful floor. The Euclidean edge mask keeps
                // that floor inside the circular bloom rather than filling the square lattice.
                if (edge <= 0f) continue
                max(0.25f, max(delayed, seed) * edge).coerceIn(0f, 1f)
            }
            val role = if (center) BloomColorRole.PAPER else colorRole(radial)
            cells += BloomCell(
                clippedLeft, clippedTop, clippedRight, clippedBottom, alpha, role,
                colorAt(radial), gridX, gridY,
            )
        }
        return cells
    }

    /** Compatibility shape helper for the pure legacy layer tests; renderers use geometry(). */
    data class Cell(val x: Int, val y: Int, val layer: Int)

    fun cells(progress: Float): List<Cell> = geometry(progress, 320f, 320f).map {
        Cell(it.gridX - GRID_CELLS / 2, it.gridY - GRID_CELLS / 2, when (it.role) {
            BloomColorRole.BURNT_ORANGE -> 0
            BloomColorRole.AUBERGINE -> 1
            BloomColorRole.GOLD -> 2
            BloomColorRole.PAPER -> 3
        })
    }

    fun staticProgress() = 0.72f

    private fun colorRole(radial: Float): BloomColorRole = when {
        radial < 0.34f -> BloomColorRole.GOLD
        radial < 0.68f -> BloomColorRole.BURNT_ORANGE
        else -> BloomColorRole.AUBERGINE
    }

    /** Actual RGBA ramp used by both renderers; kept visible to pure color-neighbor tests. */
    internal fun colorAt(radial: Float): Int = colorFor(colorRole(radial), radial)

    private fun colorFor(role: BloomColorRole, radial: Float): Int = when (role) {
        BloomColorRole.PAPER -> PAPER
        BloomColorRole.GOLD,
        BloomColorRole.BURNT_ORANGE,
        BloomColorRole.AUBERGINE -> when {
            radial < 0.34f -> mix(GOLD, ORANGE, radial / 0.34f)
            radial < 0.68f -> mix(ORANGE, AUBERGINE, (radial - 0.34f) / 0.34f)
            else -> AUBERGINE
        }
    }

    private fun mix(first: Int, second: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int) = (color ushr shift) and 0xFF
        val t = amount.coerceIn(0f, 1f)
        fun lerp(shift: Int) = (channel(first, shift) + (channel(second, shift) - channel(first, shift)) * t).toInt()
        return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    fun contrastRatio(foreground: Int, background: Int): Double {
        fun channel(value: Int): Double {
            val c = (value and 0xFF) / 255.0
            return if (c <= .03928) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
        }
        fun luminance(color: Int) = .2126 * channel(color shr 16) + .7152 * channel(color shr 8) + .0722 * channel(color)
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}
