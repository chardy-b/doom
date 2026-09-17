# WIL-196 repair result

Repair scope was limited to this worktree. The requested candidate was checked out at the
following exact starting head:

```text
7ce3207712cc9b34f9cd45b1900cc74033058c72
branch: wil-196-sunset-debug-report
worktree: clean
```

No commit, push, PR, signing, release, or Linear update was performed. The worktree is left
uncommitted as requested.

## TDD result

RED was the supplied independent review of the exact starting head:
`/mnt/HC_Volume_106820083/artifacts/doom-wil-196/opus-review-7ce3207.md`. It reported one
High timer-dismissal regression plus the bounded Medium/Low repairs addressed here. No
unexecuted Android or Gradle failure is represented as test evidence.

GREEN focused host checks after the repairs:

- `test-entry-gate-host.py`: **98 JVM tests passed**.
- `test-structural-lifecycle.py`: **50 tests passed**.
- `test-session-timer-host.py`: **5 JVM tests and 11 Python tests passed**.

The full requested host checks also passed:

- `test-overlay-evidence.py`: **8 tests**.
- `test-fixture-evidence.py`: **21 tests**.
- `test_wil155_host.py`: **5 tests**.
- `test-internal-signing.py`: **16 tests**.
- Shell syntax for `scripts/ci-device.sh`, `scripts/ci-fixture.sh`, and
  `scripts/sign-internal-apk.sh`.
- XML parsing for the changed `app/src/main/res/values/strings.xml`.
- `git diff --check`.

No Gradle command, emulator, device, adb, or connected Android test was run.

## Repairs and bounded tests

- Root package classification preserves only closed `system_ui` and `recognized_ime` tokens
  before ordinary foreign sanitization. Service-level timer tests prove SystemUI and a
  recognized IME preserve `timerDismissedThisVisit`, while ordinary foreign clears it.
- Unique-ID serialization now emits exact `u:free_form`, `u:absent`, `u:api`, and
  `u:read_error` wires; only a unique ID equal to the accepted resource token is retained.
  Exact wire tests cover unavailable and accepted/unsafe cases.
- Timeout stops before adding the timed-out row and marks `time`; clock rollback invalidates
  the whole capture. A dangling parent uses existing `nodes` truncation vocabulary. Disclosure
  text now states these rules and the typed unique-ID markers.
- Debug anchor readiness is request-scoped and reset on leaving Debug; the warm-request
  regression requires fresh anchor placement before bring-into-view.
- Intermediate API-ceiling tests, custom action-label/extras canaries, and OPEN_DEBUG
  precedence pairs against COMPLETE and NAVIGATE_MESSAGES were added.
- The documented SystemUI/IME/foreign transition risk between Debug launch and verified Doom
  return remains a consenting-phone acceptance item. Detachment, authority, privacy, routing,
  cooldown, permissions, inventories, and signing behavior were not weakened.

Changed files from the exact starting head:

- `README.md`
- `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt`
- `app/src/androidTest/java/com/chardy/doom/StructuralMetadataApiTest.kt`
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`
- `app/src/main/java/com/chardy/doom/MainActivity.kt`
- `app/src/main/java/com/chardy/doom/SanitizedStructuralReport.kt`
- `app/src/main/java/com/chardy/doom/TimerDismissalRoot.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/chardy/doom/OverlayRemovalPolicyTest.kt`
- `app/src/test/java/com/chardy/doom/SanitizedStructuralReportTest.kt`
- `app/src/test/java/com/chardy/doom/StructuralMetadataTest.kt`
- `app/src/test/java/com/chardy/doom/TimerDismissalRootTest.kt`
- `docs/WIL-149-VALIDATION.md`
- `docs/WIL-196-CLOUD-PREFLIGHT.md`
- `docs/WIL-196-CODEX-RESULT.md`
- `scripts/test-entry-gate-host.py`
- `scripts/test-structural-lifecycle.py`

## Provenance and remaining evidence

The earlier Android preflight remains recorded in
[`docs/WIL-196-CLOUD-PREFLIGHT.md`](WIL-196-CLOUD-PREFLIGHT.md), but is explicitly bound to
candidate `501d174` and must not be attributed to this `7ce3207` repair. This repair has no
new Gradle evidence.

The exact four canonical, fourteen supplemental, and thirteen fixture evidence contracts still
require authorized exact-head CI/device execution and review. Remaining unrun evidence includes
Android compilation/lint/assembly, Android-test compilation and execution, emulator/device
timing, screenshots, physical overlay detachment, SystemUI/IME/foreign Debug-return behavior,
real Instagram metadata, Messages routing, protected signing, and consenting-phone acceptance.
Host checks do not establish any of those results.

Uncommitted provenance digest, excluding this result document, is to be recorded only after the
final documentation edit and final host checks:

```text
git diff --binary HEAD -- . ':(exclude)docs/WIL-196-CODEX-RESULT.md' | sha256sum
b9dce98f6eb148ae12ca35562ee2ff177fa892e808e45efefb5717c6ddeef87c  -
```
