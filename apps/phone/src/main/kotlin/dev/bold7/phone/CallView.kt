package dev.bold7.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.textPaint

/** The call screen's images of the 9900's theme (A), decoded off the main thread; null until then. */
class CallImages(
    val background: Bitmap?,
    val avatar: Bitmap?,
    val bar: Bitmap?,
    val barFocus: Bitmap?,
    val buttons: Map<Action, Bitmap?>,
    val volumeIcon: Bitmap?,
    val volume: Bitmap?,
    val receiver: Bitmap?,
    val muteOn: Bitmap?,
)

/**
 * The call screen of BBOS 7.1, drawn on one canvas (docs/BBOS7-VISUEL.md §26): the incoming call
 * of sim-18 ("Incoming Call", the avatar, the caller, the bar with Ringer Off) and the call of
 * sim-109 and gsm 019 (status and timer at the top left, the receiver's icon above them (sim-136,
 * sim-138), My Number and the volume at the top right, the avatar and the caller, the bar of
 * Speaker, Mute, Hold, Add Participant, Mute red while muted). The bar is touched only: the touched
 * button lit with incomingcall_button_l~focus.png while the finger is on it (D). Measures in pixels of
 * the 9900 (C), from the content's top, which sits under a 35 px bar there; on the Q25 the caller's
 * block moves down by half of the height gained, the bar stays at the bottom, the theme's 640 px
 * images are drawn at their size, centred, their edges stretched (D). Texts are measured in
 * [bind], never in onDraw.
 */
class CallView(context: Context, private val onButton: (Int) -> Unit, private val onHold: () -> Unit) : View(context) {
    companion object {
        /** The 9900's content under its one-line bar: 480 - 35 (sim-18). */
        const val NINE_HEIGHT = 445
        const val NINE_WIDTH = 640
        const val TITLE_BASELINE = 54
        const val AVATAR = 130
        const val AVATAR_TOP = 75
        /** A contact's photo inside the frame: default_avatar.png's 115 px (A), its corners rounded by eye (D). */
        const val PHOTO = 115
        const val PHOTO_RADIUS = 18f
        const val CALLER_BASELINE = 256
        /** activecallscreen.pme: bestfit, 470 px wide, 36 down to 24 (A); BBAlpha Sans Condensed 36 (C). */
        const val CALLER_WIDTH = 470
        const val CALLER_SIZE = 36
        const val CALLER_MIN = 24
        const val LINE2_BASELINE = 292
        const val STATUS_X = 6
        const val STATUS_BASELINE = 60
        const val TIMER_BASELINE = 92
        /** net_rim_Phone_Receiver-icons58x33.png's frame at the content's top left (sim-136, sim-138, C). */
        const val RECEIVER_W = 58
        const val RECEIVER_TOP = 1
        /** activecall_button_mute_p.png, 160 px wide (A), centred in its cell (D on the Q25's wider cells). */
        const val MUTE_ON_W = 160
        /** The tones typed, under the timer (D). */
        const val TONES_BASELINE = 123
        const val MY_NUMBER_RIGHT = 13
        const val MY_NUMBER_BASELINE = 25
        const val VOLUME_ICON_RIGHT = 132
        const val VOLUME_ICON_TOP = 41
        const val VOLUME_RIGHT = 111
        const val VOLUME_TOP = 49
        const val VOLUME_WIDTH = 110
        /** The grey texts: BBAlpha Sans Condensed 25, #B3B3B3 (sim-18, sim-109, C). */
        const val GREY_SIZE = 25
        const val GREY = 0xFFB3B3B3.toInt()
        const val BAR = 82
        const val ICON_W = 50
        const val ICON_TOP = 6
        const val LABEL_BASELINE = 67
        /** The bar's labels: BBAlpha Sans Condensed 20 white, greyed #999999 (gsm 019, C, JPEG). */
        const val LABEL_SIZE = 20
        const val LABEL_OFF = 0xFF999999.toInt()
        /** A greyed button's image: by eye on gsm 019 (D). */
        const val ICON_OFF_ALPHA = 110
        /** Between two buttons: a dark then a light line (sim-109, C). */
        const val LINE_DARK = 0xFF070A0C.toInt()
        const val LINE_LIGHT = 0xFF4B4F52.toInt()
    }

    private var images: CallImages? = null
    private var screen: CallScreen? = null
    private var photo: Bitmap? = null
    private var pressedCell = -1
    private var receiver = 0
    private var volumeLevel = -1

    private var title = ""
    private var status = ""
    private var timer = ""
    private var tones = ""
    private var myNumber = ""
    private var caller = ""
    private var line2 = ""
    private var labels = emptyList<String>()

