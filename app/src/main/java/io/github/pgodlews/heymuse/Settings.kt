package io.github.pgodlews.heymuse

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * What the owner chooses, kept in the app's private preferences. The Muse SDK token and the speech
 * key are secrets: they never go into a log or onto the screen in full.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sdkToken: String
        get() = prefs.getString("sdk_token", "") ?: ""
        set(v) = prefs.edit().putString("sdk_token", v.trim()).apply()

    /** What the Muse app calls this Portal. */
    var displayName: String
        get() = prefs.getString("display_name", null) ?: defaultDisplayName()
        set(v) = prefs.edit().putString("display_name", v.trim().ifEmpty { defaultDisplayName() }).apply()

    /** [BUILT_IN], or one of the cloud voices. */
    var voice: String
        get() = prefs.getString("voice", BUILT_IN) ?: BUILT_IN
        set(v) = prefs.edit().putString("voice", v).apply()

    var speechKey: String
        get() = prefs.getString("speech_key", "") ?: ""
        set(v) = prefs.edit().putString("speech_key", v.trim()).apply()

    /** An OpenAI-style endpoint, like https://api.openai.com/v1; empty is OpenAI's. */
    var speechBase: String
        get() = prefs.getString("speech_base", "") ?: ""
        set(v) = prefs.edit().putString("speech_base", v.trim()).apply()

    /** Empty is gpt-4o-mini-tts. */
    var speechModel: String
        get() = prefs.getString("speech_model", "") ?: ""
        set(v) = prefs.edit().putString("speech_model", v.trim()).apply()

    /** How to say it, for a model that takes directions, like "warmly, and not too fast". */
    var speechStyle: String
        get() = prefs.getString("speech_style", "") ?: ""
        set(v) = prefs.edit().putString("speech_style", v.trim()).apply()

    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", true)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /** 0 to 1; 0.5 is the model's trained cutoff, higher wakes more readily. */
    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 0.5f)
        set(v) = prefs.edit().putFloat("sensitivity", v.coerceIn(0f, 1f)).apply()

    /**
     * Whether the microphone goes through the Portal's own voice processing (beamforming, noise
     * suppression and echo cancellation in the DSP), or comes raw.
     */
    var voiceProcessing: Boolean
        get() = prefs.getBoolean("voice_processing", true)
        set(v) = prefs.edit().putBoolean("voice_processing", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(v) = prefs.edit().putBoolean("start_on_boot", v).apply()

    /** Bring the app to the front when the wake word is heard while it is in the background. */
    var openOnWake: Boolean
        get() = prefs.getBoolean("open_on_wake", true)
        set(v) = prefs.edit().putBoolean("open_on_wake", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** Whether answers are written on the screen as well as spoken. */
    var captions: Boolean
        get() = prefs.getBoolean("captions", true)
        set(v) = prefs.edit().putBoolean("captions", v).apply()

    /** The voice that will actually speak: without a key there is no endpoint to ask. */
    val effectiveVoice: String
        get() = if (speechKey.isEmpty()) BUILT_IN else voice

    companion object {
        const val BUILT_IN = "builtin"

        fun defaultDisplayName(): String = when (Build.DEVICE.lowercase()) {
            "ohana", "alohaplus" -> "Portal+"
            "aloha" -> "Portal"
            "cipher" -> "Portal+"
            "omni" -> "Portal"
            "atlas" -> "Portal Mini"
            "terry" -> "Portal Go"
            "ripley" -> "Portal TV"
            else -> "Portal"
        }
    }
}
