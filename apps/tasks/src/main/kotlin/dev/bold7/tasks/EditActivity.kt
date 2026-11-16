package dev.bold7.tasks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.useCaret
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.ChoiceList
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.ChoiceView
import dev.bold7.ui.DateTimeRow
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.FieldSelection
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.SaveButton
import dev.bold7.ui.Spin
import dev.bold7.ui.SpinDialog
import dev.bold7.ui.TitleBar
import dev.bold7.ui.slate
import dev.bold7.ui.textAct
import dev.bold7.ui.status
import dev.bold7.ui.themeImage
import dev.bold7.platform.zoneChoices
import dev.bold7.platform.zoneLabel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One task, new or open, as sim-268 and sim-273 (C): "New Task" or "Edit Task" (A) as the title;
 * "Title:" in bold in its white box with the save button at its right; a box of Status, Priority,
 * Due (None, By Date:, sim-272) with its date and time under it, Reminder (None, Relative, By Date:,
 * A) with its time or its length under it, Recurrence once there is a due date (sim-273); then Notes.
 * Each date and time opens OS 7's box (core/ui SpinDialog, sim-274, sim-275). Menu > Save or the
 * button write the task after the firmware's checks (A, [Check]); Escape with changes asks "Changes
 * made!", Save, Discard, Cancel (sim-193, C); a new task with nothing typed is not saved (D).
 * Renders [TaskEditor]; the database is written off the main thread, the reminders armed after.
 *
 * Time Zone under Due (sim-268, sim-319): the task's, where its due date and its reminder are wall
 * times. Not there: Categories, the menu's Switch
 * Application, Switch Input Language/Method, Show Symbols and Help (sim-269).
 */
class EditActivity : Activity() {

    /** The open list's field. */
    private enum class Field { STATUS, PRIORITY, DUE, TIME_ZONE, REMINDER, BEFORE, RECURRENCE }

    companion object {
        private const val EXTRA_ID = "id"

        fun open(context: Context, id: Long): Intent = Intent(context, EditActivity::class.java).putExtra(EXTRA_ID, id)

        /** sim-268 (C): the boxes 5 px from the edges and 8 apart, the title's 51 px high, the save button 86x51. */
        const val MARGIN = 5
        const val BOX_GAP = 8
        const val TITLE_HEIGHT = 51
        const val BUTTON_WIDTH = 86
        const val BOX_BORDER = 0xFFC6C6C6.toInt()
        const val NOTES_LINES = 4
    }

    private var editor = TaskEditor.of(null, ZoneId.systemDefault())
    private var menu: Menu<TaskAct>? = null
    private val selection = FieldSelection()
    private var saving = false
    private var ready = false
    private var listing: Field? = null
    private var list: ChoiceList? = null
    private lateinit var title: EditText
    private lateinit var notes: EditText
    private lateinit var choices: Map<Field, ChoiceView>
    private lateinit var dueRow: DateTimeRow
    private lateinit var atRow: DateTimeRow
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<TaskAct>

