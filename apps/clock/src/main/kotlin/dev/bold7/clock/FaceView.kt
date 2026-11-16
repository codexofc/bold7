package dev.bold7.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import dev.bold7.design.Typography
import dev.bold7.ui.fit
import dev.bold7.ui.fitSize
import dev.bold7.ui.textPaint

/**
 * The Clock's analog face (sim-153, C): face_370.png (A) with its two pills, "AM"/"PM" in the left
 * one and the day ("Fri 13") in the right one, BBAlpha Sans 27 white (C, measured), the hands of
 * [handSprite] over it; under it, 23 px lower, the alarm bar: border_clock_button.png stretched to
 * 305x67 (A, C), the alarm's indicator and its time, BBAlpha Sans 33 white (C), or the indicator
 * that adds one. On the square, the face and the bar are centred as a block (D).
 *
 * The images ([Images]) are decoded off the main thread by its owner; it draws nothing before.
 */
class FaceView(context: Context) : View(context) {
    /** One size of the LCD digital face: the ten digits and the colon. */
    class Lcd(val digits: List<Bitmap>, val colon: Bitmap)

    class Images(
        val face: Bitmap, val hours: List<Bitmap>, val minutes: List<Bitmap>, val bar: Drawable, val barFocus: Drawable,
        val alarmOn: Bitmap, val alarmAdd: Bitmap, val snoozed: Bitmap, val lcd: List<Lcd>,
    )

    companion object {
        const val FACE = 370
        const val GAP = 23
        const val BAR_WIDTH = 305
        const val BAR_HEIGHT = 67
        const val PILL_LEFT_X = 92 // the left pill's centre, from the face's left (C)
        const val PILL_RIGHT_X = 278
        const val PILL_BASELINE = 197 // from the face's top (C)
        const val BAR_TEXT = 33
        const val BAR_GAP = 20 // between the indicator and the time (C)
        const val PILL_ROOM = 100f // the pill's room for its text: the day, "Fri 16", in the face (C)
        const val LCD_MARGIN = 16
            }

    var images: Images? = null
        set(value) {
            field = value
            invalidate()
        }

    private var hour = 0
    private var minute = 0
    private var day = ""
    private var alarm: String? = null
    private var snoozed = false
    var barLit = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Clock Face (Clock Options, sim-330): the analogue dial, or one of the three faces drawn as text (sim-331 to sim-333). */
    var kind = ClockFace.ANALOGUE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Bedside Mode (sim-323, C): everything is drawn at 80 % of its brightness, the blacks as they are. */
    var bedside = false
        set(value) {
            if (field != value) {
                field = value
                setLayerType(LAYER_TYPE_HARDWARE, if (value) Paint().apply { colorFilter = LightingColorFilter(0xFFCCCCCC.toInt(), 0) } else null)
            }
        }

    /** Whether the alarm bar is drawn (the ring screen shows the face and the bar too, sim-161). */
    var showBar = true

