#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

EXPECTED_SCREENSHOTS = (
    "screenshots/01-doom-dashboard-demo.png",
    "screenshots/02-doom-breathing-demo.png",
    "screenshots/03-doom-messages-demo.png",
    "screenshots/04-doom-completed-demo.png",
)
EXPECTED_EVIDENCE_FILES = ("doom-diagnostic.apk", *EXPECTED_SCREENSHOTS)
SIGNATURE_ENTRY = re.compile(r"^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.IGNORECASE)
MAX_APK_ENTRIES = 100_000
MAX_APK_UNCOMPRESSED_BYTES = 256 * 1024 * 1024


class ValidationError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative_path(raw: object) -> Path:
    if not isinstance(raw, str) or not raw or "\\" in raw or "\x00" in raw:
        raise ValidationError("unsafe evidence path")
    pure = PurePosixPath(raw)
    if pure.is_absolute() or any(part in ("", ".", "..") for part in pure.parts):
        raise ValidationError("unsafe evidence path")
    return Path(*pure.parts)


def load_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValidationError(f"invalid JSON: {path.name}") from exc
    if not isinstance(value, dict):
        raise ValidationError(f"expected JSON object: {path.name}")
    return value


def validate_source_run(source_run_file: Path, candidate_sha: str, source_run_id: str) -> dict:
    if re.fullmatch(r"[0-9a-f]{40}", candidate_sha) is None:
        raise ValidationError("candidate SHA must be 40 lowercase hexadecimal characters")
    if re.fullmatch(r"[1-9][0-9]*", source_run_id) is None:
        raise ValidationError("source run ID must be a positive decimal integer")
    run = load_object(source_run_file)
    expected = {
        "repository": "chardy-b/doom",
        "workflow_path": ".github/workflows/android.yml",
        "run_id": source_run_id,
        "head_sha": candidate_sha,
        "status": "completed",
        "conclusion": "success",
    }
    for key, value in expected.items():
        if str(run.get(key)) != value:
            raise ValidationError(f"source run {key} mismatch")
    if not isinstance(run.get("run_attempt"), int) or run["run_attempt"] < 1:
        raise ValidationError("invalid source run attempt")
    if run.get("event") not in {"pull_request", "push", "workflow_dispatch"}:
        raise ValidationError("unapproved source run event")
    return run


def validate_evidence(evidence_dir: Path, source_run_file: Path, candidate_sha: str, source_run_id: str) -> Path:
    validate_source_run(source_run_file, candidate_sha, source_run_id)
    manifest_path = evidence_dir / "manifest.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise ValidationError("missing regular evidence manifest")
    manifest = load_object(manifest_path)
    expected_fields = {
        "tested_sha": candidate_sha,
        "candidate_sha": candidate_sha,
        "run_id": source_run_id,
        "evidence_kind": "doom-demo-only-not-instagram",
        "actual_instagram_verified": False,
        "api_level": 35,
    }
    for key, value in expected_fields.items():
        actual = manifest.get(key)
        if key == "run_id":
            actual = str(actual)
        if actual != value:
            raise ValidationError(f"evidence manifest {key} mismatch")

    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != len(EXPECTED_EVIDENCE_FILES):
        raise ValidationError("evidence manifest must contain exactly five files")
    seen: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"path", "size", "sha256"}:
            raise ValidationError("invalid evidence manifest file entry")
        relative = safe_relative_path(entry["path"])
        normalized = relative.as_posix()
        if normalized in seen:
            raise ValidationError("duplicate evidence manifest path")
        seen.add(normalized)
        path = evidence_dir / relative
        if not path.is_file() or path.is_symlink():
            raise ValidationError(f"missing regular evidence file: {normalized}")
        size = entry["size"]
        checksum = entry["sha256"]
        if not isinstance(size, int) or size <= 0 or path.stat().st_size != size:
            raise ValidationError(f"evidence size mismatch: {normalized}")
        if not isinstance(checksum, str) or re.fullmatch(r"[0-9a-f]{64}", checksum) is None:
            raise ValidationError(f"invalid evidence checksum: {normalized}")
        if sha256(path) != checksum:
            raise ValidationError(f"evidence checksum mismatch: {normalized}")
    if seen != set(EXPECTED_EVIDENCE_FILES):
        raise ValidationError("evidence manifest paths differ from the exact contract")

    actual_files: set[str] = set()
    for path in evidence_dir.rglob("*"):
        if path.is_symlink():
            raise ValidationError("downloaded evidence contains a symbolic link")
        if path.is_file() and path != manifest_path:
            actual_files.add(path.relative_to(evidence_dir).as_posix())
    if actual_files != set(EXPECTED_EVIDENCE_FILES):
        raise ValidationError("downloaded evidence contains missing or unaccounted files")
    for name in EXPECTED_SCREENSHOTS:
        if not (evidence_dir / name).read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValidationError(f"invalid screenshot signature: {name}")
    return evidence_dir / "doom-diagnostic.apk"


def apk_payload(path: Path) -> list[tuple[str, int, int, str]]:
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if not infos or len(infos) > MAX_APK_ENTRIES:
                raise ValidationError("APK entry count outside bounds")
            names: set[str] = set()
            total = 0
            payload: list[tuple[str, int, int, str]] = []
            for info in infos:
                name = info.filename
                safe_relative_path(name.rstrip("/"))
                if name in names:
                    raise ValidationError(f"duplicate APK entry: {name}")
                names.add(name)
                if info.flag_bits & 0x1:
                    raise ValidationError(f"encrypted APK entry: {name}")
                total += info.file_size
                if total > MAX_APK_UNCOMPRESSED_BYTES:
                    raise ValidationError("APK uncompressed size exceeds bound")
                if info.is_dir() or SIGNATURE_ENTRY.fullmatch(name):
                    continue
                with archive.open(info) as handle:
                    digest = hashlib.sha256()
                    read_size = 0
                    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                        read_size += len(chunk)
                        digest.update(chunk)
                    if read_size != info.file_size:
                        raise ValidationError(f"APK entry size mismatch: {name}")
                payload.append((name, info.compress_type, info.file_size, digest.hexdigest()))
            bad = archive.testzip()
            if bad is not None:
                raise ValidationError(f"corrupt APK entry: {bad}")
            return payload
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise ValidationError(f"invalid APK archive: {path.name}") from exc


def compare_payload(source_apk: Path, signed_apk: Path) -> None:
    source = apk_payload(source_apk)
    signed = apk_payload(signed_apk)
    if source != signed:
        raise ValidationError("non-signature APK payload changed during signing")


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate = subparsers.add_parser("validate-evidence")
    validate.add_argument("--evidence-dir", type=Path, required=True)
    validate.add_argument("--source-run-file", type=Path, required=True)
    validate.add_argument("--candidate-sha", required=True)
    validate.add_argument("--source-run-id", required=True)
    compare = subparsers.add_parser("compare-payload")
    compare.add_argument("--source-apk", type=Path, required=True)
    compare.add_argument("--signed-apk", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "validate-evidence":
            validate_evidence(args.evidence_dir, args.source_run_file, args.candidate_sha, args.source_run_id)
        else:
            compare_payload(args.source_apk, args.signed_apk)
    except ValidationError as exc:
        print(f"validation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
