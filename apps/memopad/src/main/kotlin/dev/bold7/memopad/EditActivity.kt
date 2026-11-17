package dev.bold7.memopad

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.useCaret
import dev.bold7.ui.FieldSelection
import dev.bold7.ui.TextAct
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.TextDialog
import dev.bold7.ui.SaveButton
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.themeImage

/**
 * One memo, new or edited (guide p. 206, B; docs/BBOS7-VISUEL.md §34.2, sim-255 to sim-258 and
 * sim-266, C): the title "New Memo" or "Edit Memo", on the grey page a white box with "Title:" in
 * bold and its field, the save button at its right (save.png, a green tick), then the body in a
 * white box that takes the rest. Opening a memo puts the cursor in its body (sim-266). Menu: Clear
 * Field, Save (focused), Start Selection, Close (sim-256, C; no Delete: sim-256 has none), Cut and
 * Copy in Start Selection's place on a selection, Paste after them (guide 7.1 p. 213, B; their
 * place D), core/ui's FieldSelection; Escape with changes asks
 * "Changes made!", Save, Discard, Cancel (sim-193, C); a new memo with no text is not saved, one
 * with a title only is (sim-254's Moon). Renders [MemoEditor]; the database is written off the main
 * thread.
 *
 * Not there: Switch Input Language/Method, Show Symbols, Check
 * Spelling and Categories (no effect to give them), the short menu (Close, Full Menu).
 */
class EditActivity : Activity() {
    private enum class Act {
        SWITCH, FIND, CLEAR, SAVE, START_SELECTION, CUT, COPY, PASTE, CLOSE;
        val text get() = TextAct.entries.firstOrNull { it.name == name }
    }

    companion object {
        const val EXTRA_ID = "id"
        /** A new memo's title, what was typed in the list (D). */
        const val EXTRA_TITLE = "title"
        /** sim-255 (C): the boxes 5 px from the edges and 8 apart, the title's 51 px high, the save button 86 wide, #C6C6C6 borders. */
        const val MARGIN = 5
        const val GAP = 8
        const val TITLE_HEIGHT = 51
        const val BUTTON_WIDTH = 86
        const val BOX_BORDER = 0xFFC6C6C6.toInt()
        const val BODY_PADDING = 6
    }

