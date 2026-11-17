package dev.bold7.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import dev.bold7.design.DialogColors
import dev.bold7.design.Typography

/**
 * OS 7's date and time boxes (the Calendar's, sim-234, sim-235, sim-248; Tasks', sim-274, sim-275;
 * C): border_popup.png's box, centred (D on the square), a strip (border_button.png) of [Spin]'s
 * columns, each after its separator ("17 : 00", sim-274), the focused one open as a column of three
 * rows of 57 px (border_inplace.png, its value white on border_spinboxbutton~focus.png, the
 * neighbours #CCCCCC), OK and Cancel side by side. The measures of the Clock's Set Alarm (sim-156),
 * the same box; the columns as wide as their longest text and 24 px (D). It renders the [Spin] and
 * decides nothing. Touch (D): a column is focused by a tap, its value above or below steps it.
 * The Clock's Set Alarm and Set Timer are this box too; [hideStatusBar] keeps a bar-less screen
 * bar-less under it.
 */
class SpinDialog<T>(context: Context, start: Spin<T>, private val hideStatusBar: Boolean = false, private val onSave: (T) -> Unit) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
    private val view = SpinView(context, start)

    init {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
        setContentView(view)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            fitBars() // under the banner, as an activity (Bars.kt)
        }
        view.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) view.touched(e.x, e.y)?.let(::apply); true }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return
        // A screen without a status bar (the Clock, sim-156) keeps none under the box: the dialog's window would bring it back and push the screen down.
        if (hideStatusBar) window?.insetsController?.hide(WindowInsets.Type.statusBars())
        view.requestFocus()
    }

    private fun apply(r: SpinResult<T>) = when (r) {
        is SpinResult.Show -> view.spin = r.spin
        is SpinResult.Save -> {
            dismiss()
            onSave(r.value)
        }
        SpinResult.Cancel -> cancel()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val key = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> SpinKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpinKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> SpinKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpinKey.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER -> SpinKey.CLICK
            KeyEvent.KEYCODE_ENTER -> SpinKey.ENTER
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> SpinKey.BACK
            else -> return super.dispatchKeyEvent(e)
        }
        apply(view.spin.key(key))
        return true
    }
}

private class SpinView<T>(context: Context, start: Spin<T>) : View(context) {
    companion object {
        // Set Alarm's measures (sim-156, C), the same box in sim-234.
        const val WIDTH = 387
        const val HEIGHT = 258
        const val ROW_TOP = 76
        const val ROW_HEIGHT = 49
        const val ROW = 57
        const val BASELINE = 34
        const val BUTTON_TOP = 190
        const val BUTTON_HEIGHT = 49
        const val OK_LEFT = 17
        const val CANCEL_LEFT = 202
        const val BUTTON_WIDTH = 168
        const val PAD = 24
    }

    var spin: Spin<T> = start
        set(value) {
            field = value
            invalidate()
        }

