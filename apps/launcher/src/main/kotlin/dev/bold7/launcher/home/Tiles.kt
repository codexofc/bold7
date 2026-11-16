package dev.bold7.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import dev.bold7.design.HomeDimens

/** The icon's left in its 120 px cell: 14 px each side (BBOS7-VISUEL §2, D). */
private const val ICON_LEFT = (HomeDimens.COLUMN - HomeDimens.ICON) / 2

/**
 * A tile of the tray: its 92 px icon at the top of its cell, centred, and no label (sim-06, C:
 * the name shows in the tab when it has the focus). A hidden app shown by Show All is drawn at
 * half its opacity (D); while it is being moved, the theme's move underlay is behind it. An app
 * with something new wears the theme's red star over its icon (new_overlay.png, 92x92, its star at
 * (60, 12): at that place over Phone on sim-15, C).
 */
internal class TileView(context: Context, val tile: Tile, private val icon: Bitmap?, private val today: CalendarDay?) : View(context) {
    /** The theme's icon_move_underlay.png while it is moved (Move, C sim-17's action; D for the look). */
    var moveUnderlay: Bitmap? = null
        set(value) {
            if (field !== value) invalidate()
            field = value
        }

    /** The theme's new_overlay.png while the app holds something new (HubBadges, iconSplat). */
    var splat: Bitmap? = null
        set(value) {
            if (field !== value) invalidate()
            field = value
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = when (tile) { // tools/perf.sh finds the icon by it
            is App -> tile.label
            is Folder -> tile.name
        }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        moveUnderlay?.let {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(it, null, dst, paint)
        }
        val b = icon ?: return
        canvas.drawBitmap(b, ICON_LEFT.toFloat(), 0f, paint)
        today?.draw(canvas, ICON_LEFT + HomeDimens.ICON / 2f)
        splat?.let { canvas.drawBitmap(it, ICON_LEFT.toFloat(), 0f, paint) }
    }
}

/**
 * The day of the month written on the blank calendar icon of the theme, as sim-06 shows it (C):
 * "13" alone, dark grey, centred on the page, its digits 28 px high (size and grey by eye). Set
 * by [show] when the date changes, never while drawing.
 */
internal class CalendarDay {
    var date = ""
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = CALENDAR_DATE_SIZE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = CALENDAR_DATE_COLOR
        textAlign = Paint.Align.CENTER
    }

    /** True if the icons have something new to draw. */
    fun show(day: Int): Boolean {
        val text = day.toString()
        if (text == date) return false
        date = text
        return true
    }

    fun draw(canvas: Canvas, x: Float) = canvas.drawText(date, x, CALENDAR_DATE_BASELINE, paint)

    private companion object {
        // sim-06, the icon at the top of its cell: digits y 37 to 65 (C), BBAlpha Sans at 39 px
        // gives that height (by eye), grey of the digits' heart.
        val CALENDAR_DATE_SIZE = HomeDimens.CALENDAR_DATE_SIZE.toFloat()
        val CALENDAR_DATE_BASELINE = HomeDimens.CALENDAR_DATE_BASELINE.toFloat()
        val CALENDAR_DATE_COLOR = dev.bold7.design.HomeColors.CalendarDate
    }
}

/**
 * One view of the tray: its tiles in rows of 6 cells of 120x97 (BBOS7-VISUEL §2, D), as many rows
 * as it needs; the tray shows some of them and moves it by its translation, a row at a time.
 * The focused cell gets the theme's icon focus stretched to it, its corners kept (sim-08,
 * icon_underlay~focus.png as a 9-patch, A), drawn under its icon. Children draw past nothing: the tray clips.
 */
internal class GridPanel(context: Context) : ViewGroup(context) {
    var focused: Int? = null
        set(value) {
            if (field != value) invalidate()
            field = value
        }
    var focusImage: NinePatch? = null
        set(value) {
            field = value
            invalidate()
        }
    private val dst = RectF()

    init {
        setWillNotDraw(false)
    }

    val rows get() = (childCount + HomeDimens.COLUMNS - 1) / HomeDimens.COLUMNS

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (rows * HomeDimens.ROW).coerceAtLeast(HomeDimens.ROW))
        val cw = MeasureSpec.makeMeasureSpec(HomeDimens.COLUMN, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(HomeDimens.ROW, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (i % HomeDimens.COLUMNS) * HomeDimens.COLUMN
            val y = (i / HomeDimens.COLUMNS) * HomeDimens.ROW
            getChildAt(i).layout(x, y, x + HomeDimens.COLUMN, y + HomeDimens.ROW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val i = focused ?: return
        val image = focusImage ?: return
        if (i >= childCount) return
        val x = (i % HomeDimens.COLUMNS) * HomeDimens.COLUMN.toFloat()
        val y = (i / HomeDimens.COLUMNS) * HomeDimens.ROW.toFloat()
        dst.set(x, y, x + HomeDimens.COLUMN, y + HomeDimens.ROW)
        image.draw(canvas, dst)
    }

    fun tile(index: Int) = getChildAt(index) as? TileView

    fun forEachTile(action: (TileView) -> Unit) {
        for (i in 0 until childCount) tile(i)?.let(action)
    }
}