    private var editor = MemoEditor.of(null)
    private var menu: Menu<Act>? = null
    private val selection = FieldSelection()
    private var saving = false
    private var ready = false
    private lateinit var title: EditText
    private lateinit var body: EditText
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        title = field(multi = false)
        body = field(multi = true)
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        val top = FrameLayout(this).apply {
            addView(box(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ListDimens.TEXT_X, 0, ListDimens.TEXT_X, 0)
                addView(caption())
                addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
            }), FrameLayout.LayoutParams(match, TITLE_HEIGHT).apply { rightMargin = BUTTON_WIDTH + GAP })
            addView(SaveButton(this@EditActivity, themeImage(this@EditActivity, "mem_save"), getString(R.string.act_save)).apply { setOnClickListener { save() } }, FrameLayout.LayoutParams(BUTTON_WIDTH, TITLE_HEIGHT, Gravity.END))
        }
        // No status bar of Android's: the 9900 shows its title bar alone (sim-254).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            addView(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@EditActivity, getString(if (id == null) R.string.new_title else R.string.edit_title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(LinearLayout(this@EditActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(MARGIN, MARGIN, MARGIN, MARGIN)
                    addView(top, LinearLayout.LayoutParams(match, TITLE_HEIGHT).apply { bottomMargin = GAP })
                    addView(box(body.apply { setPadding(BODY_PADDING, BODY_PADDING, BODY_PADDING, BODY_PADDING) }), LinearLayout.LayoutParams(match, 0, 1f))
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        title.addTextChangedListener(watcher { editor = editor.withTitle(it) })
        body.addTextChangedListener(watcher { editor = editor.withBody(it) })
        if (id == null) {
            val typed = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            editor = MemoEditor.of(null)
            ready = true
            if (typed.isNotEmpty()) title.setText(typed)
            title.requestFocus()
            title.setSelection(title.text.length)
        } else {
            val g = graph
            g.io.post {
                val memo = g.memos.get(id)
                runOnUiThread {
                    if (memo == null) return@runOnUiThread finish()
                    // Filling the fields is not an edit: the editor is made from the stored memo, after.
                    title.setText(memo.title)
                    body.setText(memo.body)
                    editor = MemoEditor.of(memo)
                    ready = true
                    body.requestFocus()
                    body.setSelection(body.text.length)
                }
            }
        }
    }

    /** A white box with its #C6C6C6 border (sim-255, as Tasks' form). */
    private fun box(child: View) = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            setColor(ListColors.Background)
            setStroke(1, BOX_BORDER)
        }
        setPadding(1, 1, 1, 1)
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun watcher(then: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) = then(s.toString())
    }

    private fun caption() = TextView(this).apply {
        typeface = slate(Typography.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        setText(getString(R.string.field_title) + " ")
    }

    /** A text field without a border of its own: its box is the border (sim-255); the blue cursor. */
    private fun field(multi: Boolean) = EditText(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        background = null
        setPadding(0, 0, 0, 0)
        gravity = if (multi) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
        useCaret()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        isSingleLine = !multi
        defaultFocusHighlightEnabled = false
    }

    // Menu, leaving, saving

    private fun label(a: Act) = getString(a.text?.let(FieldSelection::label) ?: when (a) {
        Act.SWITCH -> R.string.act_switch_application
        Act.FIND -> R.string.act_find
        Act.CLEAR -> R.string.act_clear_field
        Act.SAVE -> R.string.act_save
        else -> R.string.act_close
    })

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    /**
     * Switch Application, Find, Clear Field, Save focused, Start Selection (or Cut, Copy), Paste, Close
     * (sim-256, C; guide 7.1 p. 213, B). Of sim-256's ten entries the others have no effect here (Show
     * Symbols, Check Spelling, Categories, Switch Input Language/Method): left out rather than shown dead.
     */
    private fun fullMenu(): Menu<Act> {
        val text = selection.acts(focusedField()).map { a -> MenuEntry(Act.entries.first { it.text == a }) }
        return Menu(listOf(MenuEntry(Act.FIND), MenuEntry(Act.CLEAR), MenuEntry(Act.SAVE)) + text + MenuEntry(Act.CLOSE, separatorAbove = true), highlighted = 2)
            .withSwitchApplication(Act.SWITCH)
    }

    private fun focusedField() = if (body.isFocused) body else title

    private var found = ""

    /** Find: asks for the text, selects its next place in the body (wrapping once), else says it is not there (D). */
    private fun find() = TextDialog(this, getString(R.string.find_label), found, onDone = { q ->
        found = q
        val text = body.text.toString()
        val from = if (body.isFocused) body.selectionEnd else 0
        val at = findNext(text, q, from)
        if (at == null) status(this, getString(R.string.not_found)) else {
            body.requestFocus()
            body.setSelection(at, at + q.length)
        }
    }).show()

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            Act.SWITCH -> SwitchApplication.start(this)
            Act.FIND -> find()
            Act.CLEAR -> focusedField().setText("")
            Act.START_SELECTION, Act.CUT, Act.COPY, Act.PASTE -> a.text?.let { selection.perform(focusedField(), it) }
            Act.SAVE -> save()
            Act.CLOSE -> leave()
        }
    }

    /** Escape or Close: "Changes made!" when there are (sim-193, C), else gone. */
    private fun leave() {
        if (!ready || !editor.changed) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.act_save), getString(R.string.discard), getString(R.string.cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> finish()
            }
        }).show()
    }

    /** Save: the memo written; a new memo with no text is not saved, a memo left as it was is not rewritten (D). */
    private fun save() {
        if (!ready || saving) return
        val e = editor
        if (!e.changed || (e.id == null && e.blank)) return finish()
        saving = true
        val g = graph
        val (id, t, b) = e.toSave()
        g.io.post {
            val saved = g.memos.save(id, t, b)
            runOnUiThread {
                saving = false
                if (saved == null) status(this, getString(R.string.not_saved)) else finish()
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return if (menu != null) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
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
        if (selection.key(e)) return true
        when {
            back -> leave()
            code == KeyEvent.KEYCODE_MENU -> show(fullMenu())
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}
