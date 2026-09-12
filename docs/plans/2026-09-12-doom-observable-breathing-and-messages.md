# Doom observable breathing and messages implementation plan

> For the implementation controller: apply writing-plans and test-driven-development task by task. The generic skill's subagent execution template is intentionally superseded: use one Codex CLI Luna-high worker and one independent Claude Code Opus reviewer, sequentially. This planning assignment authorizes no worker launch or implementation.

**Goal:** Give the diagnostic Instagram accessibility overlay Doom's breathing aesthetic, an honest in-place sanitized report status/copy boundary, and a best-effort inbox action that cannot execute before physical overlay detachment; display the installed build identity in Doom.

**Architecture:** Keep the existing AccessibilityService, InstagramEntryGate and physical-removal authority. Replace the plain overlay content with an app-owned Android View hierarchy and a small Canvas pixel view; mirror the existing Compose pixel geometry using shared pure presentation primitives, not a Compose runtime mounted in the service. Keep report ownership/export in Observation and isolate a single package-targeted inbox Intent adapter behind a testable launcher.

**Tech stack:** Kotlin/JVM 17, Android Views/Canvas, existing Compose/Material3 in MainActivity, AGP 8.7.3/Kotlin 2.0.21, existing JUnit and Android instrumentation, Python host source guards, GitHub-hosted Android CI and the protected internal signer. No new runtime library or permission is planned.

---

## 1. Global constraints and inspected baseline

Planning root: `/mnt/HC_Volume_106820083/worktrees/doom-wil149-stageb`.

Inspected exact HEAD: `9bf29169271010d9b36c8d6e316c97f46ed49795`. Initial `git status --short` was empty. Existing draft PR: #3, supplied by the controller/user; remote PR state was not queried. All paths below are relative to that root. This document is the only authorized write in this planning turn. No production/test/workflow edits, git mutation, credentials, network publication, Gradle, Android tooling, emulator, adb, or subagents are authorized here. Historical validation in repository documents is not fresh test evidence.

Future implementation is separately authorized work. Commit boundaries below are instructions for that future controller/worker, not permission to commit now. Keep PR #3 draft. Neither this plan nor a review approval authorizes merge, production release, default-on consent, or a DM-safe claim. Stop at a verified draft PR and protected signed internal prerelease, with unresolved phone gates explicitly recorded.

### Required invariants

1. Preserve two independent persisted, default-false consent booleans. Report consent and connected observation remain prerequisites for the live gate, in addition to gate consent. Do not migrate old consent into new authorization.
2. Keep `TYPE_ACCESSIBILITY_OVERLAY`, the service's existing window flags and Instagram-only tree access. No application overlay permission, extra exported activity, permission, package query, browser chooser, or accessibility action capability.
3. Five visible seconds start only after `WindowManager.addView` succeeds and `entryGate.overlayShown` accepts the current ticket. Animation is presentation, never the gate clock. Repeated events/status renders cannot restart the deadline.
4. One gate per verified Instagram foreground session; completion or skip must not re-gate on inbox/content events in the same session. Foreign boundaries reset the session. Missing/wrong roots fail open, not a reason to invent a report or re-arm the gate.
5. Guard every new or existing delayed callback with the session generation and physical-overlay instance identity. Cancel queued work and reject callbacks that still run after cancellation.
6. Ignore ordinary Doom-owned overlay accessibility events. Only the existing positive MainActivity window-state/class match qualifies for direct-return report preservation. Stronger foreign/safety cleanup wins.
7. Keep physical detachment as the postcondition: removing a reference, catching `removeViewImmediate` failure, or a timer expiring is not removal. Keep 50 ms retry/20-attempt disable fail-safe, retaining attached-view/manager references until physically detached. Teardown must not release queued external actions.
8. Only existing `SanitizedStructuralReport` values may be copied, through Observation's one clipboard sink. Bounds remain 128 nodes, depth 8, 64 unique tokens and 8,192 ASCII characters/UTF-8 bytes. No new collector inputs or format changes. No files, logs, network, screenshots, raw content, node/window IDs, raw trees, or implicit export in the production diagnostic. Existing allowed static resource tokens inside the bounded report are not a license to expose node IDs or raw `viewIdResourceName` values elsewhere.
9. No INTERNET permission. Handing a constant URI to the Instagram package is not permission for Doom to make a network request. Instagram may independently access its network.
10. This diagnostic can pause DM entry. `SKIP TO MESSAGES` is an attempted package route, not guaranteed inbox support or successful delivery. Capture failure stays `REPORT UNAVAILABLE`, never inferred success from package detection, a classifier guess, clipboard state, CI, or signing.
11. Do not fix the reported phone capture failure by loosening root checks, retaining foreign samples, enabling extra capabilities, reading content, or delaying safety removal. This feature exposes that failure; its underlying device cause remains unproven.

### Evidence from this HEAD

- `MainActivity.kt:43-44,184-207`: Ink `#171B25`, Paper `#F3E7CF`, Jade `#73B39C`, panel `#252B37`, monospace framed controls, and a sine-expanded Manhattan-distance pixel diamond. Demo reduced motion is local Compose state plus a system animator-scale check; it is not an existing shared persisted preference.
- `EntryGateOverlayView.kt:18-62`: service-owned `LinearLayout`, white background, raw-pixel padding, static surface text, five-second label, `DISMISS FOR MESSAGES` and Leave buttons. It neither observes live report changes nor has a clipboard action.
- `DoomAccessibilityService.kt:203-359`: installs the actual accessibility overlay; starts the gate after addView; uses watchdog/completion/removal retry; only executes HOME after confirmed detachment. Current UI callbacks/completion request unscoped actions; the pure gate rejects stale tickets, but that alone does not prove a stale service closure cannot remove a later overlay. Close this boundary when adding navigation, rather than assuming it is already covered.
- `OverlayRemovalPolicy.kt`: current ordering is COMPLETE < PRESERVE_REPORT < BYPASS < HOME < RESET_OUTSIDE. It is a pure action/retry policy, not proof of real Android attachment. Current interruption uses BYPASS even though pending HOME ranks higher; add an explicit safety veto rather than carry that ambiguity into a new external action.
- `Observation.kt:29-32,56-88`: report/classifier access, explicit reveal, reviewed-copy guard, one sensitive-flagged clipboard sink, main-thread state, and state reset on every record. Compose state does not automatically re-render Android Views.
- `app/build.gradle.kts:3-16`: versionCode comes from `GITHUB_RUN_NUMBER` with local fallback 1; versionName is `0.1-wave0` plus the CI suffix. Namespace is `com.chardy.doom`; applicationId is `com.chardyb.doom`. `buildFeatures` currently enables Compose, not explicitly BuildConfig. Add `buildConfig = true` for generated BuildConfig rather than assuming AGP's default.
- `scripts/test-entry-gate-host.py`: explicit pure Kotlin source/test/class lists; newly added pure files/tests must be registered. It invokes cached Kotlin compiler/JUnit via Java, not Gradle, but reads cached libraries outside the repo and creates a temporary directory. Do not run it in this tightly scoped planning assignment.
- Android tests currently construct overlay content/call callbacks, not a bound Instagram service journey. Demo screenshots and separate fixture APKs do not prove live Doom-on-Instagram behavior.
- `android.yml`, `ci-device.sh`, `evidence-manifest.py`, and `validate-internal-signing.py` require the exact four existing demo PNGs and exact evidence files. Adding overlay PNGs to that signed evidence directory would break the trusted contract. Preserve it and use a separate supplementary diagnostic artifact path.
- `README.md`, `strings.xml`, and `docs/WIL-149-VALIDATION.md` contain truthful-for-v28 but now-to-be-obsolete claims that Doom never launches Instagram and offers exactly two actions. Update them with the new explicit exception and clipboard disclosure, not broad claims of control.

## 2. Architecture choice and system/file map

### Why not ComposeView in AccessibilityService?

A ComponentActivity already provides lifecycle, saved-state and composition ownership; a plain AccessibilityService/window root does not supply those owners in this repository. Adding ComposeView here would require deliberately supplying and destroying ViewTree lifecycle/saved-state owners, choosing composition disposal on a delayed physical detach, and coordinating those owners with interrupt/unbind/reconnect and failed window removal. The existing Compose dependencies alone do not make this safe. No service-side owner infrastructure or tests exist at this HEAD. A new LifecycleService/dependency or fake resumed owner would enlarge the safety surface just to paint a small diamond.

Choose the existing View path: semantic Android TextViews/Buttons in a scroll-capable framed hierarchy, plus a custom `PixelBreathingView` that only draws. Share colors/geometry/progress math, not timers, ownership, consent, or routing, with Compose. This avoids composition/disposal authority competing with physical window authority. It is not a blanket claim that Compose overlays are impossible; reevaluate only with independent lifecycle evidence and a separate scope decision. Do not prototype ComposeView in this bounded feature.

### Exact file map

