package dev.bold7.pictures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint

// The views of Pictures, drawn as the simulator shows them (docs/BBOS7-VISUEL.md §37, sim-344 and
// sim-345, C; pixels of the 9900, the same size on the Q25). They render what the activity gives.

/** The colours of Pictures (sim-344, sim-345, C). */
object PictureColors {
    const val BACKGROUND = 0xFF000000.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    /** "0 Items" (sim-344: #ADAAAD, RGB565 of the simulator). */
    const val COUNT = 0xFFADAAAD.toInt()
    /** The line under a category's row (sim-344). */
    const val ROW_LINE = 0xFF141414.toInt()
    /** The line under the Camera row (sim-344, sim-367). */
    const val CAMERA_LINE = 0xFFFFFFFF.toInt()
    /** A focused thumbnail: its cell in blue, a dark blue line 4 px in (sim-345). */
    const val CELL_FOCUS = 0xFF257CFF.toInt()
    const val CELL_FOCUS_LINE = 0xFF00009C.toInt()
    /** The date in the bar under the grid (sim-345). */
    const val DATE = 0xFF7D7D7D.toInt()
}

/** A picture drawn into [dst], its centre square or rectangle filling it (sim-344, sim-345: the thumbnails are cut, never letterboxed). */
internal fun Canvas.drawCropped(b: Bitmap, dst: RectF, paint: Paint) {
    val scale = maxOf(dst.width() / b.width, dst.height() / b.height)
    val w = (dst.width() / scale).toInt()
    val h = (dst.height() / scale).toInt()
    val src = Rect((b.width - w) / 2, (b.height - h) / 2, (b.width + w) / 2, (b.height + h) / 2)
    drawBitmap(b, src, dst, paint)
}

/**
 * A row of the first screen (sim-344, C): 86 px on black, its line #141414 at the bottom. A
 * category: its first picture filling 86x86 at the left, or the no-picture icon (64 px at x 11),
 * the name white 27 px at x 99 on a baseline 38, the count #ADAAAD 21 px on 67. The Camera row
 * ([camera]): the camera's icon, its picture 52 px wide at x 16, "Camera" at x 88 centred, then a white line
 * and a black one (88 px in all). The focus: the blue band, the texts white.
 */
class CategoryRowView(context: Context, private val camera: Boolean = false) : View(context) {
    companion object {
        const val ROW = 86
        const val CAMERA_ROW = ROW + 2
        const val THUMB = 86
        const val ICON_X = 11
        const val TEXT_X = 99
        const val TITLE_BASELINE = 38
        const val COUNT_BASELINE = 67
        const val COUNT_SIZE = 21
        // The camera app's 92 px icon (A) drawn 67 px wide: its opaque core (72x54 of the 92) is then 52x40,
        // the picture of sim-344 (D: the 9900 draws another image of it), its left at x 16.
        const val CAMERA_ICON_X = 9
        const val CAMERA_ICON_WIDTH = 67
        const val CAMERA_TEXT_X = 88
    }

    private val focus = focusPaint(ROW)
    private val title = textPaint(Typography.TEXT, Typography.REGULAR, PictureColors.TEXT)
    private val count = textPaint(COUNT_SIZE, Typography.REGULAR, PictureColors.COUNT)
    private val line = fillPaint(PictureColors.ROW_LINE)
    private val white = fillPaint(PictureColors.CAMERA_LINE)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()

