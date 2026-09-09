package com.chardyb.doom.fixturegate

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.FutureTask

/** Disposable emulator only. Tests interact through real UI, system settings and windows. */
class CrossAppFixtureTest {
    private lateinit var automation: UiAutomation
    private lateinit var device: UiDevice
    private var oldServices = ""
    private var oldEnabled = "0"
    private var initialized = false

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Before fun setUp() {
        assertEquals("CI-only instrumentation argument required", "true",
            InstrumentationRegistry.getArguments().getString("fixtureCi"))
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("Use a disposable emulator, not a personal device", "1", shell("getprop ro.kernel.qemu"))
        oldServices = shell("settings get secure enabled_accessibility_services")
        oldEnabled = shell("settings get secure accessibility_enabled")
        require(oldServices.matches(Regex("[A-Za-z0-9_./:$-]*")))
        require(oldEnabled in setOf("0", "1", "null"))
        require(oldServices.split(':').none { it.startsWith("$GATE/") }) {
            "Disposable emulator must start with the fixture service disabled"
        }
        initialized = true
        shell("settings put secure enabled_accessibility_services null")
        shell("settings put secure accessibility_enabled 0")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am force-stop $TARGET")
        launch(GATE_COMPONENT)
        visible("TEST FIXTURE — service disconnected")
        val consent = requireNotNull(device.findObject(By.res(GATE, "fixture_consent")))
        if (!consent.isChecked) consent.click()
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        visible("TEST FIXTURE — service connected")
        shell("mkdir -p $EVIDENCE")
    }

    @After fun tearDown() {
        if (!initialized) return
        try {
            clearFixtureConsent()
        } finally {
            // Attempt both restorations even when cleanup or the first restoration fails.
            try {
                restoreSecureSetting("enabled_accessibility_services", oldServices)
            } finally {
                restoreSecureSetting("accessibility_enabled", oldEnabled)
            }
        }
    }

