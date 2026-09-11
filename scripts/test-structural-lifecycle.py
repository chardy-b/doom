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

    def test_delayed_instagram_event_with_doom_root_preserves_current_and_recycles(self):
        body = SERVICE.split("private fun collect", 1)[1].split("override fun onInterrupt", 1)[0]
        self.assertIn("applicationContext.packageName", body)
        self.assertNotIn("BuildConfig.APPLICATION_ID", body)
        self.assertNotIn('== "com.chardy.doom"', body)
        self.assertRegex(body, r'if \(root.packageName\?\.toString\(\) != "com.instagram.android"\)\s*\{\s*'
                              r'Observation.record\(null\)\s*;?\s*return\s*\}')
        self.assertLess(body.index("queue.add(root to 0)"), body.index("try {"))
        self.assertLess(body.index("try {"), body.index("root.packageName"))
        self.assertRegex(body, r'finally \{\s*while \(queue.isNotEmpty\(\)\) '
                              r'queue.removeFirst\(\).first.recycle\(\)\s*\}')
        self.assertNotIn("Observation.clear()", body)

        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        preservation = tests.split("@Test fun doomEventsPreserveReportButForeignOrMissingRootInvalidatesAllReportState", 1)[1].split("@Test", 1)[0]
        self.assertIn('getDeclaredMethod("attachBaseContext", Context::class.java)', preservation)
        self.assertIn('invoke(service, rule.activity.applicationContext)', preservation)
        self.assertIn('collectSyntheticRoot(service, rule.activity.packageName)', preservation)
        self.assertNotIn('sendEvent(service, rule.activity.packageName)', preservation)
        self.assertIn('listOf("com.example.foreign", null)', tests)
        self.assertIn("rule.activity.packageName", tests)
        self.assertNotIn('listOf(rule.activity.packageName, null)', tests)

    def test_interrupt_marks_disconnected_and_clears_before_any_later_record(self):
        self.assertRegex(SERVICE, r'override fun onInterrupt\(\)\s*\{\s*'
                                 r'Observation.connected = false\s+Observation.clear\(\)\s*\}')
        disconnect = SERVICE.split("private fun disconnect()", 1)[1]
        self.assertRegex(disconnect, r'Observation.connected = false\s+Observation.clear\(\)')
        record = OBSERVATION.split('internal fun record(', 1)[1].split('fun revealReport()', 1)[0]
        self.assertRegex(record, r'if \(!consent \|\| !connected\) return\s+clear\(\)\s+report = sample')

    def test_reconnection_clears_then_loads_consent_before_enabling_recording(self):
        body = SERVICE.split("override fun onServiceConnected()", 1)[1].split("override fun onAccessibilityEvent", 1)[0]
        self.assertRegex(body, r'Observation.connected = false\s+Observation.clear\(\)\s+Observation.load\(this\)\s+'
                              r'if \(!Observation.consent\) \{ disableSelf\(\); return \}\s+Observation.connected = true')

    def test_fresh_consent_and_no_superseded_implementation(self):
        self.assertIn('"sanitized_structural_report_v1"', OBSERVATION)
        self.assertFalse((REPO / "app/src/main/java/com/chardy/doom/StructuralFingerprint.kt").exists())
        ui = (REPO / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()
        for old in ("SampleLabel", "similarity", "Opaque fingerprint", "LABEL FEED"):
            self.assertNotIn(old, ui)
        self.assertIn("REVEAL LOCAL REPORT", ui)
        self.assertIn("COPY REVIEWED REPORT", ui)

    def test_collector_reads_only_approved_metadata(self):
        import re
        reads = set(re.findall(r"node\.([A-Za-z]+)", SERVICE))
        self.assertEqual(reads, {"packageName", "childCount", "viewIdResourceName", "className", "isClickable",
                                 "isScrollable", "isEditable", "isSelected", "isChecked", "getChild", "recycle"})
        for forbidden in ("performAction", "performGlobalAction", "dispatchGesture", "takeScreenshot", "Log."):
            self.assertNotIn(forbidden, SERVICE)
        self.assertIn("builder.markTruncated()", SERVICE)

    def test_disclosures_explain_static_resource_discovery_and_new_bounds(self):
        for path in ("README.md", "app/src/main/java/com/chardy/doom/MainActivity.kt",
                     "app/src/main/res/values/strings.xml"):
            source = (REPO / path).read_text()
            for required in ("static Instagram resource names", "64 unique", "8,192", "UI text"):
                self.assertIn(required, source, path)
            for obsolete in ("allowlisted static", "closed resource vocabulary", "32 unique", "4,096"):
                self.assertNotIn(obsolete, source, path)

    def test_copy_is_guarded_and_not_automatic(self):
        self.assertIn("fun copyReport(context: Context)", OBSERVATION)
        body = OBSERVATION.split("fun copyReport(context: Context)", 1)[1].split("fun clear()", 1)[0]
        self.assertIn("if (!canCopy) return", body)
        self.assertLess(body.index("if (!canCopy) return"), body.index("setPrimaryClip"))
        self.assertIn("ClipData.newPlainText", body)
        self.assertIn("consent && connected && report != null", OBSERVATION)
        self.assertIn("canReveal && revealed", OBSERVATION)
        self.assertIn("revealed = false", OBSERVATION)
        self.assertIn("copied = false", OBSERVATION)

    def test_connection_test_does_not_call_protected_callback_directly(self):
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        self.assertNotIn("service.onServiceConnected()", tests)
        self.assertIn('getDeclaredMethod("onServiceConnected")', tests)


    def test_process_start_is_empty_and_all_clear_state_is_memory_only(self):
        self.assertIn("var connected by mutableStateOf(false)", OBSERVATION)
        self.assertIn("var consent by mutableStateOf(false)", OBSERVATION)
        self.assertIn("var report by mutableStateOf<SanitizedStructuralReport?>(null)", OBSERVATION)
        for field in ("revealed", "copied"):
            self.assertIn(f"var {field} by mutableStateOf(false)", OBSERVATION)
        clear = OBSERVATION.split("fun clear()", 1)[1]
        self.assertRegex(clear, r'report = null\s+revealed = false\s+copied = false')
        for forbidden in ("putString", "putInt", "putLong", "File(", "rememberSaveable", "SavedStateHandle"):
            self.assertNotIn(forbidden, OBSERVATION)
        self.assertIn('if (!consent) clear()', OBSERVATION)

    def test_app_permissions_backup_and_observation_scope(self):
        import xml.etree.ElementTree as ET
        ns = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(REPO / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual([], manifest.findall("uses-permission"))
        app = manifest.find("application")
        self.assertEqual("false", app.get(ns + "allowBackup"))
        self.assertEqual("false", app.get(ns + "fullBackupContent"))
        self.assertEqual("android.permission.BIND_ACCESSIBILITY_SERVICE", app.find("service").get(ns + "permission"))
        config = ET.parse(REPO / "app/src/main/res/xml/accessibility_service_config.xml").getroot()
        self.assertEqual("com.instagram.android", config.get(ns + "packageNames"))
        self.assertEqual("typeWindowStateChanged|typeWindowContentChanged", config.get(ns + "accessibilityEventTypes"))
        self.assertEqual("flagReportViewIds", config.get(ns + "accessibilityFlags"))
        self.assertEqual("false", config.get(ns + "isAccessibilityTool"))


    def test_traversal_bounds_cover_enqueued_nodes_depth_and_missing_children(self):
        self.assertIn("while (queue.isNotEmpty() && nodes < SanitizedStructuralReport.MAX_NODES)", SERVICE)
        self.assertIn("if (depth < SanitizedStructuralReport.MAX_DEPTH)", SERVICE)
        self.assertIn("SanitizedStructuralReport.MAX_NODES - nodes - queue.size", SERVICE)
        self.assertIn("if (limit < children) builder.markTruncated()", SERVICE)
        self.assertIn("if (child == null) builder.markTruncated() else queue.add(child to depth + 1)", SERVICE)
        self.assertIn("if (queue.isNotEmpty()) builder.markTruncated()", SERVICE)
        self.assertRegex(SERVICE, r'finally \{\s+node.recycle\(\)\s+\}')

    def test_report_paths_have_no_implicit_export_or_persistence(self):
        import re
        production = REPO / "app/src/main/java/com/chardy/doom"
        files = {p.name: p.read_text() for p in production.glob("*.kt")}
        self.assertEqual(1, sum(source.count("setPrimaryClip(") for source in files.values()))
        self.assertEqual(1, sum(source.count("Observation.copyReport(context)") for source in files.values()))
        ui = files["MainActivity.kt"]
        self.assertIn('Action("COPY REVIEWED REPORT", enabled = Observation.canCopy) { Observation.copyReport(context) }', ui)
        self.assertIn('if (Observation.canReveal && Observation.revealed && report != null)', ui)
        for name in ("SanitizedStructuralReport.kt", "Observation.kt", "DoomAccessibilityService.kt"):
            for forbidden in (r'\bLog\.', r'\bprintln\(', r'\bprintStackTrace\(', r'\bFile\(',
                              r'java\.net', r'java\.io', r'android\.graphics', r'ACTION_SEND',
                              r'openFileOutput', r'putString', r'putStringSet', r'SavedStateHandle'):
                self.assertIsNone(re.search(forbidden, files[name]), f"{name}: {forbidden}")


    def test_foreign_or_unattributed_children_are_skipped_before_metadata_reads(self):
        body = SERVICE.split("val (node, depth) = queue.removeFirst()", 1)[1]
        self.assertRegex(body, r'if \(node.packageName\?\.toString\(\) != "com.instagram.android"\) \{\s+'
                              r'builder.markTruncated\(\)\s+continue\s+\}')
        self.assertLess(body.index("node.packageName"), body.index("node.childCount"))


if __name__ == "__main__":
    unittest.main()
