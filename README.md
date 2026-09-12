# Doom Wave 0 diagnostic

Native Kotlin/Compose diagnostic for WIL-149. With fresh explicit consent, it builds one sanitized structural report from Instagram accessibility metadata, with local reveal and a separate reviewed clipboard copy. The Doom-owned five-second breathing demo and immediate demo messages/leave action remain available.

## Honest status

- **Unverified entry gate:** a separate, default-off opt-in may show a five-second accessibility overlay after the first bounded sample strongly identifies Feed, Reels, or Stories. Messaging takes precedence. Inbox, thread, composer, notification-opened messaging, truncated, mixed, unknown, missing-root, foreign-window, runtime-error, or revoked-consent paths remove or bypass the gate. The only leave action removes the overlay before invoking system Home; Doom never clicks an Instagram node or guesses a private route. This is diagnostic capability, not protection.
- **Bounded observation:** the service receives package identifiers for window-state/content events from all apps so a foreign window can synchronously clear state; it returns before reading a foreign active root. A consenting, connected service traverses only an Instagram root, at most 128 nodes breadth-first through depth 8. A delayed Instagram event with a wrong/null root or a collection failure invalidates the report and gate. Foreign or unattributed children consume the node budget but are skipped before reading report metadata or children, marking truncation. Nodes are transient and recycled, including watchdog paths.
- **Strict sanitization:** sanitized reports expose static Instagram resource names from `viewIdResourceName` (compile-time resource-table names), never UI text/content/account values. Only exact `com.instagram.android:id/<name>` values are accepted: the name has 1–64 ASCII characters, starts with `a`–`z`, and contains only `a`–`z`, `0`–`9` or underscore; the total raw value is capped at 96 characters. Validated characters are copied into a new normalized string without retaining caller-owned strings or character sequences. Previously unknown names and grammar-valid numeric/generated-looking suffixes are admitted for selector discovery; there is no guessed resource allowlist or content classification. Other packages, empty/overlong names, uppercase, Unicode, controls, whitespace and path/query delimiters are rejected. Known platform/RecyclerView class names still map through a closed safe map, such as `android.widget.TextView` to `TextView`; unknown classes are omitted.
- **Report format and bounds:** identical tuples of resource token, normalized class, depth, child count (capped at 16), and clickable/scrollable/editable/selected/checked booleans are counted and sorted lexically. `-` means omitted/unavailable metadata. The header identifies format v1 and uses `truncated=0` or `truncated=1`. Working aggregation holds at most 128 rows, each with one copied resource name (at most 64 name characters plus the fixed package prefix) and one safe class; output admits at most 64 unique resource/class tokens combined, chosen lexically, then emits whole sorted rows within 8,192 ASCII characters and UTF-8 bytes, including the header. Rows referencing excluded tokens are omitted. Child-count capping, unvisited nodes/children, depth, unique-token or size limits mark truncation; exact limits without omissions do not. An empty traversal is unavailable. Sanitizer rejection is represented by `-`, independently of traversal truncation. A report can be stale, partial or contain only shape metadata; it is not proof of screen identity.
- **Excluded data:** no text, contentDescription, hints, errors, pane/tooltip titles, bounds, screenshots, notification content, account data, node/window IDs, raw tree or actions enter the report. No report file persistence, logging, network or automatic export exists. The app has no INTERNET permission, backup remains disabled, and permissions/dependencies are unchanged.
- **Memory and consent:** only two independent booleans persist: `sanitized_structural_report_v1` and the default-false `instagram_diagnostic_entry_gate_v1`. Old consent cannot authorize either diagnostic. The current report, gate session/ticket, reveal state and copy-success state start empty in a new process. Leaving Instagram, clear, revoke, observer stop, disconnect, interruption, reconnect and process death discard process-local state. Each new report, including identical content, requires a new reveal before copying.
- **Explicit copy boundary:** reports are hidden until **Reveal local report**. **Copy reviewed report** requires the current report, reveal, valid consent and connected observer, rechecked in the handler. Copy goes to the system clipboard and leaves Doom process memory. Review before copying, upload privately, then clear the clipboard. The sensitive-clipboard flag requests preview suppression on supported Android versions; it does not make clipboard data private or recall external copies. Doom clearing/stopping discards its state, not copies outside the app. Doom has no upload action.
- **Demo and fixture:** the demo gate stays inside Doom, with immediate simulated messages/leave and cancellation on background/lock. Separate `:fixtureapp` and `:fixturegate` test-only APKs exercise an accessibility overlay against original fake screens; they are not dependencies of `:app`. See [fixture scope and CI evidence](docs/FIXTURE.md). Fixture/demo evidence cannot establish Instagram support.

## Build and test (CI only)

GitHub-hosted CI runs these tasks; Android builds/tests/emulators do not run on the agent host:

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

The package is `com.chardyb.doom`. Instrumented tests use a test-only shell capture on the disposable CI emulator to retain clearly labelled Doom-owned demo screens under `/sdcard/Download/doom-ci-evidence/`, outside app-uninstall cleanup. The production observer never takes screenshots and has no storage/capture permissions. Baseline CI gates the emulator job; artifacts bind the tested source SHA to APK size/checksum and genuine screenshots. Initial bootstrap runs device evidence after baseline on the same PR because no dispatch workflow exists on main yet. No APK/build pass is implied by this document.

