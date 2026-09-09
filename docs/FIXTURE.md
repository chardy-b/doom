# WIL-149 cross-app TEST FIXTURE proof

## Status and boundary

This is Wave 0 feasibility source, not product acceptance. Android compilation, lint, JVM tests, instrumented tests and screenshots MUST run in the parent GitHub CI. They were not run on the worker host. No device evidence or elapsed timing is claimed by this document.

- `:fixtureapp` is an application with package `com.chardyb.doom.testfixture`. It owns original, static TEST FIXTURE Feed, DM and Unknown screens.
- `:fixturegate` is an independent application with package `com.chardyb.doom.fixturegate`. It owns consent UI and an actual bound AccessibilityService. Its gate is a `TYPE_ACCESSIBILITY_OVERLAY` window, not an Activity.
- Neither module depends on `:app`, and `:app` depends on neither module. Do not distribute either fixture APK as the user diagnostic APK.
- Production `:app` remains observation-only for real Instagram. This change does not add Instagram selectors, links, accounts, tree logs, screenshots, intervention or acceptance evidence.
- All source and surface content is original. No new third-party assets or production dependencies were added. Tests use the existing project's JUnit / AndroidX test versions.

## Consent and privacy

Open TEST FIXTURE — gate. Read the disclosure and select the consent checkbox. Only then is Open accessibility settings enabled. A human must enable the service in Android settings. Runtime source never changes secure settings or grants its own permission. A service activated without stored consent immediately calls `disableSelf()`.

Disable and clear TEST FIXTURE consent clears local consent, removes any overlay and calls `disableSelf()` through the preference listener. Connection text follows service connect/unbind/destroy callbacks, not merely the enabled-services setting. Consent is the only stored app state. Both APKs disable backup and exclude cloud/device-transfer data. Neither manifest requests INTERNET or storage permission. The only exported activity in each module is its normal launcher; there are no exported automation endpoints, providers or receivers. The service is protected by `BIND_ACCESSIBILITY_SERVICE` and declares `isAccessibilityTool=false`.

The fixed target is `com.chardyb.doom.testfixture`. The only classification IDs are that package's `fixture_feed_marker`, `fixture_dm_marker`, `fixture_unknown_marker` and clickable `fixture_dm_button`. The service never reads event source, text or descriptions, nor logs trees. Outside the fixture it uses event package/window/type and window focus/type/ID metadata solely to fail open.

## Overlay and session behavior

1. A confirmed focused fixture window with Feed and the known DM button starts one monotonic five-second policy ticket.
2. A full-screen, non-focusable system accessibility overlay blocks Feed touches. It leaves system Home/Back available. The overlay says TEST FIXTURE and offers Fixture messages and Leave immediately.
3. Fixture messages first removes the overlay and cancels its timer. It then obtains the current confirmed fixture root, validates the DM button's package, window ID, exact resource ID, visibility, enabled state and clickability, refreshes it, and performs `ACTION_CLICK`. There are no coordinates or private links. Failed validation/click fails open without credit. Navigation suppression prevents redraw between window removal and the DM event.
4. Leave removes the overlay and uses the system Home action. Fixture Back is an original public UI transition to Unknown, which also removes the gate.
5. Completion rechecks consent, screen/keyguard, focused window and surface before granting access. Credit belongs only to this uninterrupted foreground Feed session. DM, Unknown, another app/system window, Home, screen off, permission loss, interrupt, unbind and destroy cancel pending work without credit and reset the session. A new Feed session requires a new five-second gate.
6. `GatePolicy` owns ticket generations. Old callbacks cannot complete a replacement ticket. No surface budgets or renewal doubling are implemented.

### Window handling and bounds

`rootInActiveWindow` is deliberately not used: an accessibility overlay can become active. The service records the fixture window ID only from fixture package events. It selects a focused application window with that ID, then validates the root package before descending. It never fetches an unrelated window root. Each scan visits at most 128 nodes, caps its queue, and refuses cross-package children and ambiguous/unrecognized trees. DM or Unknown classification takes precedence over Feed.

The overlay is non-focusable. Own overlay window IDs are recognized from window type plus event package and remembered in a bounded 16-ID set, including removal events. This prevents overlay-induced recursive flicker or clearing newly completed credit. Own controller Activity events are not treated as overlay events.

