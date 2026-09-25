# WIL-196 mandatory no-emulator compile gate — exact candidate `9f531cb`

Date: 2026-09-25 (UTC)

This record covers the mandatory no-emulator compile gate after the evidence-only API-35
repairs. The evidence is bound to source candidate
`9f531cb91e469562e2140b181b50b2f539ec86ce`. Before any check, `git rev-parse HEAD`
returned that exact SHA and `git status --short` returned no entries, confirming a clean
checkout on the locally supplied `work` branch.

No checkout, fetch, rebase, emulator, device, adb, SDK installation/update, dependency change,
or toolchain change was performed. No deterministic compile or lint failure occurred, so no
source or test repair was made. This evidence document is the only resulting tracked change.

## Required host checks

The host checks ran before Gradle and all passed:

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

## Pinned SDK-35 application preflight

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The exact invocation passed with exit code 0 (`BUILD SUCCESSFUL in 6m`). The signing suite ran
**16 tests**, all passing. The **16** JUnit XML suite files under
`app/build/test-results/testDebugUnitTest/` reported:

- tests: **112**
- failures: **0**
- errors: **0**
- skipped: **0**

`app/build/reports/lint-results-debug.xml` reported **0 errors/fatal issues** and **13 warnings**.
The build emitted one nonblocking SDK XML-version compatibility warning and **10** existing
Kotlin/Java deprecation-warning lines. It also reported that `libandroidx.graphics.path.so` could
not be stripped and was packaged unchanged. None was a compile or lint error.

Debug APK:

- path: `app/build/outputs/apk/debug/app-debug.apk`
- size: **8,926,347 bytes**
- SHA-256: `5644943ef4f9875be608a301a9cf4a830bf96d40ddaf4603afa02a8a0f9d3e18`

## Android-test compilation and assembly

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
```

The exact invocation passed with exit code 0 (`BUILD SUCCESSFUL in 1m 9s`). It completed Kotlin
and Java Android-test compilation, dexing, packaging, and `:app:assembleDebugAndroidTest`. The
build emitted one nonblocking SDK XML-version compatibility warning and **43** existing Kotlin/
Java warning lines: deprecation warnings plus one Java type-mismatch warning. There were no
compile errors.

Android-test APK:

- path: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- size: **1,183,208 bytes**
- SHA-256: `07681202ebd41d57963333ceacea8d539c9c8554aeb79fd54ba209f2752f59d0`

## Explicit no-device boundary

No emulator, device, adb, `connectedAndroidTest`, GitHub Actions, protected signing, release, or
real-Instagram/consenting-phone validation was run. These results establish host behavior,
pinned SDK-35 compilation and lint, debug APK assembly, and Android-test compilation/assembly for
exact source candidate `9f531cb91e469562e2140b181b50b2f539ec86ce` only. They do not establish
runtime, device, screenshot, route, physical-overlay-detachment, or real-Instagram behavior.
