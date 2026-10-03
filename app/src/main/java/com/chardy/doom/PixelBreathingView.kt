package com.chardy.doom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class PixelBreathingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = .5f
    internal val renderedProgress get() = progress

    fun render(value: Float, reduceMotion: Boolean) {
        progress = if (reduceMotion) BreathingVisuals.staticProgress() else value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        BreathingVisuals.visitGeometry(progress, width.toFloat(), height.toFloat()) { left, top, right, bottom, alpha, _, color, _, _ ->
            paint.color = color
            paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(left, top, right, bottom, paint)
        }
        paint.alpha = 255
    }
}
