# WIL-196 repair result

This repair started from the exact requested candidate:

```text
head:   4df0506d920e59f4a196fffbb14cd988ab2e20ee
branch: wil-196-sunset-debug-report
state:  clean
```

The worktree is intentionally left uncommitted. No push, PR, Linear update, Gradle command,
emulator, device, adb, signing, or release action was performed.

## TDD result

The supplied review finding was the runtime RED: two valid Debug intents followed by a malformed
replacement in one idle turn could have the malformed `onNewIntent` clear `debugRequestPending`
before recomposition, so the valid request was not consumed and the report controls were not
anchored.

The focused host RED was then reproduced after strengthening the test/guard: `test-structural-
lifecycle.py` ran 50 tests with one failure because the candidate contained
`debugRequestPending = false` in `onNewIntent`.

The GREEN is the smallest fail-safe repair:

- exact valid Debug intents still increment the request sequence and set the pending bit;
- malformed/unrelated intents still grant no request and do not increment the sequence;
- an already pending valid request is left for the current composition to consume;
- one-shot consumption still clears the pending bit and marks the request consumed;
- the warm UI test asserts both control visibility and that the malformed intent did not leave a
  second request queued.

Focused GREEN: `test-structural-lifecycle.py` — **50 tests passed**.

## Changed files

- `app/src/main/java/com/chardy/doom/MainActivity.kt` — preserve a pending valid request across
  malformed/unrelated `onNewIntent` calls.
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` — assert malformed
  replacement does not queue a second request.
- `scripts/test-structural-lifecycle.py` — source guard for the pending-request invariant.
- `docs/WIL-196-CODEX-RESULT.md` — this exact-head result record.

The existing single `ActivityScenario` cold-intent test was left in place. A rule-owned recreate
or warm-intent mechanism would not reliably establish the initial `onCreate` intent without
broadening this repair; the cold path remains canonical API-35 emulator evidence.

## Required host checks

All requested host checks passed after the repair:

- `test-entry-gate-host.py`: **98 tests passed**.
- `test-structural-lifecycle.py`: **50 tests passed**.
- `test-overlay-evidence.py`: **8 tests passed**.
- `test-fixture-evidence.py`: **21 tests passed**.
- `test_wil155_host.py`: **5 tests passed**.
- `test-internal-signing.py`: **16 tests passed**.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh`: passed.
- `git diff --check`: passed.

No XML file changed, so changed-XML parsing was not applicable.

## Remaining evidence

This repair has no new Android compilation, unit-test, lint, APK, or Android-test compilation
result. The previously recorded Codex Cloud preflight is bound to its own exact candidate and is
not attributed to this head.

Still requiring authorized exact-head CI/device evidence are Android compilation and lint,
canonical API-35 instrumentation for cold/warm/repeated/rotation/malformed Debug navigation,
supplemental and fixture lanes, screenshots and timing, physical overlay detachment and
foreign/SystemUI/IME return behavior, protected signing provenance, and consenting-phone
validation with real Instagram metadata and Messages routing. Host checks do not establish any
of those results.
