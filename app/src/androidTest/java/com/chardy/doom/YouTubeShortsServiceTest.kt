package com.chardy.doom

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.IdentityHashMap

/** Synthetic runtime ordering only; these tests never open or inspect the real YouTube app. */
class YouTubeShortsServiceTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val fixtures = mutableListOf<Fixture>()

    private class Node(val name: String? = "reel_watch_player", val children: List<Node> = emptyList(),
                       val owner: String? = YOUTUBE_PACKAGE) : YouTubeIdNode {
        override fun packageName(): CharSequence? = owner
        override fun resourceId(): CharSequence? = name?.let { "$YOUTUBE_PACKAGE:id/$it" }
        override fun childCount() = children.size
        override fun child(index: Int): YouTubeIdNode = children[index]
        override fun recycle() = Unit
    }

    private class Fixture(activity: MainActivity) {
        var clock = 0L
        var enabled = true
        var admission = true
        var node: YouTubeIdNode? = Node()
        var readHook: () -> Unit = {}
        var removeEnabled = true
        var failSecondAdd = false
        var onAdd: () -> Unit = {}
        var homes = 0
        var additions = 0
        val attachments = IdentityHashMap<View, Boolean>()
        val views = mutableListOf<View>()
        val platform = object : OverlayPhysicalPlatform {
            override fun isAttached(view: View) = attachments[view] == true
            override fun removeImmediate(manager: WindowManager, view: View) {
                if (removeEnabled) attachments[view] = false
            }
        }
        val controller = YouTubeShortsController(activity, Handler(Looper.getMainLooper()),
            authority = { enabled }, canAdmit = { admission },
            root = { readHook(); node },
            home = { assertFalse(attachments.values.any { it }); homes++; true }, detached = {},
            physical = { platform },
            installer = { _, view, _ ->
                additions++
                if (failSecondAdd && additions == 2) throw IllegalStateException("synthetic partial add")
                attachments[view] = true
                views += view
                onAdd()
            }, updater = { _, _, _ -> }, now = { clock })
        fun observe(owner: String = YOUTUBE_PACKAGE) = controller.onEvent(owner, "com.chardyb.doom")
        fun tick(): Runnable = field(field(controller, "episode")!!, "tick") as Runnable
        fun retry(): Runnable = field(field(controller, "loop")!!, "pending") as Runnable
        fun leave() { (views.last { it is android.widget.Button } as android.widget.Button).performClick() }
    }

    private fun fixture(activity: MainActivity) = Fixture(activity).also {
        Observation.updateReminderSettings(activity, ReminderSettings(true, 10, 1))
        fixtures += it
    }

    @After fun cleanup() {
        rule.scenario.onActivity { activity ->
            fixtures.forEach { f -> f.removeEnabled = true; f.controller.cancel(); f.controller.disconnect() }
            Observation.setYouTubeShortsEnabled(activity, false)
            Observation.setGateConsent(activity, false)
            Observation.accept(activity, false)
            Observation.updateReminderSettings(activity, ReminderSettings())
            Observation.connected = false
        }
    }

    @Test fun playerAdmitsOnlyVisualAndLeaveAndOrdinarySurfacesRemoveWithoutCredit() {
        rule.scenario.onActivity { activity ->
            for (name in listOf("home", "search", "subscriptions", "watch_player", "shorts_shelf", "reel_shelf")) {
                val f = fixture(activity)
                f.node = Node(name)
                f.observe()
                assertFalse(f.controller.ownsWindows)
                f.node = Node()
                f.observe()
                assertTrue(f.controller.ownsWindows)
                assertEquals(2, f.views.size)
                assertEquals("Leave YouTube", (f.views[1] as android.widget.Button).text.toString())
                f.node = Node(name)
                f.observe()
                assertFalse(f.controller.ownsWindows)
                f.node = Node()
                f.observe() // Safety cancellation did not earn cooldown.
                assertTrue(f.controller.ownsWindows)
                f.controller.cancel()
            }
        }
    }

    @Test fun shelfConflictMissingRootAndAuthorityLossFailOpen() {
        rule.scenario.onActivity { activity ->
            val f = fixture(activity)
            for (invalid in listOf(Node(children = listOf(Node(), Node("shorts_shelf"))),
                Node(owner = "com.instagram.android"), null)) {
                f.node = Node(); f.observe(); assertTrue(f.controller.ownsWindows)
                f.node = invalid; f.observe(); assertFalse(f.controller.ownsWindows)
            }
            f.node = Node(); f.observe()
            f.enabled = false; f.tick().run()
            assertFalse(f.controller.ownsWindows)
            f.enabled = true; f.observe()
            assertTrue(f.controller.ownsWindows)
            f.readHook = { f.enabled = false }
            f.tick().run()
            assertFalse(f.controller.ownsWindows)
        }
    }

    @Test fun completionAndHomeWaitForAllPhysicalWindowsAndRevalidate() {
        rule.scenario.onActivity { activity ->
            val f = fixture(activity)
            f.observe(); f.removeEnabled = false
            f.clock = 10_000; f.tick().run()
            assertTrue(f.controller.ownsWindows)
            assertEquals(0, f.homes)
            f.clock = 20_000; f.removeEnabled = true; f.retry().run()
            assertFalse(f.controller.ownsWindows)
            f.observe(); assertFalse(f.controller.ownsWindows)
            f.clock = 80_000; f.observe(); assertTrue(f.controller.ownsWindows)
            f.removeEnabled = false; f.leave()
            assertEquals(0, f.homes)
            f.enabled = false; f.removeEnabled = true; f.retry().run()
            assertEquals(0, f.homes)
            f.enabled = true; f.observe(); f.leave()
            assertEquals(1, f.homes)
        }
    }

    @Test fun uncertaintyWhileRemovingVetoesCompletionAndStaleCallbacksCannotTouchReplacement() {
        rule.scenario.onActivity { activity ->
            val f = fixture(activity)
            f.observe(); val oldTick = f.tick()
            f.removeEnabled = false; f.clock = 10_000; oldTick.run()
            val oldRetry = f.retry()
            f.node = null; f.observe()
            f.removeEnabled = true; oldRetry.run()
            assertFalse(f.controller.ownsWindows)
            f.node = Node(); f.observe()
            assertTrue(f.controller.ownsWindows)
            oldTick.run(); oldRetry.run()
            assertTrue(f.controller.ownsWindows)
            assertTrue(f.attachments.values.any { it })
        }
    }

    @Test fun ownersPartialAddsAndRevocationDuringInstallRemainIsolated() {
        rule.scenario.onActivity { activity ->
            val f = fixture(activity)
            val other = fixture(activity)
            f.observe(); val stale = f.tick(); f.controller.cancel()
            other.observe(); stale.run()
            assertTrue(other.controller.ownsWindows)
            other.controller.cancel()
            f.failSecondAdd = true; f.additions = 0; f.observe()
            assertFalse(f.controller.ownsWindows)
            assertFalse(f.attachments.values.any { it })
            f.failSecondAdd = false
            f.onAdd = { f.enabled = false; f.controller.cancel() }
            f.observe()
            assertFalse(f.controller.ownsWindows)
            assertFalse(f.attachments.values.any { it })
            f.onAdd = {}; f.enabled = true; f.observe()
            assertTrue(f.controller.ownsWindows)
        }
    }

    @Test fun foreignEventsDisconnectAndConfigurationInvalidateWithoutExternalActions() {
        rule.scenario.onActivity { activity ->
            val f = fixture(activity)
            f.observe(); f.observe("com.example.foreign")
            assertFalse(f.controller.ownsWindows)
            f.observe(); f.controller.configurationChanged()
            assertFalse(f.controller.ownsWindows)
            f.observe(); val stale = f.tick()
            val records = f.controller.disconnect()
            stale.run()
            assertFalse(f.controller.ownsWindows)
            assertEquals(2, records.size)
            records.forEach { OverlayWindowRemover.attempt(it, false) }
            assertTrue(records.all { OverlayWindowRemover.attachment(it) == OverlayAttachment.DETACHED })
            assertEquals(0, f.homes)
        }
    }

    @Test fun separateOptInPersistsDefaultsOffAndDoesNotDeriveFromInstagramConsent() {
        rule.scenario.onActivity { activity ->
            val prefs = activity.getSharedPreferences("consent", Context.MODE_PRIVATE)
            prefs.edit().remove(Observation.YOUTUBE_SHORTS_ENABLED_KEY).commit()
            Observation.accept(activity, true); Observation.setGateConsent(activity, true)
            Observation.load(activity)
            assertFalse(Observation.youtubeShortsEnabled)
            Observation.setYouTubeShortsEnabled(activity, true)
            Observation.load(activity)
            assertTrue(Observation.youtubeShortsEnabled)
            Observation.setGateConsent(activity, false)
            assertTrue(Observation.youtubeShortsEnabled)
            Observation.setYouTubeShortsEnabled(activity, false)
            Observation.load(activity)
            assertFalse(Observation.youtubeShortsEnabled)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun actualServiceDispatchRequiresOnlyYoutubeOptInAndStopsBeforeInstagramReplacement() {
        rule.scenario.onActivity { activity ->
            val service = DoomAccessibilityService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, activity)
            val attachments = IdentityHashMap<View, Boolean>()
            var owner = YOUTUBE_PACKAGE
            var homes = 0
            val platform = object : OverlayPlatform {
                override fun isAttached(view: View) = attachments[view] == true
                override fun removeImmediate(manager: WindowManager, view: View) { attachments[view] = false }
                override fun currentRoot(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                    packageName = owner
                    viewIdResourceName = "$YOUTUBE_PACKAGE:id/reel_watch_player"
                }
                override fun routeMessages(root: AccessibilityNodeInfo): MessagesRouteResult = error("No YouTube routing")
                override fun performHome(): Boolean { homes++; return true }
            }
            setField(service, "overlayPlatform", platform)
            setField(service, "bound", true)
            setField(service, "overlayWindowInstaller", { _: WindowManager, view: View, _: WindowManager.LayoutParams -> attachments[view] = true })
            setField(service, "overlayWindowUpdater", { _: WindowManager, _: View, _: WindowManager.LayoutParams -> Unit })
            Observation.accept(activity, false)
            Observation.setGateConsent(activity, false)
            Observation.updateReminderSettings(activity, ReminderSettings(true, 10, 1))
            Observation.setYouTubeShortsEnabled(activity, true)
            Observation.connected = true
            fun event() {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                try { event.packageName = owner; service.onAccessibilityEvent(event) } finally { event.recycle() }
            }
            try {
                event()
                assertTrue(attachments.values.any { it })
                assertEquals(2, attachments.size)
                assertNull(Observation.report)
                owner = "com.example.foreign"; event()
                assertFalse(attachments.values.any { it })
                assertEquals(0, homes)
            } finally { service.onDestroy() }
        }
    }

    companion object {
        private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(target, value)
    }
}
