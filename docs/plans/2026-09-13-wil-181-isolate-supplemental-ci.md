# WIL-181: Isolate supplemental Android CI — implementation plan

> Execution handoff: one Luna-high Codex implementer in a fresh context, then one independent Opus reviewer. This is a planning deliverable, not authorization to implement, commit, push, dispatch, sign, release, merge, or update tickets.

**Goal:** Make beta delivery cheaper to diagnose and retry, and make its conclusions truthful, without relaxing product safety or canonical evidence/signing requirements.

**Architecture:** Keep `.github/workflows/android.yml` as the canonical, signer-approved workflow. Move the independent fixture and explicitly supplementary app UI instrumentation into one separate, unprivileged workflow with one disposable emulator and independently retained component results. Keep the trusted-main signer’s successful-source-run requirement intact.

**Tech stack:** Existing Kotlin/AndroidJUnitRunner, Python standard library host guards, Bash, GitHub Actions, Java 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, SDK/API 35 and build-tools 35.0.0. No upgrades or new dependencies.

## 1. Scope, inspected baseline, and evidence status

Working directory: `/mnt/HC_Volume_106820083/worktrees/doom-wil181-ci-isolation`.

Required base: `a6f49fc7703bd0f04e8793892d1192707a66f788`.

Read-only inspection confirmed that HEAD equals that exact base, branch is `ryli721/wil-181-isolate-supplemental-ci`, and the worktree was clean before this plan was written. Root `AGENTS.md` is the only discovered repository agent-instruction file. The user-supplied WIL-181 acceptance criteria are the active scope; no live Linear/GitHub lookup or modification was performed. The planner's only permitted repository write is this file.

Inspected:

- `AGENTS.md`, `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`.
- Both existing Android/signing workflows and every repository script they directly or transitively execute: `scripts/ci-device.sh`, `scripts/ci-fixture.sh`, `scripts/fixture-readiness.py`, `scripts/evidence-manifest.py`, `scripts/overlay-evidence-manifest.py`, `scripts/sign-internal-apk.sh`, `scripts/validate-internal-signing.py`, `scripts/test-internal-signing.py`.
- Existing host/source guards: `scripts/test-overlay-evidence.py`, `scripts/test-fixture-evidence.py`, `scripts/test-structural-lifecycle.py`, `scripts/test_wil155_host.py`, `scripts/test-removal-trace.py`, and `scripts/test-entry-gate-host.py`.
- All six app Android test classes; `fixturegate/src/androidTest/java/com/chardyb/doom/fixturegate/CrossAppFixtureTest.kt`; cooldown policy and relevant service event/install/removal/lifecycle paths; cooldown JVM tests; all three module build files, root settings/build files, wrapper properties, and `.gitignore`.

Source enumeration, not test execution, found 80 existing app `@Test` methods: Doom UI 3, overlay UI 4, service action 45, Messages router 8, removal-trace UI 2, structural diagnostic UI 18. The split below assigns five existing methods to supplemental and 75 to canonical, before adding cooldown instrumentation. Do not use these source counts as proof of an executed Android suite.

Confirmed retrospective evidence supplied by the task, not re-executed or downloaded by this planner:

- 46 completed Android CI attempts: 22 failed, 24 passed. Causes were mixed; this is not a pure flake rate.
- Post-merge run `34742590335`: baseline and canonical API 35 passed; fixture missed overlay startup in two scenarios; unchanged-source rerun passed. Classified intermittent supplemental fixture infrastructure, not proof of a production regression.
- Passing exact candidate run `34741540150` and signed phone build 40 passed all four real-phone core flows. This does not attest a future WIL-181 candidate or the wider Instagram/OEM matrix.
- Verified Codex Cloud cold-cache preflight had Java 17 + SDK 35 configured, 13 signing tests and 86 app unit tests passing, lint with zero errors, and successful debug assembly. Future results must be read afresh.

No host test suite, Gradle, Android, emulator, adb, signing, or release command was executed while preparing this plan. Only read-only source/Git inspection and plan validation are planner verification.

## 2. Executive decision and why

Use two workflows, not a combined workflow with a forgiving signer:

1. `Android diagnostic CI` / `.github/workflows/android.yml`: retain `baseline` and `device`, their names, their dependency, and their signer-facing artifact contract.
2. `Android supplemental CI` / new `.github/workflows/android-supplemental.yml`: one `supplemental` job containing pre-emulator deterministic preparation and one API 35 emulator. Inside that emulator, run supplementary app UI tests, collect them, then run the existing independent cross-app fixture and collect it. Record each component result even if the other fails; return failure if either fails.

This fixes two actual coupling points:

- At the base, `ci-device.sh:23` runs every app instrumented test. Its supplementary pull/manifest at lines 27–28 can also fail the emulator step after the canonical journey passed. Separate directories alone do not isolate this conclusion.
- At the base, the fixture is a job in `android.yml`. Both the signer workflow and `validate_source_run()` require that entire workflow run to have `conclusion=success`. Merely declaring fixture failures nonblocking in documentation cannot allow signing.

After the change, a supplemental red run does not make the canonical run red, does not cancel it, and is not a dependency of its device-evidence upload. The signer still requires the entire canonical workflow to succeed. Do not replace this with job-name lookups, failure-accepting source validation, `workflow_run` privilege escalation, or a blanket `continue-on-error`.

Cost discipline:

- Retain two emulator boots per fully exercised candidate: one canonical and one supplemental. Share the supplemental emulator between the app UI supplement and independent fixture; do not add a third emulator or a matrix.
- Finish deterministic compilation before either emulator boots. Do not pay emulator provisioning cost to discover Kotlin/resource/test compilation errors.
- A classified supplemental rerun need not rebuild/retest the canonical run or change the signer's source run.
- Independent workflows duplicate some app assembly/Android-test compilation. Accept this bounded cost to avoid cross-run artifact/key transfers, privileged triggers, and a new signer protocol. Do not claim measured aggregate savings before comparing actual job/step durations.

Keep WIL-181 one ticket. The topology, filters, tests, and documentation form one bounded change with no product implementation work. Split only if execution uncovers a genuine production blocker requiring an independently scoped behavior repair; do not expand this ticket to fix it silently.

## 3. Exact canonical versus supplemental boundary

### 3.1 Canonical host/build gates

Keep required:

- Privacy, lifecycle, routing, detachment, resource/manifest, evidence and signing host/source guards.
- All existing app JVM tests (`:app:testDebugUnitTest`). Do not split cheap unit tests by aesthetic importance.
- `:app:lintDebug` with its existing severity policy and no new suppressions.
- `:app:assembleDebug` and add explicit `:app:assembleDebugAndroidTest` before emulator provisioning.
- Successful installation/start and all canonical API 35 Android instrumentation described below.

A fixture-only compile/lint/oracle failure is a supplemental engineering defect, not a failed production APK build. A shared build configuration failure that prevents app compilation/install/start remains a beta blocker, regardless of which job first detects it.

### 3.2 Canonical app instrumentation

Default every app instrumented test to canonical unless explicitly annotated with the new test-only marker `com.chardy.doom.SupplementalEvidence`.

Keep all methods in:

