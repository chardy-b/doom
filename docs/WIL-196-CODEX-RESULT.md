# WIL-196 repair result

Repair was performed only in `/mnt/HC_Volume_106820083/worktrees/doom/wil-196-sunset-debug-report`.

Exact starting candidate: commit `bcc2dae79be290938a9317d132408280c7baf968`, branch `wil-196-sunset-debug-report`, with a clean worktree. The repair remains uncommitted. The final review binding is the starting commit plus the uncommitted production/test/docs/scripts diff digest recorded below (the result document is excluded to avoid self-referential hashing); no commit, push, PR, signing, release, or Linear update was performed.

## Repairs

- Restored report-only event collection with fresh v2 report consent and a connected service when optional gate consent is off. Added a real instrumentation event regression and an exact host predicate guard.
- Reserved the complete comma-separated truncation-reason header before row emission, asserted final ASCII/byte length, restored an all-limit rich-row sweep, and disclosed practical row capacity.
- Rechecked every Debug release authority after detachment and after the potentially reentrant root read. The actual button callback now has fake-platform coverage for detach/retry, report identity/hide, timer stop, exactly-once launch, no cooldown/recollection, false/throwing launch, and safety races.
- Added typed Boolean getter error encoding, typed action-list unavailability, rollback invalidation versus bounded timeout truncation, and classifier input from all token-admitted DTOs independent of emitted rows.
- Made consumed Debug requests recreation-safe with a process-local Activity saved marker; no report or authority is persisted. Added cold/warm/repeated/malformed/preview-cancel/rotation-recreate coverage and routed the fixed action to the Debug report-control anchor.
- Corrected the continuous gold→orange→aubergine RGBA ramp, added a visible alpha floor and proportional lattice gaps, and tested alpha-qualified extents, color neighbors, one ivory center, and growth geometry.
- Removed the default Debug callback, narrowed API suppressions around guarded reads, and corrected current disclosure/validation text. The exported Activity action is documented as a destination hint only; it grants no report or authority.

No permission, network, storage, dependency, workflow, signing, router, selector authority, or privacy boundary was expanded.

## Host verification executed after repair

All listed checks exited 0 after the final edits:

- `test-entry-gate-host.py`: 90 tests; `test-structural-lifecycle.py`: 47; `test-overlay-evidence.py`: 8; `test-fixture-evidence.py`: 21; `test_wil155_host.py`: 5.
- `test-session-timer-host.py`: 5 JUnit plus 11 Python; `test-removal-trace.py`: 8; `test-ci-isolation.py`: 6; `test-android-junit-validator.py`: 8; `test-integrated-ci-harness.py`: 17; `test-internal-signing.py`: 16.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh`: passed.
- Changed XML parsing: 1 file; `git diff --check`: passed.

Final uncommitted binding: starting commit `bcc2dae79be290938a9317d132408280c7baf968`; `git diff --binary HEAD -- . ':(exclude)docs/WIL-196-CODEX-RESULT.md'` SHA-256 `b9b54c8fb02d81598e593c46ef01e4dccb1bbe86305069923310da3d7dc8a7e2`.

The earlier Cloud preflight in `docs/WIL-196-CLOUD-PREFLIGHT.md` is historical evidence for its explicitly recorded source commit `9beba5fa89e83f0ef0b483d75f043e40314c5467`, not evidence for this repaired uncommitted tree. It reported 16 signing tests, 99 JVM tests, zero lint errors, and successful debug/APK assembly, but no emulator/device run. Gradle is intentionally not run for this repair per task instructions.

## Remaining evidence boundary

No Gradle command, emulator, device, adb, GitHub Actions, Android instrumentation execution, signing, release, or real-Instagram/consenting-phone validation was run for this repair. Android compilation/lint, runtime callback timing, Activity recreation behavior on a device, screenshot appearance, TalkBack reachability, exact overlay detachment timing, Instagram metadata behavior, and Messages routing remain unverified and must be established by the authorized API-35 CI/device gates. No private content, screenshot, raw tree, or account data was collected or claimed.
