package dev.bold7.contacts

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bold7.design.DialogColors
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.ChoiceList
import dev.bold7.ui.useCaret
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.FieldSelection
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SpinDialog
import dev.bold7.ui.Spin
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.fit
import dev.bold7.ui.TitleBar
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.textAct
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import dev.bold7.ui.SaveButton

/**
 * Editing a contact, or a new one (sim-184 to sim-188, sim-191, sim-192, C; guide p. 190 and 191,
 * B; docs/BBOS7-VISUEL.md §30): "Edit Contact" or "New Contact", a grey band with the save button
 * (save.png) at its right, then the boxes of OS 7.1: the picture and Title, First, Last, Company;
 * Email Addresses; Phone Numbers, each with its type's list (Work, Home, Mobile…); Address; Website;
 * Notes. Each field is border_form.png (A) after its label; the trackpad moves from field to field,
 * the keyboard types into the one with the cursor. Menu > Save writes what changed (B); Escape with
 * changes asks "Changes made!", Save, Discard, Cancel (sim-193, C). Renders [Editor]; the provider
 * is written off the main thread.
 */
open class EditActivity : Activity() {
    companion object {
        /** The grey band under the title and its save button: 60 px, the button 86x51, 3 px from its top and the right edge (sim-184, C). */
        const val BAND = 60
        const val BAND_BUTTON_TOP = 3
        const val BAND_BUTTON_RIGHT = 3
        const val BUTTON_WIDTH = 86
        const val BUTTON_HEIGHT = 51
        /** A field: 43 px with its border, 7 apart; its text 6 px in (sim-184, C). */
        const val FIELD = 43
        const val GAP = 7
        const val FIELD_TEXT_X = 6
        /** The label's column: 9 px in, the fields 13 px after the box's longest label, 9 px from the right edge (C). */
        const val LABEL_X = 9
        const val LABEL_GAP = 13
        const val FIELD_RIGHT = 9
        /** The picture's band at the header's top, 85 px; the picture image (78 px) 5 px in, 2 down (sim-184, sim-191, C). */
        const val PICTURE_BAND = 85
        const val PICTURE_X = 5
        const val PICTURE_Y = 2
        /** A number's type button: 139 px from x 14 (sim-185, C); its list 154 px wide (sim-192, C). */
        const val TYPE_X = 14
        const val TYPE_WIDTH = 139
        const val TYPE_LIST_WIDTH = 154
        private const val FIRST_TAG = "first"
        /** The list opened on the Contact List button, not a number's type. */
        private const val ACCOUNT_LIST = -2
        /** The Contact List box (sim-191, C): its button 155 px wide, 12 px from the edge. */
        const val ACCOUNT_WIDTH = 155
        const val ACCOUNT_RIGHT = 12
        /** A date's button: 86 px at least (the "Add" of sim-186, C), 12 px from the edge. */
        const val DATE_WIDTH = 86
        const val DATE_RIGHT = 12
        /** A new contact's first name, what was typed in the list (D). */
        const val EXTRA_NAME = "name"
    }

