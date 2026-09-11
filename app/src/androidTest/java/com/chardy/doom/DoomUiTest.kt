package com.chardy.doom

import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test


class DoomUiTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun visible(text: String) = assertTrue("Missing screen text: $text", device.wait(Until.hasObject(By.text(text)), 10_000))
    private fun click(text: String) {
        visible(text)
        device.findObject(By.text(text)).click()
    }
    private fun capture(name: String) {
        // Test-only shell capture on the disposable CI emulator; survives app uninstall cleanup.
        // The production app has no screenshot or storage permission/capture implementation.
        val resumed = Regex("(?:topResumedActivity|mResumedActivity)[=:]\\s*ActivityRecord\\{[^\\n]*\\scom\\.chardyb\\.doom/com\\.chardy\\.doom\\.MainActivity(?:\\s|\\})")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var foreground = false
        while (!foreground && SystemClock.elapsedRealtime() < deadline) {
            foreground = device.currentPackageName == "com.chardyb.doom" &&
                resumed.containsMatchIn(device.executeShellCommand("dumpsys activity activities"))
            if (!foreground) SystemClock.sleep(50)
        }
        assertTrue("Doom must be the top-resumed activity before test-only capture", foreground)
        require(name.matches(Regex("[a-z0-9-]+")))
        // UiAutomation uses Runtime.exec, not a shell: no &&, pipes or redirection.
        device.executeShellCommand("mkdir -p /sdcard/Download/doom-ci-evidence")
        device.executeShellCommand("screencap -p /sdcard/Download/doom-ci-evidence/$name.png")
        val bytes = device.executeShellCommand("stat -c %s /sdcard/Download/doom-ci-evidence/$name.png").trim().toLongOrNull()
        assertTrue("Screenshot must exist and be nonempty", bytes != null && bytes > 0)
    }

    @Test fun demoMessagesAreImmediateAndFeedRequiresCompletedPause() {
        visible("THE QUIET ROOM")
        capture("01-doom-dashboard-demo")
        click("TRY THE BREATHING DEMO")
        visible("Take a breath.")
        capture("02-doom-breathing-demo")
        click("DEMO MESSAGES — NO WAIT")
        visible("Messages stay open.")
        capture("03-doom-messages-demo")
        click("BACK TO DOOM")
        click("TRY THE BREATHING DEMO")
        visible("Take a breath.")
        assertFalse(device.hasObject(By.text("A deliberate start.")))
        // visible() uses UiDevice.wait(Until.hasObject(...), 10_000), not an immediate assertion.
        visible("A deliberate start.")
        capture("04-doom-completed-demo")
    }

    @Test fun backgroundCancelsPendingGate() {
        visible("THE QUIET ROOM")
        var original: MainActivity? = null
        rule.scenario.onActivity { original = it }
        click("TRY THE BREATHING DEMO")
        visible("Take a breath.")
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.pkg("com.chardyb.doom")), 5_000))
        // ActivityScenario cannot force a background task to RESUMED on API 35.
        // Bring the existing activity back as a real user would; do not recreate/reset the demo.
        device.executeShellCommand("am start --activity-reorder-to-front -n com.chardyb.doom/com.chardy.doom.MainActivity")
        visible("THE QUIET ROOM")
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, rule.scenario.state)
        rule.scenario.onActivity { assertSame("Return must not recreate/reset the activity", original, it) }
        assertFalse(device.hasObject(By.text("A deliberate start.")))
    }
}
