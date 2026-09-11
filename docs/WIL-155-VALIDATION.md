# WIL-155 bounded validation

This cleanup keeps the reviewed WIL-149 behavior unchanged and addresses only deterministic packaging/lint findings.

| Warning/finding | WIL-155 disposition |
|---|---|
| MissingApplicationIcon | Resolved: manifest now references the app-owned `ic_launcher` vector. The breathing-diamond geometry is authored in this repository from the existing dusk/mint/cream visual language; it contains no external or recognizable franchise asset. |
| UnusedResources `R.color.ink` | Resolved: removed the unused color resource. Compose uses the existing source-level color value. |
| Debug exported test/preview activities | Resolved: removed `ui-test-manifest` and debug-only `ui-tooling`, which supplied exported debug activities not required by the current instrumentation tests. `ui-tooling-preview` remains for `@Preview` annotations. `MainActivity` and the required accessibility service remain exported. |
| Backup/data extraction | Guarded: `allowBackup=false`, `fullBackupContent=false`, and both cloud-backup and device-transfer rules exclude all app data domains. |
| UnusedAttribute `isAccessibilityTool` | Deferred with evidence: it is optional and supported on API 31+; it is intentionally retained as `false` to explicitly disclaim accessibility-tool status, and is ignored on older APIs. Risk decision: the warning remains visible; changing this can alter service behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| OldTargetApi (`targetSdk 35`) | Deferred. Risk decision: the warning remains visible; upgrading can change runtime behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| Compose BOM 2024.12.01 (two declarations) | Deferred. Risk decision: the warning remains visible; upgrading can change Compose behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| activity-compose 1.10.0 | Deferred. Risk decision: the warning remains visible; upgrading can change runtime/Compose behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| lifecycle-runtime-compose 2.8.7 | Deferred. Risk decision: the warning remains visible; upgrading can change runtime/Compose behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| AndroidX test JUnit 1.2.1 | Deferred. Risk decision: the warning remains visible; upgrading can change test behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| Espresso 3.6.1 | Deferred. Risk decision: the warning remains visible; upgrading can change test behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |
| UIAutomator 2.3.0 | Deferred. Risk decision: the warning remains visible; upgrading can change test behavior and must be separately compatibility-tested. Acceptable for this nonblocking diagnostic cleanup, but revisit before beta. |

The host guard is `scripts/test_wil155_host.py`. Android/Gradle/device execution remains CI-only by project policy.
