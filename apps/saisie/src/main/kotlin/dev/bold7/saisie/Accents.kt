package dev.bold7.saisie

// The accented letters a held key gives, in order (D: the 9900's lists are not sourced). Only the
// guide's example is B: hold U and slide left until ü appears (guide 7.1, "Shortcuts"), hence ü last.
private val ACCENTS = mapOf(
    'a' to "àáâãäåæ", 'c' to "ç", 'e' to "èéêë", 'i' to "ìíîï", 'n' to "ñ",
    'o' to "òóôõöøœ", 's' to "ß", 'u' to "ùúûü", 'y' to "ýÿ",
)

/** The letter [step] places from [letter] in its accents, the letter itself between the two ends. */
fun accent(letter: Char, step: Int): Char {
    val list = ACCENTS[letter.lowercaseChar()] ?: return letter
    val cycle = list.length + 1
    val i = Math.floorMod(step, cycle)
    if (i == 0) return letter
    val c = list[i - 1]
    return if (letter.isUpperCase()) c.uppercaseChar() else c
}
