# WIL-150 Codex Cloud result

## Integrated candidate

The WIL-150 Cloud implementation was reconciled onto `origin/main` at `7d7c4fd`, preserving WIL-180’s session timer and WIL-184’s terminal-only cooldown and exact Messages-route lifecycle.

The integrated candidate includes Home/Debug navigation, persisted reminder settings, accessibility-service status, explicit preview, shared 4-second inhale / 6-second exhale rendering, configurable duration and cooldown snapshots, the sunset pixel bloom, and a production overlay without diagnostic text or controls.

## Verified host checks

The complete repository host gate passed after integration:

- Entry-gate Kotlin/JUnit suite: **85 tests**, 0 failures.
- Structural lifecycle suite: **38 tests**, 0 failures.
- Overlay-evidence suite: **5 tests**, 0 failures.
- Fixture-evidence suite: **21 tests**, 0 failures.
- WIL-155 host suite: **5 tests**, 0 failures.
- Internal-signing suite: **14 tests**, 0 failures.
- Session-timer Kotlin/JUnit suite: **5 tests**, 0 failures.
- Session-timer source-contract suite: **9 tests**, 0 failures.
- CI-isolation suite: **6 tests**, 0 failures.
- Shell syntax checks: exit 0.
- Source XML parsing: **13 files**, 0 errors.
- `git diff --check`: exit 0.

## Android preflight status

Earlier Codex Cloud runs proved the unintegrated WIL-150 implementation could pass JVM tests, lint, debug assembly, and Android-test compilation. Those results are **not** exact evidence for this integrated candidate.

The integrated candidate must run the repository-required Codex Cloud preflight from its published exact branch before independent review:

```text
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
```

Record final unit-test counts, lint errors, APK path, size, and SHA-256 only after that exact run completes.

## Evidence limits

No emulator, `connectedAndroidTest`, physical device, Instagram, screenshot capture, signed APK, GitHub Actions artifact, or release evidence has been produced for this integrated candidate. Those remain owned by canonical exact-head GitHub Actions and consented-phone evidence gates.
