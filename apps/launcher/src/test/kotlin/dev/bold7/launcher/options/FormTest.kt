package dev.bold7.launcher.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormTest {
    private val sensitivity = (100 downTo 20 step 10).map { it.toString() }

    // A form like Typing (sim-91a): a note, a Style field with a value refused, the greyed button.
    private val typing = Form(
        "Typing",
        listOf(
            Group("Input Language", listOf(Note("English(US)", "The availability…", enabled = true))),
            Group("Physical Keyboard", listOf(Choice("Style:", listOf("Direct", "Predictive"), 0, unavailable = setOf(1)), Button("Advanced Style Options", enabled = false))),
        ),
    )

    // Trackpad Sensitivity (sim-66): two choices and a check box.
    private val trackpad = Form(
        "Trackpad Sensitivity",
        listOf(Group("Trackpad", listOf(Choice("Horizontal Sensitivity:", sensitivity, 3), Choice("Vertical Sensitivity:", sensitivity, 3), Check("Audible Roll:", false)))),
    )

    private fun Form.press(vararg keys: FormKey): Form = keys.fold(this) { f, k -> (f.key(k) as FormResult.Show).form }

    @Test
    fun `the focus starts on the first field it can take and skips the disabled ones`() {
        assertEquals(0, trackpad.focus)
        val t = Form("t", listOf(Group("g", listOf(Button("b", enabled = false), Check("c", false)))))
        assertEquals(1, t.focus)
        assertEquals(1, typing.press(FormKey.DOWN).focus)
        // Advanced Style Options is greyed: the focus stops on Style.
        assertEquals(1, typing.press(FormKey.DOWN, FormKey.DOWN).focus)
        assertEquals(0, typing.press(FormKey.UP).focus)
    }

    @Test
    fun `a click opens a choice on its value, the trackpad moves in it, a click chooses`() {
        val open = trackpad.press(FormKey.CLICK)
        assertEquals(3, open.open?.chosen)
        val chosen = open.press(FormKey.UP, FormKey.UP, FormKey.CLICK)
        assertEquals(null, chosen.open)
        assertEquals(1, (chosen.fields[0] as Choice).chosen)
        assertTrue(chosen.changed)
    }

    @Test
    fun `Back closes the list without choosing`() {
        val back = trackpad.press(FormKey.CLICK, FormKey.DOWN, FormKey.BACK)
        assertEquals(null, back.open)
        assertFalse(back.changed)
    }

    @Test
    fun `an unavailable value is not chosen, the list stays open`() {
        val open = typing.press(FormKey.DOWN, FormKey.CLICK, FormKey.DOWN)
        assertEquals(1, open.open?.chosen)
        val after = open.press(FormKey.CLICK)
        assertEquals(1, after.open?.chosen)
        assertEquals(0, (after.fields[1] as Choice).chosen)
    }

    @Test
    fun `a click or Space toggles a check box`() {
        val checked = trackpad.press(FormKey.DOWN, FormKey.DOWN, FormKey.CLICK)
        assertTrue((checked.fields[2] as Check).checked)
        assertFalse((checked.press(FormKey.SPACE).fields[2] as Check).checked)
        // Space on a choice does nothing.
        assertEquals(trackpad, trackpad.press(FormKey.SPACE))
    }

    @Test
    fun `a click on a note or a button is the screen's to act on`() {
        assertEquals(FormResult.Act(0), typing.key(FormKey.CLICK))
    }

    @Test
    fun `Back leaves, or asks when something changed (sim-47)`() {
        assertEquals(FormResult.Leave, trackpad.key(FormKey.BACK))
        val changed = trackpad.press(FormKey.DOWN, FormKey.DOWN, FormKey.CLICK)
        assertEquals(FormResult.AskSave, changed.key(FormKey.BACK))
        // Changed back by hand: nothing to save.
        assertEquals(FormResult.Leave, changed.press(FormKey.CLICK).key(FormKey.BACK))
    }

    @Test
    fun `a tap focuses the field and acts as a click`() {
        val tapped = (trackpad.tap(2) as FormResult.Show).form
        assertEquals(2, tapped.focus)
        assertTrue((tapped.fields[2] as Check).checked)
        // A disabled field takes no tap.
        assertEquals(FormResult.Show(typing), typing.tap(2))
    }

    @Test
    fun `saved, the form forgets its changes`() {
        val changed = trackpad.press(FormKey.DOWN, FormKey.DOWN, FormKey.CLICK)
        assertFalse(changed.saved().changed)
        assertEquals(2, changed.saved().focus)
    }
}

// The room of the form's hand-drawn texts.
class FormRoomTest {
    @Test fun eachTextStaysBetweenItsMargins() {
        assertEquals(696f, FormRoom.groupTitle(720, 12))
        assertEquals(702f, FormRoom.noteText(720, 9))
        assertEquals(658f, FormRoom.buttonLabel(720, 13, 9, 20))
        assertEquals(0f, FormRoom.buttonLabel(40, 13, 9, 20))
    }

    // A list of long values (Time Zone) as wide as they need, growing to the left.
    @Test fun anOpenListTakesItsWidestValue() {
        assertEquals(78 to 460, FormRoom.choiceList(widest = 30f, fieldLeft = 460, width = 720))
        assertEquals(212 to 460, FormRoom.choiceList(widest = 200f, fieldLeft = 460, width = 720))
        // Longer: it grows to the left, its right edge where it was (its left 280 + 8, its right 700).
        assertEquals(412 to 280, FormRoom.choiceList(widest = 400f, fieldLeft = 460, width = 720))
        // Never past the row's margin of 9.
        assertEquals(691 to 1, FormRoom.choiceList(widest = 900f, fieldLeft = 460, width = 720))
    }
}
