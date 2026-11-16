package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * The title at the top of an app's screen in OS 7, where it has no status bar (Options, Password:
 * sim-57, sim-60, C; BBOS7-VISUEL §17, §18): 34 px of black and a #1F1F1F line. The screens with
 * the two-line bar of Messages use another bar.
 *
 * An app's options page ([options]: Clock, MemoPad, Tasks Options, sim-170, sim-263, sim-320, C;
 * BBOS7-VISUEL §34.8) has a band of 32 px of black, then two rows of #DDDDDD and a #C6C6C6 line
 * (the same 35 px). The Options app's own pages have the black bar (sim-57, sim-59, sim-60, sim-284,
 * sim-294, sim-299: #1F1F1F at row 34, no grey band).
 *
 * The title is white, not bold, on a baseline 26, its rows 6 to 25 (C). On the black
 * bar, BBAlpha Sans Condensed 27 at x 5: rendered so, "Contacts" is 105 x 20, "New Contact" 153,
 * "MemoPad" 115, "New Task" 112, "Options" 91, "Messages Options" 218, "Password" 111, "Home Screen
 * Preferences" 309, the widths of sim-178, sim-191, sim-254, sim-268, sim-57, sim-294, sim-60 and
 * sim-284 to the pixel, their ink from x 6 or 7 (BBAlpha Sans made them 11 to 12 % wider). On the
 * options band, BBAlpha Sans 27 at x 0: "Clock Options" 186, "MemoPad Options" 242, "Tasks Options"
 * 187, as on sim-170, sim-263 and sim-320, their ink from x 0 to 3.
 */
open class TitleBar(context: Context, private var title: String = "", private val options: Boolean = false) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.TITLE_BAR
        const val LINE = 1
        const val TITLE_X = ListDimens.SCREEN_TITLE_X
        const val TITLE_SIZE = Typography.TEXT
        // The titles' rows end at 25 (sim-178, sim-254, sim-60, C).
        const val TITLE_BASELINE = 26
        const val OPTIONS_BAND = 32
        const val OPTIONS_TITLE_X = 0
        const val OPTIONS_GAP = 0xFFDDDDDD.toInt()
        const val OPTIONS_LINE = 0xFFC6C6C6.toInt()
    }

    private val background = fillPaint(ListColors.TitleBar)
    private val line = fillPaint(ListColors.TitleBarLine)
    private val titlePaint = textPaint(TITLE_SIZE, Typography.REGULAR, ListColors.TitleBarText, family = if (options) Typography.FAMILY else Typography.FAMILY_CONDENSED)
    private val gap = fillPaint(OPTIONS_GAP)
    private val optionsLine = fillPaint(OPTIONS_LINE)

    private var shown = ""

    fun bind(title: String) {
        if (title == this.title) return
        this.title = title
        cut(width)
        invalidate()
    }

    private fun cut(w: Int) {
        shown = titlePaint.fit(title, TextRoom.title(w))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut(w)

    // Its touches go to nothing under it.
    override fun onTouchEvent(event: MotionEvent) = true

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (options) {
            canvas.drawRect(0f, 0f, w, OPTIONS_BAND.toFloat(), background)
            canvas.drawRect(0f, OPTIONS_BAND.toFloat(), w, HEIGHT - LINE.toFloat(), gap)
            canvas.drawRect(0f, HEIGHT - LINE.toFloat(), w, HEIGHT.toFloat(), optionsLine)
        } else {
            val bottom = (HEIGHT - LINE).toFloat()
            canvas.drawRect(0f, 0f, w, bottom, background)
            canvas.drawRect(0f, bottom, w, HEIGHT.toFloat(), line)
        }
        canvas.drawText(shown, (if (options) OPTIONS_TITLE_X else TITLE_X).toFloat(), TITLE_BASELINE.toFloat(), titlePaint)
    }
}
