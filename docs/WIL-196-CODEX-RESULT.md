# WIL-196 Codex result

Implemented in the isolated worktree `/mnt/HC_Volume_106820083/worktrees/doom/wil-196-sunset-debug-report`.

Base and branch: `c49e7ea3730807623eb36841afa25f107073cca0` on `wil-196-sunset-debug-report`. The worktree still has that base commit as `HEAD`; the implementation is intentionally uncommitted.

## Implemented

- Replaced the two bloom render paths with shared 32-pitch float geometry, 20%→87.5% width envelope, clipped fractional cells, smootherstep easing, bounded organic stagger, one ivory center, warm palette, and static reduced-motion geometry with live phase/progress.
- Added the small secondary `Debug report` reminder action. It remains below Skip and Leave, requires current authority and confirmed physical detachment, preserves only the current hidden process-memory report, opens Doom through an explicit fixed-action Intent, and never earns cooldown.
- Added scalar-only v2 structural metadata DTOs and a public-API Android adapter with SDK ceilings, closed unavailable markers, safe resource/class identifiers, strict unique-ID admission, bounded BFS/recycling, indexed relationships, geometry, actions, flags, collection/item/range and numeric metadata. Metadata is not used for selection or action authority.
- Replaced the aggregate report with deterministic indexed v2 serialization capped at 128 nodes, depth 8, 16 reported children/actions, 64 tokens, and 8,192 ASCII bytes. Added fresh v2 consent migration and Debug reveal/copy-state hiding.
- Added canonical synthetic API-ceiling, Intent, consent, Debug detach/order, geometry, report, policy, trace, and evidence-contract coverage. Updated AGENTS/privacy, README, validation, fixture lane count, accessibility disclosure, and narrow host guards. Existing manifest, permissions, dependencies, workflows, fixture implementation, canonical inventory, supplemental fourteen-file inventory, signing, and Direct-tab authority contracts were preserved.

Changed implementation/test/documentation files are:

`AGENTS.md`, `README.md`, `app/src/main/java/com/chardy/doom/{AndroidStructuralMetadataReader.kt,BreathingVisuals.kt,DoomAccessibilityService.kt,EntryGateOverlayView.kt,MainActivity.kt,Observation.kt,OverlayRemovalPolicy.kt,OverlayRemovalTrace.kt,PixelBreathingView.kt,SanitizedStructuralReport.kt,StructuralMetadata.kt}`, `app/src/main/res/values/strings.xml`, `app/src/test/java/com/chardy/doom/{BreathingVisualsTest.kt,InstagramSurfaceShadowClassifierTest.kt,OverlayRemovalPolicyTest.kt,SanitizedStructuralReportTest.kt,StructuralMetadataTest.kt}`, `app/src/androidTest/java/com/chardy/doom/{EntryGateOverlayUiTest.kt,EntryGateServiceActionTest.kt,StructuralDiagnosticUiTest.kt,StructuralMetadataApiTest.kt}`, `docs/FIXTURE.md`, `docs/WIL-149-VALIDATION.md`, `scripts/{test-entry-gate-host.py,test-overlay-evidence.py,test-removal-trace.py,test-structural-lifecycle.py}`, and this result file. The supplied plan file remains unmodified.

## TDD evidence

The first candidate RED was focused in `test-structural-lifecycle.py`: 42 tests ran with two failures caused by stale assertions for the old `root.packageName?.toString()` check and the old `nodes` queue variable/syntax. Those were updated to the safe package-token and visited-budget contracts. The subsequent lifecycle suite passed after the new adapter allowlist, mutation negatives, and Debug branch guards were added.

Final GREEN host results:

- `python3 -B scripts/test-entry-gate-host.py`: **84 tests passed**.
- `python3 -B scripts/test-structural-lifecycle.py`: **44 tests passed**.
- `python3 -B scripts/test-overlay-evidence.py`: **7 tests passed**.
- `python3 -B scripts/test-fixture-evidence.py`: **21 tests passed**.
- `python3 -B scripts/test_wil155_host.py`: **5 tests passed**.
- `python3 -B scripts/test-session-timer-host.py`: **5 JVM tests passed; 11 contract tests passed**.
- `python3 -B scripts/test-removal-trace.py`: **8 tests passed**.
- `python3 -B scripts/test-ci-isolation.py`: **6 tests passed**.
- `python3 -B scripts/test-android-junit-validator.py`: **8 tests passed**.
- `python3 -B scripts/test-integrated-ci-harness.py`: **17 tests passed**.
- `python3 -B scripts/test-internal-signing.py`: **16 tests passed**.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh`: passed.
- App XML parsing: passed. `git diff --check`: passed. Worktree/base identity checks: passed.

## Unresolved risks and deviations

No source-level deviation was needed for privacy or authority. The public `uniqueId` request was implemented conservatively: only a value equal to an already accepted safe resource ID is retained; all other values become `u:free_form`. Event action/movement metadata is unavailable when not meaningful for the subscribed event types. This is the safest interpretation of the plan’s free-form and subscription constraints.

Android compilation/lint may still identify SDK-35 API-signature or lint issues in the new adapter and instrumentation fixture. The bounded API ceiling and all later getter calls are source-guarded, but they were not compiled in this environment. Device attachment/timing, TalkBack reachability, screenshot appearance, warm/cold/recreated Activity behavior, and actual launch failure behavior remain runtime evidence risks.

## Explicit execution limits

No Gradle command, Android build/lint preflight, emulator, device, adb, GitHub Actions run, signing, release, commit, push, PR, or CI dispatch was performed. No real Instagram session or phone evidence was run or claimed. The required Java-17/SDK-35 Android preflight and authorized canonical/supplemental device gates remain for Codex Cloud/GitHub CI.
