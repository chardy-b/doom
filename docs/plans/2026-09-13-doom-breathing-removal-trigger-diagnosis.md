# Doom WIL-182 breathing-removal trigger diagnosis plan

> Planning only. Astra inspected the repository directly; no subagents were launched. This document is the only authorized write. Implementation, tickets in an external tracker, commits, pushes, CI dispatch, signing, distribution, merge and release require subsequent authorization. Do not execute the generic planning skill's subagent or commit instructions.

**Goal:** Identify the first causal removal trigger on the real phone, then fix only a demonstrated trigger without weakening removal over a genuinely foreign foreground app.

**Architecture:** Introduce a small, separately owned, opt-in, process-memory-only episode trace at existing decision/removal boundaries. Preserve the baseline gate decision policy during diagnosis. Only after evidence and a red-capable production-service regression exist, permit a narrowly scoped event/root arbiter to distinguish a demonstrated background content event from actual foreground departure. Keep the existing physical-removal authority, callback tokens, five-second gate and exact Direct-tab action.

**Explicit beta decision for this diagnostic PR:** the legacy structural report, its report-copy flow, and synthetic screenshot evidence remain unchanged. This PR repairs the enum trace and its runtime/UI tests only; it does not implement the plan's proposed trace-only replacement collector, Activity-signal redesign, no-screenshot CI/signing contract, or D4 artifact redesign. Those remain separately authorized follow-up work and must not be represented as delivered here.

**Stack:** Existing Kotlin 2.0.21/JVM 17, Android Views for the service overlay, Compose/Material3 for MainActivity, JUnit 4.13.2 and existing AndroidX instrumentation; AGP 8.7.3, API 35. No new runtime dependency, permission, service capability or exported endpoint is needed. All Android, Gradle, emulator and signing execution belongs in GitHub Actions, not on the agent host. Human installation/use of an Actions-built APK is the separate real-phone acceptance step.

## 1. Baseline, authority and evidence limits

Repository: `/mnt/HC_Volume_106820083/worktrees/doom-wil182-watchdog`.

Verified HEAD: `c16864cedef9ed68a8577f00dc2bf194f234e1ce`.

Verified branch: `ryli721/wil-182-watchdog-transient-root`. Worktree was clean before this plan. File references below are relative to this repository and refer to this exact HEAD, not the earlier September 12 plan. New files and symbols are explicitly identified as proposed.

User-supplied facts, accepted as context rather than revalidated CI results:

- Signed build code 35 passed baseline, API 35 emulator, fixture, protected signing and Opus review.
- Its exact Direct-tab Messages click works on the real phone.
- Opening Instagram and touching nothing still causes the breathing overlay to disappear within about one second.
- Build 34's apparent success on one attempt was not stable proof.
- A 150 ms null-root watchdog grace did not fully solve the symptom.
- Opus called out unconditional reset on non-Instagram events, including content-change events.

This planning session inspected source, tests, workflows and the watchdog commit diff. It did not run tests, Android tooling, Gradle, signing, emulator commands or phone automation; it did not query remote CI or open images. Source inspection establishes reachable mechanisms, not which callback occurred on the phone. Neither existing green checks nor a reviewer warning identifies the actual removal trigger.

### Scope conflicts that must not be hidden

The current code and historical CI contract are broader than this assignment's privacy boundary:

1. `DoomAccessibilityService.kt:174-233` walks up to 128 nodes, reading resource IDs and classes. `SanitizedStructuralReport.kt:24-46,86-126` admits previously unknown static IDs and normalized classes. It is not a content-free trigger trace. Do not reuse its builder, text or shadow-classification input as trace payload.
2. `DoomAccessibilityService.kt:518-520` currently recognizes MainActivity using an event class-name comparison. This is an existing local predicate, not permission to collect class names. Under the strict no-class-read scope of this plan, replace that input in the diagnostic path with a Doom-owned Activity lifecycle signal; do not expand accessibility class inspection.
3. `scripts/ci-device.sh:8-28`, the screenshot tests in `EntryGateOverlayUiTest.kt:93-145`, `DoomUiTest.kt`, and `CrossAppFixtureTest.kt:161-168` create screenshots. `scripts/validate-internal-signing.py:12-20,82-170` requires an exact screenshot-bearing evidence contract. Simply omitting screenshots would currently make signing validation fail.
4. The old cross-app fixture has a different service/policy, broad fixture traversal and a fixture-specific node action. It is not the production WIL-182 path and must not be copied into it.

Consequences: a WIL-182 trace-only diagnostic candidate must disable the legacy broad collector/export path, avoid class metadata, and use a separately reviewed no-screenshot CI/evidence contract. Do not quietly declare legacy report consent sufficient for a new retained trace. Do not quietly relax signing validation or fabricate required images. These are explicit prerequisite work packages, not a claimed breathing fix. If the owner does not authorize those prerequisite changes, stop before producing/distributing a diagnostic candidate and report the conflict. The plan itself does not alter any of them.

## 2. Inspected execution map and ranked hypotheses

For compactness, `service` below means `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`.

### Current flow

- Event subscription: `app/src/main/res/xml/accessibility_service_config.xml:2-6` subscribes to window state and content changes from all packages, with notificationTimeout 100. This timeout is not a proven foreground-detection latency bound.
- Own events: `service:99-115` ignores Doom overlay events, except a positive MainActivity-return predicate requests preservation/removal or resets a completed session.
- Non-target events: `service:116-118` calls `resetOutside()` for any event whose package is not Instagram, including absent package or a null event. It does not discriminate content changes from window-state changes and does not read the current root first.
- Cooldown: `service:120-123` returns on Instagram events during an admitted gate's one-minute cooldown, before consent handling, ticket allocation, root access and collection. This does NOT protect the earlier non-target-event branch.
- Admission: `service:129-168` creates a ticket, reads/validates root, collects, then observes/installs. `service:260-283` adds the view, records shown time, admits cooldown, seeds watchdog safety and posts completion.
- Watchdog: `service:284-313` immediately posts a first tick and then posts every 50 ms. It reads root/package only, recycles the root, maps RuntimeException to null, calls the pure watchdog, renders, and reschedules. A separate outer RuntimeException also fails open.
- Timing: `InstagramEntryGate.kt:84-113` starts the five seconds after addView acceptance and validates completion against elapsed monotonic time. Cooldown is armed only after successful display admission at `:91-97`; its duration is 60,000 ms (`:8-39`).
- Removal: `service:320-347` closes visible callbacks/disposes UI, requests a pending action and attempts removal. `:349-398` checks actual attachment, retries at 50 ms up to 20 failures and confirms detachment before releasing an action.
- Cleanup semantics: `service:401-405,459-470` maps BYPASS to cancellation/clear, RESET_OUTSIDE to session exit/ticket clear/report clear, COMPLETE to completion-or-cancel. `:483-493` collapses several causes into the same helper; final state alone cannot identify the cause.
- Actions: `service:239-247,406-449,472-477` wires explicit Skip/Leave and executes only after detachment. `InstagramMessagesRouter.kt:7,40-93` performs only the exact `direct_tab` lookup and at most one click; ambiguity/selected/unactionable cases do not click.
- Lifecycle: connect at `service:82-94`, interruption at `:522-526`, unbind/destroy/disconnect at `:528-544` all reach safety cleanup and report clearing.
- Report loss: `Observation.kt:71-75,114-118` clears reveal/copy state on replacement/clear. Report reveal/copy requires `consent && connected` at `:29-30`. Its lifetime and connectivity gate are unsuitable for recovering a lifecycle-removal trace after service disconnection.

### Rank 1 — Unrelated or unattributed accessibility event resets a still-valid gate

