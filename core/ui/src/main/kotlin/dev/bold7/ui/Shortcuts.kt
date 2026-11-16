package dev.bold7.ui

/**
 * A screen's keyboard shortcuts, the Classic's letters:
 * one action per letter, whatever its case. The views read it from dispatchKeyEvent with the key's
 * displayLabel. Pure.
 */
class Shortcuts<A> internal constructor(private val keys: Map<Char, A>) {
    /** The action of [label]; none while a text field has the focus, where the letters are typed. */
    fun of(label: Char, textFocused: Boolean = false): A? = if (textFocused) null else keys[label.lowercaseChar()]
}

/** A table of [pairs], letter to action; a letter twice, in any case, is refused. */
fun <A> shortcuts(vararg pairs: Pair<Char, A>): Shortcuts<A> {
    val keys = pairs.associate { (letter, action) -> letter.lowercaseChar() to action }
    require(keys.size == pairs.size) { "a letter declared twice in ${pairs.map { it.first }}" }
    return Shortcuts(keys)
}