- `EntryGateServiceActionTest.kt`, including new service cooldown instrumentation, stale/revoked-authority checks, real service install-path tests using fake side effects, physical-detachment postcondition tests, retry exhaustion and HOME arbitration.
- `InstagramMessagesRouterTest.kt`: exact-ID, cardinality, selected-tab short circuit, actionability, one-shot routing and node recycling.
- `StructuralDiagnosticUiTest.kt`: merged exported activity checks, disclosure/consent, callback dispatch, hidden reports and explicit copy, lifecycle cleanup, persistence and trace controls. Do not demote a privacy test because its class ends in `UiTest`.
- `RemovalTraceUiTest.kt`: explicit clipboard and trace revocation/lifecycle boundary.

Keep these `DoomUiTest.kt` methods canonical:

- `demoMessagesAreImmediateAndFeedRequiresCompletedPause`.
- `backgroundCancelsPendingGate`.

Retain the canonical four genuine Doom-owned screenshots, exactly:

- `01-doom-dashboard-demo.png`
- `02-doom-breathing-demo.png`
- `03-doom-messages-demo.png`
- `04-doom-completed-demo.png`

These are part of the existing core journey and signer manifest, not the six-image supplemental collection. Missing/corrupt canonical screenshots still mean invalid canonical evidence. WIL-181 does not remove them or claim fake-platform tests demonstrate actual OS attachment/navigation.

### 3.3 Supplemental app instrumentation

Create `app/src/androidTest/java/com/chardy/doom/SupplementalEvidence.kt` containing only a runtime-retained function annotation:

```kotlin
package com.chardy.doom

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SupplementalEvidence
```

Annotate each of these five existing methods explicitly; use method annotations, not naming heuristics or exclusions of a whole source package:

| File under `app/src/androidTest/java/com/chardy/doom/` | Method |
| --- | --- |
| `DoomUiTest.kt` | `buildFooterIsReachableAfterScrollingToTheEnd` |
| `EntryGateOverlayUiTest.kt` | `nativeOverlayIsDoomStyledSemanticAndTargeted` |
| `EntryGateOverlayUiTest.kt` | `largeFontAndLandscapeKeepWrappingActionsReachable` |
| `EntryGateOverlayUiTest.kt` | `supplementaryScreenshotsEstablishEachNamedStateAndRestoreConfiguration` |
| `EntryGateOverlayUiTest.kt` | `supplementaryFooterScreenshotScrollsOnlyInItsSeparateTest` |

These cover shape/style, target sizing/focusability, font/landscape reachability, footer polish and the exact six synthetic UI screenshots. Preserve every assertion, setup, foreground check, draw wait, screenshot filename and configuration restoration. In particular, a failure showing an unusable escape/core action is a potential beta blocker even though discovered in this lane; “minor accessibility” is a severity classification, not an automatic waiver for all reachability failures.

“Shape” here means presentation geometry, not sanitized structural-report bounds, tree access/privacy, or report validation. Those remain canonical.

Filters (shell-quoted as single Gradle arguments):

```sh
# Canonical only
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.chardy.doom.SupplementalEvidence

# Supplemental only
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.chardy.doom.SupplementalEvidence
```

No test disabling, `@Ignore`, assumption-based skipping, runner upgrade, sharding or production runner change. An unannotated new safety test is canonical by default. Exact-head JUnit readback must verify a disjoint union of executed identities matching source inventory, with no failures/errors/skips in a passing component. Source guards alone do not establish that AndroidJUnitRunner honored the filters.

### 3.4 Supplemental cross-app fixture

Move the current `fixture` job's function into the new supplemental workflow without changing fixture product or Android test source:

- `:fixtureapp` and `:fixturegate` stay independent of `:app`.
- Keep all six `GatePolicyTest` JVM identities and all six `CrossAppFixtureTest` device identities.
- Keep all thirteen screenshot names, five measured timing fields and their existing validation.
- Keep actual `TYPE_ACCESSIBILITY_OVERLAY` and physical window-absence assertions, public UI consent/revocation, secure-settings restoration, fixed package/resource selectors, emulator-only readiness, and failure diagnostics.
- Keep all PNG/JUnit/timing validation and result precedence in `ci-fixture.sh`.

Fixture success is never Instagram or production-app acceptance. Fixture infrastructure failure alone is not a beta blocker; evidence that points to a shared privacy/safety/product/signing/provenance defect must be escalated.

## 4. Signing and run-conclusion implications

Do not modify `.github/workflows/sign-internal-apk.yml`, `scripts/sign-internal-apk.sh`, or `scripts/validate-internal-signing.py` for this topology change.

Retain all existing checks:

- Manual `workflow_dispatch` only, inputs `source_run_id` and exact lowercase `candidate_sha`.
- Trusted-main workflow/checkout (`github.ref == 'refs/heads/main'`, checkout `github.sha`, matching HEAD), protected `internal-signing` environment and its main-only deployment boundary.
- Read-only `actions: read` and `contents: read`, 10-minute timeout, existing pinned actions and tools; candidate workflow has no signing secrets.
- Source repository `chardy-b/doom`, source path `.github/workflows/android.yml`, exact run ID and `head_sha`, completed status, successful conclusion, approved event and valid attempt.
- Exact named `doom-device-evidence-<candidate>` download from that source run, not the baseline APK or a supplemental artifact.
- Exact supported manifest/file sets, safe regular paths, size/checksum agreement, exact source/context, successful nonzero instrumentation summary and canonical PNGs.
- Stable pinned certificate `76ac486496e74c6a598f06745e0c43d25cdb94d18cdaf2eb69272980043f7003`, APK v2 verification, package/version validation, safe ZIP parsing, and non-signature payload identity.

The crucial behavioral change is upstream: the source workflow no longer contains a fixture job or runs supplemental app instrumentation/manifest generation. A completed canonical success can therefore coexist with a completed supplemental failure while satisfying the unchanged source validator.

Do not append fields to canonical `context.txt`: `validate_evidence()` currently requires its exact key set. Do not add supplemental files, test reports, extra APKs or outcome ledgers inside canonical `evidence/`. The workflow-level successful-outcome manifest refresh remains necessary because `emulator_outcome=success` is appended after the emulator action.

Beta approval is stricter than mechanical signer eligibility. Before an authorized signing dispatch, the controller reads both exact-candidate conclusions and classifies supplemental failures. A suspected/unclassified safety, privacy, core-action or provenance failure holds approval pending diagnosis; a documented supplemental-only polish/fixture-infrastructure defect does not. Keep the manual/protected boundary; do not introduce an automatic release from canonical green. The plan does not assume environment settings or branch protection were verified from the repository; the controller must read them before acceptance, and any changes need separate authorization.

If the repository currently requires the old fixture check in branch protection, it may otherwise remain an impossible/misleading merge gate after relocation. Controller must inspect required checks and, only with authorization, retain canonical baseline/device requirements and remove supplemental-only required contexts. Do not solve this by publishing fake green compatibility jobs.

## 5. Workflow and artifact contracts

### 5.1 Canonical workflow

Preserve:

