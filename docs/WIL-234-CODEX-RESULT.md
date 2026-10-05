# WIL-234 final blocker repair result

## Scope and provenance

- Requested and observed starting head: `10d5a8e63f3d6cc7f27809d6c806b70370aed609`.
- Final blocker repair implementation commit: `2232a1af5358c7e1fb5d748bf9948d5fa6ce7339`.
- Exact repaired implementation tree: `75e8e2d95e75fd3e7739e633219e6b41ed0a3413`.
- Tree caveat: this result document is committed after the repaired implementation so that it can name the exact repaired SHA and tree. The final documentation commit therefore has a different SHA/tree but makes no production, workflow, dependency, permission, timing, geometry, scheduler, privacy, routing, or screenshot-manifest change.
- This was a host-only, no-emulator repair preflight. It does not claim emulator, device, Instagram, screenshot, protected-signing, or release evidence.

## Repair summary

- Corrected the reduced-motion native and Compose instrumentation expectations so exact completion is labelled `Breathe out`, matching the terminal collapsed exhale frame.
- Expanded the pure JVM completion assertion to require phase `OUT`, label `Breathe out`, bloom `0`, and fully filled segments at the exact duration. Production code was inspected and left unchanged because it already implements this contract.
- Native and Compose bloom renderers now visit the same circular geometry directly instead of allocating as many as 1,024 `BloomCell` objects each frame. The allocating geometry adapter remains only for bounded tests and compatibility helpers.
- Native segmented progress stores elapsed/duration scalars and calculates fills while drawing instead of copying a segment list every frame.
- The overlay owns a per-instance frame scheduler seam. Its production default remains `View.postOnAnimation`; teardown removes pending work, callbacks recheck disposal/attachment before drawing, and callbacks recheck both before reposting.
- New Android instrumentation behavior tests cover render-before-attach, attach/advance, detach with a pending callback, disposal racing a removed-but-already-dispatched callback, completion, disposal while a callback executes, and absence of continuing callbacks after teardown.
- Reduced-motion instrumentation assertions cover both native overlay state and the presentation model used directly by Compose preview: bloom remains static while labels and progress advance through inhale, exhale, and completion.
- Duration/timeline tests cover zero, negative, sub-breath, partial-breath, hostile anchors, completion clamping, and repeated renders near completion. Partial final segments become full at actual completion.

## Documented host gates

| Command | Exit code | Result |
| --- | ---: | --- |
| `python3 -B scripts/test-entry-gate-host.py` | 0 | PASS: 105 tests. |
| `python3 -B scripts/test-structural-lifecycle.py` | 0 | PASS: 47 tests. |
| `python3 -B scripts/test-overlay-evidence.py` | 0 | PASS: 8 tests. |
| `python3 -B scripts/test-fixture-evidence.py` | 0 | PASS: 21 tests. |
| `python3 -B scripts/test_wil155_host.py` | 0 | PASS: 5 tests. |
| `python3 -B scripts/test-internal-signing.py` | 0 | PASS: 16 tests. |
| `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` | 0 | PASS. |
| `git diff --check` | 0 | PASS. |

The six host suites ran **202 tests**, all passing. No XML file changed, so changed-XML parsing was not applicable.

## Android checks and preflight

The focused instrumentation compile passed after the documented preflight:

```bash
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
```

This compiled the repaired instrumentation assertion and finished `BUILD SUCCESSFUL in 35s` with 27 actionable tasks (8 executed, 19 up-to-date). It did not execute instrumentation.

The required preflight ran exactly:

```bash
python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Combined exit code: **0**.
- Signing tests: **16 tests**, 0 failures/errors.
- Gradle: **BUILD SUCCESSFUL in 4m 59s**; 53 actionable tasks (53 executed).
- JVM unit tests: **119 tests**, 0 failures/errors/skips, aggregated from 16 JUnit XML suite files.
- Lint: **0 errors, 13 warnings**. The warnings remain 7 dependency-update notices and one each for `UnusedAttribute`, `ModifierParameter`, the pre-existing timer-overlay `DrawAllocation`, `StaticFieldLeak`, `ClickableViewAccessibility`, and `RtlHardcoded`. No bloom renderer is reported for `DrawAllocation`.
- Assembly: PASS.

The build also emitted the existing Kotlin deprecation warnings, the SDK XML version warning, and the notice that `libandroidx.graphics.path.so` could not be stripped. None failed the preflight.

## APK and blockers

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **8,943,091 bytes**
- SHA-256: `6e0e5af73e7fcdf27a2452a1340b3c48243fefef895f2ebf877e758ce797b28d`
- This is a locally assembled debug APK, not protected-signing or device evidence.
- First causal error: **none**.
- Host-preflight blocker: **none**.
- Remaining evidence boundary: the instrumentation sources compiled but were not executed locally because repository policy reserves canonical API 35 instrumentation/device evidence for GitHub Actions. The repair therefore makes no runtime-device or screenshot claim.
