# WIL-184 Codex Cloud implementation report

Date: 2026-09-13
Branch: `ryli721/wil-184-terminal-only-cooldown`

## Review repair in the local worktree (2026-09-13)

The following repair supersedes the candidate described by the historical Cloud results below. It was performed in `/mnt/HC_Volume_106820083/worktrees/doom-wil184-terminal-cooldown` under the user's explicit blocking-finding scope. The existing index was left untouched; repair edits are unstaged. No credentials or live Linear data were accessed; the user's repair contract is the scope authority.

- BLOCKER-1: `actualAccessibilityCooldownSuppressesBeforeRootAndCollection` now installs through the real service event/installer path, invokes its captured completion Runnable at five seconds, verifies detached/GRANTED/active cooldown, resets outside, then checks suppression against root/removal/install/generation/ticket counts and a synthetic sentinel report. The unused local `entryGate` is removed.
- HIGH-1: Instagram events return early when an overlay remains visible or closing, after existing consent/connection and earned-cooldown checks and before new-session ticket/root/report work. Existing foreign-event handling, the bounded package-only watchdog, removal arbitration, and terminal checks are unchanged. Installed-gate tests cover repeated valid/missing/foreign/unattributed/throwing event roots, preserved report/view/token/timer, closing Messages continuation, and watchdog retention at 149 ms/removal at 150 ms.
- Added service scenarios for report/gate consent revocation and token replacement/invalidation inside a narrow synchronous `beforeRouteReturns` fake-platform hook returning `CLICKED`; each checks one route, one terminal-root release, and no cooldown. The already-selected test now checks inactive cooldown inside that hook too.
- Added installed-service completion rejection for missing, foreign, null-package, and throwing roots, and foreign app-switch/reset followed by immediate Instagram re-entry installing a new gate without cooldown. Existing negative route, stale callback, consent, disconnect, Home, and removal-exhaustion paths gained inactive-cooldown assertions. Initial invalid-root trace tests now use a fresh service before installation, consistent with the new installed-gate behavior.
- Corrected the current validation contract's stale display-arming statement and the disclosure's already-selected-tab grammar. Added a host source guard for the early-return ordering.

Repair files: `DoomAccessibilityService.kt`, `EntryGateServiceActionTest.kt`, `strings.xml`, `docs/WIL-149-VALIDATION.md`, `scripts/test-structural-lifecycle.py`, and this retrospective. No other repair edits were made. Terminal-only credit, physical-detach-before-action, exact `direct_tab` routing, one-shot action, root release, privacy/permissions, and canonical/supplemental CI separation remain unchanged.

The service scenarios are instrumentation source, using the actual service installation/control flow with synthetic nodes and fake window side effects. They do not establish an OS-installed accessibility window, actual Instagram behavior, or a passing device result. No Gradle, Android compilation, Cloud preflight, emulator, adb, device, signing, or publishing operation ran for this repair. Historical Cloud test counts and APK details below do not validate these edits. The broader deferred service/router/policy matrix is not claimed complete.

### Repair validation actually executed

All commands below ran in the local worktree and exited 0 (189 tests total):

| Command | Result |
| --- | --- |
| `python3 -B scripts/test-entry-gate-host.py` | 85 pure Kotlin/JUnit tests passed using cached host compiler libraries; no Gradle invocation |
| `python3 -B scripts/test-structural-lifecycle.py` | 38 source-guard tests passed |
| `python3 -B scripts/test-overlay-evidence.py` | 5 tests passed |
| `python3 -B scripts/test-fixture-evidence.py` | 21 tests passed |
| `python3 -B scripts/test_wil155_host.py` | 5 tests passed |
| `python3 -B scripts/test-internal-signing.py` | 14 validator tests passed; no signing operation |
| `python3 -B scripts/test-removal-trace.py` | 8 tests passed |
| `python3 -B scripts/test-ci-isolation.py` | 5 tests passed |
| `python3 -B scripts/test-android-junit-validator.py` | 8 tests passed |

`bash -n scripts/ci-device.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` passed. Python ElementTree parsed `app/src/main/res/values/strings.xml`. Both `git diff --check` and `git diff --cached --check` passed. These host results do not compile or execute the Android service or instrumentation additions.

## Historical pre-repair implementation record

The remainder records the prior implementation's reported execution; those results were not rerun or independently revalidated by this repair.

## Root cause

The production service called `admitForDisplay` immediately after `addView` and `overlayShown`. That display-admission operation recorded the cooldown timestamp, so Back, Home, app switching, and later nonterminal removal inherited a cooldown despite never completing the five-second intervention. The Messages path also discarded `MessagesRouteResult`, preventing policy from distinguishing a successful exact Direct-tab result from failed or aborted routing.