A main-thread 200 ms watchdog complements window events. It checks consent, screen/keyguard and focused window metadata; only a previously confirmed, still-focused fixture window can be traversed. It operates while connected, including granted sessions, because a dropped background event must not preserve credit. Missing root, window mismatch and runtime inspection failures fail open. Cancellation is event-driven where available; 200 ms is a polling interval, NOT an asserted measured device latency. UI thread/system scheduling can add delay. The CI tests must verify actual behavior. The fixture's simple tree makes this bounded approach sufficient for feasibility, not a production classifier.

## CI bootstrap (parent-owned)

Use a clean checkout on an isolated GitHub Actions Linux runner. Use Java 17, the checked-in Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.0.21 and compile/target SDK 35. Do not upgrade versions. Install Android SDK platform 35, compatible build-tools 35.0.0, platform-tools, emulator and an API 35 x86_64 system image (AOSP or Google APIs). Use one booted disposable emulator with hardware acceleration, `ro.kernel.qemu=1`, no accounts, no private data, no secure lock credential, no other accessibility services and normal animation/time scales. Disable snapshots and test sharding. Ensure shell `wm dismiss-keyguard` can dismiss its keyguard. Do not install or open Instagram in this fixture job.

Run this single command inside the emulator runner's script phase, before teardown:

```sh
bash scripts/ci-fixture.sh
```

The script refuses execution unless `GITHUB_ACTIONS=true` and numeric `GITHUB_RUN_ID` / `GITHUB_RUN_ATTEMPT` exist. The checkout must be clean for exact-SHA artifact provenance. It checks API 35 and emulator identity. It performs:

```text
:fixtureapp:assembleDebug
:fixturegate:assembleDebug
:fixturegate:testDebugUnitTest
:fixturegate:lintDebug
adb install -r fixtureapp/build/outputs/apk/debug/fixtureapp-debug.apk
:fixturegate:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.fixtureCi=true
```

Instrumentation requires `fixtureCi=true` plus emulator identity. Test-only shell operations enable the actual service after public-UI consent, restore secure settings afterward and test activation rejection without consent. `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` is required on the test's single UiAutomation client so instrumentation does not suppress the service under test. Do not run shell uiautomator alongside these tests. Tests use shell Activity launch/reorder after Home instead of `ActivityScenario.moveToState(RESUMED)`, which is invalid for this SDK 35 lifecycle case.

Cleanup uses the instrumentation target context, asserted to be `com.chardyb.doom.fixturegate`, to synchronously clear only the fixture consent preferences and assert persistence and empty state. It finishes existing consent Activities through the test lifecycle monitor so a checked checkbox cannot survive into the next test. It requires no visible control, scrolling, Activity launch or unlocked screen. Consent listeners run on the main thread before both saved accessibility settings are restored in nested `finally` blocks with read-back assertions (an empty service list is normalized to an absent setting). Setup refuses an already-enabled fixture service before mutating settings, since restoring that service would conflict with clearing its consent. The consent-revocation test itself still clicks the visible clear-consent button and verifies activation rejection.

Do not use `pm clear` or force-stop on the gate package from these tests: the runner targets the gate app's main process, which clearing app data would kill. No fixture process/manifest changes or production `:app` changes are needed for this cleanup.

The supplied CI evidence from run `34293895324`, head `c2c010df4e550aab6af3ad47a162b1a8a0703eff`, identifies all six device failures as cleanup-only: `AssertionError: Missing Disable and clear TEST FIXTURE consent` at `visible:82` → `click:84` → `tearDown:71`. Tests reached execution; `connectedDebugAndroidTest` failed on the `@After` UI lookup. Artifact validation also reported missing screenshots and device-suite failures; those requirements remain unchanged. “Emulator cleanup failure” was imprecise. This repair still requires independent review and CI at a new SHA; no passing run is claimed.

Parent MUST upload `fixture-evidence/` with `if: always()` and missing-file failure, using a fixture-specific artifact name that includes checkout SHA/run ID/attempt. This script does not modify existing CI workflows. Parent MUST keep collection within emulator lifetime. It preserves the failing Gradle/pipeline exit code; if tasks pass but evidence collection or validation fails, it returns failure. EXIT collection includes available APKs, unit/lint/device reports, crash-only logcat and window/accessibility/activity diagnostics, even after a failing task. Early failures cannot produce APKs or screenshots; metadata records missing evidence instead of inventing it.

