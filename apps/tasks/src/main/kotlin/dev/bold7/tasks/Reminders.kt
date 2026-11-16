package dev.bold7.tasks

import java.time.ZoneId

// When the tasks' reminders ring, pure. The
// reminder is a wall clock time of the task's Time Zone (the phone's for a task without
// one), read at each computation; Relative counts back from the due date's instant, so that a
// summer time change between them does not move it. One alarm of AlarmManager waits for the nearest ([nextRing]); when it
// comes, or at a boot, an update, a change of time or an opening of the app, every reminder whose
// time has come and that was not shown rings ([ringing]): none is lost, a late one rings late.
// Tested on the JVM (RemindersTest).

/** The instant [t]'s reminder rings (ms), or null: none, completed, or already shown and not snoozed. [phone]: the zone of a task without its own. */
fun ringAt(t: Task, phone: ZoneId): Long? {
    if (t.status == Status.COMPLETED) return null
    t.snoozedUntil?.let { return it }
    val at = t.remindAt ?: return null
    if (at == t.reminded) return null
    val zone = t.zone ?: phone
    val r = t.reminder
    val due = t.due
    if (r is Reminder.Before && due != null) return due.atZone(zone).toInstant().toEpochMilli() - r.minutes * 60_000L
    return at.atZone(zone).toInstant().toEpochMilli()
}

/** The tasks whose reminder rings at [now]: its time has come (perhaps long ago, the phone off), in the order of their times. */
fun ringing(tasks: List<Task>, now: Long, zone: ZoneId): List<Task> =
    tasks.mapNotNull { t -> ringAt(t, zone)?.takeIf { it <= now }?.let { t to it } }.sortedBy { it.second }.map { it.first }

/** The next reminder after [now] (ms), or null: no alarm at all then. */
fun nextRing(tasks: List<Task>, now: Long, zone: ZoneId): Long? = tasks.mapNotNull { ringAt(it, zone) }.filter { it > now }.minOrNull()

/** [t] once its reminder has rung: shown, its snooze spent. */
fun Task.shown(): Task = copy(reminded = remindAt, snoozedUntil = null)

/** [t]'s reminder back in [minutes] (Tasks Options' Snooze). */
fun Task.snoozed(now: Long, minutes: Int): Task = copy(snoozedUntil = now + minutes * 60_000L)

/** Tasks Options' Snooze list, top to bottom as the Clock's (sim-175, C there; D here): minutes, None last. */
val SNOOZE_LIST = listOf(30, 15, 10, 5, 1, 0)

/** The box's Snooze by default: 5 min. (as the Calendar's, sim-245, D for Tasks). */
const val DEFAULT_SNOOZE = 5

/** Tasks Options (A: "Tasks Options", Sort By; the guide p. 211's Confirm Delete, B; Snooze, A: SNOOZE_CHOICES in the Tasks' .cod). */
data class TaskOptions(val sort: SortBy = SortBy.SUBJECT, val snooze: Int = DEFAULT_SNOOZE, val confirmDelete: Boolean = true)
