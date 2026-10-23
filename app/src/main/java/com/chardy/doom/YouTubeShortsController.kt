package com.chardy.doom

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/** No framework object survives this synchronous bounded read. */
@Suppress("DEPRECATION")
internal class AndroidYouTubeIdNode(private val node: AccessibilityNodeInfo) : YouTubeIdNode {
    override fun packageName(): CharSequence? = node.packageName
    override fun resourceId(): CharSequence? = node.viewIdResourceName
    override fun childCount() = node.childCount
    override fun child(index: Int): YouTubeIdNode? = node.getChild(index)?.let(::AndroidYouTubeIdNode)
    override fun recycle() = node.recycle()
}

/** Main-thread, YouTube-only owner; it never routes, copies reports, or touches Instagram policy. */
internal class YouTubeShortsController(
    private val context: Context,
    private val handler: Handler,
    private val authority: () -> Boolean,
    private val canAdmit: () -> Boolean,
    private val root: () -> YouTubeIdNode?,
    private val home: () -> Boolean,
    private val detached: () -> Unit,
    private val physical: () -> OverlayPhysicalPlatform = { AndroidOverlayPhysicalPlatform },
    private val installer: (WindowManager, View, WindowManager.LayoutParams) -> Unit = { m, v, p -> m.addView(v, p) },
    private val updater: (WindowManager, View, WindowManager.LayoutParams) -> Unit = { m, v, p -> m.updateViewLayout(v, p) },
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private enum class Action { CANCEL, COMPLETE, HOME }
    private class Episode(val ticket: YouTubeGateTicket, val ui: EntryGateOverlayUi, val manager: WindowManager) {
        val records = mutableListOf<OwnedOverlayWindow>()
        var installing = true
        var closing = false
        var removing = false
        var action = Action.CANCEL
        var attempts = 0
        var tick: Runnable? = null
    }
    private val gate = YouTubeShortsGate()
    private val loop = OverlayRemovalRetryLoop(HandlerOverlayRemovalScheduler(handler))
    private var episode: Episode? = null
    private var safetyVeto = false
    private var authorityEpoch = 0L
    val ownsWindows get() = episode != null

    private fun allowed(): Boolean = try {
        authority() && canAdmit() && !safetyVeto &&
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: RuntimeException) { false }

    private fun player(): Boolean = try {
        // This is the only YouTube tree entry point, never AccessibilityEvent.source.
        YouTubeShortsClassifier.isPlayer(YouTubeIdCollector.collect(root(), now))
    } catch (_: RuntimeException) { false }

    fun onEvent(owner: CharSequence?, ownPackage: String) {
        try {
            if (!allowed()) { cancel(); return }
            if (YouTubeIdSanitizer.exact(owner, ownPackage)) return
            if (!YouTubeIdSanitizer.exact(owner, YOUTUBE_PACKAGE)) { cancel(); return }
            val current = episode
            if (current?.closing == true) {
                val verified = player()
                if (episode === current && (!verified || !allowed())) cancel(current)
                return
            }
            val epoch = authorityEpoch
            val verified = player()
            if (epoch != authorityEpoch || episode !== current) return
            if (!verified || !allowed()) { cancel(); return }
            if (current != null) return
            val settings = Observation.reminderSettings
            val ticket = gate.begin(now(), settings.durationSeconds * 1_000L, settings.suppressionMinutes * 60_000L) ?: return
            install(ticket)
        } catch (_: RuntimeException) { cancel() }
    }

    private fun install(ticket: YouTubeGateTicket) {
        if (episode != null || !gate.current(ticket) || !allowed()) { gate.cancel(); return }
        var ui: EntryGateOverlayUi? = null
        var installing: Episode? = null
        try {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            lateinit var current: Episode
            ui = EntryGateOverlayViewFactory.createYouTube(context) {
                if (episode === current && !current.closing) request(current, Action.HOME)
            }
            current = Episode(ticket, ui, manager)
            installing = current
            episode = current
            val layout = layout(current) ?: throw IllegalStateException("Reminder does not fit")
            val roots = listOf(ui.visualRoot, ui.leaveInstagram)
            try {
                layout.zip(roots).forEach { (descriptor, view) ->
                    if (!visibleAuthority(current) || !player() || !visibleAuthority(current))
                        throw IllegalStateException("Lost Shorts authority")
                    val params = GateOverlayWindows.parameters(descriptor)
                    val record = OwnedOverlayWindow(view, manager, descriptor.role, params, physical(), installer, updater)
                    current.records += record
                    record.addAttempted = true
                    installer(manager, view, params)
                    if (OverlayWindowRemover.attachment(record) != OverlayAttachment.ATTACHED || !visibleAuthority(current))
                        throw IllegalStateException("Unconfirmed window")
                }
            } finally {
                current.installing = false
                if (episode === current && current.closing) remove(current)
            }
            if (!visibleAuthority(current) || !player() || !visibleAuthority(current) || !gate.shown(ticket, now())) {
                cancel(current); return
            }
            ui.visualRoot.setOnApplyWindowInsetsListener { _, insets -> updateLayout(current, insets); insets }
            tick(current)
        } catch (failure: RuntimeException) {
            ui?.closeInteraction()
            if (failure is WindowManager.BadTokenException) installing?.records?.forEach { it.mayReAdd = false }
            installing?.let { cancel(it) } ?: gate.cancel()
        }
    }

    private fun visibleAuthority(current: Episode) = episode === current && !current.closing &&
        gate.current(current.ticket) && allowed()

    private fun tick(current: Episode) {
        if (episode !== current || current.closing) return
        current.tick = null
        if (!visibleAuthority(current) || !player() || !visibleAuthority(current) ||
            current.records.any { OverlayWindowRemover.attachment(it) != OverlayAttachment.ATTACHED }) {
            cancel(current); return
        }
        val remaining = gate.remaining(current.ticket, now()) ?: run { cancel(current); return }
        if (remaining == 0L) { request(current, Action.COMPLETE); return }
        val reduced = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: RuntimeException) { true }
        current.ui.render(EntryGateOverlayModel.from(remaining, current.ticket.durationMs, reduced))
        current.tick = Runnable { tick(current) }.also { handler.postDelayed(it, minOf(50L, remaining)) }
    }

    fun cancel() {
        authorityEpoch++
        gate.cancel()
        episode?.let { request(it, Action.CANCEL) }
    }

    private fun cancel(current: Episode) {
        if (episode === current) { authorityEpoch++; gate.cancel(); request(current, Action.CANCEL) }
    }

    private fun request(current: Episode, action: Action) {
        if (episode !== current) return
        if (action == Action.CANCEL) { current.action = Action.CANCEL; gate.cancel() }
        if (current.closing) return
        current.closing = true
        current.action = action
        current.tick?.let(handler::removeCallbacks); current.tick = null
        current.ui.closeInteraction()
        current.records.forEach { it.closeInteraction() }
        if (!current.installing) remove(current)
    }

    private fun remove(current: Episode) {
        if (episode !== current || current.removing || loop.isPending) return
        current.removing = true
        try {
            current.records.sortedBy { it.role == GateWindowRole.VISUAL }.forEach {
                it.closeInteraction()
                OverlayWindowRemover.attempt(it, it.mayReAdd)
            }
        } finally { current.removing = false }
        if (episode !== current) return
        if (current.records.all { OverlayWindowRemover.attachment(it) == OverlayAttachment.DETACHED }) {
            finish(current); return
        }
        current.attempts = minOf(20, current.attempts + 1)
        if (current.attempts == 20) {
            safetyVeto = true
            current.action = Action.CANCEL
            gate.cancel()
        }
        loop.schedule(if (current.attempts < 20) 50L else 1_000L, { episode === current }) { remove(current) }
    }

    private fun finish(current: Episode) {
        if (episode !== current || current.records.any { OverlayWindowRemover.attachment(it) != OverlayAttachment.DETACHED }) return
        loop.cancel()
        current.ui.dispose()
        current.records.forEach { it.release() }
        // Recheck both the current ticket and a new bounded player sample AFTER physical removal.
        val authorized = current.action != Action.CANCEL && gate.current(current.ticket) &&
            allowed() && player() && episode === current && gate.current(current.ticket) && allowed()
        if (authorized && current.action == Action.COMPLETE) {
            if (!gate.completeDetached(current.ticket, now(), true, true)) gate.cancel()
        } else if (authorized && current.action == Action.HOME) {
            gate.cancel()
            try { home() } catch (_: RuntimeException) { }
        } else gate.cancel()
        episode = null
        detached()
    }

    /** Disconnect transfers saved handles to the service's shared removal-only retiring owner. */
    fun disconnect(): List<OwnedOverlayWindow> {
        authorityEpoch++
        gate.cancel()
        val current = episode ?: return emptyList()
        current.action = Action.CANCEL
        current.closing = true
        current.tick?.let(handler::removeCallbacks)
        current.ui.dispose()
        current.records.forEach { it.closeInteraction(); it.mayReAdd = false; it.release() }
        loop.cancel()
        episode = null
        return current.records.sortedBy { it.role == GateWindowRole.VISUAL }
    }

    fun configurationChanged() { cancel() }

    private fun updateLayout(current: Episode, insets: WindowInsets) {
        if (episode !== current || current.installing || current.closing) return
        try {
            if (!visibleAuthority(current)) { cancel(current); return }
            val descriptors = layout(current, insets) ?: run { cancel(current); return }
            current.records.zip(descriptors).forEach { (record, descriptor) ->
                if (!visibleAuthority(current) || OverlayWindowRemover.attachment(record) != OverlayAttachment.ATTACHED) {
                    cancel(current); return
                }
                val parameters = GateOverlayWindows.parameters(descriptor)
                val same = record.params?.let { WindowManager.LayoutParams().apply { copyFrom(it) }.copyFrom(parameters) == 0 } == true
                if (!same) { record.params = parameters; updater(current.manager, record.view, parameters) }
            }
        } catch (_: RuntimeException) { cancel(current) }
    }

    private fun layout(current: Episode, insets: WindowInsets? = current.ui.root.rootWindowInsets): List<GateWindowDescriptor>? {
        val manager = current.manager
        val frame = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val safe = InstagramTimerOverlayViewFactory.safeInsets(manager, insets)
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.systemGestures() or WindowInsets.Type.mandatorySystemGestures()
            val gestures = manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types)
            val delivered = insets?.getInsetsIgnoringVisibility(types)
            safe.set(maxOf(safe.left, gestures.left, delivered?.left ?: 0), maxOf(safe.top, gestures.top, delivered?.top ?: 0),
                maxOf(safe.right, gestures.right, delivered?.right ?: 0), maxOf(safe.bottom, gestures.bottom, delivered?.bottom ?: 0))
        } else if (Build.VERSION.SDK_INT >= 29 && insets != null) {
            val gestures = insets.systemGestureInsets
            safe.set(maxOf(safe.left, gestures.left), maxOf(safe.top, gestures.top), maxOf(safe.right, gestures.right), maxOf(safe.bottom, gestures.bottom))
        }
        val density = context.resources.displayMetrics.density
        val width = frame.width() - safe.left - safe.right - (40 * density).toInt()
        if (width <= 0) return null
        val button = current.ui.leaveInstagram
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = button.measuredHeight
        val margin = (20 * density).toInt()
        val available = frame.height() - safe.top - safe.bottom
        if (height < (48 * density).toInt() || height + margin * 2 > available / 2) return null
        val y = frame.height() - safe.bottom - margin - height
        current.ui.layoutDecoration(safe.left + margin, safe.top + margin, width, y - safe.top - margin * 2)
        return listOf(GateWindowDescriptor(GateWindowRole.VISUAL, GateWindowRect(0, 0, frame.width(), frame.height())),
            GateWindowDescriptor(GateWindowRole.HOME, GateWindowRect(safe.left + margin, y, width, height)))
    }
}
