package com.chardy.doom

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DoomUiTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun visible(text: String) = assertTrue("Missing screen text: $text", device.wait(Until.hasObject(By.text(text)), 10_000))
    private fun click(text: String) {
        visible(text)
        device.findObject(By.text(text)).click()
    }
    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "evidence")
        assertTrue(dir.exists() || dir.mkdirs())
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
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
        click("TRY THE BREATHING DEMO")
        visible("Take a breath.")
        device.pressHome()
        rule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        visible("THE QUIET ROOM")
        assertFalse(device.hasObject(By.text("A deliberate start.")))
    }
}
