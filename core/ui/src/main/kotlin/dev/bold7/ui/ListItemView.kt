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
 * A list's row of OS 7, a title over a subtitle, as Options draws them (sim-57, C; BBOS7-VISUEL
 * §17): 70 px and a 1 px #D9DCDE line, the title in BBAlpha Sans 27 black, the subtitle in 23
 * #7C7E80, at x 79 after an [icon] (cat_*.png, A) centred on x 37, at 9 without one (sim-24, C).
 * Under the trackpad's focus ([lit]), a blue band over the whole row and its line, white texts
 * (sim-57, C). Off the focus, the title takes another colour when an app asks (Tasks' overdue
 * tasks in red); black otherwise.
 *
 * Simplification: no time or status at the right (Text Messages, sim-53); add them when an app
 * needs them.
 */
class ListItemView(context: Context) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.ROW
        const val TEXT_X = ListDimens.TEXT_X
        const val TITLE_SIZE = Typography.TEXT
        const val TITLE_BASELINE = ListDimens.TITLE_BASELINE
        const val DESCRIPTION_SIZE = Typography.SUBTITLE
        const val DESCRIPTION_BASELINE = ListDimens.SUBTITLE_BASELINE
    }

    private val divider = fillPaint(ListColors.Divider)
    private val focus = focusPaint(HEIGHT)
    private val titlePaint = textPaint(TITLE_SIZE, Typography.REGULAR, ListColors.Title)
    private val descriptionPaint = textPaint(DESCRIPTION_SIZE, Typography.REGULAR, ListColors.Subtitle)
    private val litTitle = textPaint(TITLE_SIZE, Typography.REGULAR, ListColors.TitleBarText)
    private val litDescription = textPaint(DESCRIPTION_SIZE, Typography.REGULAR, ListColors.TitleBarText)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var title = ""
    private var description = ""
    private var lit = false
    private var icon: Bitmap? = null
    private var titleColor = ListColors.Title
    // The texts as cut to the row, their emoticons (a message's emoji) as OS 7's images.
    private var shownTitle: List<Chunk> = emptyList()
    private var shownDescription: List<Chunk> = emptyList()
    private val pen = EmoticonPen()

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
    }

    /** Shows [title] over [description], [lit] under the focus, [icon] at the left or none, the title in [titleColor] off the focus. */
    fun bind(title: String, description: String, lit: Boolean = false, icon: Bitmap? = null, titleColor: Int = ListColors.Title) {
        if (title === this.title && description === this.description && lit == this.lit && icon === this.icon && titleColor == this.titleColor) return
        this.title = title
        this.description = description
        this.lit = lit
        this.icon = icon
        this.titleColor = titleColor
        titlePaint.color = titleColor
        cut()
        invalidate()
    }

    // The texts cut to the row's room, when they or the width change: never in onDraw.
    private fun cut() {
        val room = TextRoom.listRow(width, icon != null)
        shownTitle = emoticonChunks(titlePaint.fitEmoticons(title, room))
        shownDescription = emoticonChunks(descriptionPaint.fitEmoticons(description, room))
        EmoticonStrip.want(this, shownTitle)
        EmoticonStrip.want(this, shownDescription)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val line = (HEIGHT - ListDimens.DIVIDER).toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, HEIGHT.toFloat(), focus) else canvas.drawRect(0f, line, w, HEIGHT.toFloat(), divider)
        val i = icon
        val x = if (i == null) TEXT_X else ListDimens.ICON_TEXT_X
        if (i != null) canvas.drawBitmap(i, ListDimens.ICON_CENTRE_X - i.width / 2f, (line - i.height) / 2, bitmapPaint)
        canvas.save()
        canvas.clipRect(x.toFloat(), 0f, w - TEXT_X, line)
        pen.draw(canvas, shownTitle, x.toFloat(), TITLE_BASELINE.toFloat(), if (lit) litTitle else titlePaint)
        pen.draw(canvas, shownDescription, x.toFloat(), DESCRIPTION_BASELINE.toFloat(), if (lit) litDescription else descriptionPaint)
        canvas.restore()
    }
}
