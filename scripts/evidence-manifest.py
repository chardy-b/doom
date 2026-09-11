"""Bind real CI artifacts to the tested source; never fabricate evidence."""
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Evidence collection requires GitHub Actions.")
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if sha != os.environ["CANDIDATE_SHA"]:
        raise SystemExit("Candidate identity mismatch")
    root = Path("evidence")
    apk = root / "doom-diagnostic.apk"
    screenshots = sorted((root / "screenshots").glob("*.png"))
    expected = {"01-doom-dashboard-demo.png", "02-doom-breathing-demo.png", "03-doom-messages-demo.png", "04-doom-completed-demo.png"}
    if not apk.is_file() or apk.stat().st_size == 0 or {path.name for path in screenshots} != expected:
        raise SystemExit("Missing tested APK or exact four genuine diagnostic screenshots")
    for image in screenshots:
        if not image.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise SystemExit(f"Not a PNG screenshot: {image.name}")
    manifest = {
        "tested_sha": sha,
        "candidate_sha": os.environ["CANDIDATE_SHA"],
        "event_sha": os.environ["GITHUB_SHA"],
        "event_name": os.environ["GITHUB_EVENT_NAME"],
        "run_id": os.environ["GITHUB_RUN_ID"],
        "evidence_kind": "doom-demo-only-not-instagram",
        "actual_instagram_verified": False,
        "api_level": 35,
        "files": [
            {
                "path": str(path.relative_to(root)),
                "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
            for path in [apk, *screenshots]
        ],
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Bound {len(screenshots)} screenshots and one tested APK to {sha}.")


if __name__ == "__main__":
    main()
