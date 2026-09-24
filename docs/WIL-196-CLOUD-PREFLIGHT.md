# WIL-196 final Codex Cloud preflight — exact candidate `4c9a901`

Date: 2026-09-24 (UTC)

This record covers the mandatory no-emulator preflight for exact source candidate
`4c9a90166340e6047a4fedc3e78bd0cc38a071b3`, after the independent-review lifecycle repair.
`git rev-parse HEAD` confirmed that exact commit before host or Android work, and the
supplied checkout was clean. The checkout exposed the local branch name `work`, rather than the
requested branch name `wil-196-sunset-debug-report`. No checkout, fetch, rebase, SDK
installation/update, dependency change, or toolchain change was performed.

No deterministic compile, API, unit-test, lint-error, or Android-test compile failure occurred,
so no source repair was needed. The only source-tree change produced by this preflight is this
exact-head record; production behavior and assertions are unchanged.

## Host checks

All host checks required by the repository instructions and WIL-196 plan passed before Android
work:

- `python3 -B scripts/test-entry-gate-host.py`: **98 JVM tests passed**.
- `python3 -B scripts/test-structural-lifecycle.py`: **51 tests passed**.
- `python3 -B scripts/test-overlay-evidence.py`: **8 tests passed**.
- `python3 -B scripts/test-fixture-evidence.py`: **21 tests passed**.
- `python3 -B scripts/test_wil155_host.py`: **5 tests passed**.
- `python3 -B scripts/test-session-timer-host.py`: **5 JVM tests and 11 Python tests passed**.
- `python3 -B scripts/test-removal-trace.py`: **8 tests passed**.
- `python3 -B scripts/test-ci-isolation.py`: **6 tests passed**.
- `python3 -B scripts/test-android-junit-validator.py`: **8 tests passed**.
- `python3 -B scripts/test-integrated-ci-harness.py`: **17 tests passed**.
- `python3 -B scripts/test-internal-signing.py`: **16 tests passed**.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/sign-internal-apk.sh`:
  passed.
- Python `xml.etree.ElementTree` parsing: all **6** XML files below `app/src` passed.
- `git diff --check`: passed on the clean exact candidate before Android work.

## Final Codex Cloud Android preflight

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The requested invocation passed with exit code 0 (`BUILD SUCCESSFUL`). The signing suite ran
**16 tests**, all passing. The **16** JUnit XML suite files under
`app/build/test-results/testDebugUnitTest/` reported:

- tests: **112**
- failures: **0**
- errors: **0**
- skipped: **0**

`app/build/reports/lint-results-debug.xml` reported **0 errors/fatal issues** and **13 warnings**.
The warnings are nonblocking. The build also printed the environment's nonblocking SDK XML
version compatibility warning and existing Kotlin/Java deprecation warnings.

Debug APK:

- path: `app/build/outputs/apk/debug/app-debug.apk`
- size: **8,926,347 bytes**
- SHA-256: `47dc44fffde416a7caa9bf7014d9bd7c1e19b2c7dbd63a4492d46772dcd7c345`

## Android-test compilation and assembly

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
```

The invocation passed with exit code 0 (`BUILD SUCCESSFUL`), including Kotlin and Java
Android-test compilation, dexing, packaging, and `:app:assembleDebugAndroidTest`. Existing
deprecation warnings and one existing Java type-mismatch warning were nonfatal; there were no
compile errors.

Android-test APK:

- path: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- size: **1,183,204 bytes**
- SHA-256: `bb3f0ee18b763d961e9a1183e9515a9e5bbc6c9aaed681e2e370c3d46772ed1b`

## Final evidence boundary

No emulator, `connectedAndroidTest`, device, adb, GitHub Actions, protected signing, release, or
real-Instagram/consenting-phone validation was run. Emulator/device evidence is still pending.
These results establish host behavior, SDK-35 compilation/lint, debug APK assembly, and
Android-test compilation/assembly for exact source candidate
`4c9a90166340e6047a4fedc3e78bd0cc38a071b3` only. They do not establish runtime, device,
screenshot, route, physical-overlay-detachment, or real-Instagram behavior, and no such claim is
made.