    private var name = ""
    private var items = ""
    private var lit = false
    private var picture: Bitmap? = null
    private var icon: Bitmap? = null

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (camera) CAMERA_ROW else ROW)
    }

    fun bind(name: String, items: String, lit: Boolean, picture: Bitmap?, icon: Bitmap?) {
        this.name = name
        this.items = items
        this.lit = lit
        this.picture = picture
        this.icon = icon
        count.color = if (lit) PictureColors.TEXT else PictureColors.COUNT
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (lit) canvas.drawRect(0f, 0f, width.toFloat(), ROW.toFloat(), focus) else if (!camera) canvas.drawRect(0f, ROW - 1f, width.toFloat(), ROW.toFloat(), line)
        if (camera) {
            icon?.let {
                val h = it.height * CAMERA_ICON_WIDTH / it.width
                box.set(CAMERA_ICON_X.toFloat(), (ROW - h) / 2f, (CAMERA_ICON_X + CAMERA_ICON_WIDTH).toFloat(), (ROW + h) / 2f)
                canvas.drawBitmap(it, null, box, bitmapPaint)
            }
            val fm = title.fontMetrics
            canvas.drawText(title.fit(name, width - CAMERA_TEXT_X - 4f), CAMERA_TEXT_X.toFloat(), ROW / 2f - (fm.ascent + fm.descent) / 2, title)
            canvas.drawRect(0f, ROW.toFloat(), width.toFloat(), ROW + 1f, white)
            return
        }
        val p = picture
        if (p != null) {
            box.set(0f, 0f, THUMB.toFloat(), THUMB.toFloat())
            canvas.drawCropped(p, box, bitmapPaint)
        } else {
            icon?.let { canvas.drawBitmap(it, ICON_X.toFloat(), (ROW - it.height) / 2f, bitmapPaint) }
        }
        canvas.drawText(title.fit(name, width - TEXT_X - 4f), TEXT_X.toFloat(), TITLE_BASELINE.toFloat(), title)
        canvas.drawText(items, TEXT_X.toFloat(), COUNT_BASELINE.toFloat(), count)
    }
}

/**
 * A thumbnail of the grid (sim-345, C), a square cell: the picture's centre 2 px in, on black; under
 * the focus the cell is #257CFF, a #00009C line 4 px in and the picture 5 px in. A picture not yet
 * decoded leaves the cell black; one that cannot be read shows the theme's no-picture thumbnail.
 */
class ThumbView(context: Context, private val side: Int) : View(context) {
    companion object {
        const val INSET = 2
        const val FOCUS_LINE = 4
        const val FOCUS_INSET = 5
    }

    private val focusFill = fillPaint(PictureColors.CELL_FOCUS)
    private val focusLine = Paint().apply { color = PictureColors.CELL_FOCUS_LINE; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var bitmap: Bitmap? = null
    private var lit = false

    init {
        layoutParams = AbsListView.LayoutParams(side, side)
    }

    fun bind(bitmap: Bitmap?, lit: Boolean) {
        if (bitmap === this.bitmap && lit == this.lit) return
        this.bitmap = bitmap
        this.lit = lit
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = if (lit) FOCUS_INSET else INSET
        if (lit) {
            canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), focusFill)
            canvas.drawRect(FOCUS_LINE + 0.5f, FOCUS_LINE + 0.5f, side - FOCUS_LINE - 0.5f, side - FOCUS_LINE - 0.5f, focusLine)
        }
        val b = bitmap ?: return
        box.set(inset.toFloat(), inset.toFloat(), (side - inset).toFloat(), (side - inset).toFloat())
        canvas.drawCropped(b, box, bitmapPaint)
    }
}

/**
 * The bar under the grid (sim-345, C): 32 px of black, the focused picture's name white 27 px at
 * x 2 and its date #7D7D7D right-aligned 2 px from the edge, both on a baseline 26.
 */
class PictureBar(context: Context) : View(context) {
    companion object {
        const val HEIGHT = 32
        const val BASELINE = 26
        const val MARGIN = 2
    }

    private val name = textPaint(Typography.TEXT, Typography.REGULAR, PictureColors.TEXT)
    private val date = textPaint(Typography.TEXT, Typography.REGULAR, PictureColors.DATE, align = Paint.Align.RIGHT)
    private var text = ""
    private var day = ""

    init {
        setBackgroundColor(PictureColors.BACKGROUND)
    }

    fun bind(text: String, day: String) {
        if (text == this.text && day == this.day) return
        this.text = text
        this.day = day
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val dateWidth = date.measureText(day)
        canvas.drawText(day, width - MARGIN.toFloat(), BASELINE.toFloat(), date)
        canvas.drawText(name.fit(text, width - dateWidth - 3 * MARGIN - 8f), MARGIN.toFloat(), BASELINE.toFloat(), name)
    }
}
