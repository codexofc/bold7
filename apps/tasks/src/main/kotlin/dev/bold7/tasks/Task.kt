package dev.bold7.tasks

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.TextAct
import dev.bold7.ui.matchesWords
import dev.bold7.ui.withHelp
import dev.bold7.ui.withSwitchApplication
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The tasks of BBOS 7.1 and the screens over them, pure (docs/ARCHITECTURE.md §3,
// docs/BBOS7-VISUEL.md §33, §34.3): the list with "Add Task:" first (guide p. 206, B; sim-277, C), the form
// of one task with its status, priority, due date, reminder and recurrence (sim-268, sim-273, C).
// The words are the Tasks' resource of the firmware (net_rim_bb_resource__en, A).

/** A task's status, in the order of its open list (sim-270, C). [cell] is its image's place in net_rim_Task_Status-icons27x27.png (A; the order C, sim-277 to sim-280). */
enum class Status(val cell: Int) { NOT_STARTED(0), IN_PROGRESS(1), COMPLETED(2), WAITING(3), DEFERRED(4) }

/**
 * A task's priority, in the order of its open list (sim-271, C: High, Normal, Low). [cell] is its
 * image's place in net_rim_Priority-icons9x27.png (A: the blue arrow, nothing, the red "!"; sim-277,
 * C) and the number the database keeps.
 */
enum class Priority(val cell: Int) { HIGH(2), NORMAL(1), LOW(0) }

/** Recurrence (guide p. 206, B: "the due date and recurrence"): the values of the Calendar's field (D). */
enum class Recurrence {
    NONE, DAILY, WEEKLY, MONTHLY, YEARLY;

    /** [t] one occurrence later; [t] itself without a recurrence. */
    fun step(t: LocalDateTime): LocalDateTime = when (this) {
        NONE -> t
        DAILY -> t.plusDays(1)
        WEEKLY -> t.plusWeeks(1)
        MONTHLY -> t.plusMonths(1)
        YEARLY -> t.plusYears(1)
    }
}

/** A task's reminder: "Relative" to the due date (A), [Before] it by some minutes; or "By Date:" (A), [At] a time of its own. */
sealed interface Reminder {
    data class Before(val minutes: Int) : Reminder
    data class At(val time: LocalDateTime) : Reminder
}

/** Relative's list, the Calendar's Reminder without None (sim-231, sim-232, C for the Calendar, D here). */
val BEFORE_CHOICES = listOf(0, 5, 10, 15, 30, 45, 60, 120, 180, 240, 300)

/** Relative's first value: the Calendar's default reminder, 15 Min. (D). */
const val DEFAULT_BEFORE = 15

/** How the list is sorted: Tasks Options' Sort By (A: Subject, Priority, Due Date, Status). */
enum class SortBy { SUBJECT, PRIORITY, DUE_DATE, STATUS }

data class Task(
    val id: Long,
    val title: String,
    val status: Status,
    val priority: Priority,
    /** Due: None (null) or By Date: a day and a time (sim-273, C). */
    val due: LocalDateTime?,
    val notes: String,
    /** In ms. */
    val modified: Long,
    val reminder: Reminder? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    /** The reminder time already shown (or let pass, "Is this OK?"): it does not ring again. */
    val reminded: LocalDateTime? = null,
    /** When a snoozed reminder comes back (ms), or null. */
    val snoozedUntil: Long? = null,
    /**
     * Its Time Zone (sim-268, sim-319), where [due] and the reminder are wall times; null only for a
     * row whose zone cannot be read, then the phone's (the migration gives every task of
     * before version 3 the phone's zone).
     */
    val zone: ZoneId? = null,
) {
    /** When the reminder rings, on the wall clock: Relative counts back from the due date. */
    val remindAt: LocalDateTime?
        get() = when (val r = reminder) {
            null -> null
            is Reminder.Before -> due?.minusMinutes(r.minutes.toLong())
            is Reminder.At -> r.time
        }

    /** The due date as the form shows it, "Tue 17 Nov 2026 17:00" (sim-273, C); empty without one. */
    val dueLabel: String get() = due?.let(::dateTimeLabel) ?: ""

    /** [t], a wall time of this task, on the [phone]'s wall clock. */
    fun onPhone(t: LocalDateTime, phone: ZoneId): LocalDateTime = if (zone == null || zone == phone) t else t.atZone(zone).withZoneSameInstant(phone).toLocalDateTime()

    /** Open and its due time gone, [now] on the [phone]'s wall clock (drawn in red in the list, D). */
    fun overdue(now: LocalDateTime, phone: ZoneId): Boolean = status != Status.COMPLETED && due != null && onPhone(due, phone) < now
}

private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

fun dateLabel(d: LocalDate): String = DATE.format(d)
fun timeLabel(t: LocalTime): String = TIME.format(t)
fun dateTimeLabel(t: LocalDateTime): String = "${dateLabel(t.toLocalDate())} ${timeLabel(t.toLocalTime())}"

/** Due's first value once By Date: is chosen: the next day at 17:00 (sim-273 and sim-279, C, on the 16th). */
fun defaultDue(now: LocalDateTime): LocalDateTime = now.toLocalDate().plusDays(1).atTime(17, 0)

/** The due date as stored: "2030-01-15T17:00", or a day alone (older tasks), read at 17:00 (D); null when unreadable. */
fun readDue(s: String?): LocalDateTime? = s?.let {
    runCatching { LocalDateTime.parse(it) }.getOrNull() ?: runCatching { LocalDate.parse(it).atTime(17, 0) }.getOrNull()
}

/** A reminder as stored: "-15" for Relative, the time for By Date. */
fun writeReminder(r: Reminder?): String? = when (r) {
    null -> null
    is Reminder.Before -> "-${r.minutes}"
    is Reminder.At -> r.time.toString()
}

fun readReminder(s: String?): Reminder? = when {
    s == null -> null
    s.startsWith("-") -> s.drop(1).toIntOrNull()?.let { Reminder.Before(it) }
    else -> runCatching { Reminder.At(LocalDateTime.parse(s)) }.getOrNull()
}

/**
 * The list's order, Tasks Options' Sort By (A), the title breaking ties (D): Subject by title
 * (sim-278, C: "Aard" first, the completed one in its place), Priority from High, Due Date from the
 * nearest (none last), Status in the order of its list (D).
 */
fun ordered(tasks: List<Task>, sort: SortBy = SortBy.SUBJECT): List<Task> {
    val byTitle = compareBy<Task> { it.title.lowercase() }.thenBy { it.id }
    return tasks.sortedWith(
        when (sort) {
            SortBy.SUBJECT -> byTitle
            SortBy.PRIORITY -> compareBy<Task> { it.priority.ordinal }.then(byTitle)
            SortBy.DUE_DATE -> compareBy<Task> { it.due == null }.thenBy { it.due }.then(byTitle)
            SortBy.STATUS -> compareBy<Task> { it.status.ordinal }.then(byTitle)
        },
    )
}

/**
 * A task completed (Mark Completed, guide p. 207, B). A recurring one cannot stay complete (A: "Task
 * is complete. It cannot have a recurrence."): it moves to its next occurrence instead, Not Started,
 * its reminder By Date moved by as much (D).
 */
fun Task.completed(now: Long): Task {
    val d = due
    if (recurrence == Recurrence.NONE || d == null) return copy(status = Status.COMPLETED, snoozedUntil = null, modified = now)
    val r = reminder
    return copy(
        status = Status.NOT_STARTED,
        due = recurrence.step(d),
        reminder = if (r is Reminder.At) Reminder.At(recurrence.step(r.time)) else r,
        snoozedUntil = null,
        modified = now,
    )
}

sealed interface Row
data object AddTask : Row
data class Entry(val task: Task) : Row

/** The list's actions: its full menu (sim-281, sim-282, C) and short menu (D). */
enum class ListAct { NEW, OPEN, DELETE, DELETE_COMPLETED, COMPLETE, PROGRESS, HIDE, SHOW, OPTIONS, HELP, CLOSE, SWITCH }

sealed interface ListEvent {
    data class Loaded(val tasks: List<Task>, val sort: SortBy = SortBy.SUBJECT) : ListEvent
    data class Step(val down: Boolean) : ListEvent
    /** A touch on the row at [row]. */
    data class Moved(val row: Int) : ListEvent
    /** Hide Completed, or Show Completed once hidden (guide p. 207, B). */
    data object ToggleHidden : ListEvent
    /** A character typed into Search (sim-267, C), or the last one erased. */
    data class Typed(val char: Char) : ListEvent
    data object Erased : ListEvent
}

data class TaskList(
    val all: List<Task> = emptyList(),
    val hidden: Boolean = false,
    /** An index into [rows]. */
    val focus: Int = 0,
    val loaded: Boolean = false,
    val sort: SortBy = SortBy.SUBJECT,
    /** What Search holds: each word typed starts a word of a task's title or notes. */
    val typed: String = "",
) {
    val shown: List<Task> = ordered(all, sort).filter { (!hidden || it.status != Status.COMPLETED) && matchesWords(it.title + " " + it.notes, typed) }

    /** What the field shows: the first letter as a capital, as MemoPad's (sim-189, C). */
    val field: String get() = typed.replaceFirstChar { it.uppercaseChar() }

    val rows: List<Row> = listOf(AddTask) + shown.map(::Entry)

    val focused: Row? get() = rows.getOrNull(focus)

    fun reduce(e: ListEvent): TaskList = when (e) {
        is ListEvent.Loaded -> refocused(copy(all = e.tasks, sort = e.sort, loaded = true), first = !loaded)
        ListEvent.ToggleHidden -> refocused(copy(hidden = !hidden), first = false)
        is ListEvent.Step -> copy(focus = (focus + if (e.down) 1 else -1).coerceIn(0, rows.size - 1))
        is ListEvent.Moved -> copy(focus = e.row.coerceIn(0, rows.size - 1))
        is ListEvent.Typed -> retyped(typed + e.char)
        ListEvent.Erased -> if (typed.isEmpty()) this else retyped(typed.dropLast(1))
    }

    /** Escape: what is typed goes first; null leaves the screen. */
    fun back(): TaskList? = if (typed.isEmpty()) null else retyped("")

    private fun retyped(text: String): TaskList = copy(typed = text).let { it.copy(focus = it.rows.indexOfFirst { r -> r is Entry }.coerceAtLeast(0)) }
    /** [next] with the focus on the task that had it; the first task when first loaded (as Contacts'), else the nearest row. */
    private fun refocused(next: TaskList, first: Boolean): TaskList {
        val id = (focused as? Entry)?.task?.id
        val at = next.rows.indexOfFirst { it is Entry && it.task.id == id }
        return next.copy(focus = when {
            at >= 0 -> at
            first -> next.rows.indexOfFirst { it is Entry }.coerceAtLeast(0)
            else -> focus.coerceIn(0, next.rows.size - 1)
        })
    }

    private val visibility get() = if (hidden) ListAct.SHOW else ListAct.HIDE

    /**
     * The full menu in sim-281 to sim-283's order (C): Switch Application, New, Open, Delete, Delete Completed, Mark
     * Completed, Mark in Progress, Hide Completed, then Options and Close; Open focused on a task (C), New on
     * Add Task (D). Mark Completed is not offered on a completed task, Mark in Progress on one in
     * progress, Delete Completed without any completed one (D). Not there yet: Forward As, Filter,
     * Task Reminders, Help.
     */
    fun menu(): Menu<ListAct> {
        val t = (focused as? Entry)?.task
        val entries = buildList {
            add(MenuEntry(ListAct.NEW))
            if (t != null) {
                add(MenuEntry(ListAct.OPEN))
                add(MenuEntry(ListAct.DELETE))
            }
            if (all.any { it.status == Status.COMPLETED }) add(MenuEntry(ListAct.DELETE_COMPLETED))
            if (t != null && t.status != Status.COMPLETED) add(MenuEntry(ListAct.COMPLETE))
            if (t != null && t.status != Status.IN_PROGRESS) add(MenuEntry(ListAct.PROGRESS))
            add(MenuEntry(visibility))
            add(MenuEntry(ListAct.OPTIONS, separatorAbove = true))
            add(MenuEntry(ListAct.CLOSE))
        }
        return Menu(entries, highlighted = if (t != null) 1 else 0).withHelp(ListAct.HELP, before = ListAct.CLOSE).withSwitchApplication(ListAct.SWITCH)
    }

    /** The short menu on a task: Delete, Mark Completed when open, then Open by Full Menu, focused (D); none on Add Task. */
    fun shortMenu(): Menu<ListAct>? {
        val t = (focused as? Entry)?.task ?: return null
        val acts = listOfNotNull(ListAct.DELETE, ListAct.COMPLETE.takeIf { t.status != Status.COMPLETED }, ListAct.OPEN)
        return Menu(acts.map { MenuEntry(it) }, line = focus, highlighted = acts.size - 1)
    }
}

/** What saving the form must first say or ask (the Tasks' resource, A), in this order. */
enum class Check {
    /** "A Task name must be entered": refused. */
    NO_TITLE,
    /** "Task is complete. It cannot have a recurrence.": refused. */
    COMPLETE_RECURRING,
    /** "The reminder occurs past the due date. Is this OK?" */
    REMINDER_AFTER_DUE,
    /** "The reminder cannot be set because its time has passed. Is this OK?": saved, it will not ring. */
    REMINDER_PASSED,
    /** "Warning! Due date has already passed.": said, saved. */
    DUE_PASSED,
}

/** One task's form: the fields against what was stored; [phone] is the phone's zone, [zone] the task's. */
data class TaskEditor(
    val title: String,
    val status: Status,
    val priority: Priority,
    val due: LocalDateTime?,
    val reminder: Reminder?,
    val recurrence: Recurrence,
    val notes: String,
    val zone: ZoneId,
    private val phone: ZoneId,
    private val stored: Task?,
) {
    companion object {
        /** [task]'s form, or a new task's in the [phone]'s zone (sim-268: the phone's, "Casablanca (GMT)"). */
        fun of(task: Task?, phone: ZoneId): TaskEditor = TaskEditor(
            task?.title.orEmpty(), task?.status ?: Status.NOT_STARTED, task?.priority ?: Priority.NORMAL, task?.due,
            task?.reminder, task?.recurrence ?: Recurrence.NONE, task?.notes.orEmpty(), task?.zone ?: phone, phone, task,
        )
    }

    val id: Long? get() = stored?.id

    fun withTitle(text: String) = copy(title = text)
    fun withPriority(p: Priority) = copy(priority = p)
    fun withStatus(s: Status) = copy(status = s)
    fun withNotes(text: String) = copy(notes = text)
    fun withRecurrence(r: Recurrence) = copy(recurrence = r)

    /** Another Time Zone: the due date and the reminder keep their wall times, their instants move (D, as the Calendar's). */
    fun withZone(z: ZoneId) = copy(zone = z)

    /**
     * Due: None takes the Relative reminder and the recurrence with it (sim-268: no Recurrence row
     * without a due date, C; the rest D).
     */
    fun withDue(d: LocalDateTime?) = if (d != null) copy(due = d) else copy(due = null, recurrence = Recurrence.NONE, reminder = reminder.takeIf { it is Reminder.At })

    fun withReminder(r: Reminder?) = copy(reminder = r)

    /** Reminder's list: None, Relative (with a due date only, D), By Date: (A). */
    val reminderKinds: List<ReminderKind> get() = ReminderKind.entries.filter { it != ReminderKind.RELATIVE || due != null }

    val reminderKind: ReminderKind get() = when (reminder) {
        null -> ReminderKind.NONE
        is Reminder.Before -> ReminderKind.RELATIVE
        is Reminder.At -> ReminderKind.BY_DATE
    }

    /** The reminder of [kind], from what is there: Relative 15 Min., By Date: the due time, else the next hour (D). */
    fun withReminderKind(kind: ReminderKind, now: LocalDateTime): TaskEditor = if (kind == reminderKind) this else withReminder(
        when (kind) {
            ReminderKind.NONE -> null
            ReminderKind.RELATIVE -> Reminder.Before(DEFAULT_BEFORE)
            ReminderKind.BY_DATE -> Reminder.At(due ?: now.withMinute(0).withSecond(0).withNano(0).plusHours(1))
        },
    )

    private fun fields() = listOf(title, status, priority, due, reminder, recurrence, notes, zone)

    val changed: Boolean get() = fields() != of(stored, phone).fields()

    /** Nothing to keep: a new task with no title nor notes is not saved (D, as Contacts' empty contact). */
    val blank: Boolean get() = title.isBlank() && notes.isBlank()

    private val remindAt: LocalDateTime? get() = Task(0, "", status, priority, due, "", 0, reminder).remindAt

    /** The reminder or the zone changed since it was stored. */
    private val reminderMoved: Boolean get() = remindAt != stored?.remindAt || zone != (stored?.zone ?: phone)

    /** [t], a wall time of the form's zone, on the phone's wall clock. */
    private fun onPhone(t: LocalDateTime) = if (zone == phone) t else t.atZone(zone).withZoneSameInstant(phone).toLocalDateTime()

    /**
     * What saving says or asks first (A), in order, [now] on the phone's wall clock; a reminder or a
     * due date not changed is not questioned again.
     */
    fun checks(now: LocalDateTime): List<Check> = buildList {
        if (title.isBlank()) add(Check.NO_TITLE)
        if (status == Status.COMPLETED && recurrence != Recurrence.NONE) add(Check.COMPLETE_RECURRING)
        val at = remindAt
        if (at != null && reminderMoved && due != null && at > due) add(Check.REMINDER_AFTER_DUE)
        if (at != null && reminderMoved && onPhone(at) <= now && status != Status.COMPLETED) add(Check.REMINDER_PASSED)
        if (due != null && (due != stored?.due || zone != (stored?.zone ?: phone)) && onPhone(due) < now) add(Check.DUE_PASSED)
    }

    /**
     * The task to write at [now] ([wall]: the same instant on the phone's wall clock). A reminder
     * whose time has already passed is written as shown, so that it does not ring (REMINDER_PASSED's
     * "OK"); a changed reminder time or zone drops the snooze of the old one, and rings again.
     */
    fun toTask(now: Long, wall: LocalDateTime): Task {
        val base = Task(stored?.id ?: 0, title.trim(), status, priority, due, notes, now, reminder, recurrence, stored?.reminded, stored?.snoozedUntil, zone)
        val at = base.remindAt
        return when {
            stored != null && !reminderMoved -> base
            at != null && onPhone(at) <= wall -> base.copy(reminded = at, snoozedUntil = null)
            else -> base.copy(reminded = null, snoozedUntil = null)
        }
    }
}

/** Reminder's values (A: None, Relative, By Date:). */
enum class ReminderKind { NONE, RELATIVE, BY_DATE }

/** The form's menu actions; START_SELECTION, CUT, COPY and PASTE are core/ui's [TextAct], named as it names them. */
enum class TaskAct { SWITCH, SAVE, DELETE, START_SELECTION, CUT, COPY, PASTE, CLOSE }

/**
 * The form's full menu: Switch Application (sim-269, C), Save focused, Delete on a stored task (B),
 * the focused field's selection entries [text] (guide 7.1 p. 213, B; their place D, as MemoPad's),
 * Close alone at the end (sim-269).
 */
fun taskMenu(stored: Boolean, text: List<TextAct>): Menu<TaskAct> = Menu(
    buildList {
        add(MenuEntry(TaskAct.SAVE))
        if (stored) add(MenuEntry(TaskAct.DELETE))
        text.forEach { add(MenuEntry(TaskAct.valueOf(it.name))) }
        add(MenuEntry(TaskAct.CLOSE, separatorAbove = true))
    },
    highlighted = 0,
).withSwitchApplication(TaskAct.SWITCH)
