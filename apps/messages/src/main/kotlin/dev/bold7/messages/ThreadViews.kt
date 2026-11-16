package dev.bold7.messages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import dev.bold7.design.Focus
import dev.bold7.design.ThreadColors
import dev.bold7.design.ThreadDimens as D
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.useCaret
import dev.bold7.ui.slate
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

// The thread of OS 7 (docs/BBOS7-VISUEL.md §16): the contact's band, the bubbles, the field at the
// bottom. Measures in ThreadDimens and ThreadColors, from sim-49, sim-50, sim-52 (C, 7.1) and
// gsm 023, 025 (C, 7.0). Nothing allocated in onDraw.

/** The theme's images the thread draws, decoded off the main thread; null until then (nothing drawn). */
class ThreadImages {
    @Volatile var icons: Bitmap? = null
    @Volatile var avatar: Bitmap? = null
    @Volatile var smiley: Bitmap? = null
    @Volatile var smileyFocus: Bitmap? = null
    /** The emoticons' strip of Add Smiley (net_rim_bb_sms, A); the 27 px one of the text is core/ui's [EmoticonStrip]. */
    @Volatile var smileys45: Bitmap? = null
}

/** The caret of OS 7's fields: a bar in the focus blue (sim-50, C), the text's top to its bottom. */
private fun caretPaint() = Paint().apply {
    shader = LinearGradient(0f, 0f, 0f, D.CARET_HEIGHT.toFloat(), Focus.RowTop, Focus.RowBottom, Shader.TileMode.CLAMP)
}

/**
 * The band at the top: the contact's picture and name (sim-50; the contact's own picture, gsm 023,
 * C), or for a new message a To line per recipient and an empty one under them (sim-49, gsm 025,
 * C). A touch on a To line tells which ([onLine]).
 */
class ThreadBandView(context: Context, private val images: ThreadImages, private val onLine: (Int) -> Unit) : View(context) {
    private val band = fillPaint(ThreadColors.Band)
    private val name = textPaint(Typography.TEXT, Typography.REGULAR, ThreadColors.BandText)
    private val fieldText = textPaint(Typography.TEXT, Typography.REGULAR, ThreadColors.Text)
    private val field: Drawable = themeImage(context, R.drawable.msg_field)
    private val caret = caretPaint()
    private val avatarDst = Rect(D.AVATAR_X, D.AVATAR_Y, D.AVATAR_X + D.AVATAR, D.AVATAR_Y + D.AVATAR)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val label = context.getString(R.string.thread_to)
    private var title = ""
    private var lines: List<String>? = null
    private var caretLine = -1
    private var photo: Bitmap? = null

    /** [lines] non null: a new message's To lines, the caret on [caretLine] (-1: none); else [title] and [photo] (null: the default one). */
    fun bind(title: String, lines: List<String>?, caretLine: Int, photo: Bitmap?) {
        if (title == this.title && lines == this.lines && caretLine == this.caretLine && photo === this.photo) return
        val resize = lines?.size != this.lines?.size
        this.title = title
        this.lines = lines
        this.caretLine = caretLine
        this.photo = photo
        if (resize) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = lines?.size
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (n == null) D.BAND else D.TO_BAND + (n - 1) * D.TO_LINE)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val n = lines?.size ?: return false
        if (e.actionMasked == MotionEvent.ACTION_UP) onLine((e.y.toInt() / D.TO_LINE).coerceIn(0, n - 1))
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), band)
        val lines = lines
        if (lines == null) {
            (photo ?: images.avatar)?.let { canvas.drawBitmap(it, null, avatarDst, smooth) }
            canvas.drawText(title, D.NAME_X.toFloat(), D.NAME_BASELINE.toFloat(), name)
            return
        }
        val x = (D.TO_FIELD_X + D.FIELD_TEXT_X).toFloat()
        lines.forEachIndexed { i, text ->
            val top = D.TO_FIELD_TOP + i * D.TO_LINE
            canvas.drawText(label, D.TO_LABEL_X.toFloat(), (D.TO_BASELINE + i * D.TO_LINE).toFloat(), name)
            field.setBounds(D.TO_FIELD_X, top, width - D.TO_FIELD_RIGHT, top + D.TO_FIELD_HEIGHT)
            field.draw(canvas)
            canvas.drawText(text, x, (top + D.FIELD_BASELINE).toFloat(), fieldText)
            if (i == caretLine) drawCaret(canvas, x + fieldText.measureText(text), top + D.CARET_TOP, caret)
        }
    }
}

