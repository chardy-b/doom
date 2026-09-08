#!/usr/bin/env bash
set -euo pipefail
# Only execute in GitHub-hosted Android CI, never on the agent host.
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  printf '%s\n' 'Android device execution is restricted to GitHub Actions.' >&2
  exit 2
fi
mkdir -p evidence/screenshots
collect_diagnostics() {
  result=$?
  if ! adb pull /sdcard/Android/data/com.chardyb.doom/files/evidence/. evidence/screenshots/; then
    printf '%s\n' 'No app screenshots were available for diagnostics.' >&2
  fi
  exit "$result"
}
trap collect_diagnostics EXIT
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest | tee evidence/instrumentation.log
adb pull /sdcard/Android/data/com.chardyb.doom/files/evidence/. evidence/screenshots/
cp app/build/outputs/apk/debug/app-debug.apk evidence/doom-diagnostic.apk
python3 scripts/evidence-manifest.py
