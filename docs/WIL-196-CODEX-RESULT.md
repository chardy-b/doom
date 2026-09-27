# WIL-196 corrective Codex result

Implementation base: `06be9781f7cd7937e100598fa3f7912dd284089b` on the WIL-196 corrective working tree.

## Corrective implementation

The reminder's former Activity-opening Debug action is now `CAPTURE_DEBUG`. After confirmed physical overlay detachment, the service validates the detached episode, both consents, connection, reminder setting, generation, ticket, and `GATING` state; obtains a fresh tap-time context; collects an immutable candidate while the collector owns every traversal node; then reacquires and independently recycles a separate root whose attribution must be exactly Instagram. It revalidates authority, stops the timer, requires `entryGate.bypass(captureTicket)` to succeed, revalidates authority in `BYPASSED`, and invokes the single capture clipboard sink once before consuming the detached token.

The sink clears old report/reveal/copy state before writing. A successful sensitive clipboard write commits only the fresh candidate with `copied=true` and `revealed=false`; false, exception, or a missing clipboard service leaves all three cleared. Capture performs no Activity launch, navigation, retry, overlay restoration, completion, route, or cooldown action. The former intent, request-anchor, and departure-latch seams are removed. Ordinary manual Debug navigation, reveal, and reviewed-copy controls remain.

Collector lifecycle instrumentation has distinct production-no-op read/recycle hooks; traversal-root recycling is separate from watchdog-root recycling. Capture-event provenance is process-only and is cleared by episode safety reset, interrupt, unbind, and destroy paths.

## Exact final-tree host checks

- `test-entry-gate-host.py`: **98 tests passed**.
- `test-structural-lifecycle.py`: **46 tests passed**.
- `test-overlay-evidence.py`: **8 tests passed**.
- `test-fixture-evidence.py`: **21 tests passed**.
- `test_wil155_host.py`: **5 tests passed**.
- `test-internal-signing.py`: **16 tests passed**.
- `test-removal-trace.py`: **8 tests passed**.
- `test-session-timer-host.py`: **5 JVM tests + 11 Python tests passed**.
- `test-ci-isolation.py`: **6 tests passed**.
- `test-android-junit-validator.py`: **8 tests passed**.
- `test-integrated-ci-harness.py`: **17 tests passed**.
- Shell syntax checks passed for `scripts/ci-device.sh`, `scripts/ci-fixture.sh`, and `scripts/sign-internal-apk.sh`.
- All **13 source XML files** parsed successfully.
- `git diff --check` passed.

## Final staged-tree no-emulator Android compatibility preflight

The corrective Cloud parent candidate passed the repository's pinned Java 17 / SDK 35 preflight before the final explicit `UNAVAILABLE` result guard and regression test were added. To compile the exact final staged source on this host, Gradle used the cached Android SDK 35 with a **temporary, fully restored build-script substitution** from Java/Kotlin toolchain target 17 to the installed JDK/target 21. No tracked build configuration changed.

Command-equivalent tasks:

```text
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Result: exit 0 (`BUILD SUCCESSFUL`). JVM unit results contain **112 tests**, **0 failures**, **0 errors**, and **0 skipped**. Lint contains **0 errors** and **13 warnings**. The Android-test sources, including the final unavailable-copy regression, compiled and the Android-test APK assembled.

Artifacts built from the exact final staged source under that local compatibility toolchain:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 8,926,367 bytes | `86147b4c22ae7dd926e2347206899237c1097fb9d4a290ae3c13f0c73658f3b6` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,178,352 bytes | `aa7c0ca980f8008b034bf216b0cd2ded1430914731c1b34b47b3eafa8ea69f97` |

The temporary toolchain substitution was restored and `git diff` confirms no build-script change remains. A fresh pinned Java 17 exact-tree preflight is still required before release claims.

## Evidence boundary

No emulator, connected instrumentation, device, Instagram, screenshot, route, physical-detachment timing, signing, release, or protected-CI evidence was run or claimed. The preflight only compiled the Android-test APK; it did not execute instrumentation. Historical Activity-navigation evidence is obsolete for this capture-only transaction and is not current WIL-196 evidence.
