package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
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

        // How far the value starts into the icon's clear right edge (sim-284: folder image to x 489, "Home" from 487).
        private const val ICON_OVERLAP = 2
        private const val ICON_ABOVE_BASELINE = 25
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

    /**
     * Without a label the field takes the row's width (Launch by Typing, sim-284, C); false keeps it
     * at the right, the usual width, as Downloads Folder's (sim-284, C). Set before the first bind.
     */
    var fullWidth = true
        set(v) {
            field = v
            cut()
            invalidate()
        }

    // Drawn before the value, the two centred together (Downloads Folder's folder, sim-284, C).
    private var icon: Drawable? = null
    private val iconRoom get() = icon?.let { it.intrinsicWidth - ICON_OVERLAP } ?: 0

    // The row as OS 7 measures it, from the screen's edges through its group box ([TextRoom.screenGap]).
    private var leftGap = 0
    private var rightGap = 0
    private val span get() = width + leftGap + rightGap

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

    /** [icon] before the value, or none. */
    fun bind(label: String, value: String, lit: Boolean = false, icon: Drawable? = null) {
        if (label === this.label && value == shown && lit == this.lit && icon === this.icon) return
        this.icon = icon
        this.label = label
        this.shown = value
        this.lit = lit
        cut()
        invalidate()
    }

    // The label up to the field, the value inside it before its arrow: cut when the text or the width changes.
    private fun cut() {
        // OS 7's field takes its value's width (sim-170): wider than the usual one for a long value, never over the label.
        fieldWidth = if (wide || stacked) span - 2 * ListDimens.TEXT_X else TextRoom.choiceField(span, text.measureText(label), value.measureText(shown) + iconRoom)
        labelShown = text.fit(label, if (stacked) (span - 2 * ListDimens.TEXT_X).toFloat().coerceAtLeast(0f) else TextRoom.choiceLabel(spanFieldLeft))
        valueShown = value.fit(shown, (TextRoom.inside(ax() - spanFieldLeft) - iconRoom).coerceAtLeast(0f))
    }

    // In the span's coordinates.
    private fun ax() = spanFieldLeft + fieldWidth - ListDimens.CHOICE_ARROW_FROM_EDGE - ListDimens.CHOICE_ARROW_WIDTH
    private val spanFieldLeft: Int get() = if (wide || stacked) ListDimens.TEXT_X else span - ListDimens.CHECK_RIGHT - fieldWidth
    private val wide get() = label.isEmpty() && fullWidth

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = cut()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val at = IntArray(2).also(::getLocationInWindow)
        val l = TextRoom.screenGap(at[0])
        val r = TextRoom.screenGap(rootView.width - at[0] - width)
        if (l == leftGap && r == rightGap) return
        leftGap = l
        rightGap = r
        cut()
        invalidate()
    }

    /** The field's left edge in this view, where its open list goes (ListDimens.CHOICE_LIST_X further). */
    val fieldLeft: Int get() = spanFieldLeft - leftGap

    // Without a label the field takes the row's width (a group's only button, Home Screen Preferences, sim-284, C).
    private var fieldWidth = ListDimens.CHOICE_FIELD_WIDTH

    override fun onDraw(canvas: Canvas) {
        val labelX = (ListDimens.TEXT_X - leftGap).toFloat()
        if (stacked) canvas.drawText(labelShown, labelX, STACK_BASELINE.toFloat(), text)
        else canvas.drawText(labelShown, labelX, ListDimens.CHECK_BASELINE.toFloat(), text)
        canvas.save()
        canvas.translate(-leftGap.toFloat(), fieldTop.toFloat())
        val left = spanFieldLeft
        val right = left + fieldWidth
        val top = ListDimens.CHOICE_FIELD_TOP
        val image = if (lit) fieldFocus else field
        // The field's measures are its visible edges (sim-66: 442 to 628); border_button.png has 3 transparent px on each side.
        image.setBounds(left - ListDimens.CHOICE_FIELD_SHADOW, top, right + ListDimens.CHOICE_FIELD_SHADOW, top + ListDimens.CHOICE_FIELD_HEIGHT)
        image.draw(canvas)
        val a = if (lit) arrowFocus else arrow
        val ax = ax()
        val ay = top + (ListDimens.CHOICE_FIELD_HEIGHT - a.intrinsicHeight) / 2
        a.setBounds(ax, ay, ax + a.intrinsicWidth, ay + a.intrinsicHeight)
        a.draw(canvas)
        val centre = (left + ax) / 2f
        val i = icon
        if (i == null) {
            canvas.drawText(valueShown, centre, ListDimens.CHECK_BASELINE.toFloat(), if (lit) litValue else value)
        } else {
            // The folder and the value as one, centred; the value starts 2 px into the image's clear edge,
            // the image's top 25 px over the value's baseline (sim-284, C: image x 459 to 489, y from 373; "Home" from x 487, baseline 398).
            val textWidth = value.measureText(valueShown)
            val il = (centre - (iconRoom + textWidth) / 2f).toInt()
            val iy = ListDimens.CHECK_BASELINE - ICON_ABOVE_BASELINE
            i.setBounds(il, iy, il + i.intrinsicWidth, iy + i.intrinsicHeight)
            i.draw(canvas)
            canvas.drawText(valueShown, il + iconRoom + textWidth / 2f, ListDimens.CHECK_BASELINE.toFloat(), if (lit) litValue else value)
        }
        canvas.restore()
    }
}

