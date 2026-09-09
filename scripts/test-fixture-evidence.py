"""Host-only regression checks for fixture evidence and cleanup source, never Android.

Synthetic files live in a temporary directory inside the repo and are deleted.
The Python readiness helper uses mocked subprocesses and a virtual clock.
The embedded Python validator is executed; ci-fixture.sh is never run.
"""
import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib


REPO = Path(__file__).resolve().parents[1]
SCRIPT = (REPO / "scripts/ci-fixture.sh").read_text()
PROGRAM = SCRIPT.split("  python3 -c '\n", 1)[1].split("\n' ||", 1)[0]
SPEC = importlib.util.spec_from_file_location("fixture_readiness", REPO / "scripts/fixture-readiness.py")
READINESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(READINESS)
PNG_NAMES = """01-feed-overlay 02-dm-escape 03-unknown 04-completed-feed
05-new-session 06-back-unknown 07-other-app 08-disabled-feed 09-disconnected
10-background-return 11-lock-return 12-leave-home 13-no-consent""".split()
TEST_NAMES = {
    "unit": """exactDeadlineAndSameSessionCredit everyNonFeedSurfaceAbortsWithoutCredit
staleCompletionCannotCompleteReplacementAtItsDeadline escapeDoesNotRedrawBeforeNavigationAndDoesNotGrantCredit
cancellationAlsoClearsNavigationSuppression repeatedEventsCannotExtendDeadlineOrShortenNextGate""".split(),
    "device": """feedIsActualOverlayAndMessagesEscapeImmediately completionGrantsOnlyCurrentForegroundFeedSession
unknownAndOtherAppFailOpen disablingActualServiceRemovesWindow backgroundAndLockCancelStaleCompletion
consentRevocationDisablesConnectionAndPreventsReactivation""".split(),
}


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


