# AGENTS.md

## Scope

These instructions apply to the entire Doom repository. A more-specific `AGENTS.md` overrides them within its directory.

Doom is a beta Android accessibility diagnostic. Privacy, consent, physical overlay detachment, exact-head evidence, and signing provenance are release controls.

## Read first

- `README.md` — product scope, privacy boundary, current limitations, and release status.
- `docs/WIL-149-VALIDATION.md` — implementation and evidence contract.
- `docs/FIXTURE.md` — independent cross-app fixture scope and CI rules.

Check the active Linear ticket for acceptance criteria and dependencies. Do not infer current scope from old branches or historical evidence.

## Development rules

- Make the smallest change that satisfies the active ticket.
- Do not add `INTERNET`, storage, notification-reader, contacts, SMS, account, gesture, key, or unrelated accessibility capabilities.
- Do not read, log, persist, transmit, or test with Instagram text, usernames, messages, captions, notification content, content descriptions, raw trees, coordinates, or screenshots of private content.
- Keep Instagram intervention separately consented and default off.
- Never release Home, Messages, completion, or bypass actions before physical overlay detachment is confirmed.
- Preserve stale-ticket and revoked-consent rejection.
- The Messages exception may target only `com.instagram.android:id/direct_tab` after an explicit current-overlay tap and all current authority checks. Do not add text search, traversal fallback, coordinates, gestures, retries, or another target.
- Do not weaken protected signing, exact-SHA checks, evidence manifests, or APK payload comparison.
- Do not upgrade Gradle, AGP, Kotlin, SDK, Actions, dependencies, or permissions unless the ticket explicitly requires it.

## Cheapest checks first

Run relevant host checks before Android work:

```bash
python3 -B scripts/test-entry-gate-host.py
python3 -B scripts/test-structural-lifecycle.py
python3 -B scripts/test-overlay-evidence.py
python3 -B scripts/test-fixture-evidence.py
python3 -B scripts/test_wil155_host.py
python3 -B scripts/test-internal-signing.py
bash -n scripts/ci-device.sh scripts/ci-fixture.sh scripts/sign-internal-apk.sh
git diff --check
```

Also parse changed XML files before review. Report the first causal error instead of dumping full logs.

## Codex Cloud Android preflight

The `chardy-b/doom` Codex Cloud environment has pinned Java 17 and Android SDK 35. Do not install or update the SDK during the agent phase.

After Android source, resource, test, or build changes, run this before independent review:

```bash
python3 scripts/test-internal-signing.py && \
./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

A valid preflight has exit code 0. Report signing-test and unit-test counts, lint errors, APK path, size, and SHA-256. Do not claim emulator or device evidence from this command.

Codex Cloud is not the canonical Android device gate. Do not run an emulator or `connectedAndroidTest` there unless a ticket explicitly changes this policy.

## Review and CI order

1. Run host checks.
2. Run the Codex Cloud Android preflight.
3. Fix deterministic compile, resource, unit-test, and lint failures.
4. Request independent review only for a green candidate.
5. Freeze a passing candidate unless a finding is a beta blocker.
6. Use GitHub Actions for canonical API 35 instrumentation, fixture evidence, exact-head artifacts, and protected signing.
7. Treat supplemental fixture, screenshot, minor accessibility, and polish failures as nonblocking beta defects unless they expose a core-flow, privacy, safety, startup, build, install, evidence, or signing regression.
8. Permit at most one unchanged-SHA rerun for a classified infrastructure failure; repeated failure needs a defect, not blind retries.

## Evidence discipline

- Never claim a build, test, emulator, device, route, screenshot, signing, or release result that was not executed and read back.
- Keep canonical and supplemental evidence conclusions separate.
- Bind artifacts to the exact commit SHA and preserve failure diagnostics.
- Real Instagram behavior requires consented phone evidence. Fixture and Doom-owned screens do not prove Instagram behavior.
- Do not commit, push, open a PR, sign, release, or merge unless the active task authorizes that action.
