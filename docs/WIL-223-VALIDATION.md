# WIL-223 — YouTube Shorts breathing reminders

## Recovered scope

Read from the authenticated [live WIL-223 ticket](https://linear.app/wildhearts/issue/WIL-223/wave-1-add-a-breathing-reminder-for-verified-youtube-shorts) on 2026-10-23. Status: In Progress; priority: High; labels: Feature, ready-for-agent, Wave 1. Related: WIL-222 and WIL-234. Implement from current `origin/main` after WIL-222 is merged. This checkout starts at exact main `f81b63353b0364e4853a8ad54945b6bf0edea9d3`, which includes WIL-222 and PR #19's overlay repairs. The old Shorts branch is not the implementation base.

Acceptance criteria recovered from the ticket:

1. Only a fresh exact-package, complete, unambiguous Shorts-player sample admits a reminder, with both global reminders and a separate YouTube opt-in enabled.
2. Persist the YouTube opt-in, default off, separately disclosed and independently revocable. Instagram consent must never authorize YouTube.
3. Home, search, subscriptions, long-form playback and Shorts shelves never start or retain a reminder.
4. Unknown, ambiguous, conflicting, truncated, missing-root, timeout, error, stale-ticket and revoked-authority observations fail open.
5. Snapshot shared duration/spacing per ticket. Keep a separate process-local YouTube cooldown, armed only by successful completion.
6. No YouTube timer, Messages, Debug, routing, click, gesture, retry fallback or content-based fallback. Only explicit Leave/Home may be reused after detachment.
7. Confirm every owned window physically detached before completion, Home or external actions. Removal failure releases no action.
8. Foreign attribution, revocation, disconnect, interruption and stale callbacks safely remove/invalidate the gate. Non-player/uncertain samples cancel without cooldown/action.
9. Preserve Instagram Feed/Reels/Stories, DM bypass, the exact Direct-tab exception, reports, timer and cooldown under regression tests.
10. No new permissions, accessibility capabilities, network/storage behavior, analytics, history, dependencies, SDK or toolchain upgrades. Preserve application ID/signing identity.
11. Truthful disclosures and version-specific consenting phone validation are required before claiming real YouTube compatibility.

The exact target is `com.google.android.youtube`. Approved player resource names are `reel_watch_fragment_root`, `reel_recycler`, `reel_watch_player`, `reel_player_page_container`, `reel_player_underlay`, and `shorts_video_pager`. Exclusions are `shorts_shelf` and `reel_shelf`. One player marker is sufficient only after the entire bounded scan finishes without shelves or incomplete/conflicting evidence. Only exact package attribution, resource IDs and traversal-control metadata are permitted. Text, descriptions, captions, titles, channels/accounts, coordinates, URLs, screenshots and the richer Instagram report are excluded.

## Implementation boundary

`YouTubeShorts.kt` defines a minimal node boundary, closed marker categories, a complete-scan collector capped at 128 nodes/depth 8/100 ms, and an independent ticket/cooldown policy. Null IDs are ordinary structural nodes; non-null malformed/foreign IDs invalidate collection. Only complete/player/shelf booleans leave the collector. Every acquired node is released on normal and exceptional exits. No observation is persisted or exposed in Debug.

`YouTubeShortsController` owns its own policy, callbacks, saved visual/Leave windows and cleanup loop. It reuses the breathing visuals/timing model, inert-window recovery, pending-registration ownership and removal-only retirement barrier. No Instagram policy/router is generalized. Every window must be detached before a terminal action; completion/Home then rechecks ticket identity, live authority and a newly collected player sample. Cancellation vetoes pending actions. Exhausted fast removal attempts veto further YouTube reminders while slow physical cleanup continues. Disconnect transfers physical handles together with any Instagram handles to the existing retiring owner without an action continuation.

The service completes its existing Instagram foreign-event cleanup before offering the event to YouTube. YouTube must release ownership before an Instagram gate/timer can install. Global reminder disable and explicit Stop also cancel YouTube. Returning to Doom, configuration change and screen/keyguard failure cancel conservatively. The 50-ms watchdog interval is a scheduling request, not a measured device latency. The fixed resource-ID set remains version-sensitive and does not establish compatibility with actual YouTube layouts.

## Validation ledger

JVM tests were authored before production classes. A runnable failing-first result was blocked: the host runner exits before compilation because cached Kotlin/JUnit libraries and Java are unavailable. Do not call this an executed red/green cycle.

Implementation host checks passed: structural lifecycle 47; overlay evidence 8; fixture evidence 21; WIL-155 guards 5; Android JUnit inventory/partition validator 8. With the existing Git Bash added to this process PATH: signing checks 15 pass/one Windows symlink-privilege error; CI-isolation checks 5 pass/one Windows shell-fixture error; integrated harness checks 16 pass/one Windows path-separator assertion error; removal-trace guards 8 pass. Shell syntax and changed-XML parsing passed. No checks were weakened to mask platform limitations. Final expanded host/preflight/CI results must be recorded after execution. These are not Android, instrumentation, signing or phone evidence.

New JVM coverage includes every closed marker, shelves in either order, ordinary surfaces, malformed/foreign IDs, scan limits/deadlines, node recycling, current-owner tickets, visible timing, physical-detach requirement, settings snapshots and cooldown isolation. Canonical synthetic instrumentation uses the real controller with fake physical side effects and bounded synthetic nodes, plus actual service event dispatch. It covers false positives, uncertainty/authority loss, delayed detachment, partial adds, stale/other-owner callbacks, install-time revoke, lifecycle transfer and preference persistence. These tests cannot prove real WindowManager timing or YouTube behavior.

Required before review/acceptance: all host gates including CI isolation/inventory; Java 17/SDK 35 unit/lint/debug/test-APK preflight; independent exact-diff review; canonical exact-head API 35 instrumentation; independent fixture evidence; APK payload comparison; protected internal signing. Existing screenshot sets and signing pipeline remain unchanged. Signing and phone execution require their normal authorization/environment.

## Consenting phone matrix — unexecuted

Canonical run `39921817981` at `16886fc5679298494d3c4e65d08711b888931ea9` passed baseline (173 unit tests; zero lint errors, 18 warnings) but failed two of 166 device tests. The preserved JUnit identifies `StructuralDiagnosticUiTest.interruptionDisconnectAndDestructionClearEverythingAndRejectDelayedCallbacks` and `delayedInstagramEventWithNullActiveRootInvalidatesReport`: both throw at `DoomAccessibilityService.kt:523` because the new `finally` dispatch dereferenced `applicationContext` on an intentionally unbound service. All nine new YouTube device tests passed. The repair uses the existing compile-time application ID for own-event attribution, eliminating this unnecessary context read without changing observation authority. A fresh-SHA CI result is required; the failed run is not acceptance. Supplemental run `39921818053` at that head passed five UI/six fixture tests; downloaded reports, 14 UI/13 fixture captures and all manifest hashes were inspected/verified independently.

Record only signed artifact SHA/version, device/Android/YouTube versions, broad surface category, timing and pass/fail. Never collect media, titles, captions, accounts, descriptions, private screenshots or trees.

- New explicit opt-in, independent revoke, global disable and explicit service Stop.
- Shorts entry/swiping; shelf/home/search/subscriptions/long-video false positives.
- Shared settings snapshots, successful-breath-only cooldown and independent Instagram cooldown.
- Back/Home/Recents, rotation, lock, interruption, disconnect and restart.
- Physical detachment before Leave/completion; no released action on removal failure.
- Existing Instagram feed/Reels/Stories, DM bypass, Direct-tab action, reports and timer regressions.

Every real-device row remains pending. Synthetic/fixture passes cannot close them.