| File | Responsibility/change |
| --- | --- |
| `app/src/main/java/com/chardy/doom/OverlayRemovalPolicy.kt` | Add NAVIGATE_MESSAGES priority, explicit safety veto and one-shot release semantics; keep bounded retries. |
| `app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt` (new) | Pure per-overlay epoch/ticket guard; distinguish visible UI/timer callbacks from closing/removal callbacks. No Android side effects. |
| `app/src/main/java/com/chardy/doom/InstagramInboxLauncher.kt` (new) | Constant package-targeted Intent factory and synchronous best-effort Android launch adapter. |
| `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt` | Wire scoped callbacks, cancel/race policy, post-detach route, presentation updates and disposal. Retain collector and window authority. |
| `app/src/main/java/com/chardy/doom/InstagramEntryGate.kt` | Keep timing/session semantics; use existing `bypass(ticket)` after detach. Do not turn classifier into a DM bypass rule. Remove/rename old dismiss alias only if all callers/tests are migrated in the same slice. |
| `app/src/main/java/com/chardy/doom/Observation.kt` | Compact status snapshot and explicit current-report overlay copy entry point; preserve reviewed-copy API and one clipboard sink. |
| `app/src/main/java/com/chardy/doom/EntryGateOverlayModel.kt` (new) | Report-free presentation types and pure timing/presentation mapping; enums/booleans only for diagnostics. |
| `app/src/main/java/com/chardy/doom/BreathingVisuals.kt` (new) | Pure ARGB tokens, pixel cell geometry and bounded phase functions shared by View and Compose. |
| `app/src/main/java/com/chardy/doom/PixelBreathingView.kt` (new) | Canvas renderer; no timer, no report, no service, no node access. |
| `app/src/main/java/com/chardy/doom/EntryGateOverlayView.kt` | Semantic styled layout, status, copy disclosure, bottom primary Skip/secondary Leave, render/dispose functions. |
| `app/src/main/java/com/chardy/doom/MainActivity.kt` | BuildConfig footer, shared pixel renderer primitives, honest disclosures. Demo stays simulated. |
| `app/build.gradle.kts` | Explicitly enable BuildConfig only; no version literal/dependency change. |
| `app/src/main/res/values/strings.xml` | Overlay/accessibility copy and update service disclosure. |
| `app/src/test/java/com/chardy/doom/OverlayRemovalPolicyTest.kt` | Race ordering, safety veto, at-most-once release, exhaustion. |
| `app/src/test/java/com/chardy/doom/OverlayCallbackGuardTest.kt` (new) | Stale instance/generation, closing and disposal behavior. |
| `app/src/test/java/com/chardy/doom/InstagramEntryGateTest.kt` | Bypass/no-regate and original visible-time regressions. |
| `app/src/test/java/com/chardy/doom/EntryGateOverlayModelTest.kt` (new) | Countdown boundaries/status enum mapping. |
| `app/src/test/java/com/chardy/doom/BreathingVisualsTest.kt` (new) | Geometry, static motion, bounds, contrast calculations. |
| `app/src/androidTest/java/com/chardy/doom/InstagramInboxLauncherTest.kt` (new) | Inspect real Intent; fake launch function succeeds/throws without opening Instagram. |
| `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt` (new) | Service wiring/order with controlled platform boundary, stale callbacks and denied routes. |
| `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt` (new) | Real factory rendering/click/accessibility/layout checks in a Doom-owned test host. |
| `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` | Current report copy, revocation/sentinel cases, unchanged reviewed-copy behavior and disclosure assertions. |
| `app/src/androidTest/java/com/chardy/doom/DoomUiTest.kt` | Exact BuildConfig footer checks; keep four demo captures untouched in purpose/names. |
| `scripts/test-entry-gate-host.py`, `scripts/test-structural-lifecycle.py` | Register pure tests; replace obsolete exact-string guards with precise new boundaries, not weaker broad exceptions. |
| `scripts/ci-device.sh` | CI-only supplementary overlay evidence collection, separate from signer input; no production capture. |
| `scripts/overlay-evidence-manifest.py` (new), `scripts/test-overlay-evidence.py` (new) | Strict supplementary screenshot list/provenance/hash checks outside canonical evidence. |
| `README.md`, `docs/WIL-149-VALIDATION.md` | Behavior/disclosure changes and actual RED/GREEN/CI/phone evidence ledger. |

Read-only unless a separately reviewed blocker demands scope expansion: `SanitizedStructuralReport.kt`, classifier, production manifest/accessibility XML, fixture implementations, signing workflow/scripts, `.github/workflows/android.yml`, canonical `scripts/evidence-manifest.py`, build version policy. Keep changes narrowly within the map; no repository-wide cleanup.

## 3. Exact cross-task interface contracts

These names/contracts are the handoff boundary. Change them only with a documented worker/reviewer agreement and update this plan's consumers together.

### A. Scoped overlay callbacks

Create pure types in `OverlayCallbackGuard.kt`:

```kotlin
internal data class OverlayCallbackToken(val ticket: GateTicket, val epoch: Long)
internal class OverlayCallbackGuard {
    fun open(ticket: GateTicket): OverlayCallbackToken
    fun acceptsVisible(token: OverlayCallbackToken): Boolean
    fun acceptsRemoval(token: OverlayCallbackToken): Boolean
    fun beginClosing(token: OverlayCallbackToken): Boolean
    fun detached(token: OverlayCallbackToken)
    fun invalidateVisible()
}
```

Contract: each `open` uses a new monotonically increasing instance epoch; only the current token is accepted. `beginClosing` is idempotent for the current instance, disables visible/report-copy/animation/completion callbacks immediately, but keeps removal retry valid. `detached` invalidates both; an older token cannot close, dispose, mutate or launch for a new instance. `invalidateVisible` immediately cancels interaction on safety shutdown without discarding the attached view or its removal token. Service keeps its gate ticket separate: the guard does not call `entryGate.cancel/leave/complete` early. Main-thread serialization is required.

Service captures the token in UI listeners, countdown/watchdog/completion and removal-retry closures. At the handler boundary, compare both guard token and the active service ticket/gate generation where relevant. No callback should look up a newer `ticket` and thereby acquire its authority. Guard disposal must happen even after addView failure/overlayShown rejection; no new session while a previous root remains physically attached or closing. Foreign events during retry request stronger cleanup, not `open` on a second root.

### B. Post-removal policy

Extend enum with `NAVIGATE_MESSAGES`. New normal ordering, low to high:

`COMPLETE < NAVIGATE_MESSAGES < PRESERVE_REPORT < BYPASS < HOME < RESET_OUTSIDE`.

All original relative priorities stay intact. Positive return to Doom cancels a pending messages launch and preserves the report; navigating back to Instagram after the user explicitly returned to Doom would be wrong. HOME still beats PRESERVE_REPORT as in v28. BYPASS means remove/clear without launching and must beat messages navigation. COMPLETE never routes.

Retain `request(action)`, `failedAttempt()`, `confirmedDetached()` and add:

```kotlin
fun requestSafetyCleanup(action: OverlayRemovalAction)
```

Accept only BYPASS or RESET_OUTSIDE for this method (require/assert invalid callers). It latches an external-action veto for the entire removal episode. Any pending/future HOME or NAVIGATE_MESSAGES is discarded; pending RESET_OUTSIDE stays strongest; otherwise safety cleanup is BYPASS, including over PRESERVE_REPORT/COMPLETE. This is distinct from a normal BYPASS request and prevents the current HOME-priority ambiguity on interruption/revocation. `DISABLE_SERVICE` also latches the veto. Reset attempts/pending/veto only on confirmed physical detach, never on a failed attempt. A later confirmed detach after exhaustion cannot release the discarded external action.

`confirmedDetached()` returns at most one action. It is called by the service only for the captured current root after verifying `!isAttachedToWindow`, not just `overlay == null` for an old navigation request. A no-overlay lifecycle cleanup may still run without a token, but cannot originate NAVIGATE_MESSAGES or HOME. Keep an installed-root token through removal even if observation consent is revoked.

### C. Instagram inbox route

File `InstagramInboxLauncher.kt`:

