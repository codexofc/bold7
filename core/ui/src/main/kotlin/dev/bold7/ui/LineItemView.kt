package dev.bold7.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * A list's one-line row of OS 7, as MemoPad's and Tasks' (sim-254, sim-259, sim-280, C):
 * 57 px, no line under it, the title in BBAlpha Sans 27 black at x 4 on the baseline of a check box's
 * row, an optional [icon] at the left edge (Tasks' priority and status images, 27 px high, centred
 * in the row) with the title after it at the [textX] the caller gives; under the trackpad's focus
 * ([lit]) the blue band over the whole row and white text. Not the two-line [ListItemView] of Options.
 */
class LineItemView(context: Context) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.CHECK_ROW
        const val TEXT_X = 4
        const val BASELINE = ListDimens.CHECK_BASELINE
    }

    private val focus = focusPaint(HEIGHT)
    private val titlePaint = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val litTitle = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var title = ""
    private var textX = TEXT_X
    private var lit = false
    private var icon: Bitmap? = null
    private var titleColor = ListColors.Title
    private var shown = ""

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
    }

    /** Shows [title] at [textX], [lit] under the focus, [icon] at the left edge or none, the title in [titleColor] off the focus. */
    fun bind(title: String, lit: Boolean = false, icon: Bitmap? = null, textX: Int = TEXT_X, titleColor: Int = ListColors.Title) {
        if (title == this.title && lit == this.lit && icon === this.icon && textX == this.textX && titleColor == this.titleColor) return
        this.title = title
        this.lit = lit
        this.icon = icon
        this.textX = textX
        this.titleColor = titleColor
        titlePaint.color = titleColor
        cut()
        invalidate()
    }

    private fun cut() {
        shown = titlePaint.fit(title, TextRoom.lineRow(width, textX))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, HEIGHT.toFloat(), focus)
        icon?.let { canvas.drawBitmap(it, 0f, (HEIGHT - it.height) / 2f, bitmapPaint) }
        canvas.save()
        canvas.clipRect(textX.toFloat(), 0f, w - TEXT_X, HEIGHT.toFloat())
        canvas.drawText(shown, textX.toFloat(), BASELINE.toFloat(), if (lit) litTitle else titlePaint)
        canvas.restore()
    }
}
