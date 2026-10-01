# WIL-222 Codex Cloud result

Date: 2026-10-01 UTC
Base commit: `76eeecb169cd002a1e8a032910d807e76741cb23`

## Scope and evidence boundary

This is host compilation and test evidence for the WIL-222 code candidate. No emulator,
device, live Instagram, GitHub Actions, protected signing, release, or phone evidence was
run or is claimed. The generated APKs are ordinary local debug artifacts.

## Strict-TDD red

The focused pure-policy regression was added before production code. Running
`python3 -B scripts/test-entry-gate-host.py` failed as expected because
`InstagramEntryGate.observeInstagram` did not accept a surface and therefore could not
bypass the reported messaging candidate. The first causal compiler error was:

```text
InstagramEntryGateTest.kt:39:55: error: too many arguments for ... observeInstagram
```

## Host checks

The required host-check chain exited 0:

```bash
python3 -B scripts/test-entry-gate-host.py && \
python3 -B scripts/test-structural-lifecycle.py && \
python3 -B scripts/test-overlay-evidence.py && \
python3 -B scripts/test-fixture-evidence.py && \
python3 -B scripts/test_wil155_host.py && \
python3 -B scripts/test-internal-signing.py && \
bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh
```

Results: 101 pure Kotlin/JUnit tests, 46 structural lifecycle guards, 8 overlay-evidence
tests, 21 fixture-evidence tests, 5 WIL-155 guards, and 16 internal-signing tests passed.
The changed `strings.xml` parsed successfully with Python `xml.etree.ElementTree`, and
`git diff --check` exited 0.

## Android preflight and instrumentation compilation

The requested command exited 0:

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
```

Gradle reported `BUILD SUCCESSFUL` with 80 actionable tasks. The signing source tests
passed 16/16. JVM unit tests passed 115/115 with 0 failures, 0 errors, and 0 skipped.
Lint completed with 0 errors. Android instrumentation sources compiled and the test APK
assembled; instrumentation was not executed.

Artifacts:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 8,926,707 bytes | `2e6bce406180de4013914fe11c49c6046c016847f24b40abe2c001a5e4a54b49` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,180,144 bytes | `db0d0a54e8f19793e5240d2b24eaf46a88e4a481d048f59a136ab1890099a264` |

The build emitted existing Java deprecation warnings and an Android SDK XML tooling
version warning; neither produced a lint error or build failure.
