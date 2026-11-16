package dev.bold7.calendar

// The reminder box's queue, pure: Google Calendar stays the engine that rings;
// Bold7 only shows OS 7's box, silent, for the reminders the calendar provider holds at the
// instant it broadcast. Tested on the JVM (RemindersTest).

/** A row of CalendarContract.CalendarAlerts: one reminder of one occurrence. */
data class Alert(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val alarmTime: Long,
    val title: String,
    val location: String = "",
    val allDay: Boolean = false,
) {
    /** What identifies it across broadcasts, boots and snoozes. */
    val key: String get() = "$eventId:$begin:$alarmTime"
}

/** How long a shown reminder is remembered: past the 24 h the provider broadcasts again after a boot. */
const val SHOWN_KEEP_MS = 2 * 24 * 3600_000L

/**
 * The reminders to show for a broadcast of [alarmTime]: those the provider holds at that instant,
 * not shown before ([shown]: key to when), not in the future (a forged broadcast cannot bring one
 * early), in order of their appointment.
 */
fun toShow(held: List<Alert>, alarmTime: Long, now: Long, shown: Map<String, Long>): List<Alert> =
    held.filter { it.alarmTime == alarmTime && it.alarmTime <= now && it.key !in shown }.sortedWith(compareBy({ it.begin }, { it.title }))

/** [shown] with [alerts] added at [now] and what is older than [SHOWN_KEEP_MS] dropped. */
fun remember(shown: Map<String, Long>, alerts: List<Alert>, now: Long): Map<String, Long> =
    (shown + alerts.associate { it.key to now }).filterValues { now - it < SHOWN_KEEP_MS }

/** The box's buttons (sim-245): Open, Dismiss under the focus, Snooze (5 min.). */
enum class ReminderButton { OPEN, DISMISS, SNOOZE }

/** The button under the focus when the box opens (sim-245). */
val REMINDER_FOCUS = ReminderButton.DISMISS.ordinal

/** When a snoozed box comes back: [minutes] later (Calendar Options' Snooze, guide p. 185; 5 min., sim-245). */
fun snoozedUntil(now: Long, minutes: Int = SNOOZE_MINUTES): Long = now + minutes * 60_000L

const val SNOOZE_MINUTES = 5