- Name `Android diagnostic CI`, path `.github/workflows/android.yml`.
- `pull_request` targeting `[main]`, `push` to `[main]`, and input-free `workflow_dispatch`; no path filters and no `pull_request_target`.
- Top-level `permissions: { contents: read }`.
- `CANDIDATE_SHA: ${{ github.event.pull_request.head.sha || github.sha }}`. PR head is the candidate; event/workflow SHA remains separately recorded.
- Concurrency `doom-android-${{ github.workflow }}-${{ github.ref }}`, `cancel-in-progress: true`.
- `baseline`: `ubuntu-latest`, timeout 25 minutes; `device`: `ubuntu-latest`, timeout 30 minutes, `needs: baseline`.
- Every checkout uses candidate ref and `persist-credentials: false`; verify HEAD equals candidate and clean status before evidence-producing work. Add clean-check guards to canonical paths to match fixture discipline. Never clean away unexpected source modifications to make the assertion pass.
- PR Gradle cache read-only expression; existing Java/SDK/KVM setup.
- Device emulator API 35, `google_apis`, `x86_64`, `pixel_2`, existing headless options and disabled animations. No emulator in Codex Cloud.

Changes: remove `fixture`; add all relevant Python source/evidence/signing host checks in baseline; append `:app:assembleDebugAndroidTest` to its Android preparation. Also assemble the app and Android-test APK in the device job before the emulator step because baseline build outputs are not shared across runners. On each GitHub runner, explicitly run `sdkmanager 'platforms;android-35' 'build-tools;35.0.0'` after setup-android and before Gradle preparation; do not depend on the later emulator action to install the compile platform. No cross-job APK substitution: the APK copied by `ci-device.sh` after instrumentation is the one built/tested in that device job.

Canonical artifact names/retention remain:

| Artifact | Upload condition | Retention | Contract |
| --- | --- | --- | --- |
| `doom-diagnostic-apk-${{ env.CANDIDATE_SHA }}` | Baseline success | 14 days | Debug APK only; not signer input |
| `doom-baseline-reports-${{ env.CANDIDATE_SHA }}` | `always()` | 7 days | Existing app reports; add preparation log/test-results paths where available |
| `doom-device-evidence-${{ env.CANDIDATE_SHA }}` | Canonical emulator + final manifest success | 14 days | Existing exact signer contract |
| `doom-device-diagnostics-${{ env.CANDIDATE_SHA }}` | `always()` | 7 days | Context, instrumentation, available four screenshots, reports and Android-test XML; add pre-emulator preparation log |

Keep `if-no-files-found: error` on APK/device evidence/device diagnostics and `warn` on baseline reports. Initialize context before provisioning/build work so device diagnostics have a real file on preparation failure. Store the baseline preparation log at `app/build/reports/baseline-preparation.log` and the device preparation log at `app/build/reports/androidTests/device-preparation.log`; use `tee` under pipefail and add those exact paths to their diagnostic uploads. Never place the device preparation log inside canonical `evidence/`, whose exact signer file set forbids it. Keep original canonical success upload before failure-diagnostics upload. Failed/skipped/cancelled required gates must not publish a success artifact or be signable. `always()` is best-effort under runner loss/hard cancellation; never promise artifacts that the runner could not upload.

### 5.2 Supplemental workflow

New `.github/workflows/android-supplemental.yml`:

- Name `Android supplemental CI`.
- Same exact triggers as canonical: PR targeting main, push to main, input-free dispatch. No cross-workflow trigger, dispatch input accepting arbitrary candidate data, path filter, secrets or write permissions.
- `permissions: { contents: read }`.
- Same candidate expression and checkout identity/cleanliness checks, `persist-credentials: false` and PR cache-read-only rule.
- Concurrency `doom-android-supplemental-${{ github.workflow }}-${{ github.ref }}`, `cancel-in-progress: true`. This namespace cannot cancel canonical or signing runs.
- One job `supplemental`, name `Supplemental UI and cross-app fixture evidence`, `runs-on: ubuntu-24.04`, timeout 35 minutes. No dependency on the canonical workflow or jobs. No job/step-level `continue-on-error`.
- Use the same SDK/platform/build-tools versions, KVM setup, emulator identity/profile/options and animation setting as the old fixture job. A fresh emulator per run; no snapshot reuse, accounts, Instagram installation or sharding. Add explicit `-no-snapshot` to the copied supplemental emulator options to express the existing fixture documentation's no-snapshot requirement; do not change the canonical emulator settings as a side project.
- Initialize `evidence/supplemental-context.txt` and `evidence/fixture-context.txt` immediately after checkout verification, before Java/SDK/Gradle/emulator preparation. Include candidate, event SHA, repository, workflow, run ID/attempt and explicit synthetic/fixture scope. These are supplemental files only, never signer's context.
- Run host/source guards that concern the split/evidence before expensive setup. After setup-android, explicitly run `sdkmanager 'platforms;android-35' 'build-tools;35.0.0'` on this GitHub runner, then the deterministic preparation in section 6 before KVM/emulator boot. Retain its single combined log at `evidence/supplemental-preparation.log` and include that exact log in both supplemental artifact uploads.
- Emulator `id: supplemental`; `script: bash scripts/ci-supplemental.sh`.
- An `if: always()` result step records preparation and emulator step outcomes (`success`, `failure`, `skipped`, `cancelled` as returned), links artifacts and prints the two component exit results when their files exist. An absent component result means not executed, not success. Failure summaries must not print raw report/screenshot/private payloads.

Artifacts:

| Artifact | Paths | Upload / retention |
| --- | --- | --- |
| `doom-overlay-ui-${{ env.CANDIDATE_SHA }}-${{ github.run_id }}-${{ github.run_attempt }}` | Supplemental context, app preparation log, `app/build/reports/androidTests/overlay-evidence/`, app supplemental instrumentation log, app Android test reports/results, copied supplemental tested app APK | `always()`, missing files error, 14 days |
| `doom-fixture-${{ env.CANDIDATE_SHA }}-${{ github.run_id }}-${{ github.run_attempt }}` | `evidence/fixture-context.txt`, supplemental preparation log, `fixture-evidence/`; available fixture APKs/reports/test-results from module build directories for failures before script invocation | `always()`, missing files error, 14 days |

Initialize context before preparation so these uploads do not silently become empty on an early failure. A missing directory is honestly absent; do not synthesize reports/PNGs. Preserve original `fixture-evidence/<sha>-<run-id>-<attempt>/manifest.json` and `SHA256SUMS` on every script-collected result. A build or boot failure before `ci-fixture.sh` starts has workflow context/build reports only, not a fabricated fixture manifest.

Use pinned action SHAs already in this checkout, unchanged:

- checkout: `11d5960a326750d5838078e36cf38b85af677262`
- setup-java: `cf277c60eb25467037889841efdb72551f06f6c3`
- setup-gradle: `ed408507eac070d1f99cc633dbcf757c94c7933a`
- setup-android: `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407`
- android-emulator-runner: `a421e43855164a8197daf9d8d40fe71c6996bb0d`
- upload-artifact: `ea165f8d65b6e75b540449e92b4886f43607fa02`

No new download action is needed. Leave signer's existing download action pin unchanged.

### 5.3 Exact APK identity: no false equivalence between builds

Bind each evidence collection to the exact bytes it actually tested, not merely the same source SHA:

