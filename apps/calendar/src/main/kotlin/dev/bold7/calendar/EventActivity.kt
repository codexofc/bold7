package dev.bold7.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
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
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.platform.zoneChoices
import dev.bold7.platform.zoneLabel
import dev.bold7.ui.useCaret
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.fit
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
import dev.bold7.ui.slate
import dev.bold7.ui.Spin
import dev.bold7.ui.SpinDialog
import dev.bold7.ui.status
import dev.bold7.ui.textAct
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * An appointment, new or opened (sim-228 to sim-236, sim-241: OS 7 opens an appointment in the
 * same form it creates it with): Subject and Location, the Time group (All Day, Start, End, Duration,
 * Time Zone, Reminder), Recurrence, the attendees of a meeting (D), Notes; the Save button at the top right and
 * Menu > Save; Escape with changes asks "Changes made!" (sim-47). The rules between the fields are
 * [Draft]'s; the provider is written on the io thread ([AndroidAgenda]).
 *
 * Not in it: Conference Call, Mark as Private, Show
 * Time as, Copy, Switch Application, Invite Attendee; the date fields' five segments are two here
 * (the date and the time, D).
 */
class EventActivity : Activity() {
    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_BEGIN = "begin"
        private const val EXTRA_END = "end"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_SUBJECT = "subject"

        fun open(context: Context, id: Long, begin: Long, end: Long): Intent =
            Intent(context, EventActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_BEGIN, begin).putExtra(EXTRA_END, end)

