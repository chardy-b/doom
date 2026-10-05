# WIL-235 — Overlay removal recovery and non-blocking gate

Status: implementation plan, not an implemented or device-verified fix.

Repository: `/mnt/HC_Volume_106820083/projects/doom` (`chardy-b/doom`). Inspected clean baseline: `15b3623da5e826ff57a9ca943cf9dc5ae08b0ceb`. All existing line ranges below refer to that baseline; proposed files/symbols are identified explicitly. This task authorizes this document only: no implementation, commit, push, ticket mutation, CI dispatch, signing, or release.

Issue: https://linear.app/wildhearts/issue/WIL-235/bug-go-to-message-can-leave-the-breathing-gate-stuck-blocking-the

## 1. Evidence, scope, and decisions

Read before planning: `docs/plans/2026-09-13-doom-breathing-removal-trigger-diagnosis.md`, `DoomAccessibilityService.kt`, `OverlayCallbackGuard.kt`, `OverlayRemovalPolicy.kt`, the gate UI and existing unit/service-action tests, repository instructions and validation documents, and commit `1ac78a84c555f6e955068f3f42cbf160405bdb4d` (WIL-222). The September diagnosis is historical: its disable-on-exhaustion prescription is explicitly superseded here. Its five-second/admission-cooldown descriptions must not override today's configurable duration and terminal-success-only cooldown.

The user reports an intermittent dead fullscreen gate after tapping “Go to message,” with Back, app switching, lock/unlock and the power menu unusable until a forced restart. The current factory labels that action “Skip to Messages”; these refer to the same `NAVIGATE_MESSAGES` path. No phone trace or live WindowManager evidence was obtained in this planning task. Source inspection proves a hazardous reachable failure path, not the exact Android/OEM failure that occurred.

Implement all three layers together:

1. A bounded same-view re-add-and-remove recovery cycle after an unsuccessful immediate removal, before scheduling another attempt. Attachment, not absence of an exception, is the postcondition.
2. Replace removal-budget `DISABLE_SERVICE` with persistent-in-process slow retries. Never voluntarily disable while any owned gate or timer window remains attached or attachment is unknown. Preserve irreversible action vetoes and physical ownership until actual detach.
3. Make input safety independent of callbacks: a non-touchable, non-focusable visual window, with only three small button-sized touchable windows. Never create a fullscreen touchable window, even temporarily. Also remove the fullscreen opaque background: input pass-through alone does not make an obscured phone usable.

Not in scope: reverting WIL-222, changing selectors or foreground policy, a foreground service, another process, new permissions, synthetic Home/Back escape gestures, force-stop/reboot automation, or persistent collection of user reports. A Handler is not process-independent and cannot survive process death. Do not describe it as such.

### Issue-source provenance and an acceptance conflict

Live Linear access was unavailable here (no configured API key; the browser reached login). The issue's creation response was recovered from the actual MCP tool result in session `default/20261004_231806_e219a23e`, message `412183`; the submitted description is message `412182`. The subsequent three-layer proposal and user approval are messages `412187`–`412188`. Thus the acceptance wording below is grounded in the saved issue response, not a fresh read of possible later edits. Re-read WIL-235 before implementation/closure through the controller's authenticated Linear connection.

The issue asks for a “persistent (survives restart / process death) removal-trace or watchdog fallback.” Neither the existing RAM trace nor the proposed slow Handler loop satisfies that wording literally. The approved three-layer approach supplies a failure-safe window design even when cleanup code is unavailable; it does not add durable diagnostics. Ask the issue owner to accept this as the intended watchdog/failsafe alternative and amend/clarify that criterion before closure. If actual persisted evidence remains required, keep the issue open for a separately approved bounded, consented, content-free persistence design; do not silently persist `RemovalTraceStore`, structural reports, exception messages, or usage history. This is an acceptance reconciliation, not a reason to delay the safety implementation.

The saved ticket names “Pixel 11 / Android 11” and build `CD1A.260905.001.B1`; repository README describes prior “Pixel 11 Pro / Android 17” evidence. Do not repair either identifier or assume they describe the same device. Confirm the test phone model, Android/API version, build and installed Doom candidate manually before validation.

## 2. Reachable failure mechanism and teardown verification

Current production chain (`app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`, abbreviated `service`):

