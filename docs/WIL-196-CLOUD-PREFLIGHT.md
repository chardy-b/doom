# WIL-196 Codex Cloud preflight — candidate `366b2d2`

This record **supersedes the historical preflight previously stored at this path**. The old
record covered commit `9beba5fa89e83f0ef0b483d75f043e40314c5467` and is not evidence for this
candidate.

Date: 2026-09-17 (UTC)

## Candidate identity and source-tree binding

- Requested candidate: `366b2d253818bb2c319ddfdbdac34430973e5ae9`.
- Starting `HEAD`: `366b2d253818bb2c319ddfdbdac34430973e5ae9` (exact match), with a clean
  worktree. The supplied checkout used the local branch name `work`; no fetch, checkout,
  rebase, dependency/toolchain update, or SDK installation/update was performed.
- All results below bind to that exact candidate source tree plus the minimal repair listed
  here. They must not be attributed to another commit or to the historical source tree.

## Minimal deterministic repair and final changed files

The first `:app:assembleDebugAndroidTest` invocation failed in
`:app:compileDebugAndroidTestKotlin`: `StructuralDiagnosticUiTest` directly called the
protected Android `Activity.onNewIntent(Intent)` callback at five sites. The test now delivers
those intents through the public instrumentation callback API. No production behavior,
product scope, privacy or authority boundary, permission, network/storage behavior,
dependency, workflow, evidence inventory, or signing behavior changed.

Final files changed from candidate `366b2d253818bb2c319ddfdbdac34430973e5ae9`:

- `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt`
- `docs/WIL-196-CLOUD-PREFLIGHT.md`

## Host checks

All plan and repository host checks passed:

- `python3 -B scripts/test-entry-gate-host.py`: 90 tests passed.
- `python3 -B scripts/test-structural-lifecycle.py`: 47 tests passed.
- `python3 -B scripts/test-overlay-evidence.py`: 8 tests passed.
- `python3 -B scripts/test-fixture-evidence.py`: 21 tests passed.
- `python3 -B scripts/test_wil155_host.py`: 5 tests passed.
- `python3 -B scripts/test-session-timer-host.py`: 5 JUnit and 11 Python tests passed.
- `python3 -B scripts/test-removal-trace.py`: 8 tests passed.
- `python3 -B scripts/test-ci-isolation.py`: 6 tests passed.
- `python3 -B scripts/test-android-junit-validator.py`: 8 tests passed.
- `python3 -B scripts/test-integrated-ci-harness.py`: 17 tests passed.
- `python3 -B scripts/test-internal-signing.py`: 16 tests passed.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh`: passed.
- Python `xml.etree.ElementTree` parse of every XML file below `app/src`: 6 files parsed.
- `git diff --check`: passed.

## Final Codex Cloud Android preflight

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Final post-repair invocation: **PASS**, exit code 0 (`BUILD SUCCESSFUL`). The signing
suite ran 16 tests, all passing. The 16 JUnit XML suite files under
`app/build/test-results/testDebugUnitTest/` reported:

- tests: **105**
- failures: **0**
- errors: **0**
- skipped: **0**

`app/build/reports/lint-results-debug.xml` reported **0 errors/fatal issues** and **13
warnings**. The warnings are nonblocking API-deprecation, dependency-availability, Compose,
drawing-allocation, static-field, clickable-view, RTL, and accessibility compatibility
findings. Gradle also emitted the environment's nonblocking SDK XML version compatibility
warning.

Debug APK:

- path: `app/build/outputs/apk/debug/app-debug.apk`
- size: **8,926,079 bytes**
- SHA-256: `c857e4790f891eff97e4bb489414c5afb5c52c349a3fe06d91ecdddef1ae5291`

## Android-test compilation

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
```

The initial invocation exposed the protected-callback compilation failure described above.
The post-repair invocation **passed**, exit code 0 (`BUILD SUCCESSFUL`), including
`:app:compileDebugAndroidTestKotlin`, Java compilation, dexing, packaging, and
`:app:assembleDebugAndroidTest`.

Android-test APK:

- path: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- size: **1,177,812 bytes**
- SHA-256: `ff58ba06fbec4172f7083ae93d00783a7feee6267cac47a5d15b325374e3bff0`

## Evidence boundary

No emulator, `connectedAndroidTest`, device, adb, GitHub Actions, APK signing, release, or
real-Instagram/consenting-phone validation was run. These results establish host behavior,
SDK-35 compilation/lint, debug APK assembly, and Android-test compilation only. They do not
establish runtime, device, screenshot, route, physical-overlay-detachment, or real-Instagram
behavior, and no such claim is made.
