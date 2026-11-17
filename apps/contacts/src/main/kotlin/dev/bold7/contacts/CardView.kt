package dev.bold7.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import dev.bold7.design.Focus
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

/**
 * A contact's card (sim-182, sim-194, C; gsm 015, OS 7.0; docs/BBOS7-VISUEL.md §30), under its
 * title bar: the boxes of an options screen (border_groupboxfull and its title bar, A) on the grey
 * page, the first with the picture (72 px at x 12) and the name in bold 27 at x 92, or without a
 * picture the name alone at x 12 in a box of 61 px, and the edit button (edit.png in border_button,
 * 86x51, 12 px from the right edge); then one box per kind of detail, its title bold 23 grey at x 13
 * over a line 36 px under its top, its rows of 42 px with the label ("Mobile:") 27 black at x 12 and
 * the value at the right, 10 px from the edge. The detail under the trackpad's focus: the value
 * alone on a blue band, white (C). Renders [CardScreen], decides nothing.
 */
class CardView(context: Context, private val onTouched: (Detail) -> Unit, private val onEdit: () -> Unit) : View(context) {
    companion object {
        const val HEADER = 82
        const val HEADER_BARE = 61
        const val PICTURE = 72
        const val PICTURE_X = 12
        const val PICTURE_Y = 3
        const val NAME_X = 92
        const val NAME_X_BARE = 12
        /** The edit button: 86x51, 4 px under the box's top, 12 px from the right edge (sim-194, C). */
        const val BUTTON_WIDTH = 86
        const val BUTTON_HEIGHT = 51
        const val BUTTON_TOP = 4
        const val BUTTON_RIGHT = 12
        const val NAME_BASELINE = 26
        /** The company under the name, 23 grey (D). */
        const val COMPANY_BASELINE = 55
        const val TITLE = 36
        const val TITLE_X = 13
        const val TITLE_BASELINE = 27
        const val ROW = 42
        const val LABEL_X = 12
        const val ROW_BASELINE = 31
        const val VALUE_RIGHT = 10
        /** The value's blue band: 5 to 37 px of its row, 2 px around the text (C). */
        const val BAND_TOP = 5
        const val BAND_BOTTOM = 37
        const val BAND_PAD = 2
        /** A further line of a value (an address), by eye. */
        const val LINE = 32
        /** border_groupboxfull.png: 2 grey rows and its edge above the white, its edge and 2 rows of shade under it (A). */
        const val EDGE_TOP = 3
        const val EDGE_BOTTOM = 3
        const val CORNER = 11f
        /** "No recent activity." sits in a body of 72 px under the title, its text at x 15 (sim-194, C). */
        const val EMPTY_BODY = 72
        const val EMPTY_X = 15
        const val EMPTY_BASELINE = 46
    }

