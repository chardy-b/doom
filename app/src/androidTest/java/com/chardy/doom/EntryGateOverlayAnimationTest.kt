package com.chardy.doom

import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EntryGateOverlayAnimationTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)

    private class ManualScheduler : OverlayFrameScheduler {
        var now = 1_000_000_000L
        var posts = 0
        var removals = 0
        var pending: Runnable? = null
        var duringNow: (() -> Unit)? = null
        override fun post(callback: Runnable) { posts++; pending = callback }
        override fun remove(callback: Runnable) { removals++; if (pending === callback) pending = null }
        override fun nowNanos(): Long { duringNow?.also { duringNow = null }?.invoke(); return now }
        fun runEvenIfRemoved(callback: Runnable) = callback.run()
        fun take(): Runnable = requireNotNull(pending).also { pending = null }
    }

    @Test fun renderBeforeAttachThenAttachAdvancesAndDetachStopsPendingCallback() {
        rule.scenario.onActivity { activity ->
            val scheduler = ManualScheduler()
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {}, scheduler)
            ui.render(EntryGateOverlayModel.from(10_000, 10_000, false))
            assertEquals(1, scheduler.posts)
            val first = scheduler.take()
            (activity.findViewById<ViewGroup>(android.R.id.content)).addView(ui.root)
            scheduler.now += 4_000_000_000L
            first.run()
            assertEquals("Breathe out", ui.snapshot().label)
            val pending = scheduler.take()
            (ui.root.parent as ViewGroup).removeView(ui.root)
            pending.run()
            assertEquals(2, scheduler.posts)
            assertTrue(scheduler.pending == null)
            ui.dispose()
        }
    }

    @Test fun disposeRacingPostedCallbackCannotContinueScheduling() {
        rule.scenario.onActivity { activity ->
            val scheduler = ManualScheduler()
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {}, scheduler)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(ui.root)
            ui.render(EntryGateOverlayModel.from(10_000, 10_000, false))
            val raced = scheduler.take()
            ui.dispose()
            scheduler.runEvenIfRemoved(raced)
            assertEquals(1, scheduler.posts)
            assertTrue(scheduler.pending == null)
            (ui.root.parent as ViewGroup).removeView(ui.root)
        }
    }

    @Test fun completionAndDisposeWhileCallbackExecutesLeaveNoContinuingCallback() {
        rule.scenario.onActivity { activity ->
            val scheduler = ManualScheduler()
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {}, scheduler)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(ui.root)
            ui.render(EntryGateOverlayModel.from(1, 10_000, false))
            val callback = scheduler.take()
            scheduler.now += 2_000_000L
            callback.run()
            assertEquals(listOf(1f), ui.snapshot().segments)
            assertTrue(scheduler.pending == null)

            ui.render(EntryGateOverlayModel.from(5_000, 10_000, false))
            val racing = scheduler.take()
            scheduler.duringNow = { ui.dispose() }
            scheduler.runEvenIfRemoved(racing)
            assertTrue(scheduler.pending == null)
            (ui.root.parent as ViewGroup).removeView(ui.root)
        }
    }

    @Test fun reducedMotionNativeAndComposePresentationKeepBloomStillWhileTimelineAdvances() {
        rule.scenario.onActivity { activity ->
            val scheduler = ManualScheduler()
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {}, scheduler)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(ui.root)
            ui.render(EntryGateOverlayModel.from(10_000, 10_000, true))
            val inhale = ui.snapshot()
            scheduler.now += 5_000_000_000L
            scheduler.take().run()
            val exhale = ui.snapshot()
            scheduler.now += 5_000_000_000L
            scheduler.take().run()
            val complete = ui.snapshot()
            assertEquals(listOf("Breathe in", "Breathe out", "Breathe in"), listOf(inhale.label, exhale.label, complete.label))
            assertEquals(listOf(0f), inhale.segments)
            assertEquals(listOf(.5f), exhale.segments)
            assertEquals(listOf(1f), complete.segments)
            assertEquals(inhale.bloom, exhale.bloom, 0f)
            assertEquals(exhale.bloom, complete.bloom, 0f)

            val composeStates = listOf(0L, 5_000L, 10_000L).map {
                BreathingVisuals.presentation(BreathingVisuals.frame(it, 10_000), true)
            }
            assertEquals(listOf("Breathe in", "Breathe out", "Breathe in"), composeStates.map { it.label })
            assertEquals(listOf(listOf(0f), listOf(.5f), listOf(1f)), composeStates.map { it.segments })
            assertEquals(1, composeStates.map { it.bloom }.distinct().size)
            ui.dispose()
            (ui.root.parent as ViewGroup).removeView(ui.root)
        }
    }
}
