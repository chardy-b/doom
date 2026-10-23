#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { printf '%s\n' 'API 37 execution requires GitHub Actions.' >&2; exit 2; }
cd "$(dirname "$0")/.."
. scripts/ci-provenance.sh
report=app/build/reports/api37-compatibility
mkdir -p "$report"
export EMULATOR_PORT=5554 ANDROID_SERIAL=emulator-5554
# Keep Android's user directory (and debug keystore) identical to the compile step.
export ANDROID_AVD_HOME="${RUNNER_TEMP:?}/doom-api37-avd"
mkdir -p "$ANDROID_AVD_HOME"
image='system-images;android-37.0;google_apis;x86_64'
timeout 300s sdkmanager emulator "$image" > "$report/sdk-setup.log" 2>&1
printf 'no\n' | avdmanager create avd --force --name doom-api37 --path "$ANDROID_AVD_HOME/doom-api37.avd" --package "$image" --device pixel_2 > "$report/avd-setup.log" 2>&1
test -s "$ANDROID_AVD_HOME/doom-api37.ini"
cp "$ANDROID_AVD_HOME/doom-api37.ini" "$report/avd.ini"
cp "$ANDROID_AVD_HOME/doom-api37.avd/config.ini" "$report/avd-config.ini"
grep -Fxq 'target=android-37.0' "$report/avd.ini"
timeout 10s adb start-server
"$ANDROID_HOME/emulator/emulator" -avd doom-api37 -port "$EMULATOR_PORT" -memory 4096 \
  -no-window -gpu software -noaudio -no-boot-anim -camera-back none -no-snapshot \
  > "$report/emulator.log" 2>&1 &
emulator_pid=$!
collect() {
  result=$?
  trap - EXIT
  set +e
  timeout 10s adb -s "$ANDROID_SERIAL" logcat -b crash -d -t 200 > "$report/crash.log" 2>&1
  timeout 10s adb -s "$ANDROID_SERIAL" emu kill > "$report/stop.log" 2>&1
  if kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid"; fi
  exit "$result"
}
trap collect EXIT
# Boot-completed alone can precede Android's package/input/settings service readiness.
python3 scripts/emulator-readiness.py app/build/outputs/apk/debug/app-debug.apk 37 > "$report/readiness.log" 2>&1
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb -s "$ANDROID_SERIAL" shell settings put global "$setting" 0
done
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.chardy.doom.SupplementalEvidence \
  2>&1 | tee "$report/instrumentation.log"
python3 scripts/validate-android-junit.py canonical app/build/outputs/androidTest-results/connected