```kotlin
internal enum class InboxLaunchResult { ATTEMPTED, UNAVAILABLE }
internal fun interface InstagramInboxLauncher {
    fun launch(): InboxLaunchResult
}
internal object InstagramInboxIntentFactory {
    fun create(): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://www.instagram.com/direct/inbox/")
    ).setPackage("com.instagram.android")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

Android adapter `AndroidInstagramInboxLauncher` accepts a `(Intent) -> Unit` launch function, supplied as `{ startActivity(it) }` by the service. Catch ActivityNotFoundException and SecurityException, plus other RuntimeException from the synchronous launch boundary, returning UNAVAILABLE without exception details/logging. Normal return means ATTEMPTED, not OPENED, SUCCESS or inbox verified. One call, one freshly created Intent, no extras, no chooser, no categories added by Doom, no CLEAR_TASK/CLEAR_TOP, no URI/user inputs, no resolveActivity prerequisite that would require package-visibility queries. Direct package-targeted start plus exception handling avoids mistaking filtered package queries for reliable launch support.

No implicit retry, browser fallback, alternative custom scheme, package-removing retry, generic launcher intent, node action or coordinate gesture. Android/Instagram may reject or ignore the URL, route to another Instagram surface, or return normally without displaying an inbox; that remains a phone-test observation. If unavailable, do nothing after removal and leave the current screen alone. Never reinstall a gate, send HOME, open Doom, show a replacement window or wait for a foreground event to claim success. The URI launch is user-initiated only, not from timer/capture/classifier events.

### D. Compact Observation and copy boundary

In `EntryGateOverlayModel.kt`:

```kotlin
internal enum class OverlayReportStatus { CAPTURED, UNAVAILABLE }
internal enum class OverlayCopyResult { COPIED, UNAVAILABLE }
internal data class OverlayDiagnosticStatus(
    val surface: EntryGateSurface,
    val reportStatus: OverlayReportStatus,
    val canCopyCurrentReport: Boolean
)
internal data class EntryGateOverlayModel(
    val remainingSeconds: Int,
    val progress: Float,
    val reduceMotion: Boolean,
    val diagnostic: OverlayDiagnosticStatus
)
```

Observation adds `overlayDiagnosticStatus(): OverlayDiagnosticStatus` and `copyCurrentReportFromOverlay(context: Context): OverlayCopyResult`. Eligibility is consent && connected && report != null, evaluated again synchronously inside Observation on click. The snapshot never contains report text, resource tokens, node metadata, exception strings, timestamps or package-derived free text. CAPTURED means an existing non-null bounded report only, including a truncated one; it does not mean classifier accuracy or complete collection. UNKNOWN with CAPTURED is valid. Null/denied/disconnected gives UNKNOWN + UNAVAILABLE + false.

Keep `copyReport(context)` requiring `canCopy`/explicit reveal for the main app. Do not silently call `revealReport()` from the overlay, mark `revealed=true`, rename its action to reviewed, or expose full report text. Add a separate explicit current-copy entry point authorized by the new disclosure and user tap. Both paths delegate to one private clipboard writer in Observation using the existing ClipData label and sensitive-preview flag. The overlay entry point resolves the current report on click, not a report reference cached at render time; same main-thread call validates and writes, with no coroutine/delay between consent check and copy. A replacement report requires a new tap; a cleared report cannot be copied by a stale rendered control.

COPIED only after `setPrimaryClip` returns normally; absent manager/RuntimeException gives UNAVAILABLE and must reset stale copy-success state. Reuse existing Observation reset rules; preserve the main app's reveal-before-copy invariant. No new persisted copy/reveal field and no second report store. Do not automatically read back the clipboard in production; instrumentation can use a synthetic sentinel. System clipboard ownership/pasteability after the API returns is not guaranteed.

Overlay copy result feedback is user-triggered, not diagnostic status: `Copied to system clipboard.` or `Copy unavailable.` Never call failed copy a captured report failure. Persistent compact diagnostic text remains exactly `INSTAGRAM DETECTED`, the classifier enum, and `REPORT CAPTURED`/`REPORT UNAVAILABLE`.

### E. Presentation and lifecycle

`EntryGateOverlayViewFactory.create(context, onSkipToMessages, onLeaveInstagram, onCopyCurrentReport)` returns `EntryGateOverlayUi` with `root: View`, `countdown: TextView`, `skipToMessages: Button`, `leaveInstagram: Button`, `copyCurrentReport: Button`, `render(model)`, `showCopyResult(result)` and `dispose()`. No Context retention outside the root lifetime. A new UI starts at five seconds with UNKNOWN/UNAVAILABLE until the service renders Observation's actual snapshot; factory defaults cannot fabricate CAPTURED.

`render` updates only changed text/visibility/phase. Copy control is GONE when unavailable, not an enabled placeholder. One helper in the service builds the model from `entryGate.remainingMs`, the token, motion setting and `Observation.overlayDiagnosticStatus()`. Invoke on install, after actual record/null/clear paths while still attached, and on existing watchdog ticks; read state without invoking the collector again. Recheck before copy. Self-overlay events remain ignored, so 50 ms View renders do not recursively collect a report. Hide/disable all controls immediately when closing; dispose stops animation/update work at removal request and again idempotently on detach/teardown. Retain only references necessary to retry physical removal.

`PixelBreathingView.render(progress: Float, reduceMotion: Boolean)` only draws deterministic shared cells. It has no ValueAnimator/Handler of its own; use the existing watchdog's elapsed-time phase. This limits new scheduling races. `BreathingVisuals` uses clamped progress 0..1 and the current Compose geometry: radius `2 + (sin(progress * PI) * 2).toInt()`, cells whose Manhattan distance is <= radius + 1; Paper center/Jade other cells on Ink. Preserve the existing visual spacing when moving geometry to the shared helper, and prevent negative cell sizes at tiny view dimensions. Static mode uses the fixed middle phase, not a very short animation. No flashing, bounce, shimmer or haptics.

Reduced motion: overlay reads `ValueAnimator.areAnimatorsEnabled()` (minSdk 26) on creation and existing render ticks; disabled or read failure selects still rendering without changing the five-second deadline. Existing in-app `Still image` control can remain demo-local, but label its scope honestly. Do not add a third persisted setting or pretend that local Compose state already controls the service. The overlay always respects the system setting; any future shared user preference needs separate persistence/disclosure approval.

## 4. Lifecycle/action decision table

All entries concern events delivered before the external launch call. An event not delivered to Doom before a synchronous startActivity cannot retroactively cancel it; record this timing limitation on phone tests.

| Event/race | Required action before detach | After confirmed detach |
| --- | --- | --- |
| Skip during current visible gate | Validate token/generation/consents, enqueue NAVIGATE_MESSAGES, close controls/timers. | Consume action once, recheck eligibility, bypass current gate, clear report/copy state as old explicit dismissal did, publish BYPASSED, then attempt package route. Keep session ticket so subsequent Instagram events cannot re-gate. |
| Skip then COMPLETE / COMPLETE pending then valid Skip | NAVIGATE_MESSAGES beats pending COMPLETE if the same root still exists and valid click is accepted before closing. Once closing starts, reject subsequent clicks. | At most the already accepted action; never route on a click after completion already detached. |
| Repeated Skip, old root click, queued old completion | Reject on closing/token mismatch. | No second launch; no mutation/removal of newer overlay. |
| NAVIGATE_MESSAGES vs normal BYPASS | BYPASS wins. | Remove/clear, no route. |
| NAVIGATE_MESSAGES vs HOME | HOME wins if independently authorized while still valid (policy test both orders); frozen UI normally prevents a second tap. | Only HOME, never HOME plus route. |
| NAVIGATE_MESSAGES vs positive MainActivity return | PRESERVE_REPORT wins; mark verified return; invalidate visible callbacks. | Reset session/ticket; preserve latest report; do not pull user back into Instagram. |
| PRESERVE_REPORT vs existing HOME/BYPASS | Keep v28 relative priorities for normal explicit actions. | HOME or BYPASS cleanup, no preservation. |
| Any pending action vs foreign event | RESET_OUTSIDE wins; do not read foreign tree. | Clear report/session/return marker; no HOME/route. |
| Wrong/null active root, collector/runtime failure | Safety BYPASS plus irreversible external-action veto; clear invalid report immediately and invalidate visible callbacks. | Clear/cancel, no route/Home. Retain bypassed-session behavior rather than re-arm on the next same-session sample. |
| Either consent revoked, stop, interrupt, disconnect/unbind/destroy, service disable | Immediately invalidate visible callbacks and request safety cleanup BEFORE any synchronous removal can drain actions; clear report/connection as appropriate. | No external action, even if consent is re-enabled before a retry. Do not turn revocation into a deferred inbox request. |
| Retry failure / exhausted attempts | Stop animation and input, retain current root/manager/token, retry 50 ms up to 20; exhaustion vetoes external actions then calls disableSelf. | No queued route or Home released by late teardown. Confirmed detach can finish cleanup only. |
| Failed addView or overlayShown rejection | No visible clock or externally actionable click authority; safety cleanup. | No navigation and no fake successful gate. |
| Same-package updates after successful/failed route attempt | BYPASSED session persists. | No new overlay until a verified outside boundary and later new Instagram session. |

Before executing NAVIGATE_MESSAGES, preserve the captured token locally through detach confirmation, verify it was the current installed instance, consents/connection remain valid, and no return/foreign/safety veto was delivered. Perform a final package-only active-root check if needed to establish current Instagram foreground: transient root.packageName only, always recycle, no content/classification reads, null/wrong package means no launch. This check is not a new report collection. Root query uncertainty must suppress the route, not weaken safety. No async availability probe between detach and launch. A direct MainActivity return during retries must preserve the report unless later stronger cleanup occurs.

## 5. Accessible visual specification

- Full opaque Ink/navy surface, Paper body text, Jade accents, panel border and monospaced headings matching Doom. No white platform-default button panels; explicitly style normal/pressed/focused/disabled colors with ColorStateList/background shapes. Use Paper text on Ink and Ink on Jade for the primary CTA. Avoid Rust for small status text until measured contrast passes.
- Header `INSTAGRAM DETECTED`; compact classifier enum and report status. Center `Take a breath.` and pixel diamond, `Breathe naturally. No need to hold.`, then a clear integer `5s remaining` down to completion. No report text field, selectable report area or hidden full-report accessibility description.
- Bottom action group: full-width primary `SKIP TO MESSAGES`, secondary `LEAVE INSTAGRAM`; optional `COPY CURRENT REPORT` in the diagnostic area above actions. Primary remains reachable without waiting. All targets >=48dp in both dimensions, target primary min height 52dp; dp spacing, sp text, multi-line button labels when necessary. Do not use the current raw-pixel padding.
- A weighted ScrollView body plus wrap-content bottom action group and safe system-bar/cutout insets is the default. On very small/landscape/high-font windows, allow the whole layout to scroll rather than clip actions or shrink text below readable size; cap/reduce decorative pixel area first. Verify target bounds are visible/reachable, not just measured offscreen. Do not change window flags to solve layout.
- Use native button/text semantics, meaningful reading order (heading, purpose/countdown, status/copy disclosure, actions), visible focus and keyboard/switch traversal. Mark decorative pixel Canvas inaccessible; its nearby breathing instruction is the accessible equivalent. No full report in contentDescription. At most one explicit appearance announcement if tests show it necessary; do not announce every animation frame/second.
- Countdown is ordinary readable text with accessibility live region NONE, not a progress alert; only change it at integer boundaries. On focus, a user can read current remaining time. Do not manually send announcement/content-changed events at 50 ms. Copy result can be one polite user-initiated status update, never a full clipboard payload announcement.
- Measure WCAG contrast in tests: ordinary text >=4.5:1; large text and non-text focus/control boundaries >=3:1. Use an actual calculation in test code, not visual assertion alone. Include disabled-state legibility and system dark/light setting independence.
- Check font scale 1.0, 1.3 and 2.0; portrait and landscape, minimum supported practical 320dp width, display scaling, long build footer, cutout/system navigation inset, TalkBack and switch/keyboard focus. Screenshot pixel/contrast evidence is supplementary to target and clipping assertions.
- Overlay disclosure: `Diagnostic pause may interrupt DM entry. Skip tries to open Instagram messages after removing this pause; it may not work.` Copy disclosure visible before action: `Copy sends the current sanitized report to the system clipboard without showing it here. Copies leave Doom and cannot be recalled by clearing Doom.` Do not auto-extend the countdown for reading/copy; record whether the five-second interface is usable in phone accessibility testing.
- Main footer: `Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})`, rendered as text within the shared scrollable screen footer for all demo/home states. Display exact values without truncation or a hard-coded v29. Next CI is expected to be code 29, but intervening runs may change that; installed BuildConfig and APK metadata are authoritative.

## 6. Numbered implementation steps — vertical RED/GREEN slices

Each numbered slice contains small actions, intended as short focused edits/checks. Repeat one test -> expected RED -> minimum implementation -> GREEN before adding the next behavior; do not write all tests first and implement in bulk. For multi-case tables, add and execute one case at a time. Existing passing baseline tests are regression evidence, not new RED evidence. A missing symbol is an initial interface signal only: introduce the smallest non-working compile stub if necessary, then obtain a behavioral assertion failure before filling the implementation. Never fabricate RED output.

Host test commands below are for the subsequently authorized implementation worker only. Android tests must be run by GitHub CI, including tests-first RED commits. If that is unavailable, stop the affected slice at its test-first handoff; do not write Android implementation while claiming unobserved RED. Controller may use authorized intermediate draft-PR commits to run test-first checks; no credentials are delegated to the worker/reviewer. A deliberately failing RED candidate is never eligible for signing.

### Step 1 — Establish baseline and test ledger

Files: read the map; later append evidence to `docs/WIL-149-VALIDATION.md`.

1. Verify the prescribed base and clean/scope-allowed tree; stop on mismatch. Read current policy/service/tests before editing.
2. Run the authorized host checks in section 7; record exact output and environmental blockers. Do not copy historical counts.
3. Record test/RED SHA, expected failure, GREEN SHA/run, and scope of proof for each following slice in the validation ledger. No app behavior change in this step.

Commit boundary (future only): baseline notes can join the first relevant test commit; no empty commit required.

### Step 2 — Prove messages action ordering

Files: `OverlayRemovalPolicy.kt`, `OverlayRemovalPolicyTest.kt`, `scripts/test-structural-lifecycle.py`.

1. Add `messagesWaitForDetachAndBeatCompletion`: request NAVIGATE_MESSAGES then COMPLETE, make a failed attempt, assert no action released until confirmedDetached; result NAVIGATE_MESSAGES, next confirmation null.
2. Run `python3 -B scripts/test-entry-gate-host.py`; expected RED is missing behavior (then assertion RED once interface exists).
3. Add only the enum/rank behavior and rerun GREEN.
4. Next test both orders of NAV vs PRESERVE_REPORT, BYPASS, HOME, RESET_OUTSIDE. Expected results exactly match section 4. Implement only the missing ranking.
5. Update source guards that hard-code old ordinal numbers, retaining the old relative invariants. Rerun host gate/lifecycle suites.

Commit boundary: `feat: arbitrate messages requests after overlay detach` (tests plus policy only).

### Step 3 — Make safety cancel pending external actions

Files: same policy/test/guard files; later service wiring in Step 6.

1. Add `safetyBypassCancelsPendingHomeAndMessages` using requestSafetyCleanup; repeat with a later normal HOME/NAV request. Expected RED: old priority releases HOME or route.
2. Implement the per-removal safety veto; no new gate state. Confirm BYPASS cleanup, RESET_OUTSIDE dominance and preserve cancellation.
3. Add `exhaustionCannotReleaseExternalActionOnLateDetach`; expect RED with existing max-attempt behavior, then latch veto on exhaustion.
4. Add fresh-removal-episode reset test so veto does not permanently break future independently verified sessions. Run all pure tests and source guards.

Commit boundary: `fix: veto external actions on overlay safety cleanup`.

### Step 4 — Scope callbacks to one physical overlay

Files: new `OverlayCallbackGuard.kt`, new `OverlayCallbackGuardTest.kt`, `scripts/test-entry-gate-host.py`.

1. Register the pure source/test/class in all three runner lists. Add one failing test that old token A cannot pass after token B opens even when ticket values repeat.
2. Run host RED; implement current-token/epoch comparison, rerun GREEN.
3. Add closing behavior test: acceptsVisible false, acceptsRemoval true; detached makes both false. Implement and rerun.
4. Add idempotent invalidate/detach and stale-detach-does-not-invalidate-new-root cases. Keep guard side-effect-free.

Commit boundary: `fix: bind overlay callbacks to ticket and instance`.

### Step 5 — Prove the constant package inbox adapter

Files: new `InstagramInboxLauncherTest.kt`, new `InstagramInboxLauncher.kt`.

1. Tests inspect ACTION_VIEW, exact URI including trailing slash, package `com.instagram.android`, flags equal NEW_TASK only, no extras/selector/chooser; fake launch captures the Intent and never calls the OS.
2. Controller runs tests-first GitHub Android instrumentation; expected RED is absent/incorrect package-targeted contract, not an emulator provisioning failure. Pure JVM Android stubs are not evidence of Intent behavior.
3. Implement the factory and function-injected adapter from section 3C.
4. Add one failure test at a time for ActivityNotFoundException, SecurityException, other RuntimeException, then normal return ATTEMPTED. Assert one attempt/no fallback/no thrown exception/no success-named result. Run CI GREEN.

Commit boundary: tests-first RED commit followed by `feat: add best-effort package-targeted inbox launcher` GREEN commit. No real Instagram launch in CI.

### Step 6 — Wire detachment before navigation and race guards

Files: `DoomAccessibilityService.kt`, new `EntryGateServiceActionTest.kt`, `InstagramEntryGateTest.kt`, `scripts/test-structural-lifecycle.py`, interim callback rename in `EntryGateOverlayView.kt` and `StructuralDiagnosticUiTest.kt`.

1. Add a service integration test with controlled removal failure: Skip must make zero launcher calls while attached; successful physical detach allows one call after gate becomes BYPASSED. Expected RED: old callback only bypasses/no route. Use current repository test-only reflection for private service setup rather than exporting debug controls.
2. To avoid asserting a fake View's state equals OS detachment, keep two test tiers: a controlled service platform seam verifies call order, and phone/bound-window tests verify real physical state. For service instrumentation, add only an internal `OverlayPlatform` seam in `DoomAccessibilityService.kt` with `isAttached(view): Boolean`, `removeImmediate(manager, view)`, `launchInbox(): InboxLaunchResult`, `currentForegroundPackage(): String?`, and `performHome(): Boolean`; default delegates directly to Android and the adapter. Zero-argument service construction stays intact; tests replace its private field via reflection. No new manifest component, Context-wide abstraction or injectable report collector. Production confirmation must read the default real `View.isAttachedToWindow`.
3. Implement scoped callbacks/closing, normal action selection, final current-foreground check, bypass/clear/publish then launch. Rename the old dismiss callback/control to Skip throughout this slice so CI can compile before visual work. Update the service's obsolete `Never navigates` class comment to the precise user-initiated exception.
4. Add and observe RED for each service race in section 4, especially completion A after new overlay B, retry A after B, interruption/revocation while navigation is pending, consent reacceptance, positive Doom return, foreign event, missing root, exhaustion then teardown. Wire guard checks and safetyCleanup at callback entry before synchronous removal/drain. Never weaken a failing safety assertion for UI convenience.
5. Add gate regression proving route failure still leaves BYPASSED and repeated Instagram events cannot re-gate; COMPLETE still needs the original five visible seconds.
6. Run host regression and CI integration GREEN. Source guards must assert launcher use is only in confirmed-detach execution and no node/gesture/browser fallback exists. Runtime order tests, not source string order alone, are required.

Commit boundary: `feat: skip to messages only after confirmed overlay removal`, with dedicated repair commits for each independently found race.

### Step 7 — Add compact current status through Observation

Files: new `EntryGateOverlayModel.kt`, new `EntryGateOverlayModelTest.kt`, `Observation.kt`, `StructuralDiagnosticUiTest.kt`, runner registration.

1. Add failing model/status tests: null sample -> UNKNOWN/UNAVAILABLE/no copy; real bounded synthetic sample -> CAPTURED; unknown classifier with real sample is still CAPTURED. Add Observation instrumented consent/disconnect cases.
2. Execute host and CI RED as appropriate. Implement only report-free types and Observation snapshot mapping. Do not change builder/classifier behavior to produce a desired status.
3. Add replacement/null/clear/revoke tests to eliminate stale CAPTURED. Run GREEN and unchanged sanitizer/classifier suites.

Commit boundary: `feat: expose bounded overlay diagnostic status`.

### Step 8 — Add explicit current-report clipboard boundary

Files: `Observation.kt`, `StructuralDiagnosticUiTest.kt`, `scripts/test-structural-lifecycle.py`.

1. Seed a synthetic bounded report, leave revealed=false, invoke copyCurrentReportFromOverlay explicitly. Expected RED: no such authorized copy path. Assert exact report bytes only, sensitive flag, COPIED only after writer success, and revealed remains false.
2. Observe CI RED; factor the existing writer inside Observation, preserving the old main-app reviewed-copy guard. Implement new explicit-copy entry point with immediate consent/current-state recheck.
3. One test at a time: no consent, disconnected, null report, revocation between render/click, replacement report, unavailable clipboard manager, throwing clipboard manager, prior success followed by failure. Sentinel remains unchanged on denial; no stale success label. Use a controlled ContextWrapper boundary only where real clipboard failure cannot be forced.
4. Keep old `reportIsHiddenUntilExplicitRevealAndOnlyExplicitCopyChangesClipboard` for main-app behavior. Update text/source guards intentionally: one setPrimaryClip sink, exactly the two explicit entry paths, no automated callers, no report text in overlay model. Run CI and host GREEN.

Commit boundary: `feat: copy current sanitized report from explicit overlay action`.

### Step 9 — Extract and test Doom pixel presentation

Files: new `BreathingVisuals.kt`, new `BreathingVisualsTest.kt`, new `EntryGateOverlayModelTest.kt`, `MainActivity.kt`, runner registration.

1. Add deterministic phase/cell/countdown boundary tests; run host RED. Include start=5, just-before-deadline=1, deadline=0, no premature grant; geometry clamping at tiny sizes and out-of-range progress; fixed cells in reduced-motion mode across time.
2. Implement pure tokens/geometry/progress using section 3E, not a new oscillator. Add calculated contrast assertions for actual chosen opaque colors; implement accessible control colors as needed.
3. After host GREEN, add/observe a CI regression for the demo's shared pixel geometry/semantics, then make Compose PixelBloom consume primitives without changing its demo timing or behavior. Keep `DemoGate` authority untouched.

Commit boundary: `refactor: share Doom pixel breathing presentation`.

### Step 10 — Render the styled, accessible overlay

Files: `EntryGateOverlayView.kt`, new `PixelBreathingView.kt`, new `EntryGateOverlayUiTest.kt`, `strings.xml`, `StructuralDiagnosticUiTest.kt`.

1. Add real factory UI assertion for dark background, primary Skip/secondary Leave, current report unavailable and no full report text. Run CI RED against white debug layout.
2. Implement native layout/style/custom drawing using section 5 and callbacks only. No service/Observation/Intent access from the View.
3. Add semantic/48dp/font-scale/insets/landscape/no-clipping tests one at a time; observe RED then fix minimum layout. Check button hit bounds, readable wrapped text and scroll reachability. Use a test-only Doom MainActivity-hosted view via instrumentation, not a production preview screen or new exported test activity.
4. Add live-region NONE/decorative-canvas tests and assertion that identical integer countdown renders do not reset text or emit manual rapid announcements. Add reduced-motion deterministic drawing test. CI's disabled animations alone does not prove animated mode; render controlled progress frames in the synthetic host.
5. Add conditional Copy visibility/disclosure/feedback tests; callbacks cannot copy or navigate by themselves. GREEN includes existing callback dispatch tests updated for the new API.

Commit boundary: `feat: style accessible overlay with Doom breathing visuals`.

### Step 11 — Bind live status/phase and dispose presentation

Files: `DoomAccessibilityService.kt`, `EntryGateServiceActionTest.kt`, `EntryGateOverlayUiTest.kt`, `scripts/test-structural-lifecycle.py`.

1. Add service/UI test: a rendered CAPTURED state becomes UNAVAILABLE after current Observation invalidation; a stale enabled Copy click is denied. Expected RED: factory surface is currently captured once, no live binding.
2. Implement one render helper, initial snapshot after installation, refresh on state transitions and existing watchdog only; no extra collection/scheduler. Copy callback checks current visible token then calls Observation and renders sanitized result. A current sample can be copied without leaving Instagram while the gate remains attached.
3. Add closing/dispose/revocation tests: animations/controls stop at removal request, countdown uses elapsed gate time, status changes never restart five seconds, old UI callbacks cannot reach Observation/launcher, system animation disable changes rendering to still. Observe RED/GREEN for each.
4. Keep underlying missing-root safety behavior: UNAVAILABLE may be visible only briefly before removal. Do not keep a blocking overlay alive to improve report diagnosis. Phone evidence must say when failure cannot be inspected before fail-open removal.

Commit boundary: `feat: keep overlay diagnostics live without expanding observation`.

### Step 12 — Show exact build identity and correct disclosures

Files: `app/build.gradle.kts`, `MainActivity.kt`, `DoomUiTest.kt`, `StructuralDiagnosticUiTest.kt`, `strings.xml`, `README.md`, `docs/WIL-149-VALIDATION.md`, source guards.

1. Add Android test expecting exact `Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})`; source guard rejects hard-coded 29/version strings as the displayed source. Explicit BuildConfig generation may need a tests-first build configuration commit to compile the assertion; first behavioral RED must be a missing footer, not unresolved BuildConfig.
2. Enable `buildConfig = true` and render the footer in every Doom screen's readable scrollable footer; no versionCode/versionName policy change. Run CI GREEN and large-font/wrap test.
3. Update/test disclosure: DM entry can pause, explicit Skip only attempts the constant Instagram route after removal, no guaranteed support/browser fallback, optional current report copy without full reveal, clipboard leaves process and cannot be recalled. Remove contradictory `never launches Instagram`/`exactly two actions` claims while retaining never-acts-on-nodes and no-protection language.
4. Clearly distinguish overlay current-copy consent boundary from main-app reviewed-copy. No claim the report failure is repaired. Keep service settings disclosure within readable Android string encoding.

Commit boundary: `feat: show installed build identity and disclose diagnostic limits`.

### Step 13 — Capture supplementary evidence without breaking signing

Files: `EntryGateOverlayUiTest.kt`, `scripts/ci-device.sh`, new `scripts/overlay-evidence-manifest.py`, new `scripts/test-overlay-evidence.py`; read-only canonical manifest/signer/workflows.

1. Add host failing tests for a separate supplementary manifest: exact expected PNG names, reject missing/extra/empty/not-PNG screenshots, bind exact candidate SHA/run ID/run attempt and actual tested APK SHA-256/size. Observe RED, implement strict validation GREEN using only synthetic fixture files in host tests (not claimed visual evidence).
2. Add CI tests that produce genuine screenshots of the real overlay factory over a verified Doom-owned test host, never Instagram/report text. Required names: `01-overlay-unavailable.png`, `02-overlay-captured-status.png`, `03-overlay-reduced-motion.png`, `04-overlay-large-font.png`, `05-overlay-landscape.png`, `06-doom-build-footer.png`. Initial failure for absent evidence is expected; record it before adding test-only capture.
3. Capture under `/sdcard/Download/doom-overlay-ui-evidence/`; assert Doom's MainActivity is top-resumed and all data are synthetic. Preserve tests' cleanup/restoration of font/rotation/animation settings. Pull only in CI to `app/build/reports/androidTests/overlay-evidence/`; existing diagnostic reports artifact already includes this directory. Add manifest there. Do not copy supplementary files into `evidence/` or `/sdcard/Download/doom-ci-evidence/`.
4. Extend `ci-device.sh` cleanup/diagnostic trap so supplementary artifacts can survive a failure, but successful completion requires the exact supplementary manifest. Do not spoof GITHUB_ACTIONS on the host. Hash the same `app/build/outputs/apk/debug/app-debug.apk` used by instrumentation/canonical evidence. The supplementary manifest is not trusted signer input.
5. Run source/signing/fixture regressions and exact-head CI GREEN. Existing four canonical screenshots, canonical manifest schema and trusted signer remain byte-contract compatible; only their normal content hashes change with new builds.

Commit boundary: `test: bind synthetic overlay evidence without changing signing contract`.

### Step 14 — Independent review, CI candidate and prerelease gate

Files: no implementation changes unless reviewer finds a defect; update `docs/WIL-149-VALIDATION.md` for evidence, then freeze/review the resulting HEAD.

1. Worker runs all allowed host checks and scope/diff check; prepares a concise handoff of exact candidate SHA, tests/CI run IDs, artifacts and unresolved limits.
2. Independent Claude Code Opus reviewer reviews the exact full candidate diff plus unchanged authority paths, not just the last commit. Verify every section 4 race, copy recheck, stale callback guard, no permissions/content expansion, overlay semantics, BuildConfig and supplementary evidence separation. Review production default platform delegates, not merely injected tests. No credentials or signing secrets to reviewer.
3. Any fix gets a regression RED/GREEN, a new candidate SHA and a new independent exact-head review; rerun CI. No approval inheritance across HEAD changes. No new PR for the bounded feature.
4. Controller verifies GitHub job results and artifact identity at that exact candidate, then requests protected signing and publishes/records the internal prerelease only within separately granted authority. Details in section 8. Stop on missing evidence, source/signature mismatch, unexpected version, reviewer blocker or missing protected environment approval.

Commit boundary: final validation/evidence notes before the last review; no commits after the reviewed candidate without re-review/retest.

### Repair addendum — Luna worker pass

The candidate remained uncommitted at base `9bf29169271010d9b36c8d6e316c97f46ed49795`. This repair pass addressed Opus blockers B1–B5: API-35 Intent assertions, successful-path overlay artifact pull ordering, genuine six-state Doom-owned capture setup/restoration, fake-platform runtime action/race coverage, clipboard failure/replacement coverage, and wrap-content/inset/stateful overlay accessibility. The six supplementary names remain synthetic Doom UI evidence, not actual Instagram evidence. Android compilation, instrumentation execution, emulator/adb capture, exact-head CI, and phone acceptance remain controller-owned and unverified; exact host results are recorded in `docs/WIL-149-VALIDATION.md` and the repair result artifact.

### Repair addendum — Luna rereview repair 2

This uncommitted pass addresses Opus rereview blockers N1–N4: main-thread ActivityScenario construction and reflective test setup with deterministic fake-platform visibility, posted draw-listener removal, 2.0-font portrait/640×320dp landscape reachability with immediate coordinate-correct scrolling, and restoration of the canonical Doom screenshot journey with footer verification isolated in its own test. It also removes the duplicate Intent-flags assertion and the redundant `allowClosing` branch. Host checks are the only local verification; Android compilation, instrumentation, screenshots, exact-head CI and phone acceptance remain unverified.

### WIL-179 cooldown addendum — Luna implementation

The WIL-179 contract is implemented on the existing uncommitted WIL-149 candidate without changing consent, observation, routing, permissions, persistence, or the local Doom demo. `InstagramGateCooldown` is process-local and uses an injected monotonic elapsed-time function; production supplies `SystemClock.elapsedRealtime` through the service seam. A production Instagram session can allocate a ticket only when the cooldown is inactive. The service checks that condition before ticket allocation, active-root access, or sanitized report collection, so suppressed Instagram navigation, session resets, transient roots/windows, DM list/thread Back events, and returns to Instagram do not attach/restart presentation or mutate the report lifecycle. A successful cooldown admission is recorded only after `WindowManager.addView` succeeds and `overlayShown` accepts the current live ticket; failed, stale, and local-demo attempts do not arm it. The interval is suppressed for `< 60,000 ms` and permitted at `== 60,000 ms`. Explicit Skip/Leave, safety cleanup, direct return, consent changes, and stale callbacks retain their existing removal/token protections; cooldown state is not persisted and naturally resets with service/process recreation.

Focused JVM coverage exercises the 59,999/60,000 boundary, no cooldown before display admission, session reset and explicit Skip/cancel/direct-return-shaped invalidation, consent reacceptance-shaped reset, and stale/rejected admission. Structural guards verify the monotonic seam and ordering only; they do not claim Android compilation, overlay attachment, or device timing. Host verification remains the only execution evidence for this addendum.

## 7. Allowed host verification and RED provenance

This planning assignment runs read-only repository inspection and plan validation only. It does not run the following implementation commands. For a later authorized worker, use existing non-Android host paths:

```text
python3 -B scripts/test-entry-gate-host.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test_wil155_host.py
python3 -B scripts/test-internal-signing.py
python3 -B scripts/test-overlay-evidence.py
bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh
git diff --check
```

`test-overlay-evidence.py` exists only after Step 13. Expect new tests to fail for the specific absent behavior during RED; expect all registered cases to pass after GREEN. Record actual counts; do not predict a fixed total. Parse modified XML with a host XML parser; validate Python syntax without creating tracked bytecode. Cached Kotlin/JUnit runner failure due to missing libraries is an environment blocker, not feature RED. Do not install toolchains, run Gradle, inspect credentials or expand filesystem scope to work around it without controller authorization. The future runner's external cache reads/temp writes require its own authorization, not this planning turn's scope.

Source guards are useful negative assertions (no forbidden APIs, one clipboard sink, no new permission, correct generated version source); they cannot prove Android lifecycle, View measurement, Intent resolution or clipboard behavior. Update brittle guards only alongside stronger semantic assertions. Host evidence alone never satisfies Android acceptance.

## 8. GitHub CI, review, protected signing and phone acceptance

### Exact-head CI gate

Controller, not worker, owns authorized credentialed PR updates/CI dispatch. Keep existing least-privilege keyholder flow; no credentials are used in this planning role. All Android compilation/Gradle/lint/emulator/adb work stays on GitHub-hosted CI. Preserve the existing three jobs:

- Android baseline: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` plus signing validator tests.
- Diagnostic emulator evidence: `:app:connectedDebugAndroidTest` through guarded `scripts/ci-device.sh`; real nonzero test summary, canonical four demo PNGs and tested APK, plus supplementary overlay evidence under reports.
- Cross-app fixture evidence: existing original test APK journey; regressions fail this feature too, but fixture pass is not Instagram proof.

