package com.chardy.doom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class PixelBreathingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0.5f
    private var reduceMotion = false

    fun render(progress: Float, reduceMotion: Boolean) {
        this.progress = if (reduceMotion) BreathingVisuals.staticProgress() else BreathingVisuals.clampedProgress(progress)
        this.reduceMotion = reduceMotion
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val phase = if (reduceMotion) BreathingVisuals.staticProgress() else progress
        val unit = minOf(width / 14f, height / 12f).coerceAtLeast(1f)
        val centerX = width / 2f
        val centerY = height / 2f
        BreathingVisuals.cells(phase).forEach { cell ->
            paint.color = if (cell.center) BreathingVisuals.PAPER else BreathingVisuals.JADE
            val left = centerX + cell.x * unit - unit / 2f
            val top = centerY + cell.y * unit - unit / 2f
            val extent = (unit - 2f).coerceAtLeast(1f)
            canvas.drawRect(left, top, left + extent, top + extent, paint)
        }
    }
}
