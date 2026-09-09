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
import java.util.Locale

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
                    Text("Still image · reduced motion", color = Paper, modifier = Modifier.padding(start = 12.dp))
                }
                Frame {
                    Text("INSTAGRAM · NOT PROTECTED", color = Jade, fontFamily = FontFamily.Monospace)
                    Text(when {
                        !Observation.consent -> "Observation off · consent required"
                        !Observation.connected -> "Observation off · service disconnected"
                        else -> "Observer connected · mapping unverified"
                    }, color = Paper, fontSize = 18.sp)
                    Text("SEPARABILITY RESEARCH ONLY", color = Jade)
                    Text("Counts overlapped on Pixel 11 Pro / Android 17 / Instagram 445.0.0.45.83.", color = Paper)
                    Text("Optional accessibility access can expose screen content to an app. Doom visits at most 128 Instagram nodes through depth 8. It immediately hashes resource-ID/class presence, clickable state, depth and bounded child count. It never reads text or descriptions, retains identifier values or nodes, takes screenshots, logs or sends samples. Only consent is saved; one opaque current sample and up to four tester-labeled baselines stay in process memory.", color = Paper)
                    Text("Clear removes every sample and label; a later Instagram event may provide a new sample. Revocation, stop, service disconnect/interruption and process death remove all samples and labels. Returning here keeps the last sample for labeling; it may be stale or bounded.", color = Paper)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = Observation.consent, onCheckedChange = { Observation.accept(context, it) }, modifier = Modifier.semantics { contentDescription = "Consent to local structural fingerprints" })
                        Text("Allow local structural fingerprints", color = Paper, modifier = Modifier.weight(1f))
                    }
                    Action("OPEN ACCESSIBILITY SETTINGS", enabled = Observation.consent) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    val samples = Observation.samples
                    val current = samples.current
                    Text(if (current == null) "No observation. Open Instagram after enabling the observer, then return here."
                        else "Opaque fingerprint: ${current.opaque}", color = Paper, fontFamily = FontFamily.Monospace)
                    Text("Similarity is structural overlap, not a prediction or protection. Labels are yours; no thresholds or live gate.", color = Paper)
                    Text("Scores use weighted Jaccard overlap of hashed feature counts, rounded to one decimal. Identical samples score 100%; disjoint samples score 0%. This does not establish a screen's identity.", color = Paper)
                    SampleLabel.entries.forEach { label ->
                        val baseline = samples.baselines[label]
                        Text("${label.title}: " + when {
                            baseline == null -> "not labeled"
                            current == null -> "labeled · no current sample"
                            else -> String.format(Locale.ROOT, "%.1f%% similarity", current.similarity(baseline) * 100)
                        }, color = Paper)
                        Action("LABEL ${label.title.uppercase(Locale.ROOT)}",
                            enabled = current != null && Observation.consent && Observation.connected) {
                            Observation.label(label)
                        }
                    }
                    Action("CLEAR SAMPLES & LABELS") { Observation.clear() }
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
        Text("No verified Instagram screen mapping or DM route yet. Do not rely on this diagnostic to limit scrolling. Disable or uninstall at any time.", color = Paper, fontSize = 14.sp)
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
        val radius = 2 + (kotlin.math.sin(progress * Math.PI).toFloat() * 2).toInt()
        for (x in -radius..radius) for (y in -radius..radius) {
            if (kotlin.math.abs(x) + kotlin.math.abs(y) <= radius + 1) {
                drawRect(if (x == 0 && y == 0) Paper else Jade,
                    Offset(size.width / 2 + x * unit - unit / 2, size.height / 2 + y * unit - unit / 2),
                    Size(unit - 2, unit - 2))
            }
        }
    }
}
