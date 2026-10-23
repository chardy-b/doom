package com.chardy.doom

internal enum class GateWindowRole { VISUAL, MESSAGES, HOME, DEBUG }
internal data class GateWindowRect(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class GateWindowDescriptor(
    val role: GateWindowRole, val bounds: GateWindowRect,
    val touchable: Boolean = role != GateWindowRole.VISUAL, val alpha: Float = 1f,
) {
    fun recovery() = copy(touchable = false, alpha = 0f)
}
internal data class GateSafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Scalar geometry only. Failure means remove the episode, never shrink accessible targets. */
internal object GateOverlayWindowLayout {
    fun actionWidth(width: Int, insets: GateSafeInsets, density: Float): Int =
        width - insets.left - insets.right - (40 * density).toInt()

    fun calculate(width: Int, height: Int, insets: GateSafeInsets, density: Float,
                  buttonHeights: List<Int>): List<GateWindowDescriptor>? {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0 ||
            listOf(insets.left, insets.top, insets.right, insets.bottom).any { it < 0 } ||
            buttonHeights.size != 3) return null
        val safeHeight = height - insets.top - insets.bottom
        val w = actionWidth(width, insets, density)
        val gap = maxOf(1, (8 * density).toInt())
        val margin = maxOf(1, (16 * density).toInt())
        if (w <= 0 || safeHeight <= 0) return null
        if (buttonHeights.withIndex().any { (i, h) -> h < ((if (i == 0) 52 else 48) * density).toInt() }) return null
        val stack = buttonHeights.sumOf { it.toLong() } + 2L * gap
        if (stack > safeHeight / 2 || stack + 2L * margin > safeHeight) return null
        val x = insets.left + (width - insets.left - insets.right - w) / 2
        var y = height - insets.bottom - margin - stack.toInt()
        val result = mutableListOf(GateWindowDescriptor(GateWindowRole.VISUAL, GateWindowRect(0, 0, width, height)))
        buttonHeights.forEachIndexed { index, h ->
            result += GateWindowDescriptor(GateWindowRole.entries[index + 1], GateWindowRect(x, y, w, h))
            y += h + gap
        }
        return result.toList()
    }
}