Before approval, read exact job/check results and downloaded manifests, not just green PR decoration: candidate SHA equals reviewed HEAD (not synthetic merge SHA); run/attempt identity matches; APK size/digest matches manifest; canonical screenshot names and genuine PNGs pass existing validator; supplementary manifest binds same APK and source. A canceled, timed-out, missing-artifact or emulator-provisioning-failed run is not evidence. Current CI disables animations, so deterministic phase tests plus real-phone normal-motion acceptance are required, not an animated-mode claim from still screenshots.

### Protected signing and internal prerelease

The existing `Sign internal APK` workflow is dispatch-only on trusted `main`, behind the `internal-signing` environment, and downloads `doom-device-evidence-<candidate_sha>` from an exact successful source run. It uses signer scripts from trusted main, not executable code from the PR. Preserve pinned Actions, no persisted checkout credentials, source repository/workflow/run/SHA/event/status checks, exact manifest/files, payload-equality check and pinned internal certificate.

Controller supplies the exact full lowercase candidate SHA and successful source_run_id only after independent review and CI verification. Human/protected-environment approval remains required. Do not sign locally, read/export secrets, rebuild under the signer, substitute a baseline APK for the exact device-tested APK, or weaken trusted validators to admit extra screenshot files. No merge is necessary or authorized for signing a candidate artifact through trusted main.

