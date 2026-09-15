package com.chardy.doom

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

internal class InstagramTimerOverlayUi(
    val root: View,
    private val label: TextView,
) {
    fun render(model: InstagramTimerModel) {
        val text = if (model.collapsed) "◆" else "◆  ${model.text}"
        if (label.text.toString() != text) label.text = text
        root.contentDescription = model.contentDescription + if (model.collapsed) ", collapsed" else ", expanded"
    }
    fun dispose() { root.setOnClickListener(null) }
}

internal object InstagramTimerOverlayViewFactory {
    fun margins(density: Float, safeEnd: Int, safeTop: Int): Pair<Int, Int> =
        (safeEnd + (16 * density).toInt()) to (safeTop + (16 * density).toInt())

    fun safeMargins(context: Context, manager: WindowManager, delivered: WindowInsets? = null): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            return margins(density, if (rtl) insets.left else insets.right, insets.top)
        }
        if (delivered != null) {
            val cutout = if (Build.VERSION.SDK_INT >= 28) delivered.displayCutout else null
            val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val end = if (rtl) maxOf(delivered.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                else maxOf(delivered.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
            return margins(density, end, maxOf(delivered.systemWindowInsetTop, cutout?.safeInsetTop ?: 0))
        }
        // API 26–29 receives system-bar/cutout margins when the window delivers insets.
        return margins(density, 0, 0)
    }

    fun create(context: Context, onToggle: () -> Unit): InstagramTimerOverlayUi {
        val density = context.resources.displayMetrics.density
        val label = TextView(context).apply {
            setTextColor(Color.rgb(183, 244, 216))
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14*density).toInt(), (8*density).toInt(), (14*density).toInt(), (8*density).toInt())
            minHeight = (48*density).toInt()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 34, 58)); cornerRadius = 24*density
                setStroke((1*density).toInt(), Color.rgb(104, 183, 159))
            }
        }
        val root = FrameLayout(context).apply {
            minimumWidth = (48*density).toInt(); minimumHeight = (48*density).toInt()
            addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            if (Build.VERSION.SDK_INT < 30) setOnApplyWindowInsetsListener { view, insets ->
                val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                view.setPadding(
                    maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
                )
                insets
            }
            isClickable = true; isFocusable = true; setOnClickListener { onToggle() }
        }
        return InstagramTimerOverlayUi(root, label)
    }
}
