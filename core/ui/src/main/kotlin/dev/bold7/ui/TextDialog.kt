package dev.bold7.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.ListColors
import dev.bold7.design.Typography

/** The longest text a text box takes (D: OS 7's limit is not sourced). */
const val TEXT_DIALOG_MAX = 100

/**
 * The box of [TextDialog]: border_popup.png around the label and the field (border_find.png, A),
 * laid out by [TextDialogLayout]. [field] is an ordinary [EditText]: the keyboard types into it.
 */
class TextDialogView(context: Context, private val label: String, initial: String, private val onOutside: () -> Unit) : ViewGroup(context) {
    private val popup = themeImage(context, "ui_popup")
    private val textPaint = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text)
    private var layout: TextDialogLayout? = null

    val field = EditText(context).apply {
        background = themeImage(context, "ui_find")
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(DialogDimens.FIELD_TEXT_X, 0, DialogDimens.FIELD_TEXT_X, 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(TEXT_DIALOG_MAX))
        useCaret()
        setText(initial)
        setSelection(text.length)
    }

    init {
        setWillNotDraw(false)
        field.contentDescription = label
        addView(field)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val l = TextDialogLayout(label, textPaint::measureText, w, h)
        layout = l
        field.measure(MeasureSpec.makeMeasureSpec(l.fieldWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(l.fieldHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = layout ?: return
        field.layout(d.fieldLeft, d.fieldTop, d.fieldLeft + d.fieldWidth, d.fieldTop + d.fieldHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val d = layout ?: return
        // As DialogView: border_popup.png's border pixels are its column 4 and 35, its row 2 and 33.
        popup.setBounds(d.left - 4, d.top - 2, d.left + d.width + 4, d.top + d.height + 6)
        popup.draw(canvas)
        for (i in d.lines.indices) canvas.drawText(d.lines[i], d.textX.toFloat(), d.baseline(i).toFloat(), textPaint)
    }

    // A touch off the box cancels, on the box nothing (D, as the other dialogs).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = layout
        if (e.actionMasked == MotionEvent.ACTION_UP && d != null && (e.x < d.left || e.x >= d.left + d.width || e.y < d.top || e.y >= d.top + d.height)) onOutside()
        return true
    }
}

/**
 * The text box of OS 7 (sim-61, C): [label], the field under it with [initial] typed, no button.
 * Enter hands the text, cleaned by [clean], to [onDone] after the box closes (a text with nothing
 * left keeps it open, D); Escape, Back or a touch outside cancel ([onCancel]). The keyboard of the
 * Q25 types into the field through the input method (Typing). [hideStatusBar]: as [FocusDialog],
 * for a box over the home.
 */
class TextDialog(
    context: Context,
    label: String,
    initial: String = "",
    private val clean: (String) -> String = String::trim,
    private val hideStatusBar: Boolean = false,
    private val onDone: (String) -> Unit,
    onCancel: () -> Unit = {},
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
    private val view = TextDialogView(context, label, initial) { cancel() }

    init {
        setContentView(view)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            fitBars() // under the banner, as an activity (Bars.kt)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setTitle(label)
        }
        setOnCancelListener { onCancel() }
        view.field.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
    }

    private fun submit() {
        val text = accepted(view.field.text.toString(), clean) ?: return
        dismiss()
        onDone(text)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return
        if (hideStatusBar) window?.insetsController?.hide(WindowInsets.Type.statusBars())
        view.field.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            cancel()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
