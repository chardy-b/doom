# WIL-150 second-repair preflight

> Historical working-tree record that began at `fd235a4e1babb47f5014e9ccd4d4fb1f33fdd3f0`; later exact-head preflight supersedes these artifact hashes.

Date: 2026-09-15 (UTC)

## Candidate scope

Work began only after `git rev-parse HEAD` returned the requested exact starting commit
`fd235a4e1babb47f5014e9ccd4d4fb1f33fdd3f0`. This document records local host and
Codex Cloud no-emulator checks for the resulting working tree. It is not canonical
device evidence and is not bound to a GitHub Actions candidate artifact.

## Host checks

The required host sequence passed:

```text
python3 -B scripts/test-entry-gate-host.py                 PASS (85 tests)
python3 -B scripts/test-structural-lifecycle.py            PASS (42 tests)
python3 -B scripts/test-overlay-evidence.py                PASS (5 tests)
python3 -B scripts/test-fixture-evidence.py                PASS (21 tests)
python3 -B scripts/test_wil155_host.py                     PASS (5 tests)
python3 -B scripts/test-internal-signing.py                PASS (14 tests)
python3 -B scripts/test-session-timer-host.py              PASS (5 JVM tests, 9 source-contract tests)
python3 -B scripts/test-ci-isolation.py                    PASS (6 tests)
bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh  PASS
git diff --check                                          PASS
```

No XML file changed in this repair, so there was no changed XML document to parse.

## Android no-emulator preflight

The exact requested preflight command completed with exit code 0:

```text
python3 scripts/test-internal-signing.py && ./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The signing host suite passed 14 tests. The Gradle build passed 99 unit tests across
14 result files with zero failures, zero errors, and zero skips. Lint reported zero
errors (10 non-error findings). The generated APK was
`app/build/outputs/apk/debug/app-debug.apk`, 8,859,599 bytes, with SHA-256
`cd7ceef432f42b65a01dcb42be84586b63fd1bb9134a08e04f220c39a7d7122a`.

The separately requested Android-test compilation also completed with exit code 0:

```text
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
```

It compiled the instrumentation sources but did not execute them.

## Evidence limits

No emulator, `connectedAndroidTest`, physical device, Instagram, screenshot capture,
GitHub Actions evidence artifact, protected signing, release, or route observation was
performed. In particular, compilation does not prove physical overlay detachment,
runtime accessibility behavior, canonical screenshot completeness, the exact Messages
route on Instagram, or WIL-180 timer behavior on a device. Those remain subject to the
repository's canonical API 35, exact-head artifact, protected-signing, and consented
real-phone gates.
