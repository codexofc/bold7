package dev.bold7.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.text.TextUtils
import android.widget.TextView
import android.widget.FrameLayout
import dev.bold7.design.HomeColors
import dev.bold7.design.Typography
import dev.bold7.ui.typeface
import kotlin.math.roundToInt

/** The open folder's tab, background_folder_1.png to _5.png (A): 50 px high; the folder's grid starts under it (sim-374, C). */
internal const val FOLDER_HEADER = 50

/** The tab's two ends, background_folder_1.png and _5.png, and its slant, background_folder_3.png (A). */
internal const val TAB_END = 38
internal const val TAB_SLANT = 52

/** The name's pen: its ink from x 57 on sim-16 and sim-374 ("I" and "M" start 2.6 px in), 55 on sim-482 ("Z", 1.2) (C). */
private const val NAME_X = 54

/**
 * Where the tab's slant starts: one pixel after the name's pen ends, each glyph's [advances]
 * rounded as the 9900's bitmap text does (sim-16 x 303, sim-374 x 137, sim-482 x 87, C), kept
 * between the tab's two ends on a screen [width] wide.
 */
internal fun folderSlantX(advances: FloatArray, width: Int): Int =
    (NAME_X + advances.sumOf { it.roundToInt() } + 1).coerceIn(TAB_END, width - TAB_END - TAB_SLANT)

/**
 * The five images of the tab side by side over [width], the slant at [slant]: each (left, width).
 * The 1 px images between are stretched, as the 9900 stretches them (D for the square).
 */
internal fun folderTabSlices(slant: Int, width: Int): List<Pair<Int, Int>> = listOf(
    0 to TAB_END,
    TAB_END to slant - TAB_END,
    slant to TAB_SLANT,
    slant + TAB_SLANT to width - TAB_END - slant - TAB_SLANT,
    width - TAB_END to TAB_END,
)

/**
 * The open folder's tab and the darkened ground under it (sim-16, sim-374, sim-482, C): the
 * tab's five images across the screen, 40 % black over the tray with a light edge, its slant
 * after the folder's name, then background_folder_bottom.png (40 % black, A) to the screen's
 * bottom, all under the folder's grid; on the tab, the folder's own icon at half its size, 45x45
 * from x 0, 5 px under the tab's top (its shadow going past the band's bottom; matched to the
 * pixel on the three captures), the folder's name in BBAlpha Sans 27 px, white. The name is typed
 * in the text box of Edit Folder ([dev.bold7.ui.TextDialog]), not here; a name wider than its room,
 * up to the slant's last place, ends in an ellipsis.
 */
internal class FolderHeader(context: Context) : FrameLayout(context) {
    private var slices: List<Bitmap> = emptyList()
    private var icon: Bitmap? = null
    private var slant = TAB_END
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plain = Paint()
    private val ground = Paint()
    private val dst = Rect()

    private val name = TextView(context).apply {
        setPadding(0, 0, 0, 0)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typography.Text.typeface()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(HomeColors.Text)
    }

    init {
        setWillNotDraw(false)
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, NAME_HEIGHT).apply {
            leftMargin = NAME_X
            rightMargin = TAB_END + TAB_SLANT + 1
        })
    }

    /** background_folder_1.png to _5.png, then background_folder_bottom.png. */
    fun images(tab: List<Bitmap>, bottom: Bitmap) {
        slices = tab
        ground.shader = BitmapShader(bottom, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
    }

    /** Shows [folder]'s name and its [icon] (the 92 px image). */
    fun show(folder: String, icon: Bitmap?) {
        if (name.text.toString() != folder) {
            name.setText(folder)
            val advances = FloatArray(folder.length)
            name.paint.getTextWidths(folder, advances)
            slant = folderSlantX(advances, resources.displayMetrics.widthPixels)
            invalidate()
        }
        if (icon !== this.icon) {
            this.icon = icon
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.size == 5) {
            folderTabSlices(slant, width).forEachIndexed { i, (left, w) ->
                dst.set(left, 0, left + w, FOLDER_HEADER)
                canvas.drawBitmap(slices[i], null, dst, plain)
            }
            canvas.drawRect(0f, FOLDER_HEADER.toFloat(), width.toFloat(), height.toFloat(), ground)
        }
        icon?.let {
            dst.set(0, ICON_Y, ICON, ICON_Y + ICON)
            canvas.drawBitmap(it, null, dst, paint)
        }
    }

    private companion object {
        const val ICON = 45 // the 92 px icon's ink, 69x55, is 34x27 on sim-16 and sim-374
        const val ICON_Y = 5 // sim-16: the image from y 192, the band from 187
        const val NAME_HEIGHT = 39 // the name centred in the tab's first 39 px: y 202 to 230 on sim-16
    }
}
