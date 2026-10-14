package io.github.pgodlews.heymuse

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Audio out: the media stream, so the Portal's volume buttons and Muse's set_volume move it. */
object Sound {
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** A streaming track of 16-bit mono at [rate]. */
    fun stream(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate / 2)) // a quarter second of 16-bit audio
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** The raw clips, 16-bit mono at 48 kHz (Home Assistant Voice sounds, CC BY 4.0). */
    const val CLIP_RATE = 48000

    private val clips = HashMap<Int, ByteArray>()

    private fun clip(context: Context, res: Int): ByteArray =
        synchronized(clips) { clips.getOrPut(res) { context.resources.openRawResource(res).use { it.readBytes() } } }

    /** Plays a clip once, off the caller's thread. */
    fun play(context: Context, res: Int) {
        val pcm = clip(context, res)
        thread(name = "clip") {
            val track = stream(CLIP_RATE)
            try {
                track.play()
                track.write(pcm, 0, pcm.size)
                // Let the tail drain before the track goes.
                Thread.sleep(pcm.size * 1000L / (2 * CLIP_RATE) / 4 + 150)
                track.stop()
            } catch (_: Exception) {
            } finally {
                track.release()
            }
        }
    }

    /** The volume, 0 to [steps], read off the media stream. */
    fun volume(context: Context, steps: Int): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * steps.toFloat() / max).roundToInt()
    }

    /** Sets the media stream to [level] of [steps], showing the Portal's own volume bar. */
    fun setVolume(context: Context, level: Int, steps: Int): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (level.coerceIn(0, steps) * max.toFloat() / steps).roundToInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        return volume(context, steps)
    }
}

/**
 * A ring that goes on until it is stopped: a timer or an alarm going off. It plays the clip with a
 * pause between, for at most [MAX_MS], so a Portal nobody is near does not ring all day.
 */
class Ringer(private val context: Context) {
    @Volatile private var running: Thread? = null

    val ringing: Boolean get() = running != null

    @Synchronized
    fun start() {
        if (running != null) return
        val pcm = context.resources.openRawResource(R.raw.ring).use { it.readBytes() }
        running = thread(name = "ring") {
            val track = Sound.stream(Sound.CLIP_RATE)
            val gap = ByteArray(Sound.CLIP_RATE / 2 * 2) // half a second
            val until = System.currentTimeMillis() + MAX_MS
            try {
                track.play()
                while (running === Thread.currentThread() && System.currentTimeMillis() < until) {
                    if (track.write(pcm, 0, pcm.size) < 0) break
                    if (running !== Thread.currentThread()) break
                    track.write(gap, 0, gap.size)
                }
                track.pause()
                track.flush()
            } catch (_: Exception) {
            } finally {
                track.release()
                synchronized(this) { if (running === Thread.currentThread()) running = null }
                onEnded?.invoke()
            }
        }
    }

    /** Stops the ring and says whether there was one. */
    @Synchronized
    fun stop(): Boolean {
        val was = running != null
        running = null
        return was
    }

    var onEnded: (() -> Unit)? = null

    companion object {
        const val MAX_MS = 5 * 60 * 1000L
    }
}
