package dev.bold7.calculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Typography
import dev.bold7.ui.fitSize
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

/**
 * The calculator's page as sim-249 shows it (C): no title bar, #262626, the display's green pill
 * (calculator_display_*.png, A) in x 10 to 630, y 20 to 110 (at 720 px: 11 to 709, y 22, 101 high) with the number in black bold at its
 * right ("0."), and ten columns of keys on four rows from y 135 (50 x 75 px, pitch 62 x 85, the
 * first column at x 16, all at x 1,125 on the Q25); calculator_key_*.png (A: the digits
 * lighter, the focused one blue, the pressed one darker blue), labels white, orange for the clears,
 * blue for the memory. The pending operation and an "M" while the memory holds something are
 * small over the number (D: the capture shows neither). The trackpad's focus and the presses are
 * its owner's ([CalcActivity]); a tap on a key focuses and presses it.
 */
class CalcView(context: Context, private val onKey: (Key) -> Unit) : View(context) {
    companion object {
        // sim-249 at the Q25's 720 px (x 1,125 on the 9900's 640, see Keypad): display 10..630 -> 11..709.
        const val DISPLAY_MARGIN = Keypad.DISPLAY_MARGIN
        const val DISPLAY_TOP = 22
        const val DISPLAY_HEIGHT = 101
        const val KEYS_TOP = 146
        /** The number's size, by eye (D). */
        const val NUMBER_SIZE = 56
        const val TEXT_RIGHT = Keypad.TEXT_RIGHT
        const val FLASH_MS = 120L
        /** The "M" of the memory takes this much of the pill's left. */
        const val MEMORY_ROOM = 40
        // The LCD's ink (black, sim-249, C), its note by eye (D), the page's ground (C).
        val SCREEN_TEXT = 0xFF000000.toInt()
        val SCREEN_NOTE = 0xFF4F6354.toInt()
        val KEY_TEXT = 0xFFFFFFFF.toInt()
        // The labels' own colours, the flat pixels of sim-249 (C: #FFB300, #00AAFF; its anti-aliased strokes average to #06A1EE).
        val KEY_ORANGE = 0xFFFFB300.toInt()
        val KEY_BLUE = 0xFF00AAFF.toInt()
        val BACKGROUND = 0xFF262626.toInt()
        /** The theme images it draws, by name, preloaded by [CalcApp]. */
        val IMAGES = listOf(
            "calc_display_left", "calc_display_middle", "calc_display_right",
            "calc_key", "calc_key_digit", "calc_key_focus", "calc_key_active",
        )
    }

    private val left = themeImage(context, "calc_display_left")
    private val middle = themeImage(context, "calc_display_middle")
    private val right = themeImage(context, "calc_display_right")
    private val key = themeImage(context, "calc_key")
    private val digit = themeImage(context, "calc_key_digit")
    private val focused = themeImage(context, "calc_key_focus")
    private val active = themeImage(context, "calc_key_active")
    private val number = textPaint(NUMBER_SIZE, Typography.BOLD, SCREEN_TEXT, align = Paint.Align.RIGHT)
    private val note = textPaint(Typography.SUBTITLE, Typography.REGULAR, SCREEN_NOTE, align = Paint.Align.RIGHT)
    private val memory = textPaint(Typography.SUBTITLE, Typography.BOLD, SCREEN_NOTE)
    /** One paint per label: the sim's size, shrunk if the label would still not fit its key (never overflows). */
    private val labels = Keypad.cells.associate { c ->
        val color = when (c.ink) { Ink.WHITE -> KEY_TEXT; Ink.ORANGE -> KEY_ORANGE; Ink.BLUE -> KEY_BLUE }
        var size = Keypad.labelSize(c.label)
        var p = textPaint(size, Typography.REGULAR, color, align = Paint.Align.CENTER)
        while (size > 8 && p.measureText(c.label) > Keypad.LABEL_ROOM) p = textPaint(--size, Typography.REGULAR, color, align = Paint.Align.CENTER)
        c.label to p
    }
    private var calc = Calc()
    private var focus = Keypad.NO_FOCUS
    private var pressed = -1

    init {
        setBackgroundColor(BACKGROUND)
    }

    fun bind(calc: Calc, focus: Int) {
        if (calc == this.calc && focus == this.focus) return
        this.calc = calc
        this.focus = focus
        contentDescription = "${calc.expression} ${calc.shown}"
        invalidate()
    }

    private val base = HashMap<Paint, Float>()

    /** [p] at the size where [text] takes no more than [room] px (its own size when it fits). */
    private fun shrunk(p: Paint, text: String, room: Float): Paint {
        val size = base.getOrPut(p) { p.textSize }
        p.textSize = size
        p.textSize = fitSize(size, p.measureText(text), room)
        return p
    }

    /** Draws a key as pressed for a moment (the keyboard's presses, D). */
    fun flash(index: Int) {
        pressed = index
        invalidate()
        postDelayed({ if (pressed == index) { pressed = -1; invalidate() } }, FLASH_MS)
    }

    /** The ten columns, centred. */
    private fun originX() = (width - Keypad.COLUMNS * Keypad.CELL_WIDTH) / 2

    private fun bounds(c: Cell): IntArray {
        val x = originX() + c.col * Keypad.CELL_WIDTH
        val y = KEYS_TOP + c.row * Keypad.CELL_HEIGHT
        return intArrayOf(x, y, x + Keypad.CELL_WIDTH, y + Keypad.CELL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        val l = DISPLAY_MARGIN
        val r = width - DISPLAY_MARGIN
        left.setBounds(l, DISPLAY_TOP, l + left.intrinsicWidth, DISPLAY_TOP + DISPLAY_HEIGHT)
        right.setBounds(r - right.intrinsicWidth, DISPLAY_TOP, r, DISPLAY_TOP + DISPLAY_HEIGHT)
        middle.setBounds(l + left.intrinsicWidth, DISPLAY_TOP, r - right.intrinsicWidth, DISPLAY_TOP + DISPLAY_HEIGHT)
        middle.draw(canvas)
        left.draw(canvas)
        right.draw(canvas)
        val edge = (r - TEXT_RIGHT).toFloat()
        // Never wider than the pill: a text that would not fit is drawn smaller, not cut (the number's end is what matters).
        val room = Keypad.displayRoom(width, if (calc.hasMemory) MEMORY_ROOM else 0)
        canvas.drawText(calc.expression, edge, (DISPLAY_TOP + 33).toFloat(), shrunk(note, calc.expression, room))
        canvas.drawText(calc.shown, edge, (DISPLAY_TOP + 79).toFloat(), shrunk(number, calc.shown, room))
        if (calc.hasMemory) canvas.drawText("M", (l + 20).toFloat(), (DISPLAY_TOP + 33).toFloat(), memory)
        for ((i, c) in Keypad.cells.withIndex()) {
            val b = bounds(c)
            val image = when {
                i == pressed -> active
                i == focus -> focused
                c.look == Look.DIGIT -> digit
                else -> key
            }
            image.setBounds(b[0], b[1], b[2], b[3])
            image.draw(canvas)
            val p = labels.getValue(c.label)
            canvas.drawText(c.label, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f + p.textSize * 0.36f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val at = Keypad.cells.indexOfFirst { c -> bounds(c).let { e.x >= it[0] && e.x < it[2] && e.y >= it[1] && e.y < it[3] } }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> if (at >= 0) { pressed = at; focus = at; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                invalidate()
                if (p >= 0 && p == at) { performClick(); onKey(Keypad.cells[p].key) }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    override fun performClick() = super.performClick()
}
