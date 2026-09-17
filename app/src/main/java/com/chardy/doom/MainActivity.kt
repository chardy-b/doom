@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.chardy.doom

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_DEBUG = "com.chardyb.doom.action.OPEN_DEBUG"
        private const val DEBUG_REQUEST_CONSUMED = "debug_request_consumed"

        fun debugIntent(context: android.content.Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DEBUG
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        private fun isDebugIntent(intent: Intent?): Boolean =
            intent?.action == ACTION_OPEN_DEBUG && intent.data == null && intent.categories.isNullOrEmpty() &&
                intent.extras == null
    }

    private var debugRequestSequence by mutableLongStateOf(0L)
    private var debugRequestPending = false
    private var debugRequestConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugRequestConsumed = savedInstanceState?.getBoolean(DEBUG_REQUEST_CONSUMED, false) == true
        if (isDebugIntent(intent) && !debugRequestConsumed) {
            debugRequestSequence = 1L
            debugRequestPending = true
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = BreathingVisuals.INK
        window.decorView.setBackgroundColor(BreathingVisuals.INK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        Observation.load(this)
        setContent { DoomTheme { DoomScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isDebugIntent(intent)) {
            debugRequestSequence++
            debugRequestPending = true
            debugRequestConsumed = false
        } else {
            // A replaced/malformed intent cannot replay a previously pending destination.
            debugRequestPending = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(DEBUG_REQUEST_CONSUMED, debugRequestConsumed)
        super.onSaveInstanceState(outState)
    }

    internal fun consumeDebugRequest(sequence: Long): Boolean {
        if (!debugRequestPending || sequence != debugRequestSequence) return false
        debugRequestPending = false
        debugRequestConsumed = true
        setIntent(Intent(intent).apply { action = null })
        return true
    }

    internal fun currentDebugRequestSequence(): Long = debugRequestSequence
}

private val Ink = Color(BreathingVisuals.INK)
private val Paper = Color(BreathingVisuals.PAPER)
private val Gold = Color(BreathingVisuals.GOLD)
private val Orange = Color(BreathingVisuals.ORANGE)
private val Panel = Color(BreathingVisuals.PANEL)

@Composable
fun DoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            onBackground = Paper,
            primary = Gold,
            onPrimary = Ink,
            surface = Panel,
            onSurface = Paper,
        ),
        content = content,
    )
}

private enum class Destination { HOME, DEBUG }