class FixtureReadinessTest(unittest.TestCase):
    """No subprocess is launched and no wall-clock sleep is performed."""

    def setUp(self):
        self.now = 0.0
        self.sleeps = []
        self.calls = []
        self.effect = self.ready_result
        for target, replacement in [
            ("subprocess.run", self.run_command),
            ("time.monotonic", lambda: self.now),
            ("time.sleep", self.sleep),
        ]:
            # The module is loaded without installing it in sys.modules.
            owner, attribute = target.split(".")
            patcher = patch.object(getattr(READINESS, owner), attribute, replacement)
            patcher.start()
            self.addCleanup(patcher.stop)

    def sleep(self, seconds):
        self.assertGreater(seconds, 0)
        self.assertLessEqual(seconds, READINESS.POLL_SECONDS)
        self.sleeps.append(seconds)
        self.now += seconds

    def ready_result(self, args, timeout):
        values = {"get-state": "device\r\n", "sys.boot_completed": "1\r\n",
                  "ro.build.version.sdk": "35\r\n", "ro.kernel.qemu": "1\r\n",
                  READINESS.STORAGE_PROBE: ""}
        return subprocess.CompletedProcess(args, 0, values[args[-1]], "")

    def run_command(self, args, **kwargs):
        self.assertEqual(["adb", "-s", "emulator-5554"], args[:3])
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(kwargs["text"])
        self.assertFalse(kwargs["check"])
        timeout = kwargs["timeout"]
        self.assertGreater(timeout, 0)
        self.assertLessEqual(timeout, READINESS.COMMAND_TIMEOUT_SECONDS)
        self.assertLessEqual(self.now + timeout, READINESS.TIMEOUT_SECONDS)
        self.calls.append((args, timeout))
        return self.effect(args, timeout)

    def wait_ready(self, serial="emulator-5554"):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = READINESS.wait_ready(serial)
        return code, output.getvalue()

    def test_ready_device_probes_storage_after_identity_without_sleep(self):
        code, output = self.wait_ready()
        self.assertEqual(0, code)
        self.assertEqual([], self.sleeps)
        self.assertEqual(["get-state", "sys.boot_completed", "ro.build.version.sdk",
                          "ro.kernel.qemu", READINESS.STORAGE_PROBE], [args[-1] for args, _ in self.calls])
        self.assertIn("readiness=ready stage=storage status=writable", output)

    def test_transient_offline_boot_properties_and_mount_recover(self):
        failures = iter([("get-state", 1, ""), ("get-state", 0, "offline"),
                         ("sys.boot_completed", 0, "0"), ("ro.build.version.sdk", 0, ""),
                         ("ro.kernel.qemu", 0, ""), (READINESS.STORAGE_PROBE, 1, "")])
        pending = next(failures)

        def transient(args, timeout):
            nonlocal pending
            if pending is not None and args[-1] == pending[0]:
                result = subprocess.CompletedProcess(args, pending[1], pending[2], "PRIVATE SENTINEL")
                pending = next(failures, None)
                return result
            return self.ready_result(args, timeout)

        self.effect = transient
        code, output = self.wait_ready()
        self.assertEqual(0, code)
        self.assertEqual(6, len(self.sleeps))
        self.assertEqual(7, sum(args[-1] == "get-state" for args, _ in self.calls))
        self.assertNotIn("PRIVATE SENTINEL", output)

    def test_invalid_serial_and_wrong_identity_never_probe_storage(self):
        for serial in ["", "physical-device", "emulator-5554\n", "emulator-5554;other"]:
            self.assertEqual(2, self.wait_ready(serial)[0])
        self.assertEqual([], self.calls)
        for prop, wrong in [("ro.build.version.sdk", "34"), ("ro.kernel.qemu", "0")]:
            with self.subTest(prop=prop):
                self.calls.clear()
                self.effect = lambda args, timeout: (
                    subprocess.CompletedProcess(args, 0, wrong, "") if args[-1] == prop
                    else self.ready_result(args, timeout)
                )
                code, output = self.wait_ready()
                self.assertEqual(2, code)
                self.assertIn("readiness=rejected", output)
                self.assertNotIn(READINESS.STORAGE_PROBE, [args[-1] for args, _ in self.calls])

    def test_persistent_failures_at_every_stage_have_one_total_deadline(self):
        for stage, command in [("online", "get-state"), ("boot", "sys.boot_completed"),
                               ("sdk", "ro.build.version.sdk"), ("emulator", "ro.kernel.qemu"),
                               ("storage", READINESS.STORAGE_PROBE)]:
            with self.subTest(stage=stage):
                self.now = 0.0
                self.effect = lambda args, timeout: (
                    subprocess.CompletedProcess(args, 1, "", "offline or unmounted") if args[-1] == command
                    else self.ready_result(args, timeout)
                )
                code, output = self.wait_ready()
                self.assertEqual(124, code)
                self.assertEqual(READINESS.TIMEOUT_SECONDS, self.now)
                self.assertIn(f"readiness=timeout stage={stage} status=adb-exit-1", output)

    def test_hung_commands_are_bounded_by_remaining_deadline(self):
        def hang(args, timeout):
            self.now += timeout
            raise subprocess.TimeoutExpired(args, timeout)

        self.effect = hang
        with patch.object(READINESS, "TIMEOUT_SECONDS", 13):
            code, output = self.wait_ready()
        self.assertEqual(124, code)
        self.assertEqual(13, self.now)
        self.assertEqual([10, 1], [timeout for _, timeout in self.calls])
        self.assertIn("status=adb-timeout", output)

    def test_transient_command_timeout_can_recover(self):
        def once(args, timeout):
            self.effect = self.ready_result
            self.now += timeout
            raise subprocess.TimeoutExpired(args, timeout)

        self.effect = once
        self.assertEqual(0, self.wait_ready()[0])
        self.assertEqual(12, self.now)

    def test_success_at_deadline_is_not_accepted(self):
        def late(args, timeout):
            if args[-1] == READINESS.STORAGE_PROBE:
                self.now += timeout
            return self.ready_result(args, timeout)

        self.effect = late
        with patch.object(READINESS, "TIMEOUT_SECONDS", 5):
            self.assertEqual(124, self.wait_ready()[0])

    def test_missing_adb_fails_without_retry(self):
        def missing(args, timeout):
            raise FileNotFoundError("adb")

        self.effect = missing
        code, output = self.wait_ready()
        self.assertEqual(2, code)
        self.assertIn("status=adb-unavailable", output)
        self.assertEqual([], self.sleeps)

    def test_script_gates_device_mutation_and_collection_after_provenance(self):
        gate = SCRIPT.index('python3 scripts/fixture-readiness.py | tee "$out/diagnostics/readiness.log"')
        for guard in ['${CANDIDATE_SHA:?Exact candidate required}', 'git status --porcelain', 'trap collect EXIT']:
            self.assertLess(SCRIPT.index(guard), gate)
        self.assertLess(SCRIPT.index("device_ready=0"), SCRIPT.index("trap collect EXIT"))
        self.assertEqual(1, SCRIPT.count("device_ready=1"))
        # Standalone commands under set -e must both succeed before collection is enabled.
        self.assertIn('\n'.join([
            'python3 scripts/fixture-readiness.py | tee "$out/diagnostics/readiness.log"',
            'adb shell rm -rf /sdcard/Download/doom-fixture-evidence',
            'adb shell mkdir -p /sdcard/Download/doom-fixture-evidence',
            'device_ready=1',
            'FIXTURE_PHASE=build',
        ]), SCRIPT)
        self.assertIn("set -euo pipefail", SCRIPT)
        self.assertNotIn("adb wait-for-device", SCRIPT)
        collector = SCRIPT.split("collect() {", 1)[1].split("  for module", 1)[0]
        guard, skipped = collector.split("  else\n", 1)
        self.assertIn("if (( device_ready )); then", guard)
        self.assertNotRegex(skipped, r"(?m)^\s*adb ")
        self.assertIn("collection=1", skipped)