Every diagnostic read and report-directory creation participates in the collection result. Public Actions annotations identify the last script phase (`prepare`, `build`, `install`, or `instrumentation`), task/collection results, validation-error count and allowlisted failing test identities. When present in device JUnit failure text, the fixed `setUp`, `tearDown`, `shell` and `measured` stack-frame names are also reported; a frame is a diagnostic clue, not a root-cause finding. Messages and raw stacks remain in the artifact. A collection-boundary annotation is emitted before Python validation, and a validator failure still fails the script. These annotations do not override the emulator action outcome or prove fixture acceptance.

Host-only regression check: `python3 scripts/test-fixture-evidence.py`. It executes only the embedded Python evidence validator against temporary synthetic PNG/JUnit/timing inputs inside the repository, then deletes them. Two additional source checks guard against UI-dependent teardown (including its helpers) and preserve the visible clear-consent click in the revocation journey. It never runs the shell script or Android, and its results are separate from the required six JVM and six device tests.

## Evidence contract

Tests assert foreground package and expected TEST FIXTURE label before each CI-only shell `screencap -p`. They save real PNGs to `/sdcard/Download/doom-fixture-evidence`, not app-owned external files that Gradle uninstalls. The script clears this directory before testing and pulls it before emulator teardown. Filenames are a fixed whitelist. No runtime app screenshot path exists.

Required screenshots:

| File | Assertion before capture |
| --- | --- |
| 01-feed-overlay | Fixture foreground; real accessibility overlay type and gate label |
| 02-dm-escape | Actual fixture DM marker; overlay absent |
| 03-unknown | Actual fixture Unknown; overlay absent |
| 04-completed-feed | Feed after measured gate completion; overlay absent |
| 05-new-session | DM-to-Feed creates a fresh real overlay |
| 06-back-unknown | Public Back transition removed active gate |
| 07-other-app | Settings removed gate, then known TEST FIXTURE controller foreground |
| 08-disabled-feed | Secure-setting service disable removed overlay over Feed |
| 09-disconnected | Controller reports actual disconnected callback state |
| 10-background-return | New gate after Home and waiting past stale deadline |
| 11-lock-return | New gate after screen off and waiting past stale deadline |
| 12-leave-home | Home/overlay absence asserted, then known controller captured |
| 13-no-consent | Activation without consent self-disabled; Feed has no overlay |

07 and 12 intentionally capture the known fixture controller AFTER the system navigation assertion. They are not screenshots of Settings or Home and do not alone prove those transitions. The device JUnit assertions provide that proof. No lock-screen screenshot is collected.

`timings.txt` records actual monotonic observed DM-escape, gate-from-launch, gate-visible observation, background wait and screen-off wait durations. DM escape has a two-second test budget, not a fabricated instantaneous result. The visible gate observation starts after detection and may be shorter than five seconds; the from-launch observation must be at least five seconds. Deterministic unit tests separately prove the exact policy deadline. Screenshot overhead and system scheduling remain included where applicable.

The script writes `fixture-evidence/<checkout-SHA>-<run-ID>-<attempt>/`, exact-SHA APK filenames, `manifest.json` (repository/run/workflow/SHA, task result, collection result, measured timings, validation errors and file checksums) and `SHA256SUMS`, including the manifest checksum. It validates required PNG signatures, chunks, CRCs and compressed image payload, both JUnit suites, and measured timing fields. It does not claim visual composition from PNG decoding. Parent MUST inspect the genuine images and exact-run JUnit/lint reports before acceptance.

## Tests and remaining limitations

Six standalone JVM tests exercise the actual `GatePolicy`: exact deadline/session credit, each non-Feed cancellation, stale replacement completion, navigation suppression, cancellation reset, and repeated-event deadline stability. Six device tests exercise actual cross-app windows and UI: DM escape, completion/new session, Unknown/other app, actual service disable, background/lock/Leave, and consent revocation/rejection. The supplied run reached device-test execution but failed in cleanup; the repaired source still requires a new-SHA CI run and evidence review.

Window event ordering and OEM accessibility behavior remain device risks. This fixture does not prove production classification, Instagram DM navigation, store-policy acceptance, real-device latency, accessibility compatibility with other services, or product UX polish. The required consented actual-Instagram acceptance matrix remains BLOCKED and distinct from this fixture matrix. Production remains observation-only. No live account or private evidence is permitted by this task. Parent owns independent review and all Android execution.
