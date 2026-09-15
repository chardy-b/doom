#!/usr/bin/env bash
set -euo pipefail
# Only execute in GitHub-hosted Android CI, never on the agent host.
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  printf '%s\n' 'Android device execution is restricted to GitHub Actions.' >&2
  exit 2
fi
cd "$(dirname "$0")/.."
. scripts/ci-provenance.sh
mkdir -p evidence/readiness-diagnostics evidence/screenshots
collect_diagnostics() {
  result=$?
  trap - EXIT
  set +e
  if [[ "${EMULATOR_PORT:-}" =~ ^[0-9]+$ ]] && (( EMULATOR_PORT >= 5554 && EMULATOR_PORT <= 5682 && EMULATOR_PORT % 2 == 0 )); then
    serial="emulator-${EMULATOR_PORT}"
    timeout 8s adb -s "$serial" exec-out screencap -p > evidence/readiness-diagnostics/screen.png 2>/dev/null
    timeout 8s adb -s "$serial" logcat -d -t 200 '*:W' > evidence/readiness-diagnostics/logcat.txt 2>/dev/null
    timeout 8s adb -s "$serial" shell dumpsys window > evidence/readiness-diagnostics/window.txt 2>/dev/null
    timeout 8s adb -s "$serial" pull /sdcard/Download/doom-ci-evidence/. evidence/screenshots/ >/dev/null 2>&1
  fi
  exit "$result"
}
trap collect_diagnostics EXIT
if ! python3 scripts/emulator-readiness.py app/build/outputs/apk/debug/app-debug.apk > evidence/readiness.log 2>&1; then
  printf '%s\n' 'Emulator readiness failed; see evidence/readiness.log.' >&2
  exit 2
fi
serial="emulator-${EMULATOR_PORT}"
timeout 10s adb -s "$serial" shell rm -rf /sdcard/Download/doom-ci-evidence >/dev/null 2>&1
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.chardy.doom.SupplementalEvidence | tee evidence/instrumentation.log
python3 scripts/validate-android-junit.py canonical app/build/outputs/androidTest-results/connected
timeout 15s adb -s "$serial" pull /sdcard/Download/doom-ci-evidence/. evidence/screenshots/ >/dev/null 2>&1
cp app/build/outputs/apk/debug/app-debug.apk evidence/doom-diagnostic.apk
python3 scripts/evidence-manifest.py
trap - EXIT
