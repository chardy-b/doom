#!/usr/bin/env bash
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  printf '%s\n' 'Fixture Android execution is restricted to GitHub Actions.' >&2
  exit 2
fi
cd "$(dirname "$0")/.."
sha=$(git rev-parse HEAD)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$sha" == "${CANDIDATE_SHA:?Exact candidate required}" ]]
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]
[[ "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]]
if [[ -n "$(git status --porcelain)" ]]; then
  printf '%s\n' 'Exact-SHA fixture evidence requires a clean checkout.' >&2
  exit 2
fi
# Validate the disposable target before enabling any failure-time diagnostic collection.
adb wait-for-device
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "35" ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]]
out="fixture-evidence/${sha}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
# A fresh artifact directory and device directory prevent stale evidence reuse.
[[ ! -e "$out" ]]
mkdir -p "$out/screenshots" "$out/apks" "$out/reports" "$out/diagnostics"
export FIXTURE_SHA="$sha" FIXTURE_OUT="$out" FIXTURE_PHASE=prepare
collect() {
  result=$?
  trap - EXIT
  set +e
  collection=0
  adb pull /sdcard/Download/doom-fixture-evidence/. "$out/screenshots/" > "$out/diagnostics/pull.log" 2>&1 || collection=1
  adb shell dumpsys accessibility > "$out/diagnostics/accessibility.txt" 2>&1 || collection=1
  adb shell dumpsys window windows > "$out/diagnostics/windows.txt" 2>&1 || collection=1
  adb shell dumpsys activity activities > "$out/diagnostics/activities.txt" 2>&1 || collection=1
  adb logcat -d -s AndroidRuntime:E > "$out/diagnostics/crashes.txt" 2>&1 || collection=1
  for module in fixtureapp fixturegate; do
    apk="$module/build/outputs/apk/debug/$module-debug.apk"
    if [[ -s "$apk" ]]; then cp "$apk" "$out/apks/$module-debug-$sha.apk" || collection=1
    else collection=1; fi
    for dir in reports test-results outputs/androidTest-results; do
      if [[ -d "$module/build/$dir" ]]; then
        dest="$out/reports/$module/$dir"
        mkdir -p "$dest" || collection=1
        cp -R "$module/build/$dir/." "$dest/" || collection=1
      fi
    done
  done
  export FIXTURE_TASK_EXIT="$result" FIXTURE_COLLECTION_EXIT="$collection"
  # Emit a public boundary even if the Python validator cannot start or finish.
  printf '::notice title=Fixture collection::phase=%s task_exit=%s collection_exit=%s\n' "$FIXTURE_PHASE" "$result" "$collection"
  validation=0
  python3 -c '
import hashlib, json, os, pathlib, struct, sys, xml.etree.ElementTree as ET, zlib
root = pathlib.Path(os.environ["FIXTURE_OUT"])
expected = ["01-feed-overlay", "02-dm-escape", "03-unknown", "04-completed-feed", "05-new-session", "06-back-unknown", "07-other-app", "08-disabled-feed", "09-disconnected", "10-background-return", "11-lock-return", "12-leave-home", "13-no-consent"]
errors = []
if {p.stem for p in (root / "screenshots").glob("*.png")} != set(expected):
    errors.append("Missing or unexpected fixture PNG names")
for name in expected:
    path = root / "screenshots" / (name + ".png")
    try:
        data = path.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n", "bad PNG signature"
        pos, ended, pixels = 8, False, bytearray()
        while pos < len(data):
            size = struct.unpack(">I", data[pos:pos+4])[0]
            kind = data[pos+4:pos+8]
            payload = data[pos+8:pos+8+size]
            crc = struct.unpack(">I", data[pos+8+size:pos+12+size])[0]
            assert zlib.crc32(kind + payload) & 0xffffffff == crc, "bad PNG CRC"
            if kind == b"IHDR":
                width, height = struct.unpack(">II", payload[:8])
                assert width > 0 and height > 0
            if kind == b"IDAT": pixels.extend(payload)
            if kind == b"IEND": ended = True
            pos += size + 12
        assert ended and zlib.decompress(pixels), "incomplete PNG"
    except Exception as exc:
        errors.append(f"{name}: {exc}")
expected_tests = {
    "unit": {"exactDeadlineAndSameSessionCredit", "everyNonFeedSurfaceAbortsWithoutCredit", "staleCompletionCannotCompleteReplacementAtItsDeadline", "escapeDoesNotRedrawBeforeNavigationAndDoesNotGrantCredit", "cancellationAlsoClearsNavigationSuppression", "repeatedEventsCannotExtendDeadlineOrShortenNextGate"},
    "device": {"feedIsActualOverlayAndMessagesEscapeImmediately", "completionGrantsOnlyCurrentForegroundFeedSession", "unknownAndOtherAppFailOpen", "disablingActualServiceRemovesWindow", "backgroundAndLockCancelStaleCompletion", "consentRevocationDisablesConnectionAndPreventsReactivation"},
}
failed_tests = set()
failure_frames = set()
for label, pattern in [("unit", "**/test-results/testDebugUnitTest/TEST-*.xml"), ("device", "**/outputs/androidTest-results/connected/**/TEST-*.xml")]:
    reports = list((root / "reports").glob(pattern))
    suites = []
    for path in reports:
        try:
            node = ET.parse(path).getroot()
            suites.extend([node] if node.tag == "testsuite" else list(node.iter("testsuite")))
        except ET.ParseError as exc: errors.append(f"{path}: {exc}")
    if not suites or sum(int(s.get("tests", 0)) for s in suites) != 6:
        errors.append(f"Missing complete {label} JUnit suite")
    cases = [case for suite in suites for case in suite.findall("testcase")]
    if len(cases) != 6 or {case.get("name") for case in cases} != expected_tests[label]:
        errors.append(f"Wrong {label} test identities")
    if (any(int(s.get("failures", 0)) or int(s.get("errors", 0)) or int(s.get("skipped", 0)) for s in suites) or
            any(case.find(kind) is not None for case in cases for kind in ("failure", "error", "skipped"))):
        errors.append(f"{label} failures/errors/skips")
    for case in cases:
        for kind in ("failure", "error", "skipped"):
            if case.find(kind) is not None:
                # Public annotations contain only allowlisted identities, never XML messages/stacks.
                name = case.get("name") if case.get("name") in expected_tests[label] else "unexpected-test"
                failed_tests.add(f"{label}:{name}:{kind}")
                if label == "device":
                    for frame in ("setUp", "tearDown", "shell", "measured"):
                        marker = f"at com.chardyb.doom.fixturegate.CrossAppFixtureTest.{frame}("
                        if any(marker in (failure.text or "") for failure in case.findall(kind)):
                            failure_frames.add(frame)
timings = {}
try:
    for line in (root / "screenshots/timings.txt").read_text().splitlines():
        key, value = line.split("=", 1)
        timings[key] = int(value)
    assert set(timings) == {"dm_escape_ms", "gate_from_launch_ms", "gate_observed_ms", "background_wait_ms", "lock_wait_ms"}
    assert all(value >= 0 for value in timings.values())
except Exception as exc: errors.append(f"Missing/invalid measured timings: {exc}")
files = [{"path": str(p.relative_to(root)), "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(root.rglob("*")) if p.is_file()]
metadata = {"scope": "WIL-149 TEST FIXTURE ONLY; not actual Instagram acceptance", "checkout_sha": os.environ["FIXTURE_SHA"], "github_sha": os.environ.get("GITHUB_SHA"), "repository": os.environ.get("GITHUB_REPOSITORY"), "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"], "workflow": os.environ.get("GITHUB_WORKFLOW"), "task_exit": int(os.environ["FIXTURE_TASK_EXIT"]), "collection_exit": int(os.environ["FIXTURE_COLLECTION_EXIT"]), "validation_errors": errors, "measured_timings_ms": timings, "required_screenshots": expected, "files": files}
phase = os.environ["FIXTURE_PHASE"]
assert phase in {"prepare", "build", "install", "instrumentation"}
metadata.update(task_phase=phase, failed_tests=sorted(failed_tests), failure_stack_frames=sorted(failure_frames))
(root / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
with (root / "SHA256SUMS").open("w") as output:
    for p in sorted(root.rglob("*")):
        if p.is_file() and p.name != "SHA256SUMS":
            output.write(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + str(p.relative_to(root)) + "\n")
if errors: print("Fixture evidence validation: " + "; ".join(errors), file=sys.stderr)
level = "error" if metadata["task_exit"] or metadata["collection_exit"] or errors else "notice"
print(("::{level} title=Fixture evidence::phase={task_phase} task_exit={task_exit} "
       "collection_exit={collection_exit} validation_errors={count} failed_tests={failed} stack_frames={frames}").format(
           **metadata, level=level, count=len(errors), failed=",".join(sorted(failed_tests)) or "none",
           frames=",".join(sorted(failure_frames)) or "none"))
sys.exit(bool(errors))
' || validation=$?
  if (( validation != 0 )); then
    printf '::error title=Fixture validation::validator_exit=%s (see fixture artifact for details)\n' "$validation" >&2
  fi
  printf 'Fixture artifacts: %s\n' "$out"
  if (( result != 0 )); then exit "$result"; fi
  if (( collection != 0 )); then exit "$collection"; fi
  exit "$validation"
}
trap collect EXIT
adb shell rm -rf /sdcard/Download/doom-fixture-evidence
adb shell mkdir -p /sdcard/Download/doom-fixture-evidence
FIXTURE_PHASE=build
./gradlew --no-daemon --stacktrace :fixtureapp:assembleDebug :fixturegate:assembleDebug :fixturegate:testDebugUnitTest :fixturegate:lintDebug | tee "$out/build.log"
FIXTURE_PHASE=install
adb install -r fixtureapp/build/outputs/apk/debug/fixtureapp-debug.apk
FIXTURE_PHASE=instrumentation
./gradlew --no-daemon --stacktrace :fixturegate:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.fixtureCi=true | tee "$out/instrumentation.log"
