# WIL-234 Codex evidence-only preflight result

## Scope and provenance

- Requested starting branch head: `0a81acc911a0ff0b0f4bd4c92094b6e69cbf9a0f`.
- Observed starting `git rev-parse HEAD`: `0a81acc911a0ff0b0f4bd4c92094b6e69cbf9a0f`.
- The initial `git status --short --branch` reported `## work` with no changed paths.
- This was a host-only, no-emulator preflight. It does not claim emulator, device, Instagram, screenshot, protected-signing, or release evidence.
- No production code, tests, workflows, dependencies, permissions, privacy behavior, routing, or manifests were changed. This result document is the only intended tracked change.

## Documented host gates

The documented host gates were run before the Android preflight. Results are factual command exit codes from this checkout.

| Command | Exit code | Result |
| --- | ---: | --- |
| `python3 -B scripts/test-entry-gate-host.py` | 0 | PASS: 103 tests, 0 failures. |
| `python3 -B scripts/test-structural-lifecycle.py` | 0 | PASS: 47 tests, 0 failures. |
| `python3 -B scripts/test-overlay-evidence.py` | 0 | PASS: 8 tests, 0 failures. |
| `python3 -B scripts/test-fixture-evidence.py` | 0 | PASS: 21 tests, 0 failures. |
| `python3 -B scripts/test_wil155_host.py` | 0 | PASS: 5 tests, 0 failures. |
| `python3 -B scripts/test-internal-signing.py` | 0 | PASS: 16 tests, 0 failures. |
| `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` | 0 | PASS. |
| `git diff --check` | 0 | PASS. |

The host gates ran 200 tests in total, all passing. No XML file was changed, so the instruction to parse changed XML files was not applicable.

## Required Android preflight

The required command was run exactly as requested:

```bash
python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Combined command exit code: **0**.
- `python3 scripts/test-internal-signing.py`: **16 tests**, 0 failures, 0 errors; `OK`.
- Gradle result: **BUILD SUCCESSFUL in 4m 52s**.
- Gradle work: **53 actionable tasks, 53 executed**.
- `:app:testDebugUnitTest`: **117 tests**, 0 failures, 0 errors, 0 skipped, aggregated from 16 JUnit XML suite files under `app/build/test-results/testDebugUnitTest/`.
- `:app:lintDebug`: **0 errors, 13 warnings**, from `app/build/reports/lint-results-debug.xml`.
- `:app:assembleDebug`: PASS.

### Lint warnings

The lint report contained these 13 warnings:

1. `UnusedAttribute`: `isAccessibilityTool` is used only on API level 31 and higher while the current minimum is 26.
2. `GradleDependency`: a newer `androidx.compose:compose-bom` is available (main dependency declaration).
3. `GradleDependency`: a newer `androidx.activity:activity-compose` is available.
4. `GradleDependency`: a newer `androidx.lifecycle:lifecycle-runtime-compose` is available.
5. `GradleDependency`: a newer `androidx.compose:compose-bom` is available (test dependency declaration).
6. `GradleDependency`: a newer `androidx.test.ext:junit` is available.
7. `GradleDependency`: a newer `androidx.test.espresso:espresso-core` is available.
8. `GradleDependency`: a newer `androidx.test.uiautomator:uiautomator` is available.
9. `ModifierParameter`: an optional `Modifier` parameter should default to `Modifier`.
10. `DrawAllocation`: avoid object allocation during draw/layout operations.
11. `StaticFieldLeak`: a static reference to `DoomAccessibilityService`, whose `overlay` field points to a `View`, may leak an Android context.
12. `ClickableViewAccessibility`: a `FrameLayout` with an `OnTouchListener` does not override `performClick`.
13. `RtlHardcoded`: use `Gravity.START` rather than `Gravity.LEFT` for right-to-left behavior.

The build output also emitted 11 Kotlin compiler deprecation warnings: one `isHeading`, six `recycle()`, and four system-window-inset usages. Gradle emitted an SDK-processing warning that its tooling understands SDK XML through version 3 but encountered version 4, and reported that `libandroidx.graphics.path.so` could not be stripped and was packaged unchanged. None failed the requested preflight.

## APK result

- Path: `app/build/outputs/apk/debug/app-debug.apk`.
- Size: **8,926,707 bytes**.
- SHA-256: `4e4bb6d0326bb5feaf0730bd1aa0cbb5a3f3a0f4f35e346d243a5398beb7cb16`.

This is a locally assembled debug APK. It is not protected-signing or device evidence.

## Blocker and conclusion

- First causal error: **none**; every requested/documented gate exited 0.
- Blocker: **none in this host preflight**.
- Conclusion: the exact requested starting head passed all documented host gates and the required no-emulator Android preflight. This conclusion is limited to the commands and artifacts recorded above.
