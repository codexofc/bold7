package dev.bold7.tasks

import android.content.Context

fun Context.label(s: Status): String = getString(when (s) {
    Status.NOT_STARTED -> R.string.status_not_started
    Status.IN_PROGRESS -> R.string.status_in_progress
    Status.COMPLETED -> R.string.status_completed
    Status.WAITING -> R.string.status_waiting
    Status.DEFERRED -> R.string.status_deferred
})

fun Context.label(p: Priority): String = getString(when (p) {
    Priority.LOW -> R.string.priority_low
    Priority.NORMAL -> R.string.priority_normal
    Priority.HIGH -> R.string.priority_high
})

fun Context.label(k: ReminderKind): String = getString(when (k) {
    ReminderKind.NONE -> R.string.none
    ReminderKind.RELATIVE -> R.string.relative
    ReminderKind.BY_DATE -> R.string.by_date
})

fun Context.label(r: Recurrence): String = getString(when (r) {
    Recurrence.NONE -> R.string.none
    Recurrence.DAILY -> R.string.daily
    Recurrence.WEEKLY -> R.string.weekly
    Recurrence.MONTHLY -> R.string.monthly
    Recurrence.YEARLY -> R.string.yearly
})

fun Context.label(s: SortBy): String = getString(when (s) {
    SortBy.SUBJECT -> R.string.sort_subject
    SortBy.PRIORITY -> R.string.sort_priority
    SortBy.DUE_DATE -> R.string.sort_due_date
    SortBy.STATUS -> R.string.sort_status
})

/** Snooze's values: "None", "5 Min." (the Clock's, sim-175). */
fun Context.snoozeLabel(minutes: Int): String = if (minutes == 0) getString(R.string.none) else getString(R.string.minutes, minutes)