@Composable
fun DoomScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val lifecycle = LocalLifecycleOwner.current
    val demoGate = remember { DemoGate() }
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settings by remember { mutableStateOf(ReminderSettingsStore.read(context)) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var demoScreen by remember { mutableStateOf(demoGate.screen) }
    var demoGeneration by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(5_000L) }
    var previewStartedAt by remember { mutableLongStateOf(0L) }
    var previewElapsed by remember { mutableLongStateOf(0L) }
    var productPreview by remember { mutableStateOf(false) }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    var traceFeedback by remember { mutableStateOf<String?>(null) }
    var traceRevision by remember { mutableLongStateOf(0L) }
    var debugReportControlsReady by remember { mutableStateOf(false) }
    var debugScrollRequest by remember { mutableLongStateOf(0L) }
    val homeScroll = rememberScrollState()
    val debugScroll = rememberScrollState()
    val debugReportRequester = remember { BringIntoViewRequester() }
    val debugRequest = activity?.currentDebugRequestSequence() ?: 0L
    val systemStatic = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    fun save(next: ReminderSettings) {
        settings = next
        Observation.updateReminderSettings(context, next)
    }
    fun startProductPreview() {
        previewStartedAt = SystemClock.elapsedRealtime()
        previewElapsed = 0L
        productPreview = true
    }
    fun startDemo() {
        demoGeneration = demoGate.start(SystemClock.elapsedRealtime())
        remaining = 5_000L
        demoScreen = demoGate.screen
    }
    fun leaveDemo(screen: DemoGate.Screen = DemoGate.Screen.HOME) {
        demoGate.leave(screen)
        demoScreen = demoGate.screen
        demoGeneration = demoGate.generation
    }

    LaunchedEffect(debugRequest) {
        if (debugRequest > 0L && activity?.consumeDebugRequest(debugRequest) == true) {
            destination = Destination.DEBUG
            productPreview = false
            leaveDemo()
            Observation.hideReport()
            debugScrollRequest = debugRequest
        }
    }

    LaunchedEffect(debugScrollRequest, debugReportControlsReady, destination) {
        if (debugScrollRequest > 0L && destination == Destination.DEBUG && debugReportControlsReady) {
            debugReportRequester.bringIntoView()
            // Consume the fixed-action request only after the actual scroll container has
            // completed the bring-into-view operation.
            debugScrollRequest = 0L
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    accessibilityEnabled = isAccessibilityServiceEnabled(
                        context,
                        ComponentName(context, DoomAccessibilityService::class.java),
                    )
                    traceRevision++
                }
                Lifecycle.Event.ON_STOP -> {
                    productPreview = false
                    demoGate.background()
                    demoScreen = demoGate.screen
                    demoGeneration = demoGate.generation
                }
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            demoGate.background()
        }
    }
    BackHandler(productPreview || demoScreen != DemoGate.Screen.HOME) {
        productPreview = false
        leaveDemo()
    }
    LaunchedEffect(productPreview, previewStartedAt, settings.durationSeconds) {
        val durationMs = settings.durationSeconds * 1_000L
        while (productPreview) {
            previewElapsed = (SystemClock.elapsedRealtime() - previewStartedAt).coerceAtMost(durationMs)
            if (previewElapsed >= durationMs) productPreview = false
            delay(50L)
        }
    }
    LaunchedEffect(demoGeneration, demoScreen) {
        if (demoScreen == DemoGate.Screen.BREATHING) {
            val token = demoGeneration
            while (demoGate.screen == DemoGate.Screen.BREATHING) {
                val now = SystemClock.elapsedRealtime()
                remaining = demoGate.remaining(now)
                if (demoGate.tick(now, token)) demoScreen = demoGate.screen
                delay(50L)
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(
                    selected = destination == Destination.HOME,
                    onClick = { destination = Destination.HOME; productPreview = false; leaveDemo() },
                    icon = { Text("⌂") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = destination == Destination.DEBUG,
                    onClick = { destination = Destination.DEBUG; productPreview = false; leaveDemo() },
                    icon = { Text("◆") },
                    label = { Text("Debug") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding)) {
            if (productPreview) {
                val durationMs = settings.durationSeconds * 1_000L
                BreathingPreview(
                    BreathingVisuals.frame(previewElapsed, durationMs),
                    reduceMotion || systemStatic,
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(
                        if (destination == Destination.HOME) homeScroll else debugScroll
                    ).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("DOOM", fontSize = 38.sp, color = Gold, fontFamily = FontFamily.Monospace)
                    if (destination == Destination.HOME) {
                        Home(settings, accessibilityEnabled, ::save, ::startProductPreview)
                    } else {
                        Debug(
                            demoScreen,
                            ::startDemo,
                            ::leaveDemo,
                            reduceMotion,
                            { reduceMotion = it },
                            traceRevision,
                            { traceRevision++ },
                            traceFeedback,
                            { traceFeedback = it },
                            remaining,
                            systemStatic,
                            debugReportRequester,
                            { debugReportControlsReady = true },
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun Home(
    settings: ReminderSettings,
    accessibilityEnabled: Boolean,
    save: (ReminderSettings) -> Unit,
    preview: () -> Unit,
) {
    val context = LocalContext.current
    Text("A quiet pause before the scroll.", fontSize = 20.sp, color = Paper)
    Frame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.enabled,
                onCheckedChange = { save(settings.copy(enabled = it)) },
                modifier = Modifier.semantics { contentDescription = "Breathing reminders" },
            )
            Text("Breathing reminders", Modifier.padding(start = 12.dp), color = Paper, fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = Observation.sessionTimerEnabled,
                onCheckedChange = { Observation.setSessionTimerEnabled(context, it) },
                modifier = Modifier
                    .testTag("instagram_session_timer_switch")
                    .semantics { contentDescription = "Instagram session timer" },
            )
            Text("Instagram session timer", Modifier.padding(start = 12.dp), color = Paper, fontSize = 18.sp)
        }
        Text(if (Observation.sessionTimerEnabled) "Enabled" else "Disabled", color = Gold)
        Text(if (accessibilityEnabled) "Accessibility: Enabled" else "Accessibility: Not enabled", color = Paper)
        Action("Open Accessibility Settings") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    DurationSettings(settings, save)
    SuppressionSettings(settings, save)
    Frame {
        Text("Preview", color = Gold, fontFamily = FontFamily.Monospace)
        Action("Preview breathing reminder", onClick = preview)
    }
}

@Composable
private fun DurationSettings(settings: ReminderSettings, save: (ReminderSettings) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    Frame {
        Text("Duration", color = Gold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReminderSettingsStore.durationPresets.forEach { value ->
                FilterChip(
                    selected = settings.durationSeconds == value,
                    onClick = { save(settings.copy(durationSeconds = value)) },
                    label = { Text("${value}s") },
                )
            }
            FilterChip(
                selected = settings.durationSeconds !in ReminderSettingsStore.durationPresets,
                onClick = { raw = ""; showDialog = true },
                label = { Text("Custom") },
            )
        }
    }
    if (showDialog) {
        CustomDialog(
            title = "Custom duration",
            label = "Seconds",
            raw = raw,
            change = { raw = it },
            parse = { ReminderSettingsStore.normalizeDuration(raw) },
            cancel = { showDialog = false },
            save = { save(settings.copy(durationSeconds = it)); showDialog = false },
        )
    }
}

@Composable
private fun SuppressionSettings(settings: ReminderSettings, save: (ReminderSettings) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    Frame {
        Text("Suppression", color = Gold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReminderSettingsStore.suppressionPresets.forEach { value ->
                FilterChip(
                    selected = settings.suppressionMinutes == value,
                    onClick = { save(settings.copy(suppressionMinutes = value)) },
                    label = { Text("${value}m") },
                )
            }
            FilterChip(
                selected = settings.suppressionMinutes !in ReminderSettingsStore.suppressionPresets,
                onClick = { raw = ""; showDialog = true },
                label = { Text("Custom") },
            )
        }
    }
    if (showDialog) {
        CustomDialog(
            title = "Custom suppression",
            label = "Minutes",
            raw = raw,
            change = { raw = it },
            parse = { ReminderSettingsStore.validSuppression(raw) },
            cancel = { showDialog = false },
            save = { save(settings.copy(suppressionMinutes = it)); showDialog = false },
        )
    }
}

@Composable
private fun CustomDialog(
    title: String,
    label: String,
    raw: String,
    change: (String) -> Unit,
    parse: () -> Int?,
    cancel: () -> Unit,
    save: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = { OutlinedTextField(raw, change, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { parse()?.let(save) }, enabled = parse() != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}

@Composable
private fun Debug(
    demoScreen: DemoGate.Screen,
    startDemo: () -> Unit,
    leaveDemo: (DemoGate.Screen) -> Unit,
    reduceMotion: Boolean,
    setReduceMotion: (Boolean) -> Unit,
    traceRevision: Long,
    refreshTrace: () -> Unit,
    feedback: String?,
    setFeedback: (String) -> Unit,
    remaining: Long,
    systemStatic: Boolean,
    reportRequester: BringIntoViewRequester,
    onReportAnchorReady: () -> Unit,
) {
    val context = LocalContext.current
    Frame {
        Text("DOOM-OWNED QUICK DEMO", color = Gold)
        when (demoScreen) {
            DemoGate.Screen.HOME -> {
                Action("Try the breathing demo", onClick = startDemo)
                Action("Demo messages — no wait") { leaveDemo(DemoGate.Screen.MESSAGES) }
            }
            DemoGate.Screen.BREATHING -> {
                Text("Take a breath.", color = Paper, fontSize = 30.sp)
                PixelBloom(
                    if (reduceMotion || systemStatic) BreathingVisuals.staticProgress()
                    else 1f - remaining / 5_000f
                )
                Text("Breathe naturally. No need to hold.", color = Paper)
                Text("${(remaining + 999L) / 1_000L}s remaining", color = Gold)
                Action("DEMO MESSAGES — NO WAIT") { leaveDemo(DemoGate.Screen.MESSAGES) }
                Action("LEAVE DEMO") { leaveDemo(DemoGate.Screen.HOME) }
            }
            DemoGate.Screen.MESSAGES -> {
                Text("Messages stay open.", color = Paper, fontSize = 28.sp)
                Text("The pause was cancelled immediately. This is a simulated inbox, not your Instagram messages.", color = Paper)
                Action("Try the breathing demo", onClick = startDemo)
                Action("BACK TO DOOM") { leaveDemo(DemoGate.Screen.HOME) }
            }
            DemoGate.Screen.FEED -> {
                Text("A deliberate start.", color = Paper, fontSize = 28.sp)
                Text("Five visible seconds completed. In the finished app, a scrolling session would begin here. Live Instagram limits are not implemented yet.", color = Paper)
                Action("BACK TO DOOM") { leaveDemo(DemoGate.Screen.HOME) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = reduceMotion,
                onCheckedChange = setReduceMotion,
                modifier = Modifier.semantics { contentDescription = "Reduce breathing motion" },
            )
            Text("Still image · demo-local reduced motion", color = Paper)
        }
    }
    Frame {
        Text("INSTAGRAM · NOT PROTECTED", color = Gold, fontFamily = FontFamily.Monospace)
        Text(
            when {
                !Observation.consent -> "Observation off · consent required"
                !Observation.connected -> "Observation off · service disconnected"
                else -> "Observer connected · mapping unverified"
            },
            color = Paper,
            fontSize = 18.sp,
        )
        Text("Diagnostic entry breathing gate · OFF by default. Unverified; does not claim protection. This diagnostic can pause DM notification entry and is not rollout-ready. Only completion or a successful exact Messages result starts cooldown; display, cancellation, Leave, and failed routing do not.", color = Orange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = Observation.gateConsent,
                onCheckedChange = { Observation.setGateConsent(context, it) },
                modifier = Modifier.semantics { contentDescription = "Diagnostic Instagram entry gate opt in" },
            )
            Text("Allow diagnostic Instagram entry pause", color = Paper, modifier = Modifier.weight(1f))
        }
        Text("This consent is separate from the sanitized report consent. It does not protect, block, or control Instagram.", color = Paper)
        Text("Entry gate state: ${Observation.entryGateState}", color = Gold)
        Text("An admitted reminder uses the selected duration snapshot; terminal successes use the admitted cooldown snapshot.", color = Orange)
        Text(
            "SANITIZED STRUCTURAL REPORT",
            color = Gold,
            modifier = Modifier
                .testTag("debug_report_controls")
                .bringIntoViewRequester(reportRequester)
                .onGloballyPositioned { onReportAnchorReady() },
        )
        Text("Structure changes with scrolling and content. The sanitized report is separate from a diagnostic shadow prediction; neither blocks, protects, or controls actions; the optional entry pause is default-off and fail-open.", color = Paper)
        Text("Optional accessibility access can expose screen content to an app. Doom receives package identifiers for window events from all apps and, during a visible gate, reads only one active root's package attribution to decide whether the gate remains in Instagram; it reads no foreign window tree. With fresh v2 report consent and a connected observer, even when the optional gate is off, Doom traverses only Instagram: at most 128 nodes breadth-first through depth 8. It keeps sanitized resource/class identifiers, Doom-local parent and sibling indexes, bounded screen/window geometry, normalized screen bounds, sibling-relative drawing order, fixed action names, collection/item/range tuples, named numeric fields and closed boolean masks. A public unique ID is kept only when it equals an already accepted resource ID; other values become u:free_form. Unsupported, absent, invalid, unsafe and capped values use closed u:* markers. Reports have at most 64 tokens and 8,192 final ASCII bytes, emit whole rows and mark omitted structure truncated.", color = Paper)
        Text("Reports expose static Instagram resource names and class identifiers as structural metadata only, never UI text/content/account values. No text, descriptions, hints, errors, pane or tooltip titles, notification or account content, raw trees, framework objects, arbitrary/free-form IDs or private screenshots are collected. Bounds and named scalar metadata are the narrow v2 exception; at most 64 unique tokens and 8,192 final ASCII bytes, including the complete header, are retained. Rich rows can reduce practical capacity below 128, and metadata never selects targets or drives actions. Fresh v2 report consent is required; one report stays in process memory with no file persistence, logging, network, upload or automatic export.", color = Paper)
        Text("The 8,192-byte cap includes the final header and comma-separated truncation reasons. Rich rows can make practical capacity smaller than 128 nodes; only complete rows are emitted.", color = Orange)
        Text("Clear removes the report and reveal/copy state; a later Instagram event may create a new hidden report. Returning directly to Doom preserves the latest hidden report for local review. A SystemUI, IME, or other foreign transition between Debug launch and verified Doom return may clear this process-only report; consenting-phone evidence is required for real behavior. Other foreign apps, revocation, observer stop, disconnect, interruption, reconnect and process death clear that state. A delayed Instagram event with a wrong or missing root invalidates it.", color = Paper)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = Observation.consent,
                onCheckedChange = { Observation.accept(context, it) },
                modifier = Modifier.semantics { contentDescription = "Consent to sanitized structural report" },
            )
            Text("Allow sanitized structural report", color = Paper, modifier = Modifier.weight(1f))
        }
        Action("OPEN ACCESSIBILITY SETTINGS", enabled = Observation.consent) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val report = Observation.report
        Text(
            when {
                report == null -> "No report. Open Instagram yourself after enabling the observer, then return here."
                report.truncated -> "Report available · truncated"
                else -> "Report available · bounded traversal complete"
            },
            color = Paper,
        )
        Text("Shadow prediction: ${if (report == null) "UNKNOWN" else Observation.shadowPrediction}", color = Gold)
        Text("Diagnostic only — may show an optional entry pause; never protects or controls Instagram.", color = Paper)
        Action("REVEAL LOCAL REPORT", enabled = Observation.canReveal) { Observation.revealReport() }
        if (Observation.canReveal && Observation.revealed && report != null) {
            Text(report.text, color = Paper, fontFamily = FontFamily.Monospace)
        }
        Text("Copy leaves Doom process memory and enters the system clipboard. Review the revealed report before copying; upload privately, then clear the clipboard. Clearing or stopping Doom cannot recall copies outside the app.", color = Paper)
        Action("COPY REVIEWED REPORT", enabled = Observation.canCopy) { Observation.copyReport(context) }
        if (Observation.copied) Text("Copied to system clipboard. Upload privately, then clear the clipboard.", color = Paper)
        Action("CLEAR REPORT") { Observation.clear() }
        Action("STOP OBSERVATION") { Observation.accept(context, false) }

        val availability = remember(traceRevision) { RemovalTraceStore.process.availability() }
        Text("REMOVAL TRACE · ${availability.name}", color = Gold)
        Text("The optional trace records one armed next gate episode in process memory only. It stores closed categories and monotonic offsets, never content, package strings, event integers, class names, node data, or identifiers. Arming expires after 120 seconds; a frozen trace expires after 10 minutes. Revoking either consent or clearing destroys it.", color = Paper)
        Text(
            when {
                !Observation.consent || !Observation.gateConsent -> "Trace unavailable · both observation consents are required"
                availability == RemovalTraceAvailability.ARMED -> "Trace armed · waiting for one eligible episode"
                availability == RemovalTraceAvailability.RECORDING -> "Trace recording · one episode only"
                availability == RemovalTraceAvailability.AVAILABLE -> "Trace available · process-local evidence"
                availability == RemovalTraceAvailability.EXPIRED -> "Trace expired · arm a new episode"
                else -> "No trace armed"
            },
            color = Paper,
        )
        Action(
            "ARM NEXT REMOVAL TRACE",
            enabled = Observation.consent && Observation.gateConsent &&
                availability != RemovalTraceAvailability.RECORDING &&
                availability != RemovalTraceAvailability.AVAILABLE,
        ) {
            if (RemovalTraceStore.process.arm()) setFeedback("Armed for the next eligible removal episode.")
            refreshTrace()
        }
        Text("ARM applies to the next eligible episode; terminal successes use the admitted cooldown snapshot.", color = Paper)
        Action("REFRESH TRACE STATUS") {
            setFeedback("Removal trace status refreshed.")
            refreshTrace()
        }
        Action("COPY REMOVAL TRACE", enabled = availability == RemovalTraceAvailability.AVAILABLE) {
            setFeedback(
                when (Observation.copyRemovalTrace(context)) {
                    Observation.RemovalTraceCopyResult.COPIED -> "Copied removal trace to the system clipboard."
                    Observation.RemovalTraceCopyResult.UNAVAILABLE -> "Removal trace unavailable."
                }
            )
            refreshTrace()
        }
        Action("CLEAR REMOVAL TRACE") {
            RemovalTraceStore.process.clear()
            setFeedback("Removal trace cleared.")
            refreshTrace()
        }
        feedback?.let { Text(it, color = Gold) }
        Text("Arm immediately before reproducing one episode. If no report is available, do not file structural conclusions. Review a trace or report before copying; clipboard data leaves Doom and must be cleared separately.", color = Paper)
    }
    Text("DEVICE PROOF · PENDING", color = Gold)
    Text("No verified Instagram screen mapping or DM route is claimed. This diagnostic does not protect you from scrolling and is not rollout-ready. Disable or uninstall at any time.", color = Paper)
    Text("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})", color = Gold, fontFamily = FontFamily.Monospace)
}

@Composable
private fun BreathingPreview(frame: BreathingFrame, reduced: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(frame.label, color = Paper, fontSize = 34.sp)
        PixelBloom(if (reduced) BreathingVisuals.staticProgress() else frame.bloom, Modifier.weight(1f))
        SegmentedProgress(frame.segments)
    }
}

@Composable
private fun SegmentedProgress(segments: List<Float>) {
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { fraction ->
            Box(Modifier.weight(1f).fillMaxHeight().background(Panel)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Gold))
            }
        }
    }
}

@Composable
private fun PixelBloom(progress: Float, modifier: Modifier = Modifier.height(180.dp)) {
    Canvas(modifier.fillMaxWidth().semantics { contentDescription = "A quiet pixel bloom" }) {
        BreathingVisuals.geometry(progress, size.width, size.height).forEach { cell ->
            drawRect(
                Color(cell.color),
                Offset(cell.left, cell.top),
                Size(cell.right - cell.left, cell.bottom - cell.top),
                alpha = cell.alpha,
            )
        }
    }
}

@Composable
private fun Frame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel).border(1.dp, Gold.copy(alpha = 0.8f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace)
    }
}
