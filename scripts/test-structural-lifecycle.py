"""Host-only source guards; Android lifecycle behavior is tested separately in CI."""
from pathlib import Path
import re
import unittest


REPO = Path(__file__).resolve().parents[1]
SERVICE = (REPO / "app/src/main/java/com/chardy/doom/DoomAccessibilityService.kt").read_text()
ROUTER = (REPO / "app/src/main/java/com/chardy/doom/InstagramMessagesRouter.kt").read_text()
OBSERVATION = (REPO / "app/src/main/java/com/chardy/doom/Observation.kt").read_text()
OVERLAY_VIEW = (REPO / "app/src/main/java/com/chardy/doom/EntryGateOverlayView.kt").read_text()
ADAPTER = (REPO / "app/src/main/java/com/chardy/doom/AndroidStructuralMetadataReader.kt").read_text()
METADATA = (REPO / "app/src/main/java/com/chardy/doom/StructuralMetadata.kt").read_text()
ACTIVITY = (REPO / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()


class StructuralLifecycleSourceTest(unittest.TestCase):
    def test_instagram_events_preserve_installed_or_closing_gate_before_new_session_work(self):
        event = SERVICE.split("// Suppression is checked", 1)[1].split('@Suppress', 1)[0]
        keep = event.index("if (overlay != null) return")
        self.assertLess(event.index("entryGate.cooldownActive()"), keep)
        self.assertLess(event.index("!Observation.consent || !Observation.connected"), keep)
        for operation in ("beginInstagramSessionIfEligible()", "overlayPlatform.eventRoot()", "collect(root, captureContext)"):
            self.assertLess(keep, event.index(operation))

    def test_revocation_preflight_covers_visible_overlay_and_running_timer(self):
        event = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split('@Suppress', 1)[0]
        preflight = event.split("// Doom's own", 1)[0]
        self.assertIn("if ((overlay != null || sessionTimer.running) &&", preflight)
        self.assertNotIn("overlay != null || ticket != null", preflight)

    def test_report_only_collection_requires_report_consent_and_connection_not_gate_consent(self):
        event = SERVICE.split("// Suppression is checked", 1)[1].split('@Suppress', 1)[0]
        denial = event.split("// Report-only collection", 1)[1].split(
            "// A successful Debug departure", 1
        )[0]
        self.assertIn("if (!Observation.consent || !Observation.connected)", denial)
        self.assertNotIn("!Observation.gateConsent", denial)
        self.assertIn("val activeTicket = if (Observation.gateConsent)", event)
        self.assertIn("collect(root, captureContext)", event)

    def test_report_only_regression_is_an_actual_accessibility_event_test(self):
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        body = tests.split("@Test fun reportOnlyEventCollectsWithGateConsentOff", 1)[1].split("@Test", 1)[0]
        self.assertIn("service.onAccessibilityEvent(event)", tests)
        self.assertIn("Observation.setGateConsent(rule.activity, false)", body)
        self.assertIn("Observation.connected = true", body)
        self.assertIn("assertNotNull(Observation.report)", body)

    def test_debug_callback_rechecks_all_authority_after_detachment_and_launches_once(self):
        debug = SERVICE.split("private fun requestDebugReport", 1)[1].split(
            "private fun requestOverlayRemoval", 1
        )[0]
        self.assertIn("overlayToken !== token", debug)
        self.assertIn("!Observation.gateConsent", debug)
        confirmed = SERVICE.split("OverlayRemovalAction.OPEN_DEBUG", 1)[1].split(
            "OverlayRemovalAction.NAVIGATE_MESSAGES", 1
        )[0]
        for required in ("Observation.consent", "Observation.gateConsent", "Observation.connected",
                         "Observation.hideReport()", "debugDepartureTicket = departureTicket",
                         "overlayPlatform.openDebug()", "callbackGuard.consumeDetached(detachedToken)"):
            self.assertIn(required, confirmed)
        self.assertLess(confirmed.index("Observation.connected"), confirmed.index("currentRoot()"))
        self.assertLess(confirmed.index("Observation.hideReport()"), confirmed.index("openDebug()"))

    def test_closing_or_stale_visible_event_keeps_the_old_safety_veto(self):
        body = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split('@Suppress', 1)[0]
        branch = body.split("if (packageName != INSTAGRAM)", 1)[1]
        stale = branch.split("val sample =", 1)[0]
        self.assertIn("visibleToken == null || visibleTicket == null", stale)
        self.assertIn("!callbackGuard.acceptsVisible(visibleToken)", stale)
        self.assertGreaterEqual(stale.count("resetOutside("), 3)
        self.assertIn("RemovalTraceMark.EVENT_PACKAGE_RESET", stale)
        self.assertNotIn("eventRoot()", stale)

    def test_other_package_events_revalidate_visible_root_without_collection(self):
        body = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split('@Suppress', 1)[0]
        branch = body.split("if (packageName != INSTAGRAM)", 1)[1]
        self.assertIn("readPackageRoot", branch)
        self.assertIn("overlayPlatform.eventRoot()", branch)
        self.assertIn("foregroundWatchdog.observe", branch)
        self.assertIn("resetOutside(cause = RemovalTraceMark.EVENT_PACKAGE_RESET", branch)
        event_policy = branch.split("// Suppression is checked", 1)[0]
        self.assertNotIn("collect(", event_policy)
        self.assertLess(branch.index("callbackGuard.acceptsVisible"), branch.index("eventRoot()"))
        self.assertLess(branch.index("eventRoot()"), branch.index("foregroundWatchdog.observe"))

    def test_no_visible_gate_keeps_conservative_reset_without_root_access(self):
        body = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split('@Suppress', 1)[0]
        branch = body.split("if (packageName != INSTAGRAM)", 1)[1]
        no_visible = branch.split("val sample =", 1)[0]
        self.assertIn("visibleView == null", no_visible)
        self.assertIn("resetOutside(cause = RemovalTraceMark.EVENT_PACKAGE_RESET", no_visible)
        self.assertNotIn("eventRoot()", no_visible)

    def test_collector_rejects_non_instagram_roots_and_recycles_every_path(self):
        body = SERVICE.split("private fun collect", 1)[1].split("override fun onInterrupt", 1)[0]
        self.assertIn("INSTAGRAM", body)
        self.assertNotIn("BuildConfig.APPLICATION_ID", body)
        self.assertIn("StructuralSanitizer.isExactAscii", body)
        self.assertLess(body.index("queue.add(QueueEntry(root"), body.index("try {"))
        self.assertIn("while (queue.isNotEmpty()) queue.removeFirst().node.recycle()", body)

        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        preservation = tests.split("@Test fun doomEventsPreserveReportButForeignOrMissingRootInvalidatesAllReportState", 1)[1].split("@Test", 1)[0]
        self.assertIn('getDeclaredMethod("attachBaseContext", Context::class.java)', preservation)
        self.assertIn('invoke(service, rule.activity.applicationContext)', preservation)
        self.assertIn('sendEvent(service, rule.activity.packageName, MainActivity::class.java.name)', preservation)
        self.assertNotIn('collectSyntheticRoot(service, rule.activity.packageName)', preservation)
        self.assertIn('listOf("com.example.foreign", null)', tests)
        self.assertIn("rule.activity.packageName", tests)
        self.assertNotIn('listOf(rule.activity.packageName, null)', tests)
        self.assertIn('sendEvent(service, "com.instagram.android")', preservation)

    def test_instrumentation_activity_ownership_is_explicit_and_rule_activity_is_not_replaced(self):
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        overlay = (REPO / "app/src/androidTest/java/com/chardy/doom/EntryGateOverlayUiTest.kt").read_text()
        self.assertIn("FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT", tests)
        self.assertIn("rotationScenario.recreate()", tests)
        self.assertNotIn("rule.activityRule.scenario.recreate()", tests)
        self.assertNotIn("rule.scenario.recreate()", tests)
        self.assertIn("launchOwnedScenario", overlay)
        self.assertIn("ownedScenario", overlay)
        self.assertIn("FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT", overlay)
        self.assertNotRegex(overlay, r"rule\.scenario\.onActivity\s*\{\s*it\.recreate\(\)\s*\}")

    def test_interrupt_marks_disconnected_and_clears_before_any_later_record(self):
        self.assertIn("override fun onInterrupt()", SERVICE)
        disconnect = SERVICE.split("private fun disconnect()", 1)[1]
        self.assertRegex(disconnect, r'Observation.connected = false\s+Observation.clear\(\)')
        record = OBSERVATION.split('internal fun record(', 1)[1].split('fun revealReport()', 1)[0]
        self.assertRegex(record, r'if \(!consent \|\| !connected\) return\s+clear\(\)\s+report = sample')

    def test_reconnection_clears_then_loads_consent_before_enabling_recording(self):
        body = SERVICE.split("override fun onServiceConnected()", 1)[1].split("override fun onAccessibilityEvent", 1)[0]
        self.assertRegex(body, r'Observation.connected = false\s+Observation.clear\(\)\s+Observation.load\(this\)\s+'
                              r'if \(!Observation.consent\) \{\s*disableSelf\(\)\s*return\s*\}\s*'
                              r'Observation.connected = true')

    def test_fresh_consent_and_no_superseded_implementation(self):
        self.assertIn('"sanitized_structural_report_v2"', OBSERVATION)
        self.assertFalse((REPO / "app/src/main/java/com/chardy/doom/StructuralFingerprint.kt").exists())
        ui = (REPO / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()
        for old in ("SampleLabel", "similarity", "Opaque fingerprint", "LABEL FEED"):
            self.assertNotIn(old, ui)
        self.assertIn("REVEAL LOCAL REPORT", ui)
        self.assertIn("COPY REVIEWED REPORT", ui)

    def test_collector_reads_only_approved_metadata(self):
        import re
        reads = set(re.findall(r"node\.([A-Za-z]+)", ADAPTER))
        expected = {"packageName", "viewIdResourceName", "className", "childCount", "windowId", "uniqueId",
                    "getBoundsInWindow", "getBoundsInScreen", "drawingOrder", "isCheckable", "isChecked",
                    "isClickable", "isEnabled", "isFocusable", "isFocused", "isLongClickable", "isPassword",
                    "isScrollable", "isSelected", "isAccessibilityFocused", "isVisibleToUser", "isEditable",
                    "canOpenPopup", "isContentInvalid", "isDismissable", "isMultiLine", "isContextClickable",
                    "isImportantForAccessibility", "isShowingHintText", "isHeading", "isScreenReaderFocusable",
                    "isTextEntryKey", "isTextSelectable", "isAccessibilityDataSensitive",
                    "isGranularScrollingSupported", "actionList", "collectionInfo", "collectionItemInfo",
                    "rangeInfo", "inputType", "liveRegion", "movementGranularities", "textSelectionStart",
                    "textSelectionEnd", "maxTextLength"}
        self.assertTrue(expected.issubset(reads), sorted(expected - reads))
        for forbidden in ("getText", "contentDescription", "getBeforeText", "hintText", "getHintText",
                          "getError", "getStateDescription", "getPaneTitle", "getTooltipText",
                          "getContainerTitle", "getSupplementalDescription", "getExtras",
                          "availableExtraData", "getAvailableExtraData", "getExtraRenderingInfo",
                          "getLabel", "getParcelableData", "toString", "hashCode", "getSource",
                          "getRecord", "refreshWithExtraData", "getLabelFor", "getLabeledBy",
                          "getTraversalBefore", "getTraversalAfter", "getParent", "getWindow",
                          "performAction", "dispatchGesture", "takeScreenshot", "getEventTime", "Log."):
            self.assertNotIn(forbidden, ADAPTER + SERVICE)
        self.assertIsNone(re.search(r"\.text(?:\b|\s*\()", ADAPTER + SERVICE))
        self.assertIn('builder.markTruncated("foreign")', SERVICE)

    def test_adapter_event_and_nested_getters_are_allowlisted_and_mutation_checked(self):
        import re
        event_reads = set(re.findall(r"event\.([A-Za-z]+)", ADAPTER))
        self.assertTrue({"eventType", "contentChangeTypes", "windowChanges", "action",
                         "movementGranularity"}.issubset({f for f in event_reads}))
        nested_reads = set(re.findall(r"info\.([A-Za-z]+)", ADAPTER))
        self.assertTrue({"rowCount", "columnCount", "isHierarchical", "selectionMode",
                         "itemCount", "importantForAccessibilityItemCount", "rowIndex",
                         "rowSpan", "columnIndex", "columnSpan", "isHeading", "isSelected",
                         "type", "min", "max", "current"}.issubset(nested_reads))

        forbidden = (
            r"\b(?:node|event|info|record|action)\s*\.\s*(?:text|contentDescription|hintText|label|extras|"
            r"availableExtraData|uniqueId\s*\.\s*toString)\b",
            r"\.(?:getText|getContentDescription|getHintText|getLabel|getExtras|performAction|"
            r"dispatchGesture|takeScreenshot)\s*\(",
            r"\b(?:node|event|info|record|action)\s*\.\s*toString\s*\(",
            r"(?:Gson|Moshi|Jackson|kotlinx\.serialization|JSONObject|Bundle)\b",
        )

        def assert_clean(source):
            for pattern in forbidden:
                self.assertIsNone(re.search(pattern, source), pattern)

        assert_clean(ADAPTER + SERVICE)
        for injected in (
            "node.getText()", "event.contentDescription", "info.getExtras()",
            "record.toString()", "action.getLabel()",
        ):
            with self.assertRaises(AssertionError):
                assert_clean(ADAPTER + "\n" + injected)

    def test_disclosures_explain_static_resource_discovery_and_new_bounds(self):
        for path in ("README.md", "app/src/main/java/com/chardy/doom/MainActivity.kt",
                     "app/src/main/res/values/strings.xml"):
            source = (REPO / path).read_text()
            for required in ("static Instagram resource names", "64 unique", "8,192", "UI text"):
                self.assertIn(required, source, path)
            for obsolete in ("allowlisted static", "closed resource vocabulary", "32 unique", "4,096"):
                self.assertNotIn(obsolete, source, path)

    def test_copy_is_guarded_and_not_automatic(self):
        self.assertIn("fun copyReport(context: Context)", OBSERVATION)
        body = OBSERVATION.split("fun copyReport(context: Context)", 1)[1].split("fun clear()", 1)[0]
        self.assertIn("if (!canCopy) return", body)
        self.assertLess(body.index("if (!canCopy) return"), body.index("setPrimaryClip"))
        self.assertIn("ClipData.newPlainText", body)
        self.assertIn("consent && connected && report != null", OBSERVATION)
        self.assertIn("canReveal && revealed", OBSERVATION)
        self.assertIn("revealed = false", OBSERVATION)
        self.assertIn("copied = false", OBSERVATION)

    def test_connection_test_does_not_call_protected_callback_directly(self):
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        self.assertNotIn("service.onServiceConnected()", tests)
        self.assertIn('getDeclaredMethod("onServiceConnected")', tests)

    def test_debug_intent_rejects_unknown_extras_without_empty_bundle_read(self):
        predicate = ACTIVITY.split("private fun isDebugIntent", 1)[1].split("}", 1)[0]
        self.assertIn("intent.data == null", predicate)
        self.assertIn("intent.categories.isNullOrEmpty()", predicate)
        self.assertIn("intent.extras == null", predicate)
        self.assertNotIn("extras?.isEmpty", predicate)
        self.assertNotIn("extras!!", predicate)
        on_new_intent = ACTIVITY.split("override fun onNewIntent", 1)[1].split(
            "override fun onSaveInstanceState", 1
        )[0]
        self.assertNotIn(
            "debugRequestPending = false",
            on_new_intent,
            "malformed/unrelated intents must not cancel an already pending valid request",
        )
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        malformed = tests.split("@Test fun warmRepeatedDebugIntentTargetsControlsAndMalformedReplacementDoesNotReplay", 1)[1].split("@Test", 1)[0]
        self.assertIn('putExtra("unexpected", 1)', malformed)
        self.assertIn("callActivityOnNewIntent", tests)

    def test_debug_navigation_is_one_shot_bring_into_view_and_consumes_after_visibility(self):
        self.assertIn("BringIntoViewRequester", ACTIVITY)
        self.assertIn("bringIntoViewRequester", ACTIVITY)
        self.assertIn("bringIntoView()", ACTIVITY)
        self.assertIn("debugScrollRequest = 0L", ACTIVITY)
        self.assertNotIn("positionInParent", ACTIVITY)
        self.assertNotIn("debugScroll.value +", ACTIVITY)
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        for name in (
            "coldDebugIntentConsumesOnceAndTargetsReportControls",
            "warmRepeatedDebugIntentTargetsControlsAndMalformedReplacementDoesNotReplay",
        ):
            body = tests.split(f"@Test fun {name}", 1)[1].split("@Test", 1)[0]
            self.assertNotIn("performScrollTo", body)
            self.assertNotIn("swipe", body.lower())
            self.assertIn("assertDebugControlsDisplayed", body)

    def test_debug_anchor_readiness_is_request_scoped_and_reset_when_leaving_debug(self):
        self.assertIn("debugReportControlsReadyForRequest", ACTIVITY)
        self.assertIn("debugReportControlsReadyForRequest == debugRequest", ACTIVITY)
        self.assertIn("debugReportControlsReadyForRequest = -1L", ACTIVITY)
        tests = (REPO / "app/src/androidTest/java/com/chardy/doom/StructuralDiagnosticUiTest.kt").read_text()
        body = tests.split("@Test fun leavingDebugThenStartingWarmDebugRequestReanchorsControls", 1)[1].split("@Test", 1)[0]
        self.assertIn('onNodeWithText("Home").performClick()', body)
        self.assertIn('onNodeWithText("Debug").performClick()', body)
        self.assertIn("assertDebugControlsDisplayed()", body)


    def test_process_start_is_empty_and_all_clear_state_is_memory_only(self):
        self.assertIn("var connected by mutableStateOf(false)", OBSERVATION)
        self.assertIn("var consent by mutableStateOf(false)", OBSERVATION)
        self.assertIn("var report by mutableStateOf<SanitizedStructuralReport?>(null)", OBSERVATION)
        for field in ("revealed", "copied"):
            self.assertIn(f"var {field} by mutableStateOf(false)", OBSERVATION)
        clear = OBSERVATION.split("fun clear()", 1)[1]
        self.assertRegex(clear, r'report = null\s+revealed = false\s+copied = false')
        for forbidden in ("putString", "putInt", "putLong", "File(", "rememberSaveable", "SavedStateHandle"):
            self.assertNotIn(forbidden, OBSERVATION)
        self.assertIn('if (!consent) clear()', OBSERVATION)

    def test_app_permissions_backup_and_observation_scope(self):
        import xml.etree.ElementTree as ET
        ns = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(REPO / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual([], manifest.findall("uses-permission"))
        app = manifest.find("application")
        self.assertEqual("false", app.get(ns + "allowBackup"))
        self.assertEqual("false", app.get(ns + "fullBackupContent"))
        self.assertEqual("android.permission.BIND_ACCESSIBILITY_SERVICE", app.find("service").get(ns + "permission"))
        config = ET.parse(REPO / "app/src/main/res/xml/accessibility_service_config.xml").getroot()
        self.assertIsNone(config.get(ns + "packageNames"))
        self.assertEqual("typeWindowStateChanged|typeWindowContentChanged", config.get(ns + "accessibilityEventTypes"))
        self.assertEqual("flagReportViewIds", config.get(ns + "accessibilityFlags"))
        self.assertEqual("false", config.get(ns + "isAccessibilityTool"))


    def test_traversal_bounds_cover_enqueued_nodes_depth_and_missing_children(self):
        self.assertIn("while (queue.isNotEmpty() && visited < SanitizedStructuralReport.MAX_NODES)", SERVICE)
        self.assertIn("if (depth >= SanitizedStructuralReport.MAX_DEPTH)", SERVICE)
        self.assertIn("SanitizedStructuralReport.MAX_NODES - visited - queue.size", SERVICE)
        self.assertIn("builder.markTruncated(\"nodes\")", SERVICE)
        self.assertIn('if (queue.isNotEmpty()) builder.markTruncated("nodes")', SERVICE)
        self.assertRegex(SERVICE, r'finally \{\s+node.recycle\(\)\s+\}')

    def test_report_paths_have_no_implicit_export_or_persistence(self):
        import re
        production = REPO / "app/src/main/java/com/chardy/doom"
        files = {p.name: p.read_text() for p in production.glob("*.kt")}
        self.assertEqual(1, sum(source.count("setPrimaryClip(") for source in files.values()))
        self.assertEqual(1, sum(source.count("Observation.copyReport(context)") for source in files.values()))
        ui = files["MainActivity.kt"]
        self.assertIn('Action("COPY REVIEWED REPORT", enabled = Observation.canCopy) { Observation.copyReport(context) }', ui)
        self.assertIn('if (Observation.canReveal && Observation.revealed && report != null)', ui)
        for name in ("SanitizedStructuralReport.kt", "Observation.kt", "DoomAccessibilityService.kt"):
            for forbidden in (r'\bLog\.', r'\bprintln\(', r'\bprintStackTrace\(', r'\bFile\(',
                              r'java\.net', r'java\.io', r'ACTION_SEND',
                              r'openFileOutput', r'putString', r'putStringSet', r'SavedStateHandle'):
                self.assertIsNone(re.search(forbidden, files[name]), f"{name}: {forbidden}")
        for name in ("SanitizedStructuralReport.kt", "Observation.kt"):
            self.assertNotIn("android.graphics", files[name])


    def test_foreign_or_unattributed_children_are_skipped_before_metadata_reads(self):
        body = SERVICE.split("val entry = queue.removeFirst()", 1)[1]
        self.assertIn("if (node.packageName?.let", body)
        self.assertIn('builder.markTruncated("foreign")', body)
        self.assertLess(body.index("node.packageName"), body.index("reader.read"))

    def test_entry_overlay_is_default_off_bounded_and_remove_before_actions(self):
        policy = (REPO / "app/src/main/java/com/chardy/doom/InstagramEntryGate.kt").read_text()
        self.assertIn("private val enabled: () -> Boolean = { false }", policy)
        self.assertIn("durationMs in 1L..120_000L", policy)
        self.assertIn("TYPE_ACCESSIBILITY_OVERLAY", SERVICE)
        self.assertIn('"Skip to Messages"', OVERLAY_VIEW)
        self.assertIn('button(context,"Leave Instagram"', OVERLAY_VIEW)
        self.assertIn("setOnClickListener{onSkipToMessages()}", OVERLAY_VIEW)
        self.assertIn("setOnClickListener{onLeaveInstagram()}", OVERLAY_VIEW)
        install = SERVICE.split("EntryGateOverlayViewFactory.create", 1)[1].split(
            "val parameters", 1
        )[0]
        self.assertIn("OverlayRemovalAction.NAVIGATE_MESSAGES", install)
        self.assertIn("OverlayRemovalAction.HOME, token", install)
        confirmed = SERVICE.split("private fun confirmOverlayRemoved", 1)[1].split(
            "private fun cancelAndBypass", 1
        )[0]
        home = confirmed.split("OverlayRemovalAction.HOME", 1)[1].split("null -> Unit", 1)[0]
        self.assertLess(home.index("entryGate.bypass"), home.index("performHome"))
        self.assertNotIn("dispatchGesture", SERVICE)
        self.assertNotIn("node.performAction", SERVICE)

    def test_gate_timer_watchdog_and_completion_fail_open(self):
        self.assertIn("WATCHDOG_INTERVAL_MS = 50L", SERVICE)
        self.assertIn("WATCHDOG_UNCERTAINTY_GRACE_MS = 150L", SERVICE)
        self.assertIn("entryGate.overlayShown(shownAt, activeTicket)", SERVICE)
        self.assertIn("foregroundWatchdog.reset(shownAt)", SERVICE)
        completion = SERVICE.split("completion = Runnable", 1)[1].split("watchdog =", 1)[0]
        self.assertIn("OverlayRemovalAction.COMPLETE", completion)
        watchdog = SERVICE.split("watchdog = object", 1)[1].split(
            "}.also { handler.post(it) }", 1
        )[0]
        self.assertIn("runWatchdogTick(activeTicket, token, this)", watchdog)
        tick = SERVICE.split("private fun runWatchdogTick", 1)[1].split(
            "private fun requestOverlayRemoval", 1
        )[0]
        self.assertIn("readWatchdogRoot()", tick)
        self.assertIn("handler.postDelayed(next, WATCHDOG_INTERVAL_MS)", tick)

        read = SERVICE.split("private fun readPackageRoot", 1)[1].split(
            "private fun traceRecord", 1
        )[0]
        self.assertIn("readPackageRoot", read)
        self.assertIn("overlayPlatform.recycleRoot(it)", read)
        self.assertIn("catch (_: RuntimeException)", read)
        self.assertIn("RemovalTraceRoot.READ_FAILURE", read)
        self.assertIn("foregroundWatchdog.observe", tick)
        self.assertIn("OverlayForegroundDecision.FAIL_OPEN", tick)
        self.assertIn("handler.postDelayed(next, WATCHDOG_INTERVAL_MS)", tick)

    def test_watchdog_uncertainty_is_bounded_and_foreign_roots_are_not_graced(self):
        policy = (REPO / "app/src/main/java/com/chardy/doom/OverlayForegroundWatchdog.kt").read_text()
        self.assertIn("uncertaintyGraceMs in 1L..249L", policy)
        self.assertIn("lastSafeAtMs", policy)
        self.assertIn("fun reset(shownAtMs: Long)", policy)
        self.assertIn("nowMs - lastSafe < uncertaintyGraceMs", policy)
        self.assertIn("if (packageName != null)", policy)
        self.assertLess(policy.index("if (packageName != null)"), policy.index("val lastSafe = lastSafeAtMs ?: run"))
        install = SERVICE.split("private fun installOverlay", 1)[1].split(
            "private fun runWatchdogTick", 1
        )[0]
        self.assertIn("manager.addView(view, parameters)", SERVICE)
        self.assertLess(install.index("entryGate.overlayShown(shownAt, activeTicket)"),
                        install.index("foregroundWatchdog.reset(shownAt)"))
        self.assertNotIn("recordTerminal", install)

    def test_overlay_actions_wait_for_confirmed_physical_detachment(self):
        removal = SERVICE.split("private fun requestOverlayRemoval", 1)[1].split(
            "private fun cancelAndBypass", 1
        )[0]
        self.assertIn("overlayPlatform.removeImmediate", removal)
        self.assertGreaterEqual(removal.count("overlayPlatform.isAttached(view)"), 2)
        self.assertIn("removalPolicy.failedAttempt()", removal)
        self.assertIn("handler.postDelayed(it, REMOVAL_RETRY_INTERVAL_MS)", removal)
        self.assertIn("disableSelf()", removal)
        self.assertIn("removalPolicy.confirmedDetached()", removal)
        self.assertLess(removal.index("confirmedDetached()"), removal.index("performHome"))

    def test_debug_departure_is_detached_authorized_report_preserving_and_nonterminal(self):
        confirmed = SERVICE.split("OverlayRemovalAction.OPEN_DEBUG", 1)[1].split(
            "OverlayRemovalAction.NAVIGATE_MESSAGES", 1
        )[0]
        self.assertIn("hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING)", confirmed)
        self.assertIn("overlayPlatform.currentRoot()", confirmed)
        self.assertIn("overlayPlatform.readRootPackage(root)", confirmed)
        self.assertIn("entryGate.bypass(departureTicket)", confirmed)
        self.assertIn("Observation.hideReport()", confirmed)
        self.assertIn("debugDepartureTicket = departureTicket", confirmed)
        self.assertIn("overlayPlatform.openDebug()", confirmed)
        self.assertLess(confirmed.index("entryGate.bypass(departureTicket)"), confirmed.index("overlayPlatform.openDebug()"))
        for forbidden in ("entryGate.complete", "finishMessagesRoute", "routeMessages", "recordTerminal"):
            self.assertNotIn(forbidden, confirmed)

    def test_foreign_event_reads_one_package_only_root_and_skips_collection(self):
        event = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split(
            '@Suppress("DEPRECATION")', 1
        )[0]
        branch = event.split("if (packageName != INSTAGRAM)", 1)[1]
        self.assertIn("readPackageRoot", branch)
        self.assertIn("overlayPlatform.eventRoot()", branch)
        self.assertIn("overlayPlatform.readRootPackage", SERVICE)
        self.assertLess(branch.index("eventRoot()"), branch.index("foregroundWatchdog.observe"))
        event_policy = branch.split("// Suppression is checked", 1)[0]
        self.assertNotIn("collect(", event_policy)
        self.assertIn("resetOutside(cause = RemovalTraceMark.EVENT_PACKAGE_RESET", event)
        self.assertIn("fun overlayDiagnosticStatus()", (REPO / "app/src/main/java/com/chardy/doom/Observation.kt").read_text())
        self.assertNotIn("event.text", event)
        self.assertNotIn("contentDescription", event)

    def test_doom_events_require_positive_main_activity_signal_for_preservation(self):
        self.assertIn("TYPE_WINDOW_STATE_CHANGED", SERVICE)
        self.assertIn('StructuralSanitizer.isExactAscii(event.className, MainActivity::class.java.name)', SERVICE)
        self.assertIn("isMainActivityReturn(event)", SERVICE)
        self.assertIn("Ignore all other Doom-owned events", SERVICE)
        own_events = SERVICE.split("if (packageName == applicationContext.packageName)", 1)[1].split(
            "if (packageName != INSTAGRAM)", 1
        )[0]
        self.assertIn("OverlayRemovalAction.PRESERVE_REPORT", own_events)
        self.assertIn("RemovalTraceMark.APP_RETURN", own_events)
        self.assertNotIn("if (overlay != null)", own_events)
        preserve = SERVICE.split("OverlayRemovalAction.PRESERVE_REPORT", 1)[1].split("OverlayRemovalAction.RESET_OUTSIDE", 1)[0]
        self.assertIn("mainActivityReturnObserved", preserve)

    def test_removal_priority_keeps_safety_and_explicit_actions_above_preserve(self):
        policy = (REPO / "app/src/main/java/com/chardy/doom/OverlayRemovalPolicy.kt").read_text()
        self.assertLess(policy.index("COMPLETE -> 0"), policy.index("NAVIGATE_MESSAGES -> 1"))
        self.assertLess(policy.index("HOME -> 5"), policy.index("RESET_OUTSIDE -> 6"))

    def test_non_preservation_cleanup_clears_verified_return_marker_centrally(self):
        confirmed = SERVICE.split("private fun confirmOverlayRemoved", 1)[1].split(
            "private fun cancelAndBypass", 1
        )[0]
        self.assertIn("val action = removalPolicy.confirmedDetached()", confirmed)
        self.assertIn(
            "if (action != OverlayRemovalAction.PRESERVE_REPORT) mainActivityReturnObserved = false",
            confirmed
        )

    def test_messages_route_is_exact_id_only_and_has_no_fallback(self):
        self.assertFalse((REPO / "app/src/main/java/com/chardy/doom/InstagramInboxLauncher.kt").exists())
        self.assertFalse((REPO / "app/src/androidTest/java/com/chardy/doom/InstagramInboxLauncherTest.kt").exists())
        self.assertIn('INSTAGRAM_DIRECT_TAB_ID = "com.instagram.android:id/direct_tab"', ROUTER)
        self.assertIn("findAccessibilityNodeInfosByViewId(viewId)", ROUTER)
        self.assertIn("AccessibilityNodeInfo.ACTION_CLICK", ROUTER)
        self.assertEqual(1, ROUTER.count("performAction(AccessibilityNodeInfo.ACTION_CLICK)"))
        for forbidden in ("findAccessibilityNodeInfosByText", "contentDescription", "childCount",
                          "getChild", "dispatchGesture", "createChooser", "ACTION_VIEW",
                          "ACTION_SEND", "resolveActivity", "Uri.parse", "http://", "https://",
                          "startActivity", "performGlobalAction"):
            self.assertNotIn(forbidden, ROUTER)
        self.assertNotIn("launchInbox", SERVICE)

    def test_messages_router_recycles_unique_matches_and_service_recycles_root(self):
        self.assertIn("recycleDistinct(matches, root, recycle)", ROUTER)
        self.assertIn("Collections.newSetFromMap(IdentityHashMap", ROUTER)
        route = SERVICE.split("OverlayRemovalAction.NAVIGATE_MESSAGES", 1)[1]
        self.assertIn("val root = try { overlayPlatform.currentRoot()", route)
        self.assertIn("try { overlayPlatform.recycleRoot(root) } catch (_: RuntimeException) { }", route)

    def test_messages_router_counts_before_state_reads_and_selected_short_circuits(self):
        body = ROUTER.split("): MessagesRouteResult", 1)[1]
        self.assertIn("matches.size != 1", body)
        self.assertIn("readSelected(requireNotNull(match))", body)
        self.assertIn("readActionability(requireNotNull(match))", body)
        self.assertLess(body.index("matches.size != 1"), body.index("readSelected(requireNotNull(match))"))
        self.assertLess(body.index("readSelected(requireNotNull(match))"), body.index("readActionability(requireNotNull(match))"))
        self.assertLess(body.index("readActionability(requireNotNull(match))"), body.index("click(requireNotNull(match))"))

    def test_messages_route_rechecks_token_consents_and_foreground_before_exact_query(self):
        import re
        service = SERVICE[SERVICE.index("OverlayRemovalAction.NAVIGATE_MESSAGES", SERVICE.index("private fun confirmOverlayRemoved")):]
        self.assertIn("detachedToken?.ticket", service)
        self.assertIn("ownsDetachedEpisode", service)
        self.assertIn("Observation.gateConsent", service)
        self.assertIn("Observation.consent", service)
        self.assertIn("Observation.connected", service)
        self.assertIn("currentRoot()", service)
        self.assertIn("safePackageToken(root.packageName) == INSTAGRAM", service)
        self.assertLess(service.index("hasDetachedTerminalAuthority(detachedToken, EntryGateState.GATING)"), service.index("currentRoot()"))
        authority = SERVICE.split("private fun hasDetachedTerminalAuthority", 1)[1]
        self.assertIn("token.ticket == ticket", authority)
        self.assertIn("callbackGuard.acceptsDetached(token)", authority)
        self.assertLess(service.index("currentRoot()"), service.index("routeMessages(root)"))
        route_only = service.split("OverlayRemovalAction.NAVIGATE_MESSAGES", 1)[1].split(
            "OverlayRemovalAction.PRESERVE_REPORT", 1
        )[0]
        for forbidden in ("viewIdResourceName", "contentDescription", "childCount",
                          "getChild", "findAccessibilityNodeInfosByText", "dispatchGesture"):
            self.assertNotIn(forbidden, route_only)
        self.assertIsNone(re.search(r"\.text\b|getText\s*\(", route_only))

    def test_overlay_callbacks_are_scoped_and_service_has_no_compose_or_animation_clock(self):
        guard = (REPO / "app/src/main/java/com/chardy/doom/OverlayCallbackGuard.kt").read_text()
        self.assertIn("OverlayCallbackToken", guard)
        self.assertIn("++nextEpoch", guard)
        self.assertIn("acceptsVisible", guard)
        self.assertIn("acceptsRemoval", guard)
        self.assertIn("beginClosing", guard)
        self.assertIn("callbackGuard.acceptsVisible(token)", SERVICE)
        self.assertIn("callbackGuard.acceptsRemoval(token)", SERVICE)
        self.assertNotIn("ComposeView", SERVICE)
        self.assertNotIn("ValueAnimator", (REPO / "app/src/main/java/com/chardy/doom/PixelBreathingView.kt").read_text())
        self.assertNotIn("postDelayed", (REPO / "app/src/main/java/com/chardy/doom/PixelBreathingView.kt").read_text())

    def test_overlay_status_and_copy_model_never_contain_report_payload(self):
        model = (REPO / "app/src/main/java/com/chardy/doom/EntryGateOverlayModel.kt").read_text()
        self.assertIn("OverlayReportStatus", model)
        self.assertIn("canCopyCurrentReport", model)
        for forbidden in ("SanitizedStructuralReport", "report.text", "viewIdResourceName", "contentDescription"):
            self.assertNotIn(forbidden, model)
        observation = OBSERVATION
        self.assertIn("fun overlayDiagnosticStatus()", observation)
        self.assertIn("fun copyCurrentReportFromOverlay(context: Context)", observation)
        self.assertEqual(1, sum(path.read_text().count("setPrimaryClip(") for path in
                                 (REPO / "app/src/main/java/com/chardy/doom").glob("*.kt")))

    def test_build_identity_is_generated_and_not_hard_coded(self):
        gradle = (REPO / "app/build.gradle.kts").read_text()
        ui = (REPO / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()
        self.assertIn("buildConfig = true", gradle)
        self.assertIn("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})", ui)
        self.assertNotIn("code 29", ui)

    def test_overlay_uses_wrapping_accessible_layout_and_complete_insets(self):
        self.assertIn("LinearLayout.LayoutParams(-1,-2)", OVERLAY_VIEW)
        self.assertIn("minHeight", OVERLAY_VIEW)
        self.assertNotIn("LinearLayout.LayoutParams(-1, dp(48))", OVERLAY_VIEW)
        self.assertNotIn("LinearLayout.LayoutParams(-1, dp(52))", OVERLAY_VIEW)
        self.assertIn("systemWindowInsetLeft", OVERLAY_VIEW)
        self.assertIn("systemWindowInsetTop", OVERLAY_VIEW)
        self.assertIn("systemWindowInsetRight", OVERLAY_VIEW)
        self.assertIn("systemWindowInsetBottom", OVERLAY_VIEW)
        self.assertIn("displayCutout", OVERLAY_VIEW)
        self.assertNotIn('contentDescription = "Diagnostic status"', OVERLAY_VIEW)
        for required in ('"Breathe in"', '"Skip to Messages"', '"Leave Instagram"', '"Debug report"', "PixelBreathingView", "SegmentedBreathProgressView"):
            self.assertIn(required, OVERLAY_VIEW)
        for forbidden in ("countdown", "copyCurrentReport", "status", "REPORT CAPTURED", "Take a breath"):
            self.assertNotIn(forbidden, OVERLAY_VIEW)
        self.assertIn("pixel.visibility=View.INVISIBLE", OVERLAY_VIEW)

    def test_segmented_progress_has_stable_hierarchy_and_fractional_fill(self):
        self.assertNotIn("removeAllViews", OVERLAY_VIEW)
        self.assertNotIn("track.post", OVERLAY_VIEW)
        self.assertNotIn("1000", OVERLAY_VIEW)
        self.assertIn("segments=values.toList();invalidate()", OVERLAY_VIEW)
        self.assertIn("segments.size-1", OVERLAY_VIEW)
        self.assertIn("segmentWidth*fraction.coerceIn(0f,1f)", OVERLAY_VIEW)

    def test_custom_settings_dialogs_have_real_cancel_and_valid_save(self):
        ui = (REPO / "app/src/main/java/com/chardy/doom/MainActivity.kt").read_text()
        dialog = ui.split("private fun CustomDialog(", 1)[1].split(
            "private fun Debug(", 1
        )[0]
        self.assertIn("onDismissRequest = cancel", dialog)
        self.assertIn('TextButton(onClick = cancel) { Text("Cancel") }', dialog)
        self.assertIn("enabled = parse() != null", dialog)
        self.assertNotIn('Text("Clear")', dialog)

    def test_phase_label_changes_only_with_the_breathing_phase(self):
        self.assertIn("private var lastPhase:String?=null", OVERLAY_VIEW)
        self.assertIn("if(lastPhase!=model.frame.label)", OVERLAY_VIEW)

    def test_native_overlay_restores_root_and_heading_accessibility(self):
        self.assertIn('contentDescription="Instagram diagnostic pause"', OVERLAY_VIEW)
        self.assertIn("isFocusable=true", OVERLAY_VIEW)
        self.assertIn("isAccessibilityHeading=true", OVERLAY_VIEW)

    def test_direct_return_closes_visible_callbacks_before_preserving_report(self):
        own_events = SERVICE.split("RemovalTraceMark.APP_RETURN", 1)[0]
        self.assertIn("requestOverlayRemoval", own_events)
        preserve_request = SERVICE.split("RemovalTraceMark.APP_RETURN", 1)[0]
        self.assertNotIn("allowClosing", preserve_request)
        self.assertIn("beginClosing", SERVICE.split("private fun requestOverlayRemoval", 1)[1].split("private fun requestSafetyCleanup", 1)[0])

    def test_cooldown_is_monotonic_in_memory_and_checked_before_new_session_side_effects(self):
        policy = (REPO / "app/src/main/java/com/chardy/doom/InstagramEntryGate.kt").read_text()
        self.assertIn("INSTAGRAM_ENTRY_COOLDOWN_MS = 60_000L", policy)
        self.assertIn("monotonicNowMs: () -> Long", policy)
        self.assertIn("return nowMs - terminalAt < durationMs", policy)
        admission = policy.split("fun beginInstagramSession()", 1)[1].split("return GateTicket", 1)[0]
        self.assertLess(admission.index("activeDurationMs = durationProvider"), admission.index("activeCooldownDurationMs = cooldownDurationProvider"))
        self.assertLess(admission.index("activeCooldownDurationMs = cooldownDurationProvider"), admission.index("state = if (enabled())"))
        self.assertNotIn("System.currentTimeMillis", SERVICE + policy)
        self.assertEqual(1, SERVICE.count("SystemClock.elapsedRealtime()"))
        event = SERVICE.split("override fun onAccessibilityEvent", 1)[1].split(
            "@Suppress", 1
        )[0]
        instagram = event.split("// Suppression is checked", 1)[1]
        suppression = "if (Observation.gateConsent && entryGate.cooldownActive())"
        self.assertIn(suppression, instagram)
        self.assertLess(instagram.index(suppression), instagram.index("beginInstagramSessionIfEligible"))
        self.assertLess(instagram.index(suppression), instagram.index("eventRoot()"))
        self.assertLess(instagram.index(suppression), instagram.index("Observation.record"))

    def test_cooldown_is_owned_only_by_terminal_policy_operations(self):
        policy = (REPO / "app/src/main/java/com/chardy/doom/InstagramEntryGate.kt").read_text()
        self.assertNotIn("fun admitForDisplay", policy)
        self.assertEqual(2, policy.count("cooldown.recordTerminal(ticket, nowMs, activeCooldownDurationMs)"))
        self.assertIn("activeCooldownDurationMs = cooldownDurationProvider?.invoke()", policy)
        self.assertIn("fun beginMessagesRoute", policy)
        self.assertIn("fun finishMessagesRoute", policy)
        self.assertIn("result == MessagesRouteResult.FAILED", policy)
        install = SERVICE.split("private fun installOverlay", 1)[1].split(
            "private fun runWatchdogTick", 1
        )[0]
        self.assertNotIn("recordTerminal", install)
        self.assertNotIn("admitForDisplay", install)
        self.assertLess(install.index("entryGate.overlayShown(shownAt, activeTicket)"),
                        install.index("renderOverlay(activeTicket, token)"))
        self.assertIn("requestSafetyCleanup(OverlayRemovalAction.BYPASS", install)

    def test_install_callbacks_keep_their_physical_overlay_token(self):
        install = SERVICE.split("private fun installOverlay", 1)[1].split(
            "private fun runWatchdogTick", 1
        )[0]
        self.assertRegex(install, r'OverlayRemovalAction\.NAVIGATE_MESSAGES,\s*\n\s*token')
        self.assertRegex(install, r'OverlayRemovalAction\.HOME, token')
        self.assertRegex(install, r'OverlayRemovalAction\.COMPLETE,\s*\n\s*\s*token')


if __name__ == "__main__":
    unittest.main()
