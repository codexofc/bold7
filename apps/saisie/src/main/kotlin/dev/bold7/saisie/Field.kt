package dev.bold7.saisie

/**
 * How Typing treats a text field.
 * - PASS: every key goes to the app untouched (no text field, terminals, numbers, phone, dates).
 * - PLAIN: Typing types the keys (hold for a capital, accents, Alt, Shift, locks) but never reads
 *   the field, never substitutes, never capitalizes by itself: passwords, addresses, incognito.
 * - EMAIL: PLAIN, plus Space giving @ then . (guide 7.1, "Shortcuts", B).
 * - TEXT: everything of OS 7's Direct style.
 */
enum class Kind { PASS, PLAIN, EMAIL, TEXT }

data class Field(
    val kind: Kind,
    val capsSentences: Boolean = false,
    val capsWords: Boolean = false,
    val capsChars: Boolean = false,
    val substitute: Boolean = false,
)

// android.text.InputType and EditorInfo, as in the SDK (pure code: no android import).
private const val MASK_CLASS = 0x0f
private const val CLASS_TEXT = 0x01
private const val MASK_VARIATION = 0xff0
private const val VARIATION_URI = 0x10
private const val VARIATION_EMAIL_ADDRESS = 0x20
private const val VARIATION_PASSWORD = 0x80
private const val VARIATION_VISIBLE_PASSWORD = 0x90
private const val VARIATION_WEB_EMAIL_ADDRESS = 0xd0
private const val VARIATION_WEB_PASSWORD = 0xe0
private const val FLAG_CAP_CHARACTERS = 0x1000
private const val FLAG_CAP_WORDS = 0x2000
private const val FLAG_CAP_SENTENCES = 0x4000
private const val FLAG_NO_SUGGESTIONS = 0x80000
private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

/** The field an EditorInfo describes, from its inputType and imeOptions. */
fun fieldOf(inputType: Int, imeOptions: Int): Field {
    if (inputType and MASK_CLASS != CLASS_TEXT) return Field(Kind.PASS)
    when (inputType and MASK_VARIATION) {
        VARIATION_PASSWORD, VARIATION_VISIBLE_PASSWORD, VARIATION_WEB_PASSWORD, VARIATION_URI -> return Field(Kind.PLAIN)
        VARIATION_EMAIL_ADDRESS, VARIATION_WEB_EMAIL_ADDRESS -> return Field(Kind.EMAIL)
    }
    if (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return Field(Kind.PLAIN)
    return Field(
        Kind.TEXT,
        capsSentences = inputType and FLAG_CAP_SENTENCES != 0,
        capsWords = inputType and FLAG_CAP_WORDS != 0,
        capsChars = inputType and FLAG_CAP_CHARACTERS != 0,
        // An app that asks for no suggestions (user names, codes) gets no substitution either (D).
        substitute = inputType and FLAG_NO_SUGGESTIONS == 0,
    )
}

/**
 * Whether the next letter typed after [before] is a capital, as TextUtils.getCapsMode decides it
 * (Android's rule, which OS 7's matches for sentences: B, guide 7.1, "Insert a period"). [complete]
 * says [before] reaches the start of the text.
 */
fun capsAt(before: String, complete: Boolean, field: Field): Boolean {
    if (field.kind != Kind.TEXT) return false
    if (field.capsChars) return true
    if (!field.capsWords && !field.capsSentences) return false
    var i = before.length
    // Back over opening punctuation.
    while (i > 0 && before[i - 1].let { it == '"' || it == '\'' || Character.getType(it) == Character.START_PUNCTUATION.toInt() }) i--
    var j = i
    while (j > 0 && before[j - 1].let { it == ' ' || it == '\t' }) j--
    if (j == 0) return complete
    if (before[j - 1] == '\n') return true
    if (!field.capsSentences) return i != j
    if (i == j) return false
    // Back over closing punctuation.
    while (j > 0 && before[j - 1].let { it == '"' || it == '\'' || Character.getType(it) == Character.END_PUNCTUATION.toInt() }) j--
    if (j == 0) return false
    val c = before[j - 1]
    if (c != '.' && c != '?' && c != '!') return false
    if (c == '.') {
        // A period inside the word too: an abbreviation (e.g.).
        var k = j - 2
        while (k >= 0 && before[k].isLetter()) k--
        if (k >= 0 && before[k] == '.') return false
    }
    return true
}
