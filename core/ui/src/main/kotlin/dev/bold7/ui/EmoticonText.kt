package dev.bold7.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// A line of text with OS 7's emoticon images in place of its emojis: the
// preview of a message in a list, the summary of a notification, a search result. The thread's
// bubbles draw the same images (apps/messages, EmoticonSpan). The cut is pure and tested; the
// drawing reads what it computed.

/** A piece of a line: [text], or when [emoticon] is not -1 that emoticon's image (and [text] its characters). */
class Chunk(val text: String, val emoticon: Int) {
    val isImage get() = emoticon >= 0
}

/** The width of an emoticon's image in a text of [size] px: the 27 px strip is drawn for a 27 px text (sim-148, C), scaled with it. */
fun emoticonWidth(size: Float): Float = size.roundToInt().toFloat()

/** [text] cut in its text and its emoticons, in order; empty for an empty text. [shortcuts] as [Emoticons.find]. */
fun emoticonChunks(text: String, shortcuts: Boolean = false): List<Chunk> {
    val out = ArrayList<Chunk>(1)
    var at = 0
    for (f in Emoticons.find(text, shortcuts)) {
        if (f.start > at) out += Chunk(text.substring(at, f.start), -1)
        out += Chunk(text.substring(f.start, f.end), f.index)
        at = f.end
    }
    if (at < text.length) out += Chunk(text.substring(at), -1)
    return out
}

/** The advance of [text] in a font of [size] px, [measure] giving the text pieces', an image counting its own width. */
fun emoticonAdvance(text: String, size: Float, measure: (String) -> Float, shortcuts: Boolean = false): Float {
    val chunks = emoticonChunks(text, shortcuts)
    if (chunks.none { it.isImage }) return measure(text)
    return chunks.sumOf { (if (it.isImage) emoticonWidth(size) else measure(it.text)).toDouble() }.toFloat()
}

/**
 * [text] as [fit] cuts it to [room] px with its images counted at their width: a cut falls between
 * characters, so an image is kept whole or dropped, never cut in two. Simplification: with [shortcuts]
 * a cut may leave a shortcut unconverted before the mark; emojis only (the lists' case) are exact.
 */
fun fitEmoticons(text: String, room: Float, size: Float, measure: (String) -> Float, shortcuts: Boolean = false): String =
    fit(text, room) { emoticonAdvance(it, size, measure, shortcuts) }

/** [text] fitted to [room] px in this paint's font, its emoticons as images. */
fun Paint.fitEmoticons(text: String, room: Float): String = fitEmoticons(text, room, textSize, ::measureText)

/** The 27 px strip of the emoticons (smileys_27x27.png, A), decoded once off the main thread and shared by every list. */
object EmoticonStrip {
    @Volatile var bitmap: Bitmap? = null
        private set
    private var loading = false
    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "emoticon-strip").apply { isDaemon = true } } }

    /** Decodes the strip if not yet; for a background thread. */
    fun decode(context: Context): Bitmap? {
        bitmap?.let { return it }
        val b = themeBitmap(context, "ui_smileys_27")
        bitmap = b
        return b
    }

    /** Called when [chunks] are about to be drawn in [view]: starts the decoding if an image shows and the strip is not there, then redraws [view]. */
    fun want(view: View, chunks: List<Chunk>) {
        if (bitmap != null || chunks.none { it.isImage }) return
        synchronized(this) {
            if (loading) return
            loading = true
        }
        val context = view.context.applicationContext
        executor.execute {
            decode(context)
            synchronized(this) { loading = false }
            view.post { view.invalidate() }
        }
    }
}

/**
 * Draws a line of [Chunk]s: the text in its paint, each image at the text's size, its bottom 3 px
 * (at 27 px) under the baseline as in the bubbles (sim-148, C). A view keeps one; nothing is
 * allocated while drawing.
 */
class EmoticonPen {
    private val src = Rect()
    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws [chunks] from [x] on [baseline] with [paint]; returns the x where it ends. An image whose strip is not decoded yet leaves its room blank. */
    fun draw(canvas: Canvas, chunks: List<Chunk>, x: Float, baseline: Float, paint: Paint): Float {
        var at = x
        val size = emoticonWidth(paint.textSize)
        val strip = EmoticonStrip.bitmap
        for (c in chunks) {
            if (c.isImage) {
                if (strip != null) {
                    val left = at.roundToInt()
                    val bottom = baseline.roundToInt() + (BELOW * size / CELL).roundToInt()
                    src.set(c.emoticon * CELL, 0, (c.emoticon + 1) * CELL, CELL)
                    dst.set(left, bottom - size.toInt(), left + size.toInt(), bottom)
                    canvas.drawBitmap(strip, src, dst, bitmapPaint)
                }
                at += size
            } else {
                canvas.drawText(c.text, at, baseline, paint)
                at += paint.measureText(c.text)
            }
        }
        return at
    }

    private companion object {
        const val CELL = 27
        const val BELOW = 3
    }
}
