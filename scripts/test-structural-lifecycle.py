"""Host-only source guards; Android lifecycle behavior is tested separately in CI."""
from pathlib import Path
import unittest


REPO = Path(__file__).resolve().parents[1]
SERVICE = (REPO / "app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt").read_text()
OBSERVATION = (REPO / "app/src/main/java/com/chardy/doom/Observation.kt").read_text()


class StructuralLifecycleSourceTest(unittest.TestCase):
    def test_other_package_events_return_before_reading_root_or_changing_samples(self):
        body = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split('@Suppress', 1)[0]
        guard = body.split("try {", 1)[0]
        self.assertRegex(guard, r'if \(!Observation.consent \|\| !Observation.connected \|\| '
                               r'event\?\.packageName\?\.toString\(\) != "com.instagram.android"\) return')
        self.assertNotIn("Observation.record", guard)
        self.assertNotIn("Observation.clear", guard)
        self.assertGreater(body.index("rootInActiveWindow"), body.index("return"))

    def test_delayed_instagram_event_with_mismatched_root_invalidates_current_and_recycles(self):
        body = SERVICE.split("private fun collect", 1)[1].split("override fun onInterrupt", 1)[0]
        self.assertRegex(body, r'if \(root.packageName\?\.toString\(\) != "com.instagram.android"\)\s*\{\s*'
                              r'Observation.record\(null\)\s*;?\s*return\s*\}')
        self.assertLess(body.index("queue.add(root to 0)"), body.index("try {"))
        self.assertLess(body.index("try {"), body.index("root.packageName"))
        self.assertRegex(body, r'finally \{\s*while \(queue.isNotEmpty\(\)\) '
                              r'queue.removeFirst\(\).first.recycle\(\)\s*\}')
        self.assertNotIn("Observation.clear()", body)  # Explicit baselines survive unavailability.

    def test_interrupt_marks_disconnected_and_clears_before_any_later_record(self):
        self.assertRegex(SERVICE, r'override fun onInterrupt\(\)\s*\{\s*'
                                 r'Observation.connected = false\s+Observation.clear\(\)\s*\}')
        disconnect = SERVICE.split("private fun disconnect()", 1)[1]
        self.assertRegex(disconnect, r'Observation.connected = false\s+Observation.clear\(\)')
        self.assertRegex(OBSERVATION, r'if \(consent && connected\) samples = samples.withCurrent\(sample\)')
        self.assertRegex(OBSERVATION, r'if \(consent && connected\) samples = samples.label\(label\)')

    def test_reconnection_clears_then_loads_consent_before_enabling_recording(self):
        body = SERVICE.split("override fun onServiceConnected()", 1)[1].split("override fun onAccessibilityEvent", 1)[0]
        self.assertRegex(body, r'Observation.connected = false\s+Observation.clear\(\)\s+Observation.load\(this\)\s+'
                              r'if \(!Observation.consent\) \{ disableSelf\(\); return \}\s+Observation.connected = true')


if __name__ == "__main__":
    unittest.main()