private fun drawCaret(canvas: Canvas, x: Float, top: Int, paint: Paint) {
    canvas.save()
    canvas.translate(x, top.toFloat())
    canvas.drawRect(0f, 0f, D.CARET_WIDTH.toFloat(), D.CARET_HEIGHT.toFloat(), paint)
    canvas.restore()
}

/**
 * The field's band at the bottom (sim-50, gsm 025): its gradient, the white field, the smiley
 * button and its focus (sim-146). The field is an editor ([editor]), so that the input method
 * (Typing: substitution, capitals, accents, Sym) types into it: OS 7's look, without Android's
 * underline, handles, selection bar or suggestions, its emoticons drawn with OS 7's images
 * (sim-148). A text longer than the field scrolls to show its end (D: OS 7's field grows over the
 * thread, not reproduced). [onSmiley]: a touch on the button.
 */
@SuppressLint("ViewConstructor")
class ComposerView(context: Context, private val images: ThreadImages, onSmiley: () -> Unit) : FrameLayout(context) {
    private val band = Paint()
    private val field: Drawable = themeImage(context, R.drawable.msg_field)
    private var smileyFocused = false

    val editor = object : EditText(context) {
        // No floating bar of Android over the field (cut, copy, paste, its suggestions).
        override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
    }.apply {
        background = null
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ThreadColors.Text)
        includeFontPadding = false
        isSingleLine = true
        setHorizontallyScrolling(true)
        gravity = Gravity.TOP or Gravity.START
        // Its baseline FIELD_BASELINE under the field's top (sim-50), the text FIELD_TEXT_X in.
        setPadding(0, D.FIELD_BASELINE + paint.fontMetricsInt.ascent, 0, 0)
        // Text for a message, a capital at a sentence's start, the input method's substitutions kept.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // OS 7's caret: a bar in the focus blue (sim-50, C).
        useCaret()
        val none = ColorDrawable(0)
        setTextSelectHandle(none)
        setTextSelectHandleLeft(none)
        setTextSelectHandleRight(none)
        isLongClickable = false
        setTextIsSelectable(false)
        isHapticFeedbackEnabled = false
        defaultFocusHighlightEnabled = false
    }

    init {
        setWillNotDraw(false)
        // Its 66 rows: top, middle (row 30), bottom (sim-50, column 575).
        band.shader = LinearGradient(
            0f, 0f, 0f, D.COMPOSER.toFloat(),
            intArrayOf(ThreadColors.ComposerTop, ThreadColors.ComposerMiddle, ThreadColors.ComposerBottom), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        addView(editor, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.FIELD_HEIGHT).apply {
            leftMargin = D.FIELD_X + D.FIELD_TEXT_X
            rightMargin = D.FIELD_RIGHT + D.FIELD_TEXT_X
            topMargin = D.FIELD_TOP
        })
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP && e.x >= width - D.FIELD_RIGHT) onSmiley()
            true
        }
    }

    /** The caret while the field has the focus, grey text while it leaves (D), the button's focus. */
    fun bind(focused: Boolean, sending: Boolean, smileyFocused: Boolean) {
        editor.isCursorVisible = focused && !sending
        editor.setTextColor(if (sending) ThreadColors.Sender else ThreadColors.Text)
        if (smileyFocused != this.smileyFocused) {
            this.smileyFocused = smileyFocused
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(D.COMPOSER, MeasureSpec.EXACTLY))

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), band)
        field.setBounds(D.FIELD_X, D.FIELD_TOP, width - D.FIELD_RIGHT, D.FIELD_TOP + D.FIELD_HEIGHT)
        field.draw(canvas)
        val x = (width - D.SMILEY_RIGHT).toFloat()
        // smiley_button_focus_indicator.png, 56x56, 7 px left of the button and 8 above (sim-146).
        if (smileyFocused) images.smileyFocus?.let { canvas.drawBitmap(it, x - SMILEY_FOCUS_X, (D.SMILEY_TOP - SMILEY_FOCUS_Y).toFloat(), null) }
        images.smiley?.let { canvas.drawBitmap(it, x, D.SMILEY_TOP.toFloat(), null) }
    }

    private companion object {
        const val SMILEY_FOCUS_X = 7
        const val SMILEY_FOCUS_Y = 8
    }
}