**Mechanism:** `onAccessibilityEvent` -> `packageName != INSTAGRAM` (`service:116`) -> `resetOutside` (`:491`) -> `requestSafetyCleanup(RESET_OUTSIDE)` (`:338`) -> physical removal -> `leaveInstagram`, ticket null, report clear (`:459-464`). Content events and null attribution take this same path. The 150 ms watchdog grace is never consulted. Cooldown can then prevent the removed overlay from reappearing, explaining a single disappearance rather than visible flicker.

**Why first:** It is a direct, unguarded removal path, reachable even after successful admission and during cooldown. It matches the prior review warning and does not require a timer error. An overlay that is stable in controlled tests but unstable during real app startup is compatible with additional asynchronous window/content events.

**Prediction:** An admitted/shown episode ends with `EVENT_PACKAGE_RESET`, event CONTENT (or another recorded category), owner OTHER/UNATTRIBUTED, before the normal completion deadline. A preceding watchdog may have reported IG. No accepted explicit action precedes closing.

**Falsification:** A complete episode identifies a different first closing trigger. A STATE/foreign-root event with actual user/system foreground departure is correct fail-open behavior, not proof of a false positive. CONTENT alone is also insufficient to prove the active app stayed Instagram.

**Exact proof still missing:** There is no inspected runtime test that drives an admitted production overlay through OTHER+CONTENT while supplying an IG active root and asserting the physical removal result. `scripts/test-structural-lifecycle.py:14-20` actually enforces the unconditional reset shape; a source regex is not phone-trigger evidence.

### Rank 2 — Watchdog sees an attributed non-target root, including Doom's own overlay root

**Mechanism:** `service:288-305` -> `OverlayForegroundWatchdog.observe` (`OverlayForegroundWatchdog.kt:40-51`) -> FAIL_OPEN -> BYPASS cleanup. Any non-null non-IG attribution fails immediately unless it is Doom plus `mainActivityReturnObserved`. A Doom root without the marker is deliberately NOT given null grace.

**Why plausible:** `rootInActiveWindow` need not be a durable underlying-app identity while accessibility windows change. The independent fixture expressly avoids relying on it (`FixtureGateService.kt:86-92`; `docs/FIXTURE.md:30-36`), but that is a design warning, not proof of this phone's root sequence. Do not transplant the fixture's window enumeration or assume every Doom/system root is benign.

**Prediction:** `WATCHDOG_FOREIGN` with root DOOM or FOREIGN starts closing soon after shown. The earlier event path may have ignored an own-package overlay update.

**Falsification:** Complete trace has only IG/unattributed root samples before another first closing trigger.

**Safety implication:** A non-null foreign root remains an immediate veto. If the root is DOOM without a verified local Activity return, fail open rather than whitelist Doom as safe. The present metadata scope may be insufficient to safely distinguish an overlay root from a real app transition; that is a stop condition, not permission to add window traversal.

### Rank 3 — Unattributed watchdog interval reaches the unchanged 150 ms bound

**Mechanism:** `service:288-297` conflates missing root, missing package and RuntimeException into null. `OverlayForegroundWatchdog.kt:53-58` measures from the last safe moment, seeded by shown time (`service:276`), not from the first null tick. At age >=150 ms, cleanup is requested. A gap longer than the grace or a delayed main-thread tick can therefore still remove within about a second.

**Prediction:** `WATCHDOG_UNCERTAIN` samples identify NO_ROOT, NO_PACKAGE or READ_FAILURE; the last IG confirmation and failure offset show the exact age relationship. `WATCHDOG_UNCERTAINTY_EXPIRED` starts closing. READ_FAILURE must not be exported as exception type/message/stack.

**Falsification:** A known IG root refresh occurs within the allowed interval before a different failure reason, or another path closes first.

**Do not infer:** A single tolerated null or the passing 149/150 ms boundary test does not prove the phone's gap length. Do not enlarge grace, reduce watchdog frequency or reset its deadline on each uncertain sample as an experiment disguised as a fix.

### Rank 4 — Lifecycle or runtime failure enters shared fail-open cleanup

**Mechanisms:** `service:522-544` interrupt/unbind/destroy; reconnect reset at `:85`; event catch `:169-171`; install/admission failure `:268-275,314-317`; watchdog/render catch `:309-311`. All can remove without an intentional tap. `EntryGateOverlayUi.dispose` (`EntryGateOverlayView.kt:59-69`) immediately hides the breathing pixel while removal may still be retrying, so perceived disappearance is not necessarily confirmed window detachment.

**Prediction:** Specific lifecycle/exception/admission mark precedes closing. Distinct `CLOSING` and `DETACHED` offsets distinguish early visual disposal from actual physical removal. An attach-state callback with no prior requested removal identifies external/platform detach, not a fabricated application cause.

**Limits:** Abrupt process death can erase the entire in-memory trace and need not invoke onDestroy. Empty evidence after restart cannot be labeled a lifecycle diagnosis. A visual problem while attachment remains true also requires separate presentation investigation, not relaxed foreground safety.

### Rank 5 — Completion or action-driven removal; normal completion unlikely at one second

**Mechanisms:** completion Runnable at `service:279-283`, COMPLETE handling `:465-470`; accepted Skip/Leave at `:239-247`; positive Doom return at `:100-111`. A stale closure is another possibility, but visible/removal token guards already exist and have tests.

**Prediction:** `TIMER_COMPLETE`, `USER_MESSAGES`, `USER_HOME` or `APP_RETURN` precedes closing; final released action is recorded separately. `SHOWN` to `TIMER_COMPLETE` must be compared, not launch time or the rounded UI label.

**Why last:** The user reports no touch, and completion uses a five-second post-show delay with a monotonic completion guard. `InstagramEntryGateTest.kt:20-32,57-67` covers exact visible-time semantics; `OverlayCallbackGuardTest.kt` and `EntryGateServiceActionTest.kt:209-274` cover stale authority. These reduce likelihood, not prove absence on the device.

**Action:** Preserve the working Direct-tab route. Do not modify selectors, add fallbacks or use routing as a removal workaround.

## 3. Minimal content-free trace contract

### Ownership and lifecycle

Proposed new files:

- `app/src/main/java/com/chardy/doom/OverlayRemovalTrace.kt`: pure enums, fixed-capacity recorder, immutable snapshot and strict serializer.
- `app/src/main/java/com/chardy/doom/RemovalTraceStore.kt`: main-thread, process-local owner; explicit arm/reveal/copy/clear state; no service, Activity, View, node or Context reference retained.

Do not add the trace to `SanitizedStructuralReport` or `Observation.report`. Reuse the clipboard sink only through an explicitly typed entry point, never by mixing structural text into the trace. Recording must not call Observation.clear/reveal/copy or mutate the gate.

Use an explicit `ARM NEXT PAUSE TRACE` control, default off and not persisted. Arming clears/replaces the previous episode only after the user is told this, and targets the next eligible admission. A cooldown-suppressed opening must not silently count as a traced admission. Arming expires after 120,000 ms without an episode. A trace episode starts at the eligible admission attempt, so admission/install failure can be represented; `SHOWN` marks accepted addView/overlayShown separately.

Record only during that single attempt/visible/closing episode. Freeze at confirmed detach after the released action is known, or at removal-exhaustion/service-terminal handling when attachment cannot be confirmed. Do not append subsequent Home/launcher/Doom navigation events to an already frozen episode. No multi-session history and no automatic next-session rearming.

