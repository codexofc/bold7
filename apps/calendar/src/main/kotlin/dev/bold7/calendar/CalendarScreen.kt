package dev.bold7.calendar

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withHelp
import dev.bold7.ui.withSwitchApplication
import java.time.LocalDate

// The Calendar's main screen, pure: its view, the day and the trackpad's focus, the keys of the
// guide (p. 58: C, A, D, W, M, T, G, Space and Shift+Space), the click and the menu (sim-221,
// sim-222, sim-240). Tested on the JVM (CalendarScreenTest).

enum class CalView { DAY, WEEK, MONTH, AGENDA }

/** The keys the screen takes, already read from Android's (CalendarActivity). */
enum class CalKey { UP, DOWN, LEFT, RIGHT, NEXT, PREVIOUS }

/**
 * The screen: [view] around [day], the focused day. [hour]: the focused hour of the Day and Week
 * views ([ALL_DAY] for their all-day row); [row]: the focused row of the Agenda view.
 */
data class CalendarScreen(val view: CalView, val day: LocalDate, val hour: Int = DisplayOptions().startOfDay, val row: Int = 0) {
    /**
     * A key: the trackpad moves the focus, Space ([CalKey.NEXT]) and Shift+Space ([CalKey.PREVIOUS])
     * move a day, a week or a month (guide p. 58). [rows]: how many rows the Agenda view shows.
     */
    fun key(key: CalKey, rows: Int = 0): CalendarScreen = when (view) {
        CalView.DAY, CalView.WEEK -> when (key) {
            CalKey.UP -> copy(hour = (hour - 1).coerceAtLeast(ALL_DAY))
            CalKey.DOWN -> copy(hour = (hour + 1).coerceAtMost(23))
            CalKey.LEFT -> copy(day = day.minusDays(1))
            CalKey.RIGHT -> copy(day = day.plusDays(1))
            CalKey.PREVIOUS -> copy(day = if (view == CalView.DAY) day.minusDays(1) else day.minusWeeks(1))
            CalKey.NEXT -> copy(day = if (view == CalView.DAY) day.plusDays(1) else day.plusWeeks(1))
        }
        CalView.MONTH -> when (key) {
            CalKey.UP -> copy(day = day.minusWeeks(1))
            CalKey.DOWN -> copy(day = day.plusWeeks(1))
            CalKey.LEFT -> copy(day = day.minusDays(1))
            CalKey.RIGHT -> copy(day = day.plusDays(1))
            CalKey.PREVIOUS -> copy(day = day.minusMonths(1))
            CalKey.NEXT -> copy(day = day.plusMonths(1))
        }
        // Agenda: Space is not in the guide's list for it (p. 58); the trackpad walks its list.
        CalView.AGENDA -> when (key) {
            CalKey.UP, CalKey.LEFT -> copy(row = (row - 1).coerceAtLeast(0))
            CalKey.DOWN, CalKey.RIGHT -> copy(row = (row + 1).coerceAtMost((rows - 1).coerceAtLeast(0)))
            else -> this
        }
    }

    /** Another view on the same day (Menu > View, or D, W, M, A), the hour kept; the Agenda from its top. */
    fun show(view: CalView) = if (view == this.view) this else copy(view = view, row = 0)

    /** Go To Today (T) or Go To Date (G): the same view on [date], the Agenda from its top. */
    fun goTo(date: LocalDate) = copy(day = date, row = 0)

    /** The Agenda's focus moved to [day]'s header when the list moves its start (the day changed elsewhere). */
    fun agendaDay(rows: List<AgendaRow>): LocalDate = rows.getOrNull(row)?.day ?: day
}

/** What the trackpad's click does on the focus: open an appointment, schedule one, or show a day. */
sealed interface Click {
    data class Open(val occurrence: Occurrence) : Click
    data class New(val day: LocalDate, val hour: Int?) : Click
    data class ShowDay(val day: LocalDate) : Click
}

/**
 * The click: the Day and Week views open the appointment of the focused hour (sim-240: Open is the
 * menu's default there), or schedule one at it (D; the all-day row, an all-day one); the Month view
 * shows the focused day in the Day view (D); the Agenda view opens the focused appointment, or shows
 * the focused day in the Day view (D). [inHour]: the appointments of the focused day and hour.
 */
