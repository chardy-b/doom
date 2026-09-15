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
    POLICY_UNLOCKED = """WINDOW MANAGER POLICY STATE
  KeyguardServiceDelegate
    showing=false
    secure=true
"""

    def run_gate(self, policy=None, activities="topResumedActivity=ActivityRecord{x com.chardyb.doom/.MainActivity t1}\n",
                 boot=None, qemu=("1\n", "\n"), _sleep=None):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "app/build/outputs/apk/debug/app-debug.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            calls = []
            boot = list(boot or ["1\n"])
            state = {"boot": 0, "policy": 0, "activities": 0}
            policies = policy if isinstance(policy, list) else [policy or self.POLICY_UNLOCKED]
            activity_values = activities if isinstance(activities, list) else [activities]

            def run(argv, **kwargs):
                calls.append((argv, kwargs))
                args = argv[3:]
                if args == ["get-state"]: return Completed("device\n")
                if args == ["shell", "getprop", "sys.boot_completed"]:
                    value = boot[min(state["boot"], len(boot) - 1)]; state["boot"] += 1; return Completed(value)
                if args == ["shell", "getprop", "ro.build.version.sdk"]: return Completed("35\n")
                if args == ["shell", "getprop", "ro.boot.qemu"]: return Completed(qemu[0])
                if args == ["shell", "getprop", "ro.kernel.qemu"]: return Completed(qemu[1])
                if args == ["shell", "cmd", "package", "path", "android"]: return Completed("package:android\n")
                if args == ["shell", "dumpsys", "window", "policy"]:
                    value = policies[min(state["policy"], len(policies) - 1)]; state["policy"] += 1; return Completed(value)
                if args == ["shell", "dumpsys", "activity", "activities"]:
                    value = activity_values[min(state["activities"], len(activity_values) - 1)]; state["activities"] += 1; return Completed(value)
                return Completed()

            env = {"GITHUB_ACTIONS": "true", "EMULATOR_PORT": "5554"}
            with mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(readiness.subprocess, "run", run), \
                 mock.patch.object(readiness.time, "sleep", side_effect=_sleep):
                old = Path.cwd()
                os.chdir(root)
                try:
                    result = readiness.main(["emulator-readiness.py", str(Path("app/build/outputs/apk/debug/app-debug.apk"))])
                finally:
                    os.chdir(old)
            return result, calls

    def test_each_known_unlocked_format_and_command_contract(self):
        formats = ("WindowManagerPolicy\n  mShowingLockscreen=false\n",
                   "WindowManagerPolicy\n  isStatusBarKeyguard=false\n", self.POLICY_UNLOCKED)
        for policy in formats:
            result, calls = self.run_gate(policy)
            self.assertEqual(0, result)
            argv = [call[0] for call in calls]
            self.assertTrue(all(a[:3] == ["adb", "-s", "emulator-5554"] for a in argv))
            self.assertTrue(all(call[1]["stdout"] == subprocess.PIPE and
                                call[1]["stderr"] == subprocess.PIPE for call in calls))
            self.assertTrue(all(0 < call[1]["timeout"] <= 60 for call in calls))
            install = [call for call in calls if call[0][3:5] == ["install", "-r"]]
            self.assertEqual(60.0, install[0][1]["timeout"])
            self.assertLess(argv.index(["adb", "-s", "emulator-5554", "shell", "wm", "dismiss-keyguard"]),
                            argv.index(["adb", "-s", "emulator-5554", "install", "-r",
                                        "app/build/outputs/apk/debug/app-debug.apk"]))

            install_i = argv.index(["adb", "-s", "emulator-5554", "install", "-r", "app/build/outputs/apk/debug/app-debug.apk"])
            launch_i = argv.index(["adb", "-s", "emulator-5554", "shell", "am", "start", "-W", "-n", readiness.ACTIVITY])
            proof_i = argv.index(["adb", "-s", "emulator-5554", "shell", "dumpsys", "activity", "activities"])
            clear_i = argv.index(["adb", "-s", "emulator-5554", "shell", "pm", "clear", readiness.PACKAGE])
            stop_i = argv.index(["adb", "-s", "emulator-5554", "shell", "am", "force-stop", readiness.PACKAGE])
            self.assertLess(install_i, launch_i); self.assertLess(launch_i, proof_i)
            self.assertLess(proof_i, clear_i); self.assertLess(clear_i, stop_i)

    def test_keyguard_parser_is_scoped_and_conflicts_fail_closed(self):
        self.assertFalse(readiness.keyguard_is_unlocked("random showing=false"))
        realistic = """WINDOW MANAGER POLICY STATE (dumpsys window policy)
  KeyguardServiceDelegate
    showing=false
    secure=true
  StatusBarController:
    state=NORMAL
    showing=true
"""
        self.assertTrue(readiness.keyguard_is_unlocked(realistic))
        for active in ("isStatusBarKeyguard=true", "mShowingLockscreen=true",
                       "mKeyguardShowing=true", "keyguardShowing=true"):
            self.assertFalse(readiness.keyguard_is_unlocked(realistic + active))
        self.assertFalse(readiness.keyguard_is_unlocked("  KeyguardServiceDelegate\n    showing=true\n"))
        self.assertFalse(readiness.keyguard_is_unlocked("  KeyguardServiceDelegate\n    secure=true\n"))
        self.assertFalse(readiness.keyguard_is_unlocked(
            "  KeyguardServiceDelegate\n    showing=false\n    showing=true\n"))

    def test_both_api35_emulator_properties_and_neither(self):
        self.assertEqual(0, self.run_gate(qemu=("1\n", "\n"))[0])
        self.assertEqual(0, self.run_gate(qemu=("\n", "1\n"))[0])
        self.assertFalse(readiness.has_emulator_identity("\n", "\n"))

    def test_polling_models_boot_unlock_and_foreground_transitions(self):
        result, calls = self.run_gate(
            policy=["  KeyguardServiceDelegate\n    showing=true\n", self.POLICY_UNLOCKED],
            activities=["topResumedActivity=com.android.launcher/.Launcher\n",
                        "topResumedActivity=com.chardyb.doom/.MainActivity\n"],
            boot=["0\n", "1\n"])
        self.assertEqual(0, result)
        self.assertGreaterEqual(sum(c[0][-1] == "sys.boot_completed" for c in calls), 2)
        self.assertGreaterEqual(sum(c[0][-1] == "activities" for c in calls), 2)

    def test_port_validation_cases_are_independent(self):
        self.assertEqual("emulator-5554", readiness.emulator_serial("5554"))
        for value in ("", "port", "5554;id", "5553", "5552", "5684"):
            with self.subTest(value=value), self.assertRaises(readiness.NotReady):
                readiness.emulator_serial(value)
        self.assertEqual(120.0, readiness.TOTAL_SECONDS)
        self.assertEqual(10.0, readiness.COMMAND_SECONDS)
        self.assertGreater(readiness.INSTALL_SECONDS, readiness.COMMAND_SECONDS)

    def test_missing_apk_is_independent_of_valid_port(self):
        with tempfile.TemporaryDirectory() as td:
            old = Path.cwd()
            os.chdir(td)
            try:
                env = {"GITHUB_ACTIONS": "true", "EMULATOR_PORT": "5554"}
                with mock.patch.dict(os.environ, env, clear=True), \
                     mock.patch.object(readiness.subprocess, "run") as run, \
                     mock.patch.object(readiness.time, "monotonic") as monotonic, \
                     mock.patch.object(readiness.time, "sleep") as sleep:
                    with self.assertRaisesRegex(readiness.NotReady, "APK"):
                        readiness.main(["emulator-readiness.py",
                                        "app/build/outputs/apk/debug/app-debug.apk"])
                    run.assert_not_called(); monotonic.assert_not_called(); sleep.assert_not_called()
            finally:
                os.chdir(old)

    def test_keyguard_remaining_locked_expires_at_unlock_stage_without_io_leak(self):
        self.assert_deadline_stage(
            "unlock", policy="  KeyguardServiceDelegate\n    showing=true\n")

    def test_launcher_remaining_foreground_expires_at_foreground_stage(self):
        self.assert_deadline_stage(
            "foreground", activities="topResumedActivity=com.android.launcher/.Launcher\n")

    def assert_deadline_stage(self, stage, **gate_kwargs):
        clock = [0.0]

        def monotonic():
            return clock[0]

        def sleep(seconds):
            clock[0] += seconds

        with mock.patch.object(readiness.time, "monotonic", monotonic), \
             mock.patch.object(readiness.time, "sleep", sleep):
            with self.assertRaisesRegex(readiness.NotReady,
                                        rf"^{stage}: hard readiness deadline expired$"):
                self.run_gate(_sleep=sleep, **gate_kwargs)
        self.assertLessEqual(clock[0], readiness.TOTAL_SECONDS)

    def test_command_timeout_is_sanitized_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "app/build/outputs/apk/debug/app-debug.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            secret = "ADB-OUTPUT-MUST-NOT-LEAK"

            def timeout(*args, **kwargs):
                raise subprocess.TimeoutExpired(args[0], kwargs["timeout"], output=secret, stderr=secret)

            env = {"GITHUB_ACTIONS": "true", "EMULATOR_PORT": "5554"}
            old = Path.cwd()
            os.chdir(root)
            try:
                clock = iter([0.0, 0.0, 119.5, 120.0])
                with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(readiness.subprocess, "run", timeout), mock.patch.object(readiness.time, "monotonic", side_effect=lambda: next(clock)), mock.patch.object(readiness.time, "sleep"):
                    with self.assertRaisesRegex(
                            readiness.NotReady,
                            r"^online: hard readiness deadline expired$") as failure:
                        readiness.main(["emulator-readiness.py", "app/build/outputs/apk/debug/app-debug.apk"])
            finally:
                os.chdir(old)
            self.assertNotIn(secret, str(failure.exception))


