package dev.bold7.launcher.search

import dev.bold7.showcase.CallType
import dev.bold7.showcase.Channel
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.pick
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// The search over the showcase's made-up people, texts, calls and appointments (core/showcase),
// in place of the providers while it is on. Pure: nothing here reaches a provider.

private const val MINUTE = 60_000L

/** CallLog.Calls.TYPE of a showcase call. */
private fun CallType.logType() = when (this) {
    CallType.RECEIVED -> 1
    CallType.PLACED -> 2
    CallType.MISSED -> 3
}

/**
 * What the showcase holds for [query] in [category], as the providers' sources answer it: names
 * and numbers for the contacts and the calls, texts by their words, appointments from 90 days back
 * to a year on. The categories without made-up data find nothing.
 */
internal fun showcaseFind(category: Category, query: String, now: Long, today: LocalDate, zone: ZoneId, text: (String) -> String = { it }): Found = when (category) {
    Category.CONTACTS -> found(
        Showcase.people.filter { p -> matchRanges(p.name, query) != null || p.numbers.any { numberMatches(it, query) } || p.email?.let { matchRanges(it, query) } != null }
            .map { Contact(it.id, "showcase-${it.id}", it.name, it.photo) },
    )
    Category.CALLS -> found(
        Showcase.calls.mapNotNull { c ->
            val name = c.person?.let { Showcase.person(it).name }
            Call(c.number, name, c.type.logType(), now - c.minutesAgo * MINUTE)
                .takeIf { name?.let { n -> matchRanges(n, query) } != null || numberMatches(c.number, query) }
        },
    )
    Category.TEXT_MESSAGES -> found(
        Showcase.threads.filter { it.channel == Channel.TEXT }.flatMap { t ->
            val single = t.members.singleOrNull()
            t.messages.mapNotNull { m ->
                val who = Showcase.person(m.from ?: t.members.first())
                val body = text(m.text)
                Message(who.mobile, if (single != null) who.name else null, body, now - m.minutesAgo * MINUTE)
                    .takeIf { matchRanges(body, query) != null || (!m.out && numberMatches(who.mobile, query)) }
            }
        }.sortedByDescending { it.date },
    )
    Category.CALENDAR -> {
        val begins = { o: dev.bold7.showcase.Occurrence -> o.date.atTime(o.event.start ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli() }
        val hits = Showcase.occurrences(today, today.minusDays(90), today.plusDays(365))
            .filter { matchRanges(it.event.title, query) != null || matchRanges(it.event.location, query) != null }
            .map { Event(it.event.id * 10_000 + it.date.toEpochDay() % 10_000, it.event.title, begins(it), it.event.location, it.event.start == null) }
        found(upcomingFirst(hits, now, Event::begin, Event::id))
    }
    else -> found(emptyList<Any>())
}

/**
 * [real]'s answer, or the showcase's while [on]: the providers are not touched then ([real] is not
 * called).
 */
internal fun findIn(on: Boolean, category: Category, query: String, now: Long, zone: ZoneId, real: () -> Found): Found =
    pick(on, real) { showcaseFind(category, query, now, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone), zone) }
