package dev.bold7.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.fitSize
import dev.bold7.ui.textPaint

/**
 * The stopwatch (sim-165, sim-169, C) and, drawn the same way, the countdown timer (D: not
 * captured): the dial face_316.png (A) with its minute hand (grey) and second hand (red), images of
 * net_rim_bb_clock_lib turned as the clock's ([handSprite]); the time on a dark pill (#1F1F1F,
 * unsourced, eyeballed) in BBAlpha Sans 33 white, the stopwatch's hundredths red (unsourced, eyeballed); under the dial the
 * last lap, "Lap n" grey, its time and the total white (sim-169); two buttons of 305x67
 * (border_clock_button and ~focus, A) with timer_start or timer_stop on the left, timer_lap or
 * timer_reset on the right (sim-165, sim-168). On the square, the block is centred (D).
 */
class WatchView(context: Context) : View(context) {
    class Images(val face: Bitmap, val minutes: List<Bitmap>, val seconds: List<Bitmap>, val button: Drawable, val buttonFocus: Drawable, val start: Bitmap, val stop: Bitmap, val lap: Bitmap, val reset: Bitmap)

    companion object {
        const val DIAL = 316
        const val PILL_DY = 50 // the pill's centre under the dial's (C)
        const val PILL_WIDTH = 202
        const val PILL_HEIGHT = 37
        const val TEXT = 33
        const val LAP_BASELINE = 390 - 43 // from the dial's top (C)
        const val BUTTONS_TOP = 403 - 43
        const val BUTTON_WIDTH = 305
        const val BUTTON_HEIGHT = 67
        const val BUTTON_GAP = 11
        const val BLOCK = 469 - 43
        const val RED = 0xFFE00000.toInt()
        const val GREY = 0xFF999999.toInt()
    }

    var images: Images? = null
        set(value) {
            field = value
            invalidate()
        }

    private var text = ""
    private var redFrom = -1
    private var minutePos = 0
    private var secondPos = 0
    private var lap: Triple<String, String, String>? = null
    private var running = false
    private var timer = false
    var focus = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val white = textPaint(TEXT, Typography.REGULAR, 0xFFFFFFFF.toInt())
    private val red = textPaint(TEXT, Typography.REGULAR, RED)
    private val lapGrey = textPaint(Typography.TEXT, Typography.REGULAR, GREY)
    private val lapWhite = textPaint(Typography.TEXT, Typography.REGULAR, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER)
    private val pill = fillPaint(0xFF1F1F1F.toInt())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()

    /** The stopwatch at [elapsed] ms with its laps. */
    fun bindStopwatch(elapsed: Long, laps: List<Long>, lapTimes: List<Long>, running: Boolean) {
        text = watchText(elapsed)
        redFrom = text.length - 2
        minutePos = (elapsed / 60_000 % 60).toInt()
        secondPos = (elapsed / 1000 % 60).toInt()
        lap = laps.firstOrNull()?.let { Triple("Lap ${laps.size}", lapText(lapTimes.first()), lapText(it)) }
        this.running = running
        timer = false
        invalidate()
    }

    /** The timer with [left] ms to go. */
    fun bindTimer(left: Long, running: Boolean) {
        text = timerText(left)
        redFrom = -1
        val s = (left + 999) / 1000
        minutePos = (s / 60 % 60).toInt()
        secondPos = (s % 60).toInt()
        lap = null
        this.running = running
        timer = true
        invalidate()
    }

    private val blockTop get() = (height - BLOCK) / 2

    /** The button under ([x], [y]): 0, 1 or null. */
    fun buttonAt(x: Float, y: Float): Int? {
        val by = blockTop + BUTTONS_TOP
        if (y < by || y >= by + BUTTON_HEIGHT) return null
        val left = (width - 2 * BUTTON_WIDTH - BUTTON_GAP) / 2
        return when {
            x >= left && x < left + BUTTON_WIDTH -> 0
            x >= left + BUTTON_WIDTH + BUTTON_GAP && x < left + 2 * BUTTON_WIDTH + BUTTON_GAP -> 1
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val img = images ?: return
        val t = blockTop.toFloat()
        val dx = (width - DIAL) / 2f
        canvas.drawBitmap(img.face, dx, t, bitmapPaint)
        val cx = width / 2f
        val cy = t + DIAL / 2f
        hand(canvas, img.minutes, minutePos, cx, cy)
        hand(canvas, img.seconds, secondPos, cx, cy)
        rect.set(cx - PILL_WIDTH / 2f, cy + PILL_DY - PILL_HEIGHT / 2f, cx + PILL_WIDTH / 2f, cy + PILL_DY + PILL_HEIGHT / 2f)
        canvas.drawRoundRect(rect, PILL_HEIGHT / 2f, PILL_HEIGHT / 2f, pill)
        val base = cy + PILL_DY + TEXT * 0.36f
        // The Q25's font is wider than the 9900's: the size follows the text so that it stays in the pill, 8 px clear (sim-165).
        white.textSize = TEXT.toFloat()
        val size = fitSize(TEXT.toFloat(), white.measureText(text), PILL_WIDTH - 16f)
        white.textSize = size
        red.textSize = size
        val whole = white.measureText(text)
        var x = cx - whole / 2
        if (redFrom > 0) {
            canvas.drawText(text, 0, redFrom, x, base, white)
            x += white.measureText(text, 0, redFrom)
            canvas.drawText(text, redFrom, text.length, x, base, red)
        } else {
            canvas.drawText(text, x, base, white)
        }
        lap?.let { (name, own, total) ->
            val y = t + LAP_BASELINE
            canvas.drawText(lapGrey.fit(name, cx - 5 - lapWhite.measureText(own) / 2 - 12 - (76f + (width - 640) / 2f)), 76f + (width - 640) / 2f, y, lapGrey)
            canvas.drawText(own, cx - 5, y, lapWhite)
            canvas.drawText(total, cx + 198, y, lapWhite)
        }
        val left = (width - 2 * BUTTON_WIDTH - BUTTON_GAP) / 2
        val by = (t + BUTTONS_TOP).toInt()
        for (i in 0..1) {
            val bx = left + i * (BUTTON_WIDTH + BUTTON_GAP)
            (if (focus == i) img.buttonFocus else img.button).apply { setBounds(bx, by, bx + BUTTON_WIDTH, by + BUTTON_HEIGHT) }.draw(canvas)
            val icon = if (i == 0) (if (running) img.stop else img.start) else (if (running && !timer) img.lap else img.reset)
            canvas.drawBitmap(icon, bx + (BUTTON_WIDTH - icon.width) / 2f, by + (BUTTON_HEIGHT - icon.height) / 2f, bitmapPaint)
        }
    }

    private fun hand(canvas: Canvas, images: List<Bitmap>, position: Int, cx: Float, cy: Float) =
        drawHand(canvas, images, position, cx, cy, matrix, bitmapPaint)
}
