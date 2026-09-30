package com.chardy.doom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class PixelBreathingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = .5f

    fun render(value: Float, reduceMotion: Boolean) {
        progress = if (reduceMotion) BreathingVisuals.staticProgress() else value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        BreathingVisuals.geometry(progress, width.toFloat(), height.toFloat()).forEach { cell ->
            paint.color = cell.color
            paint.alpha = (cell.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(cell.left, cell.top, cell.right, cell.bottom, paint)
        }
        paint.alpha = 255
    }
}
