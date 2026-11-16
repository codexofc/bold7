package dev.bold7.calendar

import dev.bold7.ui.withSwitchApplication
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// New Appointment and its editing, pure: the fields of OS 7 (guide p. 181, docs/BBOS7-VISUEL.md
// §29), the rules between them and the columns written to CalendarContract.Events. Tested on the
// JVM (DraftTest).

/** Recurrence of OS 7's appointment (None, Daily, Weekly, Monthly, Yearly) and its RRULE. */
enum class Recurrence(val rule: String?) {
    NONE(null),
    DAILY("FREQ=DAILY"),
    WEEKLY("FREQ=WEEKLY"),
    MONTHLY("FREQ=MONTHLY"),
    YEARLY("FREQ=YEARLY"),
    ;

    companion object {
        /** The recurrence of an RRULE OS 7 can show; anything finer (an end, an interval, days) is OTHER, kept as it is. */
        fun of(rule: String?): Recurrence? = if (rule.isNullOrBlank()) NONE else entries.firstOrNull { it.rule == rule.trim().removeSuffix(";") }
    }
}

/** The Reminder field's values, minutes before (null: None), as far as the simulator showed them (sim-231, sim-232). */
val REMINDERS: List<Int?> = listOf(null, 0, 5, 10, 15, 30, 45, 60, 120, 180, 240, 300)

/** The Reminder field's list for an event whose reminder is [current]: one OS 7 does not offer (Google's 1 day) is kept in its place (D). */
fun reminderChoices(current: Int?): List<Int?> =
    if (current in REMINDERS) REMINDERS else (REMINDERS.filterNotNull() + current!!).sorted().let { listOf<Int?>(null) + it }

/** OS 7's default reminder (Calendar Options' Default Reminder, guide p. 185): 15 min. */
const val DEFAULT_REMINDER = 15

/**
 * An appointment being written. [id]: the event edited, null for a new one. [start] and [end] are
 * wall times in [zone], the appointment's Time Zone (sim-228; [allDay]: whole days, [end] the last
 * day's start, no zone of its own). [rule]: the RRULE kept when the
 * recurrence is one OS 7 cannot show ([recurrence] null), written back as it was.
 */
data class Draft(
    val id: Long? = null,
    val subject: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val reminder: Int? = DEFAULT_REMINDER,
    val recurrence: Recurrence? = Recurrence.NONE,
    val rule: String? = null,
    val notes: String = "",
    val calendarId: Long = 0,
    val zone: ZoneId,
) {
    companion object {
        /** New Appointment (C): on [day] at [hour], or the next half hour from [now] on today; one hour long (D); in the phone's [zone]. */
        fun new(day: LocalDate, hour: Int?, now: LocalDateTime, calendarId: Long, zone: ZoneId): Draft {
            val start = when {
                hour != null -> day.atTime(hour, 0)
                day == now.toLocalDate() -> now.withSecond(0).withNano(0).let { t -> t.plusMinutes(((30 - t.minute % 30) % 30).toLong()) }
                else -> day.atTime(DisplayOptions().startOfDay, 0)
            }
            return Draft(start = start, end = start.plusHours(1), calendarId = calendarId, zone = zone)
        }
    }

    /** The start moved: the end keeps the duration (D, as in Android's calendar editor). */
    fun withStart(s: LocalDateTime) = copy(start = s, end = s.plus(Duration.between(start, end)))

    /**
     * Another Time Zone: the start and the end keep their wall times, so their instants move, and
     * the provider's reminder with them (D: what OS 7 does to the times is not captured).
     */
    fun withZone(z: ZoneId) = copy(zone = z)

    /** The end moved: before the start, the start follows it back (D). */
    fun withEnd(e: LocalDateTime) = if (e < start) copy(start = e.minus(Duration.between(start, end)), end = e) else copy(end = e)

    fun withAllDay(on: Boolean) = if (on == allDay) this else if (on) {
        copy(allDay = true, start = start.toLocalDate().atStartOfDay(), end = maxOf(end.toLocalDate(), start.toLocalDate()).atStartOfDay())
    } else {
        val s = start.toLocalDate().atTime(DisplayOptions().startOfDay, 0)
        copy(allDay = false, start = s, end = s.plusHours(1))
    }

    /** What Save refuses: an empty subject is allowed (OS 7 saves it, D); an end before the start is not. */
    val valid: Boolean get() = !end.isBefore(start)

    /**
     * The Events columns (CalendarContract.Events names). A recurring event has DURATION and no
     * DTEND, as the provider requires; an all-day one is in UTC at midnights, its end the midnight
     * after its last day. A wall time in a summer time gap moves forward by the gap, and one that
     * happens twice is the first (java.time's rule).
     */
    fun columns(): Map<String, Any?> {
        val tz = if (allDay) ZoneOffset.UTC else zone
        val begin = (if (allDay) start.toLocalDate().atStartOfDay() else start).atZone(tz).toInstant().toEpochMilli()
        val finish = (if (allDay) end.toLocalDate().plusDays(1).atStartOfDay() else end).atZone(tz).toInstant().toEpochMilli()
        val rrule = recurrence?.rule ?: if (recurrence == null) rule else null
        return mapOf(
            "calendar_id" to calendarId,
            "title" to subject,
            "eventLocation" to location,
            "description" to notes,
            "allDay" to if (allDay) 1 else 0,
            "eventTimezone" to if (allDay) "UTC" else zone.id,
            "dtstart" to begin,
            "dtend" to if (rrule == null) finish else null,
            "duration" to if (rrule == null) null else if (allDay) "P${(finish - begin) / 86_400_000}D" else "P${(finish - begin) / 1000}S",
            "rrule" to rrule,
            "hasAlarm" to if (reminder != null) 1 else 0,
        )
    }
}

