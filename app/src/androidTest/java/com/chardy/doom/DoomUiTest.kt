package com.chardy.doom

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DoomUiTest {
 @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
 private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
 private fun visible(text:String)=assertTrue("Missing $text",device.wait(Until.hasObject(By.text(text)),5_000))
 private fun click(text:String){visible(text);device.findObject(By.text(text)).click()}
 @Test fun homeDefaultsNavigationAndDebugIsolation(){visible("Breathing reminders");visible("10s");visible("1m");visible("Accessibility: Not enabled");assertFalse(device.hasObject(By.text("SANITIZED STRUCTURAL REPORT")));click("Debug");visible("SANITIZED STRUCTURAL REPORT");visible("DOOM-OWNED QUICK DEMO");click("Home");assertFalse(device.hasObject(By.text("SANITIZED STRUCTURAL REPORT")))}
 @Test fun presetsCustomDialogsAndPersistenceAfterRecreation(){click("20s");click("5m");rule.scenario.recreate();visible("20s");visible("5m");click("Custom");visible("Custom duration");device.pressBack();visible("Duration")}
 @Test fun previewRequiresTapAndContainsOnlyBreathingPresentation(){assertFalse(device.hasObject(By.text("Breathe in")));click("Preview breathing reminder");visible("Breathe in");assertFalse(device.hasObject(By.textContains("remaining")));assertFalse(device.hasObject(By.textContains("report")))}
 @Test @SupplementalEvidence fun buildFooterIsReachableAfterScrollingToTheEnd(){
  visible("Breathing reminders");click("Debug")
  assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollToEnd(20))
  visible("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
  assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(20))
 }
}
