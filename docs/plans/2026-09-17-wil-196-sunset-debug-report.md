# WIL-196 implementation plan — sunset bloom and Debug report

Planning base: `c49e7ea3730807623eb36841afa25f107073cca0`, verified clean in `/mnt/HC_Volume_106820083/worktrees/doom/wil-196-sunset-debug-report`. Scope is the full WIL-196 contract supplied with this task. No Linear connector is available; no live ticket status or dependency completion is asserted. This document is the only authorized change in this planning task. Implementation, independent review, CI dispatch, commits, pushes, PRs, signing and release are future work requiring their applicable task authorization.

## Base facts and decisions

- Read the root `AGENTS.md`, `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`, production/report/action/UI tests, host guards, evidence validators and relevant history. `51ca1c3` introduced configurable sunset breathing; the base includes subsequent landscape and Home/Debug test repairs. Historical five-second/status-overlay descriptions are not the current product specification.
- `app/build.gradle.kts` has minSdk 26, compile/targetSdk 35. Keep toolchains, dependencies, manifests, permissions and accessibility configuration unchanged. Subscription remains `typeWindowStateChanged|typeWindowContentChanged`, with only `flagReportViewIds`.
- Both renderers currently divide their available size by 16. `cells()` quantizes radius with `toInt()` and assigns ivory to Manhattan distance <=1 (five cells). `frame()` already owns 4,000/6,000 ms phases, duration snapshots and segmented progress; preserve these timing contracts.
- The report currently aggregates identical rows, losing node identity/relationships. Collection visits <=128 nodes breadth-first through depth 8; queued/foreign nodes consume the budget. `MAX_CHILDREN=16` caps reported child counts, **not** existing child retrieval. Preserve that distinction. Output is <=64 resource/class tokens and 8,192 ASCII bytes.
- `Observation` owns one immutable report plus process-only reveal/copy flags. The current overlay has neither report/status text nor copy controls; a legacy `copyCurrentReportFromOverlay()` helper still exists. Do not reconnect that helper. Debug uses explicit reveal followed by `copyReport()` through the single sensitive clipboard sink.
- `PRESERVE_REPORT` handles a verified MainActivity return; it cannot itself initiate navigation. `BYPASS` clears the report. A distinct `OPEN_DEBUG` action is necessary. Terminal cooldown belongs only to `complete()` or successful `finishMessagesRoute()`.
- Current authoritative screenshot sets are four canonical, fourteen supplemental, thirteen fixture; exactly five methods carry `@SupplementalEvidence`. Some older prose still says ten supplemental screenshots. Correct current summaries without rewriting historical results.

## Exact file and interface map

Paths below are relative to the repository; `main/`, `test/`, `androidTest/` abbreviate `app/src/<sourceSet>/java/com/chardy/doom/` only in this table.

| File | Implementation responsibility/interface |
| --- | --- |
| `main/BreathingVisuals.kt` | Keep `frame()`, `BREATH_MS`, segments, palette and `staticProgress()`; add `smootherstep(Float)`, `GRID_CELLS=32`, pure `geometry(progress,width,height): List<BloomCell>` with float rectangles, alpha and a closed color role. Replace integer-radius/layer decisions. |
| `main/PixelBreathingView.kt`; `main/MainActivity.kt::PixelBloom` | Draw the same geometry with native/Compose Canvas. No independent geometry, easing, clocks or palette mapping. |
| `main/EntryGateOverlayView.kt` | Extend factory `create(context,onSkipToMessages,onLeaveInstagram,onDebugReport)` and `EntryGateOverlayUi.debugReport`; dispose disables and removes all three listeners. Existing `EntryGateOverlayModel` stays free of diagnostics. |
| `main/OverlayRemovalPolicy.kt` | Add `OverlayRemovalAction.OPEN_DEBUG`, precedence, safety-veto and exhaustion handling. |
| `main/DoomAccessibilityService.kt` | Wire explicit tap; add `OverlayPlatform.openDebug(): Boolean`; execute only in `confirmOverlayRemoved()`. Add process-only `debugDepartureTicket` latch against intervening report collection. Retain `hasDetachedTerminalAuthority`, root/package checks, retry policy and callback ownership. Change `collect(root)` to `collect(root, context: StructuralCaptureContext)` and queue entries to node/parent/depth/child-slot/visit ordinal. |
| **New** `main/StructuralMetadata.kt` | Pure closed DTOs: `MetadataValue<T>` (value or enum reason), `StructuralCaptureContext`, `StructuralNodeMetadata`, `BoundsPx`, collection/item/range tuples, boolean bit masks, fixed action-ID/name table. No Android objects or arbitrary string maps. |
| **New** `main/AndroidStructuralMetadataReader.kt` | Public-API adapter `read(node, position, context): StructuralNodeMetadata`; narrow version-gated reads and immediate scalar copying. Event snapshot factory reads only the table below. Constructor accepts a test API ceiling clamped to actual `SDK_INT`; never permits calling above the real platform. |
| `main/SanitizedStructuralReport.kt` | Keep resource/class sanitizer; replace aggregation with indexed v2 records, deterministic serializer and existing hard caps. `Builder.add(StructuralNodeMetadata)`, `Builder(context,...)`, `build()`. Update every old `Builder.add(...)` caller; do not maintain two report formats. Retain `ShadowClassificationInput` interface. |
| `main/Observation.kt` | Preserve one-report lifetime, reveal/copy checks and single clipboard sink. Add `hideReport()` to reset only `revealed/copied` for Debug entry; version report-consent key as described below. |
| `main/MainActivity.kt` | Activity-owned navigation request state; `debugIntent(context)`, strict destination parsing, `onCreate`/`onNewIntent` handling; pass a consumed request to `DoomScreen`. Update disclosure and focus/scroll target for the existing report journey. |
| `main/OverlayRemovalTrace.kt`; service `traceAction()` | Add closed `USER_DEBUG`/`OPEN_DEBUG` enum values and cause membership; no report fields in removal trace. Preserve V2 row layout, limits and policy-release meaning. |
| `test/BreathingVisualsTest.kt`, `EntryGateOverlayModelTest.kt` | Geometry, timing, easing, palette, reduced-motion model contracts. |
| `test/SanitizedStructuralReportTest.kt`; **new** `StructuralMetadataTest.kt` | Full v2 schema, bounded values/serialization, unavailable markers, API eligibility and unsafe-ID cases. Adapt fixtures in `InstagramSurfaceShadowClassifierTest.kt`; classifier logic remains unchanged. |
| `test/OverlayRemovalPolicyTest.kt`, `InstagramEntryGateTest.kt`, `OverlayRemovalTraceTest.kt` | Debug arbitration, no terminal credit and unchanged trace bounds. |
| `androidTest/EntryGateServiceActionTest.kt` | Fake-platform detach/retry/authority/navigation/race tests. Extend existing harness; do not introduce reflection to inspect Android internals or discover metadata. |
| `androidTest/StructuralDiagnosticUiTest.kt`; **new** `StructuralMetadataApiTest.kt` | Adapter/public API fixtures, prohibited-content canaries, consent migration, Debug intent/reveal/copy/lifetime tests. New tests remain canonical. |
| `androidTest/EntryGateOverlayUiTest.kt`, `DoomUiTest.kt` | Factory signature, action hierarchy/reachability and shared rendering. Preserve five supplemental method identities and canonical capture names. |
| `scripts/test-entry-gate-host.py`, `test-structural-lifecycle.py`, `test-overlay-evidence.py`, `test-removal-trace.py` | Register new pure sources/tests; update exact metadata/consent/disclosure guards, callback order and enum expectations; strengthen evidence assertions without weakening them. |
| `scripts/overlay-evidence-manifest.py` | Keep exact fourteen names; add closed per-state meaning/elapsed/configuration metadata if needed, with paired host tests. No new arbitrary artifact files. |
| `AGENTS.md`, `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`, `app/src/main/res/values/strings.xml` | Narrow v2 privacy exception/disclosure, Debug cancellation semantics, current evidence matrix; fixture code/contract stays independent. |

