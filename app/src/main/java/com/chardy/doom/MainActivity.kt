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
    if(dialog) CustomDialog("Custom duration","Seconds",raw,{raw=it},{ReminderSettingsStore.normalizeDuration(raw)}) { value -> save(settings.copy(durationSeconds=value));dialog=false }
}
@Composable private fun SuppressionSettings(settings:ReminderSettings,save:(ReminderSettings)->Unit) {
    var dialog by remember { mutableStateOf(false) }; var raw by remember { mutableStateOf("") }
    Frame { Text("Suppression",color=Gold); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf(1,5,15).forEach { value -> FilterChip(selected=settings.suppressionMinutes==value,onClick={save(settings.copy(suppressionMinutes=value))},label={Text("${value}m")}) }
        FilterChip(selected=settings.suppressionMinutes !in listOf(1,5,15),onClick={raw="";dialog=true},label={Text("Custom")})
    }}
    if(dialog) CustomDialog("Custom suppression","Minutes",raw,{raw=it},{ReminderSettingsStore.validSuppression(raw)}) { value -> save(settings.copy(suppressionMinutes=value));dialog=false }
}
@Composable private fun CustomDialog(title:String,label:String,raw:String,change:(String)->Unit,parse:()->Int?,save:(Int)->Unit) {
    AlertDialog(onDismissRequest={},title={Text(title)},text={OutlinedTextField(raw,change,label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={parse()?.let(save)},enabled=parse()!=null){Text("Save")}},dismissButton={TextButton(onClick={change("")}){Text("Clear")}})
}

@Suppress("UNUSED_PARAMETER")
@Composable private fun Debug(demo:DemoScreen,setDemo:(DemoScreen)->Unit,preview:()->Unit,reduceMotion:Boolean,setReduce:(Boolean)->Unit,traceRevision:Long,refresh:()->Unit,feedback:String?,setFeedback:(String)->Unit) {
    val context=LocalContext.current
    Frame { Text("DOOM-OWNED QUICK DEMO",color=Gold); Action("Try the breathing demo",onClick=preview); Action("Demo messages — no wait"){setDemo(DemoScreen.MESSAGES)}
        if(demo==DemoScreen.MESSAGES) Text("Messages stay open. This is a simulated inbox.",color=Paper)
        if(demo==DemoScreen.COMPLETE) Text("A deliberate start.",color=Paper)
        Row(verticalAlignment=Alignment.CenterVertically){Switch(reduceMotion,setReduce);Text("Reduced-motion demo",color=Paper)} }
    Frame {
        Text("INSTAGRAM · DIAGNOSTIC ONLY",color=Gold)
        Text(if(Observation.connected) "Observation service: Connected" else "Observation service: Disconnected",color=Paper)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(Observation.gateConsent,{Observation.setGateConsent(context,it)});Text("Allow diagnostic Instagram entry pause",color=Paper)}
        Text("Entry gate state: ${Observation.entryGateState}",color=Gold)
        Text("An admitted reminder uses the selected duration snapshot; subsequent reminders are suppressed for the selected cooldown snapshot.",color=Orange)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(Observation.consent,{Observation.accept(context,it)});Text("Allow sanitized structural report",color=Paper)}
        Text("Accessibility access can expose screen content. With fresh report consent, Doom traverses only Instagram and retains sanitized static Instagram resource names from compile-time resource tables, safe class names, depth, child counts and closed booleans. Reports contain at most 128 nodes through depth 8, 64 unique tokens, and 8,192 ASCII/UTF-8 bytes; omissions are marked truncated. Previously unknown names are accepted only under the exact lowercase com.instagram.android:id grammar.",color=Paper)
        Text("Reports expose static resource names, never UI text or account values. Doom never collects descriptions, hints, errors, pane or tooltip titles, bounds, screenshots, notification content, node/window IDs, raw trees or report actions. Reports remain process-local with no file persistence, logging, network, or automatic export.",color=Paper)
        Text("Returning directly to Doom may preserve a hidden report for local review. Foreign apps, clear, revoke, stop, disconnect, interruption, reconnect, missing/wrong roots, and process death discard report state. Every report requires explicit reveal before explicit clipboard copy.",color=Paper)
        val availability=remember(traceRevision){RemovalTraceStore.process.availability()}
        Text("REMOVAL TRACE · ${availability.name}",color=Gold)
        Text("The optional trace stores closed categories and monotonic offsets only and expires in process memory.",color=Paper)
        Action("ARM NEXT REMOVAL TRACE",Observation.consent&&Observation.gateConsent){RemovalTraceStore.process.arm();refresh()}
        Action("REFRESH TRACE STATUS",onClick=refresh)
        Action("COPY REMOVAL TRACE",availability==RemovalTraceAvailability.AVAILABLE){setFeedback(Observation.copyRemovalTrace(context).name);refresh()}
        Action("CLEAR REMOVAL TRACE"){RemovalTraceStore.process.clear();setFeedback("Removal trace cleared");refresh()}; feedback?.let{Text(it,color=Gold)}
    }
    Frame { Text("SANITIZED STRUCTURAL REPORT",color=Gold); val report=Observation.report
        Text(if(report==null)"No report." else if(report.truncated)"Report available · truncated" else "Report available · bounded traversal complete",color=Paper)
        Text("Shadow prediction: ${if(report==null)"UNKNOWN" else Observation.shadowPrediction}",color=Gold)
        Action("REVEAL LOCAL REPORT",Observation.canReveal){Observation.revealReport()}; if(Observation.revealed&&report!=null)Text(report.text,color=Paper,fontFamily=FontFamily.Monospace)
        Action("COPY REVIEWED REPORT",Observation.canCopy){Observation.copyReport(context)}; Action("CLEAR REPORT"){Observation.clear()}; Action("STOP OBSERVATION"){Observation.accept(context,false)}
        Text("Copying places reviewed bounded data on the system clipboard outside Doom. Clear it separately.",color=Paper)
    }
    Text("DEVICE PROOF · PENDING",color=Gold); Text("No verified Instagram behavior is claimed.",color=Paper)
    Text("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",color=Gold,fontFamily=FontFamily.Monospace)
}

