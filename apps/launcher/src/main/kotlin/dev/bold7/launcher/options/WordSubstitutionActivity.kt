package dev.bold7.launcher.options

import android.app.Activity
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.launcher.graph
import dev.bold7.launcher.switcher.startSwitcher
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.ChoiceList
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.ChoiceView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SearchField
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.TitleBar
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.useCaret
import dev.bold7.ui.withSwitchApplication

/**
 * Options > Typing and Language > Word Substitution (guide 7.1 p. 216 and 217, B): the user's own
 * entries, kept by Typing (TypingOptions, on the io thread). The list, Replace in title and With
 * under it (D); Menu > New, Edit, Delete. An entry (sim-300 to sim-302, C): the list with its Search field and each row "abotu (about)" and its mode; the edit screen "Word Substitution: edit" on white, Replace and With as plain fields, Using SmartCase or Specified Case (B) as a choice
 * field of Options; Menu > Insert Macro (B, its macros D), Save, Delete. The Language field is left
 * out (D: Typing uses every list whatever the language), and so are OS 7's own entries (D: Typing's,
 * not editable). Escape with changes asks "Changes made!" (sim-47, C).
 */
class WordSubstitutionActivity : Activity() {
    private sealed interface Act {
        data object Switch : Act
        data object New : Act
        data object Edit : Act
        data object Delete : Act
        data object InsertMacro : Act
        data object Save : Act
        data object Close : Act
        data class Macro(val index: Int) : Act
    }

    private val typing by lazy { SaisieTypingOptions(applicationContext) }
    private var rows: List<Substitution>? = null
    private var focus = 0
    private var busy = false

    /** The entry open: null on the list; [original] null for a new one. */
    private var editing = false
    private var original: Substitution? = null
    private var smart = true
    private var list: ChoiceList? = null
    private var menu: Menu<Act>? = null

