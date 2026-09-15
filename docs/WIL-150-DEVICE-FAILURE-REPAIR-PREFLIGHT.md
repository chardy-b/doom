# WIL-150 device-failure repair preflight

## Candidate and bounded scope

This repair was prepared from exact baseline
`b03669e4f94e783680f43198118bfaa6808e25e6`. Before editing, `git rev-parse
HEAD` returned that SHA and the working tree was clean. The bounded changes add
scroll clearance and scroll-aware Doom-owned UI navigation, repair only the
identified instrumentation setup and expectations, remove the obsolete overlay
copy test, and make Doom's status-bar colors coherent with its dark golden-hour
theme. Fixture behavior, permissions, production reminder authority, overlay
detachment, Direct-tab routing, timer policy, and report/trace data handling were
not changed.

## Host checks

All repository-mandated host checks passed:

- `python3 -B scripts/test-entry-gate-host.py`: 85 tests passed.
- `python3 -B scripts/test-structural-lifecycle.py`: 42 tests passed.
- `python3 -B scripts/test-overlay-evidence.py`: 5 tests passed.
- `python3 -B scripts/test-fixture-evidence.py`: 21 tests passed.
- `python3 -B scripts/test_wil155_host.py`: 5 tests passed.
- `python3 -B scripts/test-internal-signing.py`: 14 tests passed.
- `python3 -B scripts/test-removal-trace.py`: 8 tests passed.
- `bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` passed.
- Python `xml.etree.ElementTree` parsed all 6 XML files under `app/src`.
- `git diff --check` passed.

## No-emulator Android preflight

The required no-emulator command plus Android-test compilation passed:

```text
python3 scripts/test-internal-signing.py &&
./gradlew --no-daemon --stacktrace
  :app:testDebugUnitTest
  :app:lintDebug
  :app:assembleDebug
  :app:compileDebugAndroidTestKotlin
```

The Gradle build completed successfully with 61 actionable tasks. The JVM suite
ran 99 tests with 0 failures, 0 errors, and 0 skipped. Lint reported 0 errors and
10 warnings. Android-test Kotlin compilation passed; its output contained only
pre-existing deprecation/type warnings and no compilation error.

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`, size 8,859,599
bytes, with SHA-256
`ba6dade13ca93f9ac5492445e71b4ec7a722d6a961bff3a1e2b97c87d9f98b8a`.

## Evidence limits

No emulator, `connectedAndroidTest`, physical device, Instagram, screenshot,
GitHub Actions, protected signing, installation, routing, or release operation
was run for this preflight. Therefore this document does **not** claim that the
canonical 109-test app suite, supplemental 5-test app suite, exact 14-image
manifest, status-bar rendering, or real Instagram behavior passes on API 35.
The previously supplied fixture result (6 of 6 device tests passing with valid
evidence) is not re-proved here. Canonical and supplemental GitHub evidence must
remain separate and must bind the eventual artifacts to the exact candidate
commit SHA.
