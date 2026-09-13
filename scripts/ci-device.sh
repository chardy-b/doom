#!/usr/bin/env bash
set -euo pipefail
# Only execute in GitHub-hosted Android CI, never on the agent host.
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  printf '%s\n' 'Android device execution is restricted to GitHub Actions.' >&2
  exit 2
fi
cd "$(dirname "$0")/.."
[[ "$(git rev-parse HEAD)" == "${CANDIDATE_SHA:?Exact candidate required}" ]]
[[ -z "$(git status --porcelain)" ]]
mkdir -p evidence/screenshots
collect_diagnostics() {
  result=$?
  if ! adb pull /sdcard/Download/doom-ci-evidence/. evidence/screenshots/; then
    printf '%s\n' 'No app screenshots were available for diagnostics.' >&2
  fi
  exit "$result"
}
trap collect_diagnostics EXIT
adb shell rm -rf /sdcard/Download/doom-ci-evidence
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.chardy.doom.SupplementalEvidence | tee evidence/instrumentation.log
python3 scripts/validate-android-junit.py canonical app/build/outputs/androidTest-results/connected
adb pull /sdcard/Download/doom-ci-evidence/. evidence/screenshots/
cp app/build/outputs/apk/debug/app-debug.apk evidence/doom-diagnostic.apk
python3 scripts/evidence-manifest.py
