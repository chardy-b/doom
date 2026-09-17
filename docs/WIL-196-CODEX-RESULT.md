# WIL-196 API-35 CI repair result

This repair starts from the exact failing head:

```text
head:   d2dabd5c9675fcf2f5a7aeabefc60da58388585e
branch: wil-196-sunset-debug-report
state:  uncommitted reviewable changes
```

No local Gradle, emulator, adb, device, signing, commit, push, PR, or Linear action was
performed. Earlier preflight records for `db6c46c` and `4df0506` are not evidence for this head.

## CI failure classification and repair

The supplied API-35 CI failures were test isolation/lifecycle defects except where a test used the
wrong source contract. No production behavior change was justified by these failures.

1. `actualDebugButtonRejectsStaleOrMissingAuthorityBeforeRemoval` created the fake overlay with
   `attached = false`, then asserted that the stale-authority callback had left it attached. The
   assertion tested its own invalid setup. The test now starts attached, proving no removal or
   launch for each missing overlay/ticket/token/connection authority case.
2. `leavingDebugThenStartingWarmDebugRequestReanchorsControls` and
   `warmRepeatedDebugIntentTargetsControlsAndMalformedReplacementDoesNotReplay` were affected by
   the cold test's nested `ActivityScenario`: the production Debug intent's `CLEAR_TOP | SINGLE_TOP`
   flags could reuse the rule-owned Activity, and closing the nested scenario then left rule
   teardown waiting on the wrong RESUMED Activity. The cold test adds test-only
   `FLAG_ACTIVITY_MULTIPLE_TASK`, keeping its cold Activity separate; the production intent and
   flags are unchanged.
3. `previewCancelAndConsumedDebugRequestSurviveRotationWithoutPersistingReport` and the cold
   test's Activity-destroyed NPE were cross-test lifecycle contamination, not a preview/report
   contract failure. Manually-created services and fake fixtures in the service-action and
   structural UI tests are now tracked and destroyed in `@After` without closing or replacing the
   rule-owned Activity. The nested cold scenario is also closed in its own task.
4. `doomEventsPreserveReportButForeignOrMissingRootInvalidatesAllReportState` called the private
   Instagram-only collector with a Doom package and therefore deliberately recorded `null`. It
   now sends an actual Doom `MainActivity` window event for preservation and real foreign/null
   events for cleanup. The Instagram-only collector remains unchanged.
5. `connectionAlwaysClearsStateAndInterruptedObserverRequiresConnectionCallback` reached its
   final report assertion through a synthetic collector context whose fixed `startedElapsedMs =
   1_000L` had already timed out against the device clock. The helper now anchors the synthetic
   context to `SystemClock.elapsedRealtime()`; the connection callback still clears before
   re-enabling observation.
6. `customActionLabelAndExtrasAreNotSerializedOrRead` hit the same stale synthetic-clock path:
   `Builder.build()` correctly returned `null` for a timed-out capture, so `build()!!` was a test
   error. Its source/privacy assertions are unchanged; only the test's clock setup is repaired.

The supplemental `supplementaryFooterScreenshotScrollsOnlyInItsSeparateTest` used
`startActivity(DebugIntent)` against the rule-owned Activity and could leave ActivityScenario
teardown PAUSED. It now delivers the same explicit internal intent through the rule-owned
Activity's `onNewIntent`; canonical cold system launch remains covered separately.

## Changed files

- `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt` — attached stale-
  authority setup and deterministic cleanup of manually-created services/fixtures.
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` — isolated cold
  ActivityScenario, service cleanup, real Doom/foreign event paths, and current-clock synthetic
  capture setup.
- `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt` — rule-owned Activity
  delivery for the separate footer screenshot test.
- `scripts/test-structural-lifecycle.py` and `scripts/test-overlay-evidence.py` — source guards
  updated to require the repaired event/lifecycle boundaries.
- `docs/WIL-196-CODEX-RESULT.md` — this exact-head result and evidence boundary.

No production Kotlin, manifest, permission, dependency, CI workflow, signing, privacy, safety,
Direct-tab, stale-ticket, or physical-detachment contract was weakened or changed.

## Host verification on this worktree

Required AGENTS checks passed:

- `test-entry-gate-host.py`: **98 tests passed**.
- `test-structural-lifecycle.py`: **50 tests passed**.
- `test-overlay-evidence.py`: **8 tests passed**.
- `test-fixture-evidence.py`: **21 tests passed**.
- `test_wil155_host.py`: **5 tests passed**.
- `test-internal-signing.py`: **16 tests passed**.
- Shell syntax for `ci-device.sh`, `ci-fixture.sh`, and `sign-internal-apk.sh`: passed.
- `git diff --check`: passed.

Additional WIL-196 plan checks passed: session-timer **5 JVM tests + 11 Python tests**, removal
trace **8 tests**, CI isolation **6 tests**, Android JUnit validator **8 tests**, integrated CI
harness **17 tests**, and XML parsing for all `app/src` XML files.

## Evidence boundary

These are host source/validator results only. No Android compilation, lint, APK assembly,
Android-test compilation, emulator instrumentation, screenshot, fixture device run, route,
physical overlay-detachment, protected signing, or consenting-phone/real-Instagram result is
claimed for `d2dabd5c9675fcf2f5a7aeabefc60da58388585e`. The supplied CI failures remain repaired
only at source level until a new authorized exact-head API-35 CI run is executed and its complete
canonical, supplemental, fixture, and provenance evidence is read back.