    private lateinit var title: TitleBar
    private lateinit var search: SearchField
    private var query = ""
    private lateinit var scroll: ScrollView
    private lateinit var listRows: LinearLayout
    private lateinit var form: LinearLayout
    private lateinit var replace: EditText
    private lateinit var with: EditText
    private lateinit var using: ChoiceView
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        title = TitleBar(this, TITLE)
        search = SearchField(this, "Search").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    if (editing || s.toString() == query) return
                    query = s.toString()
                    focus = 0
                    showList()
                }
            })
        }
        listRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        replace = field()
        with = field()
        using = ChoiceView(this).apply {
            setBackgroundColor(ListColors.Background)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, _ -> bindUsing() }
            setOnClickListener { openUsing() }
        }
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MARGIN, GAP, MARGIN, MARGIN)
            addView(caption("Replace:"), LinearLayout.LayoutParams(match, wrap))
            addView(replace, LinearLayout.LayoutParams(match, wrap).apply { topMargin = 3; bottomMargin = GAP })
            addView(caption("With:"), LinearLayout.LayoutParams(match, wrap))
            addView(with, LinearLayout.LayoutParams(match, wrap).apply { topMargin = 3; bottomMargin = GAP })
            addView(using, LinearLayout.LayoutParams(match, ChoiceView.HEIGHT))
        }
        scroll = ScrollView(this).apply {
            // The focused scroll would draw the theme's 12 % focus highlight over the whole page; the activity draws its own focus.
            defaultFocusHighlightEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listRows, ViewGroup.LayoutParams(match, wrap))
        }
        listView = ChoiceListView(this, onChoose = ::chooseUsing, onCancel = { showList(null) })
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@WordSubstitutionActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(search, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(listView, FrameLayout.LayoutParams(match, match))
            addView(menuView)
        })
        // Without the bar's slide: OS 7 has no such bar.
        window.hideStatusBar()
        val g = graph
        busy = true
        g.io.post {
            val data = typing.read()
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (data == null) {
                    status(this, "Not available")
                    return@runOnUiThread
                }
                rows = data.substitutions
                showList()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.statusBars())
    }

    private fun caption(text: String) = TextView(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        setText(text)
    }

    /** A text field as the edit screen of sim-302 (C): BBAlpha Sans 27 black, the blue cursor, no box. */
    private fun field() = EditText(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        background = null // sim-302, C: plain text on the white page, no box
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        // Typed as it is meant to come back: no substitution of its own nor capital (D).
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        defaultFocusHighlightEnabled = false
        useCaret()
    }

    // The list

    /** The entries the Search field leaves. */
    private fun shown() = rows.orEmpty().matching(query)

    private fun showList() {
        editing = false
        title.bind(TITLE)
        search.visibility = View.VISIBLE
        scroll.removeAllViews()
        scroll.addView(listRows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setBackgroundColor(ListColors.Background)
        currentFocus?.clearFocus()
        listRows.removeAllViews()
        val all = shown()
        focus = focus.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        all.forEachIndexed { i, _ ->
            listRows.addView(EntryRow(this).apply { setOnClickListener { focus = i; edit(all[i]) } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ChoiceView.HEIGHT))
        }
        bindList()
    }

    private fun bindList() {
        shown().forEachIndexed { i, s -> (listRows.getChildAt(i) as EntryRow).bind(s.rowText, s.mode, lit = i == focus) }
        listRows.getChildAt(focus)?.let { v -> v.post { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true) } }
    }

    private fun delete(entry: Substitution) {
        Bold7Dialog(this, "Delete ${entry.replace}?", listOf("Delete", "Cancel"), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b == 0) write(rows.orEmpty().remove(entry)) { showList() }
        }).show()
    }

    // An entry

    private fun edit(entry: Substitution?) {
        editing = true
        title.bind("$TITLE: edit")
        search.visibility = View.GONE
        original = entry
        replace.setText(entry?.replace.orEmpty())
        with.setText(entry?.with.orEmpty())
        smart = entry?.smart ?: true
        scroll.removeAllViews()
        scroll.addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setBackgroundColor(ListColors.Background)
        bindUsing()
        replace.requestFocus()
        replace.setSelection(replace.text.length)
    }

    private fun bindUsing() = using.bind("Using:", USING[if (smart) 0 else 1], using.isFocused)

    private fun openUsing() {
        val l = ChoiceList(USING.size, if (smart) 0 else 1)
        val at = IntArray(2).also { using.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        list = l
        listView.open(USING, l, at[0] - origin[0] + using.fieldLeft, at[1] - origin[1])
    }

    private fun showList(l: ChoiceList?) {
        list = l
        listView.show(l)
    }

    private fun chooseUsing(i: Int) {
        showList(null)
        smart = i == 0
        bindUsing()
    }

    private val entry get() = Substitution(replace.text.toString().trim(), with.text.toString().trim(), smart)

    private val changed get() = entry != (original ?: Substitution("", "", smart = true))

    private fun insertMacro(code: String) {
        with.requestFocus()
        val at = with.selectionStart.coerceAtLeast(0)
        with.text.replace(at, with.selectionEnd.coerceAtLeast(at), code)
    }

    private fun save() {
        val e = entry
        if (!changed) return showList()
        problem(e.replace, e.with)?.let { return status(this, it) }
        write(rows.orEmpty().put(original, e)) { saved ->
            focus = saved.indexOf(e).coerceAtLeast(0)
            showList()
        }
    }

    /** Escape or Close on an entry: "Changes made!" when there are (sim-47, C), else the list. */
    private fun leave() {
        if (!changed) return showList()
        Bold7Dialog(this, "Changes made!", listOf("Save", "Discard", "Cancel"), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> showList()
            }
        }).show()
    }

    /** The whole list to Typing, then [then] with it; refused, said and nothing changed. */
    private fun write(all: List<Substitution>, then: (List<Substitution>) -> Unit) {
        if (busy) return
        busy = true
        val g = graph
        g.io.post {
            val ok = typing.setSubstitutions(all)
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (!ok) return@runOnUiThread status(this, "Changes not saved")
                rows = all
                then(all)
            }
        }
    }

    // Menu

    private fun label(a: Act) = when (a) {
        Act.Switch -> "Switch Application"
        Act.New -> "New"
        Act.Edit -> "Edit"
        Act.Delete -> "Delete"
        Act.InsertMacro -> "Insert Macro"
        Act.Save -> "Save"
        Act.Close -> "Close"
        is Act.Macro -> MACROS[a.index].first
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun fullMenu(): Menu<Act> = baseMenu().withSwitchApplication(Act.Switch)

    private fun baseMenu(): Menu<Act> = if (editing) {
        Menu(
            buildList {
                add(MenuEntry<Act>(Act.InsertMacro))
                add(MenuEntry(Act.Save, separatorAbove = true))
                if (original != null) add(MenuEntry(Act.Delete))
                add(MenuEntry(Act.Close, separatorAbove = true))
            },
            highlighted = 1,
        )
    } else {
        val any = !rows.isNullOrEmpty()
        Menu(
            buildList {
                add(MenuEntry<Act>(Act.New))
                if (any) {
                    add(MenuEntry(Act.Edit))
                    add(MenuEntry(Act.Delete))
                }
                add(MenuEntry(Act.Close, separatorAbove = true))
            },
            highlighted = if (any) 1 else 0,
        )
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        val focused = shown().getOrNull(focus)
        when (a) {
            Act.Switch -> startSwitcher(this, fromHome = false)
            Act.New -> edit(null)
            Act.Edit -> focused?.let(::edit)
            Act.Delete -> if (editing) original?.let(::delete) else focused?.let(::delete)
            Act.InsertMacro -> show(Menu(MACROS.indices.map { MenuEntry<Act>(Act.Macro(it)) }, highlighted = 0))
            Act.Save -> save()
            Act.Close -> if (editing) leave() else finish()
            is Act.Macro -> insertMacro(MACROS[a.index].second)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val open = list != null || menu != null
        if (e.action != KeyEvent.ACTION_DOWN) return if (open || (!editing && !(search.hasFocus() && (e.isPrintingKey || e.keyCode == KeyEvent.KEYCODE_DEL)))) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val l = list
        if (l != null) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> showList(l.step(down = false))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> showList(l.step(down = true))
                click -> if (e.repeatCount == 0) chooseUsing(l.chosen)
                back -> showList(null)
            }
            return true
        }
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            val key = when {
                code == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                code == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
                code == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        if (busy) return true
        if (code == KeyEvent.KEYCODE_MENU) {
            if (rows != null) show(fullMenu())
            return true
        }
        if (editing) {
            if (back) leave() else return super.dispatchKeyEvent(e)
            return true
        }
        val all = shown()
        when {
            back -> if (query.isNotEmpty()) search.setText("") else finish()
            code == KeyEvent.KEYCODE_DPAD_UP -> { focus = (focus - 1).coerceAtLeast(0); bindList() }
            code == KeyEvent.KEYCODE_DPAD_DOWN -> { focus = (focus + 1).coerceAtMost((all.size - 1).coerceAtLeast(0)); bindList() }
            click -> if (e.repeatCount == 0) all.getOrNull(focus)?.let(::edit)
            e.isPrintingKey || code == KeyEvent.KEYCODE_DEL -> { search.requestFocus(); return super.dispatchKeyEvent(e) }
        }
        return true
    }

    /** A row of the list (sim-300, C): 57 px, the entry at the left, its mode at the right, the focus a blue band. */
    private class EntryRow(context: Context) : View(context) {
        private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
        private val litText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
        private val modeText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
        private val litMode = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
        private val band = focusPaint(ChoiceView.HEIGHT)
        private val divider = fillPaint(ListColors.Divider)
        private var label = ""
        private var mode = ""
        private var lit = false

        fun bind(label: String, mode: String, lit: Boolean) {
            if (label == this.label && mode == this.mode && lit == this.lit) return
            this.label = label
            this.mode = mode
            this.lit = lit
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = ChoiceView.HEIGHT.toFloat()
            if (lit) canvas.drawRect(0f, 0f, w, h, band) else canvas.drawRect(0f, h - 1, w, h, divider)
            val right = w - ROW_X
            val room = right - modeText.measureText(mode) - 2 * ROW_X
            // Cut by the one `fit` (an ellipsis), no longer by a clip.
            canvas.drawText(text.fit(label, room.coerceAtLeast(0f)), ROW_X.toFloat(), ROW_BASELINE, if (lit) litText else text)
            canvas.drawText(mode, right, ROW_BASELINE, if (lit) litMode else modeText)
        }
    }

    private companion object {
        const val ROW_X = 6
        const val ROW_BASELINE = 37f
        const val TITLE = "Word Substitution"
        /** Using's values (guide p. 217, B). */
        val USING = listOf("SmartCase", "Specified Case")
        /** Margin and gap of the edit screen (D). */
        const val MARGIN = 9
        const val GAP = 7
    }
}
