package dev.bold7.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// What the Calendar shows, pure: the occurrences read from CalendarContract.Instances, the blocks of
// the Day and Week views, the Month grid and the Agenda's rows (docs/BBOS7-VISUEL.md §29). Tested
// on the JVM (AgendaTest).

/**
 * An occurrence of an event (CalendarContract.Instances): [begin] and [end] in ms; an all-day one
 * is stored by Android at UTC midnights, a timed one at its instant. [invitation]: a meeting someone
 * else organises that Bold7's user has not answered.
 */
data class Occurrence(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean = false,
    val location: String = "",
    val recurring: Boolean = false,
    val reminder: Boolean = false,
    val invitation: Boolean = false,
    val color: Int = 0,
) {
    /** Its start and its end in local time ([zone]; an all-day one's own dates). */
    fun start(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), if (allDay) ZoneOffset.UTC else zone)

    fun finish(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), if (allDay) ZoneOffset.UTC else zone)

    /** Whether it covers part of [day]: an all-day one ends at the midnight after its last day. */
    fun on(day: LocalDate, zone: ZoneId): Boolean {
        val s = start(zone)
        val e = finish(zone)
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        return s < dayEnd && (e > dayStart || (e == s && s.toLocalDate() == day))
    }

    /**
     * Whether it fills part of [hour] on [day], the focus of the Day and Week views; [ALL_DAY] is
     * their all-day row, which holds the all-day ones.
     */
    fun covers(day: LocalDate, hour: Int, zone: ZoneId): Boolean {
        if (hour == ALL_DAY) return allDay && on(day, zone)
        if (allDay) return false
        val from = day.atTime(hour, 0)
        val to = from.plusHours(1)
        val s = start(zone)
        val e = finish(zone).let { if (it == s) it.plusMinutes(1) else it }
        return s < to && e > from
    }
}

/** The all-day row of the Day and Week views, above 00 (sim-224: the grey row under the days). */
const val ALL_DAY = -1

/** The Calendar's display options (Calendar Options, guide p. 185). */
data class DisplayOptions(
    /** Start Of Day (guide p. 185): where the Day and Week views open on another day than today (9:00, D). */
    val startOfDay: Int = 9,
    /** First Day Of Week: Monday, the simulator's (sim-224, sim-225). */
    val firstDay: DayOfWeek = DayOfWeek.MONDAY,
)

/**
 * A timed appointment placed in a day of the Day or Week view: its minutes from the day's midnight
 * ([from], [to], clipped to the day, at least [MIN_BLOCK] long), and its [column] among [columns]
 * side by side when appointments overlap (D: sim-237 shows one alone).
 */
data class Block(val occurrence: Occurrence, val from: Int, val to: Int, val column: Int, val columns: Int)

/** The shortest block drawn, minutes (D: an appointment of no length still shows). */
const val MIN_BLOCK = 15

fun blocks(day: LocalDate, occurrences: List<Occurrence>, zone: ZoneId): List<Block> {
    val midnight = day.atStartOfDay()
    val placed = occurrences.filter { !it.allDay && it.on(day, zone) }
        .map { o ->
            val from = ChronoUnit.MINUTES.between(midnight, o.start(zone)).coerceIn(0, 24 * 60).toInt()
            val to = ChronoUnit.MINUTES.between(midnight, o.finish(zone)).coerceIn(0, 24 * 60).toInt()
            Triple(o, from, maxOf(to, from + MIN_BLOCK))
        }
        .sortedWith(compareBy({ it.second }, { -it.third }, { it.first.title }))
    // Groups of appointments that overlap one another; each takes the first free column of its group.
    val result = mutableListOf<Block>()
    var group = mutableListOf<Pair<Triple<Occurrence, Int, Int>, Int>>()
    var groupEnd = -1
    fun flush() {
        val n = (group.maxOfOrNull { it.second } ?: -1) + 1
        group.forEach { (t, c) -> result += Block(t.first, t.second, t.third, c, n) }
        group = mutableListOf()
    }
    for (t in placed) {
        if (t.second >= groupEnd) flush()
        val busy = group.filter { it.first.third > t.second }.map { it.second }.toSet()
        group += t to generateSequence(0) { it + 1 }.first { it !in busy }
        groupEnd = maxOf(groupEnd, t.third)
    }
    flush()
    return result
}

/** The seven days of [day]'s week, from [firstDay]. */
fun weekOf(day: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
    val first = day.with(TemporalAdjusters.previousOrSame(firstDay))
    return (0L until 7L).map { first.plusDays(it) }
}

/** The Month view's grid: six weeks from the [firstDay] on or before the 1st of [day]'s month (sim-225). */
fun monthGrid(day: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
    val first = day.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(firstDay))
    return (0L until 42L).map { first.plusDays(it) }
}

/** A row of the Agenda view (sim-226, sim-244): a day's header, an appointment, or free time between them. */
sealed interface AgendaRow {
    val day: LocalDate

    data class Header(override val day: LocalDate) : AgendaRow
    data class Event(override val day: LocalDate, val occurrence: Occurrence) : AgendaRow

    /** "00:00 - 18:30 - Free Time -"; [to] midnight is the day's end ("19:30 - 00:00"). */
    data class Free(override val day: LocalDate, val from: LocalTime, val to: LocalTime) : AgendaRow
}

/**
 * The Agenda view: [days] days from [from], each under its header, the all-day appointments first,
 * then the day's free time and its appointments in time order (sim-244: "00:00 - 18:30", the
 * appointment, "19:30 - 00:00"; a day without any: "00:00 - 00:00", sim-226).
 */
fun agendaRows(from: LocalDate, days: Int, occurrences: List<Occurrence>, zone: ZoneId): List<AgendaRow> =
    (0L until days.toLong()).flatMap { i ->
        val d = from.plusDays(i)
        val today = occurrences.filter { it.on(d, zone) }
        val rows = mutableListOf<AgendaRow>(AgendaRow.Header(d))
        today.filter { it.allDay }.sortedBy { it.title }.forEach { rows += AgendaRow.Event(d, it) }
        var free = LocalTime.MIDNIGHT
        var reached = false // the day's end reached by an appointment running past midnight
        for (o in today.filter { !it.allDay }.sortedWith(compareBy({ it.begin }, { it.title }))) {
            val s = o.start(zone).let { if (it.toLocalDate() < d) LocalTime.MIDNIGHT else it.toLocalTime() }
            if (!reached && s > free) rows += AgendaRow.Free(d, free, s)
            rows += AgendaRow.Event(d, o)
            val e = o.finish(zone)
            if (e.toLocalDate() > d) reached = true else if (e.toLocalTime() > free) free = e.toLocalTime()
        }
        if (!reached) rows += AgendaRow.Free(d, free, LocalTime.MIDNIGHT)
        rows
    }

/** The range of ms the view [view] around [day] reads from Instances, generously (a day either side). */
fun range(view: CalView, day: LocalDate, zone: ZoneId, options: DisplayOptions): Pair<Long, Long> {
    val (from, to) = when (view) {
        CalView.DAY -> day to day.plusDays(1)
        CalView.WEEK -> weekOf(day, options.firstDay).first().let { it to it.plusDays(7) }
        CalView.MONTH -> monthGrid(day, options.firstDay).first().let { it to it.plusDays(42) }
        CalView.AGENDA -> day to day.plusDays(AGENDA_DAYS.toLong())
    }
    // All-day occurrences are at UTC midnights: a day either side covers every zone.
    return from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** How many days the Agenda view lists from its day (D). */
const val AGENDA_DAYS = 14
