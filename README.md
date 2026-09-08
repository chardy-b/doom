# Doom Wave 0 diagnostic

Native Kotlin/Compose diagnostic for WIL-149. This increment is deliberately bounded: it provides consent disclosure, service-health states, a Doom-owned five-second calm-gate demo, and an immediate demo DM/leave action.

## Honest status

- **Live Instagram:** observation-only and fail-open. With consent and an enabled service, it counts at most 128 nodes (depth at most 8), resource-ID presence and clickable controls from the current Instagram tree. It never reads node text/content descriptions or retains nodes/IDs. Only one count snapshot stays in memory; only consent persists. Counts do not classify Feed/DMs. Clear counts or stop observation from the dashboard.
- **Real capability report:** blocked pending a consenting device, Instagram version/account, and redacted DM-safe capability matrix. Fixture/demo evidence cannot satisfy this gate.
- **Demo:** a real five-second gate inside Doom, with immediate simulated messages/leave and cancellation on background/lock. The public APK has no fixture impersonation, overlay, Instagram navigation, live blocking or session allowances. An isolated cross-app fixture and actual accessibility-overlay proof remain unimplemented portions of WIL-149.

## Build and test (CI only)

GitHub-hosted CI runs these tasks; Android builds/tests/emulators do not run on the agent host:

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

The package is `com.chardyb.doom`. Instrumented tests capture clearly labelled Doom-owned demo screens under `/sdcard/Android/data/com.chardyb.doom/files/evidence/`. The production observer never takes screenshots. Baseline CI gates the emulator job; artifacts bind the tested source SHA to APK size/checksum and genuine screenshots. Initial bootstrap runs device evidence after baseline on the same PR because no dispatch workflow exists on main yet. No APK/build pass is implied by this document.

## Try the diagnostic

Install only a CI-built diagnostic APK from this repository. Open Doom and choose **Try the breathing demo**. **Demo messages — no wait** cancels the pause immediately; finishing the pause shows a simulated session start. Neither screen opens Instagram. Reduced motion uses a still pixel bloom; system-disabled animations are respected.

Optional: read the accessibility disclosure, consent to local counts, open device accessibility settings and explicitly enable **Doom observation**. Open Instagram normally, then return to Doom to inspect the in-memory counts. No credentials are requested or shared. **Stop observation** revokes app consent, clears counts and disables the service. System settings and uninstall also remain available. This diagnostic does not protect you from scrolling.

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