fun CalendarScreen.click(agenda: List<AgendaRow>, inHour: List<Occurrence>): Click = when (view) {
    CalView.DAY, CalView.WEEK -> inHour.firstOrNull()?.let { Click.Open(it) } ?: Click.New(day, hour)
    CalView.MONTH -> Click.ShowDay(day)
    CalView.AGENDA -> when (val r = agenda.getOrNull(row)) {
        is AgendaRow.Event -> Click.Open(r.occurrence)
        null -> Click.ShowDay(day)
        else -> Click.ShowDay(r.day)
    }
}

/** The focused appointment (Open, Delete, the details panel), or null. */
fun CalendarScreen.focused(agenda: List<AgendaRow>, inHour: List<Occurrence>): Occurrence? =
    (click(agenda, inHour) as? Click.Open)?.occurrence

/** The menu's actions; New Alarm, New Task, Search, Calendar Reminders, Options and Help are not there yet. */
enum class Act { NEW, NEW_ALARM, NEW_TASK, OPEN, DELETE, ACCEPT, TENTATIVE, DECLINE, TODAY, GO_TO_DATE, VIEW, DAY, WEEK, MONTH, AGENDA, HELP, CLOSE, SWITCH }

/**
 * The full menu (sim-221, sim-222, sim-240): Switch Application | New Appointment, New Alarm, New Task | Open, Delete when an appointment is
 * focused | Go to Today, Go to Date | View | Close; under the focus Open on an appointment, New
 * Appointment otherwise. An invitation adds Accept, Tentative and Decline after Delete (guide p. 183;
 * D for the place: OS 7 answered in the invitation's message).
 */
fun menu(focused: Occurrence?): Pair<List<MenuEntry<Act>>, Int> {
    val entries = buildList {
        add(MenuEntry(Act.NEW))
        add(MenuEntry(Act.NEW_ALARM))
        add(MenuEntry(Act.NEW_TASK))
        if (focused != null) {
            add(MenuEntry(Act.OPEN, separatorAbove = true))
            add(MenuEntry(Act.DELETE))
            if (focused.invitation) listOf(Act.ACCEPT, Act.TENTATIVE, Act.DECLINE).forEach { add(MenuEntry(it)) }
        }
        add(MenuEntry(Act.TODAY, separatorAbove = true))
        add(MenuEntry(Act.GO_TO_DATE))
        add(MenuEntry(Act.VIEW, separatorAbove = true, submenu = true))
        add(MenuEntry(Act.CLOSE, separatorAbove = true))
    }
    val full = Menu(entries, highlighted = entries.indexOfFirst { it.action == if (focused != null) Act.OPEN else Act.NEW }).withHelp(Act.HELP, before = Act.CLOSE).withSwitchApplication(Act.SWITCH)
    return full.entries to full.highlighted!!
}

/** View's submenu (sim-223): the other views, in the order Day, Week, Month, Agenda. */
fun viewMenu(current: CalView): List<MenuEntry<Act>> =
    listOf(CalView.DAY to Act.DAY, CalView.WEEK to Act.WEEK, CalView.MONTH to Act.MONTH, CalView.AGENDA to Act.AGENDA)
        .filter { it.first != current }.map { MenuEntry(it.second) }

/** The guide's letters (p. 58): C schedules, A, D, W, M change the view, T today, G a date. */
enum class Letter { NEW, AGENDA, DAY, WEEK, MONTH, TODAY, GO_TO_DATE }

val LETTERS = mapOf('c' to Letter.NEW, 'a' to Letter.AGENDA, 'd' to Letter.DAY, 'w' to Letter.WEEK, 'm' to Letter.MONTH, 't' to Letter.TODAY, 'g' to Letter.GO_TO_DATE)

/**
 * Whether a letter typed in [view] starts Quick Entry (sim-227): in the Day view, on by default (the
 * guide's shortcuts there need it cleared, p. 58), every letter or digit starts the subject of a new
 * appointment at the focused hour; elsewhere the letters are the guide's shortcuts ([LETTERS]).
 */
fun quickEntry(view: CalView, c: Char): Boolean = view == CalView.DAY && c.isLetterOrDigit()

/** Quick Entry's subject after Backspace; null once nothing is left, which closes the box. */
fun quickBackspace(subject: String): String? = subject.dropLast(1).ifEmpty { null }