No change to `InstagramMessagesRouter.kt`, timer policy/views, product settings persistence, signing/provenance code, workflows or fixture modules is planned. Existing tests using private Doom harness reflection need not be rewritten as an unrelated cleanup; all new metadata access uses public Android APIs, never reflection/hidden IDs.

## Bloom geometry and action layout

Use `s(t)=6t^5-15t^4+10t^3` with bounded float input. Inhale bloom is `s(local/4000)`; exhale is `1-s(local/6000)`. Preserve completion and multi-breath boundary behavior. Test exact values at 0, .25, .5, .75, 1 and zero first/second endpoint derivatives by bounded finite differences, plus monotonicity and cycle continuity.

The effective grid is **32 pitches across the drawable width**, not 32 total painted squares. Enumerate a fixed, bounded 32×32 lattice with the unique center at logical `(0,0)` (indices `(16,16)`); inactive outer cells absorb the even-grid asymmetry. Use `pitch=width/32f`, float coordinates, proportional gaps (no fixed two-pixel gap/minimum-one-pixel jump), a diamond-like radial envelope and continuous edge intersection/opacity. Outer width is `width*(.20f+.675f*bloom)`: 20% at minimum, 87.5% at maximum. Clip fractional boundary tiles to that envelope; never round radius, size, position or activation to an integer. Check **painted extents**, not merely an unused radius property.

Use a smooth Manhattan-distance mask for the diamond edge. Apply deterministic small edge delays, <=.04 of normalized activation (<=160 ms inhale / 240 ms exhale), increasing with radius and a fixed cell-coordinate pattern. Remap delayed activation to reach the same exact endpoints. No randomness or independent cell timers. Center remains full-opacity ivory `PAPER`; every other cell starts gold and transitions through burnt orange toward aubergine by normalized radial position. No interpolation to ivory outside `(0,0)`; test the actual RGBA output. Give a zero-sized canvas empty geometry and clamp non-finite progress to a documented safe static value.

Keep the 87.5% **width** target in landscape: fit the vertical envelope independently to available height, preserving the center, float geometry and visible controls. Do not silently replace width by `min(width,height)`, which would make landscape bloom much too narrow. Tests cover aspect ratios and record the intentional vertical compression. Reduced motion fixes geometry/colors at `staticProgress()` while phase labels, segmented progress and completion continue on the existing clock; honor system-disabled animations. Both Canvas adapters consume the identical pure rectangles/colors.

Add a quiet `Debug report` text-style button below Leave, >=48 dp touch target with wrap-content text. Skip to Messages stays primary and Leave remains prominent. No count, capture status, report text or copy feedback appears on the reminder. All three actions remain reachable with font scale 2.0 and 320×640 / 640×320 viewports and safe insets. Preview has no service authority; if a Debug affordance is shown there, it only closes preview and selects Debug locally.

## Public API availability and privacy reconciliation

The following is the complete proposed node/event getter inventory. API <=26 is callable on every supported device; later calls must be behind their exact SDK guard. Values can still be absent/default/unreliable on supported versions. Do not fabricate unavailable values as `0`/`false`. API 36+ APIs visible in current online docs are excluded from this SDK-35 project.