Retain the frozen enum-only snapshot for at most 10 minutes from terminal freeze, including across ordinary foreign-app navigation and service interruption/unbind/destroy/reconnect in the same process. This retention is necessary to copy the causal record after disappearance. It deliberately differs from legacy structural-report clearing and must be disclosed. Revocation/Stop Observation, explicit Clear Trace or process death destroys trace, armed state and reveal/copy state immediately. A trace must not survive revocation merely to diagnose revocation. Test revocation's terminal classification through a synthetic recorder before asserting that the user-accessible store is empty.

Freeze/record lifecycle cause before ordinary `Observation.clear()`, but do not let that call erase the separate trace. Do not tie trace access to `Observation.connected`: otherwise lifecycle evidence is inaccessible precisely when needed. Access requires the still-valid trace opt-in/session authorization, unexpired snapshot and current explicit UI action. Expiry is checked synchronously on every access as well as by a cancellable handler cleanup; a delayed timer cannot make stale data copyable. No saved state, preference, database, file, logcat, crash reporter or network destination.

### Six fields per record; no free-form payload

Proposed immutable record:

| Field | Closed domain / meaning |
| --- | --- |
| `dtMs` | Nonnegative monotonic millisecond offset from episode start; cap 10,000. No absolute elapsedRealtime value or wall time is exported. |
| `mark` | Closed program-site/outcome enum from the list below. It combines source and reason, avoiding extra origin/reason strings. |
| `event` | NA, STATE, CONTENT, OTHER, NULL_EVENT. Raw event integers are not exported. |
| `owner` | NA, IG, DOOM, OTHER, UNATTRIBUTED. Computed by transient local package comparison only. |
| `root` | NOT_READ, IG, DOOM, FOREIGN, NO_ROOT, NO_PACKAGE, READ_FAILURE. Distinguish an obtained unattributed node from no node. |
| `action` | NONE, BYPASS, RESET_OUTSIDE, COMPLETE, PRESERVE_REPORT, NAVIGATE_MESSAGES, HOME. Requested action at a trigger; actual released action at detach/freeze. |

`mark` vocabulary for v1:

- Episode/observation: ATTEMPT, SHOWN, EVENT_IGNORED_OWN, EVENT_SUPPRESSED_COOLDOWN, WATCHDOG_SAFE, WATCHDOG_UNCERTAIN.
- Event causes: EVENT_PACKAGE_RESET, EVENT_ROOT_MISSING, EVENT_ROOT_MISMATCH, EVENT_DENIED, EVENT_FAILURE.
- Watchdog causes: WATCHDOG_FOREIGN, WATCHDOG_UNCERTAINTY_EXPIRED, WATCHDOG_ROLLBACK, WATCHDOG_NO_SAFE_ANCHOR, WATCHDOG_FAILURE.
- Admission causes: INSTALL_FAILURE, SHOWN_REJECTED, ADMISSION_REJECTED.
- Independent sources: TIMER_COMPLETE, USER_MESSAGES, USER_HOME, APP_RETURN, SERVICE_CONNECTED_RESET, SERVICE_INTERRUPTED, SERVICE_UNBOUND, SERVICE_DESTROYED.
- Removal outcomes: CLOSING, REMOVAL_RETRY, SAFETY_OVERRIDE, REMOVAL_EXHAUSTED, DETACHED, ALREADY_DETACHED, NO_OVERLAY_RELEASED, ACTION_RELEASED, ACTION_VETOED, TRUNCATED.

Future arbitration may add a new schema revision with EVENT_CONTENT_CORROBORATED and EVENT_CONTENT_REJECTED. Do not silently change the meaning of existing marks or append arbitrary text to explain new branches. No route content, selected-tab data, node/window identifiers, package/class strings, build identity, device identity, token/generation/epoch values, exception details, counts or hashes in the exported record. `direct_tab` need not appear at all; this trace does not diagnose routing.

A fixed version header such as `doom-removal-trace-v1` is a compile-time format enum, not an arbitrary metadata object. Header status uses only closed values: COMPLETE, TRUNCATED, TIME_CAPPED, INVALID_CLOCK, or INCOMPLETE. Retention/disposition labels likewise use enums. Export numbers only for monotonic offsets. Keep installed build/SHA provenance outside the trace: the user separately reports the visible build code, linked by the controller to Actions evidence.

### Bounds and ordering

- One fixed trace store, maximum 64 records and serialized ASCII output <=8,192 bytes. No unbounded list, map, StringBuilder or event.toString intermediate. Test the full Cartesian field maxima against this output cap.
- Reserve terminal slots rather than allowing a content-event flood to evict the cause. Suggested allocation: 1 pinned ATTEMPT, 1 pinned SHOWN, a 46-slot pre-close circular buffer and 16 reserved terminal slots. The first accepted causal trigger and CLOSING are pinned in the terminal section. Capture the final action and DETACHED/exhausted outcome even when the pre-close buffer wrapped.
- Coalesce repeated identical observation categories, retaining both the first and most recent offset for the current run as two ordinary records. This preserves the latest IG confirmation used by the watchdog. Never coalesce across a closing trigger. Record a single retry-start and exhaustion/detach outcome rather than a row per retry. Represent omitted/wrapped/saturated history with TRUNCATED; a truncated causal interval cannot authorize a behavioral exception.
- All writes are main-thread and synchronous. Array order disambiguates equal offsets; no sequence number is needed. Internal token checks associate records with the physical episode, but tokens are not exported.
- Offset rollback/negative input marks INVALID_CLOCK without emitting a negative number. At >10,000 ms, stop detailed sampling, clamp terminal offsets and mark TIME_CAPPED. Capped timing is not precise evidence and cannot justify grace changes.
- Store accepted triggers, not every rejected stale callback. A stale callback must not contaminate the replacement episode or overwrite its cause. A competing accepted safety request during closing gets SAFETY_OVERRIDE plus its site mark; first cause and final action remain separate.
- Lifecycle callbacks after an already completed physical episode do not rewrite its cause. On unbind/destroy before detach, freeze as INCOMPLETE unless actual detach was observed. Do not claim platform teardown completed merely because disableSelf returned.
- The current diagnostic does not add an attach listener. `ALREADY_DETACHED` means attachment was absent before this removal attempt; `DETACHED` means it became absent after `removeImmediate`. If a future app-owned listener is authorized, it must inspect only Doom's own View and must not perform cleanup/navigation.

### Instrumentation sites and diagnostic non-interference

Add a cause argument to shared removal helpers, or record at each caller before invoking them; do not infer the cause from BYPASS/RESET_OUTSIDE at the bottom. Wire every listed caller, including connect, lifecycle, static consent cancellation, all catch blocks, timer and both explicit action callbacks. At `confirmOverlayRemoved`, record actual policy output before token/view references are discarded. Trace disposal and physical detach separately.

Initially reuse only values already obtained by the production branch. For the non-target-event reset, root is NOT_READ. Capture existing watchdog root observations at their actual read time. Split the watchdog adapter's missing-root, missing-package and caught-failure categories while passing the same nullable value to the unchanged watchdog. Do not add a second root read, sleep, debounce, extra traversal or different decision merely to fill a trace column. Preserve node ownership and finally recycling.

A NOT_READ root plus an earlier IG sample does not prove simultaneous foreground identity. If that ambiguity prevents identification of a safe exception, a second, separately reviewed diagnostic pass may make one package-only root observation for the demonstrated CONTENT category, without changing its reset decision. It must not read a tree, postpone STATE/confirmed-foreign cleanup, or treat a failed read as safe. Measure its read/removal offsets and compare reproduction with the initial diagnostic. If the extra observation changes reproducibility or cannot meet safety constraints, stop instead of declaring proof.

Recorder failures must never postpone or prevent safety cleanup, execute an action, or extend visibility. Do not route an otherwise harmless diagnostic-recording failure through a new gate-removal decision. Disarm/drop the recorder through a bounded no-throw boundary; report unavailable/incomplete only if safely representable. Avoid per-tick serialization and Compose observation: serialize only an immutable snapshot on Reveal/Copy, and publish only small availability transitions to UI.