- Canonical manifest still hashes `evidence/doom-diagnostic.apk` and the exact canonical screenshot/context/instrumentation set. Sign only this APK's payload.
- Supplemental overlay manifest retains exact candidate/run/attempt, tested APK size/SHA-256, six screenshot sizes/SHA-256 and `actual_instagram_verified=false`. Copy the tested app APK to an ignored supplemental path, `app/build/reports/androidTests/supplemental-apk/app-debug.apk`, for retention. Pass that identical copy to `build_manifest()` rather than naming an unavailable build-only path; do not put it inside the exact-six screenshot directory. Source/test compilation happens before instrumentation and connected Gradle tasks must not receive changed build inputs afterward.
- Fixture manifest continues to bind both exact fixture APK filenames/bytes/checksums, source SHA, repository/workflow/run/attempt, all collected reports and measured evidence. Keep `SHA256SUMS` including the manifest checksum.
- The supplemental workflow builds its own app debug APK. Different workflow `GITHUB_RUN_NUMBER` values and ephemeral debug signatures can produce different bytes/version metadata. Never assert its APK SHA equals the canonical APK SHA; never sign/distribute its APK as the canonical candidate. Both collections have exact APK+source provenance, but supplementary UI results are not binary-identical release-APK validation. Record this limitation in the summary and docs. Reusing canonical APK bytes across workflows is explicitly outside this smallest topology change.
- Baseline APK can also differ from device APK due to independent debug signing keys. Its existing status remains “diagnostic build artifact”, not “exact emulator-tested signer input”.

Do not accept an artifact from “latest main”, another run, another attempt with different bytes, or matching filename alone. For unchanged-SHA reruns, keep both attempts' URLs/identities and inspect the exact artifacts used; do not splice canonical screenshots/logs/APKs across attempts. Existing canonical names do not contain attempt; retain their signer compatibility and record attempt from the source run API and artifact metadata. If a rerun cannot produce an unambiguous complete artifact, do not sign it.

## 6. Deterministic preparation before emulator boot

Canonical baseline, after host checks and SDK setup:

```sh
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Canonical device runner, before emulator provisioning:

```sh
./gradlew --no-daemon --stacktrace \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Supplemental runner, before emulator provisioning (one shell step with pipefail and a retained log):

```sh
./gradlew --no-daemon --stacktrace \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :fixtureapp:assembleDebug :fixtureapp:lintDebug \
  :fixturegate:assembleDebug :fixturegate:testDebugUnitTest \
  :fixturegate:lintDebug :fixturegate:assembleDebugAndroidTest
```

`fixtureapp` has no JVM/instrumentation test sources/runner in its inspected build configuration; do not invent a suite or a meaningless test count. `fixturegate` has the actual six JVM tests and instrumented suite. `assembleDebugAndroidTest` is required: unit tests, lint and `assembleDebug` alone do not compile Android tests. Validate availability with Gradle task/dry-run inspection during implementation; task existence and successful compilation have not been executed by this planner.

Retain the build command inside `ci-fixture.sh` for its existing standalone GitHub-only contract and `phase=build` diagnostics. On the same runner the already-completed unchanged Gradle tasks should be up-to-date; no new build inputs, clean task or dependency resolution changes are allowed between preflight and instrumentation. Keeping the old invocation avoids extracting/rewriting its embedded validator, trap and host tests just to save an up-to-date Gradle invocation. Connected tests can check build dependencies, but deterministic work must already have succeeded before boot.

A supplemental preparation failure skips the entire supplemental emulator. Preserve preparation logs/available reports; report both runtime components as not executed. This is intentional cheap failure, not a runtime test pass. Canonical execution is independent and continues normally.

## 7. File/component map

Create:

- `.github/workflows/android-supplemental.yml` — independent supplemental conclusion, one emulator, preparation and always-upload contracts.
- `scripts/ci-overlay.sh` — supplemental-only app runner, exact APK/six-image manifest and failure-time collection.
- `scripts/ci-supplemental.sh` — small two-command coordinator, component exit recording and aggregate failure. Not a general CI framework.
- `scripts/test-ci-isolation.py` — stdlib source-contract and mocked-shell regression tests for topology, filter partition, preflight ordering and independent failures.
- `app/src/androidTest/java/com/chardy/doom/SupplementalEvidence.kt` — test-only annotation.

Modify:

- `.github/workflows/android.yml` — remove fixture, add host/pre-emulator compilation gates and failure preparation logs; retain canonical names/contracts.
- `scripts/ci-device.sh` — exclude marked app tests, remove all supplemental pulls/manifest invocations, retain canonical trap/pipeline/artifacts and add exact-clean checkout guard.
- `scripts/overlay-evidence-manifest.py` — point its main entry to the retained identical supplemental APK copy; retain pure `build_manifest()` and its strict exact-six schema.
- `scripts/test-overlay-evidence.py` — migrate source expectations from canonical script to supplemental script; add manifest/APK copy provenance negatives.
- `scripts/test-internal-signing.py` — add tests showing canonical-only source eligibility and rejecting supplemental/failed sources; do not loosen current assertions.
- `app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt` — annotate its four methods only.
- `app/src/androidTest/java/com/chardy/doom/DoomUiTest.kt` — annotate only footer method.
- `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt` — new cooldown tests and misleading HOME name correction using existing private seams.
- `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md` — current lane/build policy and honest evidence limitations. Preserve historical records as historical, not current acceptance claims.

Read and protect, with no planned edits:

- `AGENTS.md`, all production `app/src/main/`, both fixture source trees, all dependency/build configuration/permissions.
- `scripts/ci-fixture.sh`, `scripts/fixture-readiness.py`, `scripts/test-fixture-evidence.py` — keep behavior and existing tests; add outer topology guards in the new test file instead of rewriting the collector.
- `scripts/evidence-manifest.py`, signer workflow/script/validator, existing privacy/lifecycle/resource/trace source guards and pure JVM tests.

If implementation needs an additional change, explain its causal necessity. Do not rewrite unrelated files or upgrade toolchains. The only planner write is this plan; the above map describes future authorized implementation.

## 8. Sequential TDD implementation tasks

Run from the specified worktree. Before implementation, recheck base/HEAD/status and reconcile this now-present plan with the authorized edit scope. No automatic commits between tasks; publication is controller-owned and separately authorized.

### Task 1 — Lock the topology and test partition with failing host guards

Files: create `scripts/test-ci-isolation.py`; extend `scripts/test-overlay-evidence.py` and `scripts/test-internal-signing.py`.

1. Add stdlib `unittest` source guards that fail at this base because the separate workflow, annotation and runner scripts do not exist.
2. Assert exact workflow names/paths/triggers/permissions/concurrency namespaces, pinned actions, timeouts and candidate checkout guards. Assert canonical has only baseline/device, device needs baseline, and no fixture/supplemental script/manifest call or failure-masking option.
3. Enumerate `@Test` methods and marker annotations. Assert the supplemental set equals the five-method table, safety classes contain no marker, canonical demo/background remain unmarked, and runtime annotation retention is explicit. Fail on duplicate identities or unsupported parsing instead of silently omitting tests.
4. Guard both complementary Gradle filter arguments, canonical exact-four capture path, supplemental exact-six path, and absence of supplemental files from signer evidence.
5. Guard fixture/app pre-emulator compilation tasks and their order before the emulator action; fixture unit/lint/build checks must not be only inside the emulator script.
6. Extend signer tests: a valid canonical source is accepted without any supplemental result field; a source path of `.github/workflows/android-supplemental.yml` is rejected; canonical failure/cancelled/incomplete/wrong SHA/repository/run remains rejected. Existing archive/hash/payload/certificate tests remain.
7. Add source guards for the four new cooldown test names and corrected HOME name in Task 5. Require these tests in the unannotated canonical class.

