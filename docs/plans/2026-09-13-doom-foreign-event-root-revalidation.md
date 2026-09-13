# Doom WIL-182: final diagnosis and minimal repair plan

Planning only. This document is the sole authorized write. No implementation, git commands, tests/builds, signing, merge, or subagents were run. Implementation and validation below are future work, not reported results.

Repository: `/mnt/HC_Volume_106820083/worktrees/doom-wil182-watchdog`.
User-supplied HEAD: `28e1cfb577c1e062e2e47d801f0f99b33fcc3704` (not independently checked because git was prohibited).

## 1. Final diagnosis and evidence sufficiency

The build-37 trace is sufficient causal evidence to repair the unconditional foreign-event reset. It identifies `EVENT_PACKAGE_RESET` on a STATE event attributed OTHER, immediately followed by `CLOSING`, then confirmed `DETACHED` and policy release of `RESET_OUTSIDE`. The user reports no interaction. The preceding watchdog confirmed an IG root 17 ms before the reset, as supplied in the report.

Source confirms the entire causal chain in `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`:

- Lines 132–135 reset on every non-Instagram attribution after the separate Doom-owned-event handler, including missing attribution. No active root is read.
- Lines 618–628 request RESET_OUTSIDE safety cleanup; lines 422–441 invalidate visible callbacks, dispose UI and begin physical removal.
- Lines 444–521 confirm attachment loss before freezing the trace/releasing policy action; lines 582–586 clear the Instagram session, ticket and report.
- The independent 150 ms last-safe watchdog cannot protect against this earlier event branch. Instagram cooldown suppression at lines 137–142 also does not cover it.

This is an event-attribution-as-foreground-ownership bug, not evidence for changing the breathing timer or Messages routing. STATE must be included in the repair; a CONTENT-only exception would miss the observed trigger.

Evidence limit: `root=NOT_READ` means ownership at the reset instant was not measured. The preceding IG sample does not prove that the active root was still IG at that instant, nor does OTHER identify System UI. The trace proves the removal source, not the platform's reason for emitting that event. This does not block a repair that reads current ownership and preserves immediate removal for a genuinely foreign root. Do not request another diagnostic trace. Post-repair functional acceptance is separate from causal diagnosis.

This plan supersedes the conditional CONTENT-only policy, unconditional STATE reset, immediate unknown-event reset, read throttling, and additional pre-fix trace requirements in sections 5, 7E and 9 of `2026-09-13-doom-breathing-removal-trigger-diagnosis.md`. Leave that historical document unchanged. Its explicit beta deferrals remain deferrals, not delivered privacy/CI redesigns.

## 2. Smallest safe behavior change

Change the non-Instagram branch only for an already admitted, current, visible gate. Reuse the existing `foregroundWatchdog` instance and its last-safe policy; do not introduce another arbiter state machine, grace timestamp, package allowlist, dependency or configuration switch.

### Entry and authority

1. Before any visible-gate retain/ignore decision, require observation consent, gate consent and connection. Loss requests existing BYPASS safety cleanup immediately, without a root read or grace. Put this small visible-overlay preflight before the own-event and cooldown returns so they cannot mask revocation. Preserve existing revocation APIs and lifecycle cleanup.
2. Preserve the separate Doom-owned-overlay event handling at service lines 112–130: ordinary own updates are not foreign-event evidence and never refresh safety; positively identified MainActivity return still removes/preserves through the existing path. Do not expand this into a Doom-root exemption.
3. For all remaining non-Instagram events, including STATE, CONTENT, OTHER, unattributed and null events, use fresh root revalidation only when the overlay exists, its current token passes `acceptsVisible`, its ticket matches the service ticket/current generation, and the gate is GATING. Capture that token/ticket/view before the read. Do not bypass revalidation because Instagram cooldown is active.
4. With no visible gate, retain the existing conservative reset/session behavior without new root access or admission. While already closing, preserve the existing safety override/veto behavior; never restart visibility or grace. An in-flight result for a replaced/stale episode is discarded, not sent through a fallback that resets the replacement.

### One root, package attribution only

Use `OverlayPlatform.eventRoot()` for the event revalidation. Production `eventRoot()` propagates acquisition failure; `currentRoot()` currently catches RuntimeException and returns null (service lines 77–85), so blindly reusing that method would mislabel a read failure as NO_ROOT.