## Implementation

- Cooldown storage is now terminal-oriented and can be mutated only inside atomic gate-policy completion/result operations.
- Overlay display starts only the unchanged five-second visible clock.
- Messages routing uses a single-use preparation/result contract. Only `CLICKED` and `ALREADY_SELECTED` arm cooldown; `FAILED`, exceptions, stale authority, revocation, and other aborts consume or invalidate the attempt without credit.
- The service retains exact detached-epoch authority through synchronous completion/routing, rechecks current ticket/generation, consent, connection, physical detachment, and Instagram root attribution, and consumes that authority afterward.
- Completion now uses the detached token ticket and current Instagram root revalidation before sampling terminal time.
- Home and all generic bypass/reset operations remain non-crediting. Earned cooldown timestamps remain intact across later cleanup.
- User disclosure and validation documentation now describe terminal-only ownership.

## Files changed

- `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt`
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`
- `app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt`
- `app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt`
- `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt`
- `app/src/test/java/com/chardy/doom/OverlayCallbackGuardTest.kt`
- `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`
- `scripts/test-structural-lifecycle.py`
- `README.md`
- `docs/WIL-149-VALIDATION.md`
- `app/src/main/java/com/chardy/doom/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- This report.

No manifest, permission, dependency, Gradle, workflow, fixture, signing, network, analytics, persistence, traversal, gesture, retry, or coordinate-action change was made.

## Commands and exact exit codes

### Host validation

All commands ran from `/workspace/doom` and exited `0`:

- `python3 -B scripts/test-entry-gate-host.py` — exit 0; 85 tests, 0 failures/errors.
- `python3 -B scripts/test-structural-lifecycle.py` — exit 0; 37 tests, 0 failures/errors.
- `python3 -B scripts/test-overlay-evidence.py` — exit 0; 5 tests, 0 failures/errors.
- `python3 -B scripts/test-fixture-evidence.py` — exit 0; 21 tests, 0 failures/errors.
- `python3 -B scripts/test_wil155_host.py` — exit 0; 5 tests, 0 failures/errors.
- `python3 -B scripts/test-internal-signing.py` — exit 0; 14 tests, 0 failures/errors.
- `python3 -B scripts/test-removal-trace.py` — exit 0; 8 tests, 0 failures/errors.
- `python3 -B scripts/test-ci-isolation.py` — exit 0; 5 tests, 0 failures/errors.
- `python3 -B scripts/test-android-junit-validator.py` — exit 0; 8 tests, 0 failures/errors.
- `bash -n scripts/ci-device.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` — exit 0.
- `python3 -B -c 'import xml.etree.ElementTree as ET; ET.parse("app/src/main/res/values/strings.xml"); print("strings.xml: parsed")'` — exit 0.
- `git diff --check` — exit 0.

### Focused no-emulator unit validation

- `./gradlew --no-daemon --stacktrace :app:testDebugUnitTest --tests com.chardy.doom.InstagramEntryGateTest --tests com.chardy.doom.OverlayCallbackGuardTest --tests com.chardy.doom.OverlayRemovalPolicyTest --tests com.chardy.doom.OverlayForegroundWatchdogTest --tests com.chardy.doom.InstagramMessagesRoutingPolicyTest` — exit 0; `BUILD SUCCESSFUL`.

### Required Cloud preflight

- `python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — exit 0; signing validator 14 tests passed; Gradle `BUILD SUCCESSFUL`; app unit suite 89 tests across 11 suites, 0 failures/errors/skips; lint 0 errors and 11 warnings.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
Size: 8,826,819 bytes
SHA-256: `c7377baf54a8478c5749b2a517aa06618a655dd0def9d1ae32c6b4ce3ffb6a17`

### Android-test source compilation

- `./gradlew --no-daemon --stacktrace :app:compileDebugAndroidTestKotlin` — exit 0; `BUILD SUCCESSFUL`. Existing SDK/deprecation/type-mismatch compiler warnings were emitted; no compilation error occurred.

## Limitations and release evidence

No emulator, Android instrumentation execution, connected device test, adb command, or real Instagram test ran. Android-test source compilation is not device evidence. No APK signing operation, protected signing, push, merge, release, store upload, or APK publication ran. The signing command above validates signing controls only and does not sign an APK. Canonical API 35 instrumentation, exact-head evidence, protected signing, and consented phone behavior remain parent/CI responsibilities.

## Concise change summary

Cooldown now begins at genuine terminal success—not overlay display—while the service preserves five-second timing, exact Direct-tab constraints, physical-detachment ordering, current-authority vetoes, and the 59,999/60,000 boundary from terminal time. Failed and aborted Messages routes remain bypassed and unarmed.
