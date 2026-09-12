package com.chardy.doom

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private val Ink=Color(0xFF171B25); private val Paper=Color(0xFFF3E7CF); private val Jade=Color(0xFF73B39C); private val Rust=Color(0xFFB97962)
@Composable fun DoomTheme(content:@Composable ()->Unit) { MaterialTheme(colorScheme=lightColorScheme(background=Ink,onBackground=Paper,primary=Jade,onPrimary=Ink,surface=Color(0xFF252B37),onSurface=Paper), content=content) }
@Composable fun DoomScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val gate = remember { DemoGate() }
    var screen by remember { mutableStateOf(gate.screen) }
    var generation by remember { mutableLongStateOf(0) }
    var remaining by remember { mutableLongStateOf(5_000) }
    var reduceMotion by remember { mutableStateOf(false) }
    val systemStatic = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    fun leave(destination: DemoGate.Screen = DemoGate.Screen.HOME) {
        gate.leave(destination)
        screen = gate.screen
        generation = gate.generation
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                gate.background()
                screen = gate.screen
                generation = gate.generation
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); gate.background() }
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
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
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
                    Text("Diagnostic entry breathing gate · OFF by default. Unverified; does not claim protection. An admitted production gate cannot repeat for one minute; cooldown is in memory only.", color = Rust)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = Observation.gateConsent, onCheckedChange = { Observation.setGateConsent(context, it) }, modifier = Modifier.semantics { contentDescription = "Diagnostic Instagram entry gate opt in" })
                        Text("Allow diagnostic Instagram entry pause", color = Paper, modifier = Modifier.weight(1f))
                    }
                    Text("This consent is separate from the sanitized report consent and is the only persisted gate setting.", color = Paper, fontSize = 12.sp)
                    Text("Entry gate state: ${Observation.entryGateState}", color = Jade, fontFamily = FontFamily.Monospace)
                    Text("SANITIZED STRUCTURAL REPORT", color = Jade)
                    Text("Structure changes with scrolling and content. The sanitized report is separate from a diagnostic shadow prediction; neither blocks, protects, or controls actions; the optional entry pause is default-off and fail-open.", color = Paper)
                    Text("Optional accessibility access can expose screen content to an app. Doom receives package identifiers for window events from all apps only to detect leaving Instagram and remove the gate; it reads no foreign window tree. With fresh report consent, Doom traverses only Instagram: at most 128 nodes through depth 8. It keeps only sanitized static Instagram resource names from compile-time resource tables, normalized safe class names, depth, child count capped at 16, and clickable/scrollable/editable/selected/checked booleans in sorted aggregate rows. Previously unknown resource names are admitted only as exact com.instagram.android:id/ names: 1–64 lowercase ASCII letters/digits/underscores, starting with a letter, at most 96 raw characters. Invalid IDs and unknown classes are omitted. Reports have at most 64 unique tokens and 8,192 ASCII characters/UTF-8 bytes; omitted structure is marked truncated.", color = Paper)
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
        Text("Build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})", color = Jade, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable private fun Frame(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF252B37)).border(1.dp, Jade.copy(alpha = 0.65f))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable private fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun PixelBloom(progress: Float) {
    Canvas(Modifier.fillMaxWidth().height(168.dp).semantics { contentDescription = "A quiet pixel bloom" }) {
        val unit = minOf(size.width / 14, size.height / 12)
        BreathingVisuals.cells(progress).forEach { cell ->
            drawRect(if (cell.center) Paper else Jade,
                Offset(size.width / 2 + cell.x * unit - unit / 2, size.height / 2 + cell.y * unit - unit / 2),
                Size((unit - 2).coerceAtLeast(1f), (unit - 2).coerceAtLeast(1f)))
        }
    }
}