## 4. Trace-only privacy path and post-disappearance copy UX

### Satisfy the stricter scope before collecting on a phone

The future WIL-182 internal diagnostic path must not run `collect(root)` or route `SanitizedStructuralReport.text` into any copy button. Leave the legacy report types as historical/test context; do not refactor their sanitizer into a trace system. In the trace-only candidate, recycle a validated admission root without descendant reads, expose honest UNKNOWN/report-unavailable status and remove/disable the legacy report reveal/copy entry points. No replacement direct-tab scan on admission: exact Direct-tab lookup remains user-action-only.

Prefer one explicit trace-only candidate path over an ambiguous runtime switch that can accidentally restore broad collection. Any build configuration used to select it must be included in exact-APK CI/signing provenance and tested; do not add a new distributable flavor casually. Existing gate enablement/consent requirements must remain conservative, with trace consent additionally required for recording. Only the two existing consent booleans may remain persisted; trace arming/consent is session-local. Update `MainActivity.kt`, `EntryGateOverlayView.kt`, `Observation.kt`, `strings.xml` and validation/source guards to tell the truth about the reduced diagnostic path. Do not label unavailable structure as a captured report.

For direct return without class metadata, provide an internal, main-thread `onDoomActivityResumed`/`onDoomActivityPaused` signal from MainActivity's actual lifecycle. Resuming Doom requests prompt removal with preservation semantics; overlay-generated accessibility events cannot generate this signal. Reset any local foreground marker on pause/destroy/disconnect; never make arbitrary DOOM roots safe from a stale marker. Do not introduce an exported callback/intent extra or use event.className as fallback. This changes a privacy input and must be tested separately from the event-arbitration fix.

These privacy reductions can affect startup workload, overlay copy-button visibility and callback ordering. Therefore call the resulting APK a trace-only diagnostic candidate, not a bit-identical instrumented build 35. Record the differences in its acceptance ledger and first require reproduction on it. Failure to reproduce after these changes is inconclusive; it does not establish a breathing fix and does not justify reenabling prohibited collection for convenience.

### User flow

1. In Doom, read a short disclosure: categories/timing only; one episode in RAM; retained after ordinary app changes/service disconnect for up to 10 minutes; no recovery after process death; clipboard only on a tap.
2. Tap ARM NEXT PAUSE TRACE. Show ARMED, waiting for an eligible pause; warn about the unchanged one-minute cooldown. Do not reset cooldown or restart the service to force eligibility.
3. Open Instagram manually and do not interact during the reproduction. No notification, automatic Activity launch or clipboard write occurs when the overlay disappears.
4. Return to Doom normally, even through Home/another app. The frozen trace survives that return. MainActivity displays `Trace available`, `Trace incomplete`, `Trace expired`, or `No trace — process-local evidence unavailable`; it does not invent a removal cause when data is missing.
5. Tap REVEAL REMOVAL TRACE to review the immutable enum/offset snapshot. This is distinct from the legacy structural report control. The trace must be accessible even if the accessibility service is disconnected.
6. After the warning that copying leaves Doom and cannot be recalled, tap COPY REMOVAL TRACE. The click handler synchronously rechecks consent, expiry, reveal/snapshot identity and presence; only then writes via the single sensitive-flagged clipboard sink. No delayed copy job. If snapshot/consent changed, require another reveal/tap rather than copying unseen data.
7. Show COPIED only after setPrimaryClip returns normally; absent/throwing clipboard gives UNAVAILABLE. Never read clipboard back in production, register a clipboard listener, clear it automatically, or schedule clipboard writes. Clearing Doom cannot recall a copy; the user may clear the clipboard themselves.
8. Offer CLEAR TRACE / DISARM. Do not automatically rearm on copy. A new explicit arm replaces the old trace only after the replacement disclosure.

Keep the recovery control in MainActivity, not solely on the short-lived overlay. No new overlay, persistent notification, share intent, file export or automatic return-to-Doom action is required. Existing Skip and Leave remain explicit controls with unchanged removal-before-action ordering.

## 5. Conditional fail-open event/root arbitration policy

This is a candidate contract, NOT authorization to change behavior now. Implement only the cells supported by the evidence gate in section 9. If the trace instead identifies lifecycle, attributed-root or rendering failure, do not ship this event fix.

Proposed new pure file: `app/src/main/java/com/chardy/doom/OverlayEventArbitration.kt`, consuming closed event/owner/root categories, current overlay phase, positive local Doom-Activity signal and a monotonic sample time. Return a closed decision: NO_CHANGE, KEEP_CONFIRMED, REMOVE_OUTSIDE, REMOVE_BYPASS, or REMOVE_DOOM_RETURN. Keep actual Android root acquisition/recycling in a small service adapter. Do not pass Android nodes or raw package/class strings into the policy.

### What counts as confirmation

- A fresh, attributed non-IG root is an immediate removal veto, including an unverified DOOM root. Never grant it grace because an earlier event/root said IG.
- A foreign/unattributed STATE event continues to remove immediately, even when the last root was IG. A root may be stale during real navigation; do not wait for two foreign samples or make root confirmation a prerequisite to honoring foreground-transition evidence.
- Positive Doom Activity resume removes promptly using the local lifecycle signal. Own overlay CONTENT events do not establish a return or renew safety.
- Null root, null attribution, an exception and a previous IG event are uncertainty, not positive IG confirmation.
- Only a same-callback package-only root read returning IG may corroborate the specific demonstrated non-foreground CONTENT event category. No stale root cache, surface classifier, class name, System UI/package allowlist or event text can corroborate it.

### Decision table

| Situation | Required behavior |
| --- | --- |
| Consent/connection loss, interruption/unbind/destroy or current safety cleanup | Remove immediately using existing safety veto; no grace, route or credit. Trace retention follows its explicit consent rules. |
| Local Doom Activity resume | Remove now; OUTSIDE/preservation semantics after detach. No continuing overlay over Doom. |
| Known foreign/unattributed STATE event | Current immediate RESET_OUTSIDE behavior, without fetching a foreign tree or waiting for watchdog. |
| Null event / unsupported event / unproved content-owner combination | Preserve existing conservative reset behavior. Do not introduce blanket ignores. |
| Demonstrated `OTHER + CONTENT`, visible current admitted overlay, fresh root IG | Conditional narrow exception: retain the same overlay/ticket/deadline. No collection, action, credit or new ticket. Keep the 50 ms watchdog active. Enable this cell only after trace/repro proof. |
| Same content event, fresh root FOREIGN or unverified DOOM | Immediate safety cleanup; no grace or second confirmation. |
| Same content event, NO_ROOT / NO_PACKAGE / READ_FAILURE | Keep baseline immediate event cleanup in the first fix. The watchdog's existing null grace is not automatically extended to event sources. A separate traced trigger and review are required for any such extension. |
| IG event while an admitted gate is in cooldown | Preserve existing no-op for admission/collection. Do not move every IG event past the cooldown guard as an unrelated fix. Existing watchdog and foreign-event/local-lifecycle safety continue independently. |
| Own overlay event without Activity resume | Existing ignore; it does not update lastSafeAt, reset uncertainty or keep a foreign-root overlay alive. |
| Watchdog root IG | Keep and refresh lastSafeAt using that actual observation time. |
| Watchdog unattributed root/failure | Existing grace only: KEEP_UNCERTAIN while age from lastSafeAt <150 ms, remove at >=150 ms. Do not refresh the deadline from uncertainty. |
| Watchdog attributed FOREIGN/DOOM without positive app-return handling | Remove at that tick immediately. The diagnostic path does not turn DOOM into a blanket safe root. |
| Closing/removed/stale token | Never retain, reopen, render or rearm an overlay. A stronger safety signal may veto pending actions; other stale work is a no-op. |

