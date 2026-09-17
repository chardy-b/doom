# WIL-196 Codex Cloud preflight

This is historical pre-repair evidence. It is bound to the source commit explicitly recorded
below (`9beba5fa89e83f0ef0b483d75f043e40314c5467`), not to the later candidate
`bcc2dae79be290938a9317d132408280c7baf968` or its uncommitted repair tree. It must not be read
as Android build/lint evidence for the repaired candidate.

Date: 2026-09-17 (UTC)

## Candidate identity and scope

- Requested candidate and exact starting `HEAD`: `9beba5fa89e83f0ef0b483d75f043e40314c5467`.
- Verified starting `HEAD`: `9beba5fa89e83f0ef0b483d75f043e40314c5467` (exact match).
- The supplied checkout's local branch name was `work`; validation and repairs were performed directly on the exact requested commit contents. No fetch, checkout, rebase, dependency/toolchain update, or SDK installation/update was performed.
- Starting worktree was clean.

The first mandated preflight exposed deterministic WIL-196 resource and compilation failures. The minimal repairs were:

1. Escaped the apostrophe in the expanded Android accessibility-service disclosure so AAPT can compile it.
2. Corrected Kotlin nullable generic inference for optional integer metadata and made the accepted unique-ID copy use a valid Kotlin/JVM string-copy expression.
3. Converted `captureContext` to a block body so its guarded early returns compile.
4. Matched the non-null `ComponentActivity.onNewIntent(Intent)` API signature.
5. Added narrowly scoped `NewApi` lint suppressions around reads that are already protected by the adapter's explicit runtime/requested-API ceiling.

No product scope, privacy boundary, action authority, permission, network/storage behavior, Gradle/dependency/workflow, evidence inventory, or signing behavior was changed.

## Final changed files

- `app/src/main/java/com/chardy/doom/AndroidStructuralMetadataReader.kt`
- `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt`
- `app/src/main/java/com/chardy/doom/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `docs/WIL-196-CLOUD-PREFLIGHT.md`

## Command results

### Mandated Codex Cloud preflight

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Final rerun: **PASS**, exit code 0 (`BUILD SUCCESSFUL`). The signing host suite ran **16 tests**, all passing.

JUnit XML under `app/build/test-results/testDebugUnitTest/` contained 16 suite files and these aggregate totals:

- tests: **99**
- failures: **0**
- errors: **0**
- skipped: **0**

The final lint XML at `app/build/reports/lint-results-debug.xml` contained:

- errors/fatal issues: **0**
- warnings: **13**

The warnings are nonblocking existing/API-deprecation, dependency-availability, Compose modifier, drawing-allocation, static-field, clickable-view, RTL, and accessibility XML compatibility warnings. The preflight also printed the environment's nonblocking SDK XML version compatibility warning.

Debug APK:

- path: `app/build/outputs/apk/debug/app-debug.apk`
- size: **9,141,758 bytes**
- SHA-256: `c3d3f778a803437667eb0f0410f8473c22ef7078d6bd98d3d567540d4087f8cf`

### Android-test compilation

```bash
./gradlew --no-daemon --stacktrace :app:assembleDebugAndroidTest
```

Result: **PASS**, exit code 0 (`BUILD SUCCESSFUL`). `:app:compileDebugAndroidTestKotlin`, Java compilation, dexing, packaging, and `:app:assembleDebugAndroidTest` completed. The Kotlin compiler daemon terminated once during startup and Gradle recovered within the same successful invocation; no rerun was needed.

Android-test APK:

- path: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- size: **1,169,248 bytes**
- SHA-256: `f2a8af5fd4050533be23220f530f5b5bdc389f29aff7b6be3967be6f65590e50`

### Additional checks

All XML files below `app/src` parsed successfully with Python `xml.etree.ElementTree`.

```bash
git diff --check
```

Result: **PASS**, exit code 0.

## Evidence boundary

No emulator, `connectedAndroidTest`, device, adb, GitHub Actions, signing, release, or real-Instagram/consenting-phone validation was run. These results establish host tests, SDK-35 compilation/lint, APK assembly, and Android-test compilation only; they are not runtime, device, screenshot, route, overlay-detachment, or real-Instagram evidence.
