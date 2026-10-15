package io.github.pgodlews.heymuse

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import heymuse.App
import heymuse.Ears
import heymuse.Heymuse
import heymuse.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where a turn stands. */
enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

/** What the Go core says about the Portal and Muse (heymuse.Status). */
data class MuseStatus(
    val phase: String = Heymuse.PhaseStopped,
    val username: String = "",
    val bleName: String = "",
    val error: String = "",
    val hasSdkToken: Boolean = false,
    val pairingState: String = "",
    val pairingReason: String = "",
    val pairingError: String = "",
    val pairingDone: Boolean = false,
    val pairingFailed: Boolean = false,
) {
    val online get() = phase == Heymuse.PhaseOnline
    val pairing get() = phase == Heymuse.PhasePairing
    val paired get() = username.isNotEmpty()

    companion object {
        fun parse(json: String): MuseStatus = runCatching {
            val o = JSONObject(json)
            val p = o.optJSONObject("pairing")
            MuseStatus(
                phase = o.optString("phase"),
                username = o.optString("username"),
                bleName = o.optString("bleName"),
                error = o.optString("error"),
                hasSdkToken = o.optBoolean("hasSdkToken"),
                pairingState = p?.optString("state") ?: "",
                pairingReason = p?.optString("reason") ?: "",
                pairingError = p?.optString("error") ?: "",
                pairingDone = p?.optBoolean("done") ?: false,
                pairingFailed = p?.optBoolean("failed") ?: false,
            )
        }.getOrDefault(MuseStatus())
    }
}

data class TimerItem(val id: Long, val label: String, val endMs: Long)
data class AlarmItem(val id: Long, val hour: Int, val minute: Int, val days: String, val label: String, val on: Boolean, val nextMs: Long)

data class UiState(
    val phase: Phase = Phase.IDLE,
    /** What Muse heard of the last question. */
    val heard: String = "",
    /** Muse's last answer, or why there was none. */
    val answer: String = "",
    val answerIsProblem: Boolean = false,
    /** How loud the mic (listening) or the voice (speaking) is, 0 to 1. */
    val level: Float = 0f,
    val status: MuseStatus = MuseStatus(),
    val mic: Mic.State = Mic.State.OFF,
    /** What is ringing, like "Timer" or "Alarm: gym", or null. */
    val ringing: String? = null,
    val timers: List<TimerItem> = emptyList(),
    val alarms: List<AlarmItem> = emptyList(),
    /** Why pairing could not start, or the wake word could not load. */
    val problem: String = "",
    val wakeReady: Boolean = false,
)

/**
 * The Portal as Muse's voice: the one owner of the Go core, the microphone, the voice and the
 * ring. The service starts it and the screen watches [state].
 *
 * Turns are driven on the main thread. The microphone thread only feeds the ears and posts what
 * they decide; the Go core's callbacks are posted the same way, so none of them ever waits on the
 * core while the core waits on it.
 */
object Assistant {
    private const val TAG = "HeyMuse"
    /** The loud start of the chime, in bytes of 16 kHz audio: 300 ms. */
    private const val CHIME_SKIP = 16000 * 2 * 3 / 10
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    lateinit var settings: Settings
        private set
    private lateinit var context: Context
    private var app: App? = null
    private var mic: Mic? = null
    private var voice: Voice? = null
    private var ringer: Ringer? = null
    private var peripheral: BlePeripheral? = null

    /**
     * The turn whose answer is awaited, from heymuse; anything about another is old news. Its number
     * may arrive after its first event, so any turn from [minTurn] on is taken as the one.
     */
    private var turn = 0L
    private var minTurn = 1L
    private var lastTurn = 0L

    // The microphone thread's own.
    private var ears: Ears? = null
    @Volatile private var earsWanted: Float = -1f
    private val beginUtterance = AtomicBoolean(false)
    private val finishNow = AtomicBoolean(false)
    private val resetWake = AtomicBoolean(false)
    /** Audio of the utterance still to drop: the chime, heard through the microphone. */
    private var skip = 0
    @Volatile private var phase = Phase.IDLE
    private var lastLevelAt = 0L

    /** Whether the screen is showing, so a wake word knows whether to bring it up. */
    @Volatile var visible = false

    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        context = ctx.applicationContext
        AudioFocus.init(context)
        settings = Settings(context)
        voice = Voice(context, settings)
        ringer = Ringer(context).also { r -> r.onEnded = { main.post { _state.update { it.copy(ringing = null) } } } }

