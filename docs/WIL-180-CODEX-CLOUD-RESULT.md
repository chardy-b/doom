# WIL-180 Codex Cloud result

## Candidate identity and scope

Implementation base: `eeb844527e6d4bdf00a3d4778f7e0923c290e938` (WIL-184 base; no network refresh was performed for this repair). Changed files are `README.md`, `DoomAccessibilityService.kt`, `InstagramSessionTimer.kt`, `InstagramTimerOverlayView.kt`, `InstagramSessionTimerTest.kt`, `EntryGateServiceActionTest.kt`, `EntryGateOverlayUiTest.kt`, `scripts/test-session-timer-host.py`, `scripts/overlay-evidence-manifest.py`, `scripts/test-overlay-evidence.py`, `scripts/test-structural-lifecycle.py`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`, and this result.

## Architecture and lifecycle

The process-local `InstagramSessionTimer` owns only injectable-monotonic session accounting, formatting, collapse state, and second-boundary cadence. The accessibility service owns a distinct timer window epoch, view, UI listener, tick, closing state, terminal-reset flag, retry, and deferred gate handoff. A verified Instagram observation starts the clock even while the breathing window is attached. Timer installation requires exact Instagram package attribution immediately before `addView`. Every render/tap and an independent epoch-bound 50 ms watchdog recheck current authority; transient Doom-owned timer-root attribution uses the same non-refreshing 150 ms uncertainty grace as missing roots. Confirmed foreign roots, positively identified MainActivity returns, or expired uncertainty end the session and begin physical removal. Gate handoff suspends the bubble, waits for confirmed detachment, then attaches the breathing window; successful completion or Messages routing performs a new root check through timer attachment and resumes the same clock. Terminal paths synchronously reset accounting while ownership references remain until confirmed physical detach. Stale timer epochs cannot update or remove a newer window, attach failures retain ownership when physically attached, and unexpected detach enters deterministic ownership release. Removal exhaustion retains attached references, vetoes future attachment/handoff, resets the clock, and disables the service. Successful terminal tickets are retired on the first verified event after cooldown expiry without resetting the continuous timer.

The timer requires general consent, separate gate consent, and connection for start/attach/render/tick authority. `disableObservation()` begins timer cleanup before service disable. WIL-184 cooldown code and signer eligibility were not changed.

## Prior delegated Codex Cloud task evidence

The following TDD, host and Android results were supplied by the prior delegated task and are retained as historical evidence. They were not rerun or independently validated by the repair implementer and do not validate the repaired worktree.

### Delegated TDD RED and GREEN

A focused timer test was added before production classes. The first command was `./gradlew --no-daemon :app:testDebugUnitTest --tests com.chardy.doom.InstagramSessionTimerTest`; it exited nonzero before Kotlin compilation because `InstagramSessionTimer`/its model did not exist (the daemon/client output was unfortunately truncated before the compiler diagnostic, so this is not claimed as a complete log artifact). After implementation, the full JVM task executed 93 tests with zero failures/errors/skips. A subsequent genuine test RED found an incorrect test boundary setup at `InstagramSessionTimerTest.kt:15` (3,599,001 ms instead of 3,600,000 ms); correcting the test input produced GREEN.

### Delegated host checks

- Timer host guard: 4 tests passed.
- Entry-gate host Kotlin suite: 85 tests passed.
- Structural lifecycle: 38 tests passed.
- Overlay evidence: 5 tests passed.
- Fixture evidence: 21 tests passed.
- WIL-155 host: 5 tests passed.
- Internal signing: 14 tests passed.
- CI isolation: 5 tests passed.
- Android JUnit validator: 8 tests passed.
- Shell syntax (`ci-device.sh`, `ci-fixture.sh`, `sign-internal-apk.sh`) and `git diff --check` passed.
- No XML file changed, so no changed XML required parsing.

### Delegated Android no-emulator preflight

`python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` exited 0. Signing source tests: 14. JVM tests: 93, zero failures/errors/skips. Lint: zero errors and 11 warnings. APK: `app/build/outputs/apk/debug/app-debug.apk`, 8,826,831 bytes, SHA-256 `2c95c5000ea501c1dd7df092ddf6f616b684e1d9af4f774ff8ee5ba8c8a2180a`.

`./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --stacktrace` exited 0; every affected instrumentation source compiled. Deprecation/type-mismatch warnings were emitted, with no compile error.

## Evidence not run and remaining gates

No emulator, `connectedAndroidTest`, adb, device/phone, real Instagram, TalkBack, screenshot capture, GitHub Actions, exact-head artifact readback, protected signing, release, or merge was run. The four newly declared timer screenshots are CI-only synthetic Doom-owned scenarios; no PNG is claimed here. Canonical exact-four signer eligibility remains unchanged, and supplemental evidence now requires ten overlay files. Real Instagram foreground timing, OEM window behavior, visual placement across real cutouts/insets, accessibility-service teardown timing, and exact-head canonical/supplemental evidence remain independent blockers/gates.

## First local repair verification (historical)

Only the approved host checks were run for this repair; no network, Gradle, Android SDK, emulator, adb, signing, commit, push, or PR action was performed. The new spoken-label JVM regression first failed on `1 seconds`. Service behavior and layout tests were written before their repairs, but were not executed or Android-compiled locally. The timer host runner now executes the real pure Kotlin timer tests in addition to source contracts.

The current candidate needs fresh delegated Android compilation/preflight and canonical CI instrumentation. Prior delegated APK size, hash and test counts above are not results for this repaired worktree. Expanded, collapsed, 2.0 font-scale, and landscape timer screenshots remain supplemental synthetic evidence only; the canonical exact-four contract is unchanged.

Local host results: entry-gate Kotlin/JUnit 85 passed; timer Kotlin/JUnit 5 passed plus 4 source contracts; structural lifecycle 38 passed; overlay evidence 5 passed; fixture evidence 21 passed; WIL-155 5 passed; internal-signing host tests 14 passed. Shell syntax and diff whitespace checks passed. No XML changed. The structural revocation guard initially failed because it excluded a running timer; it now covers both window types. Internal-signing host tests did not sign an APK.

## Second local repair cycle (historical)

This cycle changes only `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`, `app/src/main/java/com/chardy/doom/InstagramTimerOverlayView.kt`, `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`, `scripts/test-session-timer-host.py`, and this result file. All previously staged WIL-180 work is retained. The implementation base remains `eeb844527e6d4bdf00a3d4778f7e0923c290e938`; no new commit or exact candidate SHA is claimed. The supplied repair findings define this cycle's scope; Linear was not refreshed because network use was prohibited.

The service now owns a separately session-epoch-bound, identity-checked one-shot boundary callback. A preserved non-Instagram event schedules it when no recurring timer-window watchdog exists, including during a full breathing gate or handoff. Its deadline is the existing last-safe 150 ms boundary, and it does not reschedule itself. A synchronous Instagram root may preserve the session briefly; a synchronous confirmed foreign root ends it immediately. At the boundary only exact Instagram preserves the session; missing, unattributed, foreign, or vetoed authority ends accounting and requests gate reset. Exact safe observations, timer-watchdog takeover, session replacement/end, and safety/lifecycle cleanup cancel the callback; checks both before and after root acquisition reject replacement races. Uncertain timer attachment also schedules bounded resolution. The attached bubble retains its independent 50 ms watchdog; no perpetual no-window timer poll was introduced.

After physical full-overlay detachment, an unsuccessful Messages result or router exception retires its own gate episode only with current detached authority and a fresh exact Instagram root. It preserves the running continuous clock and resumes the bubble without recording cooldown. The next verified Instagram event can immediately hand the bubble off to a new breathing gate. Lost terminal authority ends the affected timer session. A verified later event retires only a successfully completed terminal episode after the unchanged cooldown expires. Safety, admission, installation, HOME, uncertainty, and other cancel/bypass outcomes retain their bypassed ticket until a confirmed foreign transition or MainActivity return resets the session; they cannot immediately regate. Unexpected timer-window detach ends accounting; safety cleanup ends accounting and cancels boundary work before physical removal, while gate actions still wait for confirmed detachment. WIL-184 still arms cooldown only on successful completion or exact-route success.

Timer layout params are now retained with physical window ownership and cleared only after detachment. `onConfigurationChanged` and delivered window insets recompute density, system-bar, cutout and layout-direction-aware TOP|END offsets for the current window. A private production-default `updateViewLayout` seam supports instrumentation; updates recheck window/session epochs, connection, consents, foreground authority and breathing-overlay absence. Unchanged params avoid a layout/inset feedback loop. Update exceptions end accounting and initiate physical timer removal. The 48 dp target is refreshed for density changes. API 30 metrics/types and API 28 cutouts remain version-guarded; API 26–29 uses delivered legacy insets.

Focused service and source regressions were written before production changes. The first approved timer-host run passed five pure Kotlin tests and failed the three new source contracts (one failure, two missing-method errors). After repair, the timer-host run passed five Kotlin/JUnit tests and all seven source contracts. Added instrumentation covers no-bubble stale/null events with no subsequent event, exact/foreign/unattributed boundary roots, callback replacement races, connection veto, failed/throwing Messages recovery and immediate regating, configuration/density updates without a second view, update failure, and stale window/root-read epochs. Existing failed-route expectations were updated to episode retirement. These instrumentation tests were inspected but **not compiled or executed** in this cycle.

Approved host verification: entry-gate Kotlin/JUnit **85 passed**; timer Kotlin/JUnit **5 passed** plus **7 source contracts**; structural lifecycle **38 passed**; overlay evidence **5 passed**; fixture evidence **21 passed**; WIL-155 **5 passed**; internal-signing source tests **14 passed**. The approved three-script shell syntax check and staged whitespace check passed. No XML, permissions, dependencies, routing selectors, signing code, or evidence manifests changed in this cycle. Canonical signing remains exact-four; supplemental overlay evidence remains ten files. Host signing tests did not sign an APK.

No network, Gradle, Android SDK, emulator, adb, commit, push, signing or release was used. Exact-diff Android compilation (including instrumentation), full Android unit/lint/assembly preflight, API 35 canonical instrumentation/artifact verification and supplemental evidence remain unverified. Real Instagram, rotation/cutout/OEM behavior, actual window-update failures and TalkBack still require the separate authorized device gates. The historical Android results and APK digest above do not validate this final staged repair.

## Final Opus review repair

A fresh Claude Opus exact-diff review requested one blocking correction: the event path treated every `BYPASSED`, `GRANTED`, or generation-mismatched ticket as retirable, so safety cleanup, install failure, uncertainty expiry, or a failed HOME action could show a new breathing overlay on the next Instagram event. The fix narrows retirement to `ticket != null && terminalGateSucceeded`; failed Messages routing remains the only separate immediate-retirement path.

TDD evidence: the new host source contract failed against the broad retirement condition, then passed after the production fix. Instrumentation regressions now cover install failure, watchdog uncertainty expiry, and failed HOME followed immediately by another Instagram event; all require the same bypassed episode and no second install until a confirmed foreign transition. These Android tests remain source-inspected but uncompiled until the exact-head remote preflight/CI gate.

Fresh local results after this repair: entry-gate Kotlin/JUnit **85 passed**; timer Kotlin/JUnit **5 passed** plus **8 source contracts**; structural lifecycle **38 passed**; overlay evidence **5 passed**; fixture evidence **21 passed**; WIL-155 **5 passed**; internal-signing source tests **14 passed**. The approved shell syntax check and `git diff --cached --check` passed on the complete repaired diff before commit.
