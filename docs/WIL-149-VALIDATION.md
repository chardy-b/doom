# WIL-149 Stage B diagnostic entry-gate validation

## Scope

This working tree is based exactly on merged `main` `496e0387fcbc46bfeddc524e5a5643f557e039d8`. The feature remains unverified, default-off, and separately consented. It does not claim protection or enable a production rollout.

Android opens Instagram normally. On the first bounded Instagram sample in a foreground session:

- strong Feed, Reels, or Stories evidence may show a five-second accessibility overlay;
- Inbox, thread, composer, or any messaging evidence bypasses the whole session;
- unknown, mixed, truncated, missing-root, foreign-window, runtime-error, revocation, interruption, or disconnect state fails open;
- classification and gate timing remain separate;
- the five seconds begin only after `WindowManager.addView` succeeds;
- notification text, usernames, messages, descriptions, and Instagram content are never read or retained.

The overlay offers exactly two actions. **Dismiss for messages** removes the overlay and performs no Instagram action. **Leave Instagram** removes the overlay before invoking only `GLOBAL_ACTION_HOME`. The implementation never clicks an Instagram node, dispatches gestures, guesses a deep link, or launches Instagram.

## Changed files

- `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt` — pure generation-ticket policy, monotonic visible-time deadline, messaging/unknown fail-open behavior, stale-callback rejection, and bounded duration.
- `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt` — focused tests for default-off behavior, visible timing, messaging precedence before/during a gate, unknown bypass, repeated samples, revocation, stale tickets, invalid time/duration, and dismissal.
- `app/src/main/java/com/chardy/doom/OverlayRemovalPolicy.kt` and its unit test — fake removal-failure/retry policy; no action is released before confirmed detachment.
- `app/src/main/java/com/chardy/doom/EntryGateOverlayView.kt` — testable overlay content with explicit dismiss and leave callbacks.
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt` — real default-off `TYPE_ACCESSIBILITY_OVERLAY`, broad package-event session boundaries, Instagram-only traversal, 50 ms watchdog, countdown, remove-before-action ordering, and lifecycle cleanup.
- `app/src/main/java/com/chardy/doom/Observation.kt` — independent default-false persisted gate consent and process-local gate state.
- `app/src/main/java/com/chardy/doom/MainActivity.kt` — separate opt-in, state display, and corrected disclosure.
- `app/src/main/res/xml/accessibility_service_config.xml` — removes the Instagram package filter so foreign package events can synchronously end a session; tree traversal remains Instagram-only.
- `app/src/main/res/values/strings.xml` — discloses broad package-event metadata, Instagram-only traversal, the optional overlay, and fail-open limits.
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` — updates disclosures, verifies default-off independent consent, constructs the real overlay content, and executes both button callbacks.
- `scripts/test-structural-lifecycle.py` — source guards for the overlay boundary, remove-before-action ordering, watchdog cleanup, Instagram-only tree access, and default-off policy.
- `scripts/test-entry-gate-host.py` — reproducible cached Kotlin/JUnit runner that requires no Android SDK.
- `README.md` — honest current behavior, consent, privacy, and remaining evidence limits.

No manifest, Gradle/dependency, network, backup, fixture implementation, workflow, or CI script change is present.

## Executed host verification

- `python3 scripts/test-entry-gate-host.py`: **45/45 passed**, including gate and fake removal-policy tests.
- `scripts/test-structural-lifecycle.py`: **18/18 passed**.
- `scripts/test-fixture-evidence.py`: **21/21 passed**.
- `scripts/test_wil155_host.py`: **5/5 passed**.
- Python syntax and all source XML parsing: passed.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh`: passed.
- `git diff --check`: passed.
- Secret-pattern scan: no findings; no file exceeded 1 MiB.

These checks did not invoke Gradle, Android SDK, an emulator, adb, credentials, network, commit, push, or PR operations.

## Review repairs

- Removal is not treated as successful after a `WindowManager` exception. The service uses `removeViewImmediate`, verifies `View.isAttachedToWindow`, retains the view and manager while attached, retries every 50 ms up to 20 attempts, and disables the service without releasing Home/completion/bypass actions if detachment cannot be confirmed.
- Foreign-window and safety cleanup override pending Home or completion. `GLOBAL_ACTION_HOME`, gate completion, and bypass state changes occur only after confirmed physical detachment.
- Android instrumentation constructs the real overlay content and executes both button callbacks. Exact accessibility-overlay attachment, touch dispatch, active-root behavior, watchdog cleanup, and timing remain required GitHub emulator and actual-device evidence.

## Required CI evidence

Exact-head GitHub CI must compile, lint, assemble, and instrument the Android app and preserve the existing baseline, fixture, APK-digest, screenshot, and source-SHA evidence contracts. Existing Doom-owned screenshots do not prove Instagram behavior. Any CI-only test added for Stage B must use synthetic or repository-owned screens and must not capture private Instagram content.

CI run `34660087076` on initial PR head `6837e923053143fdf596fadc5adbe8d191ff51a1` compiled and passed the Android baseline and cross-app fixture jobs. Its diagnostic emulator job ran 14 tests and failed one stale persistence assertion that still expected one consent key after Stage B deliberately added the second gate-consent boolean. The assertion now expects exactly those two booleans. A fresh exact-head run is required; the failed run is not acceptance evidence.

## Required actual-device gate

Live rollout remains disabled until a privacy-safe actual-Instagram matrix passes **20 repetitions per high-risk path**, including:

- ordinary icon/Recents entry into a confirmed scrolling surface;
- pre-app breathing followed by Instagram entry;
- Inbox, existing thread, composer, and notification-opened DM entry;
- Feed/Reels/Stories to messaging transitions;
- Back, Home, Recents, lock, rotation, call/system interruption, service restart, and consent revocation;
- mixed, truncated, unknown, missing-root, and version-drift cases.

Pass criteria are zero messaging false blocks or redirects, deterministic fail-open cleanup, measured overlay removal within 100 ms of a delivered disqualifying event, actual obstruction below 250 ms on every bypass path, and a documented first-frame exposure/measurement limit. The matrix records only timing, surface category, app/device versions, and pass/fail—never notification text, usernames, messages, screenshots of private content, or raw trees.
