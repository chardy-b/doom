# WIL-150 Codex Cloud result

## Repaired candidate

The WIL-150 candidate based on exact starting commit `a5196e70c0b5c5c80e8abc2d0b044498bdef577a` was repaired after independent review. The repair restores the canonical four-image Doom-owned product evidence flow, fixes independent reminder-setting cleanup and supplemental capture lifecycle, restores Debug-only diagnostic disclosures, makes custom settings dialogs cancellable, and replaces per-frame progress hierarchy rebuilding with one stable drawing view. WIL-180 session timing and WIL-184 terminal-only cooldown, admission snapshots, and exact Messages-route lifecycle remain in place.

## Verified host checks

The required host gate passed in this working tree:

- Entry-gate Kotlin/JUnit suite: **85 tests**, 0 failures.
- Structural lifecycle suite: **40 tests**, 0 failures.
- Overlay-evidence suite: **5 tests**, 0 failures.
- Fixture-evidence suite: **21 tests**, 0 failures.
- WIL-155 host suite: **5 tests**, 0 failures.
- Internal-signing suite: **14 tests**, 0 failures.
- Session-timer host checks: exit 0.
- CI-isolation checks: exit 0.
- Shell syntax checks: exit 0.
- XML parsing: exit 0.
- `git diff --check`: exit 0.

## Android preflight

The exact no-emulator preflight completed successfully:

```text
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The signing host suite passed **14 tests**. Gradle reported **BUILD SUCCESSFUL**. The app JVM suite passed **99 tests**, with 0 failures, 0 errors, and 0 skipped. Lint reported **0 errors** and 10 nonblocking warnings. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, **8,859,599 bytes**, SHA-256 `c6b94491ce69f5a6f586f9957f27468aa80cc93ca8a5bd9fb713ba244523b35c`.

Android-test source compilation also completed successfully:

```text
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
```

Gradle reported **BUILD SUCCESSFUL**. Existing deprecation warnings and one existing Java type-mismatch warning were emitted; compilation had no errors.

## Evidence limits

No emulator, `connectedAndroidTest`, physical device, Instagram, screenshot capture, signed APK, GitHub Actions artifact, or release evidence was produced. Android-test compilation does not execute instrumentation or prove the canonical or supplemental screenshots. Canonical API 35 instrumentation, exact-head screenshot artifacts, APK/SHA binding, protected signing, and consented real-Instagram behavior remain external gates.
