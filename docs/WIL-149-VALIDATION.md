# WIL-149 Stage B diagnostic entry-gate validation

## Scope

This working tree started exactly at `014fa1006944e28b8225c81404c3da3495596377`. The feature remains unverified, default-off, and separately consented. It does not claim protection or enable a production rollout.

Android opens Instagram normally. On the first bounded Instagram sample in a foreground session:

- any verified Instagram foreground event shows a five-second accessibility overlay, regardless of sanitized classifier result;
- after `addView` and `overlayShown` successfully admit a production gate, the next production gate is suppressed for exactly 60,000 monotonic milliseconds in process memory; at 59,999 ms it remains suppressed and at 60,000 ms the next otherwise-eligible gate may start;
- Inbox, thread, composer, or any messaging evidence receives the same temporary five-second diagnostic pause; it does not bypass the gate.
- Unknown, mixed, truncated, missing-root, foreign-window, runtime-error, revocation, interruption, or disconnect state is still sanitized/fail-safe, but a verified Instagram foreground event can pause entry for up to five seconds.
- classification and gate timing remain separate;
- the five seconds begin only after `WindowManager.addView` succeeds;
- notification text, usernames, messages, descriptions, and Instagram content are never read or retained.

The overlay offers a primary **Skip to messages** action and secondary **Leave Instagram** action. The one user-consented routing exception is the explicit current-overlay Skip tap: Doom first confirms physical overlay detachment, then rechecks the callback ticket, both consents, connection, and Instagram foreground. It obtains the current Instagram root only after those checks and queries only the exact resource ID `com.instagram.android:id/direct_tab`. Exactly one match is required; if it is already selected, the route completes without action. Otherwise the match must be visible, enabled, and clickable before at most one `ACTION_CLICK`. Any zero/multiple match, missing or foreign root, stale state, exception, non-actionable node, or false click result fails closed with the overlay removed and session bypassed. Root and returned nodes are recycled safely, including duplicate matches. There is no deep link, browser, chooser, coordinate, descendant/message-row/text/content-description read, retry, delay, persistence, analytics, logging, or network behavior. Leave physically removes the overlay before invoking only `GLOBAL_ACTION_HOME`.

## WIL-182 watchdog repair

The first confirmed phone defect was an overlay disappearing about one second after Instagram opened without user action. The phone did not capture the root/package sequence, so this validation does not attribute the event with certainty. The repaired boundary keeps delivered foreign-package accessibility events authoritative and immediate. During the visible overlay episode, the watchdog treats a non-null foreign active-root package as confirmed foreign and fails open immediately. A null package or active-root inspection exception is unattributed uncertainty: it is retained for less than 150 monotonic milliseconds from the last confirmed-safe moment, then fails open. Instagram or a positively identified Doom return refreshes that safe moment. This bounded policy keeps a single transient sample from detaching the overlay; scheduling and physical removal timing remain device evidence questions. The five-second visible timer, cooldown, consent, collection scope, and actions are unchanged.

## Changed files

