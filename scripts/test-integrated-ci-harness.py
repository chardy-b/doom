#!/usr/bin/env python3
"""Host tests for strict provenance and bounded integrated emulator readiness."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True,
                       env=self.git_env())
        subprocess.run(["git", "config", "user.name", "Harness Test"], cwd=self.root, check=True,
                       env=self.git_env())
        subprocess.run(["git", "config", "user.email", "harness@example.invalid"], cwd=self.root,
                       check=True, env=self.git_env())
        (self.root / "tracked.txt").write_text("base\n")
        (self.root / ".gitignore").write_text(".kotlin/\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True, env=self.git_env())
        subprocess.run(["git", "commit", "-qm", "base"], cwd=self.root, check=True, env=self.git_env())
        self.sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root,
                                           env=self.git_env(), text=True).strip()

    def tearDown(self):
        self.temp.cleanup()

    def git_env(self):
        empty = str(self.root / "no-global-config")
        return os.environ | {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": empty,
                             "HOME": str(self.root)}

    def verify(self):
        env = self.git_env() | {"GITHUB_ACTIONS": "true", "CANDIDATE_SHA": self.sha,
                                "HELPER": str(ROOT / "scripts/ci-provenance.sh")}
        return subprocess.run(["bash", "-e", "-c", '. "$HELPER"; printf "sha=%s\\n" "$sha"'],
                              cwd=self.root, env=env, capture_output=True, text=True)

    def test_exact_sha_and_explicit_generated_state(self):
        (self.root / ".kotlin" / "sessions").mkdir(parents=True)
        (self.root / ".kotlin" / "sessions" / "state.bin").write_bytes(b"generated")
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"sha={self.sha}\n", result.stdout)
        bad = self.git_env() | {"GITHUB_ACTIONS": "true", "CANDIDATE_SHA": "b" * 40,
                                "HELPER": str(ROOT / "scripts/ci-provenance.sh")}
        result = subprocess.run(["bash", "-e", "-c", '. "$HELPER"'], cwd=self.root, env=bad,
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)

    def test_tracked_and_staged_edits_are_rejected(self):
        (self.root / "tracked.txt").write_text("edited\n")
        self.assertEqual(2, self.verify().returncode)
        subprocess.run(["git", "add", "tracked.txt"], cwd=self.root, check=True, env=self.git_env())
        self.assertEqual(2, self.verify().returncode)

    def test_arbitrary_untracked_paths_are_rejected_without_content_leak(self):
        for relative in ("Leak.kt", "settings.gradle.kts", "gradle/unsafe.conf", "src/new.kt"):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            secret = "DO-NOT-PRINT-SECRET"
            path.write_text(secret)
            result = self.verify()
            self.assertEqual(2, result.returncode, relative)
            self.assertIn(relative, result.stderr)
            self.assertNotIn(secret, result.stderr + result.stdout)
            path.unlink()


spec = importlib.util.spec_from_file_location("emulator_readiness", ROOT / "scripts/emulator-readiness.py")
readiness = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(readiness)


class Completed:
    def __init__(self, output="", code=0):
        self.stdout, self.stderr, self.returncode = output, "", code


class ReadinessTests(unittest.TestCase):
    def run_gate(self, policy, activities="topResumedActivity=ActivityRecord{x com.chardyb.doom/.MainActivity t1}\n"):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "app/build/outputs/apk/debug/app-debug.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            calls = []
            outputs = iter(["device\n", "1\n", "35\n", "1\n", "package:android\n",
                            "", "", "", "", "", policy, activities])

            def run(argv, **kwargs):
                calls.append((argv, kwargs))
                return Completed(next(outputs))

            env = {"GITHUB_ACTIONS": "true", "ANDROID_SERIAL": "emulator-5554"}
            with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(readiness.subprocess, "run", run):
                old = Path.cwd()
                os.chdir(root)
                try:
                    result = readiness.main(["emulator-readiness.py", str(Path("app/build/outputs/apk/debug/app-debug.apk"))])
                finally:
                    os.chdir(old)
            return result, calls

    def test_each_known_unlocked_format_and_command_contract(self):
        formats = ("showing=false", "mShowingLockscreen=false", "isStatusBarKeyguard=false",
                   "KeyguardServiceDelegate state: showing=false")
        for policy in formats:
            result, calls = self.run_gate(policy)
            self.assertEqual(0, result)
            argv = [call[0] for call in calls]
            self.assertTrue(all(a[:3] == ["adb", "-s", "emulator-5554"] for a in argv))
            self.assertTrue(all(call[1]["stdout"] == subprocess.PIPE and
                                call[1]["stderr"] == subprocess.PIPE for call in calls))
            self.assertTrue(all(0 < call[1]["timeout"] <= 10 for call in calls))
            self.assertLess(argv.index(["adb", "-s", "emulator-5554", "shell", "wm", "dismiss-keyguard"]),
                            argv.index(["adb", "-s", "emulator-5554", "install", "-r",
                                        "app/build/outputs/apk/debug/app-debug.apk"]))

    def test_missing_true_ambiguous_keyguard_and_missing_resumed_fail_closed(self):
        for policy in ("", "showing=true", "showing=false\nisStatusBarKeyguard=true"):
            with self.assertRaises(readiness.NotReady):
                self.run_gate(policy)
        with self.assertRaises(readiness.NotReady):
            self.run_gate("isStatusBarKeyguard=false", "mResumedActivity: com.android.launcher/.Launcher")

    def test_serial_apk_and_deadlines_are_hard_requirements(self):
        with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "ANDROID_SERIAL": "device-secret"}, clear=True):
            with self.assertRaises(readiness.NotReady):
                readiness.main(["emulator-readiness.py", "missing.apk"])
        self.assertEqual(120.0, readiness.TOTAL_SECONDS)
        self.assertEqual(10.0, readiness.COMMAND_SECONDS)

    def test_command_timeout_is_sanitized_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "app/build/outputs/apk/debug/app-debug.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            secret = "ADB-OUTPUT-MUST-NOT-LEAK"

            def timeout(*args, **kwargs):
                raise subprocess.TimeoutExpired(args[0], kwargs["timeout"], output=secret, stderr=secret)

            env = {"GITHUB_ACTIONS": "true", "ANDROID_SERIAL": "emulator-5554"}
            old = Path.cwd()
            os.chdir(root)
            try:
                with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(readiness.subprocess, "run", timeout):
                    with self.assertRaisesRegex(readiness.NotReady, "timed out") as failure:
                        readiness.main(["emulator-readiness.py", "app/build/outputs/apk/debug/app-debug.apk"])
            finally:
                os.chdir(old)
            self.assertNotIn(secret, str(failure.exception))


class PlacementTests(unittest.TestCase):
    def test_entry_scripts_use_present_tracked_helpers_before_adb(self):
        helpers = ("scripts/ci-provenance.sh", "scripts/emulator-readiness.py")
        tracked = set(subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines())
        for helper in helpers:
            self.assertIn(helper, tracked)
            self.assertTrue((ROOT / helper).is_file())
        for name in ("ci-device.sh", "ci-supplemental.sh", "ci-overlay.sh", "ci-fixture.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn(". scripts/ci-provenance.sh", text)
        for name in ("ci-device.sh", "ci-supplemental.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("python3 scripts/emulator-readiness.py", text)
            self.assertLess(text.index(". scripts/ci-provenance.sh"), text.index("python3 scripts/emulator-readiness.py"))
            first_adb = text.find("adb ")
            self.assertTrue(first_adb == -1 or text.index("python3 scripts/emulator-readiness.py") < first_adb)


if __name__ == "__main__":
    unittest.main(verbosity=2)