Factor the existing package-only sample/recycle pattern at service lines 654–675 into a small private adapter if useful, with an explicit acquisition function. The event and watchdog paths should share ownership/recycling rules, but not call the whole watchdog tick from an event: that would render/repost work and mislabel its trace source.

- Acquire once. Read only `root.packageName`, compare transiently with Instagram/Doom and reduce to IG, DOOM, FOREIGN, NO_PACKAGE, NO_ROOT or READ_FAILURE. Retain no raw package or node beyond this call. Decisions must work identically with trace disabled.
- NO_ROOT means acquisition returned null. NO_PACKAGE means an acquired node has no package. Acquisition or package-getter RuntimeException means READ_FAILURE. No exception message/type/stack is recorded.
- Every acquired root has exactly one `overlayPlatform.recycleRoot` invocation in `finally`, including foreign, null-package, getter-failure, revocation and stale-result paths. No recycle when acquisition yielded no node. Never also pass this root to `collect`, the router or another owner.
- Contain a recycle exception separately; do not retry recycling or let it erase an already obtained foreign classification into graced uncertainty. A successful ownership classification remains that classification; the trace does not claim recycling succeeded.
- After acquisition/classification/recycling, recheck current episode identity and all consent/connection flags before mutating the shared watchdog, emitting a decision record, or requesting cleanup. A stale result must not refresh the replacement's last-safe timestamp. Revocation for the current episode wins even if the sampled root was IG.

### Decision table

Use monotonic time at the sample boundary, not event creation time or a cached event/root. Feed the package-attribution result to the existing shared `OverlayForegroundWatchdog.observe`; use `verifiedDoomReturn=false` for this foreign-event path. A real Doom return is already handled separately.

| Fresh result / condition | Required effect |
| --- | --- |
| IG and valid clock/current authority | KEEP. Treat this event as transient; a genuinely fresh IG observation may refresh the shared last-safe timestamp. Return without collection, render, admission, action or resetting any timer. |
| FOREIGN or DOOM | Request RESET_OUTSIDE safety cleanup in this callback, regardless of how recent the last IG sample was. No debounce, second confirmation or grace. |
| NO_ROOT / NO_PACKAGE / READ_FAILURE, last-safe age <150 ms | KEEP_UNCERTAIN using the existing last-safe anchor. Do not refresh it. Return with the existing watchdog and completion callback still scheduled. |
| Same unknown results, last-safe age >=150 ms | Request BYPASS safety cleanup in this callback. Unknown ownership is not proof of leaving Instagram; do not fabricate RESET_OUTSIDE/foreign evidence. |
| Missing safe anchor, negative time or rollback | Immediate BYPASS; retain the watchdog's conservative clock rules. A known foreign root still requires immediate removal. |
| Consent/connection loss or accepted lifecycle safety cleanup | Immediate safety cleanup/veto, regardless of root or elapsed time. |
| Stale token/ticket/view after the read | Recycle acquired root and discard the result; no decision, trace, scheduling or action against a newer episode. |

`OverlayForegroundWatchdog.kt:33–80` already implements the last-safe arithmetic and exact `<150` boundary. Keep one instance: a fresh watchdog IG sample protects a subsequent unknown event, and a fresh event IG sample protects a subsequent unknown watchdog tick. Unknown event floods, missing-package nodes, own events and read failures must never call `reset` or advance last-safe time.

Keep the existing 50 ms watchdog chain alive and unchanged on KEEP/KEEP_UNCERTAIN; do not cancel/repost it per event, throttle by reusing old IG evidence, or post another independent 150 ms grace timer. With no further events, the existing watchdog performs the expiry check. At any event/tick observing uncertainty at age 150 ms or later, cleanup is immediate. This is a sampled-policy bound, not a guarantee of physical detach exactly at 150 ms under Android/main-thread scheduling delays. A delayed callback evaluates actual elapsed time, never grants a new interval. No synchronous spin/retry or sleeping.

Keep five seconds from accepted SHOWN, cooldown, completion token checks, retry budget, physical detach authority and external-action veto unchanged. A retained event cannot grant credit, clear/replace the report, rearm trace, or create a new session.

