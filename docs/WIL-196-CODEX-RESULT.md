# WIL-196 repair result

Repair scope was limited to `/mnt/HC_Volume_106820083/worktrees/doom/wil-196-sunset-debug-report`.

Exact starting head: `6abb000a4eced81db3cf2d5a47509001dcaab873` on branch
`wil-196-sunset-debug-report`, with a clean worktree. The repair is uncommitted. No commit,
push, PR, signing, release, or Linear update was performed.

## Repairs and files

- Supplemental captures keep `3_900` and `9_900` as elapsed milliseconds and now pass
  `10_000 - elapsedMs` to the remaining-time model. The exact fourteen-file evidence inventory
  and all names/counts are unchanged.
- Debug fixed-action delivery now uses one `BringIntoViewRequester` attached to the report
  anchor in the actual `verticalScroll` container. The request is consumed only after the
  bring-into-view operation returns. Cold and warm fixed-action instrumentation asserts the
  report controls are displayed without `performScrollTo` or swipes.
- `isDebugIntent` accepts only the exact action with no data/categories and `extras == null`.
  Malformed extras remain rejected through the public instrumentation callback API.
- Added narrow documentation for SystemUI/IME/foreign transitions that can clear the
  process-only report between Debug launch and verified Doom return; real behavior remains a
  consenting-phone evidence requirement.
- Corrected the persisted-boolean wording: consent and session-timer booleans are distinct
  from separately persisted reminder duration/suppression settings.
- Added pure tall-portrait geometry assertions and intentional landscape vertical-compression
  assertions.

Changed files from the exact starting head:

- `README.md`
- `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt`
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt`
- `app/src/main/java/com/chardy/doom/MainActivity.kt`
- `app/src/test/java/com/chardy/doom/BreathingVisualsTest.kt`
- `docs/WIL-149-VALIDATION.md`
- `docs/WIL-196-CLOUD-PREFLIGHT.md`
- `docs/WIL-196-CODEX-RESULT.md`
- `scripts/test-overlay-evidence.py`
- `scripts/test-structural-lifecycle.py`

No manifest, permission, dependency, workflow, router, selector authority, detachment,
cooldown, privacy, signing, fixture, or evidence-inventory change was made.

## TDD evidence

Focused RED after adding the new source/instrumentation contracts: the structural lifecycle
host runner reported 2 failures in 49 tests—the stale `extras?.isEmpty` predicate and the
missing bring-into-view implementation. The first causal failures were repaired in
`MainActivity.kt`; the phase conversion, malformed-extra assertion, geometry assertions, and
visibility instrumentation were then kept in the test/guard path.

Focused GREEN:

- `python3 -B scripts/test-overlay-evidence.py`: 8 tests passed.
- `python3 -B scripts/test-structural-lifecycle.py`: 49 tests passed.

## Host verification

All requested host checks passed after the repair:

- `test-entry-gate-host.py`: 91 tests.
- `test-structural-lifecycle.py`: 49 tests.
- `test-overlay-evidence.py`: 8 tests.
- `test-fixture-evidence.py`: 21 tests.
- `test_wil155_host.py`: 5 tests.
- `test-session-timer-host.py`: 5 JUnit tests and 11 Python tests.
- `test-removal-trace.py`: 8 tests.
- `test-ci-isolation.py`: 6 tests.
- `test-android-junit-validator.py`: 8 tests.
- `test-integrated-ci-harness.py`: 17 tests.
- `test-internal-signing.py`: 16 tests.
- Shell syntax for `ci-device.sh`, `ci-fixture.sh`, `ci-overlay.sh`,
  `ci-supplemental.sh`, and `sign-internal-apk.sh`: passed.
- Python XML parse of all 6 files under `app/src`: passed.
- `git diff --check`: passed.

No Gradle command was run, as requested. The earlier Cloud preflight record is explicitly
bound to source `366b2d253818bb2c319ddfdbdac34430973e5ae9` plus its test repair, committed as
`6abb000a4eced81db3cf2d5a47509001dcaab873`; its recorded totals are 16 signing tests, 105
JVM tests, 0 lint errors and 13 warnings, with the documented APK artifacts. Those numbers
are not a preflight result for this new uncommitted repair.

Final uncommitted binding, excluding this result document:

```text
git diff --binary HEAD -- . ':(exclude)docs/WIL-196-CODEX-RESULT.md' | sha256sum
e09bf4f7160b577bc60fac2524dfd9613c18cd618a6f67c4086e226d7ab886bb
```

The digest is intentionally recorded only after the final host checks and documentation edit.

## Remaining evidence boundary

No Gradle build, Android-test compilation, emulator, device, adb, GitHub Actions, screenshot
readback, signing, release, or real-Instagram/consenting-phone validation was run for this
repair. Host checks do not establish Android compilation, runtime Compose navigation, cold/warm
Activity delivery, screenshot appearance, TalkBack reachability, physical overlay detachment,
SystemUI/IME transition behavior, Instagram metadata behavior, or Messages routing. The exact
four canonical, fourteen supplemental, and thirteen fixture evidence contracts remain owned by
the authorized exact-head CI/device gates. No private content, screenshot, raw tree, or account
data was collected or claimed.
