package io.github.pgodlews.heymuse

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(state: UiState, avatar: Avatar?, captions: Boolean, wakeWord: Boolean, onSettings: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B1636), Night), radius = 1400f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Assistant.tap() }
    ) {
        val compact = maxHeight < 560.dp
        val faceSize = if (compact) maxHeight * 0.36f else maxHeight * 0.42f
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 48.dp, vertical = if (compact) 16.dp else 32.dp)) {
            TopBar(state, onSettings)
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(faceSize), contentAlignment = Alignment.Center) {
                if (avatar != null) {
                    val name = when (state.phase) {
                        Phase.LISTENING -> Avatar.LISTENING
                        Phase.THINKING -> Avatar.THINKING
                        Phase.SPEAKING -> Avatar.TALKING
                        Phase.IDLE -> Avatar.IDLE
                    }
                    avatar.Show(name, Modifier.size(faceSize))
                } else {
                    Orb(state.phase, state.level, Modifier.size(faceSize))
                }
            }
            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            Captions(state, captions, compact)
            Spacer(Modifier.weight(1f))
            BottomBar(state, wakeWord, compact)
        }
        state.ringing?.let { Ringing(it) }
    }
}

@Composable
private fun TopBar(state: UiState, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Hey Muse", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        StatusChip(state)
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = InkMuted, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
fun StatusChip(state: UiState) {
    val (text, color) = statusLine(state)
    Surface(shape = RoundedCornerShape(50), color = PanelHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(8.dp))
            Text(text, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun statusLine(state: UiState): Pair<String, Color> {
    val s = state.status
    return when {
        state.mic == Mic.State.NO_PERMISSION -> "Microphone not allowed" to Warning
        state.mic == Mic.State.SILENCED -> "Microphone off" to Warning
        state.mic == Mic.State.FAILED -> "Microphone unavailable" to Warning
        s.pairing -> "Pairing" to Accent
        s.online -> "Muse · ${s.username}" to Color(0xFF7DE2A6)
        s.phase == heymuse.Heymuse.PhaseConnecting -> "Connecting" to Warning
        s.paired -> "Offline" to Warning
        else -> "Not paired" to InkMuted
    }
}

/** Muse's stand-in face when there is no character: a ring of light that breathes, listens and talks. */
@Composable
private fun Orb(phase: Phase, level: Float, modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "orb")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val spin by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val loud by animateFloatAsState(if (phase == Phase.LISTENING || phase == Phase.SPEAKING) level else 0f, tween(90), label = "loud")
    val glow by animateFloatAsState(if (phase == Phase.IDLE) 0.35f else 1f, tween(400), label = "glow")
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        val color = if (phase == Phase.SPEAKING) AccentWarm else Accent
        val core = r * (0.46f + 0.04f * breath + 0.22f * loud)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * glow), color.copy(alpha = glow), color.copy(alpha = 0.15f)), c, core), core, c)
        if (phase == Phase.THINKING) {
            val ring = r * 0.72f
            for (i in 0 until 3) {
                val a = 2 * PI * (spin + i / 3.0)
                drawCircle(Accent, r * 0.05f, Offset(c.x + ring * cos(a).toFloat(), c.y + ring * sin(a).toFloat()))
            }
        }
        if (phase == Phase.LISTENING) {
            drawCircle(color.copy(alpha = 0.6f), core + r * 0.1f + r * 0.2f * loud, c, style = Stroke(width = r * 0.02f))
        }
    }
}

@Composable
private fun Captions(state: UiState, captions: Boolean, compact: Boolean) {
    val label = when (state.phase) {
        Phase.LISTENING -> "Listening…"
        Phase.THINKING -> "Thinking…"
        else -> ""
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) Text(label, color = Accent, fontSize = if (compact) 18.sp else 22.sp)
        if (captions && state.heard.isNotEmpty() && state.phase != Phase.LISTENING) {
            Text("“${state.heard}”", color = InkMuted, fontStyle = FontStyle.Italic, fontSize = if (compact) 18.sp else 22.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 1100.dp).padding(top = 6.dp))
        }
        val show = state.answer.isNotEmpty() && state.phase != Phase.LISTENING && (captions || state.answerIsProblem)
        if (show) {
            val long = state.answer.length > 220
            Text(state.answer, color = if (state.answerIsProblem) Warning else Ink,
                fontSize = when { compact && long -> 18.sp; compact -> 22.sp; long -> 24.sp; else -> 32.sp },
                lineHeight = when { compact && long -> 24.sp; compact -> 28.sp; long -> 32.sp; else -> 40.sp },
                textAlign = TextAlign.Center, maxLines = if (compact) 5 else 7, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 1100.dp).padding(top = 10.dp))
        }
    }
}

@Composable
private fun BottomBar(state: UiState, wakeWord: Boolean, compact: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.timers.isNotEmpty()) {
        while (state.timers.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val warning = micWarning(state)
        if (warning != null) {
            Text(warning, color = Warning, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.dp))
            Spacer(Modifier.height(10.dp))
        }
        if (state.timers.isNotEmpty() || state.alarms.any { it.on }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.timers.take(3).forEach { t ->
                    Chip("⏱ ${t.label.ifEmpty { "Timer" }} ${countdown(t.endMs - now)}")
                }
                state.alarms.filter { it.on }.minByOrNull { it.nextMs }?.let { a ->
                    Chip("⏰ ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.nextMs))}${if (a.label.isNotEmpty()) " · ${a.label}" else ""}")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val hint = when (state.phase) {
            Phase.IDLE -> if (wakeWord && state.wakeReady) "Say “Hey Muse”, or tap anywhere" else "Tap anywhere to talk"
            Phase.LISTENING -> "Tap when you're done"
            else -> "Tap to stop"
        }
        Text(hint, color = InkMuted, fontSize = 16.sp)
    }
}

fun micWarning(state: UiState): String? = when (state.mic) {
    Mic.State.NO_PERMISSION -> "Hey Muse needs the microphone. Allow it when asked, or install with adb install -g."
    Mic.State.SILENCED -> "The microphone hears nothing. Check the privacy switch, or turn off “Hey Portal” in the Portal's settings."
    Mic.State.FAILED -> "The microphone can't be opened right now. Hey Muse keeps trying."
    else -> null
}

@Composable
private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = PanelHigh) {
        Text(text, color = Ink, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

fun countdown(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun Ringing(what: String) {
    Box(
        Modifier.fillMaxSize().background(Night.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Assistant.stopRinging() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(what, color = Ink, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { Assistant.stopRinging() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentWarm, contentColor = Night),
                modifier = Modifier.height(72.dp).widthIn(min = 220.dp),
            ) { Text("Stop", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
            Text("or say “Hey Muse”", color = InkMuted, fontSize = 18.sp)
        }
    }
}
