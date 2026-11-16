package dev.bold7.showcase

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowcaseTest {
    private val fictional = Regex("""\+1 519-555-01\d\d""")

    @Test
    fun everyNumberIsInTheRangeKeptForFiction() {
        val numbers = Showcase.people.flatMap { it.numbers } + Showcase.calls.map { it.number } + Showcase.OWNER_NUMBER
        numbers.forEach { assertTrue(fictional.matches(it), it) }
    }

    @Test
    fun everyAddressIsInADomainKeptForExamples() {
        (Showcase.people.mapNotNull { it.email } + Showcase.OWNER_EMAIL).forEach {
            assertTrue(Regex(""".+@([a-z-]+\.)?example(\.(com|net|org))?""").matches(it), it)
        }
    }

    @Test
    fun idsAreUniqueAndEveryReferenceResolves() {
        listOf(Showcase.people.map { it.id }, Showcase.threads.map { it.id }, Showcase.events.map { it.id },
            Showcase.tasks.map { it.id }, Showcase.memos.map { it.id }).forEach { assertEquals(it.size, it.toSet().size) }
        val people = Showcase.people.map { it.id }.toSet()
        assertEquals((1L..16L).toSet(), people, "one avatar per person")
        Showcase.threads.forEach { t ->
            assertTrue(t.members.isNotEmpty() && people.containsAll(t.members), "thread ${t.id}")
            t.messages.forEach { m -> assertTrue(m.from == null || m.from in t.members, "thread ${t.id}") }
            assertEquals(t.messages.sortedByDescending { it.minutesAgo }, t.messages, "thread ${t.id} in order")
            assertEquals(t.channel == Channel.EMAIL, t.subject != null)
        }
        Showcase.calls.forEach { c -> assertTrue(c.person == null || c.number in Showcase.person(c.person).numbers, c.number) }
    }

    @Test
    fun theInboxHasUnreadItemsAndAMissedCall() {
        assertTrue(Showcase.threads.sumOf { it.unread } >= 3)
        assertTrue(Showcase.calls.any { it.type == CallType.MISSED && it.isNew })
    }

    @Test
    fun noTextUsesAnEmDash() {
        val texts = Showcase.threads.flatMap { t -> t.messages.map { it.text } + listOfNotNull(t.subject) } +
            Showcase.events.map { it.title + it.location } + Showcase.tasks.map { it.title + it.notes } +
            Showcase.memos.map { it.title + it.body }
        texts.forEach { assertTrue('\u2014' !in it, it) }
    }

    @Test
    fun todayIsBusyAndTheWeekAhead() {
        val monday = LocalDate.of(2026, 11, 16)
        val today = Showcase.occurrences(monday, monday, monday)
        assertTrue(today.size >= 5, "$today")
        val week = Showcase.occurrences(monday, monday, monday.plusDays(6))
        assertEquals(LocalDate.of(2026, 11, 21), week.single { it.event.id == 12L }.date)
        assertEquals(LocalDate.of(2026, 11, 22), week.single { it.event.id == 13L }.date)
        assertEquals(5, week.count { it.event.weekdays }, "stand-up on weekdays only")
        val saturday = monday.with(DayOfWeek.SATURDAY)
        assertEquals(saturday, Showcase.dateOf(Showcase.events.single { it.id == 12L }, saturday), "today counts")
    }

    @Test
    fun aSeveralDayEventIsFoundOnItsLaterDays() {
        val today = LocalDate.of(2026, 11, 16)
        val cottage = today.plusDays(20)
        assertTrue(Showcase.occurrences(today, cottage, cottage).any { it.event.id == 23L })
    }

    @Test
    fun byNumberFindsEveryNumberOfAPerson() {
        assertEquals("Grace Carter", Showcase.byNumber("+1 519-555-0175")?.name)
        assertEquals(null, Showcase.byNumber("+1 519-555-0187"))
    }

    @Test
    fun theStoryAddsDanielsTextThenTheAnswerAndTheReply() {
        assertEquals(Showcase.threads, Showcase.threads(null))
        assertEquals(Showcase.threads, Showcase.threads(ShowcaseEvent.ALARM))
        val arrived = Showcase.threads(ShowcaseEvent.MESSAGE).single { it.id == Showcase.STORY_THREAD }
        assertEquals(listOf(2L), arrived.members)
        assertEquals(Showcase.STORY_TEXT, arrived.last.text)
        assertEquals(1, arrived.unread)
        val replied = Showcase.threads(ShowcaseEvent.REPLY).single { it.id == Showcase.STORY_THREAD }
        assertEquals(listOf(Showcase.STORY_TEXT, Showcase.STORY_ANSWER, Showcase.STORY_REPLY), replied.messages.takeLast(3).map { it.text })
        assertEquals(listOf(false, true, false), replied.messages.takeLast(3).map { it.out })
        assertEquals(Showcase.unread["SMS"]!! + 1, Showcase.unread(ShowcaseEvent.MESSAGE)["SMS"])
        assertEquals(Showcase.unread["SMS"]!! + 1, Showcase.unread(ShowcaseEvent.REPLY)["SMS"])
        assertEquals(Showcase.unread, Showcase.unread(ShowcaseEvent.ALARM))
        // Every other thread untouched.
        assertEquals(Showcase.threads.filter { it.id != Showcase.STORY_THREAD }, Showcase.threads(ShowcaseEvent.REPLY).filter { it.id != Showcase.STORY_THREAD })
    }

    @Test
    fun theEventIsReadFromTheSettingsName() {
        assertEquals(ShowcaseEvent.MESSAGE, ShowcaseEvent.of("message"))
        assertEquals(ShowcaseEvent.REPLY, ShowcaseEvent.of(" REPLY "))
        assertEquals(ShowcaseEvent.ALARM, ShowcaseEvent.of("alarm"))
        assertEquals(null, ShowcaseEvent.of(null))
        assertEquals(null, ShowcaseEvent.of("call"))
    }
}
