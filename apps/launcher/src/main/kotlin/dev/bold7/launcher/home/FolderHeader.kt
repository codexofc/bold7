package dev.bold7.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.text.TextUtils
import android.widget.TextView
import android.widget.FrameLayout
import dev.bold7.design.HomeColors
import dev.bold7.design.Typography
import dev.bold7.ui.typeface

/** The open folder's header band (background_folderview.png, 480x39, A), its height. */
internal const val FOLDER_HEADER = 39

/**
 * The open folder's header (sim-16, sim-374, sim-482, C): the theme's band from the left edge, the
 * folder's own icon at half its size, 45x45 from x 0, 5 px under the band's top (its shadow
 * going past the band's bottom, the parent drawing outside its children; matched to the pixel on
 * the three captures), the folder's name at x 57 in BBAlpha Sans 27 px, white. The name is typed in the text box of Edit Folder ([dev.bold7.ui.TextDialog]), not here;
 * a name wider than its room ends in an ellipsis.
 */
internal class FolderHeader(context: Context) : FrameLayout(context) {
    private var band: Bitmap? = null
    private var icon: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
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
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, FOLDER_HEADER).apply { leftMargin = NAME_X })
    }

    fun images(band: Bitmap) {
        this.band = band
        invalidate()
    }

    /** Shows [folder]'s name and its [icon] (the 92 px image). */
    fun show(folder: String, icon: Bitmap?) {
        if (name.text.toString() != folder) name.setText(folder)
        if (icon !== this.icon) {
            this.icon = icon
            invalidate()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(FOLDER_HEADER, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        band?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        icon?.let {
            dst.set(0, ICON_Y, ICON, ICON_Y + ICON)
            canvas.drawBitmap(it, null, dst, paint)
        }
    }

    private companion object {
        const val ICON = 45 // the 92 px icon's ink, 69x55, is 34x27 on sim-16 and sim-374
        const val ICON_Y = 5 // sim-16: the image from y 192, the band from 187
        const val NAME_X = 57 // sim-16: "Instant Messaging" from x 57
    }
}