    private val pill = textPaint(Typography.TEXT, Typography.REGULAR, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER)
    private val barText = textPaint(BAR_TEXT, Typography.REGULAR, 0xFFFFFFFF.toInt())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val dateText = textPaint(BigFace.DATE_SIZE, Typography.REGULAR, 0xFFA0A0A0.toInt(), align = Paint.Align.RIGHT)
    private val bigPaint = textPaint(100, Typography.BOLD, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER)
    private val cardPaint = Paint().apply { color = 0xFF181818.toInt() }
    private val cardLine = Paint().apply { color = 0xFF000000.toInt() }
    private val ghost = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = LightingColorFilter(0, 0x111111) }
    private val bounds = Rect()
    private val rect = RectF()
    private var date = ""

    /** The time shown, the day's pill, the alarm's time when one is set ([alarm]), and whether it is snoozed. */
    fun bind(hour: Int, minute: Int, day: String, alarm: String?, snoozed: Boolean, date: String = "") {
        if (hour == this.hour && minute == this.minute && day == this.day && alarm == this.alarm && snoozed == this.snoozed && date == this.date) return
        this.date = date
        this.hour = hour
        this.minute = minute
        this.day = day
        this.alarm = alarm
        this.snoozed = snoozed
        invalidate()
    }

    private val faceLeft get() = (width - FACE) / 2
    private val faceTop get() = (height - FACE - if (showBar) GAP + BAR_HEIGHT else 0) / 2

    /** The bar's bounds, for touches: left, top, right, bottom. */
    fun barContains(x: Float, y: Float): Boolean {
        val left = (width - BAR_WIDTH) / 2
        val top = faceTop + FACE + GAP
        return showBar && x >= left && x < left + BAR_WIDTH && y >= top && y < top + BAR_HEIGHT
    }

    override fun onDraw(canvas: Canvas) {
        val img = images ?: return
        val fx = faceLeft.toFloat()
        val fy = faceTop.toFloat()
        if (kind != ClockFace.ANALOGUE) {
            canvas.drawText(dateText.fit(date, width - 2f * BigFace.DATE_RIGHT), width - BigFace.DATE_RIGHT.toFloat(), BigFace.DATE_BASELINE.toFloat(), dateText)
            val cy = fy + FACE / 2f
            when (kind) {
                ClockFace.DIGITAL -> digital(canvas, cy)
                ClockFace.FLIP -> flip(canvas, cy)
                else -> lcd(canvas, img, cy)
            }
        } else {
            canvas.drawBitmap(img.face, fx, fy, bitmapPaint)
            canvas.drawText(amPm(hour), fx + PILL_LEFT_X, fy + PILL_BASELINE, pill)
            canvas.drawText(pill.fit(day, PILL_ROOM), fx + PILL_RIGHT_X, fy + PILL_BASELINE, pill)
            hand(canvas, img.hours, hourPosition(hour, minute), fx + FACE / 2f, fy + FACE / 2f)
            hand(canvas, img.minutes, minute, fx + FACE / 2f, fy + FACE / 2f)
        }
        if (!showBar) return
        val left = (width - BAR_WIDTH) / 2
        val top = faceTop + FACE + GAP
        (if (barLit) img.barFocus else img.bar).apply { setBounds(left, top, left + BAR_WIDTH, top + BAR_HEIGHT) }.draw(canvas)
        val time = alarm
        val icon = if (time == null) img.alarmAdd else if (snoozed) img.snoozed else img.alarmOn
        val textWidth = if (time == null) 0f else BAR_GAP + barText.measureText(time)
        val x = left + (BAR_WIDTH - icon.width - textWidth) / 2
        val cy = top + BAR_HEIGHT / 2f
        canvas.drawBitmap(icon, x, cy - icon.height / 2f, bitmapPaint)
        if (time != null) canvas.drawText(barText.fit(time, BAR_WIDTH - 2f * BAR_GAP), x + icon.width + BAR_GAP, cy + BAR_TEXT * 0.36f, barText)
    }

    /** The LCD face: HH:MM, the largest size that fits, centred on [cy], an unlit "8" #111111 behind each digit (sim-333). */
    private fun lcd(canvas: Canvas, img: Images, cy: Float) {
        val set = img.lcd[lcdFit(img.lcd.map { it.digits[0].width to it.colon.width }, width - 2 * LCD_MARGIN)]
        val dw = set.digits[0].width
        val dh = set.digits[0].height
        var x = (width - (4 * dw + set.colon.width)) / 2f
        val top = cy - dh / 2f
        for (part in listOf(hour / 10, hour % 10, -1, minute / 10, minute % 10)) {
            if (part < 0) canvas.drawBitmap(set.colon, x, top, bitmapPaint) else {
                canvas.drawBitmap(set.digits[8], x, top, ghost)
                canvas.drawBitmap(set.digits[part], x, top, bitmapPaint)
            }
            x += if (part < 0) set.colon.width else dw
        }
    }

    /** Digital (sim-331): HH:MM in bold white, 84 % of the width, centred on [cy]. */
    private fun digital(canvas: Canvas, cy: Float) =
        bigText(canvas, "%02d:%02d".format(hour, minute), width / 2f, cy, BigFace.DIGITAL_CAP * width, BigFace.DIGITAL_ROOM * width)

    /** Flip Clock (sim-332): the hour and the minute on two #181818 cards, a black line across each at its middle. */
    private fun flip(canvas: Canvas, cy: Float) {
        val side = BigFace.cardSide(width).toFloat()
        val radius = BigFace.CARD_RADIUS * side
        for (i in 0..1) {
            val left = BigFace.CARD_MARGIN + i * (side + BigFace.CARD_GAP)
            rect.set(left, cy - side / 2, left + side, cy + side / 2)
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
            canvas.drawRect(left, cy - 0.5f, left + side, cy + 0.5f, cardLine)
            bigText(canvas, "%02d".format(if (i == 0) hour else minute), left + side / 2, cy, BigFace.FLIP_CAP * side, side * 0.87f)
        }
    }

    /** [text] in bold white, its digits [cap] px high (or less, so that it stays in [room]), centred on ([cx], [cy]). */
    private fun bigText(canvas: Canvas, text: String, cx: Float, cy: Float, cap: Float, room: Float) {
        bigPaint.textSize = 100f
        bigPaint.getTextBounds("0", 0, 1, bounds)
        var size = 100f * cap / bounds.height()
        bigPaint.textSize = size
        size = fitSize(size, bigPaint.measureText(text), room)
        bigPaint.textSize = size
        bigPaint.getTextBounds("0", 0, 1, bounds)
        canvas.drawText(text, cx, cy + bounds.height() / 2f, bigPaint)
    }

    private fun hand(canvas: Canvas, images: List<Bitmap>, position: Int, cx: Float, cy: Float) =
        drawHand(canvas, images, position, cx, cy, matrix, bitmapPaint)
}

/**
 * One hand: its sprite's image with its base's centre (half the 0 degree image's width in from
 * the left and the bottom) on the dial's centre ([cx], [cy]), mirrored then turned about that point.
 */
internal fun drawHand(canvas: Canvas, images: List<Bitmap>, position: Int, cx: Float, cy: Float, matrix: Matrix, paint: Paint) {
    val s = handSprite(position)
    val b = images[s.image]
    val base = images[0].width / 2f
    matrix.reset()
    matrix.setTranslate(-base, -(b.height - base))
    if (s.mirrored) matrix.postConcat(MIRROR)
    matrix.postRotate(90f * s.quarter)
    matrix.postTranslate(cx, cy)
    canvas.drawBitmap(b, matrix, paint)
}

/** The reflection across the 45 degree diagonal of a screen (y down): (x, y) to (-y, -x). */
private val MIRROR = Matrix().apply { setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)) }
