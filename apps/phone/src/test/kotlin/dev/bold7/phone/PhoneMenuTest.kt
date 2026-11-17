package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Phone screen's menu (sim-142, sim-143), Add Pause and Add Wait, the keys held (guide 7.1 p. 64, shortcuts). */
class PhoneMenuTest {
    private val call = LogEntry(1, "5551234", "Amtrak", "", LogType.OUTGOING, 0, isNew = false)
    private val loaded = Dialer().reduce(DialEvent.Loaded(listOf(call)))
    private val empty = Dialer().reduce(DialEvent.Loaded(emptyList()))

    private fun Dialer.type(s: String) = s.fold(this) { d, c -> d.reduce(DialEvent.Typed(c)) }

    @Test fun nothingToCallOpensOnCallFromContacts() {
        val m = empty.menu()
        // sim-142, sim-143: Switch Application first, groups apart by a line,
        // Status with Phone Ringtones.
        assertEquals(
            listOf(PhoneAct.SWITCH, PhoneAct.CALL_FROM_CONTACTS, PhoneAct.VOICE_MAIL, PhoneAct.VIEW, PhoneAct.ADD_PAUSE, PhoneAct.ADD_WAIT, PhoneAct.RINGTONES, PhoneAct.STATUS, PhoneAct.OPTIONS, PhoneAct.HELP, PhoneAct.CLOSE),
            m.entries.map { it.action },
        )
        assertEquals(listOf(false, true, false, true, true, false, true, false, true, false, false), m.entries.map { it.separatorAbove })
        assertEquals(1, m.highlighted)
    }

    @Test fun aFocusedCallOpensOnItsContactSubmenu() {
        // sim-392 to sim-394 (simulator, C): an unknown number's call focused, nothing typed.
        val unknown = Dialer().reduce(DialEvent.Loaded(listOf(call.copy(name = ""))))
        val m = unknown.reduce(DialEvent.Step(down = true)).menu()
        assertEquals(
            listOf(
                PhoneAct.SWITCH, PhoneAct.CALL_FROM_CONTACTS, PhoneAct.VOICE_MAIL, PhoneAct.CONTACT, PhoneAct.VIEW,
                PhoneAct.ADD_SPEED_DIAL, PhoneAct.DELETE, PhoneAct.COPY, PhoneAct.RINGTONES, PhoneAct.STATUS, PhoneAct.OPTIONS, PhoneAct.HELP, PhoneAct.CLOSE,
            ),
            m.entries.map { it.action },
        )
        assertEquals(listOf(PhoneAct.CALL_FROM_CONTACTS, PhoneAct.CONTACT, PhoneAct.ADD_SPEED_DIAL, PhoneAct.COPY, PhoneAct.RINGTONES, PhoneAct.OPTIONS), m.entries.filter { it.separatorAbove }.map { it.action })
        assertEquals(listOf(PhoneAct.CONTACT, PhoneAct.VIEW), m.entries.filter { it.submenu }.map { it.action })
        assertEquals(PhoneAct.CONTACT, m.entries[m.highlighted!!].action)
        // Its submenu (sim-395): Call and Text, Call focused.
        val sub = contactMenu()
        assertEquals(listOf(PhoneAct.CALL, PhoneAct.TEXT), sub.entries.map { it.action })
        assertEquals(0, sub.highlighted)
        // A contact's call (sim-402, sim-404): no Copy.
        val known = loaded.reduce(DialEvent.Step(down = true)).menu()
        assertEquals(false, PhoneAct.COPY in known.entries.map { it.action })
        assertEquals(true, known.entries.first { it.action == PhoneAct.RINGTONES }.separatorAbove)
        // A number typed: Call it first, as before (no capture of it).
        val typed = empty.type("555").menu()
        assertEquals(listOf(PhoneAct.SWITCH, PhoneAct.CALL, PhoneAct.CALL_FROM_CONTACTS), typed.entries.take(3).map { it.action })
        assertEquals(false, PhoneAct.DELETE in typed.entries.map { it.action })
    }

    @Test fun oneRowPerNumber() {
        // sim-399 (C): a second call from a number moves its row to the top, the older row gone.
        val older = LogEntry(1, "0612345678", "", "", LogType.MISSED, 100, isNew = false)
        val other = LogEntry(2, "5550102", "", "", LogType.MISSED, 200, isNew = false)
        val newer = LogEntry(3, "+33 6 12 34 56 78", "", "", LogType.INCOMING, 300, isNew = true)
        assertEquals(listOf(newer, other), byNumber(listOf(newer, other, older)))
        // Withheld numbers stay apart: nothing tells them apart.
        val hidden = listOf(LogEntry(4, "", "", "", LogType.MISSED, 50, false), LogEntry(5, "", "", "", LogType.MISSED, 40, false))
        assertEquals(hidden, byNumber(hidden))
    }

    @Test fun pauseAndWaitFollowANumberOnly() {
        assertEquals("555,", empty.type("555").reduce(DialEvent.Typed(PAUSE)).typed)
        assertEquals("555;12", empty.type("555").reduce(DialEvent.Typed(WAIT)).type("12").typed)
        assertEquals("", empty.reduce(DialEvent.Typed(PAUSE)).typed)
        assertEquals("", empty.reduce(DialEvent.Typed(WAIT)).typed)
        // The number before them still finds its call, and is what Send calls with them.
        val paused = loaded.type("555").reduce(DialEvent.Typed(PAUSE)).type("9")
        assertEquals(listOf(call), paused.shown)
        assertEquals("555,9", paused.toCall())
    }

    @Test fun heldKeys() {
        val speed = SpeedDial().assign('a', SpeedNumber("5551234", "Amtrak"))
        // Hold 1 (the W key, as on the 9900 and the Q25's kcm) for the voice mail.
        assertEquals(Held.VoiceMail, held("", 'w', speed))
        assertEquals(Held.Call("5551234"), held("", 'A', speed))
        assertEquals(Held.Assign('q'), held("", 'q', speed))
        // Something typed before the key: the key only types.
        assertNull(held("5", 'a', speed))
        assertNull(held("", '+', speed))
    }
}
