# Doom Wave 0 diagnostic

Native Kotlin/Compose diagnostic for WIL-149. This increment tests structural separability with explicit tester labels and opaque similarities. It also provides consent disclosure, service-health states, a Doom-owned five-second calm-gate demo, and an immediate demo DM/leave action.

## Honest status

- **Observed limitation:** structural counts overlapped on **Pixel 11 Pro / Android 17 / Instagram 445.0.0.45.83**. This is separability research only; fingerprint separability has not been verified. A high similarity is not a screen identity, confidence score or protection claim.
- **Live Instagram:** observation-only and fail-open. With fresh consent and an enabled service, the existing breadth-first traversal visits at most 128 nodes through depth 8. Each node immediately contributes a SHA-256 hash of resource-ID presence, class presence, clickable state, depth and child count capped at 128. Actual resource-ID/class values never enter the hash or retained state. Text/contentDescription are never read. Nodes exist only transiently during bounded traversal and are released on completion or failure. Unavailable trees produce no current sample; failures never obstruct Instagram.
- **Memory and comparisons:** one opaque current fingerprint/vector and optional Feed, Inbox, Thread and Compose baseline vectors exist only in process memory. Tester actions alone assign/replace labels. The vector counts duplicate hashed features; its opaque SHA-256 digest sorts feature hashes and counts, so feature order does not matter. Similarity is weighted Jaccard (sum of minimum counts divided by sum of maximum counts), displayed as a percentage rounded to one decimal with a fixed locale. Identical samples score 100%, disjoint samples 0%, and partial overlap falls between. An empty sample is unavailable, never a match. No prediction, threshold, live gate or navigation is implemented.
- **Privacy limits:** these deterministic presence/shape hashes are opaque display values, not encryption or proof of anonymity; low-entropy structural features can be guessed. No identifier values, account/content data, raw trees or sample history are retained. No sample persistence, export, logging, network or screenshots exist in production. Only consent persists; previous count-only consent does not authorize this increment. Clear, consent revocation, service stop/disconnect/interruption, a new service connection and process death discard the current sample and every baseline. Interruption also marks the observer disconnected; later events cannot record until a new service connection rechecks consent. Clear keeps consent enabled, so a later Instagram event can create a new current sample. Non-Instagram events are ignored, so normally returning to Doom preserves the last Instagram sample for manual labeling. If a delayed Instagram event instead finds an active root with a different or missing package, it invalidates the current sample and disables labeling while retaining explicit baselines. A fresh Instagram sample enables labeling again. Samples can be stale or truncated, and presence-only features deliberately cannot distinguish actual IDs/classes.
- **Real capability report:** incomplete pending verified structural separability and a consenting, DM-safe capability matrix. Fixture/demo evidence cannot satisfy this gate.
- **Demo:** a real five-second gate inside Doom, with immediate simulated messages/leave and cancellation on background/lock. The user-facing APK has no fixture impersonation, overlay, Instagram navigation, live blocking or session allowances. Separate `:fixtureapp` and `:fixturegate` test-only APKs exercise a real accessibility overlay against original fake screens; they are not dependencies of `:app`. See [fixture scope and CI evidence](docs/FIXTURE.md). Their source is not a CI pass or proof of Instagram support.

## Build and test (CI only)

GitHub-hosted CI runs these tasks; Android builds/tests/emulators do not run on the agent host:

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

The package is `com.chardyb.doom`. Instrumented tests use a test-only shell capture on the disposable CI emulator to retain clearly labelled Doom-owned demo screens under `/sdcard/Download/doom-ci-evidence/`, outside app-uninstall cleanup. The production observer never takes screenshots and has no storage/capture permissions. Baseline CI gates the emulator job; artifacts bind the tested source SHA to APK size/checksum and genuine screenshots. Initial bootstrap runs device evidence after baseline on the same PR because no dispatch workflow exists on main yet. No APK/build pass is implied by this document.

