package io.github.pgodlews.heymuse

import android.content.Context
import android.media.AudioTrack
import android.util.Log
import heymuse.AudioSink
import heymuse.Heymuse
import heymuse.Speech
import kotlin.concurrent.thread

/**
 * Says Muse's answers: in a cloud voice when there is a key for one, else in the built-in voice,
 * which also steps in when the cloud fails before it has said anything (a bad or spent key, no
 * network). One answer at a time; a new one cuts off the last.
 */
class Voice(private val context: Context, private val settings: Settings) {
    private val lock = Any()
    private var current: Utterance? = null

    private class Utterance {
        @Volatile var stopped = false
        @Volatile var track: AudioTrack? = null
        @Volatile var speech: Speech? = null
    }

    val speaking: Boolean get() = synchronized(lock) { current != null }

    /**
     * Says [text] off the caller's thread, then calls [done] with whether it was said to the end
     * (false when it failed or was stopped). [level] hears how loud each piece is, 0 to 1, for the
     * screen.
     */
    fun say(text: String, level: (Float) -> Unit = {}, done: (Boolean) -> Unit) {
        val u = Utterance()
        synchronized(lock) {
            current?.let { stopLocked(it) }
            current = u
        }
        thread(name = "voice") {
            var finished = false
            try {
                finished = speak(u, text, level)
            } catch (e: Exception) {
                Log.w(TAG, "the voice failed", e)
            } finally {
                synchronized(lock) { if (current === u) current = null }
            }
            done(finished && !u.stopped)
        }
    }

    /** Cuts off what is being said. */
    fun stop() {
        synchronized(lock) {
            current?.let { stopLocked(it) }
            current = null
        }
    }

    private fun stopLocked(u: Utterance) {
        u.stopped = true
        u.speech?.cancel()
        runCatching { u.track?.pause(); u.track?.flush() }
    }

    private fun speak(u: Utterance, text: String, level: (Float) -> Unit): Boolean {
        val voice = settings.effectiveVoice
        if (voice != Settings.BUILT_IN) {
            var started = false
            val ok = runCatching {
                play(u, Heymuse.CloudRate.toInt(), level) { write ->
                    val speech = Heymuse.newSpeech(
                        settings.speechBase, settings.speechKey, settings.speechModel, voice, settings.speechStyle
                    )
                    u.speech = speech
                    if (u.stopped) speech.cancel()
                    speech.say(text, AudioSink { pcm ->
                        started = true
                        if (!write(pcm, pcm.size)) throw InterruptedException("stopped")
                    })
                }
            }
            if (ok.isSuccess || u.stopped) return ok.getOrDefault(false)
            Log.w(TAG, "the cloud voice failed${if (started) "" else ", so the built-in voice says it"}", ok.exceptionOrNull())
            if (started) return false
        }
        return play(u, Pico.RATE, level) { write ->
            if (!Pico.speak(context, text) { pcm, n -> write(pcm, n) }) throw IllegalStateException("the built-in voice failed")
        }
    }

    /**
     * Runs [produce] with a writer onto a fresh track at [rate], and waits for the track to play out.
     * The writer returns false once the utterance has been stopped.
     */
    private fun play(u: Utterance, rate: Int, level: (Float) -> Unit, produce: ((ByteArray, Int) -> Boolean) -> Unit): Boolean {
        val track = Sound.stream(rate)
        u.track = track
        var written = 0L
        try {
            if (u.stopped) return false
            track.play()
            produce { pcm, n ->
                if (u.stopped) return@produce false
                level(loudness(pcm, n))
                var off = 0
                while (off < n && !u.stopped) {
                    val w = track.write(pcm, off, n - off)
                    if (w < 0) return@produce false
                    off += w
                }
                written += n / 2
                !u.stopped
            }
            if (u.stopped) return false
            // Wait for what was written to be heard.
            val deadline = System.currentTimeMillis() + written * 1000 / rate + 2000
            while (!u.stopped && track.playbackHeadPosition < written && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            return !u.stopped
        } finally {
            level(0f)
            runCatching { track.stop() }
            track.release()
            u.track = null
        }
    }

    companion object {
        private const val TAG = "HeyMuseVoice"

        /** How loud a piece of 16-bit audio is, roughly 0 to 1. */
        fun loudness(pcm: ByteArray, n: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val rms = if (n >= 2) Math.sqrt(sum / (n / 2)) else 0.0
            return (rms / 6000.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
