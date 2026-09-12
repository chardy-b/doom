#!/usr/bin/env python3
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "scripts/validate-internal-signing.py"
SPEC = importlib.util.spec_from_file_location("internal_signing_validator", VALIDATOR_PATH)
assert SPEC is not None and SPEC.loader is not None
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)

CANDIDATE = "a" * 40
RUN_ID = "12345"
CERT_SHA256 = "76ac486496e74c6a598f06745e0c43d25cdb94d18cdaf2eb69272980043f7003"


def file_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class InternalSigningTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.evidence = self.root / "evidence"
        (self.evidence / "screenshots").mkdir(parents=True)
        with zipfile.ZipFile(self.evidence / "doom-diagnostic.apk", "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"code")
        for name in VALIDATOR.EXPECTED_SCREENSHOTS:
            (self.evidence / name).write_bytes(b"\x89PNG\r\n\x1a\nfixture")
        files = []
        for name in VALIDATOR.EXPECTED_EVIDENCE_FILES:
            path = self.evidence / name
            files.append({"path": name, "size": path.stat().st_size, "sha256": file_sha(path)})
        self.manifest = {
            "tested_sha": CANDIDATE,
            "candidate_sha": CANDIDATE,
            "event_sha": "b" * 40,
            "event_name": "pull_request",
            "run_id": RUN_ID,
            "evidence_kind": "doom-demo-only-not-instagram",
            "actual_instagram_verified": False,
            "api_level": 35,
            "files": files,
        }
        self.source_run = {
            "repository": "chardy-b/doom",
            "workflow_path": ".github/workflows/android.yml",
            "run_id": RUN_ID,
            "head_sha": CANDIDATE,
            "status": "completed",
            "conclusion": "success",
            "event": "pull_request",
            "run_attempt": 1,
            "html_url": "https://github.com/chardy-b/doom/actions/runs/12345",
        }
        self._write_contracts()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_contracts(self) -> None:
        (self.evidence / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.root / "source-run.json").write_text(json.dumps(self.source_run), encoding="utf-8")

    def validate(self) -> Path:
        return VALIDATOR.validate_evidence(self.evidence, self.root / "source-run.json", CANDIDATE, RUN_ID)

    def test_real_evidence_manifest_schema_is_accepted(self) -> None:
        self.assertEqual(self.validate(), self.evidence / "doom-diagnostic.apk")

    def test_manifest_requires_size_not_an_invented_bytes_field(self) -> None:
        self.manifest["files"][0]["bytes"] = self.manifest["files"][0].pop("size")
        self._write_contracts()
        with self.assertRaises(VALIDATOR.ValidationError):
            self.validate()

    def test_manifest_rejects_duplicate_or_unaccounted_files(self) -> None:
        self.manifest["files"][1] = copy.deepcopy(self.manifest["files"][0])
        self._write_contracts()
        with self.assertRaises(VALIDATOR.ValidationError):
            self.validate()
        self.setUp_contracts_again()
        (self.evidence / "unexpected.txt").write_text("unexpected", encoding="utf-8")
        with self.assertRaises(VALIDATOR.ValidationError):
            self.validate()

    def setUp_contracts_again(self) -> None:
        files = []
        for name in VALIDATOR.EXPECTED_EVIDENCE_FILES:
            path = self.evidence / name
            files.append({"path": name, "size": path.stat().st_size, "sha256": file_sha(path)})
        self.manifest["files"] = files
        self._write_contracts()

    def test_manifest_rejects_checksum_or_source_run_mismatch(self) -> None:
        self.manifest["files"][0]["sha256"] = "0" * 64
        self._write_contracts()
        with self.assertRaises(VALIDATOR.ValidationError):
            self.validate()
        self.setUp_contracts_again()
        self.source_run["conclusion"] = "failure"
        self._write_contracts()
        with self.assertRaises(VALIDATOR.ValidationError):
            self.validate()

    def test_payload_comparison_allows_only_signature_replacement(self) -> None:
        source = self.root / "source.apk"
        signed = self.root / "signed.apk"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"same")
            archive.writestr("META-INF/CERT.RSA", b"old")
        with zipfile.ZipFile(signed, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"same")
            archive.writestr("META-INF/DOOM-INTERNAL.RSA", b"new")
        VALIDATOR.compare_payload(source, signed)

    def test_payload_comparison_rejects_content_or_order_change(self) -> None:
        source = self.root / "source.apk"
        changed = self.root / "changed.apk"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"same")
        with zipfile.ZipFile(changed, "w") as archive:
            archive.writestr("classes.dex", b"changed")
            archive.writestr("AndroidManifest.xml", b"manifest")
        with self.assertRaises(VALIDATOR.ValidationError):
            VALIDATOR.compare_payload(source, changed)

    def test_apk_parser_rejects_duplicate_names(self) -> None:
        duplicate = self.root / "duplicate.apk"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(duplicate, "w") as archive:
                archive.writestr("classes.dex", b"one")
                archive.writestr("classes.dex", b"two")
        with self.assertRaises(VALIDATOR.ValidationError):
            VALIDATOR.apk_payload(duplicate)

    def test_version_code_uses_ci_run_number(self) -> None:
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('environmentVariable("GITHUB_RUN_NUMBER")', gradle)
        self.assertIn("versionCode = ciRunNumber ?: 1", gradle)
        self.assertIn('"-ci.$it"', gradle)

    def test_manual_workflow_is_main_only_and_environment_protected(self) -> None:
        workflow = (ROOT / ".github/workflows/sign-internal-apk.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("pull_request_target:", workflow)
        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn("environment: internal-signing", workflow)
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertIn("run-id: ${{ inputs.source_run_id }}", workflow)
        self.assertIn("conclusion\": \"success", workflow)
        self.assertIn(".github/workflows/android.yml", workflow)

    def test_candidate_ci_never_references_signing_secrets(self) -> None:
        candidate_workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        self.assertNotIn("DOOM_INTERNAL_SIGNING_", candidate_workflow)
        signer_workflow = (ROOT / ".github/workflows/sign-internal-apk.yml").read_text(encoding="utf-8")
        self.assertEqual(signer_workflow.count("${{ secrets.DOOM_INTERNAL_SIGNING_KEYSTORE_B64 }}"), 1)
        self.assertEqual(signer_workflow.count("${{ secrets.DOOM_INTERNAL_SIGNING_PASSWORD }}"), 1)

    def test_signer_uses_explicit_validation_and_pinned_certificate(self) -> None:
        signer = (ROOT / "scripts/sign-internal-apk.sh").read_text(encoding="utf-8")
        self.assertIn(CERT_SHA256, signer)
        self.assertIn("validate-evidence", signer)
        self.assertIn("compare-payload", signer)
        self.assertIn("--print-certs", signer)
        self.assertNotIn("set -x", signer)
        subprocess.run(["bash", "-n", str(ROOT / "scripts/sign-internal-apk.sh")], check=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
