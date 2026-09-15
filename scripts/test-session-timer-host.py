#!/usr/bin/env python3
"""Source contracts for WIL-180 privacy and physical-window ownership."""
from pathlib import Path
import unittest
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt").read_text()
TIMER = (ROOT / "app/src/main/java/com/chardy/doom/InstagramSessionTimer.kt").read_text()
VIEW = (ROOT / "app/src/main/java/com/chardy/doom/InstagramTimerOverlayView.kt").read_text()

class TimerContract(unittest.TestCase):
    def test_cutout_reads_are_api_guarded_for_min_sdk_26(self):
        self.assertIn("@TargetApi(Build.VERSION_CODES.P)\n    private fun cutoutInsets", VIEW)
        self.assertGreaterEqual(VIEW.count("if (Build.VERSION.SDK_INT >= 28) cutoutInsets("), 2)
        self.assertNotIn("cutout?.safeInset", VIEW)

    def test_only_successful_terminal_episode_is_retired_in_event_path(self):
        retirement = SERVICE.split("// Only a verified event after a successful", 1)[1].split("val activeTicket", 1)[0]
        self.assertIn("ticket != null && terminalGateSucceeded", retirement)
        self.assertNotIn("EntryGateState.BYPASSED", retirement)
        self.assertNotIn("EntryGateState.GRANTED", retirement)
        self.assertNotIn("generation != entryGate.generation", retirement)

    def test_no_window_boundary_is_one_shot_and_session_bound(self):
        boundary = SERVICE.split("private fun scheduleSessionBoundaryCheck()", 1)[1].split("private fun sampleTimerAuthority", 1)[0]
        self.assertIn("session != timerSessionEpoch", boundary)
        self.assertIn("sessionBoundaryCheck !== this", boundary)
        self.assertIn("sample.packageName != INSTAGRAM", boundary)
        self.assertNotIn("WATCHDOG_INTERVAL_MS", boundary)
        self.assertIn("cancelSessionBoundaryCheck()", SERVICE.split("private fun endTimerSession()", 1)[1].split("private fun stopTimerCallbacks", 1)[0])

    def test_failed_route_retires_episode_and_resumes_timer(self):
        repair = SERVICE.split("private fun recoverFailedMessagesRoute", 1)[1].split("private fun hasDetachedTerminalAuthority", 1)[0]
        self.assertIn("entryGate.leaveInstagram()", repair)
        self.assertIn("ticket = null", repair)
        self.assertIn("attachTimerIfAllowed()", repair)
        self.assertNotIn("recordTerminal", repair)

    def test_configuration_update_keeps_physical_params_and_checks_epoch(self):
        self.assertIn("override fun onConfigurationChanged(newConfig: Configuration)", SERVICE)
        update = SERVICE.split("private fun updateTimerLayout", 1)[1].split("private fun renderTimer", 1)[0]
        self.assertIn("session != timerSessionEpoch", update)
        self.assertIn("overlayWindowUpdater(manager, view, params)", update)
        self.assertIn("endTimerSession()", update)
        self.assertIn("timerParams = null", SERVICE)

    def test_all_authority_is_required_to_start_and_render(self):
        self.assertIn("consent: Boolean, gateConsent: Boolean, connected: Boolean", TIMER)
        self.assertIn("!consent || !gateConsent || !connected", TIMER)
        self.assertIn("timerWindowCurrent(epoch) && verifyTimerAuthority()", SERVICE)

    def test_attach_has_immediate_second_root_recheck(self):
        attach = SERVICE.split("private fun attachTimerIfAllowed()", 1)[1].split("private fun renderTimer", 1)[0]
        self.assertEqual(2, attach.count("freshTimerAuthority()"))
        self.assertLess(attach.rindex("freshTimerAuthority()"), attach.index("overlayWindowInstaller"))
        self.assertIn("timerWatchdog = object : Runnable", attach)
        self.assertIn("handler.postDelayed(this, WATCHDOG_INTERVAL_MS)", attach)

    def test_terminal_cleanup_precedes_disable_and_owner_release_waits_for_detach(self):
        disable = SERVICE.split("fun disableObservation()", 1)[1].split("}", 1)[0]
        self.assertLess(disable.index("endTimerSession()"), disable.index("disableSelf()"))
        finish = SERVICE.split("private fun finishTimerDetach", 1)[1].split("private fun installOverlay", 1)[0]
        self.assertIn("overlayPlatform.isAttached(old)", finish)
        self.assertIn("timerView = null", finish)

    def test_no_private_content_or_new_capability(self):
        combined = TIMER + VIEW
        for forbidden in ("AccessibilityNodeInfo", "contentDescription?", "viewIdResourceName", "SharedPreferences", "INTERNET"):
            self.assertNotIn(forbidden, combined)

if __name__ == "__main__":
    spec = importlib.util.spec_from_file_location("host", ROOT / "scripts/test-entry-gate-host.py")
    host = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(host)
    host.MAIN = ["InstagramSessionTimer.kt"]
    host.TESTS = ["InstagramSessionTimerTest.kt"]
    host.TEST_CLASSES = ["com.chardy.doom.InstagramSessionTimerTest"]
    host.main()
    unittest.main(verbosity=2)
