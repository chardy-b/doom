"""Host-only regression checks for fixture evidence and cleanup source, never Android.

Synthetic files live in a temporary directory inside the repo and are deleted.
Only the embedded Python validator is executed; ci-fixture.sh is never run.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import re
import struct
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib


REPO = Path(__file__).resolve().parents[1]
PROGRAM = (REPO / "scripts/ci-fixture.sh").read_text().split("  python3 -c '\n", 1)[1].split("\n' ||", 1)[0]
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

    def validate(self, task="0", collection="0"):
        output = io.StringIO()
        environment = dict(FIXTURE_OUT=str(self.root), FIXTURE_SHA="a" * 40,
                           FIXTURE_TASK_EXIT=task, FIXTURE_COLLECTION_EXIT=collection,
                           FIXTURE_PHASE="instrumentation", GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")
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