Existing signing workflow uploads `doom-internal-signed-apk-<candidate_sha>`; it does not itself create a GitHub prerelease. Controller must use its separately authorized release path to publish the signed internal-test artifact as a prerelease, or report publication blocked. Never call an unsigned CI APK or mere successful sign dispatch a published prerelease. Read back the exact release/assets and signing evidence before claiming publication. Record source run/attempt, reviewed SHA, signed APK digest, pinned certificate identity, payload equality result, actual versionName/versionCode and prerelease URL. Expected next code is 29, not a hard-coded requirement in Kotlin; reconcile any mismatch with actual run numbering before installation. No production key, production release, or merge authorization.

### Phone acceptance — consenting tester, not automated Instagram interaction

Use the exact signed candidate on a consenting phone/test account. Record source SHA/APK digest, displayed BuildConfig name/code, Android/device and Instagram versions. Do not add production capture/logging and do not collect private content. Use human visual observation and category/timing/pass-fail notes. Any external screenshot evidence must be explicitly consented and contain only opaque Doom-owned UI (no private Instagram content, notifications, report body or account data); otherwise retain a synthetic screenshot plus non-content observation notes. Phone screenshots are not automatic report export.

Acceptance matrix:

1. Both consents default off independently, prior obsolete keys cannot enable them; revoke each while visible and during simulated/detected removal delay. No stale action, new overlay or report copy afterward. Stop/disconnect/reconnect clears appropriately.
2. Normal entry from launcher/Recents: navy/paper/mint diamond visible, clear five-second countdown beginning after actual overlay attach; no repeated overlay within same verified foreground session. Record first-frame exposure/measurement uncertainty. Classifier status is enum only.
3. Normal animation and system-disabled/reduced motion: animated pixel expansion/contraction vs fixed middle diamond; same elapsed deadline. TalkBack does not rapidly announce seconds; focus/48dp actions work at large fonts and in landscape without clipping. Copy disclosure is reachable/readable within the bounded presentation; if not, flag a product/accessibility blocker rather than extending timing silently.
4. Skip from Feed/Reels/Stories, Inbox, thread, composer and notification-opened DM: first observe physical removal, then route attempt only to Instagram. Record actual destination category as inbox/other/no change/unavailable; do not record message text. Route failure, absent/unhandled target or OS launch restriction leaves overlay removed and does not trigger browser/Home/re-overlay. Already in a thread may go to inbox; disclose this explicit-tap effect, never silently redirect on timer.
5. Leave Instagram: removal before HOME. Back/Home/Recents/lock/rotation/call/system interruption/foreign app and direct return to Doom during pause/retry: no stuck obstruction, no late inbox launch; direct Doom return preserves report unless stronger safety cleanup intervenes.
6. In-place status for real success, unknown classifier, truncated report and known failure: CAPTURED only for real bounded report. On the existing failure reproduce `REPORT UNAVAILABLE` where the overlay remains eligible; if safety removal occurs first, record that limitation. No invented sample or false success. Full report never appears in overlay or accessibility descriptions.
7. COPY CURRENT REPORT is absent unless report consent/connection/current bounded report permit it. Explicit tap copies only that current report through Observation and reports clipboard transfer honestly. Main-app reviewed-copy still requires reveal. Tester clears clipboard manually; Doom must not claim clearing its own state recalled an external copy.
8. Use 20 repetitions per high-risk lifecycle/entry/skip path inherited from WIL-149. Record measured removal latency following a delivered disqualifying event (target <=100 ms) and obstruction on explicit skip/bypass paths (target <250 ms), with device measurement limitations. Removal-retry/exhaustion cases must also have deterministic integration evidence; do not force unsafe stuck windows on an ordinary user's phone.

