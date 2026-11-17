package dev.bold7.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.Focus
import dev.bold7.design.Typography
import dev.bold7.ui.Dir
import dev.bold7.ui.FocusDialog
import dev.bold7.ui.Focused
import dev.bold7.ui.TEXT_DIALOG_MAX
import dev.bold7.ui.accepted
import dev.bold7.ui.fillPaint
import dev.bold7.ui.slate
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import dev.bold7.ui.useCaret

/**
 * Add Folder and Edit Folder (sim-472, sim-481, C): [title], a rule, the folder's icon (one of
 * [icons], the chooser's) and its name, a rule, [ok] over Cancel, the buttons without underlined
 * letters. The name field opens focused, an Edit Folder's name selected; the icon, focused,
 * shows the home's focus behind it (sim-473).
 */
internal class FolderBoxView(
    context: Context,
    private val title: String,
    private val buttons: List<String>,
    initialName: String,
    icon: Int,
    private val icons: List<Bitmap>,
) : ViewGroup(context), Focused {
    override var focused: Int = FolderItem.NAME
        set(value) {
            if (field == value) return
            field = value
            name.isCursorVisible = value == FolderItem.NAME
            invalidate()
        }

    var icon: Int = icon
        set(value) {
            field = value
            invalidate()
        }

    private var layout: FolderBoxLayout? = null
    private val popup = themeImage(context, "ui_popup")
    private val button = themeImage(context, "ui_button")
    private val buttonFocused = themeImage(context, "ui_button_focus")
    private val focus = themeImage(context, "home_focus")
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text)
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val labelFocused = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private val rule = fillPaint(DialogColors.Rule)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    val name = EditText(context).apply {
        background = null
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(DialogColors.Text)
        highlightColor = Focus.TextSelection
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        setPadding(0, 0, 0, 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(TEXT_DIALOG_MAX))
        useCaret()
        setText(initialName)
        if (initialName.isEmpty()) setSelection(0) else selectAll()
        contentDescription = title
    }

    init {
        setWillNotDraw(false)
        addView(name)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = (listOf(title) + buttons).joinToString(". ")
    }

    fun itemAt(x: Float, y: Float): Int? = layout?.itemAt(x, y)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val l = FolderBoxLayout(w, h)
        layout = l
        val line = name.paint.fontMetricsInt.let { it.descent - it.ascent }
        name.measure(MeasureSpec.makeMeasureSpec(l.nameRight - l.nameX + 1, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(line, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = layout ?: return
        // The first line's baseline sits the font's ascent under the field's top (no padding, no font padding).
        val top = d.nameBaseline + name.paint.fontMetricsInt.ascent
        name.layout(d.nameX, top, d.nameX + name.measuredWidth, top + name.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val d = layout ?: return
        // As DialogView: border_popup.png's border pixels are its column 4 and 35, its row 2 and 33.
        popup.setBounds(d.left - 4, d.top - 2, d.left + d.width + 4, d.top + d.height + 6)
        popup.draw(canvas)
        canvas.drawText(title, d.titleX.toFloat(), d.titleBaseline.toFloat(), text)
        for (y in d.rules) canvas.drawRect(d.ruleLeft.toFloat(), y.toFloat(), (d.ruleRight + 1).toFloat(), (y + 1).toFloat(), rule)
        if (focused == FolderItem.ICON) {
            focus.setBounds(d.focusLeft, d.iconY, d.focusRight + 1, d.iconY + ICON)
            focus.draw(canvas)
        }
        icons.getOrNull(icon)?.let { canvas.drawBitmap(it, d.iconX.toFloat(), d.iconY.toFloat(), bitmapPaint) }
        val centre = d.buttonLeft + d.buttonWidth / 2f
        for (i in buttons.indices) {
            val top = d.buttonTop(i)
            val lit = focused == FolderItem.OK + i
            // border_button*.png, 60x102: border pixels at its column 3 and 56, its row 3 and 98.
            val image = if (lit) buttonFocused else button
            image.setBounds(d.buttonLeft - 3, top - 3, d.buttonLeft + d.buttonWidth + 3, top + DialogDimens.BUTTON_HEIGHT + 3)
            image.draw(canvas)
            canvas.drawText(buttons[i], centre, (top + DialogDimens.BUTTON_BASELINE).toFloat(), if (lit) labelFocused else label)
        }
    }

    private companion object {
        const val ICON = 92
    }
}

/**
 * Add Folder or Edit Folder: the trackpad moves the focus ([folderStep]; on the name, left and
 * right move its caret first), its click or Enter chooses: the icon opens the chooser
 * ([IconChooser]), the name or [ok] hands the name, cleaned as the layout keeps it, and the icon
 * to [onDone] (an empty name keeps the box open, D), Cancel or Escape closes. A letter typed
 * goes into the name and focuses it (D).
 */
internal class FolderBox(
    context: Context,
    title: String,
    ok: String,
    cancel: String,
    name: String,
    icon: Int,
    private val icons: List<Bitmap>,
    private val onDone: (String, Int) -> Unit,
) : FocusDialog<FolderBoxView>(context, FolderBoxView(context, title, listOf(ok, cancel), name, icon, icons), 4, hideStatusBar = true) {
    init {
        window?.setTitle(title)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val field = view.name
        field.setOnCapturedPointerListener { _, e -> onPointer(e) }
        // Enter as the input method hands it: the focused item, as Enter's key does (FocusDialog).
        field.setOnEditorActionListener { _, _, _ ->
            choose(view.focused)
            true
        }
        field.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) view.focused = FolderItem.NAME
            false
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                view.focused = FolderItem.NAME
            }
        })
    }

    private val typed get() = accepted(view.name.text.toString(), ::folderName)

    override fun itemAt(x: Float, y: Float): Int? = view.itemAt(x, y)

    override fun next(from: Int, dir: Dir): Int {
        val f = view.name
        if (from == FolderItem.NAME) {
            val at = f.selectionEnd
            if (dir == Dir.LEFT && at > 0) {
                f.setSelection(at - 1)
                return from
            }
            if (dir == Dir.RIGHT && at < f.length()) {
                f.setSelection(at + 1)
                return from
            }
        }
        return folderStep(from, dir)
    }

    override fun closes(item: Int): Boolean = when (item) {
        FolderItem.ICON -> false
        FolderItem.CANCEL -> true
        else -> typed != null
    }

    override fun onChosen(item: Int) {
        when (item) {
            FolderItem.ICON -> IconChooser(context, view.icon, icons) { view.icon = it }.show()
            FolderItem.CANCEL -> Unit
            else -> typed?.let { onDone(it, view.icon) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // The name keeps the window's focus, for the keyboard and the input method; the trackpad
        // follows it there.
        view.name.requestFocus()
        view.name.requestPointerCapture()
    }
}

/**
 * The chooser of Add Folder's icon (sim-474, sim-475, C): the box of a dialog, the 21 icons of
 * [icons] in rows of five, the home's focus (92x92) behind the focused one, which opens on the
 * folder's [current] icon. The click chooses ([onChosen] then the box closes), Escape keeps it.
 */
internal class IconChooserView(context: Context, private val icons: List<Bitmap>) : View(context), Focused {
    override var focused: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var layout: IconChooserLayout? = null
    private val popup = themeImage(context, "ui_popup")
    private val focus = themeImage(context, "home_focus")
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    fun itemAt(x: Float, y: Float): Int? = layout?.itemAt(x, y)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = IconChooserLayout(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val d = layout ?: return
        popup.setBounds(d.left - 4, d.top - 2, d.left + d.width + 4, d.top + d.height + 6)
        popup.draw(canvas)
        val f = focused
        focus.setBounds(d.focusX(f), d.iconY(f), d.focusX(f) + IconChooserLayout.ICON, d.iconY(f) + IconChooserLayout.ICON)
        focus.draw(canvas)
        for (i in icons.indices) {
            dst.set(d.iconX(i), d.iconY(i), d.iconX(i) + IconChooserLayout.ICON, d.iconY(i) + IconChooserLayout.ICON)
            canvas.drawBitmap(icons[i], null, dst, bitmapPaint)
        }
    }
}

internal class IconChooser(context: Context, current: Int, icons: List<Bitmap>, private val onPick: (Int) -> Unit) :
    FocusDialog<IconChooserView>(context, IconChooserView(context, icons).also { it.focused = current }, FOLDER_ICONS, hideStatusBar = true) {
    override fun itemAt(x: Float, y: Float): Int? = view.itemAt(x, y)

    override fun next(from: Int, dir: Dir): Int = iconStep(from, dir)

    override fun onChosen(item: Int) = onPick(item)
}