    private fun clearFixtureConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Cleanup must target only the fixture gate", GATE, context.packageName)
        val preferences = context.getSharedPreferences(ConsentActivity.CONSENT_FILE, Context.MODE_PRIVATE)
        // pm clear/force-stop would kill this instrumentation's target process.
        // Finish stale checkbox state and deliver consent listeners on the main thread
        // before restoring settings, so disableSelf() cannot undo that restoration.
        val cleanup = FutureTask {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<ConsentActivity>()
                .forEach { it.finish() }
            preferences.edit().clear().commit()
        }
        // FutureTask propagates failures back to JUnit instead of crashing the main thread.
        instrumentation.runOnMainSync(cleanup)
        assertTrue("Fixture consent cleanup must persist", cleanup.get())
        assertTrue("Fixture consent preferences must be empty", preferences.all.isEmpty())
    }

    private fun restoreSecureSetting(key: String, value: String) {
        require(key in setOf("enabled_accessibility_services", "accessibility_enabled"))
        val expected = if (value.isEmpty()) "null" else value
        if (expected == "null") shell("settings delete secure $key")
        else shell("settings put secure $key $expected")
        assertEquals("Failed to restore $key", expected, shell("settings get secure $key"))
    }

    private fun launch(component: String) {
        require(component in setOf(GATE_COMPONENT, TARGET_COMPONENT, SETTINGS_COMPONENT))
        val result = shell("am start -W --activity-reorder-to-front -n $component")
        assertFalse(result, result.contains("Error:"))
    }

    private fun visible(text: String, timeout: Long = 3_000) {
        assertTrue("Missing $text", device.wait(Until.hasObject(By.text(text)), timeout))
    }
    private fun click(text: String) { visible(text); requireNotNull(device.findObject(By.text(text))).click() }
    private fun noOverlay() {
        assertTrue("Overlay did not disappear", device.wait(Until.gone(By.res(GATE, "fixture_gate_marker")), 2_000))
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (fixtureOverlayExists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertFalse("System accessibility overlay still present", fixtureOverlayExists())
    }
    private fun fixtureOverlayExists(): Boolean = automation.windows.any { window ->
        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            window.root?.let { root ->
                try { root.packageName?.toString() == GATE } finally { @Suppress("DEPRECATION") root.recycle() }
            } == true
    }
    private fun gate() {
        visible("TEST FIXTURE — five-second gate")
        assertTrue("Must be TYPE_ACCESSIBILITY_OVERLAY, not an Activity", fixtureOverlayExists())
        foreground(TARGET)
    }
    private fun foreground(pkg: String) {
        val state = shell("dumpsys activity activities")
        assertTrue("Expected resumed package $pkg", state.lineSequence().any {
            (it.contains("topResumedActivity") || it.contains("mResumedActivity") || it.contains("ResumedActivity")) &&
                it.contains("$pkg/")
        })
    }
    private fun capture(name: String, pkg: String, label: String) {
        require(name in SCREENSHOTS)
        foreground(pkg)
        visible(label)
        shell("screencap -p $EVIDENCE/$name.png")
        val bytes = shell("stat -c %s $EVIDENCE/$name.png").toLongOrNull()
        assertTrue("PNG not saved", bytes != null && bytes > 0)
    }
    private fun measured(name: String, elapsed: Long) {
        require(name in setOf("dm_escape_ms", "gate_from_launch_ms", "gate_observed_ms", "background_wait_ms", "lock_wait_ms"))
        require(elapsed >= 0)
        // UiAutomation executes Runtime.exec: use real stdin, never shell redirection.
        val descriptors = automation.executeShellCommandRw("tee -a $EVIDENCE/timings.txt")
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { input ->
                input.write("$name=$elapsed\n".toByteArray(Charsets.UTF_8))
            }
            assertEquals("Measured timing must be written unchanged", "$name=$elapsed", output.readText().trim())
        }
    }

    @Test fun feedIsActualOverlayAndMessagesEscapeImmediately() {
        launch(TARGET_COMPONENT)
        gate()
        capture("01-feed-overlay", TARGET, "TEST FIXTURE — five-second gate")
        val started = SystemClock.elapsedRealtime()
        requireNotNull(device.findObject(By.res(GATE, "fixture_escape"))).click()
        visible("TEST FIXTURE — DM", 2_000)
        assertTrue("Actual fixture DM resource marker required", device.hasObject(By.res(TARGET, "fixture_dm_marker")))
        val elapsed = SystemClock.elapsedRealtime() - started
        measured("dm_escape_ms", elapsed)
        assertTrue("DM escape exceeded 2 seconds: $elapsed ms", elapsed < 2_000)
        noOverlay()
        capture("02-dm-escape", TARGET, "TEST FIXTURE — DM")
        click("Fixture unknown")
        noOverlay()
        capture("03-unknown", TARGET, "TEST FIXTURE — Unknown")
    }

    @Test fun completionGrantsOnlyCurrentForegroundFeedSession() {
        val launched = SystemClock.elapsedRealtime()
        launch(TARGET_COMPONENT)
        gate()
        val observed = SystemClock.elapsedRealtime()
        assertTrue(device.wait(Until.gone(By.res(GATE, "fixture_gate_marker")), 7_000))
        val finished = SystemClock.elapsedRealtime()
        measured("gate_from_launch_ms", finished - launched)
        measured("gate_observed_ms", finished - observed)
        assertTrue("Gate completed before five seconds from launch", finished - launched >= 5_000)
        noOverlay()
        capture("04-completed-feed", TARGET, "TEST FIXTURE — Feed")
        SystemClock.sleep(600) // cover multiple watchdog ticks and overlay removal events
        noOverlay()
        click("Fixture messages")
        visible("TEST FIXTURE — DM")
        click("Fixture feed")
        gate()
        capture("05-new-session", TARGET, "TEST FIXTURE — five-second gate")
    }

    @Test fun unknownAndOtherAppFailOpen() {
        launch(TARGET_COMPONENT)
        gate()
        device.pressBack() // public fixture navigation, not an exported automation endpoint
        visible("TEST FIXTURE — Unknown")
        noOverlay()
        capture("06-back-unknown", TARGET, "TEST FIXTURE — Unknown")
        click("Fixture feed")
        gate()
        launch(SETTINGS_COMPONENT)
        foreground("com.android.settings")
        noOverlay()
        // Capture the known fixture controller, not potentially variable system settings content.
        launch(GATE_COMPONENT)
        capture("07-other-app", GATE, "TEST FIXTURE — service connected")
    }

    @Test fun disablingActualServiceRemovesWindow() {
        launch(TARGET_COMPONENT)
        gate()
        shell("settings put secure enabled_accessibility_services null")
        shell("settings put secure accessibility_enabled 0")
        noOverlay()
        capture("08-disabled-feed", TARGET, "TEST FIXTURE — Feed")
        launch(GATE_COMPONENT)
        capture("09-disconnected", GATE, "TEST FIXTURE — service disconnected")
    }

    @Test fun backgroundAndLockCancelStaleCompletion() {
        launch(TARGET_COMPONENT)
        gate()
        device.pressHome()
        noOverlay()
        val background = SystemClock.elapsedRealtime()
        SystemClock.sleep(5_300)
        measured("background_wait_ms", SystemClock.elapsedRealtime() - background)
        // Do not move ActivityScenario to RESUMED after Home on SDK 35.
        launch(TARGET_COMPONENT)
        gate()
        capture("10-background-return", TARGET, "TEST FIXTURE — five-second gate")
        shell("input keyevent KEYCODE_SLEEP")
        noOverlay()
        val locked = SystemClock.elapsedRealtime()
        SystemClock.sleep(5_300)
        measured("lock_wait_ms", SystemClock.elapsedRealtime() - locked)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        launch(TARGET_COMPONENT)
        gate()
        capture("11-lock-return", TARGET, "TEST FIXTURE — five-second gate")
        requireNotNull(device.findObject(By.res(GATE, "fixture_leave"))).click()
        noOverlay()
        assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName).depth(0)), 3_000))
        launch(GATE_COMPONENT)
        capture("12-leave-home", GATE, "TEST FIXTURE — service connected")
    }

    @Test fun consentRevocationDisablesConnectionAndPreventsReactivation() {
        launch(TARGET_COMPONENT)
        gate()
        launch(GATE_COMPONENT)
        click("Disable and clear TEST FIXTURE consent")
        visible("TEST FIXTURE — service disconnected")
        noOverlay()
        // Test-only attempted activation without consent must self-disable.
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (shell("settings get secure enabled_accessibility_services").contains("$GATE/") &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertFalse(shell("settings get secure enabled_accessibility_services").contains("$GATE/"))
        launch(TARGET_COMPONENT)
        visible("TEST FIXTURE — Feed")
        noOverlay()
        capture("13-no-consent", TARGET, "TEST FIXTURE — Feed")
    }

    companion object {
        private const val GATE = "com.chardyb.doom.fixturegate"
        private const val TARGET = "com.chardyb.doom.testfixture"
        private const val GATE_COMPONENT = "$GATE/.ConsentActivity"
        private const val TARGET_COMPONENT = "$TARGET/.FixtureActivity"
        private const val SETTINGS_COMPONENT = "com.android.settings/.Settings"
        private const val SERVICE = "$GATE/.FixtureGateService"
        private const val EVIDENCE = "/sdcard/Download/doom-fixture-evidence"
        private val SCREENSHOTS = setOf(
            "01-feed-overlay", "02-dm-escape", "03-unknown", "04-completed-feed",
            "05-new-session", "06-back-unknown", "07-other-app", "08-disabled-feed",
            "09-disconnected", "10-background-return", "11-lock-return", "12-leave-home", "13-no-consent"
        )
    }
}
