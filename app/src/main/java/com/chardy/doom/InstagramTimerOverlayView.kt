package com.chardy.doom

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

internal enum class TimerEdge { LEFT, RIGHT }
internal data class TimerBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun clampX(x: Float) = x.coerceIn(left.toFloat(), right.toFloat())
    fun clampY(y: Float) = y.coerceIn(top.toFloat(), bottom.toFloat())
    fun snapX(edge: TimerEdge) = if (edge == TimerEdge.LEFT) left else right
}

/** A font-independent, app-drawn assistant/chat mark. */
internal class TimerAssistantIcon(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(183, 244, 216) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = minOf(width, height) / 56f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * u
        canvas.drawRoundRect(12f*u, 13f*u, 44f*u, 39f*u, 9f*u, 9f*u, paint)
        paint.style = Paint.Style.FILL
        val tail = android.graphics.Path().apply {
            moveTo(19f*u, 38f*u); lineTo(15f*u, 47f*u); lineTo(28f*u, 39f*u); close()
        }
        canvas.drawPath(tail, paint)
        canvas.drawCircle(22f*u, 26f*u, 2f*u, paint)
        canvas.drawCircle(34f*u, 26f*u, 2f*u, paint)
    }
}

internal class InstagramTimerOverlayUi(
    val root: View,
    private val label: TextView,
    val dismiss: TextView,
    val icon: TimerAssistantIcon,
) {
    fun render(model: InstagramTimerModel) {
        label.text = model.text
        label.visibility = if (model.collapsed) View.GONE else View.VISIBLE
        dismiss.visibility = if (model.collapsed) View.GONE else View.VISIBLE
        root.contentDescription = model.contentDescription + if (model.collapsed) ", compact" else ", expanded"
        if (Build.VERSION.SDK_INT >= 30) {
            root.stateDescription = if (model.collapsed) "Compact" else "Expanded"
        }
        root.minimumWidth = if (model.collapsed) dp(root.context, 56) else dp(root.context, 152)
        root.requestLayout()
    }
    fun dispose() { root.setOnTouchListener(null); root.setOnClickListener(null); dismiss.setOnClickListener(null) }
}

internal object InstagramTimerOverlayViewFactory {
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun margins(density: Float, safeEnd: Int, safeTop: Int): Pair<Int, Int> =
        (safeEnd + (16 * density).toInt()) to (safeTop + (16 * density).toInt())

    fun bounds(frame: Rect, safe: Rect, width: Int, height: Int, density: Float): TimerBounds {
        val gap = (16 * density).toInt()
        val left = frame.left + safe.left + gap
        val top = frame.top + safe.top + gap
        val right = (frame.right - safe.right - gap - width).coerceAtLeast(left)
        val bottom = (frame.bottom - safe.bottom - gap - height).coerceAtLeast(top)
        return TimerBounds(left, top, right, bottom)
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun cutoutInsets(insets: WindowInsets): Rect = insets.displayCutout?.let {
        Rect(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom)
    } ?: Rect()

    @Suppress("DEPRECATION")
    fun safeInsets(manager: WindowManager, delivered: WindowInsets? = null): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics.windowInsets
            val stable = metrics.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = metrics.getInsets(WindowInsets.Type.ime())
            val deliveredStable = delivered?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val deliveredIme = delivered?.getInsets(WindowInsets.Type.ime())
            return Rect(maxOf(stable.left, deliveredStable?.left ?: 0, ime.left, deliveredIme?.left ?: 0),
                maxOf(stable.top, deliveredStable?.top ?: 0, ime.top, deliveredIme?.top ?: 0),
                maxOf(stable.right, deliveredStable?.right ?: 0, ime.right, deliveredIme?.right ?: 0),
                maxOf(stable.bottom, deliveredStable?.bottom ?: 0, ime.bottom, deliveredIme?.bottom ?: 0))
        }
        if (delivered == null) return Rect()
        val cutout = if (Build.VERSION.SDK_INT >= 28) cutoutInsets(delivered) else Rect()
        return Rect(maxOf(delivered.systemWindowInsetLeft, cutout.left), maxOf(delivered.systemWindowInsetTop, cutout.top),
            maxOf(delivered.systemWindowInsetRight, cutout.right), maxOf(delivered.systemWindowInsetBottom, cutout.bottom))
    }

    fun create(context: Context, onToggle: () -> Unit, onDismiss: () -> Unit,
        onMove: (Float, Float) -> Unit, onSettle: (Boolean) -> Unit): InstagramTimerOverlayUi {
        val density = context.resources.displayMetrics.density
        val icon = TimerAssistantIcon(context).apply { layoutParams = LinearLayout.LayoutParams(dp(context,56), dp(context,56)) }
        val label = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(context,4), 0, dp(context,8), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val dismiss = TextView(context).apply {
            text = context.getString(R.string.instagram_timer_dismiss); setTextColor(Color.WHITE); textSize = 24f
            gravity = Gravity.CENTER; minWidth = dp(context,48); minHeight = dp(context,48)
            contentDescription = context.getString(R.string.instagram_timer_dismiss_description)
            setOnClickListener { onDismiss() }
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(icon); addView(label); addView(dismiss) }
        val root = FrameLayout(context).apply {
            minimumWidth = dp(context,56); minimumHeight = dp(context,56); addView(row)
            background = GradientDrawable().apply { setColor(Color.rgb(18,34,58)); cornerRadius=28*density; setStroke(dp(context,2),Color.rgb(183,244,216)) }
            isClickable = true; isFocusable = true
            setOnClickListener { onToggle() }
        }
        var downX=0f; var downY=0f; var lastX=0f; var lastY=0f; var dragged=false
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        root.setOnTouchListener { view, event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX=event.rawX; downY=event.rawY; lastX=downX; lastY=downY; dragged=false; true }
                MotionEvent.ACTION_MOVE -> { if (!dragged && hypot(event.rawX-downX,event.rawY-downY)>slop) dragged=true
                    if (dragged) onMove(event.rawX-lastX,event.rawY-lastY); lastX=event.rawX; lastY=event.rawY; true }
                MotionEvent.ACTION_UP -> { if (dragged) onSettle(true) else view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { onSettle(false); true }
                else -> false
            }
        }
        root.setAccessibilityDelegate(object: View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean =
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { onDismiss(); true } else super.performAccessibilityAction(host, action, args)
        })
        return InstagramTimerOverlayUi(root,label,dismiss,icon)
    }
}

private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