@Composable private fun BreathingPreview(frame:BreathingFrame,reduced:Boolean) { Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text(frame.label,color=Paper,fontSize=34.sp); PixelBloom(if(reduced)BreathingVisuals.staticProgress() else frame.bloom,Modifier.weight(1f)); SegmentedProgress(frame.segments) } }
@Composable private fun SegmentedProgress(segments:List<Float>) { Row(Modifier.fillMaxWidth().height(8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){segments.forEach{fraction->Box(Modifier.weight(1f).fillMaxHeight().background(Panel)){Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Gold))}}} }
@Composable private fun PixelBloom(progress:Float,modifier:Modifier=Modifier.height(180.dp)) { Canvas(modifier.fillMaxWidth().semantics{contentDescription="A detailed pixel bloom"}) { val unit=minOf(size.width/16,size.height/16); val colors=listOf(Orange,Color(0xFF9A3F35),Gold,Paper); BreathingVisuals.cells(progress).forEach{cell->drawRect(colors[cell.layer],Offset(size.width/2+cell.x*unit-unit/2,size.height/2+cell.y*unit-unit/2),Size((unit-2).coerceAtLeast(1f),(unit-2).coerceAtLeast(1f)))}} }
@Composable private fun Frame(content:@Composable ColumnScope.()->Unit)=Column(Modifier.fillMaxWidth().background(Panel).border(1.dp,Gold.copy(alpha=.8f)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
@Composable private fun Action(text:String,enabled:Boolean=true,onClick:()->Unit)=Button(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled,shape=RoundedCornerShape(2.dp)){Text(text)}
