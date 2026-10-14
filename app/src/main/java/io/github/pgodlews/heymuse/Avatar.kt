package io.github.pgodlews.heymuse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import java.io.File

/**
 * The Muse character, if the owner has put one on the Portal: sprite sheets made by
 * tools/muse-avatar from their own copy of Meta's SDK, pushed to the app's files at
 * `/sdcard/Android/data/io.github.pgodlews.heymuse/files/muse-avatar/`. The character is Meta's and
 * is not in this app.
 *
 * The set is what hey-muse's Echo Show reads: manifest.json, and one PNG per animation (idle,
 * listening, thinking, talking), frames side by side in rows of `columns`, `cell` pixels square.
 */
class Avatar private constructor(
    private val cell: Int,
    private val fps: Double,
    private val animations: Map<String, Animation>,
) {
    class Animation(val frames: Int, val columns: Int, val loop: Boolean, val sheet: ImageBitmap)

    fun frameAt(name: String, elapsedMs: Long): Int {
        val a = animations[name] ?: return 0
        val i = (elapsedMs / 1000.0 * fps).toInt().coerceAtLeast(0)
        return if (a.loop) i % a.frames else minOf(i, a.frames - 1)
    }

    /** Draws the character playing [name] in a square [side] pixels across, centered, in whole pixels. */
    @Composable
    fun Show(name: String, modifier: Modifier) {
        var started by remember(name) { mutableLongStateOf(0L) }
        var now by remember { mutableLongStateOf(0L) }
        LaunchedEffect(name) {
            started = 0L
            while (true) {
                withFrameMillis { t ->
                    if (started == 0L) started = t
                    now = t
                }
            }
        }
        val a = animations[name] ?: animations[IDLE] ?: return
        Canvas(modifier) {
            val i = frameAt(name, now - started)
            val side = minOf(size.width, size.height).toInt()
            val scale = maxOf(1, side / cell)
            val px = cell * scale
            val x = ((size.width - px) / 2).toInt()
            val y = ((size.height - px) / 2).toInt()
            drawImage(
                image = a.sheet,
                srcOffset = IntOffset((i % a.columns) * cell, (i / a.columns) * cell),
                srcSize = IntSize(cell, cell),
                dstOffset = IntOffset(x, y),
                dstSize = IntSize(px, px),
                filterQuality = FilterQuality.None,
            )
        }
    }

    companion object {
        private const val TAG = "HeyMuseAvatar"
        const val IDLE = "idle"
        const val LISTENING = "listening"
        const val THINKING = "thinking"
        const val TALKING = "talking"
        private val NEEDED = listOf(IDLE, LISTENING, THINKING, TALKING)
        private const val MAX_PIXELS = 32L shl 20

        fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "muse-avatar") }

        /** The set on the Portal, or null when there is none or it cannot be read (the log says why). */
        fun load(context: Context): Avatar? {
            val dir = dir(context) ?: return null
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return null
            return try {
                val m = JSONObject(manifest.readText())
                val cell = m.getInt("cell")
                val fps = m.getDouble("fps")
                require(cell in 1..512) { "a cell of $cell pixels" }
                require(fps > 0 && fps <= 60) { "$fps frames a second" }
                val anims = m.getJSONObject("animations")
                val out = HashMap<String, Animation>()
                var pixels = 0L
                for (name in NEEDED) {
                    val a = anims.optJSONObject(name) ?: throw IllegalArgumentException("no $name animation")
                    val frames = a.getInt("frames")
                    val columns = a.getInt("columns")
                    require(frames in 1..2000 && columns >= 1) { "$name has $frames frames in rows of $columns" }
                    val rows = (frames + columns - 1) / columns
                    pixels += columns.toLong() * cell * rows * cell
                    require(pixels <= MAX_PIXELS) { "the sheets are too large" }
                    val bmp = BitmapFactory.decodeFile(File(dir, "$name.png").path,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })
                        ?: throw IllegalArgumentException("$name.png is not a picture")
                    require(bmp.width == columns * cell && bmp.height == rows * cell) {
                        "$name.png is ${bmp.width}x${bmp.height}, and its manifest says ${columns * cell}x${rows * cell}"
                    }
                    out[name] = Animation(frames, columns, a.optBoolean("loop", true), bmp.asImageBitmap())
                }
                Log.i(TAG, "Muse avatar loaded")
                Avatar(cell, fps, out)
            } catch (e: Exception) {
                Log.w(TAG, "no Muse avatar: ${e.message}")
                null
            }
        }
    }
}
