# WIL-234 repair preflight result

## Scope and provenance

- Requested and observed starting head: `2eb4ed23a214ce70d645a8e94fb7a9b8ae6a4452`.
- Repaired implementation commit: `b6ff53b7fb9192b57d59bbe86e8183d70dc3e5b4`.
- Repaired implementation tree: `ea494af97009b4ba286d22c21f25704d75bb44ef`.
- Tree caveat: this result document is committed after the repaired implementation so that it can name the exact repaired SHA and tree. The final documentation commit therefore has a different SHA/tree but makes no production, test, workflow, dependency, permission, routing, privacy, or evidence-contract change.
- This was a host-only, no-emulator repair preflight. It does not claim emulator, device, Instagram, screenshot, protected-signing, or release evidence.

## Repair summary

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

The focused compile/test command passed before the documented gates:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

This compiled the new instrumentation tests and ran the JVM suite. It finished `BUILD SUCCESSFUL in 3m 6s`.

The required preflight then ran exactly:

```bash
python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Combined exit code: **0**.
- Signing tests: **16 tests**, 0 failures/errors.
- Gradle: **BUILD SUCCESSFUL in 2m 40s**; 53 actionable tasks (28 executed, 25 up-to-date).
- JVM unit tests: **119 tests**, 0 failures/errors/skips, aggregated from 16 JUnit XML suite files.
- Lint: **0 errors, 13 warnings**. The warnings remain 7 dependency-update notices and one each for `UnusedAttribute`, `ModifierParameter`, the pre-existing timer-overlay `DrawAllocation`, `StaticFieldLeak`, `ClickableViewAccessibility`, and `RtlHardcoded`. No bloom renderer is reported for `DrawAllocation`.
- Assembly: PASS.

The build also emitted the existing Kotlin deprecation warnings, the SDK XML version warning, and the notice that `libandroidx.graphics.path.so` could not be stripped. None failed the preflight.

## APK and blockers

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **8,943,091 bytes**
- SHA-256: `90187367ce06b5a4a13c601f231f65dfbb32fd9ad3f09fc55ccd86771695c19e`
- This is a locally assembled debug APK, not protected-signing or device evidence.
- First causal error: **none**.
- Host-preflight blocker: **none**.
- Remaining evidence boundary: the instrumentation sources compiled but were not executed locally because repository policy reserves canonical API 35 instrumentation/device evidence for GitHub Actions. The repair therefore makes no runtime-device or screenshot claim.
