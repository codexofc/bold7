package dev.bold7.launcher.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.Focus
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.ui.TitleBar
import dev.bold7.ui.fillPaint
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeBitmap
import dev.bold7.ui.themeImage

// The pieces of the wizard's pages that Options' and core/ui's views do not draw. Measured on the
// Setup application's pages (sim-384 to sim-389, C); what the square screen adds is D.

/** The title bar of an Options page (TitleBar), the page's place in the wizard at its right (D). */
internal class WizardTitle(context: Context) : TitleBar(context) {
    private val paint = textPaint(TitleBar.TITLE_SIZE, Typography.REGULAR, ListColors.SearchHint, align = Paint.Align.RIGHT, family = Typography.FAMILY_CONDENSED)
    private var progress = ""

    fun bind(title: String, progress: String) {
        bind(title)
        if (progress == this.progress) return
        this.progress = progress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(progress, (width - ListDimens.TEXT_X).toFloat(), TitleBar.TITLE_BASELINE.toFloat(), paint)
    }
}

/**
 * A paragraph in a group box, as Ring Tones' (sim-385, C): BBAlpha Sans 27 black from x 9, wrapped
 * to the box. [badge]: the green check of the Setup application's done tiles before it
 * (cat_toolkit_checkmark.png, its 22 px circle at 37, 6, A; sim-375, C), the text after it (D).
 */
internal class ParagraphView(
    context: Context,
    private var text: String,
    size: Int = Typography.TEXT,
    bold: Boolean = false,
    color: Int = ListColors.Title,
    private val badge: Boolean = false,
) : View(context) {
    private val paint = TextPaint(textPaint(size, if (bold) Typography.BOLD else Typography.REGULAR, color))
    private val sprite: Bitmap? = if (badge) themeBitmap(context, "opt_checkmark") else null
    private val src = Rect(37, 6, 59, 28)
    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var layout: StaticLayout? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = text
    }

    fun bind(text: String) {
        if (text == this.text) return
        this.text = text
        contentDescription = text
        layout = null
        requestLayout()
        invalidate()
    }

    private fun left() = ListDimens.TEXT_X + if (badge) BADGE_ROOM else 0

    private fun layout(width: Int): StaticLayout = layout?.takeIf { it.width == room(width) }
        ?: StaticLayout.Builder.obtain(text, 0, text.length, paint, room(width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build().also { layout = it }

    private fun room(width: Int) = (width - left() - ListDimens.TEXT_X).coerceAtLeast(1)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, if (text.isEmpty()) 0 else layout(w).height + 2 * PAD)
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val l = layout(width)
        sprite?.let {
            val y = PAD + (l.getLineBottom(0) - src.height()) / 2
            dst.set(ListDimens.TEXT_X, y, ListDimens.TEXT_X + src.width(), y + src.height())
            canvas.drawBitmap(it, src, dst, bitmapPaint)
        }
        canvas.save()
        canvas.translate(left().toFloat(), PAD.toFloat())
        l.draw(canvas)
        canvas.restore()
    }

    private companion object {
        // sim-385: the lines 32 apart, the first one 8 under the title bar's line.
        const val PAD = 4
        const val BADGE_ROOM = 32
    }
}

/** A theme image centred in its row, as Ring Tones' speaker under its text (sim-385, C). */
internal class ImageRowView(context: Context, name: String) : View(context) {
    private val image = themeBitmap(context, name)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), image.height + 2 * PAD)

    override fun onDraw(canvas: Canvas) = canvas.drawBitmap(image, ((width - image.width) / 2).toFloat(), PAD.toFloat(), paint)

    private companion object {
        const val PAD = 8
    }
}

/**
 * The wallpapers, four a row (Pictures' grid of Wallpaper, sim-388, C: square pictures, 2 px apart,
 * the focus a blue frame), recomputed for the box's width (D); the one set has the green check
 * at its top right (cat_toolkit_checkmark, A). A touch on a picture is [onTap] with its index.
 */
