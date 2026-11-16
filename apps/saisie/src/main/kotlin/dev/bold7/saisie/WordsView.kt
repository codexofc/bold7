package dev.bold7.saisie

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.textPaint

/**
 * Predictive's list under the word (sim-94a, sim-95b, sim-96a, sim-97a, C), measured on the
 * captures at the 9900's size, the Q25's own: a frame of #212429, 10 px around the words; each word
 * in a cell 45 px high, 10 px on each side of its text, 2 px of the frame between two; the cell in
 * focus blue, the others grey, each from its top colour to its bottom one over its upper 16 px;
 * BBAlpha Sans 27 ("This" 61 px wide on sim-94a as on the TTF), bold for a completion, plain for a
 * correction, italic for the word typed; white in focus, #202121 otherwise. The frame's top left is
 * the word's bottom left (sim-94a: the word from x 96, its blue down to y 78, the frame from 96,
 * 79). The corners' radius is by eye (D). Above the word when there is no room below, moved left
 * when there is none on the right (D).
 *
 * As tall as the screen, like [SymbolView], so that the window never resizes the app: only [panel]
 * is drawn and touched. A touch on a word takes it ([onChoose], D).
 */
@SuppressLint("ViewConstructor")
class WordsView(context: Context, private val onChoose: (Int) -> Unit) : View(context) {
    private var items: List<Suggestion> = emptyList()
    private var focus = 0
    private var widths = IntArray(0)
    private var natural = IntArray(0)
    /** The words as drawn: cut by fit when the list is wider than the screen. */
    private var shown: List<String> = emptyList()

    /** The word's left, top and bottom on the screen (CursorAnchorInfo), NaN while unknown. */
    private var anchorX = Float.NaN
    private var anchorTop = Float.NaN
    private var anchorBottom = Float.NaN

    /** False from a new list until the app tells where its word is, so that it never shows at the last word's place first. */
    var ready = false
        set(value) {
            field = value
            place()
        }

    /** The frame's box, in this view; empty when nothing is drawn. */
    val panel = Rect()

    private val frame = fillPaint(FRAME)
    private val focused = cellPaint(FOCUS_TOP, FOCUS_BOTTOM)
    private val grey = cellPaint(GREY_TOP, GREY_BOTTOM)
    private val paints = Look.entries.associateWith { look ->
        textPaint(Typography.TEXT, if (look == Look.COMPLETION) Typography.BOLD else Typography.REGULAR, TEXT, italic = look == Look.TYPED)
    }
    private val box = RectF()
    private val at = IntArray(2)

    fun show(items: List<Suggestion>, focus: Int) {
        this.items = items
        this.focus = focus
        natural = IntArray(items.size) { paints.getValue(items[it].look).measureText(items[it].text).toInt() + 2 * CELL_PAD }
        relayout()
        place()
    }

    fun anchor(x: Float, top: Float, bottom: Float) {
        anchorX = x
        anchorTop = top
        anchorBottom = bottom
        place()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The whole height the window may take: the screen's (as SymbolView).
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
        else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
        place()
    }

    // The cells' widths and words, fitted to the screen: only a list wider than it pays for a cut.
    private fun relayout() {
        widths = if (width > 0) WordsLayout.cells(natural, width, SEPARATOR, PAD) else natural
        shown = items.indices.map { i ->
            if (widths[i] == natural[i]) items[i].text else paints.getValue(items[i].look).fit(items[i].text, (widths[i] - 2 * CELL_PAD).toFloat())
        }
    }

    private fun place() {
        panel.setEmpty()
        if (items.isNotEmpty() && ready && width > 0) {
            val pw = widths.sum() + SEPARATOR * (items.size - 1) + 2 * PAD
            val ph = CELL_H + 2 * PAD
            getLocationOnScreen(at)
            var x = if (anchorX.isNaN()) (width - pw) / 2 else anchorX.toInt() - at[0]
            var y = if (anchorBottom.isNaN()) (height - ph) / 2 else anchorBottom.toInt() - at[1]
            if (y + ph > height && !anchorTop.isNaN()) y = anchorTop.toInt() - at[1] - ph
            x = x.coerceAtMost(width - pw).coerceAtLeast(0)
            panel.set(x, y, x + pw, y + ph)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (panel.isEmpty) return
        box.set(panel)
        canvas.drawRoundRect(box, RADIUS, RADIUS, frame)
        var x = panel.left + PAD
        val top = panel.top + PAD
        for (i in items.indices) {
            canvas.save()
            canvas.translate(x.toFloat(), top.toFloat())
            canvas.drawRect(0f, 0f, widths[i].toFloat(), CELL_H.toFloat(), if (i == focus) focused else grey)
            val p = paints.getValue(items[i].look)
            p.color = if (i == focus) TEXT_FOCUSED else TEXT
            canvas.drawText(shown[i], CELL_PAD.toFloat(), BASELINE.toFloat(), p)
            canvas.restore()
            x += widths[i] + SEPARATOR
        }
    }

    @SuppressLint("ClickableViewAccessibility") // the trackpad and Enter are the way; a touch is a shortcut
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || panel.isEmpty) return true
        if (event.y.toInt() !in panel.top + PAD until panel.top + PAD + CELL_H) return true
        var x = panel.left + PAD
        for (i in items.indices) {
            if (event.x.toInt() in x until x + widths[i]) {
                onChoose(i)
                break
            }
            x += widths[i] + SEPARATOR
        }
        return true
    }

    private companion object {
        const val PAD = 10
        const val CELL_H = 45
        const val CELL_PAD = 10
        const val SEPARATOR = 2
        /** The text's baseline in its cell: "This" from y 100 to 120 in the cell from 89 (sim-94a). */
        const val BASELINE = 31
        /** The upper part of a cell where its colour goes from top to bottom (sim-94a: y 89 to 105). */
        const val SHEEN = 16f
        const val RADIUS = 6f

        const val FRAME = 0xFF212429.toInt()
        const val FOCUS_TOP = 0xFF0C83EA.toInt()
        const val FOCUS_BOTTOM = 0xFF1766DB.toInt()
        const val GREY_TOP = 0xFFD7D7D7.toInt()
        const val GREY_BOTTOM = 0xFFAFB1B4.toInt()
        const val TEXT = 0xFF202121.toInt()
        const val TEXT_FOCUSED = 0xFFFFFFFF.toInt()

        fun cellPaint(top: Int, bottom: Int) = Paint().apply { shader = LinearGradient(0f, 0f, 0f, SHEEN, top, bottom, Shader.TileMode.CLAMP) }
    }
}
