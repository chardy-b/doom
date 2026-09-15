#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Overlay Android execution is restricted to GitHub Actions.' >&2; exit 2; }
cd "$(dirname "$0")/.."
. scripts/ci-provenance.sh
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ && "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]]
out=app/build/reports/androidTests/overlay-evidence
apk_copy=app/build/reports/androidTests/supplemental-apk/app-debug.apk
mkdir -p evidence/readiness-diagnostics "$out" "$(dirname "$apk_copy")"
collect() {
  result=$?
  trap - EXIT
  set +e
  if [[ "${EMULATOR_PORT:-}" =~ ^[0-9]+$ ]] && (( EMULATOR_PORT >= 5554 && EMULATOR_PORT <= 5682 && EMULATOR_PORT % 2 == 0 )); then
    serial="emulator-${EMULATOR_PORT}"
    timeout 8s adb -s "$serial" exec-out screencap -p > evidence/readiness-diagnostics/overlay-screen.png 2>/dev/null
    timeout 8s adb -s "$serial" logcat -d -t 200 '*:W' > evidence/readiness-diagnostics/overlay-logcat.txt 2>/dev/null
    timeout 8s adb -s "$serial" shell dumpsys window > evidence/readiness-diagnostics/overlay-window.txt 2>/dev/null
    timeout 8s adb -s "$serial" pull /sdcard/Download/doom-overlay-ui-evidence/. "$out/" >/dev/null 2>&1
  fi
  exit "$result"
}
trap collect EXIT
if ! python3 scripts/emulator-readiness.py app/build/outputs/apk/debug/app-debug.apk > evidence/overlay-readiness.log 2>&1; then
  printf '%s\n' 'Emulator readiness failed; see evidence/overlay-readiness.log.' >&2
  exit 2
fi
serial="emulator-${EMULATOR_PORT}"
rm -rf "$out"/*
cp app/build/outputs/apk/debug/app-debug.apk "$apk_copy"
before=$(sha256sum "$apk_copy" | cut -d' ' -f1)
timeout 10s adb -s "$serial" shell rm -rf /sdcard/Download/doom-overlay-ui-evidence >/dev/null 2>&1
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.chardy.doom.SupplementalEvidence \
  | tee app/build/reports/androidTests/supplemental-instrumentation.log
python3 scripts/validate-android-junit.py supplemental app/build/outputs/androidTest-results/connected
timeout 15s adb -s "$serial" pull /sdcard/Download/doom-overlay-ui-evidence/. "$out/" >/dev/null 2>&1
after=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)
[[ "$before" == "$after" ]] || { echo 'Tested APK changed during instrumentation.' >&2; exit 1; }
python3 scripts/overlay-evidence-manifest.py
trap - EXIT
