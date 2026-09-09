# WIL-149 sanitized structural report validation

Working-tree implementation based on clean HEAD `c02dc72042b0009d04f0533ec70a102aa9905fa2`. Changes are uncommitted. No Gradle, Android, adb, credential access, network operation, commit, push or Linear update was performed. One writer; no delegated agents.

## Exact changed files

| File | Change |
| --- | --- |
| `app/src/main/java/com/chardy/doom/SanitizedStructuralReport.kt` | Exact ASCII Instagram resource-name sanitizer with copied strings, closed safe class map, and bounded deterministic aggregate report (64 tokens / 8,192 bytes). |
| `app/src/main/java/com/chardy/doom/StructuralFingerprint.kt` | Deleted superseded digest, similarity and labeled-baseline implementation. |
| `app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt` | Collect only allowed metadata; enforce traversal limits, skip foreign/unattributed children and mark omissions; retain lifecycle/root invalidation and recycling. |
| `app/src/main/java/com/chardy/doom/Observation.kt` | New consent key, one process-local report, reveal/copy state, guarded explicit clipboard write, lifecycle clearing. |
| `app/src/main/java/com/chardy/doom/MainActivity.kt` | Replace old diagnostic controls with disclosure, local reveal, reviewed copy and clear. |
| `app/src/main/res/values/strings.xml` | Replace accessibility-service disclosure. |
| `app/src/test/java/com/chardy/doom/SanitizedStructuralReportTest.kt` | New sanitizer, hostile-input, aggregation, immutability and boundary tests. |
| `app/src/test/java/com/chardy/doom/StructuralFingerprintTest.kt` | Delete tests for the removed feature. |
| `app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt` | Replace old-feature tests with report disclosure/gating/clipboard/lifecycle tests; invoke protected connection callback through test-only reflection. |
| `scripts/test-structural-lifecycle.py` | Preserve lifecycle guards for the replacement; add privacy, copy, traversal, consent, process-start, manifest and protected-callback guards. |
| `README.md` | Replace superseded diagnostic description and usage; document static resource-name discovery, exact grammar and report limits. |
| `docs/WIL-149-VALIDATION.md` | This check record. |

## TDD and executed host checks

Tests were authored before the production replacement. The first host run reported **5 expected failures out of 8 checks**, covering missing report state/copy, old consent/implementation, missing metadata fields and the protected callback call. After implementation and added coverage (including a second failing-first check for foreign child isolation), the resource-discovery repair added tests before changing production code. Its first direct host Kotlin/JUnit run compiled successfully and reported **5 expected failures out of 14 tests**: unknown-name admission, 45 distinct IDs, increased character budget, 8,192-byte limit and 64-token limit. A new disclosure source guard separately failed (1 of 14). After the repair and additional mutable-input/ASCII grammar coverage, the checks below pass.

The resource-name fixtures are synthetic, including a name absent from the previous list (`clips_viewer_video_layout_v2`). No installed Instagram version or actual app resource table was inspected. Valid static names no longer require prior source knowledge. Names resembling generated suffixes remain admissible when they satisfy the grammar; this is metadata sanitization, not content classification. Reports expose static Instagram resource names, never UI text/content/account values.

Boundary coverage includes 63/64/65 combined resource/class tokens below the byte cap; exact 8,191/8,192-byte reports and an 8,193-byte candidate with a whole row omitted; 45 distinct previously unknown IDs; copied immutable output from mutable metadata; ASCII grammar and Unicode/control/delimiter rejection; deterministic ordering/counts; an exact output field set and collector getter guard; and the existing 128-node/depth-8/child-count-16 limits.

| Executed check | Result |
| --- | --- |
| Direct host `K2JVMCompiler` + `org.junit.runner.JUnitCore` | PASS, 16 pure JVM tests; report source and test compiled together. No Gradle invocation or Android classes. |
| `python3 scripts/test-structural-lifecycle.py` | PASS, 14 tests. Source guards, not Android execution. |
| `python3 scripts/test-fixture-evidence.py` | PASS, 21 tests. Existing suite unchanged. |
| `bash -n scripts/ci-device.sh scripts/ci-fixture.sh` | PASS, both scripts. Syntax only; scripts were not executed. |
| Python `xml.etree.ElementTree.parse` over `**/src/**/*.xml` | PASS, all 13 XML files. |
| Python `ast.parse` over `scripts/*.py` | PASS, all 4 Python files. |
| `git diff --check` | PASS. |
| `git diff --exit-code c02dc72042b0009d04f0533ec70a102aa9905fa2 -- <contract paths>` | PASS, all 19 protected paths below unchanged. |

Protected paths checked: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/accessibility_service_config.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `fixtureapp`, `fixturegate`, `docs/FIXTURE.md`, `scripts/ci-device.sh`, `scripts/ci-fixture.sh`, `scripts/evidence-manifest.py`, `scripts/fixture-readiness.py`, `scripts/test-fixture-evidence.py`, `app/src/androidTest/java/com/chardy/doom/DoomUiTest.kt`, `app/src/test/java/com/chardy/doom/GatePolicyTest.kt`, `app/src/main/java/com/chardy/doom/DemoGate.kt`, `.github`.

The direct JVM check used existing cached Kotlin 1.9.23 compiler/stdlib/reflect/script-runtime jars, Trove, JetBrains annotations, JUnit 4.13.2 and Hamcrest 1.3, loaded with `java -cp`. Although stored under a cached Gradle distribution directory, only the Kotlin compiler main class and JUnit runner were invoked. Compiler arguments were `-no-stdlib -no-reflect -classpath <jars> -d <temporary-directory>` followed by the report and test paths. JUnit ran `com.chardy.doom.SanitizedStructuralReportTest`; compiled output was removed afterward. No dependencies were downloaded.

## Pending CI and real-device validation

- The pure report Kotlin/JVM tests passed on the host as described above. Android compilation, lint, APK assembly and all instrumentation remain CI-only; no Android build or runtime pass is claimed.
- CI must run `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` and `:app:connectedDebugAndroidTest`. The existing fixture jobs and exact-four demo screenshot/checksum/source-SHA contracts remain intact.
- The previous protected-callback direct call is removed. Test-only reflection exercises the real callback without changing service visibility; its Android runtime behavior still needs CI.
- Clipboard tests copy only synthetic reports and overwrite their clipboard afterward. Supported system preview suppression, clipboard behavior and service callback timing require Android verification. Clearing Doom cannot recall clipboard or uploaded copies.
- Real process death is not simulated by Activity recreation. Source guards check empty process defaults and consent-only persistence; real process-kill/service-restart behavior remains device validation.
- Resource admission uses the exact package prefix and a 1–64-character ASCII name (`[a-z][a-z0-9_]{0,63}`), with a separate 96-character raw cap. It reconstructs a new string from validated characters; class admission retains the existing closed safe map. Unknown grammar-valid resource names are admitted; invalid resources and unknown classes become `-`. The 64 combined-token / 8,192-byte caps allow useful discovery while still bounding the report; long names or many distinct tuples can exhaust the byte cap before all 45 IDs appear. Truncation identifies bounded omissions; `-` identifies unavailable/rejected metadata. Neither complete traversal nor retained tokens establish screen identity, privacy of external copies, DM routing or protection.
