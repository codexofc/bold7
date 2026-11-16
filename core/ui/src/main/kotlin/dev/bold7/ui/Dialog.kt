package dev.bold7.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.Durations
import dev.bold7.design.Typography

/** The icons at a dialog's left: dialog_*.png of the theme, 80x80, a white outline over the box (A; sim-47, sim-62, C). */
enum class DialogIcon(internal val image: String) {
    QUESTION("ui_dialog_question"),
    EXCLAMATION("ui_dialog_exclamation"),
    INFORMATION("ui_dialog_information"),
    ERROR("ui_dialog_error"),
}

/**
 * A dialog of OS 7 (docs/BBOS7-VISUEL.md §12), or without [buttons] an app's status message (§13):
 * border_popup.png (A) around the [text] at its [icon]'s right, the buttons stacked and centred,
 * border_button~focus.png on the focused one and border_button.png on the others (A: the latter is
 * a white at 85 % that gives sim-19's #AFB1B4 over the box), their first letter underlined (sim-47,
 * C); the screen is not darkened (sim-19, C). Laid out by [DialogLayout]; the keys and the touches
 * are its owner's ([Bold7Dialog]), which moves [focused].
 */
class DialogView(
    context: Context,
    private val text: String,
    private val buttons: List<String>,
    icon: DialogIcon?,
    image: Bitmap? = null,
) : View(context), Focused {
    override var focused: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var layout: DialogLayout? = null
        private set

    private val popup = themeImage(context, "ui_popup")
    private val button = themeImage(context, "ui_button")
    private val buttonFocused = themeImage(context, "ui_button_focus")
    // [image]: an app's own 80x80 icon in the place of the theme's (the Clock's net_rim_alarm_reminder.png).
    private val iconBitmap: Bitmap? = image ?: icon?.let { themeBitmap(context, it.image) }
    private val textPaint = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text)
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val labelFocused = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val underline = fillPaint(DialogColors.ButtonText)
    private val underlineFocused = fillPaint(DialogColors.ButtonTextFocused)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var labelWidths = FloatArray(buttons.size)
    private var firstLetters = FloatArray(buttons.size)

    init {
        // Drawn on its canvas: read whole by a screen reader, its text then its buttons.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = (listOf(text) + buttons).joinToString(". ")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val l = DialogLayout(text, buttons, iconBitmap != null, textPaint::measureText, w, h)
        layout = l
        labelWidths = FloatArray(buttons.size) { label.measureText(l.buttonLabels[it]) }
        firstLetters = FloatArray(buttons.size) { if (l.buttonLabels[it].isEmpty()) 0f else label.measureText(l.buttonLabels[it], 0, 1) }
    }

    override fun onDraw(canvas: Canvas) {
        val d = layout ?: return
        // border_popup.png, 40x40: its border pixels are its column 4 and 35, its row 2 and 33.
        popup.setBounds(d.left - 4, d.top - 2, d.left + d.width + 4, d.top + d.height + 6)
        popup.draw(canvas)
        iconBitmap?.let { canvas.drawBitmap(it, d.iconX.toFloat(), d.iconY.toFloat(), bitmapPaint) }
        for (i in d.lines.indices) canvas.drawText(d.lines[i], d.textX.toFloat(), d.baseline(i).toFloat(), textPaint)
        val centre = d.buttonLeft + d.buttonWidth / 2f
        for (i in buttons.indices) {
            val top = d.buttonTop(i)
            val lit = i == focused
            // border_button*.png, 60x102: border pixels at its column 3 and 56, its row 3 and 98.
            val image = if (lit) buttonFocused else button
            image.setBounds(d.buttonLeft - 3, top - 3, d.buttonLeft + d.buttonWidth + 3, top + DialogDimens.BUTTON_HEIGHT + 3)
            image.draw(canvas)
            val baseline = (top + DialogDimens.BUTTON_BASELINE).toFloat()
            canvas.drawText(d.buttonLabels[i], centre, baseline, if (lit) labelFocused else label)
            val x = centre - labelWidths[i] / 2
            canvas.drawRect(x, baseline + DialogDimens.MNEMONIC_Y, x + firstLetters[i], baseline + DialogDimens.MNEMONIC_Y + DialogDimens.MNEMONIC_HEIGHT, if (lit) underlineFocused else underline)
        }
    }
}

/**
 * A dialog window over the app, its own focus ([FocusDialog]: the trackpad moves it between the
 * buttons, its click or Enter chooses, Back or Escape cancels, [onCancel]); a button's first letter
 * chooses it, a touch on a button chooses it, elsewhere nothing (D). [onButton] runs after it
 * closes, with the button's index. The status bar of Android stays over an app (the banner of OS 7
 * stays under a popup); only the home's own windows hide it, and a window of another app that
 * may come over the home ([hideStatusBar]: the Phone's missed calls and "Call …?").
 */
class Bold7Dialog(
    context: Context,
    text: String,
    private val buttons: List<String>,
    icon: DialogIcon? = null,
    focused: Int = 0,
    private val onButton: (Int) -> Unit,
    onCancel: () -> Unit = {},
    image: Bitmap? = null,
    hideStatusBar: Boolean = false,
) : FocusDialog<DialogView>(context, DialogView(context, text, buttons, icon, image).also { it.focused = focused }, buttons.size, hideStatusBar) {
    init {
        window?.setTitle(text)
        setOnCancelListener { onCancel() }
    }

    override fun itemAt(x: Float, y: Float): Int? = view.layout?.buttonAt(x, y)

    override fun onChosen(item: Int) = onButton(item)

    override fun onOtherKey(e: KeyEvent): Boolean {
        val i = mnemonic(buttons, e.displayLabel) ?: return false
        dismiss()
        onButton(i)
        return true
    }
}

/**
 * A confirmation as OS 7 asks it: the question mark, [title] and [message] on their lines, the
 * [positive] button focused over the [negative] one (sim-47, C; D for which is focused). [then]
 * runs on the positive button only. It returns the dialog of OS 7, a [Dialog], not the framework's AlertDialog.
 */
fun confirm(context: Context, title: Int, message: Int, negative: Int, positive: Int, then: () -> Unit): Dialog =
    Bold7Dialog(
        context,
        context.getString(title) + "\n" + context.getString(message),
        listOf(context.getString(positive), context.getString(negative)),
        DialogIcon.QUESTION,
        onButton = { if (it == 0) then() },
    ).also { it.show() }

/**
 * An app's status message, OS 7's toast (sim-42, C): [text] in the dialog's box, without button,
 * centred over [activity]'s content for [Durations.STATUS_MS] (by eye), touches going through.
 */
fun status(activity: Activity, text: String) {
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    val view = DialogView(activity, text, emptyList(), null)
    content.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    view.announceForAccessibility(text)
    view.postDelayed({ content.removeView(view) }, Durations.STATUS_MS)
}