CI assigns builds a monotonic `versionCode` from `GITHUB_RUN_NUMBER`. A separate, manual workflow on trusted `main` can sign an exact successful Android CI device-evidence artifact with a stable **internal-test** identity. It revalidates GitHub run provenance and the complete evidence manifest, rejects unsafe or duplicate archive entries, proves that non-signature APK payload entries remain identical, pins the certificate fingerprint, and publishes signing evidence. Signing secrets are scoped to the `internal-signing` environment, whose deployment policy permits only `main`; pull-request workflows do not receive them. This internal identity is not the future production key.

Tests were written before implementation. The host source suite first reported five expected failures for the replacement and protected-callback fix. The resource-discovery repair first produced five expected JVM failures and one disclosure source-guard failure. Pure Kotlin JVM tests cover unknown resource names, copied mutable metadata, exact ASCII sanitizer allow/reject cases, hostile and overlong strings, deterministic aggregation and multiplicity, every permitted field, immutable reports, empty input, and node/depth/child/token/character/byte boundaries. Instrumented tests use synthetic metadata to check disclosure, hidden reports, explicit reveal and copy, unchanged clipboard on denied copy, new-report resets, both obsolete consent keys, null/wrong-root invalidation, ignored Doom events, clearing, stop/revoke/disconnect/interruption/reconnect and consent-only persistence. The connection test uses test-only reflection for protected `onServiceConnected`, fixing the prior direct-call compile error without widening production visibility.

Synthetic roots enter the private collector directly; connection tests supply a Context without OS binding. These tests do not prove real OS callback timing, process-kill behavior or actual Instagram metadata. Process-start defaults and absence of report persistence are additionally source-guarded. Clipboard tests use synthetic reports and clean their clipboard afterward. The exact-four demo screenshot, foreground verification, APK checksum, candidate-SHA and fixture evidence contracts remain unchanged. No diagnostic report is captured as CI screenshot evidence.

Pure Kotlin/JVM tests can run directly with a host Kotlin compiler and JUnit, without Gradle or Android. Android compilation, lint, assembly and instrumentation remain CI-only. Other host checks are source inspection, `python3 scripts/test-structural-lifecycle.py`, `python3 scripts/test-fixture-evidence.py`, shell syntax, XML parsing and `git diff --check`. No local Gradle, Android, adb, credentials, commit, push or Linear update is needed. See [implementation validation](docs/WIL-149-VALIDATION.md) for this working-tree check record and CI-only risks.

## Try the diagnostic

Install only a CI-built diagnostic APK from this repository. Open Doom and choose **Try the breathing demo**. **Demo messages — no wait** cancels the pause immediately; finishing the pause shows a simulated session start. Neither screen opens Instagram. Reduced motion uses a still pixel bloom; system-disabled animations are respected.

Optional: read the accessibility disclosure, select **Allow sanitized structural report**, open device accessibility settings and explicitly enable **Doom observation**. The separate **Allow diagnostic Instagram entry pause** opt-in remains off unless selected. Open Instagram yourself. A first strong Feed/Reels/Stories sample may show the five-second diagnostic overlay; messaging and uncertain states bypass it. Returning to Doom clears the Instagram session and report. Re-open Instagram, then return to Doom only when you intend to inspect a new hidden report. Doom never launches Instagram, opens a DM route, or uploads the report. This diagnostic does not protect you from scrolling.

## Remaining real-device gate

WIL-149 remains incomplete until consenting actual-device testing verifies first-frame timing, cross-app cleanup, structural mapping and immediate DM routing across versions. Bounded single-device observations exist on Pixel 11 Pro / Android 17 / Instagram 445.0.0.45.83; cross-version stability and the required repeated safety/latency matrix remain unverified. Record Android/Instagram version and pass/fail only for launcher/recents, inbox/thread/compose/calls, DM notifications, shared media, Back, lock/rotation and service restart. Do not upload message/account content. Twenty repetitions per high-risk path and measured obstruction/overlay-removal timings belong to later capability evidence, not this demo.

## Scope checklist

- [x] No INTERNET permission; backup disabled; bound accessibility service requires `BIND_ACCESSIBILITY_SERVICE`.
- [x] `isAccessibilityTool=false`; no gesture, key, text, notification-reader, contacts, SMS, or account access.
- [x] Consent is required before device settings action and live observation state.
- [x] Revocation/disconnect is represented as Disabled/Degraded and never reopens an overlay.
- [x] Unknown mapping never starts a live gate.
- [x] Original muted dusk/cream/jade framed UI; large readable controls and scrollable content.
- [ ] Actual Instagram structural mapping, DM routing, latency matrix, and device evidence (blocked). Shadow predictions are diagnostic only and do not block/protect.
- [ ] CI build, unit tests, lint, and device screenshots (not run locally by policy).

Design: https://linear.app/wildhearts/document/doom-v1-approved-state-contract-and-skeptic-resolution-c8917da62e01

Issue: https://linear.app/wildhearts/issue/WIL-149/wave-0-prove-dm-safe-instagram-interception

## Provenance

UI pixel geometry is original code; no Pokemon/Final Fantasy assets, fonts or audio are included. Gradle wrapper files come from the official Gradle v8.9.0 repository (Apache-2.0; license in `third-party/gradle-LICENSE.txt`). Wrapper JAR SHA-256 matched the official distribution checksum: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`. The Gradle distribution checksum is pinned in wrapper properties. Android dependencies have explicit versions; third-party Actions are pinned to commit SHAs. No general license for original Doom source has been selected yet.
