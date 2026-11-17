package dev.bold7.saisie

/**
 * The Q25's character keys by Linux scan code, as its own map gives them (Q25_keyboard.kl and
 * .kcm of the device tree, the same on BenOS and LineageOS; a copy is in this module's tests,
 * which check the table against it). Typing reads this, never the event's character: since
 * Android 14, Android lays a layout of its own over a physical keyboard after the input method's
 * subtype (French gives AZERTY, English drops the Alt layer), and no such layout may change what
 * Typing types. Its Alt layer is OS 7's, the Sym grid's second page.
 */
object Q25Keys {
    data class Key(val base: Char, val shift: Char, val alt: Char)

    /** First scan code of each row, its letters, their Alt characters. */
    private val ROWS = listOf(
        Triple(16, "qwertyuiop", "#123()_-+@"),
        Triple(30, "asdfghjkl", "*456/:;'\""),
        Triple(44, "zxcvbnm", "789?!,."),
    )

    /** The key's characters, or null for a key that types none (Space, Enter and modifiers included). */
    fun of(scanCode: Int): Key? = when (scanCode) {
        KEY_0 -> Key('0', '0', '0')
        // KEY_GRAVE, the $ key: no character in the map, the dollar whatever the modifiers.
        KEY_GRAVE -> Key('$', '$', '$')
        else -> ROWS.firstNotNullOfOrNull { (first, letters, alts) ->
            (scanCode - first).takeIf { it in letters.indices }?.let { Key(letters[it], letters[it].uppercaseChar(), alts[it]) }
        }
    }

    private const val KEY_0 = 11
    private const val KEY_GRAVE = 41
}