## 3. Truthful, bounded diagnostics

Extend `OverlayRemovalTrace.kt` with proposed non-cause marks `EVENT_ROOT_SAFE` and `EVENT_ROOT_UNCERTAIN`. Add proposed cause marks `EVENT_UNCERTAINTY_EXPIRED`, `EVENT_ROLLBACK`, and `EVENT_NO_SAFE_ANCHOR` to the recorder's cause set. Use existing `EVENT_ROOT_MISMATCH` with action RESET_OUTSIDE for a fresh attributed foreign root, and `EVENT_DENIED` for denied visibility. Keep `EVENT_PACKAGE_RESET` for the unchanged reset-without-revalidation branches.

- Event records retain event-kind and event-owner categories; `root` describes the root actually read. OTHER event + IG root must remain distinguishable. Do not substitute root owner for event owner.
- KEEP and KEEP_UNCERTAIN have action NONE and are not first-cause candidates. A tolerated READ_FAILURE is `EVENT_ROOT_UNCERTAIN/root=READ_FAILURE`, not a false `EVENT_FAILURE` removal cause.
- Only an accepted cleanup records a cause/action and CLOSING. Event decisions must not be labeled WATCHDOG_SAFE/FOREIGN. `NOT_READ` is valid only where the read was skipped; never infer IG from the preceding watchdog row.
- Advance the fixed serialization header to proposed `WIL182_REMOVAL_TRACE_V2` for the new vocabulary and update serializer/UI assertions. Preserve historical V1 meanings, six fields, enum-only payload, bounds/coalescing, first-cause protection and terminal reservation.
- Preserve DETACHED versus ALREADY_DETACHED versus exhaustion, original cause versus later safety override, and ACTION_RELEASED as policy release—not evidence that navigation executed. Do not mark detach on disposal, a remove call, exception, or service-disable request alone.
- Trace enabled/disabled and recorder failure must not alter the revalidation decision or root-read count. Revocation still erases armed/captured/frozen trace state; do not retain a public revocation trace as an exception.

## 4. Implementation order and files

1. Add a red-capable production-service regression in `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`: use the existing installer/platform seams to obtain an admitted attached overlay with cooldown and seeded watchdog, establish IG safety, then deliver OTHER+STATE with an IG root. Assert the same overlay remains attached with no removal. Baseline must fail this assertion through EVENT_PACKAGE_RESET. Existing `fixture()` at lines 773–809 does not itself admit cooldown or seed the watchdog; do not mistake it for the full setup. The existing armed/unarmed foreign-event test at lines 392–414 intentionally expects the old OUTSIDE result and needs the new expected behavior.
2. Implement the small guarded event revalidation and root-ownership adapter in `DoomAccessibilityService.kt`, plus the trace vocabulary/version updates in `OverlayRemovalTrace.kt`. Reuse `OverlayForegroundWatchdog.kt` without changing grace semantics. No new policy file is needed. Add only private test clock/package-read seams if required to exercise exact times and getter failure; no exported hooks.
3. Extend the existing watchdog, trace, service-action and trace-UI tests. Replace the obsolete unconditional-reset assertions in `scripts/test-structural-lifecycle.py:15–25,241–250` with visible-root-revalidation/no-collection assertions and no-visible-root-read assertions. Update `scripts/test-removal-trace.py` to guard the new adapter and vocabulary, not to conceal behavior regressions.
4. In subsequently authorized Actions validation, demonstrate baseline RED and repaired GREEN for the new service test, then run the app unit/lint and instrumentation suites plus the source/privacy guards. Relevant existing targets are `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:connectedDebugAndroidTest`. No test, build, signing or CI execution is authorized by this planning turn.

## 5. Required acceptance matrix

