# WIL-196 Codex Cloud preflight — candidate `501d174` plus minimal compile repair

Date: 2026-09-17 (UTC)

## Candidate identity and repair binding

- The checkout started clean at exact `HEAD`
  `501d1743b05fc566a5d1ce3a67c8c4538cbaf73c`. The supplied checkout's local branch name was
  `work`; no fetch, checkout, rebase, SDK installation/update, dependency change, or toolchain
  change was performed.
- The first requested Gradle preflight reached `:app:compileDebugKotlin` and failed because the
  candidate's new `BringIntoViewRequester` use requires an explicit opt-in to Compose's
  experimental foundation API. `MainActivity.kt` now has only the required file-level opt-in.
- Final results below bind to exact candidate
  `501d1743b05fc566a5d1ce3a67c8c4538cbaf73c` plus that minimal repair and this superseding
  record. They must not be attributed to the unmodified candidate or an older preflight.

Changed files from the exact candidate:

- `app/src/main/java/com/chardy/doom/MainActivity.kt`
- `docs/WIL-196-CLOUD-PREFLIGHT.md`

No product behavior, privacy or authority boundary, permission, dependency, workflow, evidence
inventory, fixture, signing, routing, selector, detachment, or cooldown behavior changed.

## Host checks

All repository and WIL-196 plan host checks passed:

- `python3 -B scripts/test-entry-gate-host.py`: 91 tests passed.
- `python3 -B scripts/test-structural-lifecycle.py`: 49 tests passed.
- `python3 -B scripts/test-overlay-evidence.py`: 8 tests passed.
- `python3 -B scripts/test-fixture-evidence.py`: 21 tests passed.
- `python3 -B scripts/test_wil155_host.py`: 5 tests passed.
- `python3 -B scripts/test-session-timer-host.py`: 5 JUnit and 11 Python tests passed.
- `python3 -B scripts/test-removal-trace.py`: 8 tests passed.
- `python3 -B scripts/test-ci-isolation.py`: 6 tests passed.
- `python3 -B scripts/test-android-junit-validator.py`: 8 tests passed.
- `python3 -B scripts/test-integrated-ci-harness.py`: 17 tests passed.
- `python3 -B scripts/test-internal-signing.py`: 16 tests passed.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh`:
  passed.
- Python `xml.etree.ElementTree` parsing: all 6 XML files below `app/src` parsed.
- `git diff --check`: passed before Android work and after the final documentation update.

## Final Codex Cloud Android preflight

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The final post-repair invocation passed with exit code 0 (`BUILD SUCCESSFUL`). The signing suite
ran 16 tests, all passing. The 16 JUnit XML suite files under
`app/build/test-results/testDebugUnitTest/` reported:

- tests: **106**
- failures: **0**
- errors: **0**
- skipped: **0**

`app/build/reports/lint-results-debug.xml` reported **0 errors/fatal issues** and **13 warnings**.
The warnings are nonblocking findings; the build also printed the environment's nonblocking SDK
XML version compatibility warning.

Debug APK:

- path: `app/build/outputs/apk/debug/app-debug.apk`
- size: **8,926,079 bytes**
- SHA-256: `f81a51335b6fd14be46db31b59cb0b127bc0f6d119600446963807542fa26582`

## Android-test compilation

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
```

The post-repair invocation passed with exit code 0 (`BUILD SUCCESSFUL`), including Kotlin and
Java Android-test compilation, dexing, packaging, and `:app:assembleDebugAndroidTest`. Compiler
deprecation and one existing Java type-mismatch warning were nonfatal; there were no compile
errors.

Android-test APK:

- path: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- size: **1,178,188 bytes**
- SHA-256: `cfa9cda675d7a9fb73e507a2715f5adceaa5e3d4887652c1f8fd51b2f0b0d074`

## Evidence boundary

No emulator, `connectedAndroidTest`, device, adb, GitHub Actions, protected signing, release, or
real-Instagram/consenting-phone validation was run. These results establish host behavior,
SDK-35 compilation/lint, debug APK assembly, and Android-test compilation only. They do not
establish runtime, device, screenshot, route, physical-overlay-detachment, or real-Instagram
behavior, and no such claim is made.
