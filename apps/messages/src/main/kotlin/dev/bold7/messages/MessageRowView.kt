package dev.bold7.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.ui.Chunk
import dev.bold7.ui.EmoticonPen
import dev.bold7.ui.EmoticonStrip
import dev.bold7.ui.emoticonChunks
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fitEmoticons
import dev.bold7.ui.osEmoji
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint

/** The two strips of status icons, decoded off the main thread; null until then (nothing drawn). */
class IconStrips {
    @Volatile var message: Bitmap? = null
    @Volatile var phone: Bitmap? = null

    fun of(strip: Strip) = if (strip == Strip.MESSAGE) message else phone
}

/**
 * A row of the inbox (docs/BBOS7-VISUEL.md §15, §16), measured on sim-53 (C, 7.1) and gsm 027 to
 * 029 (C, 7.0): 70 px and a 1 px line; the status icon, a 42 px cell of its strip, at x 15, 14 px
 * under the row's top; the sender in BBAlpha Sans 27 black at x 64 on a baseline 32 under the top,
 * bold while unread (gsm 029); the time in 23 black on the same baseline, its end 5 px from the
 * right edge; the subject or text in 23 grey on a baseline 62. Under the trackpad's focus, the blue
 * band of the lists over the row and its line, the texts white. Kept to the right edge on the Q25 (D).
 */
class MessageRowView(context: Context, private val strips: IconStrips) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.ROW
        const val ICON = 42
        const val ICON_X = 15
        const val ICON_Y = 14
        const val TEXT_X = 64
        const val TITLE_BASELINE = 32
        const val PREVIEW_BASELINE = 62
        const val TIME_RIGHT = 5

        /** Between the sender's end and the time: by eye (D). */
        const val TIME_GAP = 12
    }

    private val divider = fillPaint(ListColors.Divider)
    private val focus = focusPaint(HEIGHT)
    private val sender = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val senderBold = textPaint(Typography.TEXT, Typography.BOLD, ListColors.Title)
    private val senderLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val senderBoldLit = textPaint(Typography.TEXT, Typography.BOLD, ListColors.TitleBarText)
    private val time = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
    private val timeLit = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val preview = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val previewLit = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText)
    private val bitmapPaint = Paint()
    private val src = Rect()
    private val dst = Rect(ICON_X, ICON_Y, ICON_X + ICON, ICON_Y + ICON)

    private var line: ItemLine? = null
    private var lit = false
    private var timeWidth = 0f

    // The preview cut to the row, its emojis drawn as OS 7's emoticons.
    private var shownPreview: List<Chunk> = emptyList()
    // The sender's name too: a name may carry an emoji (the clip cuts it to the row).
    private var shownSender: List<Chunk> = emptyList()
    private val pen = EmoticonPen()

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
        isHapticFeedbackEnabled = false
    }

    /** Shows [line], [lit] under the trackpad's focus; redrawn only when either changes. */
    fun bind(line: ItemLine, lit: Boolean) {
        if (line === this.line && lit == this.lit) return
        if (line.time !== this.line?.time) timeWidth = time.measureText(line.time)
        this.line = line
        this.lit = lit
        val icon = line.icon
        src.set(icon.column * ICON, icon.row * ICON, (icon.column + 1) * ICON, (icon.row + 1) * ICON)
        cut()
        invalidate()
    }

    // Never in onDraw: when the line or the width changes. The preview's room is up to the right edge (D, as its clip was).
    private fun cut() {
        val l = line ?: return
        shownPreview = emoticonChunks(preview.fitEmoticons(osEmoji(l.preview), (width - TIME_RIGHT - TEXT_X).coerceAtLeast(0).toFloat()))
        shownSender = emoticonChunks(osEmoji(l.sender))
        EmoticonStrip.want(this, shownPreview + shownSender)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut()

    override fun onDraw(canvas: Canvas) {
        val l = line ?: return
        val w = width.toFloat()
        val bottom = HEIGHT.toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, bottom, focus) else canvas.drawRect(0f, bottom - ListDimens.DIVIDER, w, bottom, divider)
        strips.of(l.icon.strip)?.let { canvas.drawBitmap(it, src, dst, bitmapPaint) }
        val right = w - TIME_RIGHT
        canvas.drawText(l.time, right, TITLE_BASELINE.toFloat(), if (lit) timeLit else time)
        canvas.save()
        canvas.clipRect(TEXT_X.toFloat(), 0f, right - timeWidth - TIME_GAP, bottom)
        pen.draw(canvas, shownSender, TEXT_X.toFloat(), TITLE_BASELINE.toFloat(), if (l.unread) (if (lit) senderBoldLit else senderBold) else if (lit) senderLit else sender)
        canvas.restore()
        pen.draw(canvas, shownPreview, TEXT_X.toFloat(), PREVIEW_BASELINE.toFloat(), if (lit) previewLit else preview)
    }
}

/**
 * The blue arrows at the list's right edge while it scrolls further up or down (gsm 027, C):
 * navigation_up_arrow.gif and navigation_down_arrow.gif of the theme (A, 19x9), at the top of the
 * list and at its bottom, against the right edge. Drawn over the list, untouched.
 */
class ScrollArrows(context: Context) : View(context) {
    @Volatile var up: Bitmap? = null
    @Volatile var down: Bitmap? = null
    private var showUp = false
    private var showDown = false
    private val paint = Paint()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(up: Boolean, down: Boolean) {
        if (up == showUp && down == showDown) return
        showUp = up
        showDown = down
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (showUp) up?.let { canvas.drawBitmap(it, (width - it.width).toFloat(), 0f, paint) }
        if (showDown) down?.let { canvas.drawBitmap(it, (width - it.width).toFloat(), (height - it.height).toFloat(), paint) }
    }
}