Run individually to see causal RED failures:

```sh
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-internal-signing.py
```

Expected initially: only the newly expressed split/instrumentation requirements fail; existing validation behavior is not changed. Do not call a synthetic host fixture real evidence. Avoid adding a YAML dependency: test stable source contracts and use installed workflow tooling if available; behavioral shell/JUnit checks below backstop source inspection.

### Task 2 — Introduce the marker and separate app runners

Files: annotation, `DoomUiTest.kt`, `EntryGateOverlayUiTest.kt`, `scripts/ci-device.sh`, new `scripts/ci-overlay.sh`, overlay manifest and host tests.

1. Add marker and annotate exactly the five methods. Do not move assertions or alter test bodies.
2. Reduce canonical `ci-device.sh` to its GitHub-only identity guard, canonical directory initialization/cleanup, failure trap, filtered connected tests with `set -euo pipefail`, canonical pull, tested APK copy and canonical manifest. Remove supplementary screenshot directory creation, deletion, pulls and manifest call entirely.
3. In `ci-overlay.sh`, require GitHub Actions, valid run/attempt, exact candidate HEAD and a clean checkout before writes. Create output under ignored `app/build/reports/androidTests/` paths so fixture clean-check behavior is preserved.
4. Copy the prebuilt supplemental app APK to `app/build/reports/androidTests/supplemental-apk/app-debug.apk` before testing for early-failure provenance; verify after connected tests that its SHA-256/size still match `app/build/outputs/apk/debug/app-debug.apk`. Fail rather than publish a successful manifest if the tested build changed. Preserve both available files/logs as diagnostics on mismatch.
5. Clear only `/sdcard/Download/doom-overlay-ui-evidence`, run the annotated suite once, capture `instrumentation.log`, pull screenshots before manifest generation, then invoke `overlay-evidence-manifest.py` using the copied APK. Keep the exact-six manifest strict and outside canonical evidence.
6. EXIT handling preserves the original connected-test exit; pull available images on failures while the emulator lives. If testing succeeds but collection or validation fails, fail the component. Never create a successful manifest on a failed test run or allow an EXIT pull to overwrite the successful manifest directory with stale files. A partial screenshot directory is retained as diagnostics without a success manifest.
7. Move the existing host pull-before-manifest assertions to this script; assert no supplemental work remains in `ci-device.sh`. Add digest/size assertion to the pure manifest test and missing/empty/corrupt/extra file negatives. Synthetic test input must be labeled as such and never uploaded as device evidence.

Run:

```sh
python3 -B scripts/test-overlay-evidence.py
bash -n scripts/ci-device.sh scripts/ci-overlay.sh
```

Expected: overlay host tests GREEN, valid shell syntax. New topology/cooldown guards may remain RED until their tasks. Android filtering is not proven until compilation and exact-head instrumentation.

### Task 3 — Add the minimal supplemental coordinator and exercise failure paths

Files: new `scripts/ci-supplemental.sh`, `scripts/test-ci-isolation.py`.

1. Coordinator repeats GitHub/exact-clean candidate/run checks before either child and writes outcomes only under ignored `evidence/`.
2. Invoke each child as a separate Bash process. Do not call shell functions under `||` if that could disable their `errexit`. Equivalent core control flow:

```sh
overlay_exit=0
bash scripts/ci-overlay.sh || overlay_exit=$?
fixture_exit=0
bash scripts/ci-fixture.sh || fixture_exit=$?
# Persist both exact numeric exits and component scope after each child returns.
# Append them to initialized supplemental context and print a bounded summary.
if (( overlay_exit != 0 )); then exit "$overlay_exit"; fi
exit "$fixture_exit"
```

3. Preserve the fixture's exact nonzero code in its own context/manifest even when overlay failed first. Preserve overlay's exact result before entering fixture in case later timeout prevents final summary.
4. Run overlay before fixture. Overlay report/screenshot teardown and uninstall finish before actual fixture service enablement. Fixture assertions/cleanup remain unchanged. If overlay cleanup pollutes the device, fixture must fail visibly; never reset secure settings blindly to obtain green.
5. Do not add retry loops, `|| true`, success-on-error, artifact deletion or a success sentinel standing in for real tests.
6. Host tests execute these scripts only in temporary mock repositories with fake `git`, `adb`, `gradlew`, child scripts/validator commands as appropriate. Fail immediately if a mock falls through to real adb/Gradle/network. Test both-child success, overlay-only failure, fixture-only failure, both failures, missing APK, pull failure, manifest failure and preflight/identity rejection. Assert invocation order, both attempted when appropriate, original pipeline status, outcome persistence, and no canonical paths touched. Mock outputs are oracle fixtures, not release evidence.

Run:

```sh
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-overlay-evidence.py
bash -n scripts/ci-supplemental.sh scripts/ci-overlay.sh scripts/ci-fixture.sh
```

Expected: coordinator/failure subtests GREEN; only still-unimplemented workflow/cooldown source guards RED. If the harness needs focused selection, expose conventional unittest test methods and select them by class/method argument rather than disabling checks.

### Task 4 — Wire the workflows without changing the signer

Files: `.github/workflows/android.yml`, new `.github/workflows/android-supplemental.yml`, source guards.

1. Implement section 5 exactly. Keep old baseline/device names and source workflow path so trusted-main source validation remains meaningful.
2. Move fixture runtime work out of canonical and into the shared supplemental emulator coordinator. Initialize provenance before preparation; add independent always uploads for overlay and fixture, even after emulator failure.
3. Add the no-emulator preparation commands in section 6. Preserve original build/collection commands inside `ci-fixture.sh`; no fixture validator rewrite needed.
4. Keep canonical successful manifest refresh after recording emulator success and before canonical artifact upload. Do not add supplementary validation to this condition.
5. Extend source guards to verify there are exactly two emulator-action sites across the candidate workflows, no third job/matrix, separate concurrency, no signing credentials, and no cross-workflow conclusion dependency.
6. Make failure-summary/report upload conditions testable. Test preparation failure means emulator skipped with retained context; emulator failure keeps component artifacts; supplemental error has no path to canonical upload condition or source validator.

Run:

```sh
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test-internal-signing.py
```

Expected: workflow/evidence/signing portions GREEN. Run `actionlint .github/workflows/android.yml .github/workflows/android-supplemental.yml .github/workflows/sign-internal-apk.yml` if `command -v actionlint` finds it. Do not silently install/unpin tooling; report if unavailable and rely on source/syntax guards plus actual Actions parsing. Do not claim a YAML/source test proves GitHub workflow semantics.

### Task 5 — Add service-level cooldown instrumentation and correct HOME naming

File: `app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt`. No production changes.

Important source facts:

