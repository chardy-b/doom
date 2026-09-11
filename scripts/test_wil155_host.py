#!/usr/bin/env python3
"""Host-only guards for WIL-155 resource, manifest, and backup invariants."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
RULES = (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
ICON = (ROOT / "app/src/main/res/drawable/ic_launcher.xml").read_text()
COLORS = ROOT / "app/src/main/res/values/colors.xml"


class Wil155HostGuards(unittest.TestCase):
    def test_source_manifest_declares_required_exports_and_dependency_providers_are_absent(self):
        self.assertIn('android:icon="@drawable/ic_launcher"', MANIFEST)
        self.assertRegex(MANIFEST, r'\.MainActivity" android:exported="true"')
        self.assertRegex(MANIFEST, r'\.DoomAccessibilityService"[^>]*android:exported="true"')
        self.assertNotIn("ui-test-manifest", GRADLE)
        self.assertNotIn('debugImplementation("androidx.compose.ui:ui-tooling")', GRADLE)
        self.assertIn('implementation("androidx.compose.ui:ui-tooling-preview")', GRADLE)

    def test_instrumentation_proves_merged_activity_exports(self):
        test = (ROOT / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        self.assertIn("getPackageInfo", test)
        self.assertIn("PackageManager", test)
        self.assertRegex(test, r"GET_ACTIVITIES\s+or\s+PackageManager\.MATCH_DISABLED_COMPONENTS")
        self.assertIn("androidx.compose.ui.tooling.PreviewActivity", test)
        self.assertIn("androidx.activity.ComponentActivity", test)
        self.assertIn("targetActivity", test)
        self.assertRegex(test, r"targetActivity\?\.let\(forbiddenActivityNames::contains\)")
        self.assertRegex(test, r"exportedActivities.*map \{ it\.name \}")
        self.assertIn("assertEquals(listOf(\"com.chardy.doom.MainActivity\"), exportedActivities)", test)

    def test_backup_and_network_boundaries_remain_closed(self):
        self.assertIn('android:allowBackup="false"', MANIFEST)
        self.assertIn('android:fullBackupContent="false"', MANIFEST)
        self.assertIn('android:dataExtractionRules="@xml/data_extraction_rules"', MANIFEST)
        self.assertEqual(10, RULES.count('<exclude domain='))
        self.assertNotIn("android.permission.INTERNET", MANIFEST)

    def test_icon_is_app_owned_original_geometry(self):
        self.assertIn("App-owned original", ICON)
        for color in ("#171B25", "#73B39C", "#F3E7CF"):
            self.assertIn(color, ICON)
        self.assertGreaterEqual(ICON.count("android:pathData"), 4)

    def test_known_lint_cleanup_is_not_a_suppression(self):
        self.assertFalse(COLORS.exists())
        self.assertNotRegex(MANIFEST, r'tools:ignore')
        self.assertNotRegex(GRADLE, r'lint\s*\{')


if __name__ == "__main__":
    unittest.main(verbosity=2)
