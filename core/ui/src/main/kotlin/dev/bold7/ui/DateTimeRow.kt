package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.bold7.design.Focus
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * A form's row with a date and a time at its right (the Calendar's "Start:", sim-228; Tasks' due
 * date, sim-273, without a label; C), the focused part blue, the other black (sim-233, sim-274);
 * a click opens [SpinDialog] on it.
 */
class DateTimeRow(context: Context, private val label: String) : View(context) {
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val white = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val grey = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle)
    private val litPaint = Paint().apply { color = Focus.IconTop }
    private var date = ""
    private var time = ""

    var lit: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 0 the date, 1 the time. */
    var segment = 1
        set(value) {
            field = if (time.isEmpty()) 0 else value
            invalidate()
        }

    /** The texts shown; an empty [time] (an all-day appointment) leaves the date alone. */
    fun value(date: String, time: String) {
        this.date = date
        this.time = time
        if (time.isEmpty()) segment = 0
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ListDimens.CHECK_ROW)

    override fun onDraw(canvas: Canvas) {
        val y = ListDimens.CHECK_BASELINE.toFloat()
        val right = width - 8f
        val timeW = if (time.isEmpty()) 0f else text.measureText(" $time")
        // The label gives way to the values, which are short; cut to what they leave it.
        canvas.drawText(text.fit(label, TextRoom.dateLabel(width, timeW + text.measureText(date))), ListDimens.TEXT_X.toFloat(), y, if (isEnabled) text else grey)
        val dateX = right - timeW - text.measureText(date)
        val parts = listOf(dateX to date, (right - timeW + text.measureText(" ")) to time)
        for ((i, p) in parts.withIndex()) {
            if (p.second.isEmpty()) continue
            val on = lit && i == segment
            if (on) canvas.drawRect(p.first - 2, y - 29, p.first + text.measureText(p.second) + 2, y + 8, litPaint)
            canvas.drawText(p.second, p.first, y, if (on) white else if (isEnabled) text else grey)
        }
    }
}
