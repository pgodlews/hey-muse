package io.github.pgodlews.heymuse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The one screen: Muse's face, and behind the gear, the settings. Opening it starts the service,
 * which goes on listening after the screen is gone.
 *
 * Settings can also be handed over with adb, which beats typing a long token on the Portal:
 *
 *     adb shell am start -n io.github.pgodlews.heymuse/.MainActivity --es sdk_token mgst_...
 *
 * and likewise speech_key, voice, speech_base, speech_model, speech_style and display_name.
 */
class MainActivity : ComponentActivity() {
    private var showSettings by mutableStateOf(false)
    private var avatar by mutableStateOf<Avatar?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        MuseService.start(this)
        // The service starts the assistant on its own thread of events; the screen needs its settings now.
        Assistant.start(applicationContext)
        takeExtras(intent)
        askForMicrophone()
        avatar = Avatar.load(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent,
                background = Night,
                surface = Panel,
                surfaceVariant = PanelHigh,
                onSurface = Ink,
                onSurfaceVariant = InkMuted,
            )) {
                val state by Assistant.state.collectAsState()
                if (showSettings) {
                    BackHandler { showSettings = false; avatar = Avatar.load(this) }
                    SettingsScreen(
                        state = state,
                        settings = Assistant.settings,
                        avatarLoaded = avatar != null,
                        avatarPath = Avatar.dir(this)?.absolutePath ?: "",
                        canOverlay = AndroidSettings.canDrawOverlays(this),
                        onAllowOverlay = ::askForOverlay,
                        onKeepScreenOn = ::applyKeepScreenOn,
                        onClose = { showSettings = false; avatar = Avatar.load(this) },
                    )
                } else {
                    HomeScreen(state = state, avatar = avatar, captions = Assistant.settings.captions,
                        wakeWord = Assistant.settings.wakeWord, onSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        Assistant.visible = true
        applyKeepScreenOn()
    }

    override fun onPause() {
        Assistant.visible = false
        super.onPause()
    }

    private fun applyKeepScreenOn() {
        if (Assistant.settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun takeExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        val s = Assistant.settings
        var changed = false
        extras.getString("sdk_token")?.let { s.sdkToken = it; changed = true }
        extras.getString("speech_key")?.let { s.speechKey = it; changed = true }
        extras.getString("voice")?.let { s.voice = it; changed = true }
        extras.getString("speech_base")?.let { s.speechBase = it; changed = true }
        extras.getString("speech_model")?.let { s.speechModel = it; changed = true }
        extras.getString("speech_style")?.let { s.speechStyle = it; changed = true }
        extras.getString("display_name")?.let { s.displayName = it; changed = true }
        if (changed) {
            Log.i("HeyMuse", "settings taken from the intent")
            Assistant.settingsChanged()
        }
    }

    private fun askForMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun askForOverlay() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}

val Night = Color(0xFF0B0A16)
val Panel = Color(0xFF17152A)
val PanelHigh = Color(0xFF221F3B)
val Edge = Color(0xFF2E2A4D)
val Accent = Color(0xFFB9A4FF)
val AccentWarm = Color(0xFFFF9E80)
val Ink = Color(0xFFF2F0FA)
val InkMuted = Color(0xFF9D98B8)
val Warning = Color(0xFFFFC46B)
