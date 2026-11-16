package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.Spinner
import dev.bold7.design.DialogColors
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * A row of OS 7's options with a choice field (Trackpad Sensitivity, sim, C): 57 px, the label in
 * BBAlpha Sans 27 at the left as a check box's, at the right the field, border_button.png (A) with
 * its value in black and arrow_choice.png (A); under the trackpad's focus ([lit]) the field turns
 * blue, border_button~focus.png, its value and arrow white, the row staying white (C). Its owner
 * opens the list ([ChoiceListView]) on the trackpad's click or a tap.
 */
class ChoiceView(context: Context) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.CHECK_ROW

        /** The label's own line of a [stacked] row, and its baseline in it (sim-170: Home Time Zone, 135 to 167, baseline 162). */
        const val STACK_LABEL = 32
        const val STACK_BASELINE = 27
        const val STACKED_HEIGHT = STACK_LABEL + HEIGHT
    }

    private val field = themeImage(context, "ui_button")
    private val fieldFocus = themeImage(context, "ui_button_focus")
    private val arrow = themeImage(context, "ui_choice_arrow")
    private val arrowFocus = themeImage(context, "ui_choice_arrow_focus")
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.CheckText)
    private val value = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val litValue = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private var label = ""
    private var shown = ""
    private var lit = false
    /**
     * The label on a line of its own above a field as wide as the row (sim-170: Home Time Zone, a
     * long value); set before the first bind. The row is [STACKED_HEIGHT] tall, its field at [fieldTop].
     */
    var stacked = false
        set(v) {
            field = v
            cut()
            invalidate()
        }

    /** The field's row's top in the view: its open list is placed from there. */
    val fieldTop: Int get() = if (stacked) STACK_LABEL else 0

    private var labelShown = ""
    private var valueShown = ""

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // Drawn on its canvas: told to a screen reader as Android's spinner, its label and value.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Spinner::class.java.name
        info.text = "$label $shown"
    }

    fun bind(label: String, value: String, lit: Boolean = false) {
        if (label === this.label && value == shown && lit == this.lit) return
        this.label = label
        this.shown = value
        this.lit = lit
        cut()
        invalidate()
    }

    // The label up to the field, the value inside it before its arrow: cut when the text or the width changes.
    private fun cut() {
        // OS 7's field takes its value's width (sim-170): wider than the usual one for a long value, never over the label.
        fieldWidth = if (label.isEmpty() || stacked) width - 2 * ListDimens.TEXT_X else TextRoom.choiceField(width, text.measureText(label), value.measureText(shown))
        labelShown = text.fit(label, if (stacked) (width - 2 * ListDimens.TEXT_X).toFloat().coerceAtLeast(0f) else TextRoom.choiceLabel(fieldLeft))
        valueShown = value.fit(shown, TextRoom.inside(ax() - fieldLeft))
    }

    private fun ax() = fieldLeft + fieldWidth - ListDimens.CHOICE_ARROW_RIGHT - ListDimens.CHOICE_ARROW_WIDTH

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut()

    /** The field's left edge, where its open list goes (ListDimens.CHOICE_LIST_X further). */
    val fieldLeft: Int get() = if (label.isEmpty() || stacked) ListDimens.TEXT_X else width - ListDimens.CHECK_RIGHT - fieldWidth

    // Without a label the field takes the row's width (a group's only button, Home Screen Preferences, sim-284, C).
    private var fieldWidth = ListDimens.CHOICE_FIELD_WIDTH

    override fun onDraw(canvas: Canvas) {
        if (stacked) canvas.drawText(labelShown, ListDimens.TEXT_X.toFloat(), STACK_BASELINE.toFloat(), text)
        else canvas.drawText(labelShown, ListDimens.TEXT_X.toFloat(), ListDimens.CHECK_BASELINE.toFloat(), text)
        canvas.save()
        canvas.translate(0f, fieldTop.toFloat())
        val left = fieldLeft
        val right = left + fieldWidth
        val top = ListDimens.CHOICE_FIELD_TOP
        val image = if (lit) fieldFocus else field
        image.setBounds(left, top, right, top + ListDimens.CHOICE_FIELD_HEIGHT)
        image.draw(canvas)
        val a = if (lit) arrowFocus else arrow
        val ax = ax()
        val ay = top + (ListDimens.CHOICE_FIELD_HEIGHT - a.intrinsicHeight) / 2
        a.setBounds(ax, ay, ax + a.intrinsicWidth, ay + a.intrinsicHeight)
        a.draw(canvas)
        canvas.drawText(valueShown, (left + ax) / 2f, ListDimens.CHECK_BASELINE.toFloat(), if (lit) litValue else value)
        canvas.restore()
    }
}