    private val box = themeImage(context, "cnt_box")
    private val button = themeImage(context, "ui_button")
    private val editGlyph = themeImage(context, "cnt_edit")
    private val titleBar = themeImage(context, "cnt_title")
    private val name = textPaint(Typography.TEXT, Typography.BOLD, ListColors.Title)
    private val company = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val title = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.GroupTitle)
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val value = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
    private val valueLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val band = focusPaint(BAND_BOTTOM - BAND_TOP, Focus.RowTop, Focus.RowBottom)
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pictureRect = RectF()

    /** Texts cut to their room ("…"), kept per paint, room and text: the card is drawn often, a cut is measured once. */
    private val cuts = HashMap<String, String>()

    private fun cut(paint: Paint, text: String, room: Float): String {
        if (cuts.size > 256) cuts.clear()
        return cuts.getOrPut("${System.identityHashCode(paint)}|$room|$text") { paint.fit(text, room) }
    }

    private var screen: CardScreen? = null
    private var titles: Map<Section, String> = emptyMap()
    private var activityTitle = ""
    private var emptyText = ""
    /** The Recent Activity rows (label, time), null to hide the box. */
    private var activity: List<Pair<String, String>>? = null
    private var picture: Bitmap? = null
    /** Each detail's row top, in [Card.ordered]'s order, and its height. */
    private var tops = IntArray(0)
    private var heights = IntArray(0)
    private var boxes = emptyList<Pair<Int, Int>>()
    private var total = 0

    fun bind(screen: CardScreen, titles: Map<Section, String>, activityTitle: String, emptyText: String, activity: List<Pair<String, String>>?, picture: Bitmap?) {
        val relayout = screen.card != this.screen?.card || (picture == null) != (this.picture == null) || activity?.size != this.activity?.size
        this.screen = screen
        this.titles = titles
        this.activityTitle = activityTitle
        this.emptyText = emptyText
        this.activity = activity
        if (picture !== this.picture) {
            this.picture = picture
            picturePaint.shader = picture?.let {
                val s = PICTURE.toFloat() / minOf(it.width, it.height)
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(PICTURE_X - (it.width * s - PICTURE) / 2, EDGE_TOP + PICTURE_Y - (it.height * s - PICTURE) / 2) })
                }
            }
        }
        if (relayout) {
            layout(screen.card, picture != null)
            requestLayout()
        }
        invalidate()
    }

    /** The focused detail's rectangle, to keep it in sight. */
    fun focusedRect(): Rect? {
        val s = screen ?: return null
        if (s.focused == null) return null
        return Rect(0, tops[s.focus], width, tops[s.focus] + heights[s.focus])
    }

    private fun lines(d: Detail) = d.value.split('\n').filter { it.isNotBlank() }.ifEmpty { listOf("") }

    private fun layout(card: Card, withPicture: Boolean) {
        val ordered = card.ordered
        tops = IntArray(ordered.size)
        heights = IntArray(ordered.size)
        val out = ArrayList<Pair<Int, Int>>()
        // Each box: its image from its top to its bottom; its white from top + EDGE_TOP.
        var y = 0
        var bottom = y + EDGE_TOP + (if (withPicture) HEADER else HEADER_BARE) + EDGE_BOTTOM
        out += y to bottom
        var i = 0
        for ((_, details) in card.sections()) {
            y = bottom
            var row = y + EDGE_TOP + TITLE + 1
            for (d in details) {
                tops[i] = row
                heights[i] = ROW + LINE * (lines(d).size - 1)
                row += heights[i]
                i++
            }
            bottom = row + EDGE_BOTTOM
            out += y to bottom
        }
        activity?.let {
            y = bottom
            bottom = y + EDGE_TOP + TITLE + 1 + (if (it.isEmpty()) EMPTY_BODY else ROW * it.size) + EDGE_BOTTOM
            out += y to bottom
        }
        boxes = out
        total = bottom
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), total)
    }

    override fun onDraw(canvas: Canvas) {
        val s = screen ?: return
        val card = s.card
        val w = width
        for ((top, bottom) in boxes) {
            box.setBounds(0, top, w, bottom)
            box.draw(canvas)
        }
        // The header: picture and name.
        if (picture != null) {
            pictureRect.set(PICTURE_X.toFloat(), (EDGE_TOP + PICTURE_Y).toFloat(), (PICTURE_X + PICTURE).toFloat(), (EDGE_TOP + PICTURE_Y + PICTURE).toFloat())
            canvas.drawRoundRect(pictureRect, CORNER, CORNER, picturePaint)
        }
        val nameX = (if (picture != null) NAME_X else NAME_X_BARE).toFloat()
        canvas.drawText(cut(name, card.name, ContactRoom.cardName(w, picture != null)), nameX, (EDGE_TOP + NAME_BASELINE).toFloat(), name)
        if (card.company.isNotEmpty()) canvas.drawText(cut(company, card.company, ContactRoom.cardName(w, picture != null)), nameX, (EDGE_TOP + COMPANY_BASELINE).toFloat(), company)
        // border_button.png: its border pixels at column 3 and 56, row 3 and 98, as core/ui's dialogs.
        val bl = w - BUTTON_RIGHT - BUTTON_WIDTH
        val bt = EDGE_TOP + BUTTON_TOP
        button.setBounds(bl - 3, bt - 3, bl + BUTTON_WIDTH + 3, bt + BUTTON_HEIGHT + 3)
        button.draw(canvas)
        val gx = bl + (BUTTON_WIDTH - editGlyph.intrinsicWidth) / 2
        val gy = bt + (BUTTON_HEIGHT - editGlyph.intrinsicHeight) / 2
        editGlyph.setBounds(gx, gy, gx + editGlyph.intrinsicWidth, gy + editGlyph.intrinsicHeight)
        editGlyph.draw(canvas)
        var k = 1
        var i = 0
        for ((section, details) in card.sections()) {
            val top = boxes[k++].first
            titleFor(canvas, top, w, titles[section].orEmpty())
            for (d in details) {
                val lit = i == s.focus
                val rowTop = tops[i]
                val tag = d.label + ":"
                canvas.drawText(tag, LABEL_X.toFloat(), (rowTop + ROW_BASELINE).toFloat(), label)
                val room = ContactRoom.cardValue(w, label.measureText(tag))
                val right = (w - VALUE_RIGHT - BAND_PAD).toFloat()
                lines(d).forEachIndexed { n, line ->
                    val text = cut(value, line, room)
                    val baseline = (rowTop + ROW_BASELINE + n * LINE).toFloat()
                    if (lit) {
                        val bandTop = rowTop + BAND_TOP + n * LINE
                        canvas.save()
                        canvas.translate(0f, bandTop.toFloat())
                        canvas.drawRect(right - value.measureText(text) - BAND_PAD, 0f, right + BAND_PAD, (BAND_BOTTOM - BAND_TOP).toFloat(), band)
                        canvas.restore()
                    }
                    canvas.drawText(text, right, baseline, if (lit) valueLit else value)
                }
                i++
            }
        }
        activity?.let { rows ->
            val top = boxes[k].first
            val body = top + EDGE_TOP + TITLE + 1
            titleFor(canvas, top, w, activityTitle)
            if (rows.isEmpty()) canvas.drawText(emptyText, EMPTY_X.toFloat(), (body + EMPTY_BASELINE).toFloat(), label)
            rows.forEachIndexed { n, (what, time) ->
                val y = (body + n * ROW + ROW_BASELINE).toFloat()
                canvas.drawText(what, LABEL_X.toFloat(), y, label)
                canvas.drawText(time, (w - VALUE_RIGHT).toFloat(), y, value)
            }
        }
    }

    private fun titleFor(canvas: Canvas, top: Int, w: Int, text: String) {
        // border_groupboxtitlebar.png, its edge row 2 as the box's: its last row the line under the title.
        titleBar.setBounds(0, top, w, top + EDGE_TOP + TITLE + 1)
        titleBar.draw(canvas)
        canvas.drawText(cut(title, text, ContactRoom.boxTitle(w)), TITLE_X.toFloat(), (top + EDGE_TOP + TITLE_BASELINE).toFloat(), title)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val s = screen ?: return false
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val y = e.y.toInt()
            val bl = width - BUTTON_RIGHT - BUTTON_WIDTH
            if (e.x >= bl && e.x < bl + BUTTON_WIDTH && y >= EDGE_TOP + BUTTON_TOP && y < EDGE_TOP + BUTTON_TOP + BUTTON_HEIGHT) {
                onEdit()
                return true
            }
            val at = tops.indices.firstOrNull { y >= tops[it] && y < tops[it] + heights[it] }
            if (at != null) s.card.ordered.getOrNull(at)?.let(onTouched)
        }
        return true
    }
}