        /** New Appointment on [day] at [hour] (null: the next half hour today, the start of day elsewhere), [subject] already typed (Quick Entry). */
        fun new(context: Context, day: LocalDate, hour: Int?, subject: String = ""): Intent =
            Intent(context, EventActivity::class.java).putExtra(EXTRA_DAY, day.toEpochDay()).putExtra(EXTRA_HOUR, hour ?: Int.MIN_VALUE).putExtra(EXTRA_SUBJECT, subject)
    }

    private val graph get() = application.graph
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var draft: Draft? = null
    private var original: Draft? = null
    private var stored: Stored? = null
    private var occurrenceBegin = 0L
    private var busy = false

    private lateinit var scroll: ScrollView
    private lateinit var subject: EditText
    private lateinit var location: EditText
    private lateinit var allDay: CheckBoxView
    private lateinit var start: DateTimeRow
    private lateinit var end: DateTimeRow
    private lateinit var duration: TextRow
    private lateinit var timeZone: ChoiceView
    private lateinit var reminder: ChoiceView
    private lateinit var recurrence: ChoiceView
    private lateinit var recurrenceLine: TextView
    private lateinit var attendees: LinearLayout
    private lateinit var notes: EditText
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<FormAct>
    private var menu: Menu<FormAct>? = null
    private val selection = FieldSelection()

    /** The open list: which field ([timeZone], [reminder] or [recurrence]), its values and their state. */
    private var list: Triple<ChoiceView, List<Any?>, ChoiceList>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        val id = intent.getLongExtra(EXTRA_ID, 0)
        occurrenceBegin = intent.getLongExtra(EXTRA_BEGIN, 0)
        graph.io.post {
            val s = if (id > 0) graph.agenda.event(id) else null
            val calendar = if (s == null) graph.agenda.defaultCalendar() ?: 0 else s.calendarId
            val d = s?.draft(zone) ?: Draft.new(
                LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay())),
                intent.getIntExtra(EXTRA_HOUR, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE && it >= 0 },
                LocalDateTime.now(), calendar, zone,
            ).let { n ->
                // The all-day row: a new all-day appointment (D).
                if (intent.getIntExtra(EXTRA_HOUR, 0) == ALL_DAY) n.withAllDay(true) else n
            }.copy(subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty())
            mainExecutor.execute {
                if (id > 0 && s == null) return@execute finish()
                stored = s
                original = d
                draft = d
                fill(d)
                bindAttendees(s)
                if (s?.readOnly == true) listOf(subject, location, notes, allDay, start, end, timeZone, reminder, recurrence).forEach { it.isEnabled = false }
                subject.requestFocus()
                subject.setSelection(subject.text.length)
            }
        }
    }

    // Views ----------------------------------------------------------------------------------------

    private fun text(size: Int, bold: Boolean = false, color: Int = ListColors.Title) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
        typeface = slate(if (bold) Typography.BOLD else Typography.REGULAR)
        setTextColor(color)
    }

    private fun edit(single: Boolean) = EditText(this).apply {
        background = null
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        typeface = slate(Typography.REGULAR)
        setTextColor(ListColors.Title)
        // The caret's block starts 26 px over the baseline, a pixel over the line's top: TextView
        // clips at its own top, which this pixel keeps whole (9 x 32 measured on the Q25).
        setPadding(0, 1, 0, 0)
        useCaret()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or if (single) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (single) isSingleLine = true else minLines = 2
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = edited()
        })
    }

    /** A white box with its #C6C6C6 border, 5 px from the others (sim-228). */
    private fun box(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ListColors.Background)
            setStroke(1, Look.BOX_BORDER)
        }
        setPadding(1, 1, 1, 1)
        children.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Look.BOX_MARGIN, 0, Look.BOX_MARGIN, Look.BOX_MARGIN)
        }
    }

    /** "Subject:" (bold) or "Location:" and its field on the same line (sim-228). */
    private fun labelled(label: Int, bold: Boolean, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(5, 6, 5, 6)
        addView(text(Typography.TEXT, bold).apply { text = getString(label) + " " })
        addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A group's title, grey bold 23 over its #D4D4D4 line (sim-228: "Time"). */
    private fun groupTitle(label: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(Typography.SUBTITLE, bold = true, color = Look.GROUP_TITLE).apply {
            text = getString(label)
            setPadding(5, 8, 5, 8)
        })
        addView(View(context).apply { setBackgroundColor(Look.GROUP_LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    /** A core/ui row that draws the trackpad's focus itself, made focusable and clickable. */
    private fun <V : View> focusable(v: V, lit: (V, Boolean) -> Unit, click: () -> Unit) = v.apply {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, has -> lit(this, has) }
        setOnClickListener { if (isEnabled) click() }
    }

    private fun build() {
        subject = edit(single = true)
        location = edit(single = true)
        notes = edit(single = false)
        allDay = focusable(CheckBoxView(this).apply { boxFirst = true }, { v, has -> v.bind(getString(R.string.all_day), draft?.allDay == true, has, v.isEnabled) }) {
            update(draft?.let { it.withAllDay(!it.allDay) })
        }
        start = focusable(DateTimeRow(this, getString(R.string.start)), { v, has -> v.lit = has }) { pick(start) }
        end = focusable(DateTimeRow(this, getString(R.string.end)), { v, has -> v.lit = has }) { pick(end) }
        duration = TextRow(this, getString(R.string.duration))
        timeZone = focusable(ChoiceView(this), { _, _ -> fillChoices() }) { openList(timeZone) }
        reminder = focusable(ChoiceView(this), { _, _ -> fillChoices() }) { openList(reminder) }
        recurrence = focusable(ChoiceView(this), { _, _ -> fillChoices() }) { openList(recurrence) }
        recurrenceLine = text(Typography.TEXT).apply { setPadding(9, 0, 9, 10) }
        attendees = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.FORM)
            addView(SaveBar(this@EventActivity) { save() }.also { bar ->
                bar.isFocusable = true
                bar.defaultFocusHighlightEnabled = false
                bar.setOnFocusChangeListener { _, _ -> bar.invalidate() }
            })
            addView(box(labelled(R.string.subject, true, subject), labelled(R.string.location, false, location)))
            addView(box(groupTitle(R.string.group_time), allDay, start, end, duration, timeZone, reminder))
            addView(box(recurrence, recurrenceLine))
            addView(attendees)
            addView(box(groupTitle(R.string.group_notes), notes.apply { setPadding(9, 8, 9, 8) }))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(match, wrap))
        }
        listView = ChoiceListView(this, onChoose = { i -> chooseList(i) }, onCancel = { closeList() })
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = { i -> menu?.let { chosen(it.entries[i].action) } }, onExpand = { menu?.let { showMenu(it.expand()) } }, onClose = { showMenu(null) })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Look.FORM)
            addView(scroll, FrameLayout.LayoutParams(match, match))
            addView(listView, FrameLayout.LayoutParams(match, match))
            addView(menuView, FrameLayout.LayoutParams(match, match))
        })
        // The form has no bar at all on the 9900.
        window.hideStatusBar()
    }

    private fun fill(d: Draft) {
        if (subject.text.toString() != d.subject) subject.setText(d.subject)
        if (location.text.toString() != d.location) location.setText(d.location)
        if (notes.text.toString() != d.notes) notes.setText(d.notes)
        allDay.bind(getString(R.string.all_day), d.allDay, allDay.isFocused, allDay.isEnabled)
        start.value(d.start, d.allDay)
        end.value(d.end, d.allDay)
        duration.value = Texts.duration(ChronoUnit.MINUTES.between(d.start, d.end) + if (d.allDay) 24 * 60 else 0)
        fillChoices()
    }

    private fun fillChoices() {
        val d = draft ?: return
        // sim-228: the row under Duration; an all-day appointment has none (D).
        timeZone.visibility = if (d.allDay) View.GONE else View.VISIBLE
        timeZone.bind(getString(R.string.time_zone), zoneLabel(d.zone.id, d.zoneInstant), timeZone.isFocused)
        reminder.bind(getString(R.string.reminder), Texts.reminder(d.reminder), reminder.isFocused)
        val (value, line) = Texts.recurrence(d.recurrence)
        recurrence.bind(getString(R.string.recurrence), value, recurrence.isFocused)
        recurrenceLine.text = line
    }

    /** A meeting's organizer and attendees with their answers (D: OS 7 lists them in Accepted, Declined fields, guide p. 184). */
    private fun bindAttendees(s: Stored?) {
        attendees.removeAllViews()
        if (s == null || s.attendees.isEmpty()) return
        val rows = mutableListOf<View>(groupTitle(R.string.group_attendees))
        if (s.organizer.isNotEmpty()) rows += text(Typography.TEXT).apply { text = getString(R.string.organizer, s.organizer); setPadding(9, 8, 9, 8) }
        for (a in s.attendees) {
            val answer = when (a.status) {
                ATTENDEE_ACCEPTED -> R.string.attendee_accepted
                ATTENDEE_DECLINED -> R.string.attendee_declined
                ATTENDEE_TENTATIVE -> R.string.attendee_tentative
                else -> R.string.attendee_invited
            }
            rows += text(Typography.TEXT).apply { text = "${a.name.ifEmpty { a.email }}: ${getString(answer)}"; setPadding(9, 8, 9, 8) }
        }
        attendees.addView(box(*rows.toTypedArray()))
    }

    private fun edited() {
        val d = draft ?: return
        draft = d.copy(subject = subject.text.toString(), location = location.text.toString(), notes = notes.text.toString())
    }

    private fun update(d: Draft?) {
        if (d == null) return
        draft = d
        fill(d)
    }

    /** The date or the time of a Start or End row, in its box (sim-234, sim-248). */
    private fun pick(row: DateTimeRow) {
        val d = draft ?: return
        val at = if (row === start) d.start else d.end
        val set: (LocalDateTime) -> Unit = { t -> update(if (row === start) d.withStart(t) else d.withEnd(t)) }
        if (row.segment == 0 || d.allDay) SpinDialog(this, Spin.date(at.toLocalDate())) { day -> set(at.with(day)) }.show()
        else SpinDialog(this, Spin.time(at.toLocalTime())) { t -> set(at.with(t)) }.show()
    }

    // Choice lists (sim-231, sim-232) ---------------------------------------------------------------

    private fun openList(field: ChoiceView) {
        val d = draft ?: return
        val values: List<Any?> = when {
            field === timeZone -> zoneChoices(d.zone.id, d.zoneInstant)
            field === reminder -> reminderChoices(d.reminder)
            else -> Recurrence.entries.toList() + if (d.recurrence == null) listOf(null) else emptyList()
        }
        val labels = values.map {
            when {
                field === timeZone -> zoneLabel(it as String, d.zoneInstant)
                field === reminder -> Texts.reminder(it as Int?)
                else -> Texts.recurrence(it as Recurrence?).first
            }
        }
        val chosen = values.indexOf(if (field === timeZone) d.zone.id else if (field === reminder) d.reminder else d.recurrence).coerceAtLeast(0)
        val l = ChoiceList(values.size, chosen)
        list = Triple(field, values, l)
        val at = IntArray(2).also { field.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        // The zones' names are long: their list is as wide as the field (D), the others keep OS 7's.
        val width = if (field === timeZone) field.width - field.fieldLeft - dev.bold7.design.ListDimens.CHECK_RIGHT else dev.bold7.design.ListDimens.CHOICE_LIST_WIDTH
        listView.open(labels, l, at[0] - origin[0] + field.fieldLeft, at[1] - origin[1], width = width)
    }

    private fun chooseList(i: Int) {
        val (field, values, _) = list ?: return
        val d = draft ?: return
        closeList()
        update(
            when {
                field === timeZone -> d.withZone(ZoneId.of(values[i] as String))
                field === reminder -> d.copy(reminder = values[i] as Int?)
                else -> d.copy(recurrence = values[i] as Recurrence?)
            },
        )
    }

    private fun closeList() {
        list = null
        listView.show(null)
    }

    // Menu, Save, Delete ---------------------------------------------------------------------------

    private fun label(a: FormAct) = getString(
        when (a) {
            FormAct.SAVE -> R.string.act_save
            FormAct.DELETE -> R.string.act_delete
            FormAct.ACCEPT -> R.string.act_accept
            FormAct.TENTATIVE -> R.string.act_tentative
            FormAct.DECLINE -> R.string.act_decline
            FormAct.CLOSE -> R.string.act_close
            FormAct.SWITCH -> R.string.act_switch_application
            FormAct.START_SELECTION, FormAct.CUT, FormAct.COPY, FormAct.PASTE -> FieldSelection.label(a.textAct()!!)
        },
    )

    private fun showMenu(m: Menu<FormAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(a: FormAct) {
        showMenu(null)
        val s = stored
        when (a) {
            FormAct.SAVE -> save()
            FormAct.DELETE -> if (s != null) delete(s)
            FormAct.ACCEPT -> answer(ATTENDEE_ACCEPTED)
            FormAct.TENTATIVE -> answer(ATTENDEE_TENTATIVE)
            FormAct.DECLINE -> answer(ATTENDEE_DECLINED)
            FormAct.CLOSE -> leave()
            FormAct.SWITCH -> SwitchApplication.start(this)
            FormAct.START_SELECTION, FormAct.CUT, FormAct.COPY, FormAct.PASTE -> (currentFocus as? EditText)?.let { selection.perform(it, a.textAct()!!) }
        }
    }

    private fun save() {
        val d = draft ?: return
        if (busy || stored?.readOnly == true) return
        if (!d.valid) return status(this, getString(R.string.end_before_start))
        busy = true
        graph.io.post {
            val id = graph.agenda.save(d)
            mainExecutor.execute {
                busy = false
                if (id == null) status(this, getString(R.string.refused)) else finish()
            }
        }
    }

    /** "Delete appointment?" (sim-246), Cancel under the focus; a recurring one asks which (D). */
    private fun delete(s: Stored) {
        val recurring = s.rule != null
        val buttons = if (recurring) listOf(R.string.delete_all, R.string.delete_one, R.string.cancel) else listOf(R.string.act_delete, R.string.cancel)
        Bold7Dialog(
            this, getString(if (recurring) R.string.delete_recurring else R.string.delete_question), buttons.map(::getString), DialogIcon.QUESTION, buttons.size - 1,
            onButton = { i ->
                if (i == buttons.size - 1) return@Bold7Dialog
                graph.io.post {
                    val ok = if (recurring && i == 1) graph.agenda.deleteOccurrence(s.id, occurrenceBegin) else graph.agenda.delete(s.id)
                    mainExecutor.execute { if (ok) finish() else status(this, getString(R.string.refused)) }
                }
            },
        ).show()
    }

    private fun answer(status: Int) {
        val s = stored ?: return
        graph.io.post {
            val ok = graph.agenda.answer(s.id, status)
            mainExecutor.execute { status(this, getString(if (ok) R.string.answer_sent else R.string.refused)) }
        }
    }

    /** Escape or Close: "Changes made!" Save, Discard, Cancel when something changed (sim-47), else out. */
    private fun leave() {
        edited()
        if (draft == original || stored?.readOnly == true) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(R.string.act_save, R.string.discard, R.string.cancel).map(::getString), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> finish()
            }
        }).show()
    }

    // Keys -----------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val open = list
        if (open != null) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val next = open.third.step(down = code == KeyEvent.KEYCODE_DPAD_DOWN)
                    list = open.copy(third = next)
                    listView.show(next)
                }
                click -> chooseList(open.third.chosen)
                back -> closeList()
            }
            return true
        }
        val m = menu
        if (m != null) {
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else if (back) MenuKey.BACK else return true
                },
            )) {
                is MenuResult.Show -> showMenu(r.menu)
                is MenuResult.Choose -> chosen(m.entries[r.index].action)
                MenuResult.Close -> showMenu(null)
            }
            return true
        }
        // Start Selection: the trackpad extends the selection until Cut, Copy or Escape.
        if (selection.key(e)) return true
        when {
            code == KeyEvent.KEYCODE_MENU -> {
                val s = stored
                val (entries, focus) = formMenu(s != null, s?.invitation == true, s?.readOnly == true, (currentFocus as? EditText)?.let(selection::acts).orEmpty())
                showMenu(Menu(entries, highlighted = focus))
            }
            back -> leave()
            // The date rows move between their date and their time (sim-233: one segment lit).
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && (start.isFocused || end.isFocused) -> {
                val row = if (start.isFocused) start else end
                row.segment = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0
            }
            // Enter in a one-line field saves, as Menu > Save (D).
            code == KeyEvent.KEYCODE_ENTER && (subject.isFocused || location.isFocused) -> save()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}