/**
 * One message in its bubble (sim-50: sent; gsm 023: received): "Me" or the sender in bold and the
 * time over a line, the state icon, the text, then an MMS's images (D: no capture of an MMS in a
 * thread) and the count of its other parts. Sent bubbles lean left with their pointer at the
 * bottom left, received ones right (C). Under the trackpad's focus, a blue edge (D: not captured).
 */
class BubbleView(context: Context, private val images: ThreadImages, private val picture: (String) -> Bitmap?) : View(context) {
    companion object {
        /** An image's largest height in a bubble, and the space above it: by eye (D). */
        const val IMAGE_MAX_HEIGHT = 240
        const val IMAGE_GAP = 6
    }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThreadColors.Shadow }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val focusEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Focus.RowTop }
    private val sentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, D.BUBBLE_GRADIENT.toFloat(), ThreadColors.SentTop, ThreadColors.SentBottom, Shader.TileMode.CLAMP) }
    private val receivedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, D.BUBBLE_GRADIENT.toFloat(), ThreadColors.ReceivedTop, ThreadColors.ReceivedBottom, Shader.TileMode.CLAMP) }
    private val bottomFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val separator = fillPaint(ThreadColors.Separator)
    private val sender = textPaint(D.HEAD_SIZE, Typography.BOLD, ThreadColors.Sender)
    private val time = textPaint(D.HEAD_SIZE, Typography.REGULAR, ThreadColors.Sender, align = Paint.Align.RIGHT)
    private val body = TextPaint(textPaint(Typography.TEXT, Typography.REGULAR, ThreadColors.Text))
    private val note = textPaint(D.HEAD_SIZE, Typography.REGULAR, ThreadColors.Sender)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bubble = RectF()
    private val outer = RectF()
    private val pointer = Path()
    private val iconSrc = Rect()
    private val iconDst = Rect()
    private val imageDst = Rect()

    private var message: ThreadMessage? = null
    private var label = ""
    private var stamp = ""
    private var lit = false
    private var layout: StaticLayout? = null
    private var textTop = 0f
    private var imagesTop = 0
    private var extra: String? = null
    private var bubbleHeight = 0

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isHapticFeedbackEnabled = false
    }

    /** The bubble, then 19 px to the next one's top border (its pointer in between, sim-50). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), bubbleHeight + D.BUBBLE_GAP - 1)

    /** Shows [message] under [label] ("Me" or the sender), [stamp] its time, [lit] under the focus. */
    fun bind(message: ThreadMessage, label: String, stamp: String, lit: Boolean, width: Int) {
        if (message === this.message && label == this.label && stamp == this.stamp && lit == this.lit && layout?.width == textWidth(width)) {
            invalidate() // an image may have come
            return
        }
        this.message = message
        this.label = label
        this.stamp = stamp
        this.lit = lit
        // Its emoticons in OS 7's images, emojis and typed shortcuts alike.
        val text = withEmoticons(message.text, shortcuts = true)
        layout = text.takeIf { it.isNotEmpty() }?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, body, textWidth(width)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        }
        extra = if (message.otherParts > 0) context.resources.getQuantityString(R.plurals.thread_attachments, message.otherParts, message.otherParts) else null
        val l = layout
        textTop = if (l == null) 0f else (D.TEXT_BASELINE - l.getLineBaseline(0)).toFloat()
        // Without text, the images start under the icon's row (D).
        imagesTop = if (l == null) D.ICON_Y + D.ICON + IMAGE_GAP else (textTop + l.height).toInt() + IMAGE_GAP
        var bottom = if (l == null) D.ICON_Y + D.ICON else D.TEXT_BASELINE + (l.getLineBaseline(l.lineCount - 1) - l.getLineBaseline(0)) + D.TEXT_BOTTOM
        if (message.images.isNotEmpty()) bottom = imagesTop + message.images.size * (imageHeight(textWidth(width)) + IMAGE_GAP) + D.TEXT_BOTTOM
        if (extra != null) bottom += D.HEAD_SIZE + D.TEXT_BOTTOM
        // Its last row is the bottom border (sim-50: rows 69 to 145 for one line, 77).
        bubbleHeight = bottom + 1
        val icon = iconCell(message.delivery)
        iconSrc.set(icon.second * D.ICON, icon.first * D.ICON, (icon.second + 1) * D.ICON, (icon.first + 1) * D.ICON)
        requestLayout()
        invalidate()
    }

    /** Images have one size, the text's width and at most [IMAGE_MAX_HEIGHT] (D): each fits in it. */
    private fun imageHeight(textWidth: Int) = minOf(IMAGE_MAX_HEIGHT, textWidth)

    private fun textWidth(width: Int) = (width - D.BUBBLE_NEAR - D.BUBBLE_FAR - D.TEXT_X - D.PAD).coerceAtLeast(1)

    /** Row and column of net_rim_Message-icons32x32.png (A): row 1 the texts' (9 check, 10 D, 11 R, 12 cross, 5 clock), row 2 col 15 and 16 the orange and grey dots (gsm 023: grey on a received message, C). */
    private fun iconCell(d: Delivery): Pair<Int, Int> = when (d) {
        Delivery.UNREAD -> 2 to 15
        Delivery.RECEIVED -> 2 to 16
        Delivery.SENDING -> 1 to 5
        Delivery.SENT -> 1 to 9
        Delivery.DELIVERED -> 1 to 10
        Delivery.FAILED -> 1 to 12
    }

    override fun onDraw(canvas: Canvas) {
        val m = message ?: return
        val mine = m.out
        val left = (if (mine) D.BUBBLE_NEAR else D.BUBBLE_FAR).toFloat()
        val right = (width - if (mine) D.BUBBLE_FAR else D.BUBBLE_NEAR).toFloat()
        val top = D.BUBBLE_TOP.toFloat()
        val bottom = top + bubbleHeight
        val r = D.BUBBLE_RADIUS.toFloat()
        val fill = if (mine) sentFill else receivedFill
        val borderColor = if (mine) ThreadColors.SentBorder else ThreadColors.ReceivedBorder
        border.color = borderColor
        edge.color = borderColor
        bottomFill.color = if (mine) ThreadColors.SentBottom else ThreadColors.ReceivedBottom

        outer.set(left - 1, top - 1, right + 1, bottom + 1)
        canvas.drawRoundRect(outer, r + 1, r + 1, shadow)
        bubble.set(left, top, right, bottom)
        canvas.drawRoundRect(bubble, r, r, border)
        canvas.save()
        canvas.translate(0f, top)
        bubble.set(left + 1, 1f, right - 1, bottom - top - 1)
        canvas.drawRoundRect(bubble, r - 1, r - 1, fill)
        canvas.restore()

        // The pointer: its straight side 6 px in from the bubble's edge, 9 px down (sim-50).
        val side = if (mine) left + D.POINTER_IN else right - D.POINTER_IN
        val far = if (mine) side + D.POINTER else side - D.POINTER
        pointer.rewind()
        pointer.moveTo(side, bottom - 1)
        pointer.lineTo(side, bottom - 1 + D.POINTER)
        pointer.lineTo(far, bottom - 1)
        pointer.close()
        canvas.drawPath(pointer, bottomFill)
        pointer.rewind()
        pointer.moveTo(side, bottom - 1)
        pointer.lineTo(side, bottom - 1 + D.POINTER)
        pointer.lineTo(far, bottom - 1)
        canvas.drawPath(pointer, edge)

        canvas.drawText(label, left + D.PAD, top + D.SENDER_BASELINE, sender)
        canvas.drawText(stamp, right - D.PAD, top + D.TIME_BASELINE, time)
        canvas.drawRect(left + D.ICON_X, top + D.SEPARATOR, right - D.ICON_X, top + D.SEPARATOR + 1, separator)
        images.icons?.let {
            iconDst.set((left + D.ICON_X).toInt(), (top + D.ICON_Y).toInt(), (left + D.ICON_X).toInt() + D.ICON, (top + D.ICON_Y).toInt() + D.ICON)
            canvas.drawBitmap(it, iconSrc, iconDst, null)
        }
        val x = left + D.TEXT_X
        layout?.let {
            canvas.save()
            canvas.translate(x, top + textTop)
            it.draw(canvas)
            canvas.restore()
        }
        val w = textWidth(width)
        val h = imageHeight(w)
        var y = (top + imagesTop).toInt()
        for (uri in m.images) {
            picture(uri)?.let { bmp ->
                // Fitted in w x h, never enlarged.
                val scale = minOf(1f, w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                imageDst.set(x.toInt(), y, x.toInt() + (bmp.width * scale).toInt(), y + (bmp.height * scale).toInt())
                canvas.drawBitmap(bmp, null, imageDst, smooth)
            }
            y += h + IMAGE_GAP
        }
        extra?.let { canvas.drawText(it, x, y + D.HEAD_SIZE.toFloat(), note) }
        if (lit) {
            bubble.set(left + 1, top + 1, right - 1, bottom - 1)
            canvas.drawRoundRect(bubble, r, r, focusEdge)
        }
    }
}