    private val greyText = textPaint(GREY_SIZE, Typography.REGULAR, GREY, family = Typography.FAMILY_CONDENSED)
    private val greyCentred = textPaint(GREY_SIZE, Typography.REGULAR, GREY, align = Paint.Align.CENTER, family = Typography.FAMILY_CONDENSED)
    private val greyRight = textPaint(GREY_SIZE, Typography.REGULAR, GREY, align = Paint.Align.RIGHT, family = Typography.FAMILY_CONDENSED)
    private val callerText = textPaint(CALLER_SIZE, Typography.REGULAR, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER, family = Typography.FAMILY_CONDENSED)
    private val label = textPaint(LABEL_SIZE, Typography.REGULAR, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER, family = Typography.FAMILY_CONDENSED)
    private val labelOff = textPaint(LABEL_SIZE, Typography.REGULAR, LABEL_OFF, align = Paint.Align.CENTER, family = Typography.FAMILY_CONDENSED)
    private val dark = fillPaint(LINE_DARK)
    private val light = fillPaint(LINE_LIGHT)
    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val iconOff = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = ICON_OFF_ALPHA }
    private val backgroundPaint = Paint()
    private val barPaint = Paint()
    private val focusPaint = Paint()
    private val mutePaint = Paint()
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val photoClip = Path()
    private val src = Rect()
    private val dst = Rect()
    private val photoRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            press(cellAt(e))
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val cell = cellAt(e)
            press(-1)
            if (cell >= 0) onButton(cell)
            return cell >= 0
        }
        override fun onLongPress(e: MotionEvent) {
            press(-1)
            onHold()
        }
    })

    init {
        // Drawn on its canvas: the activity gives its menus and keys instead.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isHapticFeedbackEnabled = false
    }

    fun setImages(i: CallImages) {
        images = i
        shaders()
        invalidate()
    }

    fun setPhoto(p: Bitmap?) {
        if (p === photo) return
        photo = p
        photoPaint.shader = p?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        shaders()
        invalidate()
    }

    /** The screen and its texts, resolved by the activity; [labels] one per button. */
    fun bind(s: CallScreen, title: String, status: String, caller: String, line2: String, labels: List<String>, tones: String, myNumber: String, receiver: Int) {
        screen = s
        this.title = title
        this.status = status
        this.caller = caller
        this.line2 = line2
        this.labels = labels
        this.tones = tones
        this.myNumber = myNumber
        this.receiver = receiver
        fitCaller()
        refit()
        invalidate()
    }

    // What is drawn: each text cut by core/ui's fit to its room (Rooms), recomputed here and never in onDraw.
    private var shownCaller = ""
    private var shownLine2 = ""
    private var shownMyNumber = ""
    private var shownTones = ""
    private var shownLabels = emptyList<String>()

    private fun refit() {
        if (width == 0) return
        shownCaller = callerText.fit(caller, Rooms.caller(width))
        shownLine2 = greyCentred.fit(line2, Rooms.line2(width))
        shownMyNumber = greyRight.fit(myNumber, Rooms.myNumber(width))
        shownTones = greyText.fit(tones, Rooms.tones(width))
        val n = screen?.buttons?.size ?: 1
        shownLabels = labels.map { label.fit(it, Rooms.barLabel(width, n)) }
    }

    fun setTimer(t: String) {
        if (t == timer) return
        timer = t
        invalidate()
    }

    /** 0 to 10, the frame of net_rim_Phone_Volume-icons110x6.png (A); -1: not shown. */
    fun setVolume(level: Int) {
        if (level == volumeLevel) return
        volumeLevel = level
        invalidate()
    }

    private fun cellAt(e: MotionEvent): Int {
        val n = screen?.buttons?.size ?: return -1
        if (e.y < height - BAR) return -1
        return (e.x * n / width).toInt().coerceIn(0, n - 1)
    }

    private fun press(cell: Int) {
        if (cell == pressedCell) return
        pressedCell = cell
        invalidate()
    }

    /** activecallscreen.pme's bestfit: the size that fits 470 px, 36 down to 24 (A). */
    private fun fitCaller() {
        var size = CALLER_SIZE
        callerText.textSize = size.toFloat()
        while (size > CALLER_MIN && callerText.measureText(caller) > CALLER_WIDTH) {
            size--
            callerText.textSize = size.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shaders()
        refit()
    }

    /** The theme's 640 px images at their size, centred, their edge pixels carried to the Q25's edges (D). */
    private fun shaders() {
        val i = images ?: return
        if (width == 0) return
        val dx = (width - NINE_WIDTH) / 2f
        i.background?.let { backgroundPaint.shader = clamped(it, dx, 0f) }
        val barTop = (height - BAR).toFloat()
        i.bar?.let { barPaint.shader = clamped(it, dx, barTop) }
        i.barFocus?.let { focusPaint.shader = clamped(it, dx, barTop) }
        // Mute's cell, the second of four: the red image centred in it.
        i.muteOn?.let { mutePaint.shader = clamped(it, width * 1 / 4 + (width / 4 - MUTE_ON_W) / 2f, barTop) }
        val left = width / 2f - PHOTO / 2f
        val top = shift() + AVATAR_TOP + (AVATAR - PHOTO) / 2f
        photoRect.set(left, top, left + PHOTO, top + PHOTO)
        photoClip.reset()
        photoClip.addRoundRect(photoRect, PHOTO_RADIUS, PHOTO_RADIUS, Path.Direction.CW)
        photo?.let { p ->
            photoPaint.shader?.setLocalMatrix(Matrix().apply {
                setRectToRect(RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), photoRect, Matrix.ScaleToFit.CENTER)
            })
        }
    }

    private fun clamped(b: Bitmap, dx: Float, dy: Float) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setTranslate(dx, dy) })
    }

    /** The caller's block moves down by half the height the square gains under the bar (D). */
    private fun shift() = ((height - NINE_HEIGHT) / 2).coerceAtLeast(0)

    override fun onDraw(canvas: Canvas) {
        val s = screen ?: return
        val i = images
        val w = width.toFloat()
        val cx = w / 2
        canvas.drawRect(0f, 0f, w, height.toFloat(), backgroundPaint)
        val shift = shift()

        if (s.kind == Kind.INCOMING) {
            canvas.drawText(title, cx, (shift + TITLE_BASELINE).toFloat(), greyCentred)
        } else {
            if (receiver > 0) i?.receiver?.let {
                src.set(receiver * RECEIVER_W, 0, (receiver + 1) * RECEIVER_W, it.height)
                dst.set(0, RECEIVER_TOP, RECEIVER_W, RECEIVER_TOP + it.height)
                canvas.drawBitmap(it, src, dst, bitmap)
            }
            canvas.drawText(status, STATUS_X.toFloat(), STATUS_BASELINE.toFloat(), greyText)
            if (timer.isNotEmpty()) canvas.drawText(timer, STATUS_X.toFloat(), TIMER_BASELINE.toFloat(), greyText)
            if (shownTones.isNotEmpty()) canvas.drawText(shownTones, STATUS_X.toFloat(), TONES_BASELINE.toFloat(), greyText)
            canvas.drawText(shownMyNumber, w - MY_NUMBER_RIGHT, MY_NUMBER_BASELINE.toFloat(), greyRight)
            if (volumeLevel >= 0) {
                i?.volumeIcon?.let { canvas.drawBitmap(it, w - VOLUME_ICON_RIGHT, VOLUME_ICON_TOP.toFloat(), bitmap) }
                i?.volume?.let {
                    src.set(volumeLevel * VOLUME_WIDTH, 0, (volumeLevel + 1) * VOLUME_WIDTH, it.height)
                    dst.set(width - VOLUME_RIGHT, VOLUME_TOP, width - VOLUME_RIGHT + VOLUME_WIDTH, VOLUME_TOP + it.height)
                    canvas.drawBitmap(it, src, dst, bitmap)
                }
            }
        }

        val avatarTop = (shift + AVATAR_TOP).toFloat()
        if (photo != null) {
            canvas.drawPath(photoClip, photoPaint)
        } else {
            i?.avatar?.let { canvas.drawBitmap(it, cx - AVATAR / 2f, avatarTop, bitmap) }
        }
        canvas.drawText(shownCaller, cx, (shift + CALLER_BASELINE).toFloat(), callerText)
        if (shownLine2.isNotEmpty()) canvas.drawText(shownLine2, cx, (shift + LINE2_BASELINE).toFloat(), greyCentred)

        // The bottom bar: one cell per button, the touched one blue (incomingcall_button_l~focus.png).
        val barTop = height - BAR
        canvas.drawRect(0f, barTop.toFloat(), w, height.toFloat(), barPaint)
        val n = s.buttons.size
        for ((k, b) in s.buttons.withIndex()) {
            val left = width * k / n
            val right = width * (k + 1) / n
            if (b.red && b.slot == Slot.MUTE) canvas.drawRect(left.toFloat(), barTop.toFloat(), right.toFloat(), height.toFloat(), mutePaint)
            if (k == pressedCell && b.enabled) canvas.drawRect(left.toFloat(), barTop.toFloat(), right.toFloat(), height.toFloat(), focusPaint)
            if (k > 0) {
                canvas.drawRect((left - 1).toFloat(), (barTop + 1).toFloat(), left.toFloat(), height.toFloat(), dark)
                canvas.drawRect(left.toFloat(), (barTop + 1).toFloat(), (left + 1).toFloat(), height.toFloat(), light)
            }
            val mid = (left + right) / 2f
            i?.buttons?.get(b.action)?.let { canvas.drawBitmap(it, mid - ICON_W / 2f, (barTop + ICON_TOP).toFloat(), if (b.enabled) bitmap else iconOff) }
            shownLabels.getOrNull(k)?.let { canvas.drawText(it, mid, (barTop + LABEL_BASELINE).toFloat(), if (b.enabled) label else labelOff) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) post { press(-1) }
        return gestures.onTouchEvent(event)
    }
}
