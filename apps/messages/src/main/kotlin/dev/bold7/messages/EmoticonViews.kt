package dev.bold7.messages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ReplacementSpan
import android.view.View
import dev.bold7.design.DialogColors
import dev.bold7.design.Typography
import dev.bold7.ui.Dir
import dev.bold7.ui.EmoticonStrip
import dev.bold7.ui.Emoticons
import dev.bold7.ui.osEmoji
import dev.bold7.ui.FocusDialog
import dev.bold7.ui.Focused
import dev.bold7.ui.fillPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

// The emoticons of OS 7 in the thread (docs/BBOS7-VISUEL.md §16): their image in
// the text, and Add Smiley's list. Measures from sim-145 to sim-148 (C).

/** A cell of the 27 px strip (smileys_27x27.png, A) in place of an emoticon's characters. */
class EmoticonSpan(index: Int) : ReplacementSpan() {
    private companion object {
        const val SIZE = 27
        /** The image's bottom under the text's baseline (sim-148: its row 459, the text's last 456). */
        const val BELOW = 3
    }

    private val src = Rect(index * SIZE, 0, (index + 1) * SIZE, SIZE)
    private val dst = Rect()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        // The line keeps the text's own height.
        if (fm != null) paint.getFontMetricsInt(fm)
        return SIZE
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val strip = EmoticonStrip.bitmap ?: return
        dst.set(x.toInt(), y + BELOW - SIZE, x.toInt() + SIZE, y + BELOW)
        canvas.drawBitmap(strip, src, dst, null)
    }
}

/**
 * [text] with OS 7's image on each emoticon it carries: its emojis, and with [shortcuts] the texts
 * the 9900's parser read (a bubble; the field keeps them as text, sim-149, C). [text] itself if none.
 */
