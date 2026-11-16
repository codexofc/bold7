package dev.bold7.calendar

import dev.bold7.showcase.Event
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.pick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What the Calendar reads and writes of an agenda: Android's provider ([AndroidAgenda]) or the showcase's ([ShowcaseAgenda]). Off the main thread. */
interface AgendaSource {
    fun occurrences(from: Long, to: Long): List<Occurrence>
    fun event(id: Long): Stored?
    fun defaultCalendar(): Long?
    fun alerts(alarmTime: Long): List<Alert>
    fun save(draft: Draft): Long?
    fun delete(id: Long): Boolean
    fun deleteOccurrence(id: Long, begin: Long): Boolean
    fun answer(id: Long, status: Int): Boolean
}

/** The [real] agenda, or the showcase's when [showcase] is on: the real one is then never built. */
fun agendaFor(showcase: Boolean, real: () -> AgendaSource, zone: ZoneId, today: () -> LocalDate): AgendaSource =
    pick(showcase, real) { ShowcaseAgenda(zone, today) }

/**
 * The showcase's made-up appointments (core/showcase), timed ones in the phone's [zone], all-day
 * ones at UTC midnights as Android stores them. Holds no Context: nothing real is read, every write
 * is refused and no reminder ever rings.
 */
class ShowcaseAgenda(private val zone: ZoneId, private val today: () -> LocalDate) : AgendaSource {
    private val weekdays = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"

    override fun occurrences(from: Long, to: Long): List<Occurrence> {
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate()
        return Showcase.occurrences(today(), first, last).map { occurrence(it.event, it.date) }.sortedBy { it.begin }
    }

    private fun occurrence(e: Event, date: LocalDate): Occurrence {
        val (begin, end) = times(e, date)
        return Occurrence(e.id, e.title, begin, end, e.start == null, e.location, e.weekdays, e.reminder, e.invitation)
    }

    private fun times(e: Event, date: LocalDate): Pair<Long, Long> = when (val start = e.start) {
        null -> date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() to date.plusDays(e.days.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        else -> date.atTime(start).atZone(zone).toInstant().toEpochMilli().let { it to it + e.minutes * 60_000L }
    }

    /** The form of an appointment; a repeating one's series starts today. */
    override fun event(id: Long): Stored? {
        val e = Showcase.events.firstOrNull { it.id == id } ?: return null
        val (begin, end) = times(e, if (e.weekdays) today() else Showcase.dateOf(e, today()))
        val organizer = if (e.invitation) Showcase.people.firstOrNull { e.title.endsWith(it.name) } else null
        return Stored(
            e.id, CALENDAR, e.title, e.location, "", e.start == null, begin, end, if (e.weekdays) weekdays else null,
            if (e.reminder) 15 else null, organizer?.email.orEmpty(), isOrganizer = organizer == null,
            selfStatus = if (organizer != null) ATTENDEE_INVITED else 0,
            attendees = organizer?.let { listOf(Attendee(it.name, it.email.orEmpty(), ATTENDEE_ACCEPTED)) }.orEmpty(),
            timezone = zone.id,
        )
    }

    override fun defaultCalendar(): Long = CALENDAR

    override fun alerts(alarmTime: Long): List<Alert> = emptyList()

    override fun save(draft: Draft): Long? = null

    override fun delete(id: Long) = false

    override fun deleteOccurrence(id: Long, begin: Long) = false

    override fun answer(id: Long, status: Int) = false

    private companion object {
        const val CALENDAR = 1L
    }
}
