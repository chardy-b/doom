# Doom WIL-184 Terminal-Only Cooldown Implementation Plan

> For Hermes: use the subagent-driven-development skill if implementation is subsequently authorized. This assignment authorizes inspection and this plan only; it does not authorize implementation, tests, commits, pushes, PRs, CI dispatch, signing, releases, or external-system updates.

**Goal:** Start the process-memory, monotonic 60-second Instagram cooldown only on successful five-second completion or a currently authorized, physically detached Messages action whose exact Direct route returns `CLICKED` or `ALREADY_SELECTED`.

**Architecture:** Keep display timing, terminal credit, physical removal, and routing authority separate. Make successful completion and successful Messages-result consumption atomic policy operations; retain a single-use, current-ticket Messages attempt while the session is bypassed before routing. The service supplies physical-detachment and current-episode authority, consumes the actual routing result, and never exposes a generic cooldown-arm operation.

**Tech stack:** Existing Kotlin/JUnit, Android AccessibilityService, AndroidX instrumentation, Python host contracts, Java 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, SDK 35. No dependency, toolchain, permission, persistence, network, or workflow changes.

---

## 1. Scope, provenance, and prerequisite

- Workspace: `/mnt/HC_Volume_106820083/worktrees/doom-wil184-terminal-cooldown`.
- Branch inspected: `ryli721/wil-184-terminal-only-cooldown`.
- Required base and inspected HEAD: `9a956b1eb784684f4c090a7cdcf73322567fd0a5`.
- Initial `git status --short` was empty. Root `AGENTS.md` is the only discovered repository `AGENTS.md`.
- The sole planning write is `docs/plans/2026-09-13-wil-184-terminal-only-cooldown.md`.
- Active issue reference: `https://linear.app/wildhearts/issue/WIL-184`. Live acceptance criteria, comments, and dependencies were not retrieved: the Linear skill reports no `LINEAR_API_KEY`, and no local WIL-184 ticket copy was found. No credentials were requested or read. The user's explicit behavior contract is the planning authority; before implementation, an authorized controller must read the active issue and reconcile dependencies, especially any WIL-182 work not present at this exact base. Do not infer that old plans constitute current ticket approval.
- The base includes WIL-181 canonical/supplemental isolation and the currently checked-in foreign-event root revalidation. Do not undo either or implement historical WIL-182 proposals wholesale.

Read for this plan: root `AGENTS.md`, `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`; the complete production gate and service; all of `InstagramEntryGateTest.kt` (the discovered cooldown unit-test file); complete service action and structural diagnostic instrumentation; exact Messages types, router, pure routing tests and Android routing tests; callback/removal policies and their unit tests; relevant source guards, host runner, CI scripts, JUnit partition validator, all three workflows, pinned app/root build configuration, and current disclosure text. Line references below refer to the inspected base, not the eventual implementation.

### Non-negotiable behavior

1. `addView`, `overlayShown`, display admission, rendering, and repeated accessibility events do not arm cooldown.
2. Five visible seconds still begin only after successful installation and accepted `overlayShown`. Early completion at 4,999 ms fails; completion at 5,000 ms may succeed only after physical detach and current authority checks.
3. Successful completion atomically changes the policy to `GRANTED` and records the terminal monotonic time.
4. An explicit current-overlay Messages tap first closes callbacks and physically detaches. Only after current token, ticket/generation, both consents, service connection and current Instagram-root checks may the exact route run. Only `MessagesRouteResult.CLICKED` and `.ALREADY_SELECTED`, with authority still current when the result is consumed, can arm cooldown.
5. Back/app switch/reset, Leave/Home, unsuccessful installation/admission, safety cleanup, consent revocation, Doom return, stale callback/ticket/action, missing/foreign/unattributed roots, exceptions, and `FAILED`/missing/ambiguous routing never create or refresh cooldown.
6. A cooldown already earned by an earlier successful terminal action is not erased or refreshed by later resets, Home, consent changes or cleanup in the same service instance. “Does not arm” is not “must clear a previously earned cooldown.” A newly constructed service/policy starts unarmed, as at the base; no stronger service-recreation or OS process-kill guarantee is implied.
7. Expiration only makes a new, otherwise-eligible foreground session admissible. It does not automatically redraw a gate inside the same `GRANTED`/`BYPASSED` session.
8. Preserve fail-open overlay removal, one-shot routing, exactly-once root release attempts, report privacy, separate default-off gate consent, and no persistence/network. `FAILED` still leaves the current session bypassed rather than immediately regating.

## 2. Root cause and exact caller inventory

### Current coupling to remove