/**
 * The attachment in the field, a band over the field's (D: no capture of an
 * attachment in a text's field): its picture's thumbnail or its kind's attachment_*.png (52x52,
 * A), its name and its size, on the field band's middle colour. Gone without one.
 */
class AttachmentView(context: Context) : View(context) {
    companion object {
        /** By eye (D): the 52 px image and 6 px around it. */
        const val HEIGHT = 64
        const val IMAGE = 52
        const val PAD = 6
    }

    private val background = fillPaint(ThreadColors.ComposerMiddle)
    private val name = TextPaint(textPaint(Typography.TEXT, Typography.REGULAR, ThreadColors.BandText))
    private val size = textPaint(D.HEAD_SIZE, Typography.REGULAR, ThreadColors.Separator)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var title = ""
    private var shown: CharSequence = ""
    private var detail = ""
    private var image: Bitmap? = null

    fun bind(title: String, detail: String, image: Bitmap?) {
        this.title = title
        this.detail = detail
        this.image = image
        ellipsize()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = ellipsize()

    /** A long name ends in an ellipsis before the edge (D). */
    private fun ellipsize() {
        shown = TextUtils.ellipsize(title, name, (width - 3 * PAD - IMAGE).coerceAtLeast(0).toFloat(), TextUtils.TruncateAt.END)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), HEIGHT)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        image?.let { b ->
            // Fitted in the 52 px square, centred, never enlarged.
            val scale = minOf(1f, IMAGE.toFloat() / b.width, IMAGE.toFloat() / b.height)
            val w = (b.width * scale).toInt()
            val h = (b.height * scale).toInt()
            dst.set(PAD + (IMAGE - w) / 2, PAD + (IMAGE - h) / 2, PAD + (IMAGE + w) / 2, PAD + (IMAGE + h) / 2)
            canvas.drawBitmap(b, null, dst, smooth)
        }
        val x = (2 * PAD + IMAGE).toFloat()
        canvas.drawText(shown, 0, shown.length, x, PAD + Typography.TEXT.toFloat(), name)
        canvas.drawText(detail, x, (HEIGHT - PAD * 2).toFloat(), size)
    }
}
