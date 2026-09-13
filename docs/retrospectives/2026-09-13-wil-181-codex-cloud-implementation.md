# WIL-181 Codex Cloud implementation report — 2026-09-13

## Candidate and scope

Implementation started from plan-only commit `a9a9047` (whose documented implementation base is `a6f49fc`). The candidate isolates supplemental UI and fixture execution without changing production source, manifests, permissions, dependencies, fixture behavior, or protected signing implementation.

## Executed host validation

All section 9.1 checks were executed from `/workspace/doom` and exited 0:

- `python3 -B scripts/test-ci-isolation.py` — 5 tests passed, including mocked success, overlay-only failure, fixture-only failure, and both-fail coordinator cases with exit precedence.
- `python3 -B scripts/test-entry-gate-host.py` — 82 Kotlin/JUnit tests passed.
- `python3 -B scripts/test-structural-lifecycle.py` — 37 tests passed.
- `python3 -B scripts/test-overlay-evidence.py` — 5 tests passed.
- `python3 -B scripts/test-fixture-evidence.py` — 21 tests passed.
- `python3 -B scripts/test_wil155_host.py` — 5 tests passed.
- `python3 -B scripts/test-removal-trace.py` — exited 0.
- `python3 -B scripts/test-internal-signing.py` — 14 tests passed.
- `python3 -B scripts/test-android-junit-validator.py` — 4 synthetic-XML validator tests passed. Synthetic XML is not device evidence.
- `bash -n scripts/ci-device.sh scripts/ci-overlay.sh scripts/ci-supplemental.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh` — exited 0.
- `git diff --check` — exited 0 after final whitespace correction.

No XML source file changed.

## Executed no-emulator Android preflight

The configured Java 17 / Android SDK 35 environment was used as-is. No SDK or dependency was installed or updated.

The required first command exited 0:

```text
python3 scripts/test-internal-signing.py &&
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The final rerun and generated XML show 14 signing tests and 86 app unit tests passing with zero failures/errors/skips, 11 app lint findings and zero errors, and successful assembly. `app/build/outputs/apk/debug/app-debug.apk` is 8,826,639 bytes with SHA-256 `355b69ba936da2c3236e64747e3b7a426b91b6b0aa6f0d06e91ae742485d4635`.

The required deterministic compilation command exited 0:

```text
./gradlew --no-daemon --stacktrace \
  :app:assembleDebugAndroidTest \
  :fixtureapp:assembleDebug :fixtureapp:lintDebug \
  :fixturegate:assembleDebug :fixturegate:testDebugUnitTest \
  :fixturegate:lintDebug :fixturegate:assembleDebugAndroidTest
```

Results: 6 fixture unit tests passed with zero failures/errors/skips; fixture-app lint had 2 findings and zero errors; fixture-gate lint had 5 findings and zero errors; all Android-test compilation succeeded. Sizes: app Android-test APK 1,140,092 bytes; fixture-app APK 818,016 bytes; fixture-gate APK 832,223 bytes; fixture-gate Android-test APK 961,541 bytes.

The SDK tooling emitted its existing XML-version compatibility warning. Kotlin emitted existing deprecation warnings and one test-only Java type-mismatch warning; none was a lint error or build failure.

## Blockers and deliberately unexecuted evidence

No deterministic host, unit, lint, assembly, or Android-test compilation blocker remains. Per repository policy and task scope, no emulator, `connectedAndroidTest`, adb/device collection, GitHub Actions run, artifact readback, signing, release, merge, external-system update, or consented Instagram phone check was executed. This report therefore claims no canonical/supplemental device evidence, exact-head Actions result, signer eligibility, signed APK, or real-Instagram behavior.

## Independent re-review repair

- **B-1 fixed:** `revokedAdmissionDoesNotArmCooldown` now models admission revocation by making the wired service disconnected inside the synthetic installer, avoiding nested consent cleanup. It asserts the fake overlay is detached and cooldown remains inactive, restores connection before a fresh event-path re-entry, and uses cleanup that resets the static service reference even if teardown throws.
- **M-a fixed:** canonical Android JUnit validation enumerates every `@Test` method under the app Android-test package, supports the checked annotation ordering, and requires the exact source inventory minus the five supplemental identities. Supplemental validation requires exactly those five. Synthetic tests cover incomplete partitions, extras, statuses, duplicates, nested report directories/XML roots, and annotation ordering.
- **Deferred nonblocking beta follow-ups:** Opus M-b (post-Gradle clean-check timing and `.kotlin/` ignore coverage), M-c (read-only emulator settings diagnostics before fixture execution), and LOW validator/coordinator/documentation/polish gaps remain intentionally unfixed. They do not change the B-1/M-a release-blocker result unless later evidence reclassifies one as a safety, privacy, core-action, provenance, or build blocker.