The first event fix should not also increase grace, change event subscription/notificationTimeout, introduce windows/focus traversal, alter cooldown or change the router. It is legitimate for ambiguity still to cause an occasional fail-open disappearance. Safety outranks retention.

### Sample scheduling and cost

Read at most the active root attribution for the demonstrated content category while a current overlay is visible. To bound a content-event storm, permit at most one new corroborating read per 50 ms interval; additional uncorroborated foreign content events conservatively remove rather than reuse cached IG authority. This may sacrifice retention under a storm, but cannot extend an overlay over a foreign app. Prove the chosen rule with storm tests; if it makes the proposed exception ineffective on the trace, stop and redesign within scope rather than hiding throttling.

A corroborating IG read may update the shared last-safe timestamp only as a real fresh confirmation. Own/content/unknown event arrival alone never updates it. Use one safety clock/state if sharing confirmations between arbiter and watchdog; do not create independent rolling grace periods. If a simpler implementation leaves watchdog confirmation state unchanged, that is conservative and preferable for the first fix.

No artificial debounce before foreign removal. Package-only observation API calls and main-thread scheduling can still incur latency; neither a 50 ms handler interval nor a policy return is a measured physical-removal guarantee. Keep request and actual-detach offsets distinct in tests and reports.

## 6. State transitions and timing limits

Maintain two separate dimensions: existing logical `EntryGateState` and physical overlay phase (ABSENT, INSTALLING, VISIBLE, CLOSING, DETACHED/TEARDOWN_PENDING). Physical phase can be derived from current token/view/removal state; do not add a competing state machine that owns removal.

| From | Input | To / invariant |
| --- | --- | --- |
| OUTSIDE + ABSENT | Eligible IG admission, all existing consents, cooldown expired | AWAITING/current ticket, then GATING/INSTALLING only after validation. Trace ATTEMPT if explicitly armed. |
| GATING + INSTALLING | addView and overlayShown accepted; admitForDisplay succeeds | GATING + VISIBLE; SHOWN offset, fixed five-second deadline, seed existing watchdog, arm 60-second cooldown. |
| INSTALLING | Failure/rejected admission | Immediate safety cleanup; no false successful-show record or navigation. Preserve whether addView actually succeeded. |
| GATING + VISIBLE | Proven harmless content event with fresh IG root | Same state, token, visible-start time and completion callback. No resetting countdown or cooldown. |
| GATING + VISIBLE | Watchdog uncertainty below bound | Same state until safe recovery or fixed uncertainty deadline; no timer pause/extension. |
| VISIBLE | Event foreign reset, watchdog fail-open, lifecycle, explicit action, valid completion callback | CLOSING immediately: callbacks invalidated/cancelled, buttons/animation disposed, pending action recorded. Logical gate outcome remains governed by confirmed-detach handling. |
| CLOSING | Stronger safety event/revocation | Remain CLOSING; latch irreversible external-action veto; preserve original cause and record override. |
| CLOSING | Actual detached postcondition | RESET_OUTSIDE -> OUTSIDE and ticket null; BYPASS -> BYPASSED unless already OUTSIDE; valid COMPLETE -> GRANTED; Skip/Leave -> BYPASSED; verified Doom return -> OUTSIDE. Freeze trace after actual final action/veto is recorded. |
| CLOSING | Removal budget exhausted | Disable service; retain attached references and veto all external actions. Mark TEARDOWN_PENDING/INCOMPLETE; do not pretend detached. |
| Any | Stale callback from prior token | No effect on current view, policy, deadline, trace or route. |
| GRANTED/BYPASSED/OUTSIDE after admission | Reentry within cooldown | No new gate/ticket. A false event reset must not defeat cooldown. |

Timing contract:

- Gate: 5,000 ms from accepted shown time, not first event, app launch, animation phase or report capture. No trace/arbitration callback extends this deadline.
- Normal watchdog interval: 50 ms, first tick posted immediately after admission.
- Unattributed watchdog allowance: strictly less than 150 ms from last known-safe moment. At exactly 150 ms it fails open. Preserve negative/rollback fail-open behavior.
- Confirmed foreign transition: request removal in the same event callback or first observing watchdog tick. No debounce, no extra confirmation sample and no grace for attributed foreign roots.
- Healthy-platform test target: physical detach within 250 ms of controlled foreign-foreground confirmation, measured separately from observation detection; fail tests that miss it rather than raising the budget to accommodate a behavioral regression. This is an acceptance target, not an Android scheduling guarantee or a preexisting verified phone latency.
- Physical retries remain 50 ms and at most 20 failed attempts before disableSelf. Repeated events must not replenish attempts. Test exhaustion separately; the retry budget is not permission for normal foreign removal to take that long.
- Cooldown: unchanged 60,000 ms after accepted display. Preserve existing service-instance lifetime semantics; do not strengthen this into a claim that the value survives service recreation or process death.
- Trace arm: 120,000 ms maximum; episode precision cap: 10,000 ms; frozen trace retention: 10 minutes. None is a gate-retention allowance.
- At completion, retain existing time/ticket checks. Add tests for a coincident foreign signal and pending completion so safety wins. A new last-moment root check at completion is a separate safety-hardening change if found necessary, not something to hide inside trace instrumentation.

## 7. Verification matrix

All execution below is future authorized work in GitHub Actions. Expected outcomes are acceptance criteria, not results produced in this planning session. Existing source guards are supplemental; updating a regex to match new code is not runtime proof.

### A. Pure unit tests

Proposed tests:

- `app/src/test/java/com/chardy/doom/OverlayRemovalTraceTest.kt`.
- `app/src/test/java/com/chardy/doom/OverlayEventArbitrationTest.kt` (conditional fix stage).
- Extend existing `OverlayForegroundWatchdogTest.kt`, `InstagramEntryGateTest.kt`, `OverlayCallbackGuardTest.kt`, `OverlayRemovalPolicyTest.kt` only where their contracts are affected.

Cases:

1. Each removal-source family maps to a different closed mark; requested and final action remain distinct under priority/safety override.
2. Exact fixed-capacity wrap, terminal reservation, equal-offset ordering, repeated-observation coalescing, ASCII serialization cap, no partial row, immutable snapshots, replacement/clear/expiry and no post-freeze contamination.
3. Synthetic canary values for package, class, event/node content, IDs, exceptions, URLs and account-like strings never enter recorder/store/serializer. Prefer a typed API that cannot accept them, not regex sanitization of arbitrary diagnostic strings.
4. NO_ROOT, NO_PACKAGE and READ_FAILURE remain distinguishable in the trace but preserve the existing nullable-watchdog semantics in the diagnostic stage.
5. Virtual times at shown, 149 ms, 150 ms, rollback, long scheduling gap, recovery, alternating IG/uncertain, and attributed foreign during uncertainty. Repeated uncertainty never refreshes safety.
6. No early completion at 4,999 ms; completion at 5,000 ms; harmless event storms never restart the deadline. Cooldown at 59,999/60,000 ms and retained cooldown after early removal.
7. Exhaustive table of STATE/CONTENT/OTHER/NULL_EVENT against owner/root categories. Only the explicitly proven combination can keep; every known foreign root removes without grace.
8. Closing safety override defeats pending HOME/Messages/COMPLETE, and a late detach after exhaustion never releases external action.
9. Revocation clears the trace store even if a recorder briefly classified the source; service disconnect without revocation preserves only the frozen enum-only snapshot until expiry.
10. Differential decision tests: baseline vs trace-only wiring must issue the same removal/action sequence for identical gate inputs, except separately documented privacy-only input substitutions. Trace enabled/disabled must not change decisions.

