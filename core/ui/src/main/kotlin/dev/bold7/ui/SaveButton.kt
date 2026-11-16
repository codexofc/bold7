package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View

/**
 * A form's save button (sim-184, sim-228, sim-268, C): the app's [glyph] (save.png of the theme, A,
 * which each app copies) centred in border_button, border_button~focus under the trackpad's focus.
 * Used by Contacts and Tasks.
 *
 * Simplification: the Calendar's SaveBar and Messages' compose screen still draw their own.
 */
class SaveButton(context: Context, private val glyph: Drawable, label: String) : View(context) {
    private val normal = themeImage(context, "ui_button")
    private val lit = themeImage(context, "ui_button_focus")

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        contentDescription = label
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        (if (isFocused) lit else normal).apply { setBounds(-3, -3, width + 3, height + 3) }.draw(canvas)
        val x = (width - glyph.intrinsicWidth) / 2
        val y = (height - glyph.intrinsicHeight) / 2
        glyph.setBounds(x, y, x + glyph.intrinsicWidth, y + glyph.intrinsicHeight)
        glyph.draw(canvas)
    }
}
