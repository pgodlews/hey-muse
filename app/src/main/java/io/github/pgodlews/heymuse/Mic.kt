package io.github.pgodlews.heymuse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/**
 * The microphone, 16 kHz 16-bit mono in 20 ms reads, on a thread of its own. Every read goes to
 * [onAudio] on that thread.
 *
 * What the Portal does to an app's microphone:
 * - With voice processing, VOICE_COMMUNICATION goes through the DSP: the 4-mic beam, noise
 *   suppression, gain control and echo cancellation. Some firmware refuses it, and then MIC is used.
 * - The privacy switch, and Portal's own assistant while it is talking, do not fail a read: they
 *   hand back silence, every sample exactly zero. That is reported as [State.SILENCED].
 * - After an assistant session a read can start failing; the recorder is then made afresh.
 */
class Mic(
    private val context: Context,
    private val voiceProcessing: () -> Boolean,
    private val onState: (State) -> Unit,
    private val onAudio: (ByteArray, Int) -> Unit,
) {
    enum class State { OFF, LISTENING, SILENCED, NO_PERMISSION, FAILED }

    @Volatile private var running: Thread? = null
    @Volatile var state: State = State.OFF
        private set

    fun start() {
        synchronized(this) {
            if (running != null) return
            running = thread(name = "mic", priority = Thread.MAX_PRIORITY) { loop() }
        }
    }

    fun stop() {
        val t = synchronized(this) { running.also { running = null } }
        t?.join(2000)
        set(State.OFF)
    }

    /** Makes the recorder afresh, for a change of voice processing. */
    fun restart() {
        stop()
        start()
    }

    private fun set(s: State) {
        if (state != s) {
            state = s
            onState(s)
        }
    }

    private fun loop() {
        val me = Thread.currentThread()
        while (running === me) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                set(State.NO_PERMISSION)
                Thread.sleep(1000)
                continue
            }
            val rec = open()
            if (rec == null) {
                set(State.FAILED)
                Thread.sleep(2000)
                continue
            }
            val effects = effects(rec)
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "the recorder did not start")
                    set(State.FAILED)
                    Thread.sleep(2000)
                    continue
                }
                read(rec, me)
            } catch (e: Exception) {
                Log.w(TAG, "the microphone failed", e)
                set(State.FAILED)
            } finally {
                effects.forEach { runCatching { it.release() } }
                runCatching { rec.stop() }
                rec.release()
            }
            if (running === me) Thread.sleep(1000)
        }
    }

    /** Reads until the recorder fails or the mic is stopped. */
    private fun read(rec: AudioRecord, me: Thread) {
        val buf = ByteArray(CHUNK)
        var zeros = 0
        set(State.LISTENING)
        while (running === me) {
            val n = rec.read(buf, 0, buf.size)
            if (n < 0) {
                Log.w(TAG, "a read failed ($n); making the recorder afresh")
                set(State.FAILED)
                return
            }
            if (n == 0) continue
            if (allZero(buf, n)) {
                if (++zeros == SILENCED_AFTER) set(State.SILENCED)
            } else {
                zeros = 0
                set(State.LISTENING)
            }
            onAudio(buf, n)
        }
    }

    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, RATE) // at least half a second
        val sources = if (voiceProcessing()) {
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        } else {
            listOf(MediaRecorder.AudioSource.MIC)
        }
        for (source in sources) {
            val rec = try {
                @Suppress("MissingPermission")
                AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            } catch (e: Exception) {
                Log.w(TAG, "audio source $source refused", e)
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "recording from audio source $source")
                return rec
            }
            rec.release()
        }
        return null
    }

    private fun effects(rec: AudioRecord): List<android.media.audiofx.AudioEffect> {
        if (!voiceProcessing()) return emptyList()
        val out = mutableListOf<android.media.audiofx.AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; out += it }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; out += it }
        }
        return out
    }

    companion object {
        private const val TAG = "HeyMuseMic"
        const val RATE = 16000
        /** 20 ms of 16-bit mono. */
        const val CHUNK = RATE / 50 * 2
        /** A second of exact zeros is the mic cut off. */
        private const val SILENCED_AFTER = 50

        fun allZero(buf: ByteArray, n: Int): Boolean {
            for (i in 0 until n) if (buf[i].toInt() != 0) return false
            return true
        }
    }
}
