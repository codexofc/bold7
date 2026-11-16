package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The speed dial keys (guide 7.1 p. 65-66): assign, move, delete, kept as text, the list's clicks. */
class SpeedDialTest {
    private val amtrak = SpeedNumber("18008727245", "Amtrak")
    private val bob = SpeedNumber("5551234", "")
    private val dial = SpeedDial().assign('a', amtrak).assign('B', bob)

    @Test fun keysAreLettersButTheVoiceMails() {
        assertEquals(amtrak, dial['A'])
        assertEquals(bob, dial['b'])
        assertEquals(dial, dial.assign('w', bob))
        assertEquals(dial, dial.assign('1', bob))
        assertEquals(SpeedDial().assign('b', bob), dial.delete('a'))
    }

    @Test fun moveGoesToAFreeKeyOnly() {
        val moved = dial.move('a', 'z')
        assertNull(moved['a'])
        assertEquals(amtrak, moved['z'])
        assertEquals(dial, dial.move('a', 'b'))
        assertEquals(dial, dial.move('a', 'w'))
    }

    @Test fun keptAsTextAndReadBack() {
        val odd = dial.assign('c', SpeedNumber("555\t1", "Line\nTwo"))
        val back = SpeedDial.decode(odd.encode())
        assertEquals(amtrak, back['a'])
        assertEquals(SpeedNumber("555 1", "Line Two"), back['c'])
        assertEquals(SpeedDial(), SpeedDial.decode(""))
        assertEquals(SpeedDial(), SpeedDial.decode("garbage\nw\t1\tx\n"))
    }

    @Test fun theListsRowsAreTheLettersInOrder() {
        val rows = dial.rows()
        assertEquals(('a'..'z').toList(), rows.map { it.key })
        assertEquals(amtrak, rows[0].number)
        assertEquals(true, rows.first { it.key == VOICE_MAIL_KEY }.voiceMail)
        assertNull(rows[2].number)
    }

    @Test fun aClickOnTheList() {
        // Nothing pending: a free key asks for a contact, an assigned one or the voice mail's opens the menu.
        assertEquals(SpeedClick.Choose('c'), dial.click('c', null))
        assertEquals(SpeedClick.Menu, dial.click('a', null))
        assertEquals(SpeedClick.Menu, dial.click('w', null))
        // Adding from the Phone screen, or moving: a free key takes it, a taken one does nothing.
        assertEquals(SpeedClick.Changed(dial.assign('c', bob)), dial.click('c', Pending.Add(bob)))
        assertEquals(SpeedClick.None, dial.click('a', Pending.Add(bob)))
        assertEquals(SpeedClick.Changed(dial.move('a', 'c')), dial.click('c', Pending.Move('a')))
        assertEquals(SpeedClick.Changed(dial), dial.click('a', Pending.Move('a')))
        assertEquals(SpeedClick.None, dial.click('w', Pending.Move('a')))
    }

    @Test fun theMenuOfARow() {
        assertEquals(listOf(SpeedAct.CALL, SpeedAct.EDIT, SpeedAct.MOVE, SpeedAct.DELETE), speedMenu(dial.rows()[0]))
        assertEquals(listOf(SpeedAct.ASSIGN), speedMenu(dial.rows()[2]))
        assertEquals(listOf(SpeedAct.CALL), speedMenu(dial.rows().first { it.voiceMail }))
    }
}
