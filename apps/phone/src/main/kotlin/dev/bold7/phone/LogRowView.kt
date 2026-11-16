package dev.bold7.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint

/**
 * A call log row of the Phone screen, measured on sim-141 (C, 7.1; ps 33246 agrees): 66 px with its
 * #DFDFDF line; the 42 px icon at x 9, 12 px under the top, a cell of net_rim_Phone-icons42x42.png
 * (A); the name or number in BBAlpha Sans 27 black at x 64 on a baseline 26, under it the number in
 * 23 grey on 56; at the right, 3 px from the edge, the date in 27 black over the time in 23 grey.
 * Under the trackpad's focus, the blue band, texts white.
 */
class LogRowView(context: Context, private val icons: () -> Bitmap?) : View(context) {
    companion object {
        const val HEIGHT = 66
        const val ICON = 42
        const val ICON_X = 9
        const val ICON_Y = 12
        const val TEXT_X = 64
        const val TITLE_BASELINE = 26
        const val SUBTITLE_BASELINE = 56
        const val RIGHT = 3
        const val DIVIDER = 0xFFDFDFDF.toInt()
        /** Between the texts and the date: by eye (D). */
        const val GAP = 12
    }

    private val divider = fillPaint(DIVIDER)
    private val focus = focusPaint(HEIGHT)
    private val title = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val titleLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val sub = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val subLit = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText)
    private val date = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
    private val time = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle, align = Paint.Align.RIGHT)
    private val whenLit = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val dateLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val bitmap = Paint()
    private val src = Rect()
    private val dst = Rect(ICON_X, ICON_Y, ICON_X + ICON, ICON_Y + ICON)

    private var row: LogRow? = null
    private var lit = false
    private var whenWidth = 0f

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
        isHapticFeedbackEnabled = false
    }

    fun bind(row: LogRow, lit: Boolean) {
        if (row == this.row && lit == this.lit) return
        this.row = row
        this.lit = lit
        whenWidth = maxOf(date.measureText(row.date), time.measureText(row.time))
        refit()
        src.set(row.icon.cell * ICON, 0, (row.icon.cell + 1) * ICON, ICON)
        contentDescription = "${row.title} ${row.date} ${row.time}"
        invalidate()
    }

    private var shownTitle = ""
    private var shownSub = ""

    // Cut by fit to the room before the date, here and never in onDraw (a lit row is white, same width).
    private fun refit() {
        val r = row ?: return
        if (width == 0) return
        val room = Rooms.logText(width, whenWidth)
        shownTitle = title.fit(r.title, room)
        shownSub = sub.fit(r.subtitle, room)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    override fun onDraw(canvas: Canvas) {
        val r = row ?: return
        val w = width.toFloat()
        val bottom = HEIGHT.toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, bottom, focus) else canvas.drawRect(0f, bottom - ListDimens.DIVIDER, w, bottom, divider)
        if (r.icon != LogIcon.NONE) icons()?.let { canvas.drawBitmap(it, src, dst, bitmap) }
        val right = w - RIGHT
        canvas.drawText(r.date, right, TITLE_BASELINE.toFloat(), if (lit) dateLit else date)
        canvas.drawText(r.time, right, SUBTITLE_BASELINE.toFloat(), if (lit) whenLit else time)
        canvas.drawText(shownTitle, TEXT_X.toFloat(), TITLE_BASELINE.toFloat(), if (lit) titleLit else title)
        canvas.drawText(shownSub, TEXT_X.toFloat(), SUBTITLE_BASELINE.toFloat(), if (lit) subLit else sub)
    }
}
