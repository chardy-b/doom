package com.chardy.doom

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

internal interface OverlayPhysicalPlatform {
    fun isAttached(view: View): Boolean
    /** A WindowManager root also owns a pending first traversal, before View attachment. */
    fun isRegistered(view: View): Boolean = isAttached(view)
    fun removeImmediate(manager: WindowManager, view: View)
}

internal object AndroidOverlayPhysicalPlatform : OverlayPhysicalPlatform {
    override fun isAttached(view: View) = view.isAttachedToWindow
    // addView assigns the root's parent synchronously; dispatchAttachedToWindow runs later.
    // These saved roots are standalone windows, never children of another Doom view.
    override fun isRegistered(view: View) = view.isAttachedToWindow || view.parent != null
    override fun removeImmediate(manager: WindowManager, view: View) = manager.removeViewImmediate(view)
}

internal class HandlerOverlayRemovalScheduler(private val handler: Handler) : OverlayRemovalScheduler {
    override fun post(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
}

internal class OwnedOverlayWindow(
    val view: View,
    val manager: WindowManager?,
    val role: GateWindowRole,
    parameters: WindowManager.LayoutParams?,
    private val platform: OverlayPhysicalPlatform,
    private val installer: (WindowManager, View, WindowManager.LayoutParams) -> Unit,
    private val updater: (WindowManager, View, WindowManager.LayoutParams) -> Unit,
) : OverlayWindowRemovalPort {
    var params = parameters?.let { original -> WindowManager.LayoutParams().apply { copyFrom(original) } }
    var addAttempted = false
    var mayReAdd = true
    var lastAttachment = OverlayAttachment.UNKNOWN
        private set
    override fun attachment(): OverlayAttachment {
        lastAttachment = if (!addAttempted) OverlayAttachment.DETACHED else try {
            if (manager == null || params == null) OverlayAttachment.UNKNOWN
            else if (platform.isRegistered(view)) OverlayAttachment.ATTACHED else OverlayAttachment.DETACHED
        } catch (_: RuntimeException) { OverlayAttachment.UNKNOWN }
        return lastAttachment
    }
    fun closeInteraction() {
        view.isEnabled = false
        view.setOnClickListener(null)
        view.setOnTouchListener(null)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.alpha = 0f
        val recovery = recoveryParams() ?: return
        try { manager?.let { updater(it, view, recovery) } } catch (_: RuntimeException) { }
    }
    private fun recoveryParams(): WindowManager.LayoutParams? = params?.let { saved ->
        WindowManager.LayoutParams().apply {
            copyFrom(saved)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            alpha = 0f
        }
    }
    override fun removeImmediate() { manager?.let { platform.removeImmediate(it, view) } }
    override fun reAddForRemoval() {
        if (!mayReAdd) return
        val recovery = recoveryParams() ?: return
        val wm = manager ?: return
        closeInteraction()
        try { installer(wm, view, recovery) } catch (failure: WindowManager.BadTokenException) {
            mayReAdd = false
            throw failure
        }
    }
    fun release() { view.setOnApplyWindowInsetsListener(null) }
}

internal class GateOverlayWindows(val token: OverlayCallbackToken, val ui: EntryGateOverlayUi) {
    val records = mutableListOf<OwnedOverlayWindow>()
    var installing = true
    var removing = false
    var degraded = false
    fun allDetached() = records.all { OverlayWindowRemover.attachment(it) == OverlayAttachment.DETACHED }
    fun removalOrder() = records.sortedBy { it.role == GateWindowRole.VISUAL }
    fun dispose() { ui.dispose(); records.forEach { it.release() } }

    companion object {
        fun parameters(descriptor: GateWindowDescriptor): WindowManager.LayoutParams = WindowManager.LayoutParams(
            descriptor.bounds.width, descriptor.bounds.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (!descriptor.touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = descriptor.bounds.x; y = descriptor.bounds.y; alpha = descriptor.alpha
        }
    }
}

/** At most one process-local retiring episode. No routing/rendering/service continuation. */
internal object RetiringOverlayCleanup {
    val barrier = OverlayCleanupBarrier()
    private var owner: RetiringOverlayRemovalOwner? = null
    fun reconcile() { owner?.reconcileDetached() }
    fun retire(records: List<OwnedOverlayWindow>, previousLoops: List<OverlayRemovalRetryLoop>) {
        if (records.all { OverlayWindowRemover.attachment(it) == OverlayAttachment.DETACHED }) { previousLoops.forEach { it.cancel() }; return }
        records.forEach { it.mayReAdd = false; it.release() }
        owner = RetiringOverlayRemovalOwner(records,
            HandlerOverlayRemovalScheduler(Handler(Looper.getMainLooper())), barrier) {
            records.forEach { it.release() }
            owner = null
        }.also { it.takeOver(previousLoops) }
    }
}
