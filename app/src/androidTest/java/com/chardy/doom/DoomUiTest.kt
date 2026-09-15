package com.chardy.doom

import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test

class DoomUiTest {
 @get:Rule val rule=ActivityScenarioRule(MainActivity::class.java)
 private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
 @Before fun resetReminderSettings(){
  InstrumentationRegistry.getInstrumentation().targetContext
   .getSharedPreferences("reminder_settings_v1",android.content.Context.MODE_PRIVATE).edit().clear().commit()
 rule.scenario.recreate()
 }
 @After fun clearReminderSettings(){
  InstrumentationRegistry.getInstrumentation().targetContext
   .getSharedPreferences("reminder_settings_v1",android.content.Context.MODE_PRIVATE).edit().clear().commit()
 }
 private fun capture(name:String){
  val resumed=Regex("(?:topResumedActivity|mResumedActivity)[=:]\\s*ActivityRecord\\{[^\\n]*\\scom\\.chardyb\\.doom/com\\.chardy\\.doom\\.MainActivity(?:\\s|\\})")
  val deadline=SystemClock.elapsedRealtime()+5_000
  var foreground=false
  while(!foreground&&SystemClock.elapsedRealtime()<deadline){
   foreground=device.currentPackageName=="com.chardyb.doom"&&resumed.containsMatchIn(device.executeShellCommand("dumpsys activity activities"))
   if(!foreground)SystemClock.sleep(50)
  }
  assertTrue("Doom must be top-resumed before canonical capture",foreground)
  require(name.matches(Regex("[a-z0-9-]+")))
  device.executeShellCommand("mkdir -p /sdcard/Download/doom-ci-evidence")
  device.executeShellCommand("screencap -p /sdcard/Download/doom-ci-evidence/$name.png")
  assertTrue(device.executeShellCommand("stat -c %s /sdcard/Download/doom-ci-evidence/$name.png").trim().toLongOrNull()?.let{it>0}==true)
 }
 private fun visible(text:String,timeoutMs:Long=5_000)=assertTrue("Missing $text",device.wait(Until.hasObject(By.text(text)),timeoutMs))
 private fun scrollTo(text:String){
  if(device.wait(Until.hasObject(By.text(text)),1_000)) return
  repeat(20){
   device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/4,20)
   if(device.wait(Until.hasObject(By.text(text)),500)) return
  }
  visible(text)
 }
 private fun click(text:String){scrollTo(text);device.findObject(By.text(text)).click()}
 @Test fun canonicalDoomOwnedProductFlowCapturesExactRequiredScreens(){
  visible("Breathing reminders");capture("01-doom-dashboard-demo")
  click("Preview breathing reminder");visible("Breathe in");capture("02-doom-breathing-demo")
  click("Debug");click("Demo messages — no wait");visible("Messages stay open.");capture("03-doom-messages-demo")
  click("Try the breathing demo");visible("Take a breath.");visible("A deliberate start.",15_000);capture("04-doom-completed-demo")
 }
 @Test fun homeDefaultsNavigationAndDebugIsolation(){visible("Breathing reminders");visible("10s");visible("1m");visible("Accessibility: Not enabled");assertFalse(device.hasObject(By.text("SANITIZED STRUCTURAL REPORT")));click("Debug");scrollTo("DOOM-OWNED QUICK DEMO");scrollTo("SANITIZED STRUCTURAL REPORT");click("Home");assertFalse(device.hasObject(By.text("SANITIZED STRUCTURAL REPORT")))}
 @Test fun customCancelDismissesWithoutSaving(){
  click("Custom");visible("Custom duration");device.findObject(By.clazz("android.widget.EditText")).text="27"
  click("Cancel");visible("10s");rule.scenario.recreate();visible("10s");visible("1m")
 }
 @Test fun customSaveNormalizesDurationAndPreservesIndependentSuppression(){
  click("5m");click("Custom");visible("Custom duration")
  val field=device.findObject(By.clazz("android.widget.EditText"));field.text="27";click("Save")
  rule.scenario.recreate();visible("Custom");visible("5m")
  rule.scenario.onActivity { assertEquals(ReminderSettings(false,30,5),ReminderSettingsStore.read(it)) }
 }
 @Test fun previewRequiresTapAndContainsOnlyBreathingPresentation(){assertFalse(device.hasObject(By.text("Breathe in")));click("Preview breathing reminder");visible("Breathe in");assertFalse(device.hasObject(By.textContains("remaining")));assertFalse(device.hasObject(By.textContains("report")))}
 @Test @SupplementalEvidence fun buildFooterIsReachableAfterScrollingToTheEnd(){
  visible("Breathing reminders");click("Debug")
  scrollTo("DOOM-OWNED QUICK DEMO")
  scrollTo("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
  repeat(20){device.swipe(device.displayWidth/2,device.displayHeight/4,device.displayWidth/2,device.displayHeight*3/4,20)}
 }
}