/** An event as it is stored, read for its screen and its editing (CalendarContract.Events and Reminders). */
data class Stored(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    val notes: String,
    val allDay: Boolean,
    val begin: Long,
    val end: Long,
    val rule: String?,
    val reminder: Int?,
    val organizer: String = "",
    val isOrganizer: Boolean = true,
    val selfStatus: Int = 0,
    val attendees: List<Attendee> = emptyList(),
    val readOnly: Boolean = false,
    /** Its EVENT_TIMEZONE. */
    val timezone: String? = null,
) {
    /**
     * Its draft, in its own zone (the [phone]'s when it has none Java knows); a recurring one's is
     * the series', from its first occurrence (D: OS 7 asked which, not captured).
     */
    fun draft(phone: ZoneId): Draft {
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }.takeIf { !allDay } ?: phone
        val tz = if (allDay) ZoneOffset.UTC else zone
        val s = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(begin), tz)
        val e = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(end), tz).let { if (allDay) it.minusDays(1).with(LocalTime.MIDNIGHT) else it }
        val recurrence = Recurrence.of(rule)
        return Draft(id, title, location, allDay, s, maxOf(e, s), reminder, recurrence, rule.takeIf { recurrence == null }, notes, calendarId, zone)
    }

    /**
     * A meeting someone else organises (the Hub's rule, HubSources.isInvitation): it can be accepted,
     * tentatively accepted or declined (guide p. 183), answered or not.
     */
    val invitation: Boolean get() = !isOrganizer && attendees.isNotEmpty()
}

/** An attendee of a meeting, its CalendarContract.Attendees status. */
data class Attendee(val name: String, val email: String, val status: Int)

// CalendarContract.Attendees.ATTENDEE_STATUS_*.
const val ATTENDEE_NONE = 0
const val ATTENDEE_ACCEPTED = 1
const val ATTENDEE_DECLINED = 2
const val ATTENDEE_INVITED = 3
const val ATTENDEE_TENTATIVE = 4

/**
 * An RFC 5545 duration as the provider stores it (DURATION: "P3600S", "P1D", "PT1H30M", "P1W"), in
 * ms; null if unreadable.
 */
fun durationMs(value: String?): Long? {
    val m = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(value?.trim() ?: return null) ?: return null
    val (sign, w, d, h, mi, se) = m.destructured
    if (listOf(w, d, h, mi, se).all { it.isEmpty() }) return null
    fun n(x: String) = if (x.isEmpty()) 0L else x.toLong()
    val ms = ((((n(w) * 7 + n(d)) * 24 + n(h)) * 60 + n(mi)) * 60 + n(se)) * 1000
    return if (sign == "-") -ms else ms
}

/** The form's menu actions. */
/** START_SELECTION, CUT, COPY and PASTE are core/ui's [dev.bold7.ui.TextAct], named as it names them. */
enum class FormAct { SAVE, DELETE, ACCEPT, TENTATIVE, DECLINE, CLOSE, SWITCH, START_SELECTION, CUT, COPY, PASTE }

/**
 * The form's full menu (sim-236: Switch Application, Copy, Save under the focus, Close; Copy is not
 * there yet): Switch Application, Save, then for a stored event Delete and, for an
 * invitation, Accept, Tentative and Decline (guide p. 183; D for their place), Close. A read-only
 * calendar's event has no Save nor Delete (D). The focused field's selection entries [text] (Start
 * Selection, or Cut and Copy, then Paste; guide 7.1 p. 213, B; their place D, as MemoPad's) come after
 * Save and Delete.
 */
fun formMenu(stored: Boolean, invitation: Boolean, readOnly: Boolean, text: List<dev.bold7.ui.TextAct> = emptyList()): Pair<List<dev.bold7.ui.MenuEntry<FormAct>>, Int> {
    val acts = buildList {
        if (!readOnly) add(FormAct.SAVE)
        if (stored && !readOnly) add(FormAct.DELETE)
        text.forEach { add(FormAct.valueOf(it.name)) }
        if (invitation) addAll(listOf(FormAct.ACCEPT, FormAct.TENTATIVE, FormAct.DECLINE))
        add(FormAct.CLOSE)
    }
    val full = dev.bold7.ui.Menu(acts.map { dev.bold7.ui.MenuEntry(it) }, highlighted = 0).withSwitchApplication(FormAct.SWITCH)
    return full.entries to full.highlighted!!
}