- Existing `actualAccessibilityCooldownSuppressesBeforeRootAndCollection` manually calls `admitForDisplay` on a prepopulated fixture. Keep it, but it does not cover the service install/admission → detach → fresh-session boundary.
- `monotonicClock` at `DoomAccessibilityService.kt:54` is passed as a function value into `InstagramEntryGate` at construction. The inner `InstagramGateCooldown` retains that original lambda. Reflecting a new value into only the service field does not advance cooldown time.
- Existing `FakePlatform`, `overlayWindowInstaller`, `ContextWrapper.attachBaseContext`, `sendEvent`, `requestOverlayRemovalWithToken`, `field`, and `invoke` give the needed test-only seams. Production installation still calls the real `installOverlay` path.

Add a narrow test helper for a fresh, not-yet-admitted service, separate from the existing `fixture()` that manually constructs policy state. On the activity/main thread:

1. Construct/attach service, wire the existing static `instance` for actual consent-revocation callbacks, set current consent and connection, inject FakePlatform and installer with an installation counter.
2. Make one mutable test `nowMs` and one `() -> Long` lambda. Reflect that lambda into the service's `monotonicClock` and into the current gate's private `cooldown` object's `monotonicNowMs` field. Use existing `field(Any, name)` reflection utility. Do not reset `admittedAtMs`, replace gate generations, or manually admit the policy in these new tests. If Android rejects this test-only reflection, stop and report the seam issue; do not widen production visibility or alter production clock wiring to get green.
3. Keep the entire deterministic event/admission/assertion sequence inside a main-thread callback so scheduled watchdog/completion work cannot add root reads between assertions. Request real service removal to cancel its posted callbacks, and dispose/destroy every created service in `finally`; reset Observation/trace/static instance with the existing cleanup discipline. Assertions/snapshots of service state are main-thread-owned, not unsynchronized reflection from the instrumentation thread.

Add these unannotated tests:

- `serviceCooldownSurvivesDetachAndReentryUntilExactBoundary`: start at test time 10,000; send synthetic Instagram window event; assert actual service installer called once, view attached, current token/ticket present, state GATING, cooldown active. Request HOME through current token and assert no longer attached, HOME exactly once, no Messages route. Send a foreign event with no overlay to reset session/ticket through the actual service. Capture root/install counts and gate generation; seed a synthetic bounded report if needed to make non-mutation observable. At admission + 59,999 send another Instagram event: zero new roots, installations or generation changes; ticket still null; report identity/reveal/copy state unchanged. At admission + 60,000 send it again: exactly one additional event root and installer call, a new current ticket, state GATING and newly active cooldown. Do not sleep 60 seconds or assert wall-clock timing.
- `failedServiceInstallDoesNotArmCooldown`: installer throws before attachment; actual event path performs cleanup; cooldown false, no HOME/Messages call. Reset outside with a real foreign event, repair only the fake installer, and re-enter at the same fake time; the actual install path admits the first successful gate. Assert no fabricated overlayShown/admit calls in the test body.
- `revokedAdmissionDoesNotArmCooldown`: revoke gate consent inside the test installer through `Observation.setGateConsent` while the service is wired, so subsequent real `overlayShown`/admission is rejected. Assert cleanup/detachment, no external action and inactive cooldown; reaccept only through existing consent API, reset session, and verify otherwise-eligible admission at the same fake time. This guards admission rejection, not a new production consent behavior.
- `recreatedServiceStartsWithoutCooldown`: admit/remove a gate through one service, destroy it, instantiate a fresh service with fresh policy and the same fake time/consents, then send a real service event; new instance can admit. Assert cleanup of the old instance and no retained ticket. Label as service-instance recreation semantics, not OS process-kill or real-phone evidence.

Rename precisely:

- Old: `foreignOrMissingForegroundSuppressesRouteAndHome`.
- New: `messagesRouteRejectsForeignOrUnattributedRootAfterDetach`.

Reason: its cases are `FOREIGN` and `NULL_PACKAGE`, not `MISSING`, and it requests only `NAVIGATE_MESSAGES`. The incidental zero HOME-call assertion does not exercise HOME foreground suppression. Keep its assertions and add a comment describing Messages-only scope; do not change production HOME routing to fit the old name. Missing-root Messages behavior already has its own test.

Strengthen existing `homeBeatsPendingSkipAndDoesNotRouteMessages` with zero HOME/Messages assertions while attached, then exactly one HOME and remove-before-HOME call-order assertions after detach. Keep `closingForeignEventVetoesPendingHomeWithoutReadingARoot` and `removalExhaustionVetoesPendingHomeAndLateDetach` canonical. Do not claim the HOME action re-reads the foreground root: the inspected HOME branch does not; foreign-event safety veto is a different path.

TDD honesty: new host guards go RED before these test methods/rename are added, then GREEN. These are added tests of already-present production behavior, so their first correct Android execution may pass. Do not deliberately break production to manufacture a runtime RED record. Real Android compilation and execution remain separately required.

Run cheap checks after editing:

```sh
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-removal-trace.py
```

Expected: all source requirements GREEN, no production source modifications. Compile with `:app:assembleDebugAndroidTest` during no-emulator preflight; execute the tests only in GitHub Actions canonical instrumentation. If they expose a real product regression, hold beta and obtain a scoped repair decision rather than marking them supplemental.

### Task 6 — Correct current delivery documentation, preserve history

Files: `README.md`, `docs/WIL-149-VALIDATION.md`, `docs/FIXTURE.md`.

- Replace current blanket “all Android build work CI-only/no local Gradle” language with the authoritative split: configured Codex Cloud may run no-emulator build/unit/lint/test compilation; GitHub Actions owns emulator/instrumentation/signing/release.
- Document independent canonical/supplemental run conclusions, concrete test boundary and the protected/manual blocker-classification step.
- Keep canonical exact-four screenshots and six supplemental overlay/thirteen fixture screenshot contracts distinct.
- Explain source SHA versus per-run APK digest and the independent-build limitation. Show actual run/attempt/artifact links only after readback, not placeholders disguised as evidence.
- Replace stale present-tense claims that no workflow changed or that fixture success blocks canonical delivery. Mark older WIL-149/fixture repair ledgers as historical rather than rewriting their facts.
- Preserve remaining actual-Instagram privacy-safe phone matrix limitations. The supplied passing phone build 40 is retrospective only; do not relabel it as WIL-181 validation.
- Do not edit root AGENTS policy or update Linear/GitHub from the implementer context.

Run all checks in section 9; request Opus review only after a green no-emulator candidate.

## 9. Verification, review, exact-head CI and beta acceptance sequence

### 9.1 Implementer: host checks first

```sh
python3 -B scripts/test-ci-isolation.py
python3 -B scripts/test-entry-gate-host.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test_wil155_host.py
python3 -B scripts/test-removal-trace.py
python3 -B scripts/test-internal-signing.py
bash -n scripts/ci-device.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh
git diff --check
```

No test-count claims copied from historical docs. If the direct Kotlin host runner cannot find its cached compiler/JUnit prerequisites, report the actual blocker; do not download/upgrade an alternate toolchain by guesswork. Python-only source tests belong in canonical baseline; direct host Kotlin execution need not be duplicated there because Gradle runs the app JVM suite.

Parse any changed XML. None is planned. Use `git diff --name-only` to confirm no production/dependency/permission/fixture behavior changes. In-memory Python syntax checks must not be mistaken for test execution.