/**
 * A choice field's open list over the page, the whole page, drawn and touched only while bound
 * (GONE otherwise): border_inplace.png (A) [ListDimens.CHOICE_LIST_WIDTH] wide, the values centred
 * in BBAlpha Sans 27 #CCCCCC, the chosen one white in border_spinboxbutton~focus.png (A); placed by
 * [ChoiceList.top]. A tap on a value chooses it ([onChoose]), elsewhere closes it ([onCancel]).
 */
class ChoiceListView(
    context: Context,
    private val onChoose: (Int) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {
    private val panel = themeImage(context, "ui_choice_list")
    private val chosenImage = themeImage(context, "ui_choice_chosen")
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.ChoiceListText, align = Paint.Align.CENTER)
    private val chosenText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.CENTER)
    private val unavailableText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle, align = Paint.Align.CENTER)
    private var values = emptyList<String>()
    private var unavailable = emptySet<Int>()
    private var list: ChoiceList? = null
    private var left = 0
    private var listWidth = ListDimens.CHOICE_LIST_WIDTH
    private var rowTop = 0

    init {
        visibility = GONE
    }

    /**
     * Opens [values] for a field whose left edge is [fieldLeft] and row top [rowTop], in this view's
     * coordinates; the [unavailable] ones in grey (D); [width], wider
     * for longer values (a number's type, 154 px on sim-192).
     */
    fun open(values: List<String>, list: ChoiceList, fieldLeft: Int, rowTop: Int, unavailable: Set<Int> = emptySet(), width: Int = ListDimens.CHOICE_LIST_WIDTH) {
        this.values = values.map { text.fit(it, TextRoom.inside(width)) }
        this.unavailable = unavailable
        listWidth = width
        left = fieldLeft + ListDimens.CHOICE_LIST_X
        this.rowTop = rowTop
        show(list)
    }

    /** The open list's new focus, or null to close it. */
    fun show(list: ChoiceList?) {
        this.list = list
        visibility = if (list == null) GONE else VISIBLE
        invalidate()
    }

    private fun top(l: ChoiceList) = l.top(rowTop, ListDimens.CHOICE_LIST_ROW, height)

    override fun onDraw(canvas: Canvas) {
        val l = list ?: return
        val top = top(l)
        val row = ListDimens.CHOICE_LIST_ROW
        val right = left + listWidth
        panel.setBounds(left, top, right, top + l.count * row)
        panel.draw(canvas)
        val y = top + l.chosen * row
        chosenImage.setBounds(left, y, right, y + row)
        chosenImage.draw(canvas)
        val x = (left + right) / 2f
        values.forEachIndexed { i, v ->
            canvas.drawText(v, x, (top + i * row + ListDimens.CHECK_BASELINE).toFloat(), when (i) {
                l.chosen -> chosenText
                in unavailable -> unavailableText
                else -> text
            })
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = list ?: return false
        if (e.action != MotionEvent.ACTION_UP) return true
        val i = Math.floorDiv(e.y.toInt() - top(l), ListDimens.CHOICE_LIST_ROW)
        if (e.x >= left && e.x < left + listWidth && i in 0 until l.count) onChoose(i) else onCancel()
        return true
    }
}