fun withEmoticons(text: String, shortcuts: Boolean): CharSequence {
    val shown = osEmoji(text)
    val found = Emoticons.find(shown, shortcuts)
    if (found.isEmpty()) return shown
    return SpannableString(shown).also { s -> for (f in found) s.setSpan(EmoticonSpan(f.index), f.start, f.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

/** The field's text: its emoticon images laid again over what it holds (only emojis, sim-149). */
fun markEmoticons(text: Spannable) {
    for (old in text.getSpans(0, text.length, EmoticonSpan::class.java)) text.removeSpan(old)
    for (f in Emoticons.find(text, shortcuts = false)) text.setSpan(EmoticonSpan(f.index), f.start, f.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/**
 * Add Smiley's list (sim-145, sim-147, C; gsm 024, C 7.0): the dialog's box (border_popup.png,
 * #212429, A) centred, the focused emoticon's title over a grid of 7 columns of 69 px cells, the
 * images of the 45 px strip 12 px in, the last row centred, the focus a white line of one pixel
 * cut at its corners. On the 720 px square the seven rows fit without scrolling (D: the 9900
 * shows five and scrolls, with its blue arrows).
 */
@SuppressLint("ViewConstructor")
class EmoticonView(context: Context, private val images: ThreadImages, val grid: EmoticonGrid) : View(context), Focused {
    companion object {
        const val COLUMNS = 7
        private const val CELL = 69
        private const val ICON = 45
        private const val ICON_IN = 12
        /** From the box's border pixel (sim-145: x 37 and 602, y 27 and 448): the cells, the title's baseline; the rows to the bottom border. */
        private const val CELLS_X = 41
        private const val CELLS_Y = 53
        private const val TITLE_BASELINE = 43
        private const val BOTTOM = 23
        /** The focus' corners: three pixels cut on the diagonal (sim-145). */
        private const val CUT = 3
    }

    override var focused: Int = 0
        set(value) {
            if (field == value) return
            field = value
            contentDescription = Emoticons.ALL[value].title
            invalidate()
        }

    private val popup = themeImage(context, "ui_popup")
    private val title = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text, align = Paint.Align.CENTER)
    private val white = fillPaint(DialogColors.Text)
    private val src = Rect()
    private val dst = Rect()
    private val box = Rect()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = Emoticons.ALL[0].title
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val bw = 2 * CELLS_X + COLUMNS * CELL + 1
        val bh = CELLS_Y + grid.rows * CELL + BOTTOM + 1
        box.set((w - bw) / 2, (h - bh) / 2, (w - bw) / 2 + bw, (h - bh) / 2 + bh)
    }

    private fun cellLeft(i: Int) = box.left + CELLS_X + grid.column(i) * CELL
    private fun cellTop(i: Int) = box.top + CELLS_Y + i / COLUMNS * CELL

    /** The emoticon under ([x], [y]), or null. */
    fun itemAt(x: Float, y: Float): Int? = Emoticons.ALL.indices.firstOrNull {
        x >= cellLeft(it) && x < cellLeft(it) + CELL && y >= cellTop(it) && y < cellTop(it) + CELL
    }

    override fun onDraw(canvas: Canvas) {
        // border_popup.png, 40x40: its border pixels are its column 4 and 35, its row 2 and 33 (as core/ui's DialogView).
        popup.setBounds(box.left - 4, box.top - 2, box.right + 4, box.bottom + 6)
        popup.draw(canvas)
        val e = Emoticons.ALL[focused]
        canvas.drawText(e.title, box.exactCenterX(), (box.top + TITLE_BASELINE).toFloat(), title)
        val strip = images.smileys45
        for (i in Emoticons.ALL.indices) {
            val x = cellLeft(i)
            val y = cellTop(i)
            if (strip != null) {
                src.set(i * ICON, 0, (i + 1) * ICON, ICON)
                dst.set(x + ICON_IN, y + ICON_IN, x + ICON_IN + ICON, y + ICON_IN + ICON)
                canvas.drawBitmap(strip, src, dst, null)
            }
            if (i == focused) drawFocus(canvas, x, y)
        }
    }

    private fun drawFocus(canvas: Canvas, x: Int, y: Int) {
        val r = x + CELL - 1
        val b = y + CELL - 1
        val l = x.toFloat()
        val t = y.toFloat()
        canvas.drawRect(l + CUT, t, r - CUT + 1f, t + 1, white)
        canvas.drawRect(l + CUT, b.toFloat(), r - CUT + 1f, b + 1f, white)
        canvas.drawRect(l, t + CUT, l + 1, b - CUT + 1f, white)
        canvas.drawRect(r.toFloat(), t + CUT, r + 1f, b - CUT + 1f, white)
        // The cut corners: two pixels each.
        for ((cx, cy, dx, dy) in listOf(intArrayOf(x, y, 1, 1), intArrayOf(r, y, -1, 1), intArrayOf(x, b, 1, -1), intArrayOf(r, b, -1, -1))) {
            for (k in 1 until CUT) {
                val px = (cx + dx * k).toFloat()
                val py = (cy + dy * (CUT - k)).toFloat()
                canvas.drawRect(px, py, px + 1, py + 1, white)
            }
        }
    }
}

/**
 * Add Smiley as a window over the thread ([FocusDialog]): the trackpad moves on the grid, its click
 * or Enter puts the emoticon in, Escape or Back closes, a touch on a cell chooses it, elsewhere
 * closes (D). [onChosen] gets the emoticon's index after the window closes.
 */
class EmoticonDialog(context: Context, images: ThreadImages, private val chosen: (Int) -> Unit) :
    FocusDialog<EmoticonView>(
        context,
        EmoticonView(context, images, EmoticonGrid(Emoticons.ALL.size, EmoticonView.COLUMNS)),
        Emoticons.ALL.size,
        // The thread has no status bar (sim-50): its window over it neither.
        hideStatusBar = true,
        touchOutsideCancels = true,
    ) {
    override fun itemAt(x: Float, y: Float): Int? = view.itemAt(x, y)
    override fun onChosen(item: Int) = chosen(item)
    override fun next(from: Int, dir: Dir): Int = view.grid.next(from, dir)
}