| Source/getters | Added API | Output/availability rule |
| --- | --- | --- |
| Node `getPackageName`, `getClassName`, `getChildCount`, `getChild(int)` | 14 | Fixed IG package token after validation; closed normalized class; bounded count; child access only within existing BFS budget. |
| Node `getViewIdResourceName` | 18 | Existing exact resource grammar/copy rules; unavailable if absent/rejected. |
| Node `getBoundsInScreen(Rect)`, `getWindowId` | 14 | Copied integer rectangle and window ID; negative sentinel ID unavailable. Screen bounds are platform-reported and may include magnification effects. |
| Node `getBoundsInWindow(Rect)` | 34 | Copied window-coordinate rectangle; API 26–33 `u:api`. Never substitute parent bounds or subtract a guessed window origin. |
| Node `getDrawingOrder` | 24 | Sibling-relative integer, not a global z-order; duplicates are legal. |
| Node `getUniqueId` | 33 | Strict safe-value policy below; API 26–32 `u:api`. |
| Node `isCheckable`, `isChecked`, `isClickable`, `isEnabled`, `isFocusable`, `isFocused`, `isLongClickable`, `isPassword`, `isScrollable`, `isSelected` | 14 | Known/value boolean bit masks. |
| Node `isAccessibilityFocused`, `isVisibleToUser` | 16 | Boolean bits. |
| Node `isEditable` | 18 | Boolean bit; never text access. |
| Node `canOpenPopup`, `isContentInvalid`, `isDismissable`, `isMultiLine` | 19 | Boolean bits. |
| Node `isContextClickable` | 23 | Boolean bit. |
| Node `isImportantForAccessibility` | 24 | Boolean bit. |
| Node `isShowingHintText` | 26 | Boolean only; no hint getter. |
| Node `isHeading`, `isScreenReaderFocusable` | 28 | Below 28 unknown bits; no relation traversal fallback. |
| Node `isTextEntryKey` | 29 | Below 29 unknown bit. |
| Node `isTextSelectable` | 33 | Below 33 unknown bit. |
| Node `isAccessibilityDataSensitive` | 34 | Below 34 unknown bit; does not authorize reading any content. |
| Node `isGranularScrollingSupported` | 35 | Below 35 unknown bit. |
| Node `getActionList`; `AccessibilityAction.getId` | 21 | Up to 16 IDs; stable names from Doom's fixed table, never `getLabel`/`toString`. |
| Node `getCollectionInfo`; CollectionInfo `getRowCount`, `getColumnCount`, `isHierarchical` | 19 | Optional copied tuple; null `u:absent`. |
| CollectionInfo `getSelectionMode` | 21 | Numeric member. |
| CollectionInfo `getItemCount`, `getImportantForAccessibilityItemCount` | 35 | API 26–34 individual `u:api`; no row×column inference. |
| Node `getCollectionItemInfo`; CollectionItemInfo `getRowIndex`, `getRowSpan`, `getColumnIndex`, `getColumnSpan`, `isHeading` | 19 | Optional copied numeric/boolean tuple. |
| CollectionItemInfo `isSelected` | 21 | Boolean member; no row/column titles. |
| Node `getRangeInfo`; RangeInfo `getType`, `getMin`, `getMax`, `getCurrent` | 19 | Optional type/finite-float tuple. |
| Node `getInputType`, `getLiveRegion` | 19 | Numeric values, including unknown future values. |
| Node `getMovementGranularities` | 16 | Numeric bitmask only. |
| Node `getTextSelectionStart`, `getTextSelectionEnd` | 18 | Numeric offsets; `-1` means unavailable, never fetch text to validate lengths. |
| Node `getMaxTextLength` | 21 | Numeric limit; negative sentinel unavailable. |
| Event `getPackageName`, `getEventType` | 4 | Package attribution only, as today; fixed IG owner in report. Type is integer. |
| Event/record `getClassName` | 14 | Existing own-package MainActivity-return check only; no event class string in report. Compare with the fixed Doom Activity name without retaining it. |
| Event `getContentChangeTypes` | 19 | Integer mask for accepted state/content events; zero means reported zero. |
| Event `getWindowChanges` | 28 | Only for `TYPE_WINDOWS_CHANGED`; current subscription makes this `u:not_subscribed`. No event subscription/interactive-window capability expansion. |
| Event `getAction`, `getMovementGranularity` | 16 | Numeric metadata when meaningful for that event; otherwise `u:not_applicable`. No source/record traversal. |
| Doom-generated index, parent index, depth, BFS ordinal and sibling child-slot | None | Assigned in bounded traversal; root parent/child-slot `u:root`. BFS is traversal order, not Android accessibility focus order. |
| Screen dimensions and capture-relative monotonic offset | Non-node APIs | `WindowManager.defaultDisplay.getRealMetrics` (17) sampled once, `DisplayMetrics` width/height/densityDpi; `SystemClock.elapsedRealtime` (1). No wall clock/event-time subtraction. |

References checked for this plan: [node getters](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo), [event getters](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent), [collection](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.CollectionInfo), [item](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.CollectionItemInfo), [range](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.RangeInfo), [action ID versus label](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction). Verify with SDK-35 compilation/lint; the newest documentation also lists unsupported newer APIs.

**Unique-ID constraint:** a public `uniqueId` is an app-supplied String, not a guarantee of non-content data. Do not admit arbitrary bounded strings, UUID-shaped strings or hashes of them merely because this getter is public. Read only on API 33+; emit its value only when it equals this node's already accepted static resource ID, copying that already-safe token. Otherwise emit `u:free_form` (null `u:absent`). No raw unique-ID retention, character dump or hashing. This explicitly resolves the ticket's public-uniqueId request under its stronger exclusion of uncontrolled free-form values; general opaque unique-ID discovery would require a separately approved privacy contract. Keep window ID and Doom-local indexes available independently.

**Never-invoke list**, including Kotlin property syntax and inherited/compat equivalents: node/event/record `getText`, `getContentDescription`, `getBeforeText`, `getHintText`, `getError`, `getStateDescription`, `getPaneTitle`, `getTooltipText`, `getContainerTitle`, `getSupplementalDescription`; item `getRowTitle`/`getColumnTitle`; action `getLabel`; event/record `getParcelableData`; node `getExtras`, `getAvailableExtraData`, `getExtraRenderingInfo`; bundle/extras values or key enumeration; notification content/ticker/message getters. Never call node/event/action/info `toString()` or stringify exceptions; no raw object `hashCode()`/identity dump. No event `getSource`/`getRecord`, text search, `refreshWithExtraData`, extra-data requests, `getLabelFor`/`getLabeledBy`, `getTraversalBefore`/`getTraversalAfter`, `getParent`, `getWindow`, touch-delegate traversal or hidden source/connection IDs. The last group prevents unbudgeted graph expansion; parent/traversal metadata comes only from Doom's existing walk. No clipboard reads in production. Existing synthetic clipboard assertions are test-only.

