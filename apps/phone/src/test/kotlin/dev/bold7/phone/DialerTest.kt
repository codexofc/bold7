package dev.bold7.phone

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DialerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(month: Int, day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    private val amtrak = LogEntry(1, "18008727245", "Amtrak", "Work", LogType.OUTGOING, at(8, 23, 14, 38), isNew = false)
    private val unknown = LogEntry(2, "9735550100", "", "", LogType.OUTGOING, at(8, 23, 18, 11), isNew = false)
    private val missed = LogEntry(3, "+33199001234", "", "", LogType.MISSED, at(8, 24, 9, 5), isNew = true)
    private val log = listOf(missed, unknown, amtrak)
    private val loaded = Dialer().reduce(DialEvent.Loaded(log))

    @Test fun theLogOpensFocusedOnTheFieldAndTheFirstMatchTakesItOnTyping() {
        assertEquals(log, loaded.shown)
        assertEquals(FIELD, loaded.focus)
        assertNull(loaded.focused)
        assertEquals(0, loaded.reduce(DialEvent.Step(down = true)).focus)
        assertEquals(missed, loaded.reduce(DialEvent.Step(down = true)).focused)
        // A reload keeps the field, or a focused call.
        assertEquals(FIELD, loaded.reduce(DialEvent.Loaded(log.drop(1))).focus)
        assertEquals(1, loaded.reduce(DialEvent.Moved(1)).reduce(DialEvent.Loaded(log)).focus)
        assertEquals(FIELD, Dialer().reduce(DialEvent.Loaded(emptyList())).focus)
    }

    // Essai of 17/11: 666 typed and called, the log came back with the call on top and the focus on
    // the row below, the old first row, followed by its id: one click called another number.
    @Test fun afterACallThePhoneIsBackOnItsFieldWithNoRowFocused() {
        val test = LogEntry(5, "666", "", "", LogType.OUTGOING, at(8, 25, 1, 38), isNew = false)
        val typed = "666".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }
        val back = typed.placed().reduce(DialEvent.Loaded(listOf(test) + log))
        assertEquals("", back.typed)
        assertEquals(FIELD, back.focus)
        assertNull(back.focused)
        assertNull(back.toCall())
        // A call placed from a focused row: the same.
        val row = loaded.reduce(DialEvent.Moved(1)).placed().reduce(DialEvent.Loaded(listOf(unknown.copy(id = 6)) + log))
        assertEquals(FIELD, row.focus)
        assertNull(row.toCall())
    }

    @Test fun theTrackpadGoesUpToTheFieldAndStopsAtTheEnd() {
        assertEquals(FIELD, loaded.reduce(DialEvent.Moved(0)).reduce(DialEvent.Step(down = false)).focus)
        assertEquals(FIELD, loaded.reduce(DialEvent.Step(down = false)).reduce(DialEvent.Step(down = false)).focus)
        assertEquals(2, loaded.reduce(DialEvent.Moved(2)).reduce(DialEvent.Step(down = true)).focus)
        assertEquals(0, loaded.reduce(DialEvent.Step(down = true)).focus)
    }

    @Test fun typingDialsAndNarrowsTheLog() {
        val typed = "973".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }
        assertEquals("973", typed.typed)
        assertEquals(listOf(unknown), typed.shown)
        assertEquals(0, typed.focus)
        val none = typed.reduce(DialEvent.Typed('9'))
        assertEquals(emptyList(), none.shown)
        assertEquals(FIELD, none.focus)
        // Letters find a name.
        assertEquals(listOf(amtrak), "amt".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }.shown)
    }

    @Test fun aNationalNumberFindsItsInternationalForm() {
        // In France one dials 06 or 01: the log and the cards keep the international form (+33 ...).
        // Digits look in the calls only (digitsAndAnEmptyFieldShowNoContact).
        val national = LogEntry(4, "0612345678", "", "", LogType.OUTGOING, at(8, 25, 9, 0), isNew = false)
        val d = Dialer().reduce(DialEvent.Loaded(listOf(missed, national), listOf(dexter, deb)))
        assertEquals(listOf(missed), d.type("0199001").shown)
        assertEquals(listOf(missed), d.type("019900123").shown)
        assertEquals(emptyList(), d.type("0699").shown)
        // The international form still finds them, and finds a call kept in the national one.
        assertEquals(listOf(missed), d.type("+33199001").shown)
        assertEquals(listOf(national), d.type("+33612345678").shown)
    }

    // Contacts found by name in the field (guide 7.1 p. 61: "Type a phone number or contact name"),
    // with the words rule of Contacts and Messages (core/ui matchesWords).
    private val dexter = LogEntry(-11, "+33 1 99 00 11 22", "Dexter Morgan", "Mobile", LogType.CONTACT, 0, isNew = false)
    private val deb = LogEntry(-12, "0199001133", "Debra Morgan", "Work", LogType.CONTACT, 0, isNew = false)
    private val amtrakCard = LogEntry(-13, "1 800 872 7245", "Amtrak", "Work", LogType.CONTACT, 0, isNew = false)
    private val book = Dialer().reduce(DialEvent.Loaded(log, listOf(amtrakCard, deb, dexter)))
    private fun Dialer.type(s: String) = s.fold(this) { d, c -> d.reduce(DialEvent.Typed(c)) }

    @Test fun lettersProposeTheContactsWhoseWordsStartWithThem() {
        assertEquals(listOf(deb, dexter), book.type("mor").shown)
        assertEquals(listOf(dexter), book.type("dex").shown)
        assertEquals(0, book.type("dex").focus)
        assertEquals("+33 1 99 00 11 22", book.type("dex").toCall())
        // A word's start only, accents and case ignored.
        assertEquals(emptyList(), book.type("organ").shown)
        val accented = dexter.copy(name = "Déxter")
        assertEquals(listOf(accented), book.copy(contacts = listOf(accented)).type("DEX").shown)
    }

    @Test fun theCallsComeFirstAndAContactAlreadyCalledIsNotRepeated() {
        // Amtrak's card has the number of the logged call, written otherwise.
        assertEquals(listOf(amtrak), book.type("amt").shown)
        val other = amtrakCard.copy(id = -14, number = "5551234")
        assertEquals(listOf(amtrak, other), book.copy(contacts = listOf(other)).type("amt").shown)
    }

    @Test fun digitsAndAnEmptyFieldShowNoContact() {
        assertEquals(log, book.shown)
        assertEquals(listOf(unknown), book.type("973").shown)
        // The missed call from +33 1 99 00 12 34: its national form (aNationalNumberFindsItsInternationalForm).
        assertEquals(listOf(missed), book.type("0199").shown)
        assertEquals(emptyList(), book.type("0188").shown)
    }

    @Test fun aContactRowHasNeitherIconNorDate() {
        val row = logRow(dexter, paris)
        assertEquals("Dexter Morgan", row.title)
        assertEquals("Mobile: +33 1 99 00 11 22", row.subtitle)
        assertEquals("", row.date)
        assertEquals("", row.time)
        assertEquals(LogIcon.NONE, row.icon)
    }

    // Place Call under the lock (guide 7.1 p. 61, PlaceCallActivity): no log, no contact is read,
    // a name typed only dials its keypad digits (D), an emergency number as any number.
    @Test fun placeCallWhileLockedOnlyDials() {
        val locked = Dialer()
        assertEquals(emptyList(), locked.type("amt").shown)
        assertEquals("268", locked.type("amt").toCall())
        assertEquals("112", locked.type("112").toCall())
        assertNull(locked.toCall())
    }

    @Test fun backspaceAndEscape() {
        val typed = "97".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }
        assertEquals("9", typed.reduce(DialEvent.Erased).typed)
        assertEquals("", typed.reduce(DialEvent.Erased).reduce(DialEvent.Erased).reduce(DialEvent.Erased).typed)
        assertEquals(log, typed.reduce(DialEvent.Erased).reduce(DialEvent.Erased).shown)
        // Escape clears what was typed first, then leaves (null).
        val cleared = typed.back()!!
        assertEquals("", cleared.typed)
        assertNull(cleared.back())
    }

    @Test fun theCallKeyDialsWhatIsTypedElseTheFocusedCall() {
        assertNull(loaded.toCall())
        assertEquals("+33199001234", loaded.reduce(DialEvent.Step(down = true)).toCall())
        val typed = "112".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }
        assertEquals("112", typed.toCall())
        assertNull(Dialer().reduce(DialEvent.Loaded(emptyList())).toCall())
        // Letters dial their keypad digit (guide 7.1 p. 63); a search by name dials the name found.
        assertEquals("18008727245", "amt".fold(loaded) { d, c -> d.reduce(DialEvent.Typed(c)) }.toCall())
        assertEquals("1800FLOWERS".let(::dialable), "18003569377")
    }

    @Test fun aNewLogKeepsTheFocusedCall() {
        val onAmtrak = loaded.reduce(DialEvent.Moved(2))
        val newer = LogEntry(4, "5551234", "", "", LogType.INCOMING, at(8, 25, 8, 0), isNew = false)
        assertEquals(3, onAmtrak.reduce(DialEvent.Loaded(listOf(newer) + log)).focus)
        assertEquals(0, onAmtrak.reduce(DialEvent.Loaded(listOf(newer))).focus)
    }

    @Test fun theKeyboardsDigitsOrItsLetters() {
        // The 9900's keys with a digit dial it (guide p. 63: "To type a number, press a number key").
        assertEquals('1', dialChar(number = '1', letter = 'w', alt = false))
        assertEquals('w', dialChar(number = '1', letter = 'w', alt = true))
        assertEquals('q', dialChar(number = '\u0000', letter = 'q', alt = false))
        assertEquals('+', dialChar(number = '+', letter = '\u0000', alt = false))
        assertNull(dialChar(number = '\u0000', letter = '\u0000', alt = false))
        assertNull(dialChar(number = '\u0000', letter = '!', alt = false))
    }

    @Test fun aRowOfPs33246() {
        val row = logRow(amtrak, paris)
        assertEquals("Amtrak", row.title)
        assertEquals("Work: 18008727245", row.subtitle)
        assertEquals("08/23", row.date)
        assertEquals("2:38p", row.time)
        assertEquals(LogIcon.PLACED, row.icon)
        val bare = logRow(unknown, paris)
        assertEquals("9735550100", bare.title)
        assertEquals("", bare.subtitle)
        assertEquals("6:11p", bare.time)
        assertEquals(LogIcon.MISSED_NEW, logRow(missed, paris).icon)
        assertEquals("9:05a", logRow(missed, paris).time)
        assertEquals(LogIcon.MISSED, logRow(missed.copy(isNew = false), paris).icon)
        assertEquals(LogIcon.RECEIVED, logRow(amtrak.copy(type = LogType.INCOMING), paris).icon)
        assertEquals("Private Number", logRow(unknown.copy(number = ""), paris, withheld = "Private Number").title)
        // sim-141: a day-first locale with a 24-hour clock.
        val gb = logRow(missed, paris, dayFirst = true, h24 = true)
        assertEquals("24/08", gb.date)
        assertEquals("09:05", gb.time)
    }
}
