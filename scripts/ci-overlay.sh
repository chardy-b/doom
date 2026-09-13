#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Overlay Android execution is restricted to GitHub Actions.' >&2; exit 2; }
cd "$(dirname "$0")/.."
sha=$(git rev-parse HEAD)
[[ "$sha" =~ ^[0-9a-f]{40}$ && "$sha" == "${CANDIDATE_SHA:?Exact candidate required}" ]]
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ && "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]]
[[ -z "$(git status --porcelain)" ]] || { echo 'Exact-SHA overlay evidence requires a clean checkout.' >&2; exit 2; }
out=app/build/reports/androidTests/overlay-evidence
apk_copy=app/build/reports/androidTests/supplemental-apk/app-debug.apk
mkdir -p "$out" "$(dirname "$apk_copy")"
rm -rf "$out"/*
cp app/build/outputs/apk/debug/app-debug.apk "$apk_copy"
before=$(sha256sum "$apk_copy" | cut -d' ' -f1)
collect() {
  result=$?
  trap - EXIT
  set +e
  adb pull /sdcard/Download/doom-overlay-ui-evidence/. "$out/" >/dev/null 2>&1
  exit "$result"
}
trap collect EXIT
adb shell rm -rf /sdcard/Download/doom-overlay-ui-evidence
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.chardy.doom.SupplementalEvidence \
  | tee app/build/reports/androidTests/supplemental-instrumentation.log
python3 scripts/validate-android-junit.py supplemental app/build/outputs/androidTest-results/connected
adb pull /sdcard/Download/doom-overlay-ui-evidence/. "$out/"
after=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)
[[ "$before" == "$after" ]] || { echo 'Tested APK changed during instrumentation.' >&2; exit 1; }
python3 scripts/overlay-evidence-manifest.py
trap - EXIT