internal class WallpaperGridView(context: Context, count: Int, private val onTap: (Int) -> Unit) : View(context) {
    val thumbs = arrayOfNulls<Bitmap>(count)
    private val sprite = themeBitmap(context, "opt_checkmark")
    private val empty = fillPaint(ListColors.Subtitle)
    private val frame = Paint().apply { color = Focus.RowTop; style = Paint.Style.STROKE; strokeWidth = FRAME.toFloat() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect(37, 6, 59, 28)
    private val dst = Rect()
    private val cell = RectF()
    var lit = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var chosen = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    init {
        contentDescription = "Wallpapers"
    }

    fun side(width: Int = this.width) = (width - 2 * ListDimens.TEXT_X - (COLUMNS - 1) * GAP) / COLUMNS

    /** The cell of picture [i], for the scroll to bring it into view. */
    fun cellRect(i: Int): Rect {
        val s = side()
        val x = ListDimens.TEXT_X + (i % COLUMNS) * (s + GAP)
        val y = PAD + (i / COLUMNS) * (s + GAP)
        return Rect(x, y, x + s, y + s)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val lines = (thumbs.size + COLUMNS - 1) / COLUMNS
        setMeasuredDimension(w, 2 * PAD + lines * side(w) + (lines - 1).coerceAtLeast(0) * GAP)
    }

    override fun onDraw(canvas: Canvas) {
        for (i in thumbs.indices) {
            val r = cellRect(i)
            cell.set(r)
            val t = thumbs[i]
            if (t == null) canvas.drawRect(cell, empty) else canvas.drawBitmap(t, null, r, paint)
            if (i == chosen) {
                dst.set(r.right - src.width() - 4, r.top + 4, r.right - 4, r.top + 4 + src.height())
                canvas.drawBitmap(sprite, src, dst, paint)
            }
            if (i == lit) {
                cell.inset(FRAME / 2f, FRAME / 2f)
                canvas.drawRect(cell, frame)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val i = thumbs.indices.firstOrNull { cellRect(it).contains(e.x.toInt(), e.y.toInt()) }
            if (i != null) onTap(i)
        }
        return true
    }

    private companion object {
        const val GAP = 6
        const val PAD = 8
        const val FRAME = 4
    }
}

/**
 * The grey band at the foot of a Setup page and its buttons (sim-385, C: #DDDDDD, 57 px, the
 * button 51 high 3 under its top, border_button with its label 27, blue under the focus; sim-384:
 * Return to Setup centred). The wizard's Back and Next side by side, centred, of one width (D).
 * [plain]: drawn without the firmware's images, for the page that says they are missing.
 */
internal class BandView(context: Context, private val plain: Boolean, private val onTap: (Int) -> Unit) : View(context) {
    private val normal = if (plain) null else themeImage(context, "ui_button")
    private val focused = if (plain) null else themeImage(context, "ui_button_focus")
    private val background = fillPaint(ListColors.GroupBackground)
    private val plainButton = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DialogColors.Button }
    private val plainFocus = focusPaint(DialogDimens.BUTTON_HEIGHT)
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val textLit = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val box = RectF()
    private var labels = emptyList<String>()
    private var lit = -1

    fun bind(labels: List<String>, lit: Int) {
        if (labels == this.labels && lit == this.lit) return
        this.labels = labels
        this.lit = lit
        contentDescription = labels.joinToString(", ")
        invalidate()
    }

    private fun buttonWidth() = maxOf(MIN_WIDTH, (labels.maxOfOrNull { text.measureText(it) } ?: 0f).toInt() + 2 * PAD)

    private fun left(i: Int): Int {
        val w = buttonWidth()
        val all = labels.size * w + (labels.size - 1) * GAP
        return (width - all) / 2 + i * (w + GAP)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), HEIGHT)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val w = buttonWidth()
        for (i in labels.indices) {
            val l = left(i)
            val on = i == lit
            if (plain) {
                box.set(l.toFloat(), TOP.toFloat(), (l + w).toFloat(), (TOP + DialogDimens.BUTTON_HEIGHT).toFloat())
                canvas.save()
                canvas.translate(0f, TOP.toFloat())
                box.offset(0f, -TOP.toFloat())
                canvas.drawRoundRect(box, 6f, 6f, if (on) plainFocus else plainButton)
                canvas.restore()
            } else {
                (if (on) focused else normal)!!.apply { setBounds(l - 3, TOP - 3, l + w + 3, TOP + DialogDimens.BUTTON_HEIGHT + 3) }.draw(canvas)
            }
            canvas.drawText(labels[i], l + w / 2f, (TOP + DialogDimens.BUTTON_BASELINE).toFloat(), if (on) textLit else text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val w = buttonWidth()
            val i = labels.indices.firstOrNull { e.x >= left(it) && e.x < left(it) + w }
            if (i != null) onTap(i)
        }
        return true
    }

    companion object {
        const val HEIGHT = 57
        private const val TOP = 3
        private const val PAD = 20
        private const val GAP = 24
        private const val MIN_WIDTH = 200
    }
}