class PlacementTests(unittest.TestCase):
    @staticmethod
    def top_level_commands(name):
        commands, depth = [], 0
        for line in (ROOT / "scripts" / name).read_text().splitlines():
            stripped = line.strip()
            if depth == 0 and stripped and not stripped.startswith("#"):
                commands.append(stripped)
            depth += line.count("{") - line.count("}")
        return commands

    def test_entry_scripts_use_present_tracked_helpers_before_adb(self):
        helpers = ("scripts/ci-provenance.sh", "scripts/emulator-readiness.py")
        tracked = set(subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines())
        for helper in helpers:
            self.assertIn(helper, tracked)
            self.assertTrue((ROOT / helper).is_file())
        for name in ("ci-device.sh", "ci-supplemental.sh", "ci-overlay.sh", "ci-fixture.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn(". scripts/ci-provenance.sh", text)
        for name in ("ci-device.sh", "ci-overlay.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("python3 scripts/emulator-readiness.py", text)
            self.assertLess(text.index(". scripts/ci-provenance.sh"), text.index("python3 scripts/emulator-readiness.py"))
            self.assertLess(text.index("trap "), text.index("python3 scripts/emulator-readiness.py"))

    def test_supplemental_has_no_duplicate_readiness_and_collectors_are_bounded(self):
        self.assertNotIn("emulator-readiness.py", (ROOT / "scripts/ci-supplemental.sh").read_text())
        for name in ("ci-device.sh", "ci-overlay.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertLess(text.index(". scripts/ci-provenance.sh"), text.index("mkdir -p"))
            self.assertLess(text.index("trap "), text.index("python3 scripts/emulator-readiness.py"))
            self.assertIn("timeout 8s adb -s \"$serial\"", text)
            self.assertNotIn("adb shell", text)

    def test_entry_traps_collect_failures_but_are_disarmed_after_success_artifacts(self):
        expected_last_work = {
            "ci-device.sh": "python3 scripts/evidence-manifest.py",
            "ci-overlay.sh": "python3 scripts/overlay-evidence-manifest.py",
        }
        for name, last_work in expected_last_work.items():
            commands = self.top_level_commands(name)
            readiness_command = next(i for i, command in enumerate(commands)
                                     if "python3 scripts/emulator-readiness.py" in command)
            self.assertLess(commands.index("trap collect_diagnostics EXIT") if name == "ci-device.sh"
                            else commands.index("trap collect EXIT"), readiness_command)
            self.assertLess(commands.index(last_work), commands.index("trap - EXIT"))
            self.assertEqual("trap - EXIT", commands[-1])

    def test_workflows_build_exact_apk_before_pinned_port_runner(self):
        for name in ("android.yml", "android-supplemental.yml"):
            text = (ROOT / ".github/workflows" / name).read_text()
            self.assertLess(text.index(":app:assembleDebug"), text.index("android-emulator-runner@"))
            self.assertIn("emulator-port: 5554", text)
        self.assertIn("app/build/outputs/apk/debug/app-debug.apk",
                      (ROOT / "scripts/emulator-readiness.py").read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
