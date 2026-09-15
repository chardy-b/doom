package com.chardy.doom

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Observation.load(this)
        setContent { DoomTheme { DoomScreen() } }
    }
}

private val Ink=Color(BreathingVisuals.INK); private val Paper=Color(BreathingVisuals.PAPER)
private val Gold=Color(BreathingVisuals.GOLD); private val Orange=Color(BreathingVisuals.ORANGE)
private val Panel=Color(BreathingVisuals.PANEL)
@Composable fun DoomTheme(content:@Composable ()->Unit) = MaterialTheme(
    colorScheme=darkColorScheme(background=Ink,onBackground=Paper,primary=Gold,onPrimary=Ink,surface=Panel,onSurface=Paper), content=content)

private enum class Destination { HOME, DEBUG }
private enum class DemoScreen { NONE, BREATHING, MESSAGES, COMPLETE }

@Composable fun DoomScreen() {
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settings by remember { mutableStateOf(ReminderSettingsStore.read(context)) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(DemoScreen.NONE) }
    var previewStartedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    var traceFeedback by remember { mutableStateOf<String?>(null) }
    var traceRevision by remember { mutableLongStateOf(0) }
    val systemStatic=remember { Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f }
    val durationMs=settings.durationSeconds*1_000L
    fun save(next:ReminderSettings){ settings=next; Observation.updateReminderSettings(context,next) }
    fun startPreview(){ previewStartedAt=SystemClock.elapsedRealtime(); elapsed=0; demo=DemoScreen.BREATHING }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> { accessibilityEnabled=isAccessibilityServiceEnabled(context,ComponentName(context,DoomAccessibilityService::class.java)); traceRevision++ }
            Lifecycle.Event.ON_STOP -> if(demo==DemoScreen.BREATHING) demo=DemoScreen.NONE
            else -> Unit
        }}
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(demo,previewStartedAt,durationMs) { while(demo==DemoScreen.BREATHING) { elapsed=(SystemClock.elapsedRealtime()-previewStartedAt).coerceAtMost(durationMs); if(elapsed>=durationMs) demo=DemoScreen.COMPLETE; delay(32) } }
    Scaffold(
        bottomBar={ NavigationBar(containerColor=Panel) {
            NavigationBarItem(selected=destination==Destination.HOME,onClick={ destination=Destination.HOME; demo=DemoScreen.NONE },icon={ Text("⌂") },label={ Text("Home") })
            NavigationBarItem(selected=destination==Destination.DEBUG,onClick={ destination=Destination.DEBUG; demo=DemoScreen.NONE },icon={ Text("◆") },label={ Text("Debug") })
        }}
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding).safeDrawingPadding()) {
            if(demo==DemoScreen.BREATHING) BreathingPreview(BreathingVisuals.frame(elapsed,durationMs),reduceMotion||systemStatic)
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("DOOM",fontSize=38.sp,color=Gold,fontFamily=FontFamily.Monospace)
                if(destination==Destination.HOME) Home(settings,accessibilityEnabled,::save,::startPreview)
                else Debug(demo,{ demo=it },::startPreview,reduceMotion,{ reduceMotion=it },traceRevision,{traceRevision++},traceFeedback,{traceFeedback=it})
            }
        }
    }
    BackHandler(screen != DemoGate.Screen.HOME) { leave() }
    LaunchedEffect(generation, screen) {
        if (screen == DemoGate.Screen.BREATHING) {
            val token = generation
            while (gate.screen == DemoGate.Screen.BREATHING) {
                remaining = gate.remaining(SystemClock.elapsedRealtime())
                if (gate.tick(SystemClock.elapsedRealtime(), token)) screen = gate.screen
                delay(50)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("DOOM", fontSize = 38.sp, color = Jade, fontFamily = FontFamily.Monospace)
            Text("FIELD\nNOTES / 00", color = Paper, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text("A quiet pause before the scroll.", fontSize = 18.sp, color = Paper)
        Text("WAVE 0 · DIAGNOSTIC ONLY", color = Jade, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        when (screen) {
            DemoGate.Screen.HOME -> {
                Frame {
                    Text("THE QUIET ROOM", color = Jade, fontFamily = FontFamily.Monospace)
                    Text("Try a small pause.", fontSize = 27.sp, color = Paper)
                    Text("This demo stays inside Doom. Instagram is not blocked or controlled.", color = Paper)
                    Action("TRY THE BREATHING DEMO") {
                        generation = gate.start(SystemClock.elapsedRealtime())
                        remaining = 5_000
                        screen = gate.screen
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = reduceMotion, onCheckedChange = { reduceMotion = it }, modifier = Modifier.semantics { contentDescription = "Reduce breathing motion" })
                    Text("Still image · demo-local reduced motion", color = Paper, modifier = Modifier.padding(start = 12.dp))
                }
                Frame {
                    Text("INSTAGRAM · NOT PROTECTED", color = Jade, fontFamily = FontFamily.Monospace)
                    Text(when {
                        !Observation.consent -> "Observation off · consent required"
                        !Observation.connected -> "Observation off · service disconnected"
                        else -> "Observer connected · mapping unverified"
                    }, color = Paper, fontSize = 18.sp)
                    Text("Diagnostic entry breathing gate · OFF by default. Unverified; does not claim protection. Only completion or a successful exact Messages result starts the one-minute in-memory cooldown; display, Leave, and failed routing do not.", color = Rust)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = Observation.gateConsent, onCheckedChange = { Observation.setGateConsent(context, it) }, modifier = Modifier.semantics { contentDescription = "Diagnostic Instagram entry gate opt in" })
                        Text("Allow diagnostic Instagram entry pause", color = Paper, modifier = Modifier.weight(1f))
                    }
                    Text("This consent is separate from the sanitized report consent and is the only persisted gate setting.", color = Paper, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = Observation.sessionTimerEnabled,
                            onCheckedChange = { Observation.setSessionTimerEnabled(context, it) },
                            modifier = Modifier.testTag("instagram_session_timer_switch").semantics {
                                contentDescription = "Instagram session timer"
                            }
                        )
                        Text("Instagram session timer", color = Paper, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    }
                    Text(if (Observation.sessionTimerEnabled) "Enabled" else "Disabled", color = Jade)
                    Text("Entry gate state: ${Observation.entryGateState}", color = Jade, fontFamily = FontFamily.Monospace)
                    val traceAvailability = remember(traceRevision) {
                        RemovalTraceStore.process.availability()
                    }
                    Text("REMOVAL TRACE · ${traceAvailability.name}", color = Jade, fontFamily = FontFamily.Monospace)
                    Text("Optional trace records one armed next gate episode in process memory only. It stores closed categories and monotonic offsets, never content, package strings, event integers, class names, node data or identifiers. Arming expires after 120 seconds; a frozen trace expires after 10 minutes. Revoking either consent or clearing destroys it.", color = Paper, fontSize = 12.sp)
                    Text(when {
                        !Observation.consent || !Observation.gateConsent -> "Trace unavailable · both observation consents are required"
                        traceAvailability == RemovalTraceAvailability.ARMED -> "Trace armed · waiting for one eligible episode"
                        traceAvailability == RemovalTraceAvailability.RECORDING -> "Trace recording · one episode only"
                        traceAvailability == RemovalTraceAvailability.AVAILABLE -> "Trace available · process-local evidence"
                        traceAvailability == RemovalTraceAvailability.EXPIRED -> "Trace expired · arm a new episode"
                        else -> "No trace armed"
                    }, color = Paper)
                    Action(
                        "ARM NEXT REMOVAL TRACE",
                        enabled = Observation.consent && Observation.gateConsent &&
                            traceAvailability != RemovalTraceAvailability.RECORDING &&
                            traceAvailability != RemovalTraceAvailability.AVAILABLE
                    ) {
                        if (RemovalTraceStore.process.arm()) {
                            traceFeedback = "Armed for the next eligible removal episode."
                            refreshTrace()
                        }
                    }
                    Text("ARM applies to the next eligible episode; terminal successes keep the unchanged 60-second cooldown.", color = Paper, fontSize = 12.sp)
                    Action("REFRESH TRACE STATUS") { refreshTrace() }
                    Action("COPY REMOVAL TRACE", enabled = traceAvailability == RemovalTraceAvailability.AVAILABLE) {
                        traceFeedback = when (Observation.copyRemovalTrace(context)) {
                            Observation.RemovalTraceCopyResult.COPIED -> "Copied removal trace to the system clipboard."
                            Observation.RemovalTraceCopyResult.UNAVAILABLE -> "Removal trace unavailable."
                        }
                        refreshTrace()
                    }
                    Action("CLEAR REMOVAL TRACE") {
                        RemovalTraceStore.process.clear()
                        traceFeedback = "Removal trace cleared."
                        refreshTrace()
                    }
                    traceFeedback?.let { Text(it, color = Jade, fontSize = 12.sp) }
                    Text("SANITIZED STRUCTURAL REPORT", color = Jade)
                    Text("Structure changes with scrolling and content. The sanitized report is separate from a diagnostic shadow prediction; neither blocks, protects, or controls actions; the optional entry pause is default-off and fail-open.", color = Paper)
                    Text("Optional accessibility access can expose screen content to an app. Doom receives package identifiers for window events from all apps and, during a visible gate, reads only one active root's package attribution to decide whether the gate remains in Instagram; it reads no foreign window tree. With fresh report consent, Doom traverses only Instagram: at most 128 nodes through depth 8. It keeps only sanitized static Instagram resource names from compile-time resource tables, normalized safe class names, depth, child count capped at 16, and clickable/scrollable/editable/selected/checked booleans in sorted aggregate rows. Previously unknown resource names are admitted only as exact com.instagram.android:id/ names: 1–64 lowercase ASCII letters/digits/underscores, starting with a letter, at most 96 raw characters. Invalid IDs and unknown classes are omitted. Reports have at most 64 unique tokens and 8,192 ASCII characters/UTF-8 bytes; omitted structure is marked truncated.", color = Paper)
                    Text("Reports expose static resource names, never UI text/content/account values. No text, descriptions, hints, errors, pane or tooltip titles, bounds, screenshots, notification or account content, node/window IDs, raw trees or actions are collected. Only consent is saved. One report stays in process memory; no file persistence, logging, network or automatic export.", color = Paper)
                    Text("Clear removes the report and reveal/copy state; a later Instagram event may create a new hidden report. Returning directly to Doom preserves the latest hidden report for local review. Other foreign apps, revocation, observer stop, disconnect, interruption, reconnect and process death clear that state. A delayed Instagram event with a wrong or missing root invalidates it.", color = Paper)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = Observation.consent, onCheckedChange = { Observation.accept(context, it) }, modifier = Modifier.semantics { contentDescription = "Consent to sanitized structural report" })
                        Text("Allow sanitized structural report", color = Paper, modifier = Modifier.weight(1f))
                    }
                    Action("OPEN ACCESSIBILITY SETTINGS", enabled = Observation.consent) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    val report = Observation.report
                    Text(when {
                        report == null -> "No report. Open Instagram yourself after enabling the observer, then return here."
                        report.truncated -> "Report available · truncated"
                        else -> "Report available · bounded traversal complete"
                    }, color = Paper)
                    Text("Shadow prediction: ${if (report == null) "UNKNOWN" else Observation.shadowPrediction}", color = Jade, fontFamily = FontFamily.Monospace)
                    Text("Diagnostic only — may show an optional entry pause; never protects or controls Instagram.", color = Paper, fontSize = 12.sp)
                    Action("REVEAL LOCAL REPORT", enabled = Observation.canReveal) { Observation.revealReport() }
                    if (Observation.canReveal && Observation.revealed && report != null) {
                        Text(report.text, color = Paper, fontFamily = FontFamily.Monospace)
                    }
                    Text("Copy leaves Doom process memory and enters the system clipboard. Review the revealed report before copying; upload privately, then clear the clipboard. Clearing or stopping Doom cannot recall copies outside the app.", color = Paper)
                    Action("COPY REVIEWED REPORT", enabled = Observation.canCopy) { Observation.copyReport(context) }
                    if (Observation.copied) {
                        Text("Copied to system clipboard. Upload privately, then clear the clipboard.", color = Paper)
                    }
                    Action("CLEAR REPORT") { Observation.clear() }
                    Action("STOP OBSERVATION") { Observation.accept(context, false) }
                }
            }
            DemoGate.Screen.BREATHING -> {
                Frame {
                    Text("DOOM-OWNED DEMO", color = Jade, fontFamily = FontFamily.Monospace)
                    Text("Take a breath.", color = Paper, fontSize = 30.sp)
                    PixelBloom(if (reduceMotion || systemStatic) 0.5f else (1f - remaining / 5_000f))
                    Text("Breathe naturally. No need to hold.", color = Paper)
                    Text("${(remaining + 999) / 1000}s remaining", color = Jade, fontSize = 22.sp)
                    Action("DEMO MESSAGES — NO WAIT") { leave(DemoGate.Screen.MESSAGES) }
                    Action("LEAVE DEMO") { leave() }
                }
            }
            DemoGate.Screen.MESSAGES -> Frame {
                Text("DOOM-OWNED DEMO", color = Jade, fontFamily = FontFamily.Monospace)
                Text("Messages stay open.", color = Paper, fontSize = 28.sp)
                Text("The pause was cancelled immediately. This is a simulated inbox, not your Instagram messages.", color = Paper)
                Action("BACK TO DOOM") { leave() }
            }
            DemoGate.Screen.FEED -> Frame {
                Text("DOOM-OWNED DEMO", color = Jade, fontFamily = FontFamily.Monospace)
                Text("A deliberate start.", color = Paper, fontSize = 28.sp)
                Text("Five visible seconds completed. In the finished app, a scrolling session would begin here. Live Instagram limits are not implemented yet.", color = Paper)
                Action("BACK TO DOOM") { leave() }
            }
        }
            Text("DEVICE PROOF · PENDING", color = Jade, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("No verified Instagram screen mapping or DM route is claimed. Any verified Instagram entry, including DM or unknown surfaces, may receive the temporary five-second diagnostic pause. Do not rely on it to limit scrolling. Disable or uninstall at any time.", color = Paper, fontSize = 14.sp)
        }
        Text(
            "Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
            color = Jade,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

@Composable private fun Home(settings:ReminderSettings, accessibilityEnabled:Boolean, save:(ReminderSettings)->Unit, preview:()->Unit) {
    val context=LocalContext.current
    Text("A quiet pause before the scroll.",fontSize=20.sp,color=Paper)
    Frame {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(settings.enabled,{save(settings.copy(enabled=it))},Modifier.semantics { contentDescription="Breathing reminders" })
            Text("Breathing reminders",Modifier.padding(start=12.dp),color=Paper,fontSize=18.sp)
        }
        Text(if(accessibilityEnabled) "Accessibility: Enabled" else "Accessibility: Not enabled",color=Paper)
        Action("Open Accessibility Settings") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
    DurationSettings(settings,save)
    SuppressionSettings(settings,save)
    Frame { Text("Preview",color=Gold,fontFamily=FontFamily.Monospace); Action("Preview breathing reminder",onClick=preview) }
}

@Composable private fun DurationSettings(settings:ReminderSettings,save:(ReminderSettings)->Unit) {
    var dialog by remember { mutableStateOf(false) }; var raw by remember { mutableStateOf("") }
    Frame { Text("Duration",color=Gold); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf(10,20,30).forEach { value -> FilterChip(selected=settings.durationSeconds==value,onClick={save(settings.copy(durationSeconds=value))},label={Text("${value}s")}) }
        FilterChip(selected=settings.durationSeconds !in listOf(10,20,30),onClick={raw="";dialog=true},label={Text("Custom")})
    }}
    if(dialog) CustomDialog("Custom duration","Seconds",raw,{raw=it},{ReminderSettingsStore.normalizeDuration(raw)},{dialog=false}) { value -> save(settings.copy(durationSeconds=value));dialog=false }
}
@Composable private fun SuppressionSettings(settings:ReminderSettings,save:(ReminderSettings)->Unit) {
    var dialog by remember { mutableStateOf(false) }; var raw by remember { mutableStateOf("") }
    Frame { Text("Suppression",color=Gold); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf(1,5,15).forEach { value -> FilterChip(selected=settings.suppressionMinutes==value,onClick={save(settings.copy(suppressionMinutes=value))},label={Text("${value}m")}) }
        FilterChip(selected=settings.suppressionMinutes !in listOf(1,5,15),onClick={raw="";dialog=true},label={Text("Custom")})
    }}
    if(dialog) CustomDialog("Custom suppression","Minutes",raw,{raw=it},{ReminderSettingsStore.validSuppression(raw)},{dialog=false}) { value -> save(settings.copy(suppressionMinutes=value));dialog=false }
}
@Composable private fun CustomDialog(title:String,label:String,raw:String,change:(String)->Unit,parse:()->Int?,cancel:()->Unit,save:(Int)->Unit) {
    AlertDialog(onDismissRequest=cancel,title={Text(title)},text={OutlinedTextField(raw,change,label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={parse()?.let(save)},enabled=parse()!=null){Text("Save")}},dismissButton={TextButton(onClick=cancel){Text("Cancel")}})
}

