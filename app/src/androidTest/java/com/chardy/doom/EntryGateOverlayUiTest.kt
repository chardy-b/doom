package com.chardy.doom

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EntryGateOverlayUiTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun nativeOverlayIsDoomStyledSemanticAndTargeted() {
        var ui: EntryGateOverlayUi? = null
        rule.scenario.onActivity { activity ->
            ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {})
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content.addView(ui!!.root, ViewGroup.LayoutParams(-1, -1))
            ui!!.render(EntryGateOverlayModel(
                5, 0f, false,
                OverlayDiagnosticStatus(EntryGateSurface.UNKNOWN, OverlayReportStatus.UNAVAILABLE, false)
            ))
        }
        rule.scenario.onActivity {
            val actual = requireNotNull(ui)
            assertEquals(BreathingVisuals.INK, (actual.root.background as ColorDrawable).color)
            assertEquals(View.GONE, actual.copyCurrentReport.visibility)
            assertTrue(actual.skipToMessages.minimumHeight >= (48 * it.resources.displayMetrics.density).toInt())
            assertTrue(actual.skipToMessages.isFocusable)
            assertTrue(actual.skipToMessages.isClickable)
            actual.dispose()
            (actual.root.parent as? ViewGroup)?.removeView(actual.root)
        }
    }

    @Test fun largeFontAndLandscapeKeepWrappingActionsReachable() {
        rule.scenario.onActivity { activity ->
            listOf(
                Triple(320, 640, Configuration.ORIENTATION_PORTRAIT),
                Triple(640, 320, Configuration.ORIENTATION_LANDSCAPE),
            ).forEach { (widthDp, heightDp, orientation) ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                    this.orientation = orientation
                }
                val context = activity.createConfigurationContext(configuration)
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                val ui = EntryGateOverlayViewFactory.create(context, {}, {}, {})
                ui.root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                ui.root.layout(0, 0, width, height)
                assertTrue(ui.skipToMessages.measuredHeight >= (48 * density).toInt())
                assertTrue(ui.leaveInstagram.measuredHeight >= (48 * density).toInt())
                val scroll = ui.root as ScrollView
                val body = scroll.getChildAt(0) as ViewGroup
                fun assertReachable(action: View) {
                    val topInContent = body.top + action.top
                    val bottomInContent = body.top + action.bottom
                    val maxScroll = (body.height - scroll.height).coerceAtLeast(0)
                    val targetScroll = (bottomInContent - (scroll.height - scroll.paddingBottom))
                        .coerceIn(0, maxScroll)
                    scroll.scrollTo(0, targetScroll)
                    assertTrue(topInContent >= scroll.scrollY + scroll.paddingTop)
                    assertTrue(bottomInContent <= scroll.scrollY + scroll.height - scroll.paddingBottom)
                }
                assertReachable(ui.skipToMessages)
                assertReachable(ui.leaveInstagram)
                ui.dispose()
            }
        }
    }

    @Test fun supplementaryScreenshotsEstablishEachNamedStateAndRestoreConfiguration() {
        val originalFontScale = device.executeShellCommand("settings get system font_scale")
            .trim().takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) } ?: "1.0"
        val originalRotation = device.displayRotation
        val originalAccelerometerRotation = device.executeShellCommand("settings get system accelerometer_rotation")
            .trim().takeIf { it.matches(Regex("[01]")) } ?: "1"
        val originalUserRotation = device.executeShellCommand("settings get system user_rotation")
            .trim().takeIf { it.matches(Regex("[0-3]")) } ?: "0"
        var overlay: EntryGateOverlayUi? = null
        try {
            mount { overlay = it }
            renderAndCapture(overlay, "01-overlay-unavailable", reducedMotion = false, captured = false)
            renderAndCapture(overlay, "02-overlay-captured-status", reducedMotion = false, captured = true)
            renderAndCapture(overlay, "03-overlay-reduced-motion", reducedMotion = true, captured = false)

            device.executeShellCommand("settings put system font_scale 2.0")
            recreateActivity()
            mount { overlay = it }
            renderAndCapture(overlay, "04-overlay-large-font", reducedMotion = true, captured = false)

            device.setOrientationLeft()
            recreateActivity()
            mount { overlay = it }
            renderAndCapture(overlay, "05-overlay-landscape", reducedMotion = true, captured = false)

            unmount(overlay)
            overlay = null
        } finally {
            unmount(overlay)
            device.unfreezeRotation()
            device.executeShellCommand("settings put system accelerometer_rotation 0")
            device.executeShellCommand("settings put system user_rotation $originalRotation")
            device.executeShellCommand("settings put system accelerometer_rotation $originalAccelerometerRotation")
            device.executeShellCommand("settings put system user_rotation $originalUserRotation")
            device.unfreezeRotation()
            device.executeShellCommand("settings put system font_scale $originalFontScale")
            recreateActivity()
        }
    }

    @Test fun supplementaryFooterScreenshotScrollsOnlyInItsSeparateTest() {
        val footer = "Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
        val scroll = UiScrollable(UiSelector().scrollable(true))
        try {
            assertTrue(scroll.scrollToEnd(20))
            assertTrue(device.wait(Until.hasObject(By.text(footer)), 5_000))
            assertTopResumed()
            waitForDraw(rule.scenario)
            capture("06-doom-build-footer")
        } finally {
            scroll.scrollToBeginning(20)
        }
    }

    private fun mount(assign: (EntryGateOverlayUi) -> Unit) {
        rule.scenario.onActivity { activity ->
            val ui = EntryGateOverlayViewFactory.create(activity, {}, {}, {})
            activity.findViewById<ViewGroup>(android.R.id.content)
                .addView(ui.root, ViewGroup.LayoutParams(-1, -1))
            assign(ui)
        }
        instrumentation.waitForIdleSync()
    }

    private fun unmount(ui: EntryGateOverlayUi?) {
        rule.scenario.onActivity {
            ui?.dispose()
            ui?.root?.let { root -> (root.parent as? ViewGroup)?.removeView(root) }
        }
        instrumentation.waitForIdleSync()
    }

    private fun renderAndCapture(
        ui: EntryGateOverlayUi?,
        name: String,
        reducedMotion: Boolean,
        captured: Boolean
    ) {
        rule.scenario.onActivity {
            val actual = requireNotNull(ui)
            actual.render(EntryGateOverlayModel(
                5, if (reducedMotion) .5f else 0f, reducedMotion,
                OverlayDiagnosticStatus(
                    EntryGateSurface.UNKNOWN,
                    if (captured) OverlayReportStatus.CAPTURED else OverlayReportStatus.UNAVAILABLE,
                    false
                )
            ))
            assertEquals(
                "UNKNOWN\n" + if (captured) "REPORT CAPTURED" else "REPORT UNAVAILABLE",
                actual.status.text.toString()
            )
            assertTrue(actual.root.parent != null)
        }
        assertTopResumed()
        waitForDraw(rule.scenario)
        capture(name)
    }

    private fun recreateActivity() {
        rule.scenario.onActivity { it.recreate() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        assertTopResumed()
    }

    private fun assertTopResumed() {
        val resumed = Regex("(?:topResumedActivity|mResumedActivity)[=:]\\s*ActivityRecord\\{[^\\n]*\\scom\\.chardyb\\.doom/com\\.chardy\\.doom\\.MainActivity(?:\\s|\\})")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.currentPackageName == "com.chardyb.doom" &&
                resumed.containsMatchIn(device.executeShellCommand("dumpsys activity activities"))) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Doom MainActivity must be top-resumed before supplementary capture")
    }

    private fun waitForDraw(scenario: androidx.test.core.app.ActivityScenario<MainActivity>) {
        val drawn = CountDownLatch(1)
        var root: View? = null
        scenario.onActivity { activity ->
            val target = activity.findViewById<View>(android.R.id.content)
            root = target
            lateinit var listener: ViewTreeObserver.OnDrawListener
            listener = ViewTreeObserver.OnDrawListener {
                drawn.countDown()
                target.post {
                    if (target.viewTreeObserver.isAlive) {
                        target.viewTreeObserver.removeOnDrawListener(listener)
                    }
                }
            }
            target.viewTreeObserver.addOnDrawListener(listener)
            target.invalidate()
        }
        instrumentation.waitForIdleSync()
        assertTrue("Doom view must draw before capture", drawn.await(5, TimeUnit.SECONDS))
    }

    private fun capture(name: String) {
        require(name.matches(Regex("[a-z0-9-]+")))
        device.executeShellCommand("mkdir -p /sdcard/Download/doom-overlay-ui-evidence")
        device.executeShellCommand("screencap -p /sdcard/Download/doom-overlay-ui-evidence/$name.png")
        val bytes = device.executeShellCommand(
            "stat -c %s /sdcard/Download/doom-overlay-ui-evidence/$name.png"
        ).trim().toLongOrNull()
        assertTrue("Screenshot must exist and be nonempty", bytes != null && bytes > 0)
    }
}