## Bounded v2 data and wire format

Keep one `SanitizedStructuralReport(text,truncated,shadowInput)`, no second retained raw/DTO report or history. Builders hold <=128 immutable scalar node DTOs and a queue whose visited+queued count never exceeds 128. A queue item owns one transient Android node and its Doom traversal position; release all acquired nodes on normal, skipped and exceptional paths. Validate package **before** any other node metadata/children. Foreign/unattributed children consume budget, mark truncation and never get a row or descendants. No framework event, Rect, node, action, collection/item/range object, caller-owned CharSequence or callback survives capture.

- Retain `MAX_NODES=128`, `MAX_DEPTH=8`, reported `MAX_CHILDREN=16`, `MAX_UNIQUE_TOKENS=64`, `MAX_CHARS=8192` (ASCII == UTF-8 bytes). Richer reports will contain fewer rows at the byte limit; state this limitation. Traverse child slots using the existing remaining-node budget, not a new 16-child traversal cap.
- Each accepted row has `n` (Doom index), `p` (parent), `d` (depth), `t` (visited BFS ordinal), `s` (original sibling slot), `draw`, `children`; indexes may have gaps after foreign nodes. Root index is 0; every emitted non-root parent must already be emitted. Stop row emission at the byte boundary; never cut a row or leave dangling parent references.
- Fixed IG package token in header; resource ID and class remain copied through existing exact sanitizers. Choose <=64 tokens lexically as before, but replace excluded token **fields** with `u:token_limit` rather than removing indexed parent rows. Resource-valued uniqueId shares this token budget. No generic user-controlled key/value map.
- Bound actions to the first 16 platform-list entries inspected (constant work even for a maliciously huge list); copy only IDs, deduplicate and sort the retained IDs. Record reported list size and `actions_truncated`. Generate names from a literal SDK-35 numeric table: all legacy ACTION_* IDs plus public AccessibilityAction IDs through API 35; unknown/custom IDs use `UNKNOWN`. Never inspect labels even for unknown IDs. Do not execute any reported action. Platform constants/names in this table are code-owned, not obtained from action objects.
- Scalars use signed Int/Long where applicable, with meaningful sentinels mapped to reasons. Counts beyond the existing child cap show 16 and a cap marker. Do not clamp IDs, action masks or offsets into misleading valid values. Range members must be finite; type or order inconsistency is `u:invalid`. Floats use locale-independent bounded decimal/exponent encoding, with negative zero normalized.
- Bounds are four copied Int coordinates; accept negative/off-screen coordinates, reject inverted rectangles, and retain legitimate empty rectangles as empty (not proof of visibility). Screen width/height must be positive. Normalize each screen coordinate as `round(coordinate*10000/axisDimension)` using Long/Double intermediates; these are signed ten-thousandths, not clamped to [0,10000]. Invalid dimensions yield `u:dimensions`; mark overflow unavailable. Window bounds remain independently unavailable below 34. Dimensions are default-display metrics; they do not prove secondary-display/magnification coordinate alignment.
- `dt_ms` is per-node elapsedRealtime minus capture-start elapsedRealtime, bounded to 0..10,000 ms; rollback invalidates capture, exceeding 10 seconds stops with a time-limit truncation. This is a report offset, not gate timing, wall time or event age. Header contains only sampled integer dimensions/density/API, event metadata and closed availability/truncation markers. Do not call `getEventTime` or mix uptime and elapsedRealtime.
- Per-field unavailable enum vocabulary: `api`, `absent`, `invalid`, `read_error`, `not_applicable`, `not_subscribed`, `root`, `dimensions`, `free_form`, `token_limit`. Boolean fields use fixed-position `known`, `value`, `error` bit masks (value bits must be a subset of known); absent older APIs leave known unset. Header defines the exact bit order from the API table. Header truncation reasons are a bounded set: nodes/depth/children/foreign/missing_child/tokens/actions/bytes/time. Supported-but-unset metadata is distinct from unsupported API. Getter errors use only `read_error`, never exception messages.

Serialization is versioned ASCII, fixed key order and LF endings, using a small hand-written serializer (no new library). Header starts `sanitized-structure-v2`; schema/tuple/bit definitions appear once. Each node is one line of fixed-order `key=value` columns; tuples use commas and action entries `numericId:PLATFORM_NAME` separated by semicolons. Unavailable scalars are `u:<reason>`. Empty action list is `[]`, distinct from unavailable. Header includes `truncated=0|1`, reasons, `visited`, `emitted`, `api`, `screen=w,h,dpi`, `event=type,contentMask,windowMask,action,movement`, `package=com.instagram.android`. Example fragment (not a complete row):

```text
sanitized-structure-v2
truncated=0 reasons=[] visited=1 emitted=1 api=35 screen=1080,2400,420
package=com.instagram.android event=32,0,u:not_subscribed,u:not_applicable,u:not_applicable
...
n=0 p=u:root d=0 t=0 s=u:root draw=0 children=0 id=com.instagram.android:id/direct_tab class=View win=7 uid=u:absent screen=0,0,1080,2400 window=0,0,1080,2400 norm=0,0,10000,10000 ... dt_ms=0
```