    private val popup = themeImage(context, "ui_popup")
    private val button = themeImage(context, "ui_button")
    private val buttonFocus = themeImage(context, "ui_button_focus")
    // The focused column: sim-156's blue, bright at the focused row and dark at both ends (SpinLayout.STOPS), one shader for its 170 px.
    private val columnHeight = SpinLayout.ABOVE + ROW_HEIGHT + SpinLayout.BELOW
    private val columnPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, columnHeight.toFloat(), SpinLayout.STOPS.map { it.second }.toIntArray(),
            SpinLayout.STOPS.map { it.first / columnHeight.toFloat() }.toFloatArray(), Shader.TileMode.CLAMP,
        )
    }
    private val chosen = themeImage(context, "ui_choice_chosen")
    private val black = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val white = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val grey = textPaint(Typography.TEXT, Typography.REGULAR, SpinLayout.NEIGHBOUR_TEXT, align = Paint.Align.CENTER)
    private val ok = context.getString(R.string.ui_ok)
    private val cancelLabel = context.getString(R.string.ui_cancel)

    private val boxLeft get() = (width - WIDTH) / 2
    private val boxTop get() = (height - HEIGHT) / 2

    /** Each column's left and right in the box: as wide as its widest text near the value, after its separator, centred together with the strip's tail. */
    private fun columns(): List<Pair<Int, Int>> {
        val s = spin
        val widths = s.columns.map { c -> listOf(-1, 0, 1).maxOf { black.measureText(c.label(c.turn(s.value, it))) }.toInt() + PAD }
        val gaps = s.columns.map { black.measureText(it.before).toInt() }
        return SpinLayout.columns(WIDTH, widths, gaps)
    }

    override fun onDraw(canvas: Canvas) {
        val l = boxLeft
        val t = boxTop
        val s = spin
        popup.setBounds(l - 4, t - 2, l + WIDTH + 4, t + HEIGHT + 6)
        popup.draw(canvas)
        val rowTop = t + ROW_TOP
        val cols = columns()
        button.setBounds(l + cols.first().first - 3, rowTop - 3, l + SpinLayout.stripEnd(cols) + 3, rowTop + ROW_HEIGHT + 3)
        button.draw(canvas)
        val baseline = (rowTop + BASELINE).toFloat()
        for ((i, c) in s.columns.withIndex()) {
            val (a, b) = cols[i]
            val x = l + (a + b) / 2f
            if (c.before.isNotEmpty()) canvas.drawText(c.before, l + a - black.measureText(c.before) / 2, baseline, black)
            if (i != s.focus) {
                canvas.drawText(c.label(s.value), x, baseline, black)
                continue
            }
            canvas.save()
            canvas.translate(0f, (rowTop - SpinLayout.ABOVE).toFloat())
            canvas.drawRect((l + a).toFloat(), 0f, (l + b).toFloat(), columnHeight.toFloat(), columnPaint)
            canvas.restore()
            val (cl, cr) = SpinLayout.cell(cols, i)
            canvas.save()
            canvas.clipRect(l + a, rowTop - SpinLayout.CELL_MARGIN, l + SpinLayout.pillEnd(cols, i), rowTop + ROW_HEIGHT + SpinLayout.CELL_MARGIN)
            // Its rows the strip's (border_button.png, the same margin).
            chosen.setBounds(l + cl, rowTop - SpinLayout.CELL_MARGIN, l + cr, rowTop + ROW_HEIGHT + SpinLayout.CELL_MARGIN)
            chosen.draw(canvas)
            canvas.restore()
            canvas.drawText(c.label(s.value), x, baseline, white)
            // A list that stops (Set Alarm's Off, On, Weekdays, sim-158) has nothing past its end.
            val up = c.turn(s.value, -1)
            val down = c.turn(s.value, 1)
            if (up != s.value) canvas.drawText(c.label(up), x, baseline - ROW, grey)
            if (down != s.value) canvas.drawText(c.label(down), x, baseline + ROW, grey)
        }
        for ((i, bl) in listOf(OK_LEFT, CANCEL_LEFT).withIndex()) {
            val lit = s.focus == s.ok + i
            val bx = l + bl
            val by = t + BUTTON_TOP
            (if (lit) buttonFocus else button).apply { setBounds(bx - 3, by - 3, bx + BUTTON_WIDTH + 3, by + BUTTON_HEIGHT + 3) }.draw(canvas)
            canvas.drawText(black.fit(if (i == 0) ok else cancelLabel, TextRoom.inside(BUTTON_WIDTH)), bx + BUTTON_WIDTH / 2f, (by + BASELINE).toFloat(), if (lit) white else black)
        }
    }

    /** What a tap at ([x], [y]) does, or null. */
    fun touched(x: Float, y: Float): SpinResult<T>? {
        val bx = x - boxLeft
        val by = y - boxTop
        val s = spin
        if (by >= BUTTON_TOP && by < BUTTON_TOP + BUTTON_HEIGHT) {
            if (bx >= OK_LEFT && bx < OK_LEFT + BUTTON_WIDTH) return SpinResult.Save(s.value)
            if (bx >= CANCEL_LEFT && bx < CANCEL_LEFT + BUTTON_WIDTH) return SpinResult.Cancel
            return null
        }
        val f = columns().indexOfFirst { (a, b) -> bx >= a && bx < b }.takeIf { it >= 0 } ?: return null
        val row = Math.floorDiv((by - ROW_TOP).toInt(), ROW)
        if (row !in -1..1) return null
        val focused = s.copy(focus = f)
        return SpinResult.Show(if (f == s.focus && row != 0) focused.turn(row) else focused)
    }
}
