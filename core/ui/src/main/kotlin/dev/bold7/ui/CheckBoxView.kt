package dev.bold7.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * A row of OS 7's options with a check box, its switch (OS 7 draws no toggle: Manage Connections
 * and Options check boxes, sim-23, sim-60, C): 57 px, the label in BBAlpha Sans 27 at the left, the
 * box at the right, a 27x27 cell of net_rim_system_Check-icons27x27.png (A: columns normal, focus,
 * disabled, disabled with focus; rows unchecked, checked). Under the trackpad's focus ([lit]), the
 * blue band and white text (sim-60, C); disabled, grey text and box (sim-60, C). Its owner toggles
 * it on the trackpad's click, Space or a tap, as OS 7 does (D).
 */
class CheckBoxView(context: Context) : View(context) {
    companion object {
        const val HEIGHT = ListDimens.CHECK_ROW
    }

    private val sprite: Bitmap = themeBitmap(context, "ui_check")
    private val focus = focusPaint(HEIGHT)
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.CheckText)
    private val disabledText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle)
    private val litText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val bitmapPaint = Paint()
    private val src = Rect()
    private val dst = Rect()
    private var label = ""
    private var checked = false
    private var lit = false
    private var enabled = true
    private var shown = ""

    // The row as OS 7 measures it, from the screen's edges through its group box ([TextRoom.screenGap]).
    private var leftGap = 0
    private var rightGap = 0

    /** The box before its label, as the form of the Calendar has it (sim-228: All Day); the lists have it after (sim-23). */
    var boxFirst = false
        set(v) {
            field = v
            shown = text.fit(label, room(width))
            invalidate()
        }

    private fun room(w: Int) = roomIn(w + leftGap + rightGap)
    private fun roomIn(w: Int) = if (boxFirst) TextRoom.checkLabelAfterBox(w) else TextRoom.checkLabel(w)

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // Drawn on its canvas: told to a screen reader as Android's check box, its label and its state.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = CheckBox::class.java.name
        info.isCheckable = true
        info.isChecked = checked
        info.isEnabled = enabled
        info.text = label
    }

    fun bind(label: String, checked: Boolean, lit: Boolean = false, enabled: Boolean = true) {
        if (label === this.label && checked == this.checked && lit == this.lit && enabled == this.enabled) return
        this.label = label
        this.checked = checked
        this.lit = lit
        this.enabled = enabled
        shown = text.fit(label, room(width))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shown = text.fit(label, room(w))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val at = IntArray(2).also(::getLocationInWindow)
        val l = TextRoom.screenGap(at[0])
        val r = TextRoom.screenGap(rootView.width - at[0] - width)
        if (l == leftGap && r == rightGap) return
        leftGap = l
        rightGap = r
        shown = text.fit(label, room(width))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (lit) canvas.drawRect(0f, 0f, width.toFloat(), HEIGHT.toFloat(), focus)
        // Margins from the screen's edges (sim-170): the row's own canvas shifted by its box.
        canvas.save()
        canvas.translate(-leftGap.toFloat(), 0f)
        val w = width + leftGap + rightGap
        canvas.drawText(shown, (if (boxFirst) TextRoom.CHECK_LABEL_AFTER_BOX else ListDimens.TEXT_X).toFloat(), ListDimens.CHECK_BASELINE.toFloat(), if (lit) litText else if (enabled) text else disabledText)
        val box = ListDimens.CHECK_BOX
        val column = (if (enabled) 0 else 2) + (if (lit) 1 else 0)
        val row = if (checked) 1 else 0
        src.set(column * box, row * box, (column + 1) * box, (row + 1) * box)
        val x = if (boxFirst) ListDimens.CHECK_RIGHT else w - ListDimens.CHECK_RIGHT - box
        val y = (HEIGHT - box) / 2
        dst.set(x, y, x + box, y + box)
        canvas.drawBitmap(sprite, src, dst, bitmapPaint)
        canvas.restore()
    }
}
