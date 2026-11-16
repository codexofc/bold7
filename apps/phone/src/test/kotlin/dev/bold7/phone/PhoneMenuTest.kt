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
        // sim-142, sim-143 without Help: Switch Application first, groups apart by a line,
        // Status with Phone Ringtones.
        assertEquals(
            listOf(PhoneAct.SWITCH, PhoneAct.CALL_FROM_CONTACTS, PhoneAct.VOICE_MAIL, PhoneAct.VIEW, PhoneAct.ADD_PAUSE, PhoneAct.ADD_WAIT, PhoneAct.RINGTONES, PhoneAct.STATUS, PhoneAct.OPTIONS),
            m.entries.map { it.action },
        )
        assertEquals(listOf(false, true, false, true, true, false, true, false, true), m.entries.map { it.separatorAbove })
        assertEquals(1, m.highlighted)
    }

    @Test fun aFocusedCallComesFirstWithAddSpeedDial() {
        val m = loaded.reduce(DialEvent.Step(down = true)).menu()
        assertEquals(listOf(PhoneAct.SWITCH, PhoneAct.CALL, PhoneAct.ADD_SPEED_DIAL, PhoneAct.CALL_FROM_CONTACTS), m.entries.take(4).map { it.action })
        assertEquals(true, m.entries[3].separatorAbove)
        assertEquals(1, m.highlighted)
        // A number typed: Call it, but nothing to add to speed dial.
        val typed = empty.type("555").menu()
        assertEquals(listOf(PhoneAct.SWITCH, PhoneAct.CALL, PhoneAct.CALL_FROM_CONTACTS), typed.entries.take(3).map { it.action })
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