Separate diagnostic acceptance from future DM-safe rollout acceptance: v28 intentionally can pause DM entry for five seconds, so the existing document's zero-messaging-false-block/redirect rollout criterion cannot be honestly passed by this diagnostic feature. Retain that future gate as blocked; do not silently relax it into a protection claim. A phone may legitimately show that the best-effort URI does not open inbox. Safety can pass while route capability remains unverified/unavailable; report them separately. Stop release escalation if safety fails. A signed internal prerelease may be the artifact needed for this phone gate, not evidence that it has passed.

## 9. Proposed tracer-bullet tickets under WIL-149

Create no issues from this planning role. Controller reviews the manifest, creates children under WIL-149 and adds native dependencies using the returned issue IDs, never placeholder IDs or prose-only blocker links. Four tickets keep safety, privacy, visual integration and evidence ownership distinct without a horizontal platform project. Priority uses Linear numeric convention: 1 urgent, 2 high, 3 normal, 4 low; no production urgency is implied.

### Wave 0 — Remove overlay before best-effort messages routing

Outcome: a current explicit Skip tap results in at most one package-targeted inbox attempt after verified physical removal, with stronger safety/foreign/return actions suppressing it. Steps 2-6.

Acceptance: exact Intent contract; race table including old callbacks, HOME/BYPASS/COMPLETE/PRESERVE_REPORT/RESET_OUTSIDE, revocation/reacceptance and exhaustion; no launch while attached; failed route leaves removed/BYPASSED; one gate per session and five-visible-second regressions; source guards plus real adapter/integration tests.