@Suppress("UNUSED_PARAMETER")
@Composable private fun Debug(demo:DemoScreen,setDemo:(DemoScreen)->Unit,preview:()->Unit,reduceMotion:Boolean,setReduce:(Boolean)->Unit,traceRevision:Long,refresh:()->Unit,feedback:String?,setFeedback:(String)->Unit) {
    val context=LocalContext.current
    Frame { Text("DOOM-OWNED QUICK DEMO",color=Gold); Action("Try the breathing demo",onClick=preview); Action("Demo messages — no wait"){setDemo(DemoScreen.MESSAGES)}
        if(demo==DemoScreen.MESSAGES) Text("Messages stay open. This is a simulated inbox.",color=Paper)
        if(demo==DemoScreen.COMPLETE) Text("A deliberate start.",color=Paper)
        Row(verticalAlignment=Alignment.CenterVertically){Switch(reduceMotion,setReduce,Modifier.semantics{contentDescription="Use a still bloom in the Doom-owned demo"});Text("Still image · demo-local reduced motion",color=Paper)} }
    Frame {
        Text("INSTAGRAM · DIAGNOSTIC ONLY",color=Gold)
        Text(if(Observation.connected) "Observation service: Connected" else "Observation service: Disconnected",color=Paper)
        Text("INSTAGRAM · NOT PROTECTED",color=Gold,fontFamily=FontFamily.Monospace)
        Text("Diagnostic entry breathing gate · OFF by default. Unverified; does not claim protection. This diagnostic can pause DM notification entry and is not rollout-ready. Only completion or a successful exact Messages result starts cooldown; display, cancellation, Leave, and failed routing do not.",color=Orange)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(Observation.gateConsent,{Observation.setGateConsent(context,it)},Modifier.semantics{contentDescription="Allow the separate diagnostic Instagram entry pause"});Text("Allow diagnostic Instagram entry pause · enables only the default-off temporary overlay",color=Paper,modifier=Modifier.weight(1f))}
        Text("This consent is separate from sanitized report consent. It does not protect, block, or control Instagram.",color=Paper)
        Text("Entry gate state: ${Observation.entryGateState}",color=Gold)
        Text("An admitted reminder uses the selected duration snapshot; subsequent reminders are suppressed for the selected cooldown snapshot.",color=Orange)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(Observation.consent,{Observation.accept(context,it)},Modifier.semantics{contentDescription="Allow collection of one bounded sanitized structural report"});Text("Allow sanitized structural report · enables only bounded Instagram structure collection",color=Paper,modifier=Modifier.weight(1f))}
        Text("Accessibility access receives package identifiers for window events from all apps. During a visible gate Doom may read one active root's package attribution, but it does not read a foreign window tree. With fresh report consent, Doom traverses only Instagram and retains sanitized static Instagram resource names from compile-time resource tables, safe class names, depth, child counts and closed booleans. Reports contain at most 128 nodes through depth 8, 64 unique tokens, and 8,192 ASCII/UTF-8 bytes; omissions are marked truncated. Previously unknown names are accepted only under the exact lowercase com.instagram.android:id grammar.",color=Paper)
        Text("Reports expose static resource names, never UI text or account values. Doom never collects descriptions, hints, errors, pane or tooltip titles, bounds, screenshots, notification content, node/window IDs, raw trees or report actions. Reports remain process-local with no file persistence, logging, network, or automatic export.",color=Paper)
        Text("Returning directly to Doom may preserve a hidden report for local review. Foreign apps, clear, revoke, stop, disconnect, interruption, reconnect, missing/wrong roots, and process death discard report state. Every report requires explicit reveal before explicit clipboard copy.",color=Paper)
        val availability=remember(traceRevision){RemovalTraceStore.process.availability()}
        Text("REMOVAL TRACE · ${availability.name}",color=Gold)
        Text("The optional trace records one armed next gate episode in process memory only. It stores closed categories and monotonic offsets, never content, package strings, event integers, class names, node data, or identifiers. Arming expires after 120 seconds; a frozen trace expires after 10 minutes. Revoking either consent or clearing destroys it.",color=Paper)
        Text(when{!Observation.consent||!Observation.gateConsent->"Trace unavailable · both observation consents are required";availability==RemovalTraceAvailability.ARMED->"Trace armed · waiting for one eligible episode";availability==RemovalTraceAvailability.RECORDING->"Trace recording · one episode only";availability==RemovalTraceAvailability.AVAILABLE->"Trace available · process-local evidence";availability==RemovalTraceAvailability.EXPIRED->"Trace expired · arm a new episode";else->"No trace armed"},color=Paper)
        Action("ARM NEXT REMOVAL TRACE",Observation.consent&&Observation.gateConsent){RemovalTraceStore.process.arm();refresh()}
        Text("ARM applies to the next eligible episode; terminal successes use the admitted cooldown snapshot.",color=Paper)
        Action("REFRESH TRACE STATUS",onClick=refresh)
        Action("COPY REMOVAL TRACE",availability==RemovalTraceAvailability.AVAILABLE){setFeedback(Observation.copyRemovalTrace(context).name);refresh()}
        Action("CLEAR REMOVAL TRACE"){RemovalTraceStore.process.clear();setFeedback("Removal trace cleared");refresh()}; feedback?.let{Text(it,color=Gold)}
    }
    Frame { Text("SANITIZED STRUCTURAL REPORT",color=Gold); val report=Observation.report
        Text(if(report==null)"No report." else if(report.truncated)"Report available · truncated" else "Report available · bounded traversal complete",color=Paper)
        Text("Shadow prediction: ${if(report==null)"UNKNOWN" else Observation.shadowPrediction}",color=Gold)
        Action("REVEAL LOCAL REPORT",Observation.canReveal){Observation.revealReport()}; if(Observation.revealed&&report!=null)Text(report.text,color=Paper,fontFamily=FontFamily.Monospace)
        Action("COPY REVIEWED REPORT",Observation.canCopy){Observation.copyReport(context)}; Action("CLEAR REPORT"){Observation.clear()}; Action("STOP OBSERVATION"){Observation.accept(context,false)}
        Text("Review the revealed report before copying. Copying places reviewed bounded data on the system clipboard outside Doom; upload privately, then clear the clipboard. Clearing or stopping Doom cannot recall copies outside the app.",color=Paper)
        if(Observation.copied)Text("Copied to system clipboard. Upload privately, then clear the clipboard.",color=Paper)
    }
    Text("DEVICE PROOF · PENDING",color=Gold); Text("No verified Instagram screen mapping or DM route is claimed. This diagnostic does not protect you from scrolling and is not rollout-ready. Disable or uninstall at any time.",color=Paper)
    Text("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",color=Gold,fontFamily=FontFamily.Monospace)
}