        val dir = File(context.filesDir, "muse").apply { mkdirs() }
        importPairing(dir)
        val core = Heymuse.newApp(dir.absolutePath, settings.displayName, BuildConfig.VERSION_NAME, host)
        core.setSDKToken(settings.sdkToken)
        core.start()
        app = core
        refreshDevice()

        earsWanted = settings.sensitivity
        mic = Mic(context, { settings.voiceProcessing }, { s -> main.post { _state.update { it.copy(mic = s) } } }, ::onAudio).also { it.start() }
    }

    /**
     * Takes a pairing made elsewhere (hey-muse's musepair, on a computer with Bluetooth), pushed to
     * the app's external files as muse.json: for a Portal whose own Bluetooth cannot advertise. The
     * copy there is deleted once taken, since anything in that folder is readable over adb.
     */
    private fun importPairing(dir: File) {
        val pushed = context.getExternalFilesDir(null)?.let { File(it, "muse.json") } ?: return
        if (!pushed.isFile) return
        try {
            val st = JSONObject(pushed.readText())
            require(st.optJSONObject("credentials")?.optString("refresh_token")?.isNotEmpty() == true) { "it holds no pairing" }
            val tmp = File(dir, "muse.json.tmp")
            tmp.writeText(st.toString())
            if (!tmp.renameTo(File(dir, "muse.json"))) throw IllegalStateException("could not keep it")
            Log.i(TAG, "took the pairing from ${pushed.path}")
        } catch (e: Exception) {
            Log.w(TAG, "could not take the pairing from ${pushed.path}: ${e.message}")
        } finally {
            pushed.delete()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mic?.stop()
        voice?.stop()
        ringer?.stop()
        peripheral = null
        app?.stop()
        app = null
        phase = Phase.IDLE
        _state.update { it.copy(phase = Phase.IDLE, level = 0f) }
    }

    /** Re-reads the settings after the screen changed them. */
    fun settingsChanged(micChanged: Boolean = false) {
        val core = app ?: return
        core.setSDKToken(settings.sdkToken)
        core.setDisplayName(settings.displayName)
        earsWanted = settings.sensitivity
        if (micChanged) thread { mic?.restart() }
    }

    // ---- Turns, on the main thread ----

    /** A tap on the screen: whatever moves the turn on. */
    fun tap() {
        when {
            ringer?.ringing == true -> stopRinging()
            phase == Phase.IDLE -> listen()
            phase == Phase.LISTENING -> finishNow.set(true)
            phase == Phase.THINKING -> {
                thread { app?.cancelTurn() }
                toIdle()
            }
            phase == Phase.SPEAKING -> {
                voice?.stop()
                toIdle()
            }
        }
    }

    private fun listen() {
        val status = _state.value.status
        if (!status.online) {
            problem(excuse(status))
            return
        }
        voice?.stop()
        AudioFocus.request()
        Sound.play(context, R.raw.wake_chime)
        newTurn()
        phase = Phase.LISTENING
        finishNow.set(false)
        beginUtterance.set(true)
        _state.update { it.copy(phase = Phase.LISTENING, heard = "", answer = "", answerIsProblem = false, level = 0f) }
    }

    private fun excuse(s: MuseStatus): String = when {
        s.pairing -> "I'm pairing with the Muse app right now."
        !s.paired -> "This Portal is not paired with Muse yet. Open the settings to pair it."
        else -> "I can't reach Muse right now."
    }

    private fun onUtterance(pcm: ByteArray) {
        if (phase != Phase.LISTENING) return
        phase = Phase.THINKING
        _state.update { it.copy(phase = Phase.THINKING, level = 0f) }
        val core = app ?: return toIdle()
        thread { val id = core.askVoice(pcm); main.post { asked(id) } }
    }

    private fun nothingSaid() {
        if (phase == Phase.LISTENING) toIdle()
    }

    private fun toIdle() {
        phase = Phase.IDLE
        resetWake.set(true)
        if (ringer?.ringing != true) {
            AudioFocus.abandon()
        }
        _state.update { it.copy(phase = Phase.IDLE, level = 0f) }
    }

    /** Says why something cannot be done, and shows it. */
    private fun problem(text: String) {
        phase = Phase.SPEAKING
        AudioFocus.request()
        _state.update { it.copy(phase = Phase.SPEAKING, answer = text, answerIsProblem = true, heard = "") }
        say(text, again = false)
    }

    private fun say(text: String, again: Boolean) {
        val v = voice ?: return toIdle()
        v.say(text, level = ::voiceLevel) { finished ->
            main.post {
                if (phase != Phase.SPEAKING) return@post
                // An answer that asks something gets its answer without the wake word.
                if (finished && again && text.trimEnd().endsWith("?")) listen() else toIdle()
            }
        }
    }

    private fun voiceLevel(level: Float) {
        val now = System.currentTimeMillis()
        if (now - lastLevelAt < 40 && level > 0f) return
        lastLevelAt = now
        main.post { if (phase == Phase.SPEAKING) _state.update { it.copy(level = level) } }
    }

    private fun newTurn() {
        turn = 0
        minTurn = lastTurn + 1
    }

    private fun asked(id: Long) {
        lastTurn = maxOf(lastTurn, id)
        if (phase == Phase.THINKING && turn == 0L && id >= minTurn) turn = id
    }

    /** Whether a turn event belongs to the turn awaited. */
    private fun ours(id: Long): Boolean {
        if (phase != Phase.THINKING || id < minTurn) return false
        lastTurn = maxOf(lastTurn, id)
        if (turn == 0L) turn = id
        return turn == id
    }

    // ---- The microphone thread ----

    private fun onAudio(buf: ByteArray, n: Int) {
        val e = earsFor() ?: return
        val pcm = buf.copyOf(n)
        if (resetWake.getAndSet(false)) e.resetWake()
        when (phase) {
            Phase.LISTENING -> {
                if (beginUtterance.getAndSet(false)) {
                    e.begin()
                    skip = CHIME_SKIP
                }
                if (skip > 0 && !finishNow.get()) {
                    skip -= pcm.size
                    return
                }
                val result = if (finishNow.getAndSet(false)) Heymuse.Finished else e.listen(pcm)
                micLevel(pcm)
                when (result) {
                    Heymuse.Finished -> {
                        val utterance = e.utterance()
                        main.post { onUtterance(utterance) }
                    }
                    Heymuse.NothingSaid -> main.post { nothingSaid() }
                }
            }
            Phase.IDLE -> {
                if (settings.wakeWord && e.wake(pcm)) main.post { onWake() }
            }
            // While Muse thinks the room is not listened to; while the Portal speaks, its own voice
            // would be heard. A ring can be stopped by the wake word, as on the Echo.
            Phase.THINKING, Phase.SPEAKING -> {
                if (ringer?.ringing == true && settings.wakeWord && e.wake(pcm)) main.post { onWake() }
            }
        }
    }

    private fun earsFor(): Ears? {
        val want = earsWanted
        if (ears != null && want < 0) return ears
        if (want >= 0) {
            earsWanted = -1f
            ears = try {
                Heymuse.newEars(File(context.filesDir, "wake").absolutePath, want.toDouble()).also {
                    main.post { _state.update { s -> s.copy(wakeReady = true) } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "the wake word did not load", e)
                main.post { _state.update { s -> s.copy(wakeReady = false, problem = "The wake word did not load: ${e.message}") } }
                null
            }
        }
        return ears
    }

    private fun micLevel(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastLevelAt < 40) return
        lastLevelAt = now
        val level = Voice.loudness(pcm, pcm.size)
        main.post { if (phase == Phase.LISTENING) _state.update { it.copy(level = level) } }
    }

    private fun onWake() {
        if (ringer?.ringing == true) {
            stopRinging()
            return
        }
        if (phase != Phase.IDLE) return
        Log.i(TAG, "wake word")
        bringToFront()
        listen()
    }

    private fun bringToFront() {
        if (!settings.openOnWake) return
        val canStart = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || AndroidSettings.canDrawOverlays(context)
        if (!canStart) {
            Log.w(TAG, "cannot bring to front: missing overlay permission")
            return
        }
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ))
        }.onFailure { Log.w(TAG, "could not come to the front", it) }
    }

    fun stopRinging() {
        ringer?.stop()
        if (phase == Phase.IDLE) AudioFocus.abandon()
        _state.update { it.copy(ringing = null) }
    }

    // ---- Pairing, timers and alarms, for the screen ----

    fun startPairing() {
        val core = app ?: return
        _state.update { it.copy(problem = "") }
        val p = BlePeripheral(context, onWrite = { core.pairingWrite(it) }, onDisconnect = { core.pairingDisconnected() })
        peripheral = p
        thread(name = "pairing") {
            try {
                core.startPairing(p, "")
            } catch (e: Exception) {
                Log.w(TAG, "pairing did not start", e)
                main.post { _state.update { it.copy(problem = e.message ?: "Pairing did not start.") } }
            }
        }
    }

    fun cancelPairing() {
        thread { app?.cancelPairing() }
    }

    fun unpair() {
        thread {
            runCatching { app?.unpair() }.onFailure { e -> main.post { _state.update { it.copy(problem = e.message ?: "") } } }
        }
    }

    fun cancelTimer(id: Long) = thread { app?.cancelTimer(id) }
    fun deleteAlarm(id: Long) = thread { runCatching { app?.deleteAlarm(id) } }

    /** Asks Muse in words, from the screen. */
    fun ask(text: String) {
        if (text.isBlank()) return
        val status = _state.value.status
        if (!status.online) return problem(excuse(status))
        voice?.stop()
        newTurn()
        phase = Phase.THINKING
        _state.update { it.copy(phase = Phase.THINKING, heard = text, answer = "", answerIsProblem = false) }
        val core = app ?: return toIdle()
        thread { val id = core.askText(text); main.post { asked(id) } }
    }

    /** Says a line in the chosen voice, to hear what it sounds like. */
    fun testVoice() {
        if (phase != Phase.IDLE) return
        phase = Phase.SPEAKING
        _state.update { it.copy(phase = Phase.SPEAKING) }
        say("Hi, I'm Muse. This is how I sound on your Portal.", again = false)
    }

    private fun refreshDevice() {
        val core = app ?: return
        val timers = runCatching {
            val a = JSONArray(core.timers())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { TimerItem(it.getLong("id"), it.optString("label"), it.getLong("end")) } }
        }.getOrDefault(emptyList())
        val alarms = runCatching {
            val a = JSONArray(core.alarms())
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let {
                    AlarmItem(it.getLong("id"), it.getInt("hour"), it.getInt("minute"), it.optString("days"),
                        it.optString("label"), it.optBoolean("on"), it.optLong("next"))
                }
            }
        }.getOrDefault(emptyList())
        main.post { _state.update { it.copy(timers = timers, alarms = alarms) } }
    }

    // ---- The Go core's callbacks, on its goroutines ----

    private val host = object : Host {
        override fun onStatus(status: String) {
            val s = MuseStatus.parse(status)
            main.post { _state.update { it.copy(status = s) } }
        }

        override fun onHeard(turn: Long, text: String) {
            main.post { if (ours(turn)) _state.update { it.copy(heard = text) } }
        }

        override fun onAnswer(turn: Long, text: String) {
            main.post {
                if (!ours(turn)) return@post
                phase = Phase.SPEAKING
                _state.update { it.copy(phase = Phase.SPEAKING, answer = text, answerIsProblem = false) }
                say(text, again = true)
            }
        }

        override fun onTurnFailed(turn: Long, reason: String) {
            main.post { if (ours(turn)) problem(reason) }
        }

        override fun onTurnEnded(turn: Long) {
            main.post { if (ours(turn)) toIdle() }
        }

        override fun onDeviceChanged() = refreshDevice()

        override fun setVolume(level: Long): Long =
            Sound.setVolume(context, level.toInt(), Heymuse.VolumeSteps.toInt()).toLong()

        override fun volume(): Long = Sound.volume(context, Heymuse.VolumeSteps.toInt()).toLong()

        override fun ring(kind: String, label: String) {
            val what = (if (kind == "alarm") "Alarm" else "Timer") + if (label.isNotEmpty()) ": $label" else ""
            main.post {
                ringer?.start()
                _state.update { it.copy(ringing = what) }
                bringToFront()
            }
        }

        override fun stopRinging(): Boolean {
            val was = ringer?.stop() ?: false
            main.post { _state.update { it.copy(ringing = null) } }
            return was
        }

        override fun stopSpeaking() {
            voice?.stop()
        }

        override fun log(level: String, line: String) {
            when (level) {
                "error" -> Log.e("HeyMuseCore", line)
                "warn" -> Log.w("HeyMuseCore", line)
                "debug" -> Log.d("HeyMuseCore", line)
                else -> Log.i("HeyMuseCore", line)
            }
        }
    }
}