Real blockers: verified prescribed base, permission to obtain tests-first GitHub CI evidence; no dependency on styling or new report capture. Risk: high (external navigation, synchronous removal/cancellation races). Verification: pure policy/guard tests, Android fake-boundary wiring/Intent tests, exact-head independent safety review, later phone destination/latency matrix. Exclusions: browser/alternate scheme, classifier DM bypass, node/gesture automation, guaranteed route support, new permission.

### Wave 0 — Diagnose and explicitly copy the current bounded report in place

Outcome: Observation supplies compact truthful live status and an explicit overlay current-copy path without exposing a report body or weakening reviewed-copy in the app. Steps 7-8.

Acceptance: CAPTURED iff eligible existing bounded report; UNKNOWN distinct from unavailable; stale enabled controls fail safely; current-on-click consent and report recheck; one clipboard sink, sensitive flag, honest transfer/failure; unchanged sanitizer and reset behavior. Known phone capture failure remains UNAVAILABLE.

Real blockers: none beyond baseline/CI access; can be implemented independently of route architecture against the declared snapshot/copy contract. Risk: high (privacy boundary, stale sample/success state). Verification: synthetic Observation/clipboard tests including sentinel/denial/failure/replacement, source guards, no collector/permission changes. Exclusions: capture failure root-cause repair, report schema expansion, auto-copy/reveal, files/network/screenshots/raw content, full overlay report.

### Wave 0 — Ship the observable Doom breathing overlay and build footer

Outcome: the actual service renders the shared pixel aesthetic, accessible controls, live status/copy and exact installed version, with bounded lifecycle-safe updates. Steps 9-12.

Real blockers: routing ticket supplies guarded action/callback interfaces; report ticket supplies status/copy interfaces. These are integration blockers, not a reason to make the earlier tickets depend on visual polish. Risk: medium-high (clipping, motion accessibility, self-event recursion, View lifetime). Acceptance: section 5, no independent clock/composition owner, five-visible-second/no-regate invariants, UNAVAILABLE visible when eligible, honest DM/clipboard/route disclosure, BuildConfig values. Verification: pure geometry/contrast/countdown tests, real factory instrumentation/layout semantics, service update/disposal tests, footer checks. Exclusions: Compose in service, persistent motion setting, animation framework/dependencies, production permission or fixture changes.

### Wave 0 — Verify the exact candidate and hand off a signed diagnostic prerelease

Outcome: reviewed exact-head draft PR with intact canonical CI/signing contracts, supplementary synthetic overlay screenshots, protected signed prerelease and honest phone-test ledger. Steps 13-14 and section 8.

Real blockers: all behavior slices complete, independent Opus review availability, successful exact-head CI, protected signing approval, controller release authority; consenting phone/tester for actual Instagram acceptance. Risk: medium-high (false evidence/provenance, accidental signer weakening). Acceptance: no extra files in signer input, manifest/APK/SHA match, screenshots genuine/synthetic-labelled, exact-head review after fixes, signed payload identity, actual build identity, no merge, phone gates recorded as passed/failed/not run rather than inferred. Verification: host manifest negative tests, baseline/device/fixture jobs, separate screenshot digest manifest, protected signer output/readback and release asset readback, phone matrix. Exclusions: local Android/signing, signer workflow redesign, credentials to agents, production key/release, DM-safe rollout or merge authorization.

## Linear ticket manifest

The following single YAML document is the machine-readable final section. All issue keys are deliberately `NEW`; title strings are exact dependency keys, not existing issues. Final execution order is encoded after the tickets so the plan ends with the bounded worker/reviewer handoff.