class FixtureCleanupSourceTest(unittest.TestCase):
    """Guard the UI-cleanup regression and retain the separate public-UI journey.

    These inspect Kotlin source only; they do not prove Android runtime behavior.
    """

    def setUp(self):
        source = (REPO / "fixturegate/src/androidTest/java/com/chardyb/doom/fixturegate/CrossAppFixtureTest.kt").read_text()
        self.methods = dict(re.findall(
            r"^    (?:@\w+ )?(?:private )?fun (\w+)\([^\n]*\) \{\n(.*?)^    \}",
            source, re.MULTILINE | re.DOTALL,
        ))

    def test_teardown_and_its_helpers_do_not_depend_on_ui_lookup_or_navigation(self):
        pending, visited = ["tearDown"], set()
        while pending:
            name = pending.pop()
            if name in visited:
                continue
            visited.add(name)
            body = self.methods[name]
            self.assertNotRegex(body, r"\b(?:launch|click|visible)\s*\(|\b(?:device|By|Until)\.", name)
            calls = set(re.findall(r"\b(\w+)\s*\(", body))
            pending.extend(calls.intersection(self.methods).difference(visited))

    def test_consent_revocation_still_uses_visible_clear_consent_button(self):
        body = self.methods["consentRevocationDisablesConnectionAndPreventsReactivation"]
        self.assertIn('click("Disable and clear TEST FIXTURE consent")', body)
        self.assertNotIn("clearFixtureConsent(", body)


class FixtureEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".fixture-validator-", dir=REPO)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "screenshots").mkdir()
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(b"\0\0\0\0")) + chunk(b"IEND", b"")
        for name in PNG_NAMES:
            (self.root / "screenshots" / (name + ".png")).write_bytes(png)
        (self.root / "screenshots/timings.txt").write_text(
            "dm_escape_ms=100\ngate_from_launch_ms=5100\ngate_observed_ms=4900\nbackground_wait_ms=5300\nlock_wait_ms=5300\n"
        )
        self.reports = {}
        for label, directory in [("unit", "test-results/testDebugUnitTest"), ("device", "outputs/androidTest-results/connected/emulator")]:
            path = self.root / "reports/fixturegate" / directory / "TEST-fixture.xml"
            path.parent.mkdir(parents=True)
            self.reports[label] = path
            self.write_suite(label)

    def write_suite(self, label, failure=None, names=None):
        names = TEST_NAMES[label] if names is None else names
        suite = ET.Element("testsuite", tests=str(len(names)), failures=str(int(failure == "failure")),
                           errors=str(int(failure == "error")), skipped=str(int(failure == "skipped")))
        for index, name in enumerate(names):
            case = ET.SubElement(suite, "testcase", name=name)
            if index == 0 and failure:
                ET.SubElement(case, failure).text = (
                    "PRIVATE SENTINEL\n::error::must not enter public summary\n"
                    "at com.chardyb.doom.fixturegate.CrossAppFixtureTest.tearDown(CrossAppFixtureTest.kt:65)"
                )
        ET.ElementTree(suite).write(self.reports[label])

    def validate(self, task="0", collection="0", phase="instrumentation"):
        output = io.StringIO()
        environment = dict(FIXTURE_OUT=str(self.root), FIXTURE_SHA="a" * 40,
                           FIXTURE_TASK_EXIT=task, FIXTURE_COLLECTION_EXIT=collection,
                           FIXTURE_PHASE=phase, GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")
        with patch.dict(os.environ, environment), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            with self.assertRaises(SystemExit) as exit_result:
                exec(compile(PROGRAM, "ci-fixture.sh:embedded-validator", "exec"), {})
        return exit_result.exception.code, json.loads((self.root / "manifest.json").read_text()), output.getvalue()

    def test_complete_evidence_publishes_phase_and_independent_results(self):
        code, manifest, output = self.validate()
        self.assertEqual(0, code)
        self.assertEqual([], manifest["validation_errors"])
        self.assertIn("::notice title=Fixture evidence::", output)
        self.assertIn("phase=instrumentation task_exit=0 collection_exit=0 validation_errors=0", output)

    def test_task_and_collection_failures_remain_separate_and_public(self):
        for task, collection in [("17", "0"), ("0", "1"), ("17", "1")]:
            with self.subTest(task=task, collection=collection):
                code, manifest, output = self.validate(task, collection)
                self.assertEqual(0, code)  # Python reports validation only; EXIT preserves the task result.
                self.assertEqual(int(task), manifest["task_exit"])
                self.assertEqual(int(collection), manifest["collection_exit"])
                self.assertIn("::error title=Fixture evidence::", output)
                self.assertIn(f"task_exit={task} collection_exit={collection}", output)

    def test_readiness_timeout_with_no_device_evidence_remains_prepare_failure(self):
        for path in self.root.rglob("*"):
            if path.is_file():
                path.unlink()
        code, manifest, output = self.validate(task="124", collection="1", phase="prepare")
        self.assertNotEqual(0, code)
        self.assertEqual([], manifest["failed_tests"])
        self.assertIn("Missing complete unit JUnit suite", manifest["validation_errors"])
        self.assertIn("Missing complete device JUnit suite", manifest["validation_errors"])
        self.assertIn("phase=prepare task_exit=124 collection_exit=1", output)

    def test_junit_failures_errors_and_skips_publish_identity_without_private_output(self):
        for label in TEST_NAMES:
            for failure in ["failure", "error", "skipped"]:
                with self.subTest(label=label, failure=failure):
                    self.write_suite(label, failure)
                    code, manifest, output = self.validate(task="1")
                    self.assertNotEqual(0, code)
                    self.assertIn(f"{label} failures/errors/skips", manifest["validation_errors"])
                    self.assertIn(f"{label}:{TEST_NAMES[label][0]}:{failure}", output)
                    if label == "device":
                        self.assertIn("stack_frames=tearDown", output)
                    self.assertNotIn("PRIVATE SENTINEL", output)
                    # A failure element must not become a pass if suite counters disagree.
                    report = ET.parse(self.reports[label])
                    report.getroot().set({"failure": "failures", "error": "errors", "skipped": "skipped"}[failure], "0")
                    report.write(self.reports[label])
                    self.assertNotEqual(0, self.validate()[0])
                self.write_suite(label)

    def test_missing_extra_and_corrupt_pngs_fail(self):
        path = self.root / "screenshots" / (PNG_NAMES[0] + ".png")
        original = path.read_bytes()
        path.unlink()
        self.assertNotEqual(0, self.validate()[0])
        path.write_bytes(b"not a PNG")
        self.assertNotEqual(0, self.validate()[0])
        path.write_bytes(original)
        (self.root / "screenshots/unexpected.png").write_bytes(original)
        self.assertNotEqual(0, self.validate()[0])

    def test_wrong_or_missing_test_identities_fail(self):
        for label in TEST_NAMES:
            self.write_suite(label, names=["unexpected"] + TEST_NAMES[label][1:])
            self.assertNotEqual(0, self.validate()[0])
            self.reports[label].unlink()
            self.assertNotEqual(0, self.validate()[0])
            self.write_suite(label)

    def test_missing_timings_fail(self):
        (self.root / "screenshots/timings.txt").unlink()
        self.assertNotEqual(0, self.validate()[0])


if __name__ == "__main__":
    unittest.main()