Focused pure Kotlin JVM tests cover deterministic fingerprints, identical/disjoint/partial similarities, multiplicity, feature dimensions, empty samples, immutability, bounds and explicit baseline replacement/clear. Unavailable-current tests verify that labels cannot be created or replaced and existing baselines survive. Emulator UI tests use synthetic structures only to check disclosure, disabled labels without samples, deterministic scores, label replacement, clearing, revocation and service cleanup callbacks. They distinguish ignored Doom events from a delayed Instagram event's mismatched-root collection path, and check disconnected UI, blocked recording after interruption and recovery through the service connection callback. Synthetic roots enter the private collector directly; connection tests supply a Context without OS binding. These tests do not prove actual OS callback timing, capture diagnostic samples or open Instagram. The existing exact-four demo screenshot, foreground verification, APK checksum and candidate-SHA evidence contracts remain unchanged. JVM and Android execution belongs to CI; host checks are source inspection, Python source/evidence checks (`python3 scripts/test-structural-lifecycle.py` and `python3 scripts/test-fixture-evidence.py`), shell syntax, XML parsing and `git diff --check` only.

## Try the diagnostic

Install only a CI-built diagnostic APK from this repository. Open Doom and choose **Try the breathing demo**. **Demo messages — no wait** cancels the pause immediately; finishing the pause shows a simulated session start. Neither screen opens Instagram. Reduced motion uses a still pixel bloom; system-disabled animations are respected.

Optional: read the accessibility disclosure, consent to local structural fingerprints, open device accessibility settings and explicitly enable **Doom observation**. Open a known Instagram screen normally, return to Doom, and label the last sample **Feed**, **Inbox**, **Thread** or **Compose**. Each label replaces only that baseline. Visit another screen normally and return to compare opaque structural similarity; the app never decides which screen you visited. No credentials are requested or shared. **Clear samples & labels** removes the current sample and every baseline. **Stop observation** revokes app consent, clears everything and disables the service. System settings and uninstall also remain available. This diagnostic does not protect you from scrolling.

## Remaining real-device gate

WIL-149 remains incomplete until a separate fixture proves cross-app overlay removal and consenting actual-device testing verifies structural mapping and an immediate DM route. Record Android/Instagram version and pass/fail only for launcher/recents, inbox/thread/compose/calls, DM notifications, shared media, Back, lock/rotation and service restart. Do not upload message/account content. Twenty repetitions per high-risk path and measured obstruction/overlay-removal timings belong to later capability evidence, not this demo.

## Scope checklist

- [x] No INTERNET permission; backup disabled; bound accessibility service requires `BIND_ACCESSIBILITY_SERVICE`.
- [x] `isAccessibilityTool=false`; no gesture, key, text, notification-reader, contacts, SMS, or account access.
- [x] Consent is required before device settings action and live observation state.
- [x] Revocation/disconnect is represented as Disabled/Degraded and never reopens an overlay.
- [x] Unknown mapping never starts a live gate.
- [x] Original muted dusk/cream/jade framed UI; large readable controls and scrollable content.
- [ ] Actual Instagram structural mapping, DM routing, latency matrix, and device evidence (blocked).
- [ ] CI build, unit tests, lint, and device screenshots (not run locally by policy).

Design: https://linear.app/wildhearts/document/doom-v1-approved-state-contract-and-skeptic-resolution-c8917da62e01

Issue: https://linear.app/wildhearts/issue/WIL-149/wave-0-prove-dm-safe-instagram-interception

## Provenance

UI pixel geometry is original code; no Pokemon/Final Fantasy assets, fonts or audio are included. Gradle wrapper files come from the official Gradle v8.9.0 repository (Apache-2.0; license in `third-party/gradle-LICENSE.txt`). Wrapper JAR SHA-256 matched the official distribution checksum: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`. The Gradle distribution checksum is pinned in wrapper properties. Android dependencies have explicit versions; third-party Actions are pinned to commit SHAs. No general license for original Doom source has been selected yet.
