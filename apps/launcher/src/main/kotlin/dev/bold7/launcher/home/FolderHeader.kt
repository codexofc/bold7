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
 * The open folder's header (sim-16, C): the theme's band from the left edge, the small folder icon
 * at x 6 (net_rim_Folder-icons32x32.png, its open folder, A), the folder's name at x 57 in BBAlpha
 * Sans 27 px, white. The name is typed in the text box of Edit Folder ([dev.bold7.ui.TextDialog]), not here;
 * a name wider than its room ends in an ellipsis.
 */
internal class FolderHeader(context: Context) : FrameLayout(context) {
    private var band: Bitmap? = null
    private var icons: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
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

    fun images(band: Bitmap, icons: Bitmap) {
        this.band = band
        this.icons = icons
        invalidate()
    }

    /** Shows [folder]'s name. */
    fun show(folder: String) {
        if (name.text.toString() != folder) name.setText(folder)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(FOLDER_HEADER, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        band?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        icons?.let {
            val f = OPEN_FRAME * ICON
            src.set(f, 0, f + ICON, ICON)
            dst.set(ICON_X, (FOLDER_HEADER - ICON) / 2, ICON_X + ICON, (FOLDER_HEADER + ICON) / 2)
            canvas.drawBitmap(it, src, dst, paint)
        }
    }

    private companion object {
        const val ICON = 32
        const val ICON_X = 6 // sim-16: x 6 to 38
        const val NAME_X = 57 // sim-16: "Instant Messaging" from x 57
        const val OPEN_FRAME = dev.bold7.design.HomeDimens.FOLDER_OPEN_FRAME // the strip's last frame, the open folder (by eye)
    }
}
