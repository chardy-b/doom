#!/usr/bin/env bash
set -euo pipefail
umask 077

EXPECTED_CERT_SHA256="76ac486496e74c6a598f06745e0c43d25cdb94d18cdaf2eb69272980043f7003"
KEY_ALIAS="doom-internal"

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${CANDIDATE_SHA:?CANDIDATE_SHA is required}"
: "${SOURCE_RUN_ID:?SOURCE_RUN_ID is required}"
: "${SOURCE_RUN_FILE:?SOURCE_RUN_FILE is required}"
: "${DOOM_DEVICE_EVIDENCE_DIR:?DOOM_DEVICE_EVIDENCE_DIR is required}"
: "${DOOM_INTERNAL_OUTPUT_DIR:?DOOM_INTERNAL_OUTPUT_DIR is required}"
: "${DOOM_INTERNAL_SIGNING_KEYSTORE_B64:?missing internal keystore secret}"
: "${DOOM_INTERNAL_SIGNING_PASSWORD:?missing internal signing password}"

if [[ ! "$CANDIDATE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  printf '%s\n' 'Invalid candidate SHA.' >&2
  exit 1
fi
if [[ ! "$SOURCE_RUN_ID" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'Invalid source run ID.' >&2
  exit 1
fi

build_tools="$ANDROID_HOME/build-tools/35.0.0"
aapt="$build_tools/aapt"
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"
for tool in "$aapt" "$apksigner" "$zipalign"; do
  [[ -x "$tool" ]] || { printf 'Missing Android build tool: %s\n' "$tool" >&2; exit 1; }
done
for tool in python3 base64 zip; do
  command -v "$tool" >/dev/null || { printf 'Missing required tool: %s\n' "$tool" >&2; exit 1; }
done

python3 scripts/validate-internal-signing.py validate-evidence \
  --evidence-dir "$DOOM_DEVICE_EVIDENCE_DIR" \
  --source-run-file "$SOURCE_RUN_FILE" \
  --candidate-sha "$CANDIDATE_SHA" \
  --source-run-id "$SOURCE_RUN_ID"

input_apk="$DOOM_DEVICE_EVIDENCE_DIR/doom-diagnostic.apk"
mkdir -p "$DOOM_INTERNAL_OUTPUT_DIR"
work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT INT TERM
keystore="$work/doom-internal.p12"
prepared="$work/prepared.apk"
aligned="$work/aligned.apk"
output_apk="$DOOM_INTERNAL_OUTPUT_DIR/doom-internal-${CANDIDATE_SHA}.apk"

printf %s "$DOOM_INTERNAL_SIGNING_KEYSTORE_B64" | base64 --decode > "$keystore"
unset DOOM_INTERNAL_SIGNING_KEYSTORE_B64
[[ -s "$keystore" ]] || { printf '%s\n' 'Decoded keystore is empty.' >&2; exit 1; }
cp "$input_apk" "$prepared"
zip -q -d "$prepared" 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' >/dev/null 2>&1 || status=$?
if [[ ${status:-0} -ne 0 && ${status:-0} -ne 12 ]]; then
  printf '%s\n' 'Could not remove the ephemeral signature entries.' >&2
  exit 1
fi
"$zipalign" -p -f 4 "$prepared" "$aligned"
"$apksigner" sign \
  --ks "$keystore" \
  --ks-type PKCS12 \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:DOOM_INTERNAL_SIGNING_PASSWORD \
  --key-pass env:DOOM_INTERNAL_SIGNING_PASSWORD \
  --min-sdk-version 26 \
  --out "$output_apk" \
  "$aligned"

signer_report="$DOOM_INTERNAL_OUTPUT_DIR/signer-report.txt"
"$apksigner" verify --verbose --print-certs "$output_apk" | tee "$signer_report"
mapfile -t signer_digests < <(sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' "$signer_report" | tr '[:upper:]' '[:lower:]')
if [[ ${#signer_digests[@]} -ne 1 || "${signer_digests[0]}" != "$EXPECTED_CERT_SHA256" ]]; then
  printf '%s\n' 'Stable internal signer fingerprint mismatch.' >&2
  exit 1
fi
grep -Fqx 'Verified using v2 scheme (APK Signature Scheme v2): true' "$signer_report"

python3 scripts/validate-internal-signing.py compare-payload \
  --source-apk "$input_apk" \
  --signed-apk "$output_apk"

input_badging=$("$aapt" dump badging "$input_apk" | sed -n '1p')
output_badging=$("$aapt" dump badging "$output_apk" | sed -n '1p')
input_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$input_badging")
output_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$output_badging")
version_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$output_badging")
version_name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$output_badging")
if [[ "$input_package" != "com.chardyb.doom" || "$output_package" != "$input_package" ]]; then
  printf '%s\n' 'APK package identity mismatch.' >&2
  exit 1
fi
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ || -z "$version_name" ]]; then
  printf '%s\n' 'APK version metadata is invalid.' >&2
  exit 1
fi

export INPUT_APK="$input_apk" OUTPUT_APK="$output_apk" SIGNER_REPORT="$signer_report"
export EXPECTED_CERT_SHA256 SOURCE_RUN_FILE version_code version_name
python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path

def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

source_run = json.loads(Path(os.environ["SOURCE_RUN_FILE"]).read_text(encoding="utf-8"))
input_apk = Path(os.environ["INPUT_APK"])
output_apk = Path(os.environ["OUTPUT_APK"])
manifest = {
    "schema": 1,
    "candidate_sha": os.environ["CANDIDATE_SHA"],
    "source_run_id": os.environ["SOURCE_RUN_ID"],
    "source_run_attempt": source_run["run_attempt"],
    "source_workflow": source_run["workflow_path"],
    "source_apk": {
        "size": input_apk.stat().st_size,
        "sha256": digest(input_apk),
    },
    "signed_apk": {
        "path": output_apk.name,
        "size": output_apk.stat().st_size,
        "sha256": digest(output_apk),
        "package": "com.chardyb.doom",
        "version_code": int(os.environ["version_code"]),
        "version_name": os.environ["version_name"],
        "certificate_sha256": os.environ["EXPECTED_CERT_SHA256"],
    },
    "payload_identical_except_signatures": True,
    "actual_instagram_verified": False,
}
(output_apk.parent / "signing-evidence.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
chmod 0644 "$output_apk" "$signer_report" "$DOOM_INTERNAL_OUTPUT_DIR/signing-evidence.json"
unset DOOM_INTERNAL_SIGNING_PASSWORD
printf 'Signed stable internal APK for %s from run %s.\n' "$CANDIDATE_SHA" "$SOURCE_RUN_ID"