- `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt:10-39`: `InstagramGateCooldown` records `admittedAtMs` and `lastAdmittedGeneration`; `admit` reads the clock separately from visible timing.
- `InstagramEntryGate.kt:85-97`: `overlayShown` starts visible time, then `admitForDisplay` separately arms cooldown while `GATING`.
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt:405-425`: successful installation calls `overlayShown` followed by `admitForDisplay`. This is why aborted displays currently suppress later entry.
- `DoomAccessibilityService.kt:680-686`: `COMPLETE` grants through `complete`, currently without terminal-time cooldown accounting, and looks up the mutable service ticket instead of explicitly tying completion to the detached token's ticket.
- `DoomAccessibilityService.kt:621-665`: Messages validates authority, bypasses before routing, but ignores `overlayPlatform.routeMessages(root)`'s result. Therefore a new unconditional arm in this branch would wrongly credit `FAILED`.
- `DoomAccessibilityService.kt:229-250`: cooldown suppression precedes ticket creation, root acquisition, and report collection. Keep that ordering for an actually earned cooldown.

### Every production gate caller and intended treatment

All paths below are in `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt` unless a full path is given.

| Caller / base location | Current use | Planned treatment |
| --- | --- | --- |
| service construction, 54-59 | Captures monotonic function in the gate's cooldown | Keep production `SystemClock.elapsedRealtime()` and existing service-instance lifetime; never use wall clock |
| `onAccessibilityEvent`, 133-136 | Own MainActivity return without overlay: `leaveInstagram`, clear ticket | No arm; preserve earned cooldown and hidden report |
| `onAccessibilityEvent`, 232, 243 | `cooldownActive`, `beginInstagramSessionIfEligible` | Keep early suppression/no new ticket/root/report during earned cooldown |
| `onAccessibilityEvent`, 295-307 | `observeInstagram`, install/render or request bypass | No arm; repeated events keep the same visible deadline |
| `installOverlay`, 412 | `overlayShown(shownAt, activeTicket)` | Keep accepted display/visible-clock boundary |
| `installOverlay`, 417-424 | `admitForDisplay` and rejection branch | Remove this cooldown operation and its now-obsolete second admission branch; preserve `overlayShown` rejection cleanup |
| `confirmOverlayRemoved`, BYPASS, 616-620 | `cancel` | No arm, invalidate pending Messages attempt, clear report |
| same, NAVIGATE_MESSAGES, 629/636/649/660 | Cancellation on failed authority/root/route exception | No arm; do not cancel a replacement ticket when rejecting an old continuation |
| same, NAVIGATE_MESSAGES, 648 | `bypass(routeTicket)` before one route | Replace with the narrow `beginMessagesRoute(routeTicket)` transition; generic bypass must not authorize success credit |
| same, NAVIGATE_MESSAGES, 658 | Ignored route result | Consume exact result using `finishMessagesRoute` only after current detached authority recheck |
| same, PRESERVE_REPORT, 667-673 | `leaveInstagram`, clear ticket, preserve report | No arm; clears pending attempt but not earned cooldown |
| same, RESET_OUTSIDE, 674-679 | `leaveInstagram`, clear ticket/report | No arm; same cooldown preservation |
| same, COMPLETE, 680-686 | `complete(now, mutable ticket)`, else `cancel` | Use detached-token ticket and atomic terminal completion after authority checks |
| same, HOME, 687-693 | Generic `bypass`, then `performHome` | Keep removal-before-bypass-before-Home; never call either success API |
| `renderOverlay`, 727-735 | `remainingMs` | Pure presentation, never arm/refresh |

Indirect initiators to audit and test, not new arming sites:

- `installOverlay` listeners at 381-391: `NAVIGATE_MESSAGES`, `HOME`, and report copy; timer Runnable at 428-436: `COMPLETE`; watchdog Runnable/tick at 437-487: safety cleanup.
- `requestOverlayRemoval`, `requestSafetyCleanup`, `attemptOverlayRemoval`, `confirmOverlayRemoved` at 490-696, including all retry and exhaustion branches. The single winning removal action determines which terminal API, if any, can run.
- Own-app return, non-Instagram root decisions, denied event, missing/foreign/throwing Instagram root, failed collect/install handling in `onAccessibilityEvent`.
- `cancelAndBypass`, `failOpen`, `failOpenCauseAlreadyRecorded`, `resetOutside` at 698-725.
- `onServiceConnected`, `onInterrupt`, `onUnbind`, `onDestroy`, `disconnect` at 95-107 and 861-886.
- Companion `cancelEntryGate` and `disableObservation` at 39-50.
- `app/src/main/java/com/chardy/doom/Observation.kt:56-74`: gate-consent revocation calls `cancelEntryGate`; report-consent revocation calls `disableObservation`. Keep these wired to the actual service in instrumentation.
- `Observation.clear`, reveal/copy actions and removal-trace controls are not cooldown authorities. Do not couple them to terminal success or add persistence to `Observation`.

### Every direct old admission/skip test caller

`app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt`:

- `cooldownStartsOnlyAfterDisplayAdmissionAndHasExactBoundary` — replace with display-does-not-arm and terminal-time boundary cases.
- `sessionResetsAndExplicitActionsCannotBypassCooldownOrAllocateTicket` — earn cooldown with an actual successful terminal policy operation first, then test non-erasure/non-refresh.
- `rejectedAndStaleDisplayAttemptsNeverStartCooldown` — preserve rejected/stale display assertions without the removed admission API, add stale terminal rejection.
- `skipToMessagesRequiresCurrentVisibleGate` and old skip calls in the cooldown tests — replace test-only `skipToMessages` alias with `beginMessagesRoute` plus explicit result consumption. A generic skip/bypass is not successful routing.
- All existing `complete` callers in `instagramDetectionNeedsFiveVisibleSecondsAfterOverlayIsShown`, `repeatedInstagramSamplesKeepDiagnosticGateVisible`, `repeatedInstagramSamplesKeepSameVisibleDeadline`, `leavingResetsAndStaleCompletionCannotGrant`, and `optOutOrCancellationInvalidatesTicketIdempotently` retain their deadline/stale assertions and gain unarmed/armed assertions as appropriate. Use a shared injected fake clock for any suppression assertion.

`app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`:

- Direct `admitForDisplay` calls at 546, 648, 663, 706 belong respectively to `actualAccessibilityEventPathsAreIdenticalWithTraceArmedOrUnarmed`, `actualAccessibilityCooldownSuppressesBeforeRootAndCollection`, `otherStateOverInstagramRootKeepsTheCurrentAdmittedGate`, and `admittedInstagramRootEventHasArmedAndUnarmedEquivalentBehavior`.
- Remove manual admission from the three visible-gate/event/watchdog tests; their display must be unarmed. Trace “armed” means the optional trace, not cooldown. Preserve actual event/root/watchdog assertions and add inactive-cooldown assertions.
- Rewrite `actualAccessibilityCooldownSuppressesBeforeRootAndCollection` to earn cooldown through the actual service terminal path, then reset/reenter. It must not pretend that an attached visible gate already earned cooldown.
- Rewrite `serviceCooldownSurvivesDetachAndReentryUntilExactBoundary` (423-453) from HOME-earned admission cooldown to successful completion-earned cooldown. Keep this method identity because `scripts/test-ci-isolation.py:39-43` requires it; add distinct HOME non-arming coverage.
- `failedServiceInstallDoesNotArmCooldown` and `revokedAdmissionDoesNotArmCooldown`: the repaired successful display must now still be unarmed. Preserve connection rejection inside the installer, not nested consent cleanup in that installer.
- `recreatedServiceStartsWithoutCooldown`: the first service must earn cooldown through completion; the second merely displays and remains unarmed.
- `freshService` (1274-1315) already injects one mutable clock into both `service.monotonicClock` and `gate.cooldown.monotonicNowMs`. Keep this two-reference injection unless production wiring is deliberately changed for a proven need. Changing only the service field does not update the captured cooldown function.
- `fixture` (1330-1369) fabricates policy/view state and calls `beginInstagramSession`, `observeInstagram`, `overlayShown`; leave it useful for narrow negative/race tests, but never count it as proof of real install-to-terminal arming. No fixture may write a cooldown timestamp or call a low-level arm helper.

`InstagramGateCooldown` has no separate discovered unit-test class and no caller outside `InstagramEntryGate`. `skipToMessages` has no production caller. The exact routing-result type is used by the Android router, `OverlayPlatform`, production adapter, and the service test's `FakePlatform`/fixtures. No fixture-module or Doom demo policy caller should be migrated to production cooldown APIs.

## 3. Atomic policy contract

### 3.1 Proposed API surface

In `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt`:

- Keep `overlayShown(nowMs: Long, ticket: GateTicket): Boolean` as display validation plus one-time visible-clock initialization only.
- Remove `admitForDisplay(ticket)` entirely; do not keep a deprecated arming alias.
- Keep `complete(nowMs: Long, ticket: GateTicket): Boolean`, but make its success atomic: validate current generation, enabled predicate, `GATING`, accepted shown time, nonnegative/non-rollback time and five-second deadline; record cooldown with that same `nowMs`; then clear visible/pending state and set `GRANTED`. If any condition or cooldown recording rejects, return false without granting or changing the cooldown. There must be no observable `GRANTED` without the corresponding cooldown and no cooldown on a rejected completion.
- Add `internal fun beginMessagesRoute(ticket: GateTicket): Boolean`. Require enabled/current generation, `GATING`, and an accepted visible-start time. Atomically consume this gate's routing opportunity, retain a bounded pending attempt (ticket plus shown timestamp), clear visible timing, and set `BYPASSED`. This method never arms cooldown. It authorizes one synchronous result-consumption attempt, not another click or retry.
- Add `internal fun finishMessagesRoute(nowMs: Long, ticket: GateTicket, result: MessagesRouteResult): Boolean`. Accept only the exact current pending attempt in `BYPASSED`; consume that attempt once. `FAILED`, disabled authority, invalid time, rollback before shown time, or cooldown-record rejection cannot arm. Only `CLICKED`/`ALREADY_SELECTED` may record terminal time. Return true exactly when cooldown was recorded; state remains `BYPASSED`. An absent/stale attempt returns false and must not mutate a newer attempt/session. A failed current attempt is consumed so a later invented success cannot resurrect it.
- Remove the existing test-only `skipToMessages(ticket)` alias. Keep `bypass(ticket)` exclusively non-crediting. Generic bypass, `cancel`, `leaveInstagram`, and new-session initialization clear any pending Messages attempt. Clear an attempt even when an idempotent generic bypass sees an already-bypassed current session. Stale-ticket bypass must not clear a newer attempt.
- Keep `beginInstagramSessionIfEligible` and `cooldownActive`. Neither may refresh terminal time. Preserve suppressed admission before generation increment.

Move the unchanged declaration `internal enum class MessagesRouteResult { FAILED, ALREADY_SELECTED, CLICKED }` from `app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt:38` into `InstagramEntryGate.kt`, in the same package. This is a declaration-only move, not a new result taxonomy or router behavior. It keeps the gate Android-free and available to `scripts/test-entry-gate-host.py`, whose explicit source list already includes `InstagramEntryGate.kt` but not the Android-dependent router. Do not import `AccessibilityNodeInfo` into the policy or replace the result with an unguarded Boolean. No new source file/host-runner dependency is needed.

### 3.2 Cooldown internals and atomicity

- Rename private admission-oriented storage to `terminalAtMs` / `lastTerminalGeneration` and replace low-level `admit` with `recordTerminal(ticket, nowMs)` used only inside the two successful terminal operations. Pass the validated terminal timestamp explicitly; do not read a second clock inside the commit.
- Keep the duration exactly `INSTAGRAM_ENTRY_COOLDOWN_MS = 60_000L` and elapsed subtraction (`nowMs - terminalAt < durationMs`), avoiding expiry-time addition overflow.
- Preserve conservative suppression on negative or backward clock samples while an earned cooldown exists. Reject negative/rollback terminal timestamps without credit. Never substitute `System.currentTimeMillis` or timers/animation progress for the monotonic clock.
- Preserve generation-based duplicate/stale rejection even after cooldown expiry. Repeated completion or result consumption must not reset the anchor, including at/after 60,000 ms.
- The policy owns the only cooldown mutator; the service has no `startCooldown`, `armCooldown`, or `recordTerminal` call. Both terminal APIs perform validation, cooldown mutation and final state changes synchronously on the service main thread, with no callbacks or suspension between those steps. “Atomic” does not require adding cross-thread locks or coroutine infrastructure.
- Route preparation is intentionally distinct from the atomic success commit: `BYPASSED` before external routing preserves fail-open/no-regating behavior while a single-use pending attempt prevents arbitrary bypasses from earning credit. Do not implement `bypass(); startCooldown()` or `complete(); startCooldown()` at the service layer.

## 4. Service ordering and detached-token authority

### 4.1 Installation, events, and visible time

Remove only the `admitForDisplay` stage from `installOverlay`. Keep `overlayShown` rejection cleanup, `SHOWN` trace, `foregroundWatchdog.reset(shownAt)`, rendering, state publication, and one completion/watchdog chain. `SHOWN` is display evidence, not terminal credit.

Removing display cooldown makes Instagram events during `GATING` reach the existing root/collection path rather than the earned-cooldown return. Handle this deliberately:

- Keep the existing consent preflight and own/foreign-event root-revalidation ordering.
- For current visible Instagram events, preserve the existing bounded Instagram-only collector and current-ticket `observeInstagram`/render path; repeated samples must neither create another overlay nor move the five-second deadline. Do not use a fake cooldown to debounce these events.
- Missing/foreign/throwing roots and collection failures while `GATING` retain existing fail-open cleanup without credit. Test these now-reachable cases against a genuinely installed gate.
- While a view is closing/removal-retrying, do not restart installation/timers or a Messages attempt. If a narrow closing-event guard is needed, limit it to closing authority after consent/foreign safety handling; it must not suppress those safety vetoes or introduce a new collection capability. Do not broaden the watchdog grace, event subscriptions, or metadata scope.
- After a genuine terminal success, keep cooldown suppression before ticket allocation, root access and report collection. After failed routing or HOME, the bypassed current ticket still prevents same-session regating; a real outside-session reset permits immediate new entry when no previous cooldown exists.

### 4.2 Keep detached epoch authority until the continuation ends

The base clears `overlayToken` and `OverlayCallbackGuard.current` at physical detach. `ownsDetachedEpisode` is a one-time Boolean, not a sufficient freshness check if a synthetic root/route hook opens a replacement token with the same ticket generation.

Make a small, private-policy change in `app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt`:

- Add `acceptsDetached(token): Boolean` and `consumeDetached(token)` for the just-detached continuation.
- On accepted `detached(token)`, retain the token identity while marking it detached/closing; visible/removal authority remain false. `acceptsDetached` requires exact token/epoch identity and detached state.
- A new `open`, consumption, or safety invalidation of a detached continuation invalidates that identity. `invalidateVisible` continues to retain removal authority while a view is attached/closing; when already detached, it also revokes detached-continuation authority.
- Stale detach/consume cannot affect a replacement epoch. No second route becomes possible through this guard: the removal action is consumed once, the gate owns the single-use attempt, and the continuation consumes the detached token in a final cleanup.

In `confirmOverlayRemoved`, establish ownership before clearing references: there must have been an owned overlay view, exact detached token equal to current overlay token, and accepted removal authority. Retain the physical `isAttached` postcondition, removal-policy action arbitration, and retry/exhaustion behavior. A synthetic no-overlay release must not grant/arm completion or Messages success.

Create one private service predicate for detached terminal authority, parameterized by expected policy state. It checks `acceptsDetached(detachedToken)`, no replacement overlay/token, detached-token ticket equals service ticket and current gate generation, both consents, connection, and expected state. Use it before root acquisition, after acquisition/package validation and after routing where applicable. This predicate is not an exported test endpoint. A stale continuation may abandon only its own pending attempt/guard; never call an unconditional `cancel()` that would corrupt a newly installed replacement.

### 4.3 Completion sequence

1. The real captured completion Runnable requests `COMPLETE`; it does not grant or arm anything.
2. Cancel visible callbacks, arbitrate action, attempt physical removal with the unchanged retries and safety veto.
3. After confirmed detachment and a winning `COMPLETE`, use `detachedToken.ticket`, not a fresh lookup of whatever ticket now happens to be in the service.
4. Revalidate current detached authority in `GATING`; acquire one current root and verify Instagram package only. Missing/foreign/unattributed/throwing roots reject completion. Do not traverse or invoke the Messages router for this check. Recheck authority after root acquisition/package inspection.
5. Sample `terminalNow = monotonicClock()` after these checks and call `entryGate.complete(terminalNow, detachedTicket)` once. This must still validate the five-second deadline; a stale/early Runnable cannot grant.
6. Publish the resulting state. On rejected current completion, bypass/cancel only that current episode without credit. Do not mutate a replacement.
7. In `finally`, attempt to recycle each obtained root exactly once and consume detached authority. A recycle exception is swallowed as at the existing service boundary and cannot trigger a duplicate completion/arm. No root obtained means no recycle.

The new package-only completion revalidation is needed so a foreground change during delayed detach cannot grant/arm merely because no disqualifying callback arrived. It is not a new tree traversal or a new capability. No uncertainty grace grants terminal credit; uncertain terminal foreground fails open. Keep the existing visible watchdog's 150 ms grace unchanged.

### 4.4 Messages sequence

1. Only the current overlay's explicit Skip listener may request `NAVIGATE_MESSAGES`. Close visible callbacks and cancel the timer/watchdog before removal attempts.
2. While attached, no root for routing, no bypass release, no route, no click and no cooldown. HOME, Doom return, foreign reset, safety cleanup, revocation, disconnect and retry exhaustion may still veto/replace the pending action according to the existing removal policy.
3. After confirmed physical detach and winning `NAVIGATE_MESSAGES`, validate current detached authority in `GATING` before `currentRoot()`.
4. Acquire exactly one current root. Verify its package is `com.instagram.android`; recheck detached token/ticket/generation, both consents and connection. Null/foreign/unattributed/exception cases abandon this current episode without credit and recycle once if acquired.
5. Call `beginMessagesRoute(detachedTicket)` once. Publish `BYPASSED` and clear report as the base does before the external route. If preparation rejects, do not call the router.
6. Call `val result = overlayPlatform.routeMessages(root)` once. The production adapter remains `InstagramMessagesRouter.route(root)`. Do not treat reaching this line or `DirectTabDecision.CLICK` as success.
7. After return, recheck current detached authority in `BYPASSED` and the pending policy attempt. Do not reacquire a second root or perform a second lookup/click. The current root's pre-route Instagram attribution plus the revalidated continuation governs this synchronous one-shot attempt; this does not prove actual destination arrival. Revocation/reset/replacement inside a route seam must veto credit, even if it returns `CLICKED`.
8. Read terminal monotonic time at accepted route-result consumption, not at tap, shown time, route preparation or initial removal request. Pass that exact time and actual enum into `finishMessagesRoute`. `FAILED` consumes the attempt without arming. Exceptions are a failed attempt; never assume `CLICKED`.
9. Recycle the obtained root once in the existing `finally`, then consume detached authority. Preserve a successful terminal anchor if only recycling throws; do not retry routing/recycling or award a second time. All exit paths leave the overlay detached and the failed current session bypassed; a later foreign-session reset permits reentry without an invented cooldown.

### 4.5 Exact Direct routing remains unchanged

`app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt` must retain:

- `INSTAGRAM_DIRECT_TAB_ID = "com.instagram.android:id/direct_tab"` only.
- `DirectTabDecision { REJECT, ALREADY_SELECTED, CLICK }` is policy intent, not route success. `CLICK` becomes `CLICKED` only after `ACTION_CLICK` returns true.
- Exactly one match before selected/actionability reads; zero or multiple matches produce `FAILED` without state reads/clicks.
- A selected unique match returns `ALREADY_SELECTED` without reading actionability or clicking.
- Otherwise visibility, enabled and clickable must all pass before at most one `ACTION_CLICK`. False/throwing click returns `FAILED`; no retry.
- Router recycles each distinct returned non-root node once, excluding root identity; service owns the root's one release attempt. Test root-as-match and duplicate identities so introducing terminal handling cannot double-recycle.
- No text search, content-description reads, child traversal, coordinate/gesture action, alternative selector, deep link, chooser, fallback, retry, telemetry, network, or persisted result.

## 5. Deterministic policy test matrix

Edit `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt` first. Tests use mutable monotonic time, never sleeps. Retain existing default-off, deadline, classifier-independent, stale-ticket, invalid-duration and session behavior coverage.

For each boundary test: install/show at `S = 10_000`, prove inactive immediately after shown, complete the specified terminal operation at `T`, then perform `leaveInstagram()` to make a new session otherwise eligible. At `T + 59_999`, assert suppression, null eligible ticket and unchanged generation. At `T + 60_000`, assert no suppression and one eligible ticket. Do not accidentally assert expiration against shown time or compare only `cooldownActive` without admission behavior.

| Terminal case | T | Suppressed check | Eligible check |
| --- | ---: | ---: | ---: |
| Successful completion at five seconds | 15,000 | 74,999 | 75,000 |
| Delayed successful completion after detach | 16,250 | 76,249 | 76,250 |
| Prepared Messages returns `CLICKED` | 12,750 | 72,749 | 72,750 |
| Prepared Messages returns `ALREADY_SELECTED` | 13,250 | 73,249 | 73,250 |

Required additional cases:

- Admission alone, repeated `overlayShown`, repeated observation/render-shaped queries and waiting beyond 60 seconds without success never arm. Repeated shown calls do not change the original five-second deadline.
- Completion missing shown, AWAITING/BYPASSED/OUTSIDE/GRANTED state, early at 4,999 ms, negative time, rollback before shown, disabled predicate, stale generation, duplicate completion all reject without new cooldown.
- Messages preparation rejects AWAITING/unshown, disabled, stale and duplicate attempts. Successful preparation enters BYPASSED without cooldown; timer completion now rejects.
- `FAILED` consumes the current pending attempt, stays BYPASSED and cannot be upgraded by a later success call. `CLICKED`/`ALREADY_SELECTED` without preparation reject. Generic bypass/old skip semantics cannot authorize success.
- Cancel, leave/reset, generic bypass, revocation-shaped disable/reaccept, a new generation, or repeated terminal calls invalidate pending credit and cannot arm. Rejection for an old ticket must not alter a fresh attempt.
- Negative/rollback result time rejects without credit; a current invalid attempt is consumed and cannot later be retried. An already-earned cooldown conservatively suppresses invalid clock samples.
- Parameterize both success enums and completion for duplicate calls: retries before expiry and after expiry never refresh/rearm; the original exact expiration remains unchanged.
- Test both halves of reset semantics: before a terminal success, cancel/leave/reaccept allows otherwise-eligible immediate new session; after a success, the same operations preserve the original anchor and allocate no ticket until expiry.
- New policy/service-instance construction starts unarmed at the same fake time; no preference or disk state participates.

Add `acceptsDetached`/consume/invalidation tests to `app/src/test/java/com/chardy/doom/OverlayCallbackGuardTest.kt`: visible/removal authority stays false after detach, exact epoch has only continuation authority, new epoch with the same ticket invalidates the old continuation, safety invalidates detached authority, stale detach/consume cannot invalidate new epoch, and consumption is final.

Keep `app/src/test/java/com/chardy/doom/OverlayRemovalPolicyTest.kt` priorities/retry behavior unchanged and run it. Add a focused assertion only if a missing continuation-veto case is discovered; do not alter arbitration to make cooldown tests pass.

## 6. Service-path instrumentation matrix

Implement in `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`, unannotated/canonical. Prefer `freshService`, a real synthetic Instagram `onAccessibilityEvent`, actual installer callback, actual captured completion Runnable and actual overlay button callbacks. Test reflection is confined to existing private seams/guard assertions, not production-state shortcuts.

### Deterministic harness requirements

- One mutable fake time must drive service timing and captured cooldown clock; no real-time sleeps for 59,999/60,000 or five-second boundaries. Keep mutation/assertions on the main thread.
- Hold each clock-sensitive scenario inside a controlled main-thread callback; cancel/drain pending callbacks in teardown so the posted real watchdog cannot create timing-dependent root counts. Capture and invoke the real completion Runnable; for delayed detach invoke the actual retry boundary deterministically without waiting for real 60 seconds.
- Extend `FakePlatform` only with test-local route hook/result timing and optional real-router delegation through synthetic nodes. Configure root/route exceptions, refusal/throwing removal, root recycle failure, revocation and token/ticket replacement. Do not widen production routing capabilities or add exported hooks.
- Log only synthetic call markers and counts: installer, remove, attachment check, root acquire, route, root release. Assert cooldown is false inside attached-removal and route callbacks, not merely before the whole test; true only after accepted successful result/completion. No private content or root dumps.
- `Outcome` may gain a cooldown-active field for trace-on/off equivalence. Use real terminal paths for positive credit, not `admitForDisplay`, direct cooldown internals, pre-granted policy state or a manually seeded timestamp.
- Keep `InstallMode.REJECT_CONNECTION`: it models rejection between fake install and `overlayShown` without nested preference-listener teardown. Test real report/gate revocation separately through `Observation` with the service's companion instance wired.

### Required positive and boundary paths

| Path | Required service assertions |
| --- | --- |
| Actual install/display | Attached/GATING/current token and ticket; inactive cooldown; exactly one installer; no route/Home |
| Normal completion | At S+4,999 captured completion rejects/no arm; use a separate current episode for success at S+5,000; detach and current-root validation precede `GRANTED`/active cooldown |
| Delayed completion removal | Request at S+5,000 while attachment persists: no grant/arm; final detach at 16,250 with Instagram/current authority succeeds and anchors there, not 15,000 |
| `CLICKED` Messages | Actual Skip listener, delayed physical detach, root check, BYPASSED at route, false cooldown inside route, one route/click, one root recycle, true cooldown only after returned result |
| `ALREADY_SELECTED` Messages | Same ordering/credit with zero clicks; no actionability reads via real-router seam |
| Exact terminal boundaries | For completion and each success enum, service reset plus Instagram events at T+59,999 and T+60,000: before expiry no root/report/ticket/generation/install change; at expiry one new eligible install, still unarmed until its own success |
| Duplicate callbacks | Reinvoke saved completion/Skip/removal retry after success, including expiry: no second route, no new arm/anchor extension, no new overlay mutation |
| Recreation | First actual service earns cooldown and is destroyed; fresh service at same fake time displays unarmed; old callbacks cannot credit the new service |

For suppression tests, seed a synthetic sentinel report after the foreign reset, snapshot it plus ticket/generation/root/install counts, and assert the suppressed event does not replace/clear/reveal/copy it. A report cleared by the reset itself must not be mistaken for cooldown behavior.

### Required non-arming paths (each needs explicit cooldown assertions)

| Trigger / branch | Service-path test and required result |
| --- | --- |
| Back / app switch / outside reset before five seconds | Delivered foreign-root event drives actual reset and detach; no route/Home/credit; immediate eligible reentry after reset installs again |
| User Leave / HOME | Real Leave callback detaches before one HOME; no cooldown even when HOME succeeds, returns false or throws; same-session bypass remains, outside reset permits immediate reentry |
| Pending Messages overridden by HOME | Existing `homeBeatsPendingSkipAndDoesNotRouteMessages`, add no-credit assertion; HOME does not inherit the discarded Messages opportunity |
| Pending completion overridden by HOME/Skip | HOME never arms; winning Skip arms only for its own successful exact route result, not from elapsed timer time |
| Failed installation | `InstallMode.FAIL` actual event path: no credit/actions; successful retry after outside reset displays unarmed |
| Rejected shown/admission | `InstallMode.REJECT_CONNECTION`, plus stale/revoked shown policy cases: cleanup, no credit; reaccepted eligible display still unarmed |
| Generic BYPASS / safety cleanup | Call through `cancelEntryGate`, `failOpen`/watchdog and delivered denied events; detach before policy release, no new cooldown |
| Watchdog foreign / uncertainty / failure | Actual tick with foreign root; missing/null-package/package-read failure at shared 149/150 boundary; rollback/failure paths: retention at 149 without credit, cleanup at 150 without credit |
| Visible Instagram root missing/foreign/throwing or collection failure | Use actually installed unarmed gate and delivered Instagram event; existing fail-open path removes without credit; repeated valid events keep one timer/overlay and no cooldown |
| Gate consent revoked / report consent revoked | Use actual `Observation.setGateConsent` / `Observation.accept`; cover visible state, pending detach, root-read hook, and route-result hook; reaccept cannot release old success/credit |
| Service interruption/unbind/destroy/stop/reconnect | Cover visible and pending COMPLETE/Messages; no arming from cleanup or a saved callback after reconnect; preserve any already-earned anchor within the same object |
| Direct Doom return | Deliver MainActivity state event while visible and during pending COMPLETE/Messages; current session resets, latest hidden report preserved when preservation wins, no route/credit; other own-overlay events are not direct return |
| Stronger foreign/safety cleanup over Doom return | Clears report and vetoes pending action without credit, preserving existing priority |
| Stale token / epoch / ticket / generation | Old visible action, old timer and old retry cannot remove/credit a replacement; replace token with same ticket during root acquisition and during route-result hook; reject old continuation without canceling new session |
| Completion foreground invalid at final detach | Missing/foreign/null-package/throwing current root, revoked connection/consent or stale token after delayed detach: no grant/credit, acquired root released once |
| Messages missing root / acquisition exception | Zero route/click; zero recycle when root not obtained; no credit and bypassed current session |
| Messages foreign / null-package / package-read exception | Zero route/click; obtained root recycled once; no credit |
| Messages `FAILED` / route exception | One route at most, no credit, root released once, same session bypassed and no immediate regate; actual outside reset permits immediate entry |
| Exact-ID zero / multiple / duplicate matches | Run real router via test-local seam through service Skip path, not just canned FAILED; no click, no credit, distinct matches/root released exactly once |
| Exact-ID non-actionable / false click / throwing click | Each actionability field false prevents click; false/throwing action invokes at most one click, returns FAILED, no credit, releases all owned nodes |
| Missing/ambiguous destination | No inferred success, alternate selector or fallback. Cover missing/ambiguous exact matches in preceding service-router cases; phone destination ambiguity is not proof of success |
| Detach refusal / exception while still attached | For COMPLETE and Messages, no grant/route/credit during retries; exception alone is not detachment |
| Retry exhaustion / late detach | Exhaust COMPLETE and Messages as well as existing HOME test; service disabling and later detach cannot resurrect discarded action or credit |
| Already detached current view | Current authorized COMPLETE/Skip may succeed only after observing detachment and normal validations; unrelated external detach/no current action or no view cannot itself arm |
| Root recycling exception | One release attempt only; failure/no-authority route stays unarmed; successful consumed terminal result retains its one earned anchor, no retry/second route |
| Copy / render / trace arm-refresh-copy | Never arms cooldown, with trace enabled and disabled; existing clipboard boundary and report-clear/preservation semantics remain intact |

For negative tests starting without cooldown, assert `cooldownActive == false` before/after the trigger and verify otherwise-eligible immediate reentry via real outside reset where appropriate. For cleanup after an earlier success, assert that the original T+59,999/T+60,000 boundary is preserved rather than demanding inactive cooldown.

Extend `app/src/androidTest/java/com/chardy/doom/InstagramMessagesRouterTest.kt` only as needed for root-as-match, find/selected/actionability exceptions and recycling-exception coverage. Keep all existing exact-ID/order/no-click tests. `app/src/test/java/com/chardy/doom/InstagramMessagesRoutingPolicyTest.kt` remains pure routing regression coverage under Gradle. Service tests must prove result-to-credit wiring; router-only tests cannot substitute for them.

Run `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` canonically: disclosure, separate consent, wired lifecycle invalidation, report preservation/clearing and consent-only persistence must remain green. Add a current-copy/disclosure assertion if the wording update needs one; never insert real Instagram content.

## 7. Ordered implementation tasks and exact file scope

These tasks are future work. Each test group follows RED (write and execute a focused failing test against current behavior), GREEN (minimal change), then targeted regression checks. Do not count a compiler error for a not-yet-added API as behavioral red evidence; also retain tests that fail on the old admission-time behavior. No commit step is authorized by this plan.

### Task 1 — Establish terminal policy contract

1. Add display-unarmed and completion terminal-time tests to `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt`; run the host gate suite and retain the behavioral failures.
2. Remove admission arming, make `complete` atomic, and rename private cooldown timestamp/recording internals in `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt`.
3. Add pending Messages preparation/result tests, move the unchanged result enum out of `app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt`, and implement the two narrow Messages APIs.
4. Update all old skip/admission unit callers; run the host gate suite until the new and retained policy tests pass. No Android import/dependency in policy.

### Task 2 — Detached authority and service terminal wiring

1. Add guard continuation tests and implement only the required detached epoch lifetime in `app/src/test/java/com/chardy/doom/OverlayCallbackGuardTest.kt` and `app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt`.
2. Add service positive/negative regressions in `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt` for actual display, completion and both route outcomes. Compile instrumentation later in the configured no-emulator environment; behavioral instrumentation RED/GREEN belongs to authorized GitHub API 35 execution, not this host or Codex Cloud.
3. Edit `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`: remove installation arming, bind completion to detached ticket, revalidate current terminal authority, consume Messages result, preserve pre-route bypass, protect replacement episodes on stale continuation, and release roots/authority exactly once.
4. Add the delayed-detach, stale/revoked continuation, reentry and non-arming matrix; migrate every direct test admission caller. Keep required WIL-181 test names.

### Task 3 — Routing/lifecycle regression and source contracts

1. Extend `app/src/androidTest/java/com/chardy/doom/InstagramMessagesRouterTest.kt` for missing ownership/exception edges; use real-router delegation in service tests for zero/multiple/unactionable/failed click.
2. Update `scripts/test-structural-lifecycle.py` guards at 239-252 and 418-446: remove requirements for admission arming; assert addView-before-shown-before-watchdog/render, no cooldown mutation in installation/nonterminal APIs, atomic terminal APIs, returned enum success filtering after route, detached current-ticket/consent/connection/Instagram checks and terminal-time clock use. Preserve early earned-cooldown suppression, one-shot routing and root-release/privacy guards.
3. Keep `scripts/test-ci-isolation.py`, `scripts/validate-android-junit.py`, all workflow files and CI scripts unchanged. Existing required method names are retained; the validator dynamically inventories new unannotated service tests. Do not weaken inventories or move these tests to supplemental.
4. Run `scripts/test-entry-gate-host.py` unchanged: placing the pure enum with the policy avoids adding Android-dependent router code to this compiler list.

### Task 4 — Correct current disclosures, not historical evidence

Update only the admission-time claims in:

- `README.md:7,41`.
- `docs/WIL-149-VALIDATION.md:13-23,31,39,80` (clearly label WIL-184 current behavior versus historical WIL-179 evidence; do not rewrite old executed results as new evidence).
- `app/src/main/java/com/chardy/doom/MainActivity.kt:137,168`.
- `app/src/main/res/values/strings.xml:2` (`observation_description`).

State: a successful five-second completion, or an authorized exact Messages route returning clicked/already-selected, starts a 60-second in-memory monotonic cooldown from terminal success. Display, cancellation, Leave and failed routing do not. Preserve default-off, fail-open, DM-unsafe/unverified, process/service-recreation limitations, exact selector scope and no destination guarantee. Describe already-selected as no-action success rather than conflating it with failed routing. Trace arming does not bypass eligibility or create credit; keep the phrase `60-second cooldown` so `scripts/test-removal-trace.py:91` still asserts the truthful disclosure. No trace schema expansion is needed and `ACTION_RELEASED` must not be presented as proof of route/terminal success.

Only adjust `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` if necessary to assert the revised disclosure and unchanged privacy/consent semantics. Parse changed XML. Leave historical plans/retrospectives, `docs/FIXTURE.md`, fixture implementation, demo policy and UI styling untouched.

### Files outside the implementation boundary

No `.github/workflows/*`, signing/evidence validators, production manifest, accessibility configuration, Gradle files, wrapper, dependency declarations, fixture modules or permissions should change. No cooldown state in preferences, saved state, files, static process service registry, report payload or analytics. Optional policy-test additions belong in the existing files listed above, not a new framework.

## 8. Complete validation and promotion sequence (not executed here)

All commands below are future instructions for separately authorized implementation/validation. Run from the repository root. Stop at the first causal deterministic failure; preserve the relevant output rather than claiming later stages ran. No local emulator/adb or local signing, and no SDK installation/update in Codex Cloud.

### 8.1 Host checks first

```bash
python3 -B scripts/test-entry-gate-host.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test_wil155_host.py
python3 -B scripts/test-internal-signing.py
python3 -B scripts/test-removal-trace.py
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-android-junit-validator.py
bash -n scripts/ci-device.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh
python3 -B -c 'import xml.etree.ElementTree as ET; ET.parse("app/src/main/res/values/strings.xml"); print("strings.xml: parsed")'
git diff --check
```

Expected acceptance, not observed results: all exit zero; report actual test counts. The host gate runner uses compatible cached compiler/JUnit jars without invoking Gradle; absent cache is a prerequisite blocker, not permission to upgrade or download a toolchain. Its explicit list does not include `InstagramMessagesRoutingPolicyTest`; the Gradle command below supplies that coverage. Host source guards do not establish Android compilation, real attachment or phone routing.

### 8.2 Focused no-emulator unit tests in configured Codex Cloud

```bash
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest \
  --tests com.chardy.doom.InstagramEntryGateTest \
  --tests com.chardy.doom.OverlayCallbackGuardTest \
  --tests com.chardy.doom.OverlayRemovalPolicyTest \
  --tests com.chardy.doom.OverlayForegroundWatchdogTest \
  --tests com.chardy.doom.InstagramMessagesRoutingPolicyTest
```

### 8.3 Mandatory Codex Cloud preflight before independent review

Use pinned Java 17/SDK 35. Run the exact root-AGENTS command, including a full unfiltered unit suite after any focused invocation:

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Then compile the modified service/router instrumentation without a device:

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
stat -c '%n %s bytes' app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Read actual `app/build/test-results/testDebugUnitTest/TEST-*.xml` and `app/build/reports/lint-results-debug.xml`. Report signing-host-test count, full unit-test count with failures/errors/skips, lint error count, command exit codes, and APK path/size/SHA-256. The APK path relative to this checkout is `app/build/outputs/apk/debug/app-debug.apk`; its absolute workspace path is `/mnt/HC_Volume_106820083/worktrees/doom-wil184-terminal-cooldown/app/build/outputs/apk/debug/app-debug.apk`. No count, size or digest is predetermined. Do not claim instrumentation/emulator/phone evidence from compilation or this preflight.

### 8.4 Independent Opus review

Only after green host checks, full Codex Cloud preflight and instrumentation compilation, request an independent Opus review in an isolated read-only context. The controller must select/verify the actual Opus reviewer model; do not label an arbitrary default subagent “Opus.” No review is executed or claimed by this planning task.

Provide the exact candidate/base diff, this behavior contract, test matrix, actual check output and static privacy/security scan findings; give read-only access to adjacent removal/guard/router code. Ask for a structured PASS/FAIL with file:line findings and separate beta-blocking safety/privacy/core-flow/signing/provenance defects from suggestions. Specifically require scrutiny of:

- Atomic completion/result credit and a terminal, not shown/tap, timestamp.
- The two-result success allowlist, pending-attempt consumption and no arbitrary bypass credit.
- Physical detach before completion/Home/Messages/bypass; stale same-generation epochs; root-read/route-return revocation; no replacement mutation by rejected old actions.
- No extra route/query/click, root ownership/recycling, no private metadata and no network/persistence.
- All negative paths and exact boundaries executed at the proper validation tier.
- WIL-181 isolation and exact-head signer input unchanged.

Missing/unparseable review or an unverified reviewer identity is not a pass. Fix beta blockers, rerun affected host checks and full required preflight, then obtain targeted Opus rereview. Freeze a green candidate rather than adding polish or opportunistic refactors. Publication/commit/push/PR operations still require explicit authorization outside this plan.

### 8.5 Exact-head canonical GitHub Actions CI

After controller-authorized publication, bind the candidate to its actual full 40-character SHA. Do not use this plan's base SHA as the implementation candidate. Read back the run's repository, workflow path, event, head SHA, run ID/attempt and conclusion.

Keep `.github/workflows/android.yml` (`Android diagnostic CI`) unchanged:

- `Android baseline` runs host source/evidence checks and:

```bash
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- `Diagnostic emulator evidence`, after baseline, compiles before provisioning and runs on the existing API 35 Google APIs x86_64 disposable emulator:

```bash
bash scripts/ci-device.sh
```

The script owns the complete canonical commands and executes:

```bash
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.chardy.doom.SupplementalEvidence
python3 scripts/validate-android-junit.py canonical app/build/outputs/androidTest-results/connected
```

Run the script as configured in Actions, not these device commands on an agent/Codex Cloud host. Canonical results must include every unannotated service, router, lifecycle, privacy and new cooldown test with no failures/errors/skips or duplicates. Reconcile actual XML identities against current source through the validator; do not hard-code an old test count or assume compilation means execution.

Read back exact-head `doom-device-evidence-<candidate_sha>` and `doom-device-diagnostics-<candidate_sha>` (plus baseline reports). Validate the manifest and tested `doom-diagnostic.apk` digest, complete canonical JUnit, four required Doom-owned screenshots, and failure diagnostics. `doom-diagnostic-apk-<candidate_sha>` from baseline is not a substitute for the emulator-tested signer input. Canonical synthetic evidence does not prove Instagram behavior.

### 8.6 Independent supplemental CI, unchanged WIL-181 boundary

Keep `.github/workflows/android-supplemental.yml` (`Android supplemental CI`) independent and unchanged. Preparation remains:

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebug :app:assembleDebugAndroidTest :fixtureapp:assembleDebug :fixtureapp:lintDebug :fixturegate:assembleDebug :fixturegate:testDebugUnitTest :fixturegate:lintDebug :fixturegate:assembleDebugAndroidTest
```

Within its single existing API 35 emulator, use the coordinator:

```bash
bash scripts/ci-supplemental.sh
```

It runs `bash scripts/ci-overlay.sh` and `bash scripts/ci-fixture.sh`, attempting both components and preserving their separate failures. Overlay instrumentation uses:

```bash
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.chardy.doom.SupplementalEvidence
python3 scripts/validate-android-junit.py supplemental app/build/outputs/androidTest-results/connected
```

The fixture script retains its existing build/install/readiness steps and the device task:

```bash
./gradlew --no-daemon --stacktrace :fixturegate:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixtureCi=true
```

The complete authoritative device procedures are the checked-in scripts; do not bypass readiness/clean-SHA checks, fake `GITHUB_ACTIONS`, or manually install fixtures on a private device.

Read back `doom-overlay-ui-<candidate_sha>-<run_id>-<attempt>` and `doom-fixture-<candidate_sha>-<run_id>-<attempt>`, both component exit records, manifests, JUnit and diagnostics. Preserve exactly five marked supplemental methods, six overlay screenshots and thirteen fixture screenshots. Each lane binds the same source SHA to the APK it actually tested; independently built APK bytes need not match each other. Supplemental artifacts are never signer input.

Supplemental red remains visible and receives severity classification; it must not be hidden with `continue-on-error`, change canonical conclusion, or independently revoke/authorize signer eligibility. A core-flow, privacy, safety, startup/build/install, evidence or signing regression is a beta blocker regardless of lane. Other supplemental fixture/screenshot/accessibility/polish findings may be tracked as nonblocking beta defects. Allow at most one unchanged-SHA rerun only for a classified infrastructure failure; repeated failure needs a defect rather than blind reruns.

### 8.7 Protected signing, only after separate authorization

No local signing or release command is authorized. An authorized controller uses the existing `Sign internal APK` workflow, `.github/workflows/sign-internal-apk.yml`, dispatched on trusted `main`, with explicit `source_run_id` (successful canonical run) and `candidate_sha` (exact tested candidate). Do not sign a local/preflight, baseline-only, supplemental or fixture APK.

The workflow must retain its repository/workflow/event/head-SHA/success provenance checks, protected `internal-signing` environment limited to `main`, complete device-evidence validation, safe ZIP handling, signature verification and non-signature payload comparison. Its signing step is the unchanged `bash scripts/sign-internal-apk.sh` in that protected runner, not on this host. Do not fetch, print or move signing secrets.

Read back `doom-internal-signed-apk-<candidate_sha>`, `doom-internal-<candidate_sha>.apk`, `signing-evidence.json` and `signer-report.txt`. Verify source run/attempt/SHA, source APK digest matching canonical tested evidence, signed APK size/digest/package/version, one expected certificate fingerprint (`76ac486496e74c6a598f06745e0c43d25cdb94d18cdaf2eb69272980043f7003`), v2 signature and identical non-signature payload. Signing evidence must continue to say `actual_instagram_verified: false`; signing is not phone or rollout acceptance. Release/merge remains separately authorized.

### 8.8 Focused consenting phone checks

Use only an explicitly authorized consenting phone and the exact verified internal-signed candidate, with both consents enabled by the user. Record candidate/version, Android/Instagram versions, broad surface category, monotonic elapsed observations and pass/fail only. No raw accessibility trees, text, names, messages, captions, descriptions, coordinates, notification content or private screenshots; do not upload reports to prove cooldown. Do not use real account content as test assertions.

1. Enter Instagram, observe a normal untouched five-second completion, exit and reenter before 60 seconds from completion: no repeat gate. Reenter after 60 seconds from completion: otherwise-eligible gate may appear. Record from completion/removal, not from initial display. Exact millisecond proof remains deterministic tests, not hand-timed phone claims.
2. Enter, press system Back or switch away before completion, then return promptly: a fresh otherwise-eligible pause must appear. Repeat using Leave Instagram/Home. No earned cooldown should mask these aborted pauses.
3. Use explicit Skip on an eligible pause. Confirm physical overlay removal and broad Messages destination outcome without content capture. After successful route, exit/reenter before and after the terminal-minute interval. Do not infer success merely from overlay disappearance.
4. Where the installed Instagram layout safely exposes an already-selected Direct tab under an eligible pause, check no extra navigation/click and terminal cooldown. If this state is not reproducible without unsafe interaction, report not exercised; canonical synthetic service/router tests remain the deterministic `ALREADY_SELECTED` evidence.
5. If routing is naturally missing/ambiguous/fails, confirm fail-open removal and immediate eligible reentry after leaving the session; never probe extra selectors, collect private metadata, force a click, or modify Instagram to manufacture the case. If unavailable, report the negative live route case untested rather than claiming a pass.
6. Check direct Doom return before completion (report remains hidden/preserved), consent revocation/observer stop during a pause, app switch during delayed cleanup where observable, lock/rotation/interruption and service restart. No stale action/credit; no visible stranded overlay. New service instance may reset process-memory cooldown.
7. Record incidental early safety cleanup distinctly from five-second completion. No “cooldown working” claim when no overlay was admitted, service was disconnected, consent was absent, or a prior successful cooldown suppressed the test itself.

This focused regression does not replace the broader WIL-149 consented 20-repetition-per-high-risk-path safety/latency matrix in `docs/WIL-149-VALIDATION.md`. Fixture/Doom-owned screens cannot prove Instagram routing, DM safety or rollout readiness.

## 9. Acceptance checklist and honest handoff

- [ ] Active WIL-184 ticket/dependencies read and reconciled before implementation; exact base preserved.
- [ ] No display admission arming API/call remains; five-second display clock unchanged.
- [ ] Successful completion atomically grants and records terminal time only after physical detach/current authority.
- [ ] Successful exact Messages result atomically records terminal time only for a single current prepared attempt, with bypass before route and no credit on FAILED/stale/revoked results.
- [ ] Every listed cancellation/reset/Home/safety/root/route/install/lifecycle path has explicit service-level non-arming coverage.
- [ ] Completion, delayed detach and both route-success enums have deterministic terminal-relative 59,999/60,000 checks, including no root/report/ticket/install before expiry.
- [ ] Same-session bypass, stale-ticket rejection, exactly-once recycling and no replacement mutation preserved.
- [ ] Current disclosure corrected without rewriting historical executed evidence.
- [ ] Host checks, Codex Cloud full preflight and instrumentation compilation actually green with read-back counts/artifacts.
- [ ] Independent Opus review actually obtained for that green candidate; beta blockers resolved and rereviewed.
- [ ] Exact-head canonical CI passed/read back; supplemental CI separately read/classified; unchanged WIL-181 partition and signer contract.
- [ ] If authorized, protected signing evidence and focused phone results verified separately; missing live cases explicitly unverified.
- [ ] No permissions/dependencies/workflows/network/persistence/selector expansion and no unrequested commit/push/PR/release actions.

Planning verification is limited to source inspection, exact-base/clean-start verification, this plan's path/coverage/whitespace checks, and confirmation that the plan is the only worktree change. No host test suite, Gradle build, instrumentation, emulator, adb, signing, release, Opus review, commit, push or PR action was executed during planning. Every future validation item remains unexecuted until its real result is obtained and read back.