/** The instant whose offsets the Time Zone row and its list show: the appointment's start (D). */
private val Draft.zoneInstant: java.time.Instant get() = start.atZone(zone).toInstant()

/** A Start or End row showing [t] (sim-228); an all-day one has no time. */
private fun DateTimeRow.value(t: LocalDateTime, allDay: Boolean) = value(Texts.shortDay(t.toLocalDate()), if (allDay) "" else Texts.time(t.toLocalTime()))

/** A row with a value that is not edited: Duration (sim-228). */
private class TextRow(context: Context, private val label: String) : View(context) {
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val right = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
    var value = ""
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dev.bold7.design.ListDimens.CHECK_ROW)

    override fun onDraw(canvas: Canvas) {
        val y = dev.bold7.design.ListDimens.CHECK_BASELINE.toFloat()
        val x = dev.bold7.design.ListDimens.TEXT_X.toFloat()
        val shownValue = right.fit(value, (width - 8f - x - text.measureText(label) - x).coerceAtLeast(0f))
        canvas.drawText(text.fit(label, width - 8f - x - right.measureText(shownValue) - x), x, y, text)
        canvas.drawText(shownValue, width - 8f, y, right)
    }
}

/** The grey band at the top of the form and its Save button, border_button.png and save.png (sim-228). */
private class SaveBar(context: Context, private val onSave: () -> Unit) : View(context) {
    private val button = themeImage(context, "ui_button")
    private val buttonFocus = themeImage(context, "ui_button_focus")
    private val icon = themeImage(context, "cal_save")

    init {
        setBackgroundColor(Look.FORM)
        setOnClickListener { onSave() }
        contentDescription = context.getString(R.string.act_save)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Look.FORM_TOP)

    override fun onDraw(canvas: Canvas) {
        val r = width - 2
        val l = r - Look.SAVE_WIDTH
        val t = 5
        (if (isFocused) buttonFocus else button).apply { setBounds(l, t, r, t + Look.SAVE_HEIGHT) }.draw(canvas)
        val x = (l + r - icon.intrinsicWidth) / 2
        val y = t + (Look.SAVE_HEIGHT - icon.intrinsicHeight) / 2
        icon.setBounds(x, y, x + icon.intrinsicWidth, y + icon.intrinsicHeight)
        icon.draw(canvas)
    }
}