- `installOverlay`, lines 979–1055: installs one `MATCH_PARENT × MATCH_PARENT`, `TYPE_ACCESSIBILITY_OVERLAY`, `PixelFormat.OPAQUE` window with `FLAG_NOT_FOCUSABLE`, but not `FLAG_NOT_TOUCHABLE` (1001–1008). Not-focusable does not make in-bounds touches pass through.
- Button closure at 985–990 calls `requestOverlayRemoval(NAVIGATE_MESSAGES, token, USER_MESSAGES)`.
- `requestOverlayRemoval`, 1111–1133, closes callback authority, removes completion/watchdog callbacks and disposes the UI. `EntryGateOverlayUi.dispose`, `EntryGateOverlayView.kt:34`, disables/removes button listeners; this is not physical window removal and does not relinquish its touch region.
- `attemptOverlayRemoval`, 1159–1203, catches `removeImmediate` exceptions, checks attachment, and retries at 50 ms. After 20 unsuccessful attempts the policy vetoes external actions and returns `DISABLE_SERVICE`. The service calls `disableSelf()` without scheduling another removal attempt.
- `onInterrupt`, 1640–1651, and `disconnect`, 1665–1676, also cancel all Handler messages and discard the scheduled removal retry. A disable/unbind sequence can therefore abandon the only app-owned cleanup chain with a disposed, still-attached touchable view.
- Related loopholes: timer removal exhaustion calls `disableSelf()` at 943–950; `disableObservation()` does so at 66–74. Fixing only the gate switch arm leaves these paths able to kill gate cleanup.

### What Android actually establishes

Inspected AOSP `android-15.0.0_r1` source, chosen as the repository's API-35 baseline, not as proof about the reported phone:

- [AccessibilityService.java, 1131–1145](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java#L1131-L1145): `disableSelf()` documents disabling the service/settings and invokes the remote connection. There is no synchronous overlay-detached return value; a missing connection is a no-op and a remote failure may throw.
- [AccessibilityServiceConnection.java, 199–221](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java#L199-L221): the server removes the enabled-service setting and requests client-state reevaluation.
- [AbstractAccessibilityServiceConnection.java, 1650–1679](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#L1650-L1679): service removal has an intended platform cleanup path, including `removeWindowToken(..., true, displayId)` and overlay detachment. This supports expected cleanup, not guaranteed completion before local callbacks stop, nor immunity to a platform wedge.
- [WindowManagerGlobal.java, 390–400](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowManagerGlobal.java#L390-L400): adding the same view can force completion of a pending dying root; adding an ordinarily registered view throws “already been added.” This is a narrow rationale for a recovery probe, not a universally supported repair for every failed removal.
- [WindowManager.java, 2645–2720](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/WindowManager.java#L2645-L2720): `FLAG_NOT_TOUCHABLE` delegates touches below; accessibility overlays are trusted windows for Android S+ pass-through rules. `FLAG_NOT_FOCUSABLE` implies not-touch-modal, not non-touchable. Ordinary child Views cannot opt back into touch when their window is non-touchable.

Conclusion: the comment at service:1189 (“Android service teardown is the final platform-owned removal path”) is an unsafe application correctness assumption. Replace it with the reason cleanup must remain live. Do not intentionally reproduce the old disable-while-attached behavior on the user's main phone to prove it. A local `View.isAttachedToWindow == false` remains the public app-side detachment postcondition; it is not a query of server-side input/surface state. A server orphan or blocked main thread requires manual device evidence. No finite regression matrix can establish “never” for all OS failures; the invariant to prove in code is that Doom never installs fullscreen touch interception.

## 3. Concrete ownership and window design

### Selected architecture: one visual window plus three button windows

Use public `WindowManager.addView/updateViewLayout/removeViewImmediate`; keep `TYPE_ACCESSIBILITY_OVERLAY`. Prefer three exact button-sized windows over one full-width touchable bar, so spacers, margins and invisible bar padding cannot eat touches. Do not use hidden touchable-insets APIs, reflection, touch forwarding, gesture injection or a fullscreen touchable container with `onTouch` returning false.

Refactor `EntryGateOverlayUi`/factory (`EntryGateOverlayView.kt:26–67`) to expose `visualRoot`, `skipToMessages`, `leaveInstagram`, `debugReport`, and an ordered immutable list of window roots. Buttons are not children of `visualRoot`. Keep the existing three callback meanings and labels; moving them into separate windows must not add actions. `render` updates only phase/bloom/progress. Split idempotent `closeInteraction()` (remove listeners, disable actions, stop visual work) from `dispose()` (also clear layout/inset listeners after detach); neither is evidence of detachment.

Window parameters, explicit at every initial add and fallback re-add:

| Role | Size/format | Required flags and content |
| --- | --- | --- |
| Visual | Fullscreen allowed; `PixelFormat.TRANSLUCENT` | `FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL`; transparent root, no opaque fullscreen Ink fill, no dim/blur-behind, no clickable/scrollable fullscreen parent. Bloom, phase and progress occupy a bounded central region, leaving device UI visible around it. |
| Each of the three actions | Explicit measured positive button width/height, never `MATCH_PARENT`; `PixelFormat.TRANSLUCENT` | `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`; optional layout-in-screen only with inset-safe placement. No `FLAG_WATCH_OUTSIDE_TOUCH`, fullscreen touch delegate, or focus/key capture. Only the button rectangle receives input. |
| Any recovery re-add | Same original root and bounded role geometry | Force `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, `alpha = 0f`, and an inert/disposed root before add. A removal recovery must never briefly re-enable or redraw the gate. |

Add proposed `GateOverlayWindowLayout.kt`: pure geometry calculation with scalar viewport/safe-inset/button-measurement inputs and role descriptors, independent of Android Views. Reserve system/gesture/cutout/IME-safe space and a visible escape corridor. Start with max action width `min(320dp, safeWidth - 32dp)` and cap the combined action stack to half the safe height. Measure wrapped labels with minimum 48dp targets (retain the existing 52dp primary minimum). Reduce decorative bloom first, not accessibility target sizes. If three fully reachable buttons cannot fit within the cap, fail open and remove the entire episode; never solve large-font/landscape overflow by expanding a touchable window to fullscreen or shrinking text silently. No scrolling touch surface outside the three actions.

On configuration/insets changes (`service:810–833`), recompute current-episode positions, using window/view measurements only, not Instagram coordinates. Update only current visible windows. Invalid bounds, layout/update exceptions or a stale token trigger safety cleanup, not replacement admission. Do not move/recreate windows while closing. A non-touchable decoration should not trap TalkBack focus or declare a modal pane; expose phase/buttons with sensible semantics and manually verify cross-window traversal. No continuous accessibility announcements that keep focus hostage.

Intentional product trade-off: outside the three buttons, underlying app touches work even during a healthy reminder. This is a visual reminder, not an input lock. A wholly opaque fullscreen visual would permit blind underlying actions while hiding system controls, so merely adding a flag to the current opaque ScrollView is insufficient. A stuck small button can still obstruct its own rectangle; the guarantee is no whole-device lock, not zero residual pixels or local obstruction. Test actual system escape surfaces before accepting placement.

### One episode, all windows

Add a proposed Android-side `GateOverlayWindows` owner with one callback token and up to four `OwnedOverlayWindow` records (view identity, manager, role, copied LayoutParams, whether add was attempted, and attachment observation). Retain each record before calling add: add may attach and then throw. A failed partial install closes/removes every attempted window and never starts the completion clock, earns cooldown or releases an action. Add the non-touchable visual first, then actions; only after all adds and current authority checks succeed call `entryGate.overlayShown` and schedule completion/watchdog once.

Replace every gate-presence use of `overlay != null`/`overlay == null` with episode ownership, not visual-root attachment. Inventory all occurrences, including event classification (178–374), admission (458–473), timer attachment (732–807), install (980–1026), debug (1101–1108), watchdog (1063), detach authority (1469–1475), render (1514–1520), and lifecycle. If the visual is detached but one button remains, the episode still exists: no new gate, timer, collection, route or completion. Do not open a new `OverlayCallbackGuard` epoch for fallback adds. Update its class comment at line 5 from one physical overlay instance to one physical window-set episode; the existing visible/removal/detached state machine can otherwise remain unchanged.

## 4. Layer 1 — hardened removal transaction

Add proposed pure `OverlayWindowRemover.kt` with a tiny port (`attachment(): ATTACHED/DETACHED/UNKNOWN`, `removeImmediate()`, `reAddForRemoval()`) and a `mayReAdd` input. The port captures one owned record; production adapters use the saved manager/view/params and existing private installer/updater seams. Tests supply scripted state changes without loading Android. Result: `DETACHED` or `RETRY_REQUIRED`; this helper does not own tickets, policy, retries, trace or external actions.

One attempt for a record:

1. Check current episode/removal authority before touching any retry handle or window. If detached, return without remove or re-add. Treat a thrown attachment check as UNKNOWN, never success.
2. Best-effort make closing action windows non-touchable with `overlayWindowUpdater`; hide visual content/clear accessibility interactions. This is an extra safety measure, not required for the whole-device invariant, which already holds at install. Update failure does not stop removal.
3. Call `removeImmediate`. Catch `RuntimeException` at the platform boundary. Recheck attachment even after a throw. If now detached, succeed and do not re-add a successfully removed window.
4. If still attached/unknown and the service is still bound with a valid cleanup owner, try one same-view, same-manager re-add using a copied, invisible/non-touchable recovery LayoutParams. Do not call `installOverlay`, allocate a new View, open a token, render, collect, or run `overlayShown`.
5. Whether re-add returns or throws (including already-added, bad token, or add-attached-then-threw), perform the second immediate removal when the record is not confirmed detached. Catch independently and recheck. Never let an add exception skip this second removal. If re-add itself completed the detach and failed before reattachment, accept the observed detach without another add.
6. If still attached/unknown, retain ownership and return RETRY_REQUIRED. Bound each record to one re-add and at most two immediate removes per attempt. No recursion, synchronous while-loop, sleep or retry of Instagram navigation.

After unbind/destroy or loss of a valid service window token, disable re-add permanently for that retiring owner; continue remove/check only. Recovery must not resurrect windows under a dead token or after another service incarnation acquired ownership. Consent revocation permits only inert cleanup while still bound, never visible re-admission. If manager/params are unexpectedly unavailable, retain the record as unresolved and schedule removal-only recovery; do not manufacture a new manager/context or report detach.

`attemptOverlayRemoval` (1159–1203) processes action windows first, visual last, but attempts all records even when one fails. Count one unsuccessful window-set pass as one policy failure, not one failure per window. `confirmOverlayRemoved` (1205–1238) may consume policy/token only after all attempted windows are confirmed detached. Keep `NAVIGATE_MESSAGES` (1320–1382), completion (1400–1435), Debug (1247–1318) and Home (1437–1444) behind this barrier. No route or debug traversal begins while any owned window remains.

## 5. Layer 2 — degraded retries instead of abandoning cleanup

### Policy and scheduler

Change `OverlayRemovalDecision` (`OverlayRemovalPolicy.kt:6`) to `RETRY_FAST, RETRY_SLOW`; remove `DISABLE_SERVICE`. Keep the constructor budget but interpret it as the fast-attempt budget. In `failedAttempt` (42–54), saturate the counter at `maxAttempts` to avoid overflow. Before the threshold return RETRY_FAST; at and after it latch the existing irreversible veto and normalize pending action to RESET_OUTSIDE or BYPASS, then always return RETRY_SLOW. Repeated requests never reset the count. Only `confirmedDetached` (60–70) resets episode state.

Keep 50 ms fast retry and 20 unsuccessful complete passes. Add `REMOVAL_SLOW_RETRY_INTERVAL_MS = 1_000L` at service:51–54. Slow retries have no lifetime/attempt cap while that owner has an unresolved window and its process/main looper can run. One queued task per owner; scheduling is after the preceding attempt, with no catch-up burst. These intervals are scheduling policy, not wall-clock completion guarantees.

Extract a small production-used `OverlayRemovalRetryLoop` with an injectable single-task scheduler (Handler in production; virtual queue in unit tests). First close attempts immediately. While a task is already pending, additional events may strengthen the safety action but must not cancel/repost the task, reset the fast budget, or trigger extra immediate passes. This prevents event storms from starving the slow retry or converting it back to a 50 ms loop. Validate owner/token before cancellation in both `attemptOverlayRemoval` and `confirmOverlayRemoved`; today stale callbacks can cancel the current retry at 1160/1209 before being rejected.

The first fast-budget exhaustion stops timer/session/admission work, freezes the existing enum-only trace as `REMOVAL_EXHAUSTED`, `detached=false`, `policyReleased=false`, and preserves the vetoed action. Freeze once, not every slow tick. Retaining this bounded incomplete failure snapshot is deliberate: it does not claim eventual detach, and late recovery must not overwrite it with a fabricated successful trace. A future trace format can represent both degradation and late detach separately; no new persistence is needed here.

Do not automatically disable merely because the fast budget elapsed. Late confirmed detach releases BYPASS/RESET_OUTSIDE only, never the old Messages/Home/Debug/COMPLETE action or cooldown credit. A new eligible session can be considered later under existing consent/session rules, not by this retry callback. Do not mark `Observation.connected=false` solely because cleanup is degraded if the service remains OS-connected; use a distinct internal admission/cleanup latch rather than falsifying connection state.

### Close all disable/lifecycle escape hatches

- `disableObservation` (66–74): immediately revoke behavioral authority/clear report and trace per existing stop semantics; latch `disableWhenDetached`. Keep only physical cleanup running. Proposed `maybeDisableAfterDetach()` is the sole production `disableSelf()` call site, requiring no unresolved gate, retiring owner or timer windows. Invoke after both gate and timer detach. A no-window explicit stop may disable immediately. No post-detach external action is allowed once stop is requested.
- `attemptTimerRemoval` (935–955): use the same bounded remover and fast-to-slow schedule for the small timer window, using existing `timerParams`. Preserve `timerSafetyVeto`, end the timer session once, cancel pending gate handoff, and keep timer-removal callbacks alive instead of disabling. Do not recurse through `endTimerSession()`/`stopTimerCallbacks()` on every slow tick. `finishTimerDetach` (957–977) checks deferred disable and must not install a gate after a terminal/safety close.
- Split `stopTimerCallbacks` (918–922) into visible/session callback cancellation and removal-retry cancellation. The latter is allowed only after confirmed detach or explicit transfer of cleanup ownership, not ordinary safety shutdown.
- `onInterrupt` (1640–1651): stop collection/visible behavior, veto actions and request cleanup; remove the blanket `handler.removeCallbacksAndMessages(null)` and attached-window `disableSelf()` path. Named completion/watchdog/timer/session callbacks stop; removal retries remain. Interruption is not proof of unbind or process death.
- `onUnbind`, `onDestroy`, `disconnect` (1653–1676): external OS/user disable cannot be prevented. Immediately stop all non-cleanup work, invalidate tickets/actions and clear observation. If windows remain, transfer them to a minimal process-local retiring cleanup owner, with its own removal scheduler and no service-action continuations. Continue slow remove/check, without re-add, until all detach or the process dies. Dispose/release listeners, WindowManager/View records and scheduler then. Never set `instance` back to the retired service to keep cleanup alive.
- Make retirement an explicit ownership transfer, not two loops. The cleanup owner captures window handles/removal ports, not a service closure for routing, collection or rendering. Views/managers necessarily retain context while unresolved; this intentional exceptional retention ends at detach. A process-local registry admits at most one retiring episode; reconnect must reconcile it before installing any new windows. If an old unresolved owner exists, the new service remains inert for admission. Process death destroys the registry; do not claim to recover those Java handles after restart.
- Reconnection (`onServiceConnected`, 160–170) must not reopen a closing/retiring gate or reset its retry budget. Fresh user consent does not resurrect a retired external action. Cleanup without consent reads only Doom-owned attachment state, never Instagram nodes.

A live slow loop cannot repair a blocked Binder call, a dead main looper, or process death. Do not move View/WindowManager work to a background thread or promise a second Handler solves those cases. Layer 3 is essential precisely because recovery scheduling has these limits.

## 6. Edit map for implementation review

Paths are under `app/src/main/java/com/chardy/doom/` unless stated otherwise.

| File / exact current anchor | Planned change |
| --- | --- |
| `DoomAccessibilityService.kt:34–45,129–158` | Adapt existing `OverlayPlatform`, installer/updater seams to the remover ports, add a private disable seam for wiring tests; keep root/router APIs unchanged. |
| `service:51–54,66–74,87–92,139–141` | Slow interval, deferred-disable gate, window-set ownership, retry owner. |
| `service:172–485,732–807,979–1055` | Replace single-view presence with whole-episode ownership; all-window installation transaction and unchanged admission/clock semantics. |
| `service:810–833` | Safe window-layout updates on configuration/insets, closing/stale guards. |
| `service:907–977` | Separate timer behavior cancellation from removal scheduling; no timer-driven attached-window disable; no unsafe gate handoff. |
| `service:985–997,1101–1157` | Wire all three action windows to the same token. Explicit UI requests require visible authority; internal safety requests may strengthen an already-closing episode. Close once and enqueue once. |
| `service:1159–1238` | Token-first validation, all-window bounded fallback/removal, fast/slow scheduling, all-detached confirmation; preserve trace truthfulness. |
| `service:1240–1475` | Audit every action branch and detached authority for all-window barrier and stop veto; keep router and cooldown semantics. |
| `service:1514–1520,1640–1676` | Render only visible owner; interruption/unbind/destroy preserve or transfer cleanup rather than dropping it. |
| `OverlayRemovalPolicy.kt:6,42–70` | Fast/slow decisions, saturating count, permanent exhaustion veto until detach. |
| `OverlayCallbackGuard.kt:5–48` | Clarify episode ownership; preserve closing removal authority and final detached-consumption semantics. |
| `EntryGateOverlayView.kt:26–67` | Separate visual/button roots, transparent full-screen decoration, explicit interaction close/dispose, bounded accessible layout. |
| Proposed `OverlayWindowRemover.kt`, `OverlayRemovalRetryLoop.kt`, `GateOverlayWindowLayout.kt`, `GateOverlayWindows.kt` | Small pure helpers for removal, scheduling and geometry; Android window records/adapters kept separate. Place retiring cleanup owner beside window owner, not in observation/trace stores. |
| `scripts/test-entry-gate-host.py:12–54` | Register new pure source files, test files and JUnit class names in all three explicit lists. Do not include Android-dependent adapters in host compilation. |
| `scripts/test-structural-lifecycle.py`; `README.md`; `docs/WIL-149-VALIDATION.md` | Replace obsolete disable-on-exhaustion/single-window assumptions, describe pass-through reminder and manual evidence limits; retain WIL-222/source/consent guards. Do not rewrite historical executed evidence as a new pass. |

If extracting helpers changes line numbers, the PR should retain this symbol-based mapping. No changes to `InstagramMessagesRouter.kt`, classifier selectors, permissions, dependencies, or signing/evidence contracts are planned.

## 7. Tests — existing JUnit style, real production helpers

Use existing `org.junit.Test`, `org.junit.Assert.*`, small fake ports and explicit method-style test names, as in `OverlayRemovalPolicyTest` and `OverlayCallbackGuardTest`. No new mocking/Android runtime dependency is needed for pure tests. Use a virtual scheduler and operation log; no sleeps, real Handler, real WindowManager, synthetic claimed platform output, or duplicated test-only implementation of the algorithm.

### Pure JVM tests under `app/src/test/java/com/chardy/doom/`

1. Update `OverlayRemovalPolicyTest.kt:18–24,111–117`: replace disable expectations with `retryBudgetEntersSlowRecoveryWithoutReleasingAction` and `slowRecoveryNeverRestoresVetoedAction`. Add `repeatedFailuresStaySlowAndCounterSaturates`, `requestsCannotReplenishFastBudget`, `lateDetachPreservesResetOutside`, `lateDetachAfterExhaustionOnlyBypasses`, and `confirmedDetachResetsForNextEpisode`. Cover all action enum values and retain current priority/consent-safety tests. Test boundary before/at/after the threshold with a small injected budget and the production budget.
2. New `OverlayWindowRemoverTest.kt`: `alreadyDetachedNeverReadds`; `removeThrowsButDetachesIsSuccess`; `removeReturnsButStillAttachedRunsFallback`; `removeFailureRunsAddThenSecondRemove`; `alreadyAddedExceptionStillRunsSecondRemove`; `addAttachesThenThrowsStillGetsRemoved`; `fallbackCannotReenableInteraction`; `bothRemovesFailReturnsRetry`; `unknownAttachmentNeverConfirms`; `unboundOwnerNeverReadds`; `partialInstallFailureIsStillOwned`. Assert exact operation order, maximum calls, saved view identity, and no new install/admission effects.
3. New `OverlayRemovalRetryLoopTest.kt`: `oneQueuedRetryPerOwner`; `stormDoesNotPostponeRetryOrAccelerateSlowLoop`; `staleRetryCannotCancelReplacementTask`; `lateDetachStopsQueueExactlyOnce`; `safetyOverrideDuringSlowRetryIsIrreversible`; `retirementTransfersRatherThanDuplicatesRetry`; `reconnectCannotAdmitOverRetiredWindows`; `noWindowStopCanDisableButUnknownAttachmentCannot`. Verify fast/slow delays with virtual time and no activity past confirmed detach. Retirement/disable decisions must be exercised through production-owned pure coordination, not invented in a test fixture.
4. Extend `OverlayCallbackGuardTest.kt`: `closingKeepsRemovalAuthorityAcrossSlowRetries`, `fallbackKeepsSameEpoch`, and `staleDetachOrButtonCannotAffectReplacementEpisode`. Keep existing token/consumption tests. Integration tests below verify stale callbacks do not cancel the new retry, which guard-only tests cannot prove.
5. New `GateOverlayWindowLayoutTest.kt`: `onlyThreeBoundedButtonRegionsAreTouchable`; `visualRoleAlwaysPassesInput`; `gapsAndSystemInsetsAreOutsideButtonRegions`; `largeFontLandscapeFailsOpenInsteadOfFullscreen`; `rotationRecomputesWithinSafeArea`; `recoveryRoleIsInvisibleAndNonTouchable`. Table-test portrait, landscape, short/zero viewport, cutout, navigation/IME insets and oversized label measurements. Pure descriptor/rectangle assertions are not proof that Android applies flags; test the adapter separately.
6. Retain/extend `InstagramEntryGateTest.kt` WIL-222 tests: `reportedTruncatedThreadSignatureBypassesBeforeAdmission`, `messagingSampleCannotCommitBypassAfterOverlayAdmission`, `staleOrRevokedMessagingSampleCannotBypassNewerEpisode`; preserve terminal-success cooldown and configurable deadline tests. Add whole-window delayed-detach scenarios in the coordinator/service tests, not into a gate policy that cannot observe WindowManager.
7. Extend `OverlayRemovalTraceTest.kt` only for changed wiring expectations: first exhaustion freezes an incomplete snapshot once, repeated slow retries do not overflow/overwrite it, late detach does not turn the frozen exhaustion into a false success, revocation still clears. No persistent trace assertions or private data.

### Production wiring tests in existing `app/src/androidTest`

These are future Android tests, not runnable on this VPS. Extend `EntryGateServiceActionTest.kt`, using its existing `ActivityScenarioRule`, private reflection/seams, synthetic roots and main-thread assertions. Its `FakePlatform` at 68–110 already has per-view attachment records but `attach()` asserts no overlapping windows; replace that assumption with exactly one gate episode containing the expected roles and no gate/timer overlap. Record attachment and add/remove/update results per identity, not a single Boolean that can accidentally detach every window together.

Required tests:

- Real `installOverlay` creates four correctly flagged/sized windows; fresh authority is rechecked across partial adds; no completion clock before full successful install. Add failure at every position, including attach-then-throw, removes all attempted roots.
- Tap each actual wired button, not just reflective `requestOverlayRemoval`. Hold one button window attached while the visual and other buttons detach: route/Home/Debug/completion remain zero. Final detach permits at most the current authorized action once.
- Fault-script first remove → add → second remove success and repeated permanent failure. At exhaustion, disable-call count remains zero, one slow retry survives, no external action/cooldown/timer attach escapes. A later actual detach commits only safety bypass/reset.
- Update-to-non-touchable failure must not skip removals. Re-add failure must not skip the second remove. Check real LayoutParams sent to installer on every fallback.
- Replay stale completion/retry/detach/button callbacks against a replacement owner; they cannot remove/re-add its windows or cancel its queued cleanup. Retain current stale tests around 453–484.
- `disableObservation`, interrupt, unbind, destroy, reconnect, timer exhaustion and timer-to-gate handoff each preserve/transfer one cleanup chain and veto external actions. Verify the sole disable seam is called only after all owned/retiring gate and timer windows are detached.
- Preserve `preAdmissionMessagingNeverInstallsOrArmsOrReleasesActions` (599 onward) and `visibleGateMessagingWaitsForPhysicalDetachAndClosingEventsDoNotRecollect` (617 onward). Add prolonged slow closing with many IG messaging events: no collection, fresh ticket, window install, deadline reset, route or cooldown.
- Keep route result, foreign/root/consent races and root recycling assertions. No broad traversal or new selector as a removal workaround.

Extend `EntryGateOverlayUiTest.kt` for separate-root layout, all three reachable buttons, transparent visual background, normal/reduced animation, 2.0 font scale, rotation and disposal/listener cleanup. Adapt existing synthetic screenshot composition deliberately; do not remove safety assertions or change canonical signer eligibility to make old single-root evidence pass. Update source guards as supplemental wiring checks, never as substitutes for the runtime cases above.

## 8. WIL-222 and other regression risks

- `1ac78a8` adds bounded classification of a still-visible current episode (`service:342–374`) and pre-admission MESSAGING bypass (`InstagramEntryGate.observeInstagram`). It does not modify the removal retry/disable mechanism. It may expose another removal timing, but there is no evidence it caused this phone wedge. Do not revert it speculatively.
- Pre-admission DM bypass still installs no gate windows and earns no cooldown or actions. Visible-gate DM detection still requests safety BYPASS and commits only after the entire window set detaches. Missing/unknown/truncated non-messaging samples do not become positive DM evidence.
- Closing means no further classification/collection even if only a button window remains. A recovery re-add is not visible admission; it cannot reopen `acceptsVisible`, reset shown time, or arm cooldown.
- Preserve `com.instagram.android:id/direct_tab` as the only routing target, one-shot after confirmed detach and fresh authority. No re-add/remove retry may retry the router. `CLICKED`/`ALREADY_SELECTED` retain existing terminal-success credit; failed route, Home, Debug, auto-DM bypass, exhaustion and interruption do not earn credit.
- Extra windows produce extra Doom-owned events and may change active-root attribution. Preserve existing own-event handling, foreign-root veto and 150 ms uncertainty bound; do not whitelist every Doom root or relax watchdog timing to hide a layout regression.
- Pass-through touches can navigate Instagram beneath the reminder, so WIL-222's fresh messaging detection and genuine foreign-app cleanup become more important, not optional. Never auto-route because the underlying user navigated to a DM.
- Timer and gate must remain mutually exclusive as episodes. Partial gate detach must not attach a timer. Timer exhaustion must not disable the service while gate recovery exists. No new gate after terminal timer teardown.
- Extra add/remove calls, reentrant callbacks and event storms can leak windows/listeners or produce duplicate actions. Use identity ownership, a single epoch, guarded postconditions after platform calls, and one scheduled retry. No new nodes are read in removal or layout.

## 9. Execution boundaries and manual device verification

This planning session did not run Kotlin/JUnit, Gradle, Android, an emulator, adb, instrumentation, signing or phone automation. Source/AOSP inspection and the document write are not fix validation.

Future implementation checks, in cheapest-first order:

1. On an authorized host, run the registered pure suite with `python3 -B scripts/test-entry-gate-host.py`, then relevant source/evidence/signing host suites and `git diff --check`. The helper's explicit file/class lists are not equivalent to the full Gradle suite.
2. In configured Codex Cloud or GitHub Actions, run `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` with the checked-in Gradle wrapper and pinned environment. Record actual exits/reports. No Android builds are requested on this VPS.
3. Emulators and `connectedAndroidTest` are forbidden on this VPS. Do not install/start an emulator, invoke CI scripts locally, spoof `GITHUB_ACTIONS`, or claim source/fake-platform tests prove input dispatch. Existing canonical Actions instrumentation/signing gates remain unchanged and require separate authorization to dispatch.
4. WIL-235 phone verification is manual. Use an approved exact-candidate signed build; do not use passing fixture/emulator results as Instagram/OEM proof. No phone command, private screenshot, hierarchy dump or log capture is authorized by this plan.

### What cannot be proved without a device

Actual WindowManager registration versus `isAttachedToWindow`, whether the re-add cycle helps this OEM failure, touch delivery through accessibility windows, multi-window z-order, system power/shade/keyguard visibility, gesture navigation, TalkBack traversal, real layout/insets/IME, real Instagram selectors and root timing, and callbacks during user disable/process death. A fake returning false for attachment proves only the fake. A main-thread wedge must be survivable by initial window design without requiring a flag update, retry, trace write or callback.

### Manual matrix (record candidate SHA/build, confirmed device/OS/Instagram versions and pass/fail only)

1. First verify on a non-primary/test device or otherwise agreed safe setup that the healthy visual leaves navigation/system surfaces visible and touchable, and each of the three buttons works. Do not begin with deliberate hangs on a phone without an agreed recovery method.
2. Perform 20 eligible Go-to-message trials across cold/warm launches and two sessions, with both immediate and near-completion taps. Respect actual configured cooldown; suppressed openings are not trials. Zero stuck blocking gates, duplicate routes or premature routes. This is a bounded regression gate, not statistical proof of “never.”
3. Manually test inbox/thread/composer and notification-opened DM entry, plus Feed/Reels-to-DM transitions: preserve WIL-222 bypass and no automatic routing/cooldown. No message content is collected as evidence.
4. During a visible reminder and an intentionally retained inert gate from a separately approved test-only failure harness, use Back/Home/Recents, notification shade, power menu (do not select reboot), Settings/app switch, lock/unlock, portrait/landscape, keyboard and large font/TalkBack. All system escape paths must remain usable with cleanup callbacks unavailable; blank/opaque coverage of critical controls fails acceptance even if touches technically pass through.
5. A test-only harness may script remove failures/no-detach and a bounded pause of gate callbacks on synthetic Doom-owned screens, with a prearranged manual recovery. Keep fault injection out of release builds and out of exported endpoints. Verify fast exhaustion → slow retries, independent usability, and eventual detach without any old action when failures are released. Do not deliberately deadlock the system or leave an unbounded main-thread block.
6. Manual Stop Observation, OS accessibility disable/re-enable and process restart: no visible re-add during shutdown, no stale action on reconnect, no abandoned whole-device interception. Distinguish OS-driven teardown from app-confirmed detach; process loss does not yield a recovered trace. If exact request/detach timing is unavailable, record it as unmeasured rather than inventing milliseconds.

If actual wedge evidence becomes available under separate authorization, inspect only Doom-owned window/input/attachment categories and service lifecycle, not foreign tree contents. That can distinguish a live-service removal failure, local/server attachment disagreement and a stalled service. Until then label the platform cause unresolved; do not assert `disableSelf()` was actually observed during the incident.

## 10. Acceptance ledger from WIL-235

All boxes remain unchecked in this plan. Wording below comes from the saved issue creation response described in section 1.

| WIL-235 criterion | Required evidence / disposition |
| --- | --- |
| “Root cause: identify why the gate overlay can survive the NAVIGATE_MESSAGES removal transaction with the service unable to clean it up” | Source-backed failure chain in section 2 plus red/green fault-injection tests through production removal/lifecycle wiring. Separately identify the device/platform failure using manual evidence if reproducible; source reachability alone does not prove the phone cause. Record unresolved OEM/main-thread/server-state questions honestly. |
| “Add a persistent (survives restart / process death) removal-trace or watchdog fallback so a stuck overlay can never permanently lock the device” | Same-view fallback, live slow cleanup, and initially non-blocking window geometry are the agreed safety alternative. Prove no fullscreen touchable window in any install/recovery path and manual escape with callbacks unavailable. Explicit owner reconciliation is required: the Handler/trace do not survive process death, and no persistent trace is implemented by this scope. |
| “Verify on real device (Pixel 11 / Android 11) that the Go-to-message flow can never leave a blocking overlay” | Confirm the conflicting device identity first; execute and record the manual matrix for the exact candidate. Require zero whole-device blocks, usable system UI during induced failure, all-window detach before action, and WIL-222 behavior intact. Keep claims limited to the tested candidate/device and structural window invariant. |
| “Linked as regression follow-up to WIL-222” | WIL-222 is referenced in this plan, the saved issue body and implementation/PR description. Have the controller verify/create the actual Linear related-issue relation if required; a text reference is not proof of a native relation. No Linear mutation occurred here. |

Additional review acceptance:

- No `DISABLE_SERVICE` decision remains; the single guarded voluntary-disable path cannot run with attached/unknown gate, timer or retiring windows.
- Every unsuccessful immediate removal gets the bounded fallback while bound; removal-only retries continue after retirement. No detached window is gratuitously re-added, and every recovery re-add is invisible/non-touchable.
- Every owned window remains accounted for through partial installation, partial detach, lifecycle and configuration failure. One slow retry per owner survives event storms and safety cleanup; stale callbacks cannot cancel it.
- Messages/Home/Debug/completion are released at most once and only after the all-window barrier plus current authorization. Slow-recovery exhaustion permanently vetoes the original external action.
- No new selector, permission, privacy exposure, persistent report/trace, background service, route retry, cooldown semantics change or signing bypass.
- Unit/service/UI tests have actual executed results in their permitted environments; manual evidence and acceptance-wording reconciliation remain mandatory for closure.

## 11. Implementation sequence and review gates

1. Add red tests for fast-budget degradation, ordered fallback, all-window barriers, stale-retry cancellation and lifecycle retry loss. Extract the small production-used pure helpers; demonstrate green tests without Android emulation.
2. Implement the safe window split and whole-episode ownership first, including partial-add cleanup and transparent presentation. Do not distribute a retry-only patch that retains fullscreen touch interception.
3. Wire hardened gate and timer removal, slow scheduling, deferred explicit disable and retiring-owner lifecycle. Review every `disableSelf`, retry cancellation and gate/timer presence check; no blanket Handler cancellation may discard physical cleanup.
4. Adapt WIL-222/service/UI tests and host/source guards; run approved non-emulator preflight. Independent review must challenge fallback duplicate-add handling, callback reentrancy, incomplete teardown, action veto and escape-surface geometry.
5. Run separately authorized canonical CI/signing without weakening evidence contracts, then manual device acceptance. Keep the issue open for unresolved platform causality, failed system escape, missing real-device evidence or the persistence wording conflict. No merge/release follows automatically from this plan.