- `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt` — pure generation-ticket policy, classifier-independent Instagram trigger, monotonic visible-time deadline, stale-callback rejection, bounded duration, and process-local 60-second admission cooldown.
- `app/src/main/java/com/chardy/doom/OverlayForegroundWatchdog.kt` — pure monotonic policy distinguishing confirmed foreign roots from bounded unattributed-root uncertainty.
- `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt` — focused tests for default-off behavior, visible timing, DM/unknown diagnostic pauses, repeated samples, revocation, stale tickets, invalid time/duration, skip, exact cooldown boundaries, session resets, and rejected/stale admissions.
- `app/src/test/java/com/chardy/doom/OverlayForegroundWatchdogTest.kt` — deterministic transient-null, monotonic-boundary, foreign-root, and verified-Doom-return policy tests.
- `app/src/main/java/com/chardy/doom/OverlayRemovalPolicy.kt` and its unit test — fake removal-failure/retry policy; no action is released before confirmed detachment.
- `app/src/main/java/com/chardy/doom/EntryGateOverlayView.kt` — native Doom-styled, semantic overlay with live status/copy controls and explicit skip/leave callbacks.
- `app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt`, `EntryGateOverlayModel.kt`, `BreathingVisuals.kt`, `PixelBreathingView.kt` — pure lifecycle/presentation contracts and timer-free Canvas rendering.
- `app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt` — pure exact-match policy and Android adapter for the single user-consented `direct_tab` click.
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt` — real default-off `TYPE_ACCESSIBILITY_OVERLAY`, broad package-event session boundaries, Instagram-only traversal, monotonic clock seam, cooldown-before-ticket/root/report checks, 50 ms watchdog with bounded active-root uncertainty, countdown, remove-before-action ordering, and lifecycle cleanup.
- `app/src/main/java/com/chardy/doom/Observation.kt` — independent default-false persisted gate consent, compact live status, and explicit current-report copy boundary.
- `app/src/main/java/com/chardy/doom/MainActivity.kt` — separate opt-in, state display, and corrected disclosure.
- `app/src/main/res/xml/accessibility_service_config.xml` — removes the Instagram package filter so foreign package events can synchronously end a session; tree traversal remains Instagram-only.
- `app/src/main/res/values/strings.xml` — discloses broad package-event metadata, Instagram-only traversal, the optional overlay, and fail-open limits.
- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` — updates disclosures, verifies default-off independent consent, constructs the real overlay content, and covers current-report clipboard denial/replacement/revocation cases.
- `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt` — fake-platform runtime coverage for detach ordering, token races, safety vetoes, direct return, route failure, foreground suppression, HOME arbitration, retry exhaustion, and the watchdog policy through the service-owned boundary.
- `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt`, `DoomUiTest.kt`, `InstagramMessagesRouterTest.kt` — CI-bound six-state Doom-owned capture scenarios, layout configuration checks, and exact-ID routing/action/recycling coverage.
- `app/src/test/java/com/chardy/doom/InstagramMessagesRoutingPolicyTest.kt` — pure zero/one/multiple-match and actionability policy coverage.
- `scripts/test-structural-lifecycle.py` — source guards for the overlay boundary, remove-before-action ordering, watchdog cleanup, Instagram-only tree access, default-off policy, and cooldown ordering/clock seam.
- `scripts/test-entry-gate-host.py`, `scripts/test-overlay-evidence.py`, `scripts/overlay-evidence-manifest.py` — reproducible host runners and isolated supplementary evidence binding.
- `scripts/ci-device.sh` — pulls supplementary overlay artifacts separately from canonical signer input.
- `README.md` — honest current behavior, consent, privacy, and remaining evidence limits.

No manifest, dependency, network, backup, fixture implementation, workflow, or canonical signer contract change is present. BuildConfig generation is explicitly enabled in Gradle.

## Executed host verification

