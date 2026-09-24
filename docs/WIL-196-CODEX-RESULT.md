# WIL-196 API-35 repair result

Repair target: exact head `cb6e2246e158b790df8658e8cf6f943daeb1dba0` on
`wil-196-sunset-debug-report`. The supplied API-35 records are runs `35197051876` and
`35197051885`.

No local Gradle, emulator, adb, device, signing, commit, push, PR, or Linear action was
performed. The supplied evidence is not a green emulator result: baseline passed, the
cross-app fixture passed 6/6, supplemental evidence had one teardown failure, and canonical
execution reached 122+ of 132 tests before the workflow timeout/cancel.

## Root causes repaired

- The rule-owned Activity was still being recreated by the rotation test and supplemental
  screenshot helpers. Those flows now launch an explicitly owned `ActivityScenario` in a new
  document/multiple task, recreate only that scenario, and close only that scenario. The rule
  Activity remains the rule's owner throughout.
- The cold Debug journey now uses the same isolated task boundary. Warm repeated and
  leave/return journeys deliver `onNewIntent` through the public instrumentation API to the
  rule-owned Activity; they do not start, finish, clear, or replace a task.
- Rotation now waits for the recreated owned Activity's Debug controls and asserts the Debug
  destination anchor. It does not scroll the destination into view as part of the assertion.
- The report-preservation test now uses a real Doom `MainActivity` event for preservation and
  real foreign/null events plus a real Instagram event with a missing root for invalidation. The
  separate report-only event test remains the synthetic-root collection path.
- Host guards now reject rule-owned recreation and conflicting Debug task launches while
  accepting the explicit owned-scenario strategy.

No production Kotlin, manifest, permission, dependency, CI workflow, signing, privacy, safety,
Direct-tab, stale-ticket, or physical-detachment contract was weakened or changed.

## Changed files

- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` — isolated cold and
  rotation Activities, deterministic post-rotation destination synchronization, and exact
  service-root setup for the report test.
- `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt` — owned scenarios for
  supplemental recreation/screenshot flows; warm Debug delivery remains `onNewIntent`.
- `scripts/test-structural-lifecycle.py` and `scripts/test-overlay-evidence.py` — ownership and
  evidence source guards.
- `docs/WIL-196-CODEX-RESULT.md` — exact-head repair and evidence boundary.

## Local verification

The host-only checks run after this repair are recorded below. They do not establish Android
compilation, lint, APK output, emulator instrumentation, screenshots, fixture device evidence,
route behavior, physical detachment, protected signing, or real-Instagram behavior.

<!-- HOST_CHECKS -->

- `test-entry-gate-host.py`: **98 tests passed**.
- `test-structural-lifecycle.py`: **51 tests passed**.
- `test-overlay-evidence.py`: **8 tests passed**.
- `test-fixture-evidence.py`: **21 tests passed**.
- `test_wil155_host.py`: **5 tests passed**.
- `test-internal-signing.py`: **16 tests passed**.
- `test-removal-trace.py`: **8 tests passed**.
- `test-session-timer-host.py`: **5 JVM + 11 Python tests passed**.
- `test-ci-isolation.py`: **6 tests passed**.
- `test-android-junit-validator.py`: **8 tests passed**.
- `test-integrated-ci-harness.py`: **17 tests passed**.
- Relevant shell syntax checks and XML parsing: passed.
- `git diff --check`: passed.

## Remaining risk

Independent review of `932c8d2` found that `MainActivity.onNewIntent` and
`consumeDebugRequest` changed the Activity's launch-Intent identity. AndroidX
`ActivityScenario` uses that identity to associate lifecycle callbacks, explaining the remaining
API-35 teardown timeouts despite task isolation. The Activity now keeps its original launch Intent;
the fixed Debug action remains one-shot through the existing request sequence, pending flag, and
saved consumed state. Instrumentation and host guards assert that warm delivery preserves the
launch identity. The successful synthetic-root path again asserts that text, content description,
hint, and error canaries are absent from the serialized report.

The repaired source still requires a new authorized exact-head API-35 run. In particular, the
owned-task lifecycle, Android ActivityScenario teardown, post-rotation visibility, screenshot
restoration, and complete canonical execution have not been re-proven on an emulator here.