### 9.2 Implementer: mandatory Codex Cloud no-emulator preflight

Use the already configured Java 17/SDK 35 environment. Do not install/update SDK packages in Codex Cloud. Run the exact root AGENTS command first:

```sh
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Then compile all relevant Android tests and deterministic fixture paths without booting a device:

```sh
./gradlew --no-daemon --stacktrace \
  :app:assembleDebugAndroidTest \
  :fixtureapp:assembleDebug :fixtureapp:lintDebug \
  :fixturegate:assembleDebug :fixturegate:testDebugUnitTest \
  :fixturegate:lintDebug :fixturegate:assembleDebugAndroidTest
```

Both must exit zero before independent review. Read JUnit XML to report actual signing-test count, app/fixture unit counts, zero failures/errors/skips, lint error counts, APK path/size/SHA-256 and Android-test compilation success. App APK is `app/build/outputs/apk/debug/app-debug.apk`; use `stat -c '%s'` and `sha256sum` on actual files. These commands do not supply device/emulator evidence. Fix deterministic Kotlin/resource/oracle/lint defects before spending review or emulator time. If running in this planner host rather than Codex Cloud, hand off the preflight; do not turn it into an agent-host emulator/build experiment.

### 9.3 Opus review: one green candidate

Provide this plan, the complete diff, exact base/current candidate identity, host/preflight outputs with paths/counts, and explicit unexecuted GitHub/phone items. One Opus reviewer checks the checklist in section 12. Resolve beta blockers and deterministic failures; record nonblocking polish/edge suggestions instead of churning a passing candidate. Re-run affected cheap/preflight checks after any review repair. Do not request a second opinion merely to average away a blocker.

### 9.4 Controller: freeze and run GitHub Actions at exact head

Only after explicit publication/CI authorization:

1. Establish the final committed candidate SHA and clean checkout. Do not pretend the uncommitted implementer worktree already has final-SHA evidence.
2. Run both candidate workflows through their declared PR/push/dispatch trigger at that candidate. For dispatch, ref is the candidate branch and must still resolve to the intended full SHA. Do not dispatch main while validating another branch and then call it exact-head.
3. Read back repository, workflow path, event, `head_sha`, run ID, attempt, status and conclusion separately. A PR's synthetic event SHA must not be mistaken for the checked-out candidate head. Stop on signer/API/source disagreement rather than weakening its exact-head comparison.
4. Read canonical baseline and device conclusions. Check real install/start/instrumentation outputs; failed/skipped/cancelled canonical is not acceptance. Download the exact `doom-device-evidence-<sha>` and separate diagnostics.
5. Run the unchanged evidence validator against a source-run record constructed from the inspected API values, never hard-code a success conclusion to make validation pass:

```sh
python3 -B scripts/validate-internal-signing.py validate-evidence \
  --evidence-dir "$CANONICAL_EVIDENCE_DIR" \
  --source-run-file "$SOURCE_RUN_FILE" \
  --candidate-sha "$CANDIDATE_SHA" \
  --source-run-id "$SOURCE_RUN_ID"
