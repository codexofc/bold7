package dev.bold7.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.widget.TextView
import dev.bold7.design.Focus

/**
 * OS 7's text caret: a block 9 px wide in the focus blue, from 26 px over the text's baseline to
 * 6 under it, 32 high (sim-47, sim-191, sim-228, C: #088FF0 at its top to #1666DC, the same in the
 * three, at a field's start and after a letter).
 *
 * Android stretches a caret over its line, from the baseline plus the font's ascent: [ascent] (the
 * paint's, a positive count) places the block in it, whatever the line's height.
 */
class Caret(private val ascent: Int) : Drawable() {
    companion object {
        const val WIDTH = 9
        const val HEIGHT = 32
        const val ABOVE_BASELINE = 26
    }

    private val paint = Paint()
    private var top = 0

    override fun getIntrinsicWidth() = WIDTH

    override fun onBoundsChange(bounds: Rect) {
        top = bounds.top + ascent - ABOVE_BASELINE
        paint.shader = LinearGradient(0f, top.toFloat(), 0f, (top + HEIGHT).toFloat(), Focus.RowTop, Focus.RowBottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds.left.toFloat(), top.toFloat(), (bounds.left + WIDTH).toFloat(), (top + HEIGHT).toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Gives this field OS 7's caret, after its typeface and its size are set. */
fun TextView.useCaret() {
    textCursorDrawable = Caret(-paint.fontMetricsInt.ascent)
}

/**
 * A form's field, as OS 7's: the trackpad goes from field to field, up from the first line and
 * down from the last ([leavesField]); Android's movement would spend a step taking the caret to
 * the text's start or end first. Within a field's lines and sideways, Android's.
 */
object FieldMovement : ArrowKeyMovementMethod() {
    override fun up(widget: TextView, buffer: Spannable): Boolean = !leaves(widget, buffer, false) && super.up(widget, buffer)

    override fun down(widget: TextView, buffer: Spannable): Boolean = !leaves(widget, buffer, true) && super.down(widget, buffer)

    private fun leaves(widget: TextView, buffer: Spannable, down: Boolean): Boolean {
        val layout = widget.layout ?: return false
        return leavesField(layout.getLineForOffset(Selection.getSelectionEnd(buffer).coerceAtLeast(0)), layout.lineCount, down)
    }
}
