package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Options > Typing and Language > Keyboard (guide 7.1 p. 220, B), as Options' OptionsStore writes it. */
class KeyboardOptionsTest {
    @Test fun keyToneOnlyWhenTurnedOnAndTheProfileRings() {
        assertTrue(keyTone("1", Kind.TEXT, ringing = true, keyboard = true, repeat = 0))
        assertFalse(keyTone(null, Kind.TEXT, ringing = true, keyboard = true, repeat = 0), "off by default")
        assertFalse(keyTone("0", Kind.TEXT, ringing = true, keyboard = true, repeat = 0))
        assertFalse(keyTone("1", Kind.TEXT, ringing = false, keyboard = true, repeat = 0), "silent or vibrate profile")
    }

    @Test fun keyToneForKeyboardKeysOnceInAField() {
        assertFalse(keyTone("1", Kind.TEXT, ringing = true, keyboard = false, repeat = 0), "the trackpad, volume, Back")
        assertFalse(keyTone("1", Kind.TEXT, ringing = true, keyboard = true, repeat = 1), "a held key's repeats")
        assertFalse(keyTone("1", Kind.PASS, ringing = true, keyboard = true, repeat = 0), "keys Typing leaves to the app")
        assertTrue(keyTone("1", Kind.PLAIN, ringing = true, keyboard = true, repeat = 0), "the same tone for every key: nothing of a password told")
    }

    @Test fun currencyIsOneOfOptionsFourElseTheDollar() {
        assertEquals('€', currencyOf("€"))
        assertEquals('£', currencyOf("£"))
        assertEquals('¥', currencyOf("¥"))
        assertEquals('$', currencyOf(null))
        assertEquals('$', currencyOf("x"))
        assertEquals('$', currencyOf("€€"))
    }

    @Test fun theQ25DollarKeyTypesTheDollarItsMapLeavesOut() {
        // KEY_GRAVE (41), read with getevent: the Q25's character map gives it no character.
        assertEquals('$', keyChar(android.view.KeyEvent.KEYCODE_GRAVE, 0))
        assertEquals('$', keyChar(android.view.KeyEvent.KEYCODE_GRAVE, '`'.code), "a map that gives it a backquote")
        assertEquals('a', keyChar(android.view.KeyEvent.KEYCODE_A, 'a'.code), "a mapped key keeps its character")
        assertEquals(0.toChar(), keyChar(android.view.KeyEvent.KEYCODE_F1, 0), "an unmapped key stays unmapped")
    }
}
