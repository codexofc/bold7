package dev.bold7.launcher.options

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.LinearLayout
import dev.bold7.design.DialogColors
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Palette
import dev.bold7.design.Typography
import dev.bold7.launcher.connections.ConnectionsLayout
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

// The pieces of an options screen that core/ui has no view for yet: the group box, a note, a
// button row. Measured on sim-91a (C), with the boxes of Manage Connections (sim-23, C).

/**
 * A group of an options screen (sim-31, sim-66, sim-91a, C): border_groupboxfull and its title bar
 * (A, Manage Connections' 9-patches) behind its fields, the title bold 23 grey; the next box starts
 * right under its shade, as on sim-91a (edge at 171, next box at 174).
 */
internal class GroupBox(context: Context, private val title: String) : LinearLayout(context) {
    private val box = themeImage(context, "connections_box")
    private val titleBar = themeImage(context, "connections_title")
    private val text = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.GroupTitle)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        // The title bar above the fields; the box's lower edge and its two rows of shade under them.
        setPadding(0, ConnectionsLayout.TITLE, 0, ConnectionsLayout.GAP)
    }

    override fun onDraw(canvas: Canvas) {
        box.setBounds(0, 0, width, height)
        box.draw(canvas)
        titleBar.setBounds(0, 0, width, ConnectionsLayout.TITLE)
        titleBar.draw(canvas)
        canvas.drawText(text.fit(title, FormRoom.groupTitle(width, ConnectionsLayout.TITLE_X)), ConnectionsLayout.TITLE_X.toFloat(), ConnectionsLayout.TITLE_BASELINE.toFloat(), text)
    }
}

/**
 * A line of text over its grey note (Typing's input language, sim-91a, C): the text 27 black at x 9
 * on 26, the note 23 #7C7E80 wrapped, its lines 27 apart from 56; 90 px for two lines. Without a
 * text, the note alone under the field above, as Word Learning's (sim-92a, C): its lines from 22.
 * Under the trackpad's focus, the blue band and white texts (D).
 */
internal class NoteView(context: Context) : View(context) {
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val labelLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val notePaint = TextPaint(textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle))
    private val notePaintRed = TextPaint(textPaint(Typography.SUBTITLE, Typography.REGULAR, Palette.RedBase))
    private val notePaintLit = TextPaint(textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText))
    private var text = ""
    private var note = ""
    private var lit = false
    private var red = false
    private var band: Paint? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        band = null
    }

    fun bind(text: String, note: String, lit: Boolean, red: Boolean = false) {
        if (text == this.text && note == this.note && lit == this.lit && red == this.red) return
        this.red = red
        val relayout = note != this.note || text.isEmpty() != this.text.isEmpty()
        this.text = text
        this.note = note
        this.lit = lit
        if (relayout) requestLayout()
        invalidate()
    }

    private fun layout(width: Int, paint: TextPaint) =
        StaticLayout.Builder.obtain(note, 0, note.length, paint, (width - 2 * ListDimens.TEXT_X).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f).build()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val lines = layout(w, notePaint).lineCount
        setMeasuredDimension(w, top() + NOTE_LINE * lines)
    }

    override fun onDraw(canvas: Canvas) {
        if (lit) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), band ?: focusPaint(height).also { band = it })
        canvas.drawText(label.fit(text, FormRoom.noteText(width, ListDimens.TEXT_X)), ListDimens.TEXT_X.toFloat(), TEXT_BASELINE, if (lit) labelLit else label)
        val paint = if (lit) notePaintLit else if (red) notePaintRed else notePaint
        val l = layout(width, paint)
        for (i in 0 until l.lineCount) {
            canvas.drawText(note, l.getLineStart(i), l.getLineEnd(i), ListDimens.TEXT_X.toFloat(), (top() + NOTE_BASELINE - NOTE_TOP + i * NOTE_LINE).toFloat(), paint)
        }
    }

    private fun top() = if (text.isEmpty()) NOTE_TOP_ALONE else NOTE_TOP

    private companion object {
        const val TEXT_BASELINE = 26f
        const val NOTE_BASELINE = 56
        const val NOTE_LINE = 27
        // 90 px for two lines (sim-91a: 81 to 171).
        const val NOTE_TOP = 36
        // sim-92a: the note's first line 22 under the box's row, two lines in 56 px.
        const val NOTE_TOP_ALONE = 2
    }
}

/**
 * A button alone on its row, at the right (Advanced Style Options, sim-91a, C): the row 61 px as
 * All Off's, the button 51 high 6 under its top, 13 from the right edge, its label's width plus 20
 * each side; grey and its label grey when disabled (border_button~disabled.png, A), blue under the
 * trackpad's focus.
 */
internal class ButtonRowView(context: Context) : View(context) {
    private val normal = themeImage(context, "ui_button")
    private val focused = themeImage(context, "ui_button_focus")
    private val disabled = themeImage(context, "opt_button_disabled")
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val textLit = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val textOff = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle, align = Paint.Align.CENTER)
    private var label = ""
    private var lit = false
    private var on = true

    /**
     * Wallpaper Settings' two buttons, stacked right of the thumbnail (sim-284, C: visible tops 196 and
     * 253, 57 apart, as a choice row's field): the choice row's height and field. Set before the first draw.
     */
    var stacked = false

    fun bind(label: String, lit: Boolean, enabled: Boolean) {
        if (label == this.label && lit == this.lit && enabled == on) return
        this.label = label
        this.lit = lit
        on = enabled
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), if (stacked) ListDimens.CHECK_ROW else ConnectionsLayout.BUTTON_ROW)

    override fun onDraw(canvas: Canvas) {
        val right = width - RIGHT
        val shown = text.fit(label, FormRoom.buttonLabel(width, RIGHT, ListDimens.TEXT_X, ConnectionsLayout.BUTTON_PAD))
        val left = right - text.measureText(shown).toInt() - 2 * ConnectionsLayout.BUTTON_PAD
        val image = when {
            !on -> disabled
            lit -> focused
            else -> normal
        }
        val top = if (stacked) ListDimens.CHOICE_FIELD_TOP else TOP
        image.setBounds(left, top, right, top + ConnectionsLayout.BUTTON_HEIGHT)
        image.draw(canvas)
        canvas.drawText(shown, (left + right) / 2f, (top + ListDimens.CHECK_BASELINE - ListDimens.CHOICE_FIELD_TOP).toFloat(), if (!on) textOff else if (lit) textLit else text)
    }

    private companion object {
        const val RIGHT = 13
        const val TOP = 6
    }
}

/**
 * Wallpaper Settings' thumbnail (sim-284, C: the wallpaper whole, 150x113 at x 9 on the 9900's
 * 640x480, its top level with the first button's row): on the square screen the wallpaper is square,
 * [SIDE] px, the height of the 9900's (D). Nothing drawn until its picture is decoded.
 */
internal class WallpaperThumbView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var picture: Bitmap? = null
    private val box = android.graphics.Rect(0, 0, SIDE, SIDE)

    fun bind(b: Bitmap?) {
        if (b === picture) return
        picture = b
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(SIDE, SIDE)

    override fun onDraw(canvas: Canvas) {
        picture?.let { canvas.drawBitmap(it, null, box, paint) }
    }

    companion object {
        const val SIDE = 113
    }
}