    private var contactId: Long? = null
    /** A new contact asked by another app (InsertContactActivity): its extras prefill, its id extra is never read. */
    private var prefill: Prefill? = null
    private var asked = false
    private var editor: Editor? = null
    private var menu: Menu<ContactAct>? = null
    private val selection = FieldSelection()
    private var list: ChoiceList? = null
    private var listing = -1
    private var saving = false
    private var picture: Bitmap? = null
    private lateinit var page: LinearLayout
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<ContactAct>
    private val typeButtons = HashMap<Int, TypeButton>()
    private val dateButtons = HashMap<Int, TypeButton>()
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = this is InsertContactActivity
        if (asked) {
            prefill = (Request.parse(intent.action, null, null, extrasOf(intent)) as? Request.Insert)?.prefill
        } else {
            contactId = intent.getLongExtra(CardActivity.EXTRA_ID, -1).takeIf { it >= 0 }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listView = ChoiceListView(this, onChoose = ::choose, onCancel = { showList(null) })
        menuView = MenuView(this, glyph = { null }, label = { getString(when (it) { ContactAct.SWITCH -> R.string.act_switch_application; ContactAct.SAVE -> R.string.act_save; ContactAct.CLEAR -> R.string.act_clear_field; ContactAct.HELP -> R.string.act_help; ContactAct.CLOSE -> R.string.act_close; else -> FieldSelection.label(it.textAct()!!) }) }, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-178).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            addView(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@EditActivity, getString(if (contactId == null) R.string.new_title else R.string.edit_title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(ScrollView(this@EditActivity).apply {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = ScrollView.OVER_SCROLL_NEVER
                    addView(page)
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(listView)
            addView(menuView)
        })
        val g = graph
        val contact = contactId
        g.io.post {
            val stored = if (contact == null) emptyList() else g.store.stored(contact)
            val photo = contact?.let { g.store.card(it)?.photo }?.let { g.store.thumbnail(it) }
            val frame = BitmapFactory.decodeResource(resources, R.drawable.cnt_picture, BitmapFactory.Options().apply { inScaled = false })
            runOnUiThread {
                picture = photo ?: frame
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val e = Editor.of(stored).let { base -> prefill?.let(base::prefilled) ?: base }
                bind(if (contact == null && name.isNotEmpty()) e.edited(e.fields.indexOfFirst { it.key == FieldKey.FIRST }, name) else e)
                page.findViewWithTag<View>(FIRST_TAG)?.requestFocus()
            }
        }
    }

    /** The page built from [e]'s fields: the band, then a box per group, a row per field. */
    private fun bind(e: Editor) {
        editor = e
        page.removeAllViews()
        typeButtons.clear()
        dateButtons.clear()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        page.addView(FrameLayout(this).apply {
            addView(SaveButton(this@EditActivity, themeImage(this@EditActivity, R.drawable.cnt_save), getString(R.string.act_save)).apply { setOnClickListener { save() } }, FrameLayout.LayoutParams(BUTTON_WIDTH, BUTTON_HEIGHT, Gravity.END).apply {
                topMargin = BAND_BUTTON_TOP
                rightMargin = BAND_BUTTON_RIGHT
            })
        }, LinearLayout.LayoutParams(match, BAND))
        // A new contact says where it goes first (sim-191, C): Bold7 has one place, the account that holds most (D).
        if (contactId == null) page.addView(accountBox(), LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        val boxes = listOf(
            null to listOf(Group.NAME, Group.ORGANIZATION),
            getString(R.string.section_emails) to listOf(Group.EMAIL),
            getString(R.string.section_phones) to listOf(Group.PHONE),
            getString(R.string.section_address) to listOf(Group.ADDRESS),
            getString(R.string.section_dates) to listOf(Group.DATE),
            getString(R.string.section_website) to listOf(Group.WEBSITE),
            getString(R.string.section_notes) to listOf(Group.NOTE),
        )
        for ((title, groups) in boxes) {
            val rows = e.fields.withIndex().filter { it.value.key.group in groups }
            val box = BoxLayout(this, title, if (title == null) picture else null, band = title == null)
            val labelWidth = rows.maxOfOrNull { label.measureText(it.value.key.label) } ?: 0f
            rows.forEachIndexed { n, (i, f) ->
                box.addView(row(i, f, labelWidth), LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (n > 0) topMargin = GAP })
            }
            page.addView(box, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** "Contact List:" and its one choice, Default (sim-191, C). */
    private fun accountBox(): View {
        val box = BoxLayout(this, null, null, band = false)
        val frame = FrameLayout(this)
        frame.addView(TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            text = getString(R.string.contact_list)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON_HEIGHT).apply { leftMargin = LABEL_X })
        frame.addView(TypeButton(this, arrow = true).apply {
            bind(getString(R.string.contact_list_default))
            setOnClickListener { openList(ACCOUNT_LIST, this) }
        }, FrameLayout.LayoutParams(ACCOUNT_WIDTH, BUTTON_HEIGHT, Gravity.END).apply { rightMargin = ACCOUNT_RIGHT })
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BUTTON_HEIGHT))
        return box
    }

    /** A date's row: its label, and at the right a button, "Add" or the date, which opens the date box (sim-186, C). */
    private fun dateRow(index: Int, f: Field): View {
        val frame = FrameLayout(this)
        frame.addView(TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            text = f.key.label
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON_HEIGHT).apply { leftMargin = LABEL_X })
        val b = TypeButton(this, arrow = false).apply {
            bind(dateText(f.value))
            setOnClickListener { askDate(index) }
        }
        dateButtons[index] = b
        val width = maxOf(DATE_WIDTH, label.measureText(dateText(f.value)).toInt() + 2 * dev.bold7.ui.TextRoom.VALUE_PAD + 24)
        frame.addView(b, FrameLayout.LayoutParams(width, BUTTON_HEIGHT, Gravity.END).apply { rightMargin = DATE_RIGHT })
        return frame
    }

    private fun dateText(value: String) = if (value.isEmpty()) getString(R.string.add) else dateLabel(value)

    /** The date box of the Calendar's form (core/ui), on what is stored or today; OK writes it into the field. */
    private fun askDate(index: Int) {
        val f = editor?.fields?.getOrNull(index) ?: return
        SpinDialog(this, Spin.date(parseDate(f.value) ?: java.time.LocalDate.now())) { day -> setDate(index, day.toString()) }.show()
    }

    private fun setDate(index: Int, value: String) {
        val e = editor ?: return
        editor = e.edited(index, value)
        dateButtons[index]?.bind(dateText(value))
        dateButtons[index]?.let { b -> b.layoutParams = (b.layoutParams as FrameLayout.LayoutParams).also { it.width = maxOf(DATE_WIDTH, label.measureText(dateText(value)).toInt() + 2 * dev.bold7.ui.TextRoom.VALUE_PAD + 24) } }
    }

    /** A field's row: its label then border_form.png, or a number's type button then the field; Notes alone. */
    private fun row(index: Int, f: Field, labelWidth: Float): View {
        if (f.key.group == Group.DATE) return dateRow(index, f)
        val frame = FrameLayout(this)
        val left: Int
        when {
            f.key == FieldKey.PHONE -> {
                val b = TypeButton(this).apply {
                    bind(f.type?.label.orEmpty())
                    setOnClickListener { openList(index, this) }
                }
                typeButtons[index] = b
                frame.addView(b, FrameLayout.LayoutParams(TYPE_WIDTH, BUTTON_HEIGHT).apply { leftMargin = TYPE_X })
                left = TYPE_X + TYPE_WIDTH + 4
            }
            f.key.label.isEmpty() -> left = LABEL_X
            else -> {
                frame.addView(TextView(this).apply {
                    typeface = slate(Typography.REGULAR)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
                    setTextColor(ListColors.Title)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    text = f.key.label
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, FIELD).apply { leftMargin = LABEL_X })
                left = LABEL_X + labelWidth.toInt() + LABEL_GAP
            }
        }
        val multi = f.key == FieldKey.ADDRESS || f.key == FieldKey.NOTES
        val field = EditText(this).apply {
            if (f.key == FieldKey.FIRST) tag = FIRST_TAG
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            includeFontPadding = false
            background = themeImage(this@EditActivity, R.drawable.cnt_field)
            setPadding(FIELD_TEXT_X, 0, FIELD_TEXT_X, 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = FIELD
            useCaret()
            inputType = when (f.key) {
                FieldKey.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                FieldKey.WEBSITE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                FieldKey.PHONE -> InputType.TYPE_CLASS_PHONE
                FieldKey.FIRST, FieldKey.LAST -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                FieldKey.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                FieldKey.NOTES -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            } or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = !multi
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            defaultFocusHighlightEnabled = false
            setText(f.value)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = edited(index, s.toString())
            })
        }
        val top = if (f.key == FieldKey.PHONE) (BUTTON_HEIGHT - FIELD) / 2 else 0
        frame.addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left
            rightMargin = FIELD_RIGHT
            topMargin = top
        })
        return frame
    }

    /**
     * Text typed: the state follows; the last empty email, number or address filled brings its next
     * empty row (C for the email), so the page is built again, the cursor kept in the field.
     */
    private fun edited(index: Int, text: String) {
        val e = editor ?: return
        val next = e.edited(index, text)
        editor = next
        if (next.fields.size == e.fields.size) return
        bind(next)
        fieldAt(index)?.let {
            it.requestFocus()
            it.setSelection(it.text.length)
        }
    }

    /** The editor of field [index], in the page's order, which is the fields' order. */
    private fun fieldAt(index: Int): EditText? {
        var n = -1
        for (b in 0 until page.childCount) {
            val box = page.getChildAt(b) as? ViewGroup ?: continue
            for (r in 0 until box.childCount) {
                val row = box.getChildAt(r) as? ViewGroup ?: continue
                for (c in 0 until row.childCount) {
                    val v = row.getChildAt(c) as? EditText ?: continue
                    n++
                    if (n == index) return v
                }
            }
        }
        return null
    }

    // A number's type

    private fun openList(index: Int, button: View) {
        val account = index == ACCOUNT_LIST
        val f = if (account) null else editor?.fields?.getOrNull(index) ?: return
        val labels = if (account) listOf(getString(R.string.contact_list_default)) else PhoneType.entries.map { it.label }
        val l = ChoiceList(labels.size, f?.type?.ordinal ?: 0)
        listing = index
        list = l
        val at = IntArray(2).also { button.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        listView.open(labels, l, at[0] - origin[0], at[1] - origin[1], width = if (account) ACCOUNT_WIDTH else TYPE_LIST_WIDTH)
    }

    private fun showList(l: ChoiceList?) {
        list = l
        listView.show(l)
    }

    private fun choose(chosen: Int) {
        val i = listing
        showList(null)
        // Contact List has the one choice: nothing to change.
        if (i == ACCOUNT_LIST) return
        val e = editor ?: return
        val type = PhoneType.entries[chosen]
        editor = e.typed(i, type)
        typeButtons[i]?.bind(type.label)
    }

    // Menu, leaving, saving

    private fun show(m: Menu<ContactAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            ContactAct.SWITCH -> SwitchApplication.start(this)
            ContactAct.SAVE -> save()
            ContactAct.CLEAR -> dateButtons.entries.firstOrNull { it.value.isFocused }?.let { setDate(it.key, "") }
            // Help has no source for its text: it only closes the menu, as in the other apps.
            ContactAct.HELP -> Unit
            ContactAct.CLOSE -> leave()
            ContactAct.START_SELECTION, ContactAct.CUT, ContactAct.COPY, ContactAct.PASTE -> (currentFocus as? EditText)?.let { selection.perform(it, a.textAct()!!) }
        }
    }

    /** Menu (sim-188, C): see [contactMenu]. Not there: Switch Input Language, Show Symbols, Categories, Delete Picture. */
    private fun fullMenu(): Menu<ContactAct> {
        val onDate = dateButtons.entries.firstOrNull { it.value.isFocused }?.key?.let { editor?.fields?.getOrNull(it)?.value?.isNotEmpty() } == true
        return contactMenu(onDate, (currentFocus as? EditText)?.let(selection::acts).orEmpty())
    }

    /** Escape or Close: "Changes made!" when there are (sim-193, C), else gone. */
    private fun leave() {
        val e = editor
        if (e == null || !e.changed) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.act_save), getString(R.string.discard), getString(R.string.cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> finish()
            }
        }).show()
    }

    /** Save: what changed written; a new contact with nothing typed is not saved (D). */
    private fun save() {
        val e = editor ?: return
        if (saving) return
        if (!e.changed || (contactId == null && e.empty)) return finish()
        saving = true
        val g = graph
        val contact = contactId
        val changes = e.changes()
        g.io.post {
            val saved = g.store.save(contact, changes)
            runOnUiThread {
                saving = false
                if (saved == null) status(this, getString(R.string.not_saved)) else {
                    // The contact added for another app: its URI, readable by it (as Android's Contacts returns it).
                    if (asked) setResult(RESULT_OK, Intent().setData(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, saved)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    finish()
                }
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return if (list != null || menu != null) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val l = list
        if (l != null) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> showList(l.step(down = false))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> showList(l.step(down = true))
                click -> if (e.repeatCount == 0) choose(l.chosen)
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
        // Start Selection: the trackpad extends the selection until Cut, Copy or Escape.
        if (selection.key(e)) return true
        when {
            back -> leave()
            code == KeyEvent.KEYCODE_MENU -> show(fullMenu())
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}

/**
 * A box of the form, as the card's (border_groupboxfull and its title bar, A; sim-185, C): white,
 * its title bold 23 grey over a line, its rows 7 px under it and 8 px over its edge; the header box
 * has no title but the contact's picture in its 85 px band.
 */
private class BoxLayout(context: Context, private val title: String?, private val picture: Bitmap?, band: Boolean) : LinearLayout(context) {
    private val box = themeImage(context, R.drawable.cnt_box)
    private val titleBar = themeImage(context, R.drawable.cnt_title)
    private val text = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.GroupTitle)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        // The Contact List box (sim-191: 62 px round a 51 px button) has neither a title nor the picture's band.
        val top = CardView.EDGE_TOP + if (title != null) CardView.TITLE + 1 + EditActivity.GAP else if (band) EditActivity.PICTURE_BAND else 1
        setPadding(0, top, 0, CardView.EDGE_BOTTOM + if (title == null && !band) 4 else 8)
    }

    private var shownTitle = ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shownTitle = title?.let { text.fit(it, ContactRoom.boxTitle(w)) }.orEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        box.setBounds(0, 0, width, height)
        box.draw(canvas)
        if (title != null) {
            titleBar.setBounds(0, 0, width, CardView.EDGE_TOP + CardView.TITLE + 1)
            titleBar.draw(canvas)
            canvas.drawText(shownTitle, CardView.TITLE_X.toFloat(), (CardView.EDGE_TOP + CardView.TITLE_BASELINE).toFloat(), text)
        }
        picture?.let { canvas.drawBitmap(it, EditActivity.PICTURE_X.toFloat(), (CardView.EDGE_TOP + EditActivity.PICTURE_Y).toFloat(), bitmapPaint) }
    }
}

/** A number's type (sim-185, C): border_button.png with its value and arrow_choice.png, blue under the trackpad's focus (A). */
private class TypeButton(context: Context, private val arrow: Boolean = true) : View(context) {
    private val normal = themeImage(context, "ui_button")
    private val lit = themeImage(context, "ui_button_focus")
    private val arrowImage = themeImage(context, "ui_choice_arrow")
    private val arrowLit = themeImage(context, "ui_choice_arrow_focus")
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
    private val textLit = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
    private var value = ""
    /** [value] cut to the button's room ("…"), worked out when the value or the width changes. */
    private var shown = ""

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
    }

    fun bind(value: String) {
        this.value = value
        contentDescription = value
        refit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    private fun refit() {
        shown = if (width == 0) value else text.fit(value, ContactRoom.button(width, arrow))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val on = isFocused
        // border_button*.png: border pixels at column 3 and 56, row 3 and 98, as core/ui's dialogs.
        (if (on) lit else normal).apply { setBounds(-3, -3, width + 3, height + 3) }.draw(canvas)
        if (!arrow) return canvas.drawText(shown, width / 2f, ListDimens.CHECK_BASELINE.toFloat(), if (on) textLit else text)
        val a = if (on) arrowLit else arrowImage
        val ax = width - ListDimens.CHOICE_ARROW_RIGHT - a.intrinsicWidth
        val ay = (height - a.intrinsicHeight) / 2
        a.setBounds(ax, ay, ax + a.intrinsicWidth, ay + a.intrinsicHeight)
        a.draw(canvas)
        canvas.drawText(shown, ax / 2f + 4, ListDimens.CHECK_BASELINE.toFloat(), if (on) textLit else text)
    }
}

/**
 * New Contact for the other apps: ACTION_INSERT and ACTION_INSERT_OR_EDIT ("Add to
 * contacts" of Google Messages, a browser...), exported with strict filters. The form opens
 * prefilled with their extras and writes nothing before the user's Save.
 */
class InsertContactActivity : EditActivity()

/** The strings and numbers an intent carries, what [Request.parse] reads of its extras. */
internal fun extrasOf(intent: Intent): Map<String, Any?> =
    intent.extras?.let { b -> b.keySet().associateWith { k -> if (b.containsKey(k)) b.get(k) else null } }.orEmpty()