Complete row key order is `n,p,d,t,s,draw,children,id,class,win,uid,screen,window,norm,flags,actions,action_count,actions_truncated,collection,item,range,input,live,movement,selection,max_length,dt_ms`. `flags=known,value,error` uses unsigned decimal masks, with bit 0 onward in the boolean-getter order of the API table; collection/item booleans stay in their own tuples. `collection=rows,columns,hierarchical,selectionMode,itemCount,importantItemCount`; `item=rowIndex,rowSpan,columnIndex,columnSpan,heading,selected`; `range=type,min,max,current`; `selection=start,end`. Non-mask booleans serialize as 0/1. Each absent tuple is one unavailable marker; individual unsupported members use their own marker. Coordinates always order left,top,right,bottom. These fixed keys/tuple orders are also the DTO field contract; no `Any`, Android objects or arbitrary string-valued fields.

Construct event/dimension context only after existing consent/connection/cooldown/visible-overlay early returns and verified IG root attribution, immediately before `collect`. Copy event scalars synchronously; never queue the event. Optional event fields not meaningful to the accepted event type are unavailable. Capture the default-display metrics once per report; no additional root/window discovery or screen-content access. New node indexes describe only the existing bounded traversal.

Freeze exact complete header/row golden fixtures in RED, including every table field, tuple order and boolean bit order. Reserve a bounded worst-case header before emitting rows; headers and rows together never exceed 8,192 bytes. Smaller injected test limits must fit that header plus one minimal row. Empty traversal remains unavailable. No partially built report replaces the old one on an exception; follow existing safety invalidation instead. Optional individual metadata getter failures produce typed unavailable values; root/package/child acquisition or authority failures abort/clear through the current service safety path. Recheck report consent/connection before `Observation.record()`; never preserve a report after revocation because it was collected earlier.

Preserve the classifier's input type and existing resource/selected/scrollable signal rules, including messaging precedence and truncation behavior. Build its bounded sets from sanitized token-admitted DTOs, as the current builder does, independent of the v2 text row byte cutoff; no new metadata becomes a selector or action authority. Document that distinction. Report format v1 is process-only, so no parser/migration/backward storage support is needed.

## Debug removal transaction and Activity state

1. The actual button callback captures its installed `OverlayCallbackToken`. Before requesting `OPEN_DEBUG`, require `acceptsVisible(token)`, same view/token/ticket/generation, GATING, reminders enabled, both consents and connection. Invalid/stale callbacks are inert and cannot mutate a replacement report/session. No report is required: Debug must still explain unavailable reports.
2. Enter existing closing/removal machinery: cancel completion/watchdog callbacks, dispose all buttons, request policy action, attempt immediate removal and verify `isAttachedToWindow`. Do not launch, cancel the gate, grant credit or hide/reveal/copy report while physically attached. Repeated taps are rejected once closing. If removal throws but attachment is false, proceed; if still attached, preserve manager/view and retry every 50 ms, <=20 attempts, then veto and disable as today.
3. Precedence (low→high) becomes COMPLETE, NAVIGATE_MESSAGES, PRESERVE_REPORT, OPEN_DEBUG, BYPASS, HOME, RESET_OUTSIDE. All existing relative priorities stay unchanged. Safety cleanup irrevocably vetoes OPEN_DEBUG as well as other external actions; later taps cannot revive it. A verified direct Doom return during closing may preserve the report but cannot remove a safety veto. Tests cover every pair involving the new action and exhaustion.
4. In `confirmOverlayRemoved`, require `ownsDetachedEpisode`, `hasDetachedTerminalAuthority(token,GATING)` **and** reminders still enabled. Sample only the current root's package and recycle exactly once. Require verified IG, or verified Doom with `mainActivityReturnObserved`; reject null/foreign/unattributed/throwing roots. Recheck all authority after this potentially reentrant read. No report metadata lookup, root traversal or Direct-tab query here.
5. On accepted OPEN_DEBUG: end the Instagram timer session through its current detach path (a gate must have no attached timer view); `entryGate.bypass(ticket)` retains the visit latch and cancels visible timing without cooldown. Set `terminalGateSucceeded=false`, publish state, keep the identical `Observation.report`, call `hideReport()`, set `debugDepartureTicket=ticket`, consume detached token, and invoke `overlayPlatform.openDebug()` exactly once. Do not call `complete`, `recordTerminal`, `beginMessagesRoute`, `finishMessagesRoute`, or `recoverFailedMessagesRoute`; no bubble resume/credit. Keep the bypassed ticket until the existing verified MainActivity return resets OUTSIDE. While the departure latch matches the current bypassed ticket, IG events return after consent/safety checks and before new root/report/timer work: bypass alone currently prevents a gate but **does not prevent recollection**. The verified Doom return clears the latch, preserves the report and resets the timer as today. Foreign cleanup, revocation, disconnect, service reset and ticket replacement clear it; it must never suppress their cleanup or a replacement session.
6. A false/throwing launch leaves the overlay detached, session bypassed, departure latch active, timer stopped and report hidden/preserved for manual Doom return. No retry, Home/chooser/browser fallback, overlay restoration, cooldown or automatic export. Revoked/disconnected/foreign safety paths still clear the report. Stale detached callbacks never cancel a newer ticket or clear its report; operate only on the owned episode. Add explicit rejection assertions for root-read reentrancy and replacement-token races. An attachment-query exception is unknown attachment, never successful detachment; consume the existing bounded retry budget and disable on exhaustion.

`MainActivity.debugIntent(context)` returns an explicit component Intent with a fixed internal action such as `com.chardyb.doom.action.OPEN_DEBUG`, no URI/categories/selector/ClipData/report extras, and `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`. This is direct internal navigation, not an Instagram deep link. Manifest remains launcher-only: no VIEW/BROWSABLE filter, exported endpoint, task-affinity or launchMode changes. Reference: [Intent flags](https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_CLEAR_TOP) and [Activity.onNewIntent](https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent)).

Treat the action as a **destination hint, never authorization**, because MainActivity is already exported. `onCreate` applies a recognized Debug action before initial composition; normal cold launch defaults Home. `onNewIntent` calls super, `setIntent`, parses only the exact fixed action with no URI, and publishes a monotonically increasing, process-only request sequence. Malformed/unknown input grants nothing; preserve current destination on an unrelated warm intent. Do not deserialize unknown extras. A Debug request overrides restored HOME and cancels active product preview/demo through their existing cancellation functions; it does not run their completion callbacks. A repeated valid request while already on Debug must work again.