Use `:app:testDebugUnitTest` for Android-project unit execution in Actions. `scripts/test-entry-gate-host.py` has explicit file/class lists and currently omits the router policy suite from those lists; do not treat it as a complete test runner. Register any new pure files if that helper is retained, but do not run it on the planning host or claim it replaces Gradle's suite.

### B. Production-service instrumentation: the critical missing seam

Proposed new `app/src/androidTest/java/com/chardy/doom/EntryGateRemovalTriggerTest.kt`; extend `EntryGateServiceActionTest.kt` and add `RemovalTraceUiTest.kt`.

The production platform boundary supplies the event and watchdog root reads while retaining the same nullable/exception behavior; the private installer and tick seams are instrumentation-only. The existing watchdog instrumentation test (`EntryGateServiceActionTest.kt:346-367`) originally called only the pure watchdog, not its Runnable. The fixture setup at `:393-411` originally set overlayShown but did not call admitForDisplay. The repaired tests address these gaps without changing policy.

Introduce a small injectable root/clock/scheduler test seam for the event and actual watchdog callback, keeping production defaults identical and preserving distinguishable read failures. Factor the tick into an internal method invoked by the real Runnable and tests; do not test an independently reimplemented policy. Avoid new exported test hooks. Admission fixtures must arm cooldown and seed the exact shown timestamp; test root recycling on all paths.

Required journeys:

1. Install/admit a production overlay through its real path (or a controlled add/remove platform adapter with explicit admission), deliver OTHER+CONTENT at a known offset, supply IG root, run real onAccessibilityEvent. Baseline must demonstrate closing/physical removal via EVENT_PACKAGE_RESET. The conditional fix test expects no closing only for the proven cell. Include a healthy attached-view postcondition, not just logical state.
2. Repeat with STATE, FOREIGN root, DOOM root, missing root, null package, read failure, null event and unsupported event. Show immediate removals and zero external actions on safety paths.
3. Drive the actual scheduled watchdog tick through IG -> transient null -> IG; IG -> null until boundary; IG -> DOOM; IG -> FOREIGN; throw. Assert source/reason, time and recycle counts. Reflection into the pure watchdog alone is inadequate.
4. Normal no-touch visible episode reaches TIMER_COMPLETE after five visible seconds and GRANTED only after detach. Verify no passive clipboard changes.
5. Interruption, unbind, destroy, reconnect, gate revocation, observation stop and install failure all have distinguishable internal source records; enforce the separate trace-erasure rules.
6. Delay/remove failure: CLOSING can precede DETACHED; retry budget unchanged; safety overrides a pending Skip/Home; stale completion/retry/detach cannot mutate a later overlay or trace.
7. Preserve the existing external-detachment safety test; the current attempt boundary records `ALREADY_DETACHED` or `DETACHED` only, without inventing a lifecycle cause or automatically routing.
8. Real Doom Activity resume removes promptly and preserves the frozen trace; own overlay content changes do not synthesize Activity return. No class getter is needed. Rotation/recreation/background return cannot leave a stale foreground marker.
9. Strict scope: a root/node test double fails if child/text/description/class/general-ID access occurs in the trace-only admission/event/watchdog path. Exact Direct-tab state/query access is allowed only after explicit Skip and confirmed removal.
10. Re-run all existing `InstagramMessagesRouterTest` and service-action ordering cases; zero/multiple/selected/unactionable results remain no-click, success at most once, no generic fallback.

Clipboard/UI journeys with a synthetic sentinel:

- Arm, admission, render, failure, normal completion, unrelated app events, service disconnection, MainActivity recreation, reveal and expiry do not write the clipboard.
- After failure -> Home/another app -> Doom, snapshot remains available without requiring service reconnection or the old structural report.
- Explicit reveal then copy writes exactly the frozen ASCII enum/offset snapshot with sensitive-preview flag; no build/package/class/report text is appended.
- Revoke, clear, expiry or snapshot replacement between render and tap rejects copy and resets success UI. Missing/throwing clipboard returns UNAVAILABLE.
- A store initialized in a fresh process is empty; Activity recreation in the same process may retain it. Do not use rememberSaveable, SavedStateHandle, SharedPreferences or files to make process-death recovery pass.
- Accessibility and large-font controls remain reachable without screenshots. Do not add a new overlay copy callback merely to recover an already-removed overlay.

### C. Cross-app fixture tests without claiming Instagram proof

The existing `fixturegate` uses its own GatePolicy, 200 ms watchdog, focused-window selection and fixture selectors (`FixtureGateService.kt:66-80,86-152`). Its passing suite cannot verify the production event/cooldown/watchdog interaction. Its documented screenshots and generic fixture action are outside this assignment's new evidence path.

Add a no-screenshot, no-tree/no-node-action WIL-182 harness that drives the production service decision/tick/removal seam, with a simple fixed target Activity and separately controlled foreign Activity. Reuse `fixtureapp` only as a synthetic window producer; do not enable the legacy FixtureGateService or change the production target package to include a fixture. A test-only target adapter must be confined to androidTest/test sources, not a remotely configurable production allowlist. Own app buttons/test instrumentation may trigger synthetic events; the production runtime must not gain generic node actions.

Test both layers and label them separately:

- Deterministic production-service injection of category sequences proves exact branch wiring, cooldown and removal action.
- A bound emulator service with actual nonfocusable TYPE_ACCESSIBILITY_OVERLAY and actual Activity changes proves physical cross-app detach and lifecycle ordering. Use root-package classification only; no window dump, screenshots, raw event log or foreign-tree inspection. If the chosen harness does not exercise production service code, mark it supplemental and do not accept it as the WIL-182 regression.

Journeys: stable target for full pause; synthetic background content while target remains active; actual target-to-foreign Activity, Home, Back, lock/screen-off and service disable; dropped event covered by watchdog; rapid target/foreign transitions; event flood; return to Doom and explicit trace copy. Use `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` and disposable API 35 identity guards already documented in `docs/FIXTURE.md`; restore service settings in finally. No Instagram installation, credentials or live account in CI.

Measure foreign-transition observation, removal request and actual detach offsets separately. Preserve a failing latency result; do not replace it with a polling constant. Exercise long scheduler delays distinctly from healthy-platform latency acceptance.

### D. No-screenshot CI/signing evidence prerequisite (deferred; unchanged in this diagnostic PR)

The beta decision leaves the existing synthetic screenshot and signing contract untouched. The redesign below remains a separately authorized follow-up and is not evidence produced by this PR.

Do not run the current screenshot scripts as-is for this assignment. Before any diagnostic APK is distributed, authorize a versioned, strict no-screenshot evidence path:

- Update `.github/workflows/android.yml`, `scripts/ci-device.sh`, `scripts/evidence-manifest.py`, fixture runner/evidence validation and screenshot-producing test helpers to select an explicit no-screenshot contract. Preserve behavioral assertions; remove screenshot side effects from this mode, not whole safety journeys.
- Inventory `DoomUiTest.kt`, `EntryGateOverlayUiTest.kt`, `CrossAppFixtureTest.kt`, `scripts/ci-fixture.sh`, `scripts/test-fixture-evidence.py` and overlay evidence helpers before editing. Legacy screenshot tests must not run inadvertently in the new job, including failure collectors.
- New fixed evidence kind: `doom-removal-diagnosis-no-content-v1`; exact candidate SHA, source run/attempt, API, APK hash, named JUnit assertion results and controlled synthetic timing results. Keep CI provenance distinct from the phone trace schema. No phone trace, raw package/event data, screenshot, hierarchy dump, diagnostic logcat or phone identifiers in Actions artifacts.
- Restrict outputs to build/test provenance and allowlisted test results; test failures must not dump synthetic node objects/raw event representations either. Runtime diagnostic state remains RAM-only. Persisted CI build/test artifacts are not runtime trace persistence and must not contain user evidence.
- The trusted signer must explicitly validate this exact new evidence kind and exact required file set, nonzero passing tests, matching candidate/run/API, absence of unaccounted files and signed-vs-tested non-signature payload equality. Keep strict legacy validation for legacy evidence; do not turn screenshot requirements into an optional unchecked hole.
- Signing remains on trusted main, behind `internal-signing`, using the exact emulator-tested APK, not a rebuild. No secrets on the agent host, PR runner or phone trace. Changes to the trusted contract need independent security review and owner authorization before use.