/**
 * A choice field's open list over the page, the whole page, drawn and touched only while bound
 * (GONE otherwise): border_inplace.png (A) [ListDimens.CHOICE_LIST_WIDTH] wide, or as its longest
 * value (sim-270, TextRoom.choiceList), the values right-aligned 8 px from its edge (sim-67: 100, 90
 * and 70 end at 518, the panel at 526; sim-270)
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
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.ChoiceListText, align = Paint.Align.RIGHT)
    private val chosenText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val unavailableText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle, align = Paint.Align.RIGHT)
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
        val widest = values.maxOfOrNull { text.measureText(it) } ?: 0f
        // The screen, not this view: GONE until now, it has no width yet. Too wide for the room
        // right of the field, the list moves left rather than cut its values (sim-417).
        val screen = resources.displayMetrics.widthPixels
        listWidth = TextRoom.choiceList(width, widest, screen)
        left = TextRoom.choiceListLeft(fieldLeft + ListDimens.CHOICE_LIST_X, listWidth, screen)
        this.values = values.map { text.fit(it, TextRoom.inside(listWidth)) }
        this.unavailable = unavailable
        this.rowTop = rowTop
        show(list)
    }

    /** The open list's new focus, or null to close it. */
    fun show(list: ChoiceList?) {
        this.list = list
        visibility = if (list == null) GONE else VISIBLE
        invalidate()
    }

    // The title bar at the top of the page stays clear (s_rs0: Launch by Typing's list over "Home Screen Preferences").
    private fun top(l: ChoiceList) = l.top(rowTop, ListDimens.CHOICE_LIST_ROW, height, TitleBar.HEIGHT)

    override fun onDraw(canvas: Canvas) {
        val l = list ?: return
        val top = top(l)
        val row = ListDimens.CHOICE_LIST_ROW
        val right = left + listWidth
        panel.setBounds(left, top, right, top + l.count * row)
        panel.draw(canvas)
        val y = top + l.chosen * row
        // The chosen value's cell inside the panel's border, its row high (sim-270: panel 424 to 598,
        // cell 432 to 591; sim-67); border_spinboxbutton~focus.png has 3 transparent px on each side.
        val shadow = ListDimens.CHOICE_FIELD_SHADOW
        chosenImage.setBounds(left + TextRoom.LIST_PAD - shadow, y - shadow, right - TextRoom.LIST_PAD + 1 + shadow, y + row + shadow)
        chosenImage.draw(canvas)
        val x = (right - TextRoom.LIST_PAD).toFloat()
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
