package io.github.pgodlews.heymuse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.github.pgodlews.heymuse.server.SetupServer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heymuse.Heymuse

@Composable
fun SettingsScreen(
    state: UiState,
    settings: Settings,
    avatarLoaded: Boolean,
    avatarPath: String,
    canOverlay: Boolean,
    onAllowOverlay: () -> Unit,
    onKeepScreenOn: () -> Unit,
    onClose: () -> Unit,
) {
    // Settings are plain preferences; this counter redraws the screen after one is written.
    var version by remember { mutableIntStateOf(0) }
    fun changed(mic: Boolean = false) {
        version++
        Assistant.settingsChanged(micChanged = mic)
    }

    val context = LocalContext.current
    var showQrSetup by remember { mutableStateOf(false) }
    var setupServer by remember { mutableStateOf<SetupServer?>(null) }

    fun startQrSetup() {
        val server = SetupServer(context, settings) {
            changed(false)
        }
        if (server.start()) {
            setupServer = server
            showQrSetup = true
        }
    }

    fun stopQrSetup() {
        showQrSetup = false
        setupServer?.stop()
        setupServer = null
    }

    if (showQrSetup && setupServer != null) {
        QrCodeDialog(
            server = setupServer!!,
            onDismiss = ::stopQrSetup
        )
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                @Suppress("DEPRECATION")
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Ink, modifier = Modifier.size(30.dp))
            }
            Text("Settings", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = ::startQrSetup,
                colors = ButtonDefaults.buttonColors(containerColor = PanelHigh, contentColor = Accent),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("📱 Phone Setup")
            }
            StatusChip(state)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            key(version) {
                MuseCard(state, settings, ::changed, ::startQrSetup)
                VoiceCard(settings, ::changed)
                ListeningCard(settings, canOverlay, onAllowOverlay, ::changed)
                ScreenCard(settings, avatarLoaded, avatarPath, onKeepScreenOn, ::changed)
            }
            ClockCard(state)
            AboutCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 980.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Edge),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = InkMuted, fontSize = 15.sp, lineHeight = 21.sp)

@Composable
private fun Toggle(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink, fontSize = 17.sp)
            if (detail != null) Note(detail)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A text setting with its own Save button, so half-typed values are never used. */
@Composable
private fun Field(label: String, value: String, secret: Boolean = false, placeholder: String = "", onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onSave(text) }, enabled = text != value) { Text("Save") }
    }
}

@Composable
private fun MuseCard(state: UiState, settings: Settings, changed: (Boolean) -> Unit, onOpenQrSetup: () -> Unit) {
    val s = state.status
    Section("Muse") {
        Text(
            when {
                s.online -> "Connected to Muse as ${s.username}."
                s.pairing -> "Pairing with the Muse app."
                s.paired && s.error.isNotEmpty() -> "Paired as ${s.username}, but not connected: ${s.error}"
                s.paired -> "Paired as ${s.username}, connecting."
                else -> "Not paired with Muse yet."
            },
            color = Ink, fontSize = 17.sp,
        )
        OutlinedButton(
            onClick = onOpenQrSetup,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f))
        ) {
            Text("📱 Configure from Phone (Scan QR)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Field("Muse SDK token", settings.sdkToken, secret = true, placeholder = "mgst_…") {
            settings.sdkToken = it
            changed(false)
        }
        Note("Your own token from gadgets.muse.ai (Account → SDK tokens). It is personal: never share or publish it. " +
            "Scan the QR code above to easily paste it from your phone, or hand it over via adb.")

        if (s.pairing) {
            PairingPanel(s)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { Assistant.startPairing() }, enabled = settings.sdkToken.isNotEmpty()) {
                    Text(if (s.paired) "Pair again" else "Pair with the Muse app")
                }
                if (s.paired) OutlinedButton(onClick = { Assistant.unpair() }) { Text("Unpair") }
            }
            if (s.pairingFailed && !s.paired) Text("The last pairing failed: ${s.pairingReason.ifEmpty { s.pairingError }}", color = Warning, fontSize = 15.sp)
            if (s.pairingDone && s.paired && !s.online) Note("Paired. Connecting to Muse…")
        }
        if (state.problem.isNotEmpty()) Text(state.problem, color = Warning, fontSize = 15.sp)
        Field("Name in the Muse app", settings.displayName) {
            settings.displayName = it
            changed(false)
        }
    }
}