| Case | Assertion |
| --- | --- |
| Observed STATE trigger; OTHER/System UI-like event over IG root | One fresh root read/recycle, same attached view/token/ticket, no CLOSING, no collect/route/Home, fixed completion deadline. Also cover CONTENT, OTHER, unattributed and null event inputs. System UI-like is synthetic test attribution, not an allowlisted package. |
| Actual switch-away | Same event inputs with FOREIGN root immediately request RESET_OUTSIDE and detach on a healthy removal adapter, even just after IG safety or during uncertainty. DOOM root without the separate return path does likewise. Zero external action. |
| Unknown at 149/150 ms | Parameterize NO_ROOT, NO_PACKAGE and READ_FAILURE: keep at 149, BYPASS at 150; repeated uncertain events cannot move the boundary. Assert through the service entry point as well as pure policy. |
| Acquisition/getter failure | Acquisition throw becomes READ_FAILURE with zero recycles; package getter throw after acquisition becomes READ_FAILURE with one recycle. Same bounded rule, not the outer catch's unconditional cleanup. No failure details escape. |
| Shared last-safe state | Watchdog IG then unknown event; event IG then unknown watchdog; uncertain event/tick alternation; fresh IG recovery; no anchor; rollback; delayed callback past expiry. Both sources use one anchor and one watchdog chain. |
| No subsequent events / event storm | Watchdog still expires continuing uncertainty without more events. Floods do not postpone it, add chains, reset grace, or extend five visible seconds/cooldown. Foreign confirmation interrupts any flood immediately. |
| Stale token | Replace/close the episode during injected root read and invoke old watchdog/completion/retry work. Root released once; old result cannot refresh, remove, render, trace, grant or route for replacement. |
| Consent revocation | Observation consent, gate consent and connection lost before/during root read: immediate current-episode cleanup, no safe refresh or external action. Use real revocation entry points with the service instance wired, not only detached mock flags. Store remains empty after revocation. |
| Closing/action race | Foreign safety or revocation overrides pending Messages/Home/COMPLETE during delayed physical removal. No restart or budget replenishment; original cause remains, veto/final action truthful. |
| Root ownership | IG, FOREIGN, DOOM, no package, getter failure, stale result and revocation each recycle exactly once if acquired. Null/acquisition failure recycle zero. Throwing recycle is attempted once and cannot turn known foreign into grace. |
| Trace correctness | Event owner and sampled root remain separate; retained outcomes have action NONE and no cause/CLOSING; expiry identifies the unknown category; fresh foreign cause has the observed root. Armed/unarmed decisions, read counts and physical outcomes match. Bounds/version/revocation/terminal tests pass. |
| Existing behavior | No-visible foreign reset does not read a root; own-overlay events do not renew safety; positive Doom return removes; IG cooldown still suppresses collection; normal completion waits five visible seconds; failed removal/exhaustion releases no external action. |
| Direct-tab exception | Existing routing policy and Android router/service-action tests remain unchanged in meaning: explicit Skip only, confirmed physical detach, current consent/ticket and IG-root recheck, exact `com.instagram.android:id/direct_tab`, unique actionable match, at most one click; selected/absent/ambiguous/unactionable matches do not click. No fallback or passive routing. |

Use deterministic time and controlled platform attachment for exact boundary assertions; source regexes and pure-policy tests alone are insufficient. After separately authorized implementation, check ordinary no-touch completion and deliberate switch-away on the phone, plus the already-working Messages action. No additional pre-fix trace is necessary. Do not claim new phone/build/test validation until it exists.

## 6. Privacy boundary and non-goals

The new revalidation path reads package attribution only: no event source, text, descriptions, classes, resource IDs, descendants, windows/focus traversal, screenshots, structural report collection or node actions. It introduces no logging, persistence, network, automatic clipboard/export, permissions or service capability. Only the existing explicitly armed, bounded process-memory enum trace is extended. Raw phone trace rows are not copied into this plan.

The current source still contains the previously authorized legacy structural collector/report UI and own MainActivity class predicate; the old plan explicitly deferred replacing them. This minimal repair does not broaden or invoke them on foreign-event revalidation and does not falsely certify the whole app as package-only. Existing consent preferences are not new runtime-evidence persistence. Do not bundle collector/UI/Activity-signal/CI/signing redesigns into this behavior fix. The exact Direct-tab user-action exception remains untouched.

Residual risk: Android's active root is an instantaneous platform sample, not omniscient foreground truth. An IG root briefly lagging a real transition may retain until the next watchdog observation; a confirmed foreign root must never be graced. Unknowns can still cause an intentional fail-open disappearance at the unchanged bound. Neither limitation justifies blind event suppression, a broader allowlist or another rolling grace period.