```yaml
tickets:
  - key: NEW
    title: "Wave 0 — Remove overlay before best-effort messages routing"
    blocked_by_titles: []
    priority: 2
    description: |
      Child of WIL-149. Implement steps 2-6 from the inspected base
      9bf29169271010d9b36c8d6e316c97f46ed49795 on existing draft PR #3.
      Add NAVIGATE_MESSAGES, physical-overlay callback tokens, irreversible
      safety veto and one-shot post-detach package inbox routing. Risk: high,
      because a stale callback or wrong action priority can navigate externally.
      Real external blockers are authorized tests-first GitHub CI access and
      exact-head review; styling/report work is not a blocker.
    acceptance_criteria:
      - "Use ACTION_VIEW https://www.instagram.com/direct/inbox/ with package com.instagram.android and FLAG_ACTIVITY_NEW_TASK only."
      - "Invoke the launcher at most once and only after current installed overlay physical detachment is confirmed; normal return means ATTEMPTED, never guaranteed inbox success."
      - "Keep COMPLETE < NAVIGATE_MESSAGES < PRESERVE_REPORT < BYPASS < HOME < RESET_OUTSIDE for normal requests; safety cleanup and exhaustion veto pending and later external actions for the removal episode."
      - "Cover both request orders, direct MainActivity return, missing/foreign root, interruption, consent revocation/reacceptance, retry exhaustion, stale UI/completion/retry callbacks and new-session isolation."
      - "Failed/unavailable launch leaves overlay removed and same session BYPASSED; no fallback or re-gate; preserve five-visible-second addView timing and one gate per verified session."
      - "Keep physical removal retries and disable fail-safe; callbacks cannot acquire a newer ticket's authority."
    exclusions:
      - "No browser fallback, alternate scheme, chooser, node clicks, coordinate gestures, raw-tree actions or automatic launch."
      - "No permissions, package queries, collector changes, guaranteed deep-link support or DM-safe claim."
      - "No merge, credentials, local Android execution or signing by worker."
    verification:
      - "Observed RED then GREEN for each policy/guard behavior via registered cached Kotlin/JUnit host runner."
      - "GitHub-only Android Intent and controlled service-boundary order tests, plus existing unit/lint/instrumentation regressions."
      - "Independent exact-head safety review; actual Instagram destinations and physical timing remain the phone gate."

  - key: NEW
    title: "Wave 0 — Diagnose and explicitly copy the current bounded report in place"
    blocked_by_titles: []
    priority: 2
    description: |
      Child of WIL-149. Implement steps 7-8 using Observation as the sole
      report/export owner. Expose enum-only status and an explicit current-report
      overlay copy path without revealing report text or weakening the main-app
      reviewed-copy guard. Risk: high, because stale consent/sample/success state
      could cross the clipboard privacy boundary. No code dependency on routing;
      authorized tests-first CI is the real external blocker.
    acceptance_criteria:
      - "Expose only classifier enum, CAPTURED/UNAVAILABLE and eligibility booleans; CAPTURED requires an eligible existing bounded report, not a classifier or detection success."
      - "Null/cleared/denied/disconnected state maps to UNKNOWN and UNAVAILABLE; the existing phone capture failure is not relabelled as success."
      - "Recheck consent, connection and current report synchronously on explicit copy; no cached report payload or delayed write."
      - "Use one Observation clipboard sink with the existing sensitive-preview flag; return COPIED only after setPrimaryClip returns, otherwise UNAVAILABLE and no stale success."
      - "Overlay copy does not set revealed; main-app copy still requires explicit reveal; replacement/clear/revocation resets stay intact."
      - "No full report in overlay visual/accessibility text; copy disclosure states system clipboard transfer without prior overlay preview and inability to recall external copies."
    exclusions:
      - "No collector/root-failure repair, report schema/bounds expansion or new permissions."
      - "No automatic export/reveal, files, logs, network, screenshots, UI content, node/window IDs or raw trees."
      - "No separate report cache, persistent copy state or upload feature."
    verification:
      - "Observed RED/GREEN synthetic status and Observation tests, including UNKNOWN with a real report and null replacement."
      - "GitHub instrumentation clipboard sentinel, denial, replacement, missing-manager and throwing-manager tests; old reviewed-copy tests remain passing."
      - "Host source guards prove one sink and no forbidden API expansion; independent privacy review and phone in-place copy checks."

  - key: NEW
    title: "Wave 0 — Ship the observable Doom breathing overlay and build footer"
    blocked_by_titles:
      - "Wave 0 — Remove overlay before best-effort messages routing"
      - "Wave 0 — Diagnose and explicitly copy the current bounded report in place"
    priority: 2
    description: |
      Child of WIL-149. Implement steps 9-12: shared pure Doom pixel presentation,
      native View overlay, service-driven live status/countdown, accessible
      actions, exact BuildConfig footer and honest disclosures. Risk: medium-high
      from View lifecycle, self-event recursion and high-font clipping. Native
      blockers are the scoped callback/route and Observation interfaces above.
    acceptance_criteria:
      - "Use Ink #171B25, Paper #F3E7CF and Jade #73B39C with the app's pixel-diamond breathing geometry; keep TYPE_ACCESSIBILITY_OVERLAY and existing window authority."
      - "Use no ComposeView/service lifecycle owner and no independent animation clock; start elapsed presentation only after addView/overlayShown success."
      - "Provide bottom primary SKIP TO MESSAGES, secondary LEAVE INSTAGRAM and COPY CURRENT REPORT only when permitted by current Observation state."
      - "Render only INSTAGRAM DETECTED, classifier enum and REPORT CAPTURED/REPORT UNAVAILABLE for diagnosis; use honest separate user-triggered clipboard feedback."
      - "Respect system-disabled animations with a still diamond and unchanged five-second deadline; no rapid automatic countdown speech."
      - "Targets at least 48dp, readable contrast, focus semantics, font scales 1.0/1.3/2.0, 320dp-width and landscape/inset checks; no clipped or unreachable actions."
      - "Refresh status without new collection or timing reset; ignore self-overlay events and dispose/freeze presentation on closing/revocation/stale token."
      - "Display exact BuildConfig.VERSION_NAME and VERSION_CODE in all Doom screen footers; explicitly enable BuildConfig generation, never hard-code v29."
      - "Disclose diagnostic DM pauses, best-effort package routing and system clipboard transfer; preserve no-protection/no-rollout claims."
    exclusions:
      - "No Compose inside AccessibilityService, extra dependency, new persisted motion preference or changed permission/window type."
      - "No collector, classifier gate policy, fixture implementation or version numbering policy change."
      - "No full report display, auto-copy or claimed phone validation."
    verification:
      - "Observed host RED/GREEN geometry, timing, reduced-motion and calculated contrast tests."
      - "GitHub real View factory/layout/semantics and service render/dispose tests, plus exact BuildConfig footer assertions."
      - "Independent exact-head visual/accessibility/privacy review and consenting phone TalkBack/motion/layout checks."

  - key: NEW
    title: "Wave 0 — Verify the exact candidate and hand off a signed diagnostic prerelease"
    blocked_by_titles:
      - "Wave 0 — Remove overlay before best-effort messages routing"
      - "Wave 0 — Diagnose and explicitly copy the current bounded report in place"
      - "Wave 0 — Ship the observable Doom breathing overlay and build footer"
    priority: 2
    description: |
      Child of WIL-149. Implement steps 13-14 and section 8. Bind supplementary
      synthetic overlay screenshots to the tested APK without altering canonical
      signer input, obtain exact-head independent review/CI, then controller-only
      protected signing and internal prerelease readback. Risk: medium-high from
      provenance mismatch or overstated device evidence. External blockers:
      successful GitHub jobs, independent Opus reviewer, protected signing approval,
      controller release authority and consenting phone tester for actual behavior.
    acceptance_criteria:
      - "Keep existing canonical exact-four demo screenshot/manifest/APK contract and protected trusted-main signer unchanged."
      - "Store six genuine synthetic overlay/footer PNGs and strict SHA/run/attempt/APK-bound supplementary manifest only under app/build/reports/androidTests/overlay-evidence."
      - "All baseline, device and fixture jobs pass on exact reviewed candidate HEAD; artifacts are read back and hashes/identities validated."
      - "One independent Claude Code Opus review covers the full exact-head diff and safety/privacy interfaces; every repair requires new review and relevant RED/GREEN plus CI."
      - "Protected signer uses the exact device-tested APK from a successful source run; certificate and non-signature payload equality verified; no PR code receives signing secrets."
      - "Controller verifies published signed internal prerelease assets and actual BuildConfig identity; signing artifact alone is not release publication."
      - "Record actual phone outcomes/latency and 20 repetitions per high-risk path or mark not run/blocked; never infer real Instagram success from synthetic evidence."
      - "Keep PR #3 draft, diagnostic default off and DM-safe rollout blocked; stop at verified draft PR and signed internal prerelease without merge authorization."
    exclusions:
      - "No local Gradle/Android/emulator/adb/signing, credentials to worker/reviewer or signer workflow redesign."
      - "No production key/release, merge, permission expansion or private Instagram screenshot/report capture."
      - "No treating unavailable inbox/report, missing evidence or failed provisioning as success."
    verification:
      - "Host supplementary manifest negative tests and unchanged signing/fixture guards, shell syntax and diff checks."
      - "Exact-head GitHub check/job/artifact readback, canonical and supplementary manifests, real nonzero instrumentation summary."
      - "Independent review SHA, protected signing evidence, certificate/payload checks and prerelease asset readback."
      - "Consenting phone matrix separates diagnostic safety, route capability and future DM-safe rollout; unresolved gates remain explicit."

implementation_order:
  - "Controller authorizes execution from the prescribed worktree/base; one Codex CLI Luna-high worker performs Step 1 and routing Steps 2-6 with vertical observed RED/GREEN. No additional workers or subagents."
  - "The same worker performs report Steps 7-8, then shared presentation/live overlay/footer Steps 9-12 against the declared interfaces. Controller alone obtains tests-first GitHub CI runs."
  - "The same worker implements supplementary evidence Step 13, runs allowed host checks and freezes a candidate SHA with actual CI/artifact evidence."
  - "One independent Claude Code Opus reviewer executes Step 14 on that exact full candidate; worker repairs with regression RED/GREEN and reviewer rechecks each new HEAD."
  - "Controller verifies exact-head baseline/device/fixture evidence, obtains protected trusted-main signing approval, and verifies the signed internal prerelease assets without merging draft PR #3."
  - "Consenting phone testing uses that exact signed artifact; record safety/report/route outcomes or explicit blockers. Stop at verified draft PR and signed diagnostic prerelease; no merge or production rollout authorization."
```
