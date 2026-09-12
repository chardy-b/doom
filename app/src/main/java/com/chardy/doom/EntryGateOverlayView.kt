package com.chardy.doom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

internal class EntryGateOverlayUi(
    val root: View,
    val countdown: TextView,
    val skipToMessages: Button,
    val leaveInstagram: Button,
    val copyCurrentReport: Button,
    val status: TextView,
    private val feedback: TextView,
    private val pixel: PixelBreathingView
) {
    private var disposed = false
    private var lastCountdown = -1
    private var lastStatus: String? = null
    private var lastCopyVisibility = View.GONE

    fun render(model: EntryGateOverlayModel) {
        if (disposed) return
        if (lastCountdown != model.remainingSeconds) {
            countdown.text = "${model.remainingSeconds}s remaining"
            lastCountdown = model.remainingSeconds
        }
        val statusText = "${model.diagnostic.surface.name}\n" +
            if (model.diagnostic.reportStatus == OverlayReportStatus.CAPTURED) "REPORT CAPTURED"
            else "REPORT UNAVAILABLE"
        if (statusText != lastStatus) {
            status.text = statusText
            lastStatus = statusText
        }
        val copyVisibility = if (model.diagnostic.canCopyCurrentReport) View.VISIBLE else View.GONE
        if (copyVisibility != lastCopyVisibility) {
            copyCurrentReport.visibility = copyVisibility
            lastCopyVisibility = copyVisibility
        }
        pixel.render(model.progress, model.reduceMotion)
    }

    fun showCopyResult(result: OverlayCopyResult) {
        if (disposed) return
        feedback.text = if (result == OverlayCopyResult.COPIED) "Copied to system clipboard."
        else "Copy unavailable."
        feedback.visibility = View.VISIBLE
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        skipToMessages.setOnClickListener(null)
        leaveInstagram.setOnClickListener(null)
        copyCurrentReport.setOnClickListener(null)
        skipToMessages.isEnabled = false
        leaveInstagram.isEnabled = false
        copyCurrentReport.isEnabled = false
        pixel.visibility = View.INVISIBLE
    }
}

internal object EntryGateOverlayViewFactory {
    fun create(
        context: Context,
        onSkipToMessages: () -> Unit,
        onLeaveInstagram: () -> Unit,
        onCopyCurrentReport: () -> Unit
    ): EntryGateOverlayUi {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun text(value: String, size: Float, color: Int = BreathingVisuals.PAPER) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(BreathingVisuals.INK)
            isFillViewport = true
            contentDescription = "Instagram diagnostic pause"
            setOnApplyWindowInsetsListener { view, insets ->
                val cutoutSafeInsets = if (Build.VERSION.SDK_INT >= 28) {
                    insets.displayCutout?.let { cutout ->
                        intArrayOf(
                            cutout.safeInsetLeft,
                            cutout.safeInsetTop,
                            cutout.safeInsetRight,
                            cutout.safeInsetBottom
                        )
                    }
                } else null
                val left = maxOf(insets.systemWindowInsetLeft, cutoutSafeInsets?.get(0) ?: 0)
                val top = maxOf(insets.systemWindowInsetTop, cutoutSafeInsets?.get(1) ?: 0)
                val right = maxOf(insets.systemWindowInsetRight, cutoutSafeInsets?.get(2) ?: 0)
                val bottom = maxOf(insets.systemWindowInsetBottom, cutoutSafeInsets?.get(3) ?: 0)
                view.setPadding(left, top, right, bottom)
                insets
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        val header = text("INSTAGRAM DETECTED", 20f, BreathingVisuals.JADE).apply {
            contentDescription = "Instagram detected"
            isFocusable = true
            typeface = Typeface.MONOSPACE
            minHeight = dp(48)
        }
        body.addView(header, LinearLayout.LayoutParams(-1, -2))
        body.addView(text("Take a breath.", 28f).apply { gravity = Gravity.CENTER })
        val pixel = PixelBreathingView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(pixel, LinearLayout.LayoutParams(-1, dp(112)))
        body.addView(text("Breathe naturally. No need to hold.", 16f).apply { gravity = Gravity.CENTER })
        val countdown = text("5s remaining", 22f, BreathingVisuals.JADE).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            minHeight = dp(48)
        }
        body.addView(countdown, LinearLayout.LayoutParams(-1, -2))
        val status = text("UNKNOWN\nREPORT UNAVAILABLE", 14f, BreathingVisuals.JADE).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(status)
        body.addView(text("Diagnostic pause may interrupt DM entry. Skip tries to open Instagram messages after removing this pause; it may not work.", 14f))
        body.addView(text("Copy sends the current sanitized report to the system clipboard without showing it here. Copies leave Doom and cannot be recalled by clearing Doom.", 14f))
        val feedback = text("", 14f, BreathingVisuals.JADE).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(feedback)
        val copy = button(context, "COPY CURRENT REPORT", BreathingVisuals.INK, BreathingVisuals.JADE, dp(48)).apply {
            visibility = View.GONE
            setOnClickListener { onCopyCurrentReport() }
        }
        val skip = button(context, "SKIP TO MESSAGES", BreathingVisuals.INK, BreathingVisuals.JADE, dp(52)).apply {
            setOnClickListener { onSkipToMessages() }
        }
        val leave = button(context, "LEAVE INSTAGRAM", BreathingVisuals.PAPER, BreathingVisuals.PANEL, dp(48)).apply {
            setOnClickListener { onLeaveInstagram() }
        }
        body.addView(copy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(skip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(leave, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return EntryGateOverlayUi(scroll, countdown, skip, leave, copy, status, feedback, pixel)
    }

    private fun button(context: Context, label: String, textColor: Int, fill: Int, minHeight: Int) = Button(context).apply {
        text = label
        textSize = 14f
        this.minHeight = minHeight
        minimumHeight = minHeight
        isAllCaps = false
        gravity = Gravity.CENTER
        setPadding(paddingLeft, context.resources.displayMetrics.density.let { (12 * it).toInt() },
            paddingRight, context.resources.displayMetrics.density.let { (12 * it).toInt() })
        setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(BreathingVisuals.PAPER, BreathingVisuals.INK, textColor, textColor, BreathingVisuals.PAPER)
        ))
        fun background(color: Int, stroke: Int, width: Int = 1) = GradientDrawable().apply {
            setColor(color)
            setStroke(width, stroke)
            cornerRadius = 2f
        }
        val focusStroke = if (fill == BreathingVisuals.JADE) BreathingVisuals.INK else BreathingVisuals.PAPER
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), background(BreathingVisuals.PANEL, BreathingVisuals.PAPER, 1))
            addState(intArrayOf(android.R.attr.state_pressed), background(BreathingVisuals.PAPER, BreathingVisuals.INK, 2))
            addState(intArrayOf(android.R.attr.state_focused), background(fill, focusStroke, 2))
            addState(intArrayOf(android.R.attr.state_enabled), background(fill, BreathingVisuals.JADE, 1))
            addState(intArrayOf(), background(fill, BreathingVisuals.JADE, 1))
        }
        stateListAnimator = null
    }
}
