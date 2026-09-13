"""Host-only privacy and scope guards for the WIL-182 enum trace path."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
TRACE = "\n".join(
    (ROOT / "app/src/main/java/com/chardy/doom" / name).read_text()
    for name in ("OverlayRemovalTrace.kt", "RemovalTraceStore.kt")
)
SERVICE = (ROOT / "app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt").read_text()


class RemovalTracePrivacyTest(unittest.TestCase):
    def test_trace_uses_elapsed_realtime_and_never_nano_time(self):
        store = (ROOT / "app/src/main/java/com/chardy/doom/RemovalTraceStore.kt").read_text()
        service = SERVICE
        self.assertIn("SystemClock.elapsedRealtime()", store)
        self.assertIn("monotonicClock", service)
        self.assertNotIn("System.nanoTime", store + service)

    def test_cleanup_action_type_and_terminal_truth_are_closed(self):
        calls = re.findall(r"requestSafetyCleanup\(([^\n]*)", SERVICE)
        self.assertTrue(calls)
        self.assertFalse(any("RemovalTraceAction" in call for call in calls))
        self.assertIn("NO_OVERLAY_RELEASED", TRACE)
        self.assertIn("ALREADY_DETACHED", TRACE)
        self.assertIn("WATCHDOG_ROLLBACK", TRACE)
        self.assertIn("WATCHDOG_NO_SAFE_ANCHOR", TRACE)
        self.assertIn("TRUNCATED", TRACE)
        self.assertIn("vetoedExternalAction", SERVICE)

    def test_trace_is_default_off_before_category_and_clock_work(self):
        event = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split(
            "@Suppress", 1
        )[0]
        self.assertLess(event.index("isCapturing()"), event.index("traceEventKind(event)"))
        self.assertIn("if (!RemovalTraceStore.process.isCapturing()) return", SERVICE)

    def test_capacity_coalescing_and_main_thread_contract_are_explicit(self):
        self.assertIn("TERMINAL_RESERVE", TRACE)
        self.assertIn("sameCategory", TRACE)
        self.assertIn("@Synchronized", (ROOT / "app/src/main/java/com/chardy/doom/RemovalTraceStore.kt").read_text())
    def test_trace_types_are_closed_and_have_no_runtime_payload_or_side_effects(self):
        for forbidden in (
            ".text", "contentDescription", "className", "getChild", "childCount",
            "viewIdResourceName", "nodeId", "packageName", "Log.", "println(",
            "SharedPreferences", "File(", "java.net", "Http", "setPrimaryClip",
            "AccessibilityNodeInfo", "ClipData", "Intent", "ACTION_SEND",
        ):
            self.assertNotIn(forbidden, TRACE)
        self.assertIn("enum class RemovalTraceMark", TRACE)
        self.assertIn("enum class RemovalTraceEvent", TRACE)
        self.assertIn("enum class RemovalTraceOwner", TRACE)
        self.assertIn("enum class RemovalTraceRoot", TRACE)
        self.assertIn("enum class RemovalTraceAction", TRACE)
        self.assertIn("WIL182_REMOVAL_TRACE_V2", TRACE)
        self.assertIn("EVENT_ROOT_SAFE", TRACE)
        self.assertIn("EVENT_ROOT_UNCERTAIN", TRACE)
        self.assertIn("EVENT_ROOT_MISMATCH", TRACE)
        self.assertIn("EVENT_UNCERTAINTY_EXPIRED", TRACE)
        self.assertIn("MAX_RECORDS = 64", TRACE)
        self.assertIn("MAX_OFFSET_MS = 10_000L", TRACE)
        self.assertIn("MAX_ASCII_BYTES = 8_192", TRACE)

    def test_service_trace_adapter_only_classifies_existing_package_and_event_values(self):
        adapter = SERVICE.split("private fun readPackageRoot", 1)[1].split(
            "private fun traceRecord", 1
        )[0]
        for forbidden in (".text", "contentDescription", ".className", "getChild", "childCount",
                          "viewIdResourceName", "Log.", "File(", "SharedPreferences", "ClipData",
                          "setPrimaryClip", "serializeAscii"):
            self.assertNotIn(forbidden, adapter)
        self.assertIn("event.eventType", SERVICE.split("private fun traceEventKind", 1)[1])
        self.assertIn("overlayPlatform.readRootPackage", adapter)
        self.assertIn("overlayPlatform.recycleRoot(it)", adapter)
        self.assertIn("RemovalTraceStore.process", SERVICE)
        self.assertIn("RemovalTraceMark.EVENT_ROOT_MISMATCH", SERVICE)
        self.assertIn("RemovalTraceAction.RESET_OUTSIDE", SERVICE)

    def test_trace_copy_is_only_explicit_and_separate_from_structural_report(self):
        observation = (ROOT / "app/src/main/java/com/chardy/doom/Observation.kt").read_text()
        activity = (ROOT / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()
        self.assertEqual(1, sum(p.read_text().count("setPrimaryClip(") for p in
                                (ROOT / "app/src/main/java/com/chardy/doom").glob("*.kt")))
        copy = observation.split("internal fun copyRemovalTrace", 1)[1].split("/** Explicit overlay", 1)[0]
        self.assertIn("snapshot.serializeAscii()", copy)
        self.assertIn('Action("COPY REMOVAL TRACE"', activity)
        self.assertIn('Action("REFRESH TRACE STATUS"', activity)
        self.assertIn("60-second cooldown", activity)
        self.assertIn("traceAvailability != RemovalTraceAvailability.RECORDING", activity)
        self.assertIn("traceAvailability != RemovalTraceAvailability.AVAILABLE", activity)
        self.assertNotIn("Observation.report", copy)
        self.assertNotIn("SanitizedStructuralReport", copy)

    def test_trace_schema_is_ascii_only_and_no_serializer_accepts_strings(self):
        serializer = TRACE.split("fun serializeAscii()", 1)[1].split("/** Pure", 1)[0]
        self.assertNotRegex(serializer, r"append\([^)]*String")
        self.assertIn("record.mark.name", serializer)
        self.assertIn("<= RemovalTraceRecorder.MAX_ASCII_BYTES", serializer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