@Composable private fun BreathingPreview(frame:BreathingFrame,reduced:Boolean) { Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text(frame.label,color=Paper,fontSize=34.sp); PixelBloom(if(reduced)BreathingVisuals.staticProgress() else frame.bloom,Modifier.weight(1f)); SegmentedProgress(frame.segments) } }
@Composable private fun SegmentedProgress(segments:List<Float>) { Row(Modifier.fillMaxWidth().height(8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){segments.forEach{fraction->Box(Modifier.weight(1f).fillMaxHeight().background(Panel)){Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Gold))}}} }
@Composable private fun PixelBloom(progress:Float,modifier:Modifier=Modifier.height(180.dp)) { Canvas(modifier.fillMaxWidth().semantics{contentDescription="A detailed pixel bloom"}) { val unit=minOf(size.width/16,size.height/16); val colors=listOf(Orange,Color(0xFF9A3F35),Gold,Paper); BreathingVisuals.cells(progress).forEach{cell->drawRect(colors[cell.layer],Offset(size.width/2+cell.x*unit-unit/2,size.height/2+cell.y*unit-unit/2),Size((unit-2).coerceAtLeast(1f),(unit-2).coerceAtLeast(1f)))}} }
@Composable private fun Frame(content:@Composable ColumnScope.()->Unit)=Column(Modifier.fillMaxWidth().background(Panel).border(1.dp,Gold.copy(alpha=.8f)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
@Composable private fun Action(text:String,enabled:Boolean=true,onClick:()->Unit)=Button(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled,shape=RoundedCornerShape(2.dp)){Text(text)}