Future Actions commands, after that mode exists, retain the existing real Gradle targets:

`./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

`./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest`

The second command must run only with the approved no-screenshot test configuration. The fixture harness's exact target/arguments must be recorded when implemented; do not present the current `bash scripts/ci-fixture.sh` as compliant without its required changes. Run signing/evidence Python regression suites inside Actions as part of the contract review. Do not set GITHUB_ACTIONS locally to bypass runner restrictions.

### E. Real-phone evidence and acceptance

Use only the authorized, protected-signed exact Actions candidate, with the visible build code checked by the user. No agent adb, screenshot/video, UI hierarchy dump, logcat capture or remote-control session on the phone. User manually opens apps and copies the bounded trace if desired. Do not request raw Instagram content or account details.

Diagnostic stage:

1. Arm trace, wait for normal cooldown eligibility, open Instagram from launcher and touch nothing. Note whether the pause actually appears; do not count a cooldown-suppressed opening as successful retention.
2. If it disappears unexpectedly, return normally and reveal/copy the trace. Record the user statement `no touch; Instagram remained visible` separately from machine observations. An earlier IG root is not simultaneous foreground proof.
3. Repeat until the same complete first-closing signature is captured twice, or stop after five eligible diagnostic attempts if the symptom/signature cannot be reproduced. A failure to reproduce is INCONCLUSIVE, not a pass.
4. Include one intentional five-second completion and explicit Skip/Leave controls as source-label calibration, plus one foreign-app departure to verify prompt removal is still visible to the user. Do not run an unbounded session that accumulates app-usage traces.
5. If evidence is empty, expired, truncated across the cause, process-lost or ambiguous, rearm a bounded attempt or seek a narrower enum-only probe. Do not ask for broader content collection.

Conditional fix stage, after red/green proof:

- Require at least ten eligible no-touch admissions across two separately conducted phone sessions, covering cold/warm Instagram entry and normal/reduced animation settings where available. Each must either complete at the unchanged deadline or have an explained intentional safety/action end. Cooldown suppression is excluded from the denominator.
- Recheck actual app departure during the first second and later in the pause, Home, Back, notification shade/system surface, lock/unlock, return to Doom, service stop/reconnect and rapid app switching. Any overlay retained over a confirmed foreign app stops acceptance immediately.
- Preserve the working one-shot Direct-tab click and no-regate cooldown. Test no-match/failure safely in CI; do not solicit additional live selectors.
- A human impression of prompt removal is useful but not a measured sub-250 ms phone guarantee. Exact request/detach offsets and controlled CI timing support the latency claim; do not exaggerate what the content-free phone trace can establish about the moment an OS foreground change happened.
- These repeated trials are a bounded regression gate, not statistical proof of all-device reliability. Keep product/device validation wording limited to the tested candidate and observed journeys.

## 8. Staged work packages, dependencies and routing

These are proposed work-package labels, not created Linear/GitHub ticket IDs. Token budgets are planning envelopes for cumulative model context/output work per package, not spend guarantees. Split or return for review when the envelope is exhausted; do not silently escalate to max/Mac/ultra. Routing is advisory for future authorized work; no agent, subprocess or delegation is launched by this plan.

| Package | Deliverable / task type | Depends on | Difficulty | PR risk | Token budget | Model / reasoning | Routing rationale |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D0 | Scope/privacy and evidence-contract approval; architecture/security decision | This plan, owner approval | High | High if assumptions leak into implementation | 8k-14k | Sol, high | Reconcile strict privacy with existing collector and signer; not a mechanical UI task. |
| D1 | Trace schema, store and pure tests; bounded implementation | D0 | Medium | Low-medium | 10k-16k | Terra, standard; Sol reviews invariants | Closed types/capacity can be implemented routinely once lifetime/authority contracts are fixed. |
| D2 | Service hooks, root/tick test seam and privacy-only diagnostic path; systems implementation | D0, D1 | High | High | 22k-34k | Sol, high | Shared cleanup, node ownership, consent, cooldown and callback races require cross-path reasoning. |
| D3 | Arm/reveal/copy/clear UX and sentinel tests; UI implementation | D1, D2 API contract | Medium | Medium | 10k-18k | Terra, standard | Existing Compose controls/clipboard sink suffice; disconnected-service access needs explicit tests. |
| D4 | No-screenshot CI/fixture evidence and protected signer contract; CI/security implementation | D0; D2/D3 for integrated assertions | High | High | 20k-32k | Sol, high; independent Sol/security review | Exact artifact provenance and trusted-main validation must not be weakened to speed diagnosis. |
| D5 | Diagnostic candidate validation and phone trace collection; QA/evidence acquisition | D1-D4 green, independent review, owner distribution approval | Medium | Medium operational risk | 6k-10k | Terra, standard, human phone operator | Deterministic checklist and user interaction; no autonomous phone access. |
| D6 | Trace triage and red-capable exact-trigger regression; diagnosis/test design | D5 or qualifying existing exact runtime proof | High | Medium | 12k-20k | Sol, high; Luna may format only | Distinguish first cause from overrides and genuine foreign foreground; no hypothesis-to-fix shortcut. |
| D7 | One evidence-specific behavioral fix with table/property tests; conditional implementation | D6 evidence gate PASS | High | High | 18k-30k | Sol, high | Safety-sensitive event/root arbitration; minimize changed cells and retain unknown fail-open paths. |
| D8 | Full production/fixture/phone regression and independent review; acceptance | D7, D4 contract, repeated real-phone tests | High | High | 12k-20k | Sol, high for safety review; Terra for QA coordination | Requires independent challenge of foreign safety and exact tested/signed payload, not formatting. |
| D9 | Evidence ledger and bounded handoff; documentation | D5 for diagnosis report; D8 for fix acceptance | Low | Low | 3k-5k | Luna, standard, Terra verifies | Extract verified outcomes/provenance; Luna does not decide shipping or certify side effects. |

Suggested review/PR boundaries for future authorization:

- Prerequisite contract changes (D0/D4) reviewed separately from product timing decisions. Preserve trusted signer controls; no expedited bypass.
- Diagnostic implementation (D1-D3): trace/privacy only, explicit baseline-decision characterization, no event grace change. Can be signed for bounded internal diagnosis after D4/D5 gates; it is not a WIL-182 fix release.
- Diagnosis report and failing exact-trigger regression (D6): evidence decision checkpoint before authorizing behavioral work.
- Narrow behavioral change (D7) plus acceptance/review (D8): no router/UI redesign, selector expansion, cooldown change or opportunistic cleanup.
- D9 records tests actually run, exact SHA/run/APK lineage, phone build identity, evidence sufficiency and remaining limits. Never rewrite a historical green run as validation of the new candidate.

### Implementable steps per package

**D0 — Resolve prerequisites before code.** Inventory prohibited current reads/exports and screenshot collectors using the paths above. Approve the trace-only candidate path and no-screenshot evidence contract in writing. Confirm that diagnostic retention across service disconnect is disclosed and revocation still erases it. Stop if authority is insufficient.

**D1 — Build recorder/store test-first.** Add closed enums and failing bounds/lifetime tests. Run RED in Actions. Implement fixed allocation/reserved terminal slots, virtual clock and immutable snapshots. Run GREEN plus serialization adversarial cases. Keep serializer/platform clipboard separate.

**D2 — Characterize and instrument the actual service.** First add admitted-overlay fixtures and event/tick root seam tests on the unchanged policy, including the rank-1 removal and watchdog branches. Then add per-caller reason hooks without changing outcomes. Add the strict privacy-path reductions and local Activity-return signal as visibly separate changes with tests; verify no broad collector/class/export path is reachable. Run diagnostic enabled/disabled differential tests. Review every call to cancelAndBypass, failOpen, resetOutside, requestOverlayRemoval, requestSafetyCleanup and confirmOverlayRemoved.

**D3 — Expose recovery UX.** Add session-local arm consent and status in MainActivity. Add explicit reveal/copy/clear with one clipboard sink and no connectivity requirement for a frozen valid trace. Test sentinel behavior, snapshot identity, revocation, expiry, disconnection and process-start emptiness. Disable legacy report export in the strict candidate.

**D4 — Establish compliant CI delivery.** Separate screenshot side effects from existing behavioral assertions. Add strict versioned no-screenshot device/fixture evidence, validator failure cases and signed payload identity checks. Review trusted-main integration independently; run all build/lint/unit/instrumentation/fixture and contract tests in Actions. Preserve failures and do not distribute a candidate when any prerequisite is missing.

**D5 — Collect, do not fix.** Present exact signed candidate/build identity and short phone instructions. The user controls opt-in, app opening and clipboard export. Obtain complete episodes or record INCONCLUSIVE. No behavior flag flips, debounce or extra selectors while collecting.

**D6 — Decide evidence sufficiency.** Identify first accepted closing source and final action, account for overrides and trace truncation, compare shown/uncertainty/completion offsets. Minimize a production-service red regression using the observed category ordering. If CONTENT reset lacks simultaneous root evidence, use the narrowly reviewed follow-up probe or stop. Record the exact decision-table cell that is eligible for change. Review alternatives before authorizing D7.

**D7 — Change one proven mechanism.** Write the expected-retention regression first and demonstrate failure on the diagnostic baseline in Actions. Implement the narrow arbiter cell only if rank 1 is proven; otherwise create a specific plan amendment for the observed lifecycle/watchdog mechanism. Keep all foreign/unknown/stale/removal tests green. No blanket ignores or grace expansion. A failed fix returns to D6, not another stacked hypothesis.

**D8 — Validate and independently challenge.** Run all required suites against the exact candidate. Verify no-screenshot artifact contract, exact protected signed payload, privacy call graph, real-phone repeated-admission matrix and negative foreign-app cases. Review the full service wiring, not only the pure policy. A reviewer pass is necessary but not device proof.

**D9 — Record only results.** Publish no external issue/comment/artifact without authorization. Write the bounded acceptance ledger with provenance and test outcomes; keep user trace off public CI/analytics, and do not automatically persist copied phone evidence into repository docs. Record conclusions/categories rather than personal usage sequences. Leave merge/release to explicit owner approval.

## 9. Evidence gates and mandatory stop conditions

### Gate E0 — Baseline identity and scope

HEAD must match the intended candidate lineage, worktree changes must be scoped, and the trace/privacy/CI prerequisites must be approved. This session verified only the starting HEAD and source; no later candidate exists yet.

### Gate E1 — Diagnostic correctness and privacy

No phone diagnostic distribution until closed schema/lifetime tests, real service wiring tests, no passive clipboard tests, no prohibited data access/export checks, baseline decisions and exact-APK CI/signing all pass under the authorized no-screenshot contract. If stopping broad collection changes reproduction, label evidence inconclusive rather than claiming a fix.

### Gate E2 — Causal proof before behavioral change

Accept either:

- A complete consented trace from a reproduced disappearance, corroborated enough to distinguish genuine app departure, followed by a minimized runtime regression through the production entry point that reaches the same source; or
- An already existing runtime test that proves the exact trigger under the relevant admitted/cooldown/foreground conditions, with verifiable executed output and a red/green assertion for the reported mechanism.

No inspected test currently meets the second exception. The source regex enforcing reset, pure watchdog boundary test, mocked post-detach router test, demo journey and independent fixture suite do not prove OTHER+CONTENT with IG foreground removed the admitted phone overlay. A newly invented synthetic sequence demonstrates possibility, not that it occurred on the phone. Do not use it to bypass the requested trace-first gate.

For a harmless-event exception, require evidence for its complete allowed combination and an adversarial actual-foreign counterpart. If the trace only says event CONTENT/root NOT_READ, do not call that proven transient on its own. If metadata cannot distinguish safe from unsafe within the allowed scope, keep fail-open behavior and ask for a separate architecture decision; do not expand data collection.

### Gate E3 — Behavioral regression and foreign safety

The exact-trigger test must be RED on the baseline, GREEN with the candidate, and accompanied by foreign/root/lifecycle/unknown/stale-token/physical-removal tests. Only then run bounded real-phone fix acceptance. A phone gate must actually be admitted; cooldown suppression or service disconnection is not successful retention.

Stop immediately for:

- Any retained overlay over confirmed foreign foreground, delayed STATE cleanup, renewed uncertainty without fresh IG proof, or ordinary removal exceeding the controlled latency target.
- Raw package/class/node content or IDs beyond the existing action exception entering the new diagnostic path; broad traversal, screenshots, persistence, logging/analytics/network, new permission/capability/exported test endpoint, or passive clipboard write.
- Extra node action, generic click, selector fallback, gesture, retry route, coordinate click, automatic Home or navigation without the existing explicit user action and confirmed detach.
- A stale callback mutating/removing/routing for a later overlay, safety veto lost during retry, or unconfirmed detachment reported as success.
- A trace missing the first cause, overwritten terminal information, expiry/process loss, contradictory category sequence or unavailable consent. Missing data is not a license to guess.
- An event fix proposed when trace points to watchdog/lifecycle/completion; a null-grace increase proposed without exact gap evidence and a separate safety review; or multiple speculative changes bundled together.
- No reproduction within the diagnostic attempt bound, renewed unexplained disappearance after a fix, or a second unsuccessful hypothesis-led adjustment. Return to diagnosis/architecture, not repeated timer tuning. Build 34's single apparent success cannot serve as a control pass.
- Android/Gradle/emulator/signing execution requested outside Actions, a failed evidence contract, artifact/SHA mismatch, missing protected approval or attempts to bypass trusted signer checks.

### Gate E4 — Delivery truthfulness

A diagnostic candidate is allowed to reproduce the bug; its success criterion is safe evidence acquisition, not retention. A behavioral fix is not accepted until repeated phone journeys, exact production-path regression, foreign safety, privacy and exact-payload validation pass. No plan, unit-only green result, fixture pass, signing success or Opus/Sol review alone is shipping proof. Any merge, release or wider distribution remains separately authorized.

## 10. Expected handoff

The next authorized step is prerequisite scope/evidence-contract review, then a diagnostic-only implementation—not changing the non-Instagram event branch today.

The eventual handoff must distinguish: (a) observed first removal trigger, (b) root/foreground evidence available at that instant, (c) final physical-removal outcome and action, (d) exact changed policy cell if any, (e) executed test and signed-artifact provenance, and (f) unresolved phone/platform limits. Do not attach broader structural reports to make the trace appear more convincing.
