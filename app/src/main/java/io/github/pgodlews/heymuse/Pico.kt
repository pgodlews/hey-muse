package io.github.pgodlews.heymuse

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The built-in voice: SVOX Pico, U.S. English, 16 kHz. It needs nothing but the APK, makes five
 * seconds of speech in a fraction of a second, and sounds like a machine from 2009.
 *
 * The voice data is read by path, so it is copied out of the APK's assets once.
 */
object Pico {
    const val RATE = 16000
    private const val TAG = "HeyMusePico"
    private val FILES = listOf("en-US_ta.bin", "en-US_lh0_sg.bin")

    fun interface Sink {
        /** Takes the next [length] bytes of [pcm]; false stops the voice. */
        fun write(pcm: ByteArray, length: Int): Boolean
    }

    private var handle = 0L

    init {
        System.loadLibrary("pico")
    }

    @Synchronized
    private fun open(context: Context): Long {
        if (handle != 0L) return handle
        val dir = File(context.filesDir, "pico")
        dir.mkdirs()
        for (name in FILES) {
            val out = File(dir, name)
            context.assets.openFd("pico/$name").use { fd ->
                if (out.length() == fd.length) return@use
                fd.createInputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        handle = nativeOpen(dir.absolutePath)
        if (handle == 0L) Log.e(TAG, "the built-in voice did not start")
        return handle
    }

    /** Says [text] into [sink] and returns false if the engine failed. One voice at a time. */
    @Synchronized
    fun speak(context: Context, text: String, sink: Sink): Boolean {
        val h = open(context)
        if (h == 0L) return false
        return nativeSpeak(h, text, sink)
    }

    @JvmStatic private external fun nativeOpen(dir: String): Long
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeSpeak(handle: Long, text: String, sink: Sink): Boolean
}