Pass request/consumption to `DoomScreen`; consume once per request using a sequence, clear the recognized action on the Activity Intent, and do not replay a consumed request on rotation/recomposition. `rememberSaveable` may continue storing only selected destination/reduced-motion UI preference; never store report bytes, reveal/copy flags, pending service token or request payload. New process has no report even if Android restores Debug. Separate Debug report scroll state/anchor from Home so a warm launch from a scrolled page reveals the report **controls** (via BringIntoViewRequester or measured scroll), not report contents. Hide previous reveal/copy-success state on an explicit Debug entry; existing controls still require consent/connection and explicit reveal/copy. Back leaves/cancels preview normally and never reconstructs the service overlay or grants cooldown.

## Consent, docs and host guards

The broadened report exceeds the old disclosure. Replace the report-consent preference key with `sanitized_structural_report_v2`; on load/accept remove obsolete `accepted`, `structural_fingerprints_v1`, and v1 report authorization and default v2 consent false. Do not infer v2 consent from v1. Preserve gate opt-in, timer preference and reminder settings independently. This replaces one boolean, adds no persistent report/state. A service upgraded with old consent must disable rather than collect v2 until the user accepts the updated disclosure. Test both absence and explicit false v2, old true v1, v2 true round-trip and either-consent revocation. No unrelated settings migration.

In AGENTS, replace the blanket coordinates/raw-tree prohibition only with: bounded scalar bounds, Doom-local relationship indexes, the named numeric/boolean metadata and sanitized identifier fields may enter the v2 consented process-memory report. They may never select targets or drive actions. Raw trees/framework objects, arbitrary IDs/free-form values and private screenshots remain forbidden. Extend “physical detach before actions” to Debug launch. Keep all signing, exact-SHA, Direct-tab, stale-ticket and consent rules intact.

Update current README, accessibility service disclosure string, MainActivity Debug copy and validation scope to describe v2, all caps/markers, sensitive geometry/identifier metadata, fresh consent, hidden report preservation and terminal-only cooldown. Remove obsolete claims that bounds/window IDs/actions are excluded or that report status/copy appears on the reminder. Keep the strong content exclusions, no-network/persistence promises, and unverified real-Instagram status. Keep historical test/run records clearly historical. Only correct FIXTURE's current lane-count summary; fixture traversal, privacy, thirteen captures and acceptance remain unchanged.

Strengthen `test-structural-lifecycle.py` rather than deleting its old exact node-getter check: explicitly enumerate all adapter node/event/nested-info getters, permitted call sites and SDK guards; reject both Java getter and Kotlin property spellings for prohibited APIs across **all new adapter helpers**. Guard against aliasing/stringification/extra-data escape routes and generic serializers. Make the guard accept source text and mutation-test it in memory with forbidden calls injected under different receiver names; no file mutation or Android needed. Keep package-before-metadata, bounded traversal/recycling, no-source/no-extra-window/no-action, lifecycle clearing, process defaults, permissions, single sensitive clipboard sink and exact Direct-tab guards. Change v1 key/disclosure/queue syntax expectations narrowly. Register `StructuralMetadata.kt` and its pure tests in the host runner. Preserve existing timer/removal/CI isolation guards; extend closed trace enums without widening trace payloads.

## TDD slices and cheapest checks

Each slice starts with an observed failing assertion on the exact base/candidate, not only an unresolved-symbol compile failure. Record the first causal RED and its focused GREEN; then run the combined checks once. Android runtime RED/GREEN occurs only in authorized GitHub API-35 CI.

| Slice | RED expectation | Minimal GREEN implementation/check |
| --- | --- | --- |
| G1 geometry/easing | Existing frame fails quintic sample (.25); current geometry fails continuous intermediate widths, 20%/87.5% extent, 32-pitch and one-ivory-cell assertions. | Pure geometry/frame + both adapters; `python3 -B scripts/test-entry-gate-host.py`; later native/Compose render comparison on API 35. |
| G2 reduced motion/layout | Static snapshots differ or new Debug action is absent/unreachable at 2× fonts/landscape; current layer test allows five ivory cells. | Extend pure model tests and existing supplemental UI methods; keep 4/6 phases and segment completion tests. Host suite, then supplemental API-35 lane. |
| R1 schema/sanitizer | A baseline synthetic report fails v2/header/index/parent/numeric field assertions; duplicate nodes are wrongly merged. | Typed DTO/serializer tests for every field, identical-input determinism, unavailable reasons, 128/129 nodes, depth 8/9, child 16/17, 64/65 tokens, exact/over byte and action limits, whole-row/parent integrity and immutable output. Host runner. |
| R2 public adapter/privacy | Host allowlist/SDK checks fail for missing adapter; canary fixture fails expected numeric output, with no canary content emitted. | Adapter reads table fields only; synthetic API-35 nodes/events set prohibited text/labels/extras to canaries without querying them. API-ceiling cases 26/27/28/29/32/33/34/35 assert unavailable bits/fields; lint guards actual call availability. Host guards + unit checks, then canonical API-35 instrumentation. |
| R3 collection/lifetime | Indexed report loses parent/sibling ordering; child/root failures or consent loss leave bad/stale state; v1 consent erroneously authorizes expanded report. | Queue positions, typed capture, scalar-only recycling, versioned consent, replacement/clear/reset tests; host guards followed by `StructuralDiagnosticUiTest`/adapter canonical tests. |
| D1 policy | `OPEN_DEBUG` behavior missing: no retained report or cancellation without terminal credit; new action survives safety veto in initial RED stub. | Pure removal precedence/exhaustion, trace cause and gate bypass/cooldown tests; host runner and removal guards. Retain terminal success and 59,999/60,000 ms tests. |
| D2 physical action | Real callback cannot reach Debug; fake launch occurs while attached/in wrong state or loses report; stale/revoked/disconnected cases launch. | Service branch + platform seam; canonical action tests assert call order `remove → attached=false → current authority/package → bypass → launch`, no launch while retries pending, once only, preserved object identity, no cooldown, stopped timer, old callbacks inert. |
| D3 Activity/copy | Cold/warm Debug action opens Home or leaves preview active; rotation replays request; copy is possible without reveal. | Intent handler and consumed navigation state; canonical cold/warm/repeated/rotated/recreated/malformed/no-report tests plus explicit reveal, exact bounded copy, sensitive flag, denied-copy sentinel unchanged, replacement resets and clipboard failure. |
| E1 evidence/docs | Wrong phase time, unreachable new action, stale status wording, incorrect screenshot inventory or absent API-state definitions fails host assertions. | Update docs/guards and capture helpers, preserve evidence lane sets; host evidence/isolation/signing checks, full no-emulator preflight, then separately authorized canonical/supplemental CI/readback. |

