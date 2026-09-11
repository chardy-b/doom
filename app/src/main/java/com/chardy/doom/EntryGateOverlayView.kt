package com.chardy.doom

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal data class EntryGateOverlayUi(
    val root: LinearLayout,
    val countdown: TextView,
    val dismissForMessages: Button,
    val leaveInstagram: Button
)

internal object EntryGateOverlayViewFactory {
    fun create(
        context: Context,
        onDismissForMessages: () -> Unit,
        onLeaveInstagram: () -> Unit
    ): EntryGateOverlayUi {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
        }
        root.addView(TextView(context).apply {
            text = "UNVERIFIED DIAGNOSTIC\nTake a breath"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })
        val countdown = TextView(context).apply {
            text = "5s remaining"
            textSize = 48f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val explanation = TextView(context).apply {
            text = "This pause is optional and unverified. Messages and unknown screens bypass it."
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val dismiss = Button(context).apply {
            text = "DISMISS FOR MESSAGES"
            setOnClickListener { onDismissForMessages() }
        }
        val leave = Button(context).apply {
            text = "LEAVE INSTAGRAM"
            setOnClickListener { onLeaveInstagram() }
        }
        root.addView(countdown)
        root.addView(explanation)
        root.addView(dismiss)
        root.addView(leave)
        return EntryGateOverlayUi(root, countdown, dismiss, leave)
    }
}