- `python3 -B scripts/test-entry-gate-host.py`: **72/72 passed**, including watchdog uncertainty, exact last-safe boundaries, rollback, reset, and foreign-root policy boundaries.
- `python3 -B scripts/test-structural-lifecycle.py`: **33/33 passed**.
- `python3 -B scripts/test-overlay-evidence.py`: **5/5 passed**.
- `python3 -B scripts/test-fixture-evidence.py`: **21/21 passed**.
- `python3 -B scripts/test_wil155_host.py`: **5/5 passed**.
- `python3 -B scripts/test-internal-signing.py`: **13/13 passed**.
- Python syntax and all source XML parsing: passed.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh`: passed.
- `git diff --check`: passed.
- Secret-pattern scan: no findings; no file exceeded 1 MiB.

These checks did not invoke Gradle, Android SDK, an emulator, adb, credentials, network, commit, push, or PR operations.

The implementation is uncommitted by policy, so no final candidate SHA is claimed here. Android compilation, lint, instrumentation, emulator, adb, exact-head CI/artifact readback, independent review, signing, and actual Instagram/phone acceptance remain unverified and controller-owned. Host checks do not establish Android runtime success; no screenshot or route success is claimed. Real-phone proof still must verify that the explicit Skip tap reaches the Instagram Messages tab across the approved lifecycle/version matrix, while recording only surface category, timing, versions, and pass/fail—not message/account content or raw accessibility trees.

## Review repairs

- Removal is not treated as successful after a `WindowManager` exception. The service uses `removeViewImmediate`, verifies `View.isAttachedToWindow`, retains the view and manager while attached, retries every 50 ms up to 20 attempts, and disables the service without releasing Home/completion/bypass actions if detachment cannot be confirmed.
- The watchdog no longer equates one unattributed active-root sample with a foreign foreground. Null or root-inspection uncertainty receives less than 150 monotonic milliseconds from the last confirmed-safe moment; a non-null foreign package and every authoritative foreign accessibility event still fail open immediately.
- Foreign-window and safety cleanup override pending Home or completion. `GLOBAL_ACTION_HOME`, gate completion, and bypass state changes occur only after confirmed physical detachment.
- A positively identified return to Doom overrides automatic timer completion so the gate session resets while the report remains available; explicit Skip/Leave and foreign-window safety cleanup still override report preservation.
- Android instrumentation now includes runtime fake-platform action-order tests and the real overlay factory/layout paths. The Intent, fake detach boundary, top-resumed checks, draw waits and configuration restoration are still unexecuted here; Android compilation, actual attachment/touch dispatch, active-root behavior, watchdog timing, TalkBack behavior and Instagram routing remain GitHub/phone evidence.
- The six supplementary names are CI-bound scenarios: the first five exercise Doom-owned overlay states (including system font-scale 2.0 and landscape), and the sixth is the scrollable MainActivity build-footer screen after overlay removal. They remain synthetic UI evidence and are not claimed as executed until exact-head CI uploads the manifest.
- WIL-179 cooldown is host-verified only: suppression is checked before new ticket/root/report work, and admission is recorded only after the real service's successful `addView`/`overlayShown` boundary. Android compilation, instrumentation timing, service recreation behavior, and actual Instagram navigation remain unverified.
- WIL-175 direct-click rereview repairs: [issue WIL-175](https://linear.app/wildhearts/issue/WIL-175) tracks lazy exact-match state reads, meaningful no-click/recycling assertions, and service-level root/route failure and revocation coverage. These remain host- and CI-test contracts; no Android execution is claimed here.

## Repair ledger

This uncommitted repair preserves the candidate's base SHA and addresses the independent Opus blockers B1–B5. Host checks below are the only executed verification for this repair; no Android execution or screenshot artifact was fabricated.

- B1: fixed the API-35 Intent assertion to use `selector` and added null categories/`FLAG_ACTIVITY_NEW_TASK` assertions; reviewed all new Android tests for API-35 Kotlin/API usage.
- B2: added the successful-path supplementary `adb pull` before manifest generation and a host ordering regression.
- B3: made the CI-bound six-state capture establish font scale/orientation, assert top-resumed Doom, wait for draw, and restore settings; the footer capture is isolated in a separate scroll/reset test.
- B4: replaced guard-only action coverage with fake-platform service runtime tests and added missing clipboard failure/stale/replacement cases.
- B5: changed text/actions to wrap-content with minimum heights, exposed visible status text to accessibility, added stateful contrast styling, applied top/bottom/side/cutout insets, preserved pixel layout during retries, and added font-scale/landscape/reachability tests.

WIL-175 direct-click rereview repairs are tracked at [WIL-175](https://linear.app/wildhearts/issue/WIL-175): B1 makes match counting precede lazy state reads and selected matches stop before actionability reads; B2 counts every no-click path and exact returned-node recycling; B3 adds configurable service root/route failure, revocation, and root-recycling coverage. These are implementation/test contracts, not Android runtime evidence.

Repair 2 for the independent rereview addressed N1–N4 in source/tests: all ActivityScenario reflection and fake-platform mutations are main-thread-owned with deterministic visibility; draw-listener removal is posted after the draw callback; font-scale 2.0 reachability covers 320×640 portrait and 640×320 landscape with immediate content-relative scrolling; and the canonical demo capture/CTA journey remains at the top while footer verification is separate. The duplicate Intent-flags assertion and redundant `allowClosing` branch were removed. These changes are not Android runtime results.

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