```

6. Read supplemental context, both component exits, reports and artifacts. Verify all six overlay images/exact manifest and both fixture APKs/exact fixture manifest/checksums when passing. When failed, preserve partial diagnostics and precise phase; do not demand a fabricated passing set to log a nonblocking defect.
7. Parse actual app Android JUnit files: canonical methods are the unannotated source inventory, supplemental methods the five marked identities; their intersection is empty and their union complete. All four new cooldown methods and corrected Messages-only name must appear in canonical results. Passing components have no skipped/error/failure cases. Keep fixture six-unit/six-device identities separate.
8. Verify independent outcomes: host mocked failure matrix must prove supplemental-only failure cannot affect canonical upload/signing prerequisites. On real Actions, if a supplemental failure occurs, demonstrate retained canonical artifact and canonical success alongside visible supplemental red. If both runs pass, record that fact and the host-negative result separately; do not manufacture a CI failure or claim a live red case was executed. Read actual workflow structure/conclusions to establish that supplemental is no longer part of source-run success.
9. Inspect required checks and protected signing settings read-only. Classify any supplemental failure under section 10 before an authorized signing approval. For each core-action/privacy/provenance suspicion, hold approval until resolved; otherwise log the supplemental defect and retain independent red.

Suggested read-only controller commands, after authorized runs exist and using approved narrowly scoped GitHub access:

```sh
gh run view "$SOURCE_RUN_ID" --repo chardy-b/doom --json databaseId,headSha,event,status,conclusion,jobs,url,workflowName
gh api "repos/chardy-b/doom/actions/runs/$SOURCE_RUN_ID"
gh api "repos/chardy-b/doom/actions/runs/$SUPPLEMENTAL_RUN_ID"
gh api "repos/chardy-b/doom/actions/runs/$SOURCE_RUN_ID/artifacts"
gh api "repos/chardy-b/doom/actions/runs/$SUPPLEMENTAL_RUN_ID/artifacts"
```

Downloading artifacts writes local files and is controller work, not authorized planner action. Do not run sign/release commands merely because they appear in an implementation plan.

### 9.5 Controller: protected signing and beta approval

After authorized signing, read back the exact signing run and `doom-internal-signed-apk-<sha>` artifact, signing-evidence JSON, source APK digest, signed APK digest/size/package/version/certificate and payload comparison. Signed bytes differ by signatures; compare non-signature payload using the existing validator. Do not sign an independently rebuilt supplemental/baseline APK.

Use an authorized consented phone for any requested new real-phone smoke. Reconfirm the approved core flows (entry pause/completion, explicit Messages, Leave/HOME, and non-regating cooldown behavior) on the actual signed candidate if release acceptance requires them, recording only versions, surface category, timing and pass/fail. Do not claim these were the exact four retrospective scenario definitions without their original record. No Instagram private screenshots, content, names, trees or coordinates. No rollout/DM-safety claim from emulator/fixture evidence.

WIL-181 acceptance requires: canonical exact-head gates/evidence valid; supplemental runs/diagnostics visible and independent; exact provenance for each tested APK; new cooldown and renamed test executed canonically; signer eligibility still strict and unaffected solely by supplemental red; no unresolved beta blocker. A signing or release claim additionally requires its actual authorized execution/readback. Plan completion or a green host suite is not beta acceptance.

## 10. Failure classification and one-rerun policy

Classify the first causal error, not the final umbrella “workflow failed”. Preserve run/attempt/SHA, component, phase, failing identity/signature, original exit codes and artifact links.

| Class | Examples / required evidence | Disposition |
| --- | --- | --- |
| Product build/install/start/core-flow | App Kotlin/resource failure, install/start failure, broken Messages/Leave/completion, lost escape or regating behavior | Beta blocker; deterministic repair before emulator/review where possible |
| Privacy/safety | Consent/stale-authority leak, widened tree/action access, action before physical detach, failed safety veto | Beta blocker regardless of lane; never waive as supplemental |
| Signing/provenance/evidence | Wrong source/candidate/APK digest, invalid canonical manifest, payload/cert mismatch, ambiguous artifact identity | Hold signing; canonical failure is blocking. Missing supplemental images alone are supplemental evidence failure, not automatically corrupt canonical evidence |
| Deterministic supplemental-only defect | Fixture test compile/oracle, UI shape/footer/minor-accessibility assertion with no core-action impact | Fix cheaply where in scope; log independently, do not block canonical APK/signing solely for this |
| Known supplemental infrastructure | Matching classified fixture overlay-startup miss with supporting logs and no shared regression, bounded storage/readiness/transport signature | Preserve red and classify; at most one unchanged-SHA rerun of supplemental workflow |
| Known canonical infrastructure | Identified provisioning/transport failure before valid canonical evidence | At most one classified unchanged-SHA rerun; still no signing until canonical passes with valid evidence |
| Unknown/repeated | Same failure after allowed retry, or insufficient diagnosis | No blind retry. Open/record defect via authorized controller; hold beta only if blocker or unresolved blocker suspicion |

The supplied run `34742590335` classification applies to that inspected occurrence, not every future “Missing overlay” assertion. Confirm stage/signature and available logs first. “Same source” is not “same binary” after a fresh independent workflow run; preserve each run's APK hash/version and only use evidence for the specific accepted artifact.

Readiness polling already implemented in `fixture-readiness.py` remains bounded preparation, not a new full-suite rerun. Do not lengthen its 120-second total/10-second adb command budgets or mutate selectors/waits under the guise of isolation. No automatically retried tests, loops around connected tasks, repeated reruns until green, or deletion of failed attempts. One permitted unchanged-SHA rerun requires a recorded classification and controller authorization; repeated infrastructure failure needs a defect.

## 11. Non-goals and regression risks

Non-goals:

- Production behavior, permissions, dependencies, network, persistence, consent defaults, tree collection or routing changes.
- Replacing the cooldown policy, changing 60,000 ms, five-second visible timing, watchdog intervals or removal retries.
- Fixing intermittent fixture startup in this ticket, replacing fixture tests, weakening their evidence or adding private Instagram automation.
- Removing the canonical exact-four screenshot contract, changing canonical manifest/signer schema, rebasing signer trust to candidate code, or automatic beta release.
- Reusing a canonical APK/test key across workflows, inventing a general lane framework, sharding, adding emulators, or speculative cache/action upgrades.
- Proving real Instagram DM safety, latency, OS attachment timing, phone process-kill or cross-version support with fake-platform tests.

Risks and required mitigations:

- Annotation silently skips tests: runtime-retained function marker, exact source whitelist, complementary runner filters, actual JUnit disjoint-union readback; zero tests is never success evidence.
- Supplemental red hidden by shell/YAML: no continue-on-error; child process status capture, independent always artifacts, negative mock matrix, visibly failed supplemental run.
- Signer still coupled: no fixture job/supplemental operation in canonical source workflow, unchanged strict source validator, regression test rejecting supplemental source path.
- Canonical upload fails due to supplemental files: canonical context/file schema untouched and no supplemental paths under that artifact.
- Shared emulator contamination: overlay first with existing finally/uninstall cleanup, then fixture readiness/consent/secure-setting restoration; diagnose rather than force-reset to obtain green.
- Test clock advances service but not cooldown: inject the same lambda at both captured references, all mutation on main thread, no policy-state shortcuts, compile/run actual tests.
- Pending handlers leak between tests: per-service finally removal/destruction, current-token requests, cleanup of Observation/trace/static instance; no delayed callback access after fixture destruction.
- Different app APKs mistaken for release evidence: per-run APK hash/size and explicit independent-build labeling; signer consumes only canonical tested APK.
- Early preparation loses diagnostics: initialize contexts first; upload available host reports/logs even without emulator/script manifest.
- Existing fixture clean checkout guard sees new output: all coordinator/overlay/preflight files live under already ignored `evidence/` or `**/build/`; fixture's fresh root is created only by `ci-fixture.sh`. No `.gitignore` change or cleaning untracked files to hide provenance problems.
- Required checks retain obsolete fixture context: controller readback and separately authorized settings correction; no fake compatibility success job.
- Source guards become prose-only: pair them with executable mocked-shell failures, actual no-emulator compilation, exact-head instrumentation and trusted validator readback.

## 12. Concise implementer brief and Opus reviewer checklist

### Implementer brief

Start at the exact base in the stated worktree. Read this plan and root AGENTS. Keep `android.yml` canonical and signer-compatible; make one unprivileged supplemental workflow with one shared emulator. Use a runtime method annotation for exactly five supplemental app tests. Split `ci-device.sh`/new `ci-overlay.sh`, then coordinate overlay and unchanged fixture with independent exits/artifacts. Compile app and fixture Android tests before emulator boot. Add the four service-clock/admission regression tests and precise Messages-only rename without touching production behavior. Keep exact-four/exact-six/exact-thirteen evidence separate and per-APK provenance honest. Host guards RED first; shell/workflow changes GREEN; mandatory Codex Cloud preflight before one Opus review. No publication/signing/external updates without controller authorization. Return diff scope, real commands/results/counts, artifact paths if actually produced, and explicit remaining GitHub/phone checks.

### Reviewer checklist

- [ ] Diff starts from the stated base; production, dependencies, manifests, fixture behavior and protected signer implementation are unchanged.
- [ ] Only baseline/device remain in canonical; source workflow success still means every required canonical gate passed.
- [ ] Exactly five existing supplemental methods marked; safety/privacy classes and canonical demo/background remain canonical; new cooldown tests included.
- [ ] Canonical runner never calls supplemental pulls/manifest/fixture; signer still rejects failure and supplemental source runs.
- [ ] Exact four canonical images, manifest/context schema, success refresh and APK identity retained.
- [ ] Supplemental workflow red stays visible; both component outcomes retained; failure of one child does not suppress attempting/collecting the other.
- [ ] There are two total emulator-action sites, independent concurrency, least privilege, pinned actions, finite timeouts and clean exact-candidate checkouts.
- [ ] Fixture build/unit/lint and app/fixture instrumentation compilation precede boot; deterministic preparation failure has honest diagnostics and no runtime pass claim.
- [ ] Both evidence collections identify the exact APKs actually tested; independent supplemental build is not falsely equated with canonical bytes.
- [ ] New clock tests control both captured time sources, drive actual service event/install/admission/removal paths, use main-thread assertions and cancel handlers in cleanup.
- [ ] Misleading `foreignOrMissingForegroundSuppressesRouteAndHome` name removed; replacement does not claim HOME root validation; existing HOME detachment/veto evidence retained.
- [ ] No production data capture, routing fallback, consent weakening or privacy disclosure changes.
- [ ] Host/preflight results were actually executed/read; no regression output or final SHA invented. Remaining GitHub/phone gates explicitly unexecuted where applicable.
- [ ] Failure severity policy preserves blocker holds without treating fixture/polish red as automatically release-blocking; one classified unchanged-SHA retry maximum.
- [ ] Nonblocking polish suggestions logged instead of expanding/churning the green candidate.

## 13. Planner completion and handoff status

This file is the architecture plan, not an implementation or release record. Planning inspection established the exact base and the two coupling points, enumerated existing app tests, traced the cooldown clock/admission and misleading HOME test, and inspected signing/evidence constraints. The final planner validation is limited to plan structure, referenced existing/proposed paths, whitespace and unchanged base/worktree scope. No future command above should be reported as executed merely because it is written here.
