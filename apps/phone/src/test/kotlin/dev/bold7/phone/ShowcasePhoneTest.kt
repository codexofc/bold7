package dev.bold7.phone

import dev.bold7.showcase.Showcase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowcasePhoneTest {
    private val now = 1_800_000_000_000L
    private val log = callLog(showcase = true, real = { error("real call log opened") }, now = { now })

    @Test
    fun theLogIsTheShowcaseCallsNewestFirstNamedAndLabelled() {
        val r = log.recent()
        assertEquals(Showcase.calls.size, r.size)
        assertEquals(r.sortedByDescending { it.date }, r)
        val missed = r.first()
        assertEquals(LogEntry(1, "+1 519-555-0105", "Grace Carter", "Mobile", LogType.MISSED, now - 75 * 60_000L, isNew = true), missed)
        assertEquals("Work", r.first { it.name == "Olivia Bennett" }.label)
        assertEquals("Home", r.first { it.number == "+1 519-555-0175" }.label)
        val unknown = r.single { it.number == "+1 519-555-0187" }
        assertEquals("" to "", unknown.name to unknown.label)
        assertTrue(r.any { it.type == LogType.OUTGOING } && r.any { it.type == LogType.INCOMING })
    }

    @Test
    fun theFieldProposesEveryNumberOfThePeopleSortedByName() {
        val c = log.contacts()
        assertEquals(Showcase.people.sumOf { it.numbers.size }, c.size)
        assertTrue(c.all { it.type == LogType.CONTACT && it.id < 0 })
        assertEquals(c.size, c.map { it.id }.toSet().size)
        assertEquals(c.map { it.name }.sorted(), c.map { it.name })
        assertEquals(listOf("Mobile", "Work"), c.filter { it.name == "Olivia Bennett" }.map { it.label })
    }

    @Test
    fun theOtherReadsAreServedFromTheShowcase() {
        assertEquals("Olivia Bennett", log.name("+1 519-555-0151"))
        assertNull(log.name("+1 519-555-0187"))
        assertEquals(Showcase.OWNER_NUMBER, log.lineNumber())
        assertTrue(log.timed().isNotEmpty())
        assertNull(log.picked("content://com.android.contacts/data/1"))
        assertNull(log.photo("+1 519-555-0101"))
    }

    @Test
    fun nothingIsPlacedNorMarked() {
        assertTrue(log.place("+1 519-555-0101"), "a call pretends to go, nothing is placed")
        assertEquals(VoiceMailResult.NO_NUMBER, log.voiceMail())
        log.markMissedSeen()
        assertTrue(log.recent().first().isNew)
    }

    @Test
    fun theStoresKeepNothing() {
        val speed = speedDials(showcase = true, real = { error("real speed dial opened") })
        val before = speed.load()
        assertTrue(before.keys.values.all { Showcase.byNumber(it.number) != null })
        speed.save(SpeedDial())
        assertEquals(before, speed.load())
        val timers = timerStore(showcase = true, real = { error("real timers opened") })
        timers.save(CallTimersCleared(lastCall = 5))
        assertEquals(CallTimersCleared(), timers.load())
    }

    @Test
    fun theDemoCallIsGraceInTheShowcaseAndItsPictureHerAvatar() {
        assertEquals("+1 519-555-0105" to "Grace Carter", demoCaller(showcase = true))
        assertEquals("5551234" to "Essai", demoCaller(showcase = false))
        val asked = mutableListOf<String>()
        val withAvatars = callLog(showcase = true, real = { error("real call log opened") }, avatar = { asked += it; null })
        withAvatars.photo("+1 519-555-0105")
        withAvatars.photo("+1 519-555-0187")
        assertEquals(listOf(Showcase.person(5).photo), asked)
    }
}