    private val now: LocalDateTime get() = LocalDateTime.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        title = field(multi = false)
        notes = field(multi = true)
        choices = Field.entries.associateWith { f -> choice { open(f) } }
        dueRow = dateRow { pick(dueRow) }
        atRow = dateRow { pick(atRow) }
        listView = ChoiceListView(this, onChoose = ::choose, onCancel = { showList(null) })
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        val top = FrameLayout(this).apply {
            addView(box(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ListDimens.TEXT_X, 0, ListDimens.TEXT_X, 0)
                addView(caption(R.string.field_title, bold = true))
                addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
            }), FrameLayout.LayoutParams(match, TITLE_HEIGHT).apply { rightMargin = BUTTON_WIDTH + BOX_GAP })
            addView(SaveButton(this@EditActivity, themeImage(this@EditActivity, R.drawable.tsk_save), getString(R.string.act_save)).apply { setOnClickListener { save() } }, FrameLayout.LayoutParams(BUTTON_WIDTH, TITLE_HEIGHT, Gravity.END))
        }
        val fields = box(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (v in listOf(choices.getValue(Field.STATUS), choices.getValue(Field.PRIORITY), choices.getValue(Field.DUE), dueRow, choices.getValue(Field.TIME_ZONE), choices.getValue(Field.REMINDER), choices.getValue(Field.BEFORE), atRow, choices.getValue(Field.RECURRENCE))) {
                addView(v, LinearLayout.LayoutParams(match, ChoiceView.HEIGHT))
            }
        })
        val notesBox = box(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ListDimens.TEXT_X, 8, ListDimens.TEXT_X, 8)
            addView(caption(R.string.field_notes, bold = false))
            addView(notes, LinearLayout.LayoutParams(match, wrap))
        })
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MARGIN, MARGIN, MARGIN, MARGIN)
            addView(top, LinearLayout.LayoutParams(match, TITLE_HEIGHT).apply { bottomMargin = BOX_GAP })
            addView(fields, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = BOX_GAP })
            addView(notesBox, LinearLayout.LayoutParams(match, wrap))
        }
        // No status bar of Android's: the 9900 shows its title bar alone (sim-267).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            addView(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@EditActivity, getString(if (id == null) R.string.new_title else R.string.edit_title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(ScrollView(this@EditActivity).apply {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = ScrollView.OVER_SCROLL_NEVER
                    addView(page)
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(listView, FrameLayout.LayoutParams(match, match))
            addView(menuView)
        })
        title.addTextChangedListener(watcher { editor = editor.withTitle(it) })
        notes.addTextChangedListener(watcher { editor = editor.withNotes(it) })
        if (id == null) {
            ready = true
            fill()
            title.requestFocus()
        } else {
            val g = graph
            g.io.post {
                val task = g.tasks.get(id)
                runOnUiThread {
                    if (task == null) return@runOnUiThread finish()
                    // Filling the fields is not an edit: the editor is made from the stored task, after.
                    title.setText(task.title)
                    notes.setText(task.notes)
                    editor = TaskEditor.of(task, ZoneId.systemDefault())
                    ready = true
                    fill()
                    title.requestFocus()
                    title.setSelection(title.text.length)
                }
            }
        }
    }

    // Views

    /** A white box with its #C6C6C6 border (sim-268, as the Calendar's sim-228). */
    private fun box(child: View) = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            setColor(ListColors.Background)
            setStroke(1, BOX_BORDER)
        }
        setPadding(1, 1, 1, 1)
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun caption(text: Int, bold: Boolean) = TextView(this).apply {
        typeface = slate(if (bold) Typography.BOLD else Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        setText(getString(text) + " ")
    }

    /** A text field without a border of its own: its box is the border (sim-268); OS 7's caret. */
    private fun field(multi: Boolean) = EditText(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        background = null
        // The caret's block starts a pixel over the first line's top, where TextView clips: this
        // pixel keeps it whole (9 x 32 measured on the Q25, as the Calendar's).
        setPadding(0, 1, 0, 0)
        useCaret()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        isSingleLine = !multi
        if (multi) minLines = NOTES_LINES
        defaultFocusHighlightEnabled = false
    }

    private fun watcher(then: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) = then(s.toString())
    }

    /** A core/ui choice row that draws the trackpad's focus itself, made focusable and clickable (as the Calendar's). */
    private fun choice(click: () -> Unit) = ChoiceView(this).apply { focusable(this, click) }

    private fun dateRow(click: () -> Unit) = DateTimeRow(this, "").apply { focusable(this, click) }

    private fun focusable(v: View, click: () -> Unit) = v.apply {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, _ -> fill() }
        setOnClickListener { click() }
    }

    /** The fields from the editor: values, which rows show, which is lit. */
    private fun fill() {
        val e = editor
        fun bind(f: Field, label: Int, value: String) = choices.getValue(f).let { it.bind(if (label == 0) "" else getString(label), value, it.isFocused) }
        bind(Field.STATUS, R.string.field_status, label(e.status))
        bind(Field.PRIORITY, R.string.field_priority, label(e.priority))
        bind(Field.DUE, R.string.field_due, getString(if (e.due == null) R.string.none else R.string.by_date))
        // sim-268: always there, under Due (and its date, sim-319).
        bind(Field.TIME_ZONE, R.string.field_time_zone, zoneLabel(e.zone.id, zoneInstant))
        bind(Field.REMINDER, R.string.field_reminder, label(e.reminderKind))
        bind(Field.BEFORE, 0, beforeText((e.reminder as? Reminder.Before)?.minutes ?: DEFAULT_BEFORE))
        bind(Field.RECURRENCE, R.string.field_recurrence, label(e.recurrence))
        e.due?.let { dueRow.value(dateLabel(it.toLocalDate()), timeLabel(it.toLocalTime())) }
        (e.reminder as? Reminder.At)?.let { atRow.value(dateLabel(it.time.toLocalDate()), timeLabel(it.time.toLocalTime())) }
        dueRow.lit = dueRow.isFocused
        atRow.lit = atRow.isFocused
        dueRow.visibility = if (e.due != null) View.VISIBLE else View.GONE
        choices.getValue(Field.BEFORE).visibility = if (e.reminder is Reminder.Before) View.VISIBLE else View.GONE
        atRow.visibility = if (e.reminder is Reminder.At) View.VISIBLE else View.GONE
        choices.getValue(Field.RECURRENCE).visibility = if (e.due != null) View.VISIBLE else View.GONE
    }

    /** The instant whose offsets the Time Zone row and its list show: the due date, else now (D). */
    private val zoneInstant: Instant get() = editor.due?.atZone(editor.zone)?.toInstant() ?: Instant.now()

    /** "15 Min.", "1 Hour", "5 Hours": the Calendar's (sim-231, sim-232). */
    private fun beforeText(m: Int) = when {
        m >= 60 && m % 60 == 0 -> (m / 60).let { resources.getQuantityString(R.plurals.hours, it, it) }
        else -> getString(R.string.minutes, m)
    }

    // The open lists

    private fun values(f: Field): List<Any?> = when (f) {
        Field.STATUS -> Status.entries
        Field.PRIORITY -> Priority.entries
        Field.DUE -> listOf(false, true)
        Field.TIME_ZONE -> zoneChoices(editor.zone.id, zoneInstant)
        Field.REMINDER -> editor.reminderKinds
        Field.BEFORE -> BEFORE_CHOICES.reversed()
        Field.RECURRENCE -> Recurrence.entries
    }

    private fun text(f: Field, v: Any?): String = when (f) {
        Field.STATUS -> label(v as Status)
        Field.PRIORITY -> label(v as Priority)
        Field.DUE -> getString(if (v == true) R.string.by_date else R.string.none)
        Field.TIME_ZONE -> zoneLabel(v as String, zoneInstant)
        Field.REMINDER -> label(v as ReminderKind)
        Field.BEFORE -> beforeText(v as Int)
        Field.RECURRENCE -> label(v as Recurrence)
    }

    private fun current(f: Field): Any? = when (f) {
        Field.STATUS -> editor.status
        Field.PRIORITY -> editor.priority
        Field.DUE -> editor.due != null
        Field.TIME_ZONE -> editor.zone.id
        Field.REMINDER -> editor.reminderKind
        Field.BEFORE -> (editor.reminder as? Reminder.Before)?.minutes
        Field.RECURRENCE -> editor.recurrence
    }

    private fun open(f: Field) {
        val field = choices.getValue(f)
        val vs = values(f)
        val l = ChoiceList(vs.size, vs.indexOf(current(f)).coerceAtLeast(0))
        listing = f
        val at = IntArray(2).also { field.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        list = l
        // The zones' names are long: their list is as wide as the field (D, as the Calendar's), the others keep OS 7's.
        val width = if (f == Field.TIME_ZONE) field.width - field.fieldLeft - ListDimens.CHECK_RIGHT else ListDimens.CHOICE_LIST_WIDTH
        listView.open(vs.map { text(f, it) }, l, at[0] - origin[0] + field.fieldLeft, at[1] - origin[1], width = width)
    }

    private fun showList(l: ChoiceList?) {
        list = l
        listView.show(l)
    }

    private fun choose(i: Int) {
        val f = listing ?: return
        val v = values(f)[i]
        showList(null)
        val e = editor
        editor = when (f) {
            Field.STATUS -> e.withStatus(v as Status)
            Field.PRIORITY -> e.withPriority(v as Priority)
            Field.DUE -> if (v == true) e.withDue(e.due ?: defaultDue(now)) else e.withDue(null)
            Field.TIME_ZONE -> e.withZone(ZoneId.of(v as String))
            Field.REMINDER -> e.withReminderKind(v as ReminderKind, now)
            Field.BEFORE -> e.withReminder(Reminder.Before(v as Int))
            Field.RECURRENCE -> e.withRecurrence(v as Recurrence)
        }
        fill()
    }

    /** The date or the time of a row, in OS 7's box (sim-274, sim-275). */
    private fun pick(row: DateTimeRow) {
        val e = editor
        val t = if (row === dueRow) e.due else (e.reminder as? Reminder.At)?.time
        if (t == null) return
        val set: (LocalDateTime) -> Unit = { v ->
            editor = if (row === dueRow) editor.withDue(v) else editor.withReminder(Reminder.At(v))
            fill()
        }
        if (row.segment == 0) SpinDialog(this, Spin.date(t.toLocalDate())) { d -> set(t.with(d)) }.show()
        else SpinDialog(this, Spin.time(t.toLocalTime())) { h -> set(t.with(h)) }.show()
    }

    // Menu, leaving, saving

    private fun label(a: TaskAct) = getString(when (a) {
        TaskAct.SWITCH -> R.string.act_switch_application
        TaskAct.SAVE -> R.string.act_save
        TaskAct.DELETE -> R.string.act_delete
        TaskAct.CLOSE -> R.string.act_close
        TaskAct.START_SELECTION, TaskAct.CUT, TaskAct.COPY, TaskAct.PASTE -> FieldSelection.label(a.textAct()!!)
    })

    private fun show(m: Menu<TaskAct>?) {
        menu = m
        menuView.bind(m)
    }

    /**
     * Switch Application (sim-269, C), Save focused, Delete on a stored task (B), the focused field's
     * Start Selection (or Cut and Copy) and Paste (guide 7.1 p. 213, B; their place D, as MemoPad's),
     * Close (sim-269). No field in focus (a row of the form): none of the selection entries.
     */
    private fun fullMenu(): Menu<TaskAct> = taskMenu(editor.id != null, (currentFocus as? EditText)?.let(selection::acts).orEmpty())

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            TaskAct.SWITCH -> SwitchApplication.start(this)
            TaskAct.SAVE -> save()
            TaskAct.DELETE -> delete()
            TaskAct.CLOSE -> leave()
            TaskAct.START_SELECTION, TaskAct.CUT, TaskAct.COPY, TaskAct.PASTE -> (currentFocus as? EditText)?.let { selection.perform(it, a.textAct()!!) }
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

    /** Save: a task not changed is not rewritten, a new one with nothing typed is not saved (D); then the firmware's checks, one by one (A). */
    private fun save() {
        if (!ready || saving) return
        val e = editor
        if (!e.changed || (e.id == null && e.blank)) return finish()
        check(e, e.checks(now))
    }

    private fun check(e: TaskEditor, left: List<Check>) {
        val c = left.firstOrNull() ?: return write(e)
        val rest = left.drop(1)
        when (c) {
            Check.NO_TITLE -> status(this, getString(R.string.no_name))
            Check.COMPLETE_RECURRING -> status(this, getString(R.string.complete_recurring))
            Check.REMINDER_AFTER_DUE, Check.REMINDER_PASSED -> Bold7Dialog(
                this, getString(if (c == Check.REMINDER_PASSED) R.string.reminder_passed else R.string.reminder_after_due),
                listOf(getString(R.string.yes), getString(R.string.no)), DialogIcon.QUESTION,
                onButton = { if (it == 0) check(e, rest) },
            ).show()
            // A warning, then saved (D: a box with OK, so that it is read before the form closes).
            Check.DUE_PASSED -> Bold7Dialog(this, getString(R.string.due_passed), listOf(getString(R.string.ok)), DialogIcon.EXCLAMATION, onButton = { check(e, rest) }).show()
        }
    }

    private fun write(e: TaskEditor) {
        saving = true
        val g = graph
        val task = e.toTask(System.currentTimeMillis(), now)
        g.io.post {
            val saved = g.tasks.save(task)
            runOnUiThread {
                saving = false
                if (saved == null) status(this, getString(R.string.not_saved)) else finish()
            }
        }
        sweep(this, "saved")
    }

    private fun delete() {
        val id = editor.id ?: return
        val g = graph
        g.io.post {
            val confirm = g.options.confirmDelete
            runOnUiThread {
                val gone = { g.io.post { val ok = g.tasks.delete(id); if (ok) g.ringer.cancel(id); runOnUiThread { if (ok) finish() else status(this, getString(R.string.not_deleted)) } }; sweep(this, "deleted") }
                if (!confirm) return@runOnUiThread gone()
                Bold7Dialog(this, getString(R.string.delete_question), listOf(getString(R.string.act_delete), getString(R.string.cancel)), DialogIcon.QUESTION, focused = 1, onButton = { b ->
                    if (b == 0) gone()
                }).show()
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val open = list != null || menu != null
        if (e.action != KeyEvent.ACTION_DOWN) return if (open) true else super.dispatchKeyEvent(e)
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
        val dateRow = listOf(dueRow, atRow).firstOrNull { it.isFocused }
        when {
            back -> leave()
            code == KeyEvent.KEYCODE_MENU -> show(fullMenu())
            // A date row moves between its date and its time (sim-274: one segment lit).
            dateRow != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) -> dateRow.segment = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}