@Composable
private fun PairingPanel(s: MuseStatus) {
    Column(
        Modifier.fillMaxWidth().background(PanelHigh, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("This Portal is on Bluetooth as", color = InkMuted, fontSize = 15.sp)
        Text(s.bleName, color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text("On your phone, in the Muse app:", color = Ink, fontSize = 16.sp)
        Note("1. Settings → Devices, turn on Developer mode.\n" +
            "2. Tap Add Device (+) and pick ${s.bleName}.\n" +
            "3. Muse warns that it is a community device: continue if this Portal is yours.\n" +
            "4. When asked for Wi-Fi, pick the one network shown. No password is needed.")
        Text("Now: ${s.pairingState}${if (s.pairingReason.isNotEmpty()) " (${s.pairingReason})" else ""}", color = Accent, fontSize = 16.sp)
        Note("Pairing stays open for ${Heymuse.PairingWindowSeconds / 60} minutes. It keeps setup secret from anyone listening, " +
            "but cannot prove who the phone is, so pair on a network you trust.")
        OutlinedButton(onClick = { Assistant.cancelPairing() }) { Text("Stop pairing") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCard(settings: Settings, changed: (Boolean) -> Unit) {
    Section("Voice") {
        Note("Muse answers in words, and the Portal says them: in its built-in voice, or in a cloud voice " +
            "if you give an OpenAI-style speech key. With a cloud voice the answer's text goes to that service.")
        val voices = listOf(Settings.BUILT_IN) + Heymuse.cloudVoices().split(",").filter { it.isNotBlank() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            voices.forEach { v ->
                val needsKey = v != Settings.BUILT_IN && settings.speechKey.isEmpty()
                FilterChip(
                    selected = settings.voice == v,
                    onClick = { settings.voice = v; changed(false) },
                    label = { Text(if (v == Settings.BUILT_IN) "Built in" else v) },
                    enabled = !needsKey,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)),
                )
            }
        }
        Field("Speech key", settings.speechKey, secret = true, placeholder = "sk-…") { settings.speechKey = it; changed(false) }
        Field("How to say it", settings.speechStyle, placeholder = "warmly, and not too fast") { settings.speechStyle = it; changed(false) }
        Field("Speech endpoint", settings.speechBase, placeholder = "https://api.openai.com/v1") { settings.speechBase = it; changed(false) }
        Field("Speech model", settings.speechModel, placeholder = "gpt-4o-mini-tts") { settings.speechModel = it; changed(false) }
        Button(onClick = { Assistant.testVoice() }) { Text("Hear it") }
    }
}

@Composable
private fun ListeningCard(settings: Settings, canOverlay: Boolean, onAllowOverlay: () -> Unit, changed: (Boolean) -> Unit) {
    Section("Listening") {
        Toggle("Listen for “Hey Muse”", "The wake word is heard on the Portal. Nothing is sent until it is, or you tap.", settings.wakeWord) {
            settings.wakeWord = it; changed(false)
        }
        var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
        Text("Sensitivity", color = Ink, fontSize = 17.sp)
        Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = { settings.sensitivity = sensitivity; changed(false) },
            enabled = settings.wakeWord,
        )
        Note("Higher wakes more readily, and falsely more often. The middle is what the model was tuned for.")
        Toggle("Portal voice processing", "The Portal's own beamforming, noise suppression and echo cancellation. " +
            "Turn off if the wake word is missed.", settings.voiceProcessing) {
            settings.voiceProcessing = it; changed(true)
        }
        Toggle("Open on the wake word", "Bring Hey Muse to the front when it hears “Hey Muse” in the background.", settings.openOnWake) {
            settings.openOnWake = it; changed(false)
        }
        if (settings.openOnWake && !canOverlay) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Needs “Display over other apps”.", color = Warning, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(onClick = onAllowOverlay) { Text("Allow") }
            }
        }
        Toggle("Start when the Portal starts", null, settings.startOnBoot) { settings.startOnBoot = it; changed(false) }
        Note("For the microphone to be Hey Muse's alone, turn off “Hey Portal” and Alexa in the Portal's own settings. " +
            "While Portal's assistant talks, other apps hear silence.")
    }
}

@Composable
private fun ScreenCard(settings: Settings, avatarLoaded: Boolean, avatarPath: String, onKeepScreenOn: () -> Unit, changed: (Boolean) -> Unit) {
    Section("Screen") {
        Toggle("Show what was said", "Write the question and the answer on the screen as well as speaking them.", settings.captions) {
            settings.captions = it; changed(false)
        }
        Toggle("Keep the screen on", "While Hey Muse is in front.", settings.keepScreenOn) {
            settings.keepScreenOn = it; changed(false); onKeepScreenOn()
        }
        Text(if (avatarLoaded) "The Muse character is installed." else "No Muse character installed.", color = Ink, fontSize = 17.sp)
        Note("The character is Meta's and is not in this app. Make it from your own copy of Meta's SDK with " +
            "tools/muse-avatar, then copy the muse-avatar folder to $avatarPath with adb push.")
    }
}

@Composable
private fun ClockCard(state: UiState) {
    if (state.timers.isEmpty() && state.alarms.isEmpty()) return
    Section("Timers and alarms") {
        Note("Ask Muse to set them, like “set a timer for ten minutes” or “wake me at 7”.")
        state.timers.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Timer ${t.label}".trim() + " · " + countdown(t.endMs - System.currentTimeMillis()), color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { Assistant.cancelTimer(t.id) }) { Text("Cancel") }
            }
        }
        state.alarms.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d:%02d · %s%s%s".format(a.hour, a.minute, a.days, if (a.label.isNotEmpty()) " · ${a.label}" else "", if (a.on) "" else " · off"),
                    color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { Assistant.deleteAlarm(a.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Section("About") {
        Text("Hey Muse ${BuildConfig.VERSION_NAME}", color = Ink, fontSize = 17.sp)
        Note("A hobby project, not made or endorsed by Meta. Muse is Meta's, and using it this way is covered by Meta's Gadget SDK Terms. " +
            "After the wake word, what you say goes to your Muse as a recording, kept on the message in your Muse account. " +
            "Anyone who speaks to the Portal is recorded into your account the same way.")
    }
}
