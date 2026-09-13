#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("validator", ROOT / "scripts/validate-android-junit.py")
MODULE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class ValidatorTest(unittest.TestCase):
    def write(self, cases, attrs='failures="0" errors="0" skipped="0"', relative='connected/debug',
              case_child=None, report_root='testsuite'):
        root = Path(self.temp.name)
        directory = root / relative
        directory.mkdir(parents=True, exist_ok=True)
        body = "".join(
            f'<testcase classname="{c}" name="{n}">' +
            (f'<{case_child}/>' if case_child and index == 0 else '') +
            '</testcase>'
            for index, (c, n) in enumerate(cases)
        )
        suite = f'<testsuite tests="{len(cases)}" {attrs}>{body}</testsuite>'
        document = suite if report_root == 'testsuite' else f'<testsuites>{suite}</testsuites>'
        (directory / "TEST-suite.xml").write_text(
            document, encoding='utf-8'
        )
        return root

    def setUp(self): self.temp = tempfile.TemporaryDirectory()
    def tearDown(self): self.temp.cleanup()

    def test_exact_supplemental_set(self):
        cases = [identity.rsplit("#", 1) for identity in MODULE.SUPPLEMENTAL]
        self.assertEqual(MODULE.SUPPLEMENTAL, MODULE.validate("supplemental", self.write(cases)))

    def canonical_cases(self):
        return [
            identity.rsplit("#", 1)
            for identity in sorted(MODULE.source_test_inventory() - MODULE.SUPPLEMENTAL)
        ]

    def test_canonical_requires_complete_source_inventory(self):
        cases = self.canonical_cases()
        self.assertEqual(
            set(MODULE.source_test_inventory()) - MODULE.SUPPLEMENTAL,
            MODULE.validate("canonical", self.write(cases)),
        )
        with self.assertRaises(ValueError):
            MODULE.validate("canonical", self.write(cases[:-1]))

    def test_supplemental_missing_and_extra_tests_are_rejected(self):
        cases = [identity.rsplit("#", 1) for identity in sorted(MODULE.SUPPLEMENTAL)]
        with self.assertRaises(ValueError):
            MODULE.validate("supplemental", self.write(cases[:-1]))
        with self.assertRaises(ValueError):
            MODULE.validate("supplemental", self.write(cases + [self.canonical_cases()[0]]))

    def test_supported_annotation_ordering_is_enumerated(self):
        source_root = Path(self.temp.name) / "source"
        source_root.mkdir()
        (source_root / "Ordered.kt").write_text(
            "package synthetic\nclass Ordered {\n"
            "    @SupplementalEvidence\n    @Test fun reversed() {}\n"
            "    @Test\n    @Other fun ordinary() {}\n}\n",
            encoding='utf-8',
        )
        self.assertEqual(
            {"synthetic.Ordered#reversed", "synthetic.Ordered#ordinary"},
            MODULE.source_test_inventory(source_root),
        )

    def test_skips_errors_and_failures_are_rejected(self):
        cases = self.canonical_cases()
        for status in ('failures="1"', 'errors="1"', 'skipped="1"'):
            with self.assertRaises(ValueError): MODULE.validate("canonical", self.write(cases, status))
        for child in ('failure', 'error', 'skipped'):
            with self.assertRaises(ValueError):
                MODULE.validate("canonical", self.write(cases, case_child=child))

    def test_nested_testsuites_report_root_is_accepted(self):
        cases = [identity.rsplit("#", 1) for identity in sorted(MODULE.SUPPLEMENTAL)]
        self.assertEqual(
            MODULE.SUPPLEMENTAL,
            MODULE.validate("supplemental", self.write(cases, report_root='testsuites')),
        )

    def test_duplicate_identities_are_rejected_across_nested_reports(self):
        cases = self.canonical_cases()
        root = self.write(cases)
        duplicate = root / "connected" / "debug" / "nested"
        duplicate.mkdir()
        classname, name = cases[0]
        (duplicate / "TEST-duplicate.xml").write_text(
            f'<testsuite tests="1"><testcase classname="{classname}" name="{name}"/></testsuite>',
            encoding='utf-8',
        )
        with self.assertRaises(ValueError): MODULE.validate("canonical", root)

    def test_missing_reports_rejected(self):
        with self.assertRaises(ValueError): MODULE.validate("canonical", Path(self.temp.name))

if __name__ == "__main__": unittest.main(verbosity=2)
