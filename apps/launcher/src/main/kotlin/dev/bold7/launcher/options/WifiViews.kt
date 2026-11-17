package dev.bold7.launcher.options

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.launcher.R
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeBitmap

/**
 * A row of Wi-Fi Network or Bluetooth: a list row of Options (ListItemView's
 * measures, sim-57, C) with images at its right that ListItemView does not draw: a network's lock
 * (wi-fi_lock_icon.png, A) and signal (net_rim_WlanSignalLevel-icons58x27.png of _bs1, A), or a
 * device's icon at the left (net_rim_bluetooth_device-icons42x42.png, A). Their places are D.
 *
 * core/ui's ListItemView has no right-hand image; this row stays here until a second app needs one.
 */
internal class IconRowView(context: Context) : View(context) {
    private val divider = fillPaint(ListColors.Divider)
    private val band = focusPaint(ListDimens.ROW)
    private val title = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val subtitle = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val litTitle = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val litSubtitle = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText)
    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private var text = ""
    private var note = ""
    private var lit = false
    private var left: Frame? = null
    private var right: List<Frame> = emptyList()

    /** A frame [index] of a strip of [size]-px-wide images. */
    data class Frame(val strip: Bitmap, val index: Int, val size: Int)

    fun bind(text: String, note: String, lit: Boolean, left: Frame? = null, right: List<Frame> = emptyList()) {
        if (text == this.text && note == this.note && lit == this.lit && left == this.left && right == this.right) return
        this.text = text
        this.note = note
        this.lit = lit
        this.left = left
        this.right = right
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), ListDimens.ROW)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = ListDimens.ROW.toFloat()
        if (lit) canvas.drawRect(0f, 0f, w, h, band) else canvas.drawRect(0f, h - ListDimens.DIVIDER, w, h, divider)
        // Right to left from 13 px off the edge (D, as a choice field's arrow), 8 px between two images.
        var x = width - EDGE
        for (f in right) {
            x -= f.size
            draw(canvas, f, x)
            x -= GAP
        }
        val l = left
        val textX = if (l == null) ListDimens.TEXT_X else ListDimens.ICON_TEXT_X
        if (l != null) draw(canvas, l, ListDimens.ICON_CENTRE_X - l.size / 2)
        val room = (x + GAP - textX - ListDimens.TEXT_X).coerceAtLeast(0).toFloat()
        val t = if (lit) litTitle else title
        if (note.isEmpty()) {
            canvas.drawText(t.fit(text, room), textX.toFloat(), ONE_LINE_BASELINE, t)
        } else {
            canvas.drawText(t.fit(text, room), textX.toFloat(), ListDimens.TITLE_BASELINE.toFloat(), t)
            val s = if (lit) litSubtitle else subtitle
            canvas.drawText(s.fit(note, room), textX.toFloat(), ListDimens.SUBTITLE_BASELINE.toFloat(), s)
        }
    }

    private fun draw(canvas: Canvas, f: Frame, x: Int) {
        val y = (ListDimens.ROW - f.strip.height) / 2
        canvas.drawBitmap(f.strip, Rect(f.index * f.size, 0, (f.index + 1) * f.size, f.strip.height), Rect(x, y, x + f.size, y + f.strip.height), bitmap)
    }

    companion object {
        const val EDGE = 13
        const val GAP = 8
        const val SIGNAL_WIDTH = 58

        /** A title alone, centred on the 71 px row (D, BBAlpha Sans 27). */
        const val ONE_LINE_BASELINE = 45f

        /** The theme's strips, decoded once (io thread). */
        fun strips(context: Context) = Strips(
            themeBitmap(context, "opt_wifi_signal"),
            themeBitmap(context, "opt_wifi_lock"),
            themeBitmap(context, "opt_bt_devices"),
        )
    }

    class Strips(val signal: Bitmap, val lock: Bitmap, val devices: Bitmap) {
        /** One to three bars: the strip's frames 2 to 4 (0 is off, 1 no signal). */
        fun bars(n: Int) = Frame(signal, n.coerceIn(1, 3) + 1, SIGNAL_WIDTH)
        val locked get() = Frame(lock, 0, lock.width)
        fun device(i: Int) = Frame(devices, i, devices.height)
    }
}