D2 negative matrix must include missing overlay, not-yet-shown ticket, obsolete token/epoch/generation, already-closing duplicate tap, premature COMPLETE race, report null/replaced, disabled reminders, either consent revoked, disconnected/interrupted/unbound/destroyed service, null/foreign root, root read exception, revocation/replacement during read, attach-state exception, delayed detach, exhausted removal, false/throwing launch, direct-Doom return and foreign safety override. Inject IG events between detach/launch/Activity return and after launch failure: assert zero recollection/timer restart and report identity preserved; inject foreign/revocation/replacement events and assert the latch cannot override safety. Assert no root/action reads on preflight rejection, no replacement mutation, exactly-once recycling where acquired, no new terminal credit and no report preservation on safety cleanup. Existing Skip routing and timer race suites remain required regression evidence.

## Evidence and failure/rollback

Canonical `Android diagnostic CI` retains all unannotated service/privacy/router/navigation/metadata tests and four `DoomUiTest` screenshots. New Debug navigation/reveal/copy assertions are canonical **without report screenshots**. Supplemental UI layout failures stay independently visible; core-flow/privacy/safety/build/install/evidence/signing defects block regardless of lane. No new `@SupplementalEvidence` methods or relaxed inventory checks.

Keep these fourteen supplemental files exactly: `01-home`, `01-overlay-unavailable`, `02-debug`, `02-overlay-captured-status`, `03-reminder-inhale`, `03-overlay-reduced-motion`, `04-reminder-exhale`, `04-overlay-large-font`, `05-reminder-reduced-motion`, `05-overlay-landscape`, `07-timer-expanded-dismiss`, `08-timer-compact-icon`, `09-dashboard-timer-disabled`, `10-dashboard-timer-reenabled` (all `.png`). Historical “captured-status” names remain compatibility identifiers, not permission to show report status. Use deterministic `elapsedMs` arguments instead of current `captured:Boolean`→time coupling: inhale maximum at 4,000 ms is the **phase boundary into OUT**, so capture named inhale at 3,900 ms with IN asserted and exhale at 9,900 ms with OUT asserted; pure endpoint tests cover exact extrema. Reduced captures fix geometry and separately assert advancing phase/segments. Real font-scale/orientation setup and restoration, draw wait, foreground checks, and all timer captures remain.

Use `02-debug` for the actual fixed-action Debug navigation destination, with report hidden; canonical tests prove reveal/copy on synthetic data without capturing it. Assert all three reminder controls/no diagnostic text before each applicable capture. No screenshot of a revealed report, clipboard preview or private Instagram screen. Validate exact filenames, PNG signatures, APK digest, source SHA/run/attempt and per-state definitions; inspect the images for visual claims. Fixture keeps thirteen images and independent APK. Never conflate supplemental APK bytes with canonical signer input.

Failure rules: optional field unavailable stays explicit; invalid collection/authority clears through existing safety path; detachment uncertainty never releases navigation. Clock rollback cannot complete a gate or produce a fabricated report offset. Launch failure leaves manual Debug review possible. Clipboard sink failures leave `copied=false`; clearing/revoking cannot recall an already explicit copy. Process death drops report/navigation request/reveal/copy state; no reconstruction from Intent or saved state. A Debug cancel never erases an already legitimately earned cooldown, extends it, or earns a new one.

Rollback is a source revert of the cohesive v2 consent/adapter/schema/navigation change and its matching docs/tests, followed by the same verification at the rollback SHA. Bloom can be reverted separately if its failure is purely visual. Do not ship a v2 collector with v1 disclosure, disable guards, add a runtime bypass flag, restore raw metadata, or use a previous-SHA APK/evidence as proof. Reverted v1 code sees its old consent removed and requires fresh consent rather than silently restoring authorization. No dependency/schema migration or stored-report cleanup is needed.

## Exact verification sequence for implementation

At implementation start verify cwd/base and status; subsequent checks use the actual implementation candidate, never label modified code as the base's evidence:

```bash
test "$PWD" = /mnt/HC_Volume_106820083/worktrees/doom/wil-196-sunset-debug-report
test "$(git rev-parse HEAD)" = c49e7ea3730807623eb36841afa25f107073cca0
git status --short
python3 -B scripts/test-entry-gate-host.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test_wil155_host.py
python3 -B scripts/test-session-timer-host.py
python3 -B scripts/test-removal-trace.py
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-android-junit-validator.py
python3 -B scripts/test-integrated-ci-harness.py
python3 -B scripts/test-internal-signing.py
bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh
python3 -B - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for path in Path('app/src').rglob('*.xml'):
    ET.parse(path)
print('app XML parsed')
PY
git diff --check
```

