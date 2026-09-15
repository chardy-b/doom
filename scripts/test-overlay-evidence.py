#!/usr/bin/env python3
"""Host-only negative tests for supplementary overlay evidence provenance."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("overlay_manifest", ROOT / "scripts/overlay-evidence-manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class OverlayEvidenceManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.overlay = root / "overlay"
        self.overlay.mkdir()
        for name in MODULE.EXPECTED_SCREENSHOTS:
            (self.overlay / name).write_bytes(b"\x89PNG\r\n\x1a\nsynthetic")
        self.apk = root / "app-debug.apk"
        self.apk.write_bytes(b"tested-apk")

    def tearDown(self):
        self.temp.cleanup()

    def manifest(self):
        return MODULE.build_manifest(self.overlay, self.apk, "a" * 40, "123", "2")

    def test_exact_synthetic_set_and_apk_binding_is_accepted(self):
        manifest = self.manifest()
        self.assertEqual("doom-overlay-ui-synthetic-only", manifest["evidence_kind"])
        self.assertEqual("a" * 40, manifest["tested_sha"])
        self.assertEqual(15, len(manifest["files"]))
        self.assertEqual(self.apk.stat().st_size, manifest["apk"]["size"])

    def test_missing_or_extra_screenshot_is_rejected(self):
        (self.overlay / MODULE.EXPECTED_SCREENSHOTS[0]).unlink()
        with self.assertRaises(ValueError):
            self.manifest()
        (self.overlay / MODULE.EXPECTED_SCREENSHOTS[0]).write_bytes(b"\x89PNG\r\n\x1a\n")
        (self.overlay / "extra.png").write_bytes(b"\x89PNG\r\n\x1a\n")
        with self.assertRaises(ValueError):
            self.manifest()

    def test_non_png_and_bad_sha_are_rejected(self):
        (self.overlay / MODULE.EXPECTED_SCREENSHOTS[1]).write_bytes(b"not-png")
        with self.assertRaises(ValueError):
            self.manifest()
        with self.assertRaises(ValueError):
            MODULE.build_manifest(self.overlay, self.apk, "A" * 40, "123", "2")

    def test_supplemental_runner_pulls_before_manifest_and_canonical_is_clean(self):
        canonical = (ROOT / "scripts/ci-device.sh").read_text()
        runner = (ROOT / "scripts/ci-overlay.sh").read_text()
        self.assertNotIn("doom-overlay-ui-evidence", canonical)
        self.assertNotIn("overlay-evidence-manifest.py", canonical)
        pull = "adb pull /sdcard/Download/doom-overlay-ui-evidence/."
        self.assertLess(runner.index(pull), runner.index("python3 scripts/overlay-evidence-manifest.py"))
        self.assertIn("supplemental-apk/app-debug.apk", runner)

    def test_missing_or_empty_apk_is_rejected(self):
        self.apk.unlink()
        with self.assertRaises(ValueError): self.manifest()
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError): self.manifest()


if __name__ == "__main__":
    unittest.main(verbosity=2)
