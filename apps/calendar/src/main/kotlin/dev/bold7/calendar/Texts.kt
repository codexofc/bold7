package dev.bold7.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Calendar's dates and times as the simulator writes them (English of the 9900, 24 h like the
// Q25), pure. Tested on the JVM (TextsTest).
object Texts {
    private val en = Locale.ENGLISH
    private val dayTitle = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", en)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM yyyy", en)
    private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", en)
    private val reminderDate = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", en)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", en)

    /** The Day view's band: "Friday 13 November 2026" (sim-220). */
    fun dayTitle(d: LocalDate): String = dayTitle.format(d)

    /** The Agenda's headers (sim-226) and the form's dates without the time: "Fri 13 Nov 2026". */
    fun shortDay(d: LocalDate): String = shortDay.format(d)

    /** The form's Start and End: "Fri 13 Nov 2026 18:00" (sim-228); an all-day one has no time (D). */
    fun dateTime(t: LocalDateTime, allDay: Boolean): String = if (allDay) shortDay(t.toLocalDate()) else shortDay(t.toLocalDate()) + " " + hm.format(t)

    /** The Month view's band: "November 2026" (sim-225). */
    fun monthTitle(d: LocalDate): String = monthTitle.format(d)

    /** The Week view's corner: "NOV" over "2026" (sim-224). */
    fun weekCorner(d: LocalDate): Pair<String, String> = DateTimeFormatter.ofPattern("MMM", en).format(d).uppercase(en) to "${d.year}"

    /** A weekday in capitals: "MON" (sim-224, sim-225). */
    fun weekday(d: LocalDate): String = DateTimeFormatter.ofPattern("EEE", en).format(d).uppercase(en)

    fun time(t: LocalTime): String = hm.format(t)

    /** "18:30 - 19:30" (sim-237); an all-day one: "All Day" (D). */
    fun span(begin: LocalDateTime, end: LocalDateTime, allDay: Boolean): String = if (allDay) ALL_DAY_TEXT else "${hm.format(begin)} - ${hm.format(end)}"

    /** The reminder box's two lines: "13 Nov 2026 18:30" and "13 Nov 2026 19:30" (sim-245); an all-day one: its dates (D). */
    fun reminderLines(begin: Long, end: Long, allDay: Boolean, zone: ZoneId): List<String> {
        val tz = if (allDay) ZoneOffset.UTC else zone
        val b = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), tz)
        val e = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), tz)
        if (allDay) return listOf(DateTimeFormatter.ofPattern("d MMM yyyy", en).format(b), ALL_DAY_TEXT)
        return listOf(reminderDate.format(b), reminderDate.format(e))
    }

    /** The box's first line: "Team Meeting (Office)" (sim-245), the place in brackets as in the Day view (sim-237). */
    fun subject(title: String, location: String, none: String): String {
        val t = title.ifEmpty { none }
        return if (location.isEmpty()) t else "$t ($location)"
    }

    /** Duration: "1 Hour 0 Mins" (sim-228); days in front when there are (D). */
    fun duration(minutes: Long): String {
        val d = minutes / (24 * 60)
        val h = minutes / 60 % 24
        val m = minutes % 60
        val hours = "$h ${if (h == 1L) "Hour" else "Hours"} $m ${if (m == 1L) "Min" else "Mins"}"
        return if (d == 0L) hours else "$d ${if (d == 1L) "Day" else "Days"} $hours"
    }

    /** The Reminder field: "None", "0 Min.", "15 Min.", "1 Hour", "5 Hours" (sim-231, sim-232); other lengths in minutes (D). */
    fun reminder(minutes: Int?): String = when {
        minutes == null -> "None"
        minutes >= 60 && minutes % 60 == 0 -> (minutes / 60).let { "$it ${if (it == 1) "Hour" else "Hours"}" }
        else -> "$minutes Min."
    }

    /** The Recurrence field and its line under it (sim-229: "None", "No Recurrence."); the others D. */
    fun recurrence(r: Recurrence?): Pair<String, String> = when (r) {
        Recurrence.NONE -> "None" to "No Recurrence."
        Recurrence.DAILY -> "Daily" to "Every day."
        Recurrence.WEEKLY -> "Weekly" to "Every week."
        Recurrence.MONTHLY -> "Monthly" to "Every month."
        Recurrence.YEARLY -> "Yearly" to "Every year."
        null -> "Other" to "Kept as it was written."
    }

    const val ALL_DAY_TEXT = "All Day"
}