Configured Java-17/SDK-35 Codex Cloud, no SDK installation/update and no emulator:

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
python3 -B - <<'PY'
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET
totals = {k: 0 for k in ('tests', 'failures', 'errors', 'skipped')}
for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(root.get(key, '0'))
print('unit totals', totals)
lint = ET.parse('app/build/reports/lint-results-debug.xml').getroot()
print('lint errors', sum(i.get('severity') in ('Error', 'Fatal') for i in lint.findall('issue')))
apk = Path('app/build/outputs/apk/debug/app-debug.apk')
print(apk, apk.stat().st_size, hashlib.sha256(apk.read_bytes()).hexdigest())
PY
```

Require exit 0 and read signing-test count, nonzero unit-test count, failure/error/skip counts, lint and APK path/size/SHA-256. Fix the first deterministic failure before independent review. Compilation is not runtime evidence. Freeze a passing candidate unless review finds a beta blocker.

Only an authorized GitHub Actions run on a clean exact candidate owns device checks. Keep checked-in workflow setup/readiness/provenance and run, within its respective API-35 emulator:

```bash
# Canonical workflow:
bash scripts/ci-device.sh
python3 scripts/validate-android-junit.py canonical app/build/outputs/androidTest-results/connected

# Independent supplemental workflow (separate checkout/run):
bash scripts/ci-supplemental.sh
```

The scripts own instrumentation annotation filters, fixture build/install and evidence collection. Do not execute these device commands locally, fake CI variables, reroute signer inputs, or bypass clean-SHA checks. Read back baseline reports, canonical tested APK/manifest/four PNGs/complete JUnit, and separate supplemental fourteen-PNG and fixture thirteen-PNG manifests/JUnit/diagnostics. Reconcile methods through the source-derived JUnit validator; no historical test-count assumption. At most one unchanged-SHA rerun after classifying infrastructure failure; repeated failures require a defect. Protected signing remains a separate authorized main-only workflow with provenance, certificate and non-signature APK-payload comparison unchanged.

Actual Instagram claims additionally require an explicitly consenting phone and exact verified candidate: repeated Debug tap/removal/no-cooldown/manual-return/revoke/restart checks and the existing WIL-149 high-risk matrix. Record versions, timings, broad surface and pass/fail only. No private screen/content capture or automated clipboard/report export. API-35 synthetic evidence proves implementation behavior only; phone validation remains unexecuted by this plan.

## Scope exclusions

No production/test edits in this planning task. Future implementation adds no network/storage/permissions, raw report persistence/history, clipboard reads, screenshots in production, logs/uploads/automatic export, notification or event-source access, arbitrary unique-ID output, extra node graph traversal, action-label reads, reflection/hidden Android APIs, broadened event subscription, selector/classifier/routing expansion, gestures/coordinates for actions, cooldown/timer/settings redesign, dependency/toolchain updates or fixture/signing changes. No claim that a metadata report identifies the screen reliably or that Debug/fixture evidence proves real Instagram or DM safety.

## Requirement-to-test traceability

| Requirement | Required test/evidence |
| --- | --- |
| Effective 16→32 grid, smaller exhale, 85–90% inhale | G1 `BreathingVisualsTest`: 32 pitch, actual 20%/87.5% extents across portrait/landscape; API-35 inhale/exhale images. |
| 4s/6s, continuous float/smootherstep, stagger | G1 exact phase/sample/endpoint/continuity tests, sub-cell movement and deterministic bounded edge-delay assertions. |
| Exactly one ivory center, gold→orange/aubergine | G1 actual generated rectangle/color tests over dense progress samples; image inspection. |
| Reduced motion with ongoing phase/progress | G2 static geometry identity plus advancing frame/segments/completion; system-disabled animation and reduced captures. |
| Small Debug action, dominant exits, no report/status on reminder | G2 native semantic/layout tests; font 2.0, landscape, insets and no diagnostic text assertions. |
| Physical detach before Debug, no cooldown, preserve report | D1/D2 policy/service order, attached retry/exhaustion, no terminal calls/credit, identical report identity, timer stopped. |
| Invalid/stale/revoked/disconnected rejection | D2 full negative/race matrix, no replacement mutation, no post-veto launch. |
| MainActivity cold/warm/deep-link state | D3 exact explicit Intent, no URI/payload, onCreate/onNewIntent, repeat/rotation/process recreation, preview cancellation, report anchor. |
| Node/parent/depth/traversal/drawing/child metadata | R1/R3 indexed BFS fixtures, gaps/parent integrity, child budget and scalar caps; public adapter API tests. |
| Package/resource/class, bounds/normalization/dimensions, window/unique ID | R1/R2 sanitizer/golden tuples, off-screen/empty/overflow bounds, API 33/34 guards, free-form uniqueId unavailable cases. |
| Broad booleans/actions/collection/item/range/input/live/movement/selection | R1 every-field golden and boundary tests; R2 API-ceiling/public API tests, custom action ID with no label access. |
| Event/content/window types, monotonic offset, unavailable/truncation | R1/R2/R3 exact masks/reasons, not-subscribed marker, no event retention, rollback/time and node/token/action/byte limits. |
| No uncontrolled getters/raw objects/logs/storage/export | R2 prohibited-getter allowlist plus in-memory mutation checks/canaries; lifecycle/source guards; no-permission and single-sink checks. |
| Existing reveal/copy and process lifetime, fresh consent | R3/D3 consent migration, explicit reveal/copy-sensitive/denied/failure tests, new-report reset, clear/revoke/disconnect/reconnect/process defaults. |
| Direct-tab-only authority, timer and terminal-only cooldown unchanged | Existing router/service/timer suites, exact 59,999/60,000 terminal boundaries and stale-ticket tests; new metadata never read by router. |
| Narrow AGENTS/privacy changes and guarded schema | E1 exact disclosure/consent/getter/serializer source checks; review file scope against this map. |
| Canonical/supplemental/provenance/signing contracts | E1 host evidence/isolation/signing checks; exact-SHA canonical four, supplemental fourteen, fixture thirteen plus full lane JUnit; protected signing unchanged. |
| No real-Instagram claim without consenting phone | Validation docs and artifact `actual_instagram_verified=false`; separate unexecuted consenting-phone matrix. |
