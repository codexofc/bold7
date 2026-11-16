package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * A list's date header of OS 7 (BBOS7-VISUEL §15): 56 px of background_list_header.png's top rows
 * (A; the gradient #D5D7D6 to #ACB0AF of gsm 027, C) stretched to the width, a 1 px #D9DCDE line
 * under it (sim-53, C), the date centred in BBAlpha Sans 27 black (gsm 027, C); under the
 * trackpad's focus ([lit]), the blue of the rows and white text (sim-53, C).
 *
 * [stuck], a header pinned at the list's top, draws as the others: OS 7 pins none (gsm 027, C).
 */
open class ListHeaderView(context: Context, val stuck: Boolean = false) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.HEADER + ListDimens.DIVIDER
        const val LINE = ListDimens.DIVIDER
        const val BASELINE = ListDimens.HEADER_BASELINE

        /** The text is centred; kept for its readers. */
        const val TEXT_X = 0
    }

    private val image = themeImage(context, "ui_list_header")
    private val focus = focusPaint(ListDimens.HEADER)
    private val line = fillPaint(ListColors.Divider)
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.HeaderText, align = Paint.Align.CENTER)
    private val litText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.CENTER)
    private val bounds = Rect()
    private var title = ""
    private var lit = false
    private var shown = ""

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
        isHapticFeedbackEnabled = false
    }

    /** Shows [title], [lit] under the trackpad's focus; redrawn only when either changes. */
    fun show(title: String, lit: Boolean = false) {
        if (title === this.title && lit == this.lit) return
        this.title = title
        this.lit = lit
        shown = text.fit(title, TextRoom.header(width))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shown = text.fit(title, TextRoom.header(w))
        bounds.set(0, 0, w, ListDimens.HEADER)
        image.bounds = bounds
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, ListDimens.HEADER.toFloat(), focus) else image.draw(canvas)
        canvas.drawRect(0f, ListDimens.HEADER.toFloat(), w, HEIGHT.toFloat(), line)
        canvas.drawText(shown, w / 2, BASELINE.toFloat(), if (lit) litText else text)
    }
}
