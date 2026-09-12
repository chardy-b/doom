#!/usr/bin/env python3
"""Bind synthetic Doom overlay screenshots to the exact tested CI APK."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess

EXPECTED_SCREENSHOTS = (
    "01-overlay-unavailable.png", "02-overlay-captured-status.png",
    "03-overlay-reduced-motion.png", "04-overlay-large-font.png",
    "05-overlay-landscape.png", "06-doom-build-footer.png",
)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def build_manifest(root: Path, apk: Path, candidate_sha: str, run_id: str, run_attempt: str) -> dict:
    if len(candidate_sha) != 40 or any(c not in "0123456789abcdef" for c in candidate_sha):
        raise ValueError("candidate SHA must be lowercase full SHA")
    if not run_id or not run_attempt:
        raise ValueError("run identity is required")
    present = {path.name for path in root.iterdir()} if root.is_dir() else set()
    screenshots = sorted(path.name for path in root.glob("*.png"))
    if present != set(EXPECTED_SCREENSHOTS):
        raise ValueError("supplementary evidence directory contains an unexpected file")
    if tuple(screenshots) != EXPECTED_SCREENSHOTS:
        raise ValueError("supplementary evidence requires the exact six screenshots")
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("tested APK is missing or empty")
    files = []
    for name in EXPECTED_SCREENSHOTS:
        path = root / name
        if not path.read_bytes().startswith(PNG_SIGNATURE) or path.stat().st_size == 0:
            raise ValueError(f"not a nonempty PNG: {name}")
        files.append({"path": name, "size": path.stat().st_size,
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return {
        "evidence_kind": "doom-overlay-ui-synthetic-only",
        "tested_sha": candidate_sha, "candidate_sha": candidate_sha,
        "run_id": run_id, "run_attempt": run_attempt,
        "actual_instagram_verified": False,
        "apk": {"path": str(apk), "size": apk.stat().st_size,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()},
        "files": files,
    }


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Overlay evidence requires GitHub Actions.")
    candidate = os.environ["CANDIDATE_SHA"]
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if actual != candidate:
        raise SystemExit("Candidate identity mismatch")
    root = Path("app/build/reports/androidTests/overlay-evidence")
    apk = Path("app/build/outputs/apk/debug/app-debug.apk")
    manifest = build_manifest(root, apk, candidate, os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"])
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Bound {len(EXPECTED_SCREENSHOTS)} supplementary screenshots to {candidate}.")


if __name__ == "__main__":
    main()
