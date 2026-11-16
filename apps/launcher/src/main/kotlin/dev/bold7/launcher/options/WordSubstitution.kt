package dev.bold7.launcher.options

// Typing's data as Options shows and edits it: Word Learning (sim-92a, C) and
// the user's own Word Substitution list (guide 7.1 p. 216 and 217, B). Typing (apps/saisie) keeps
// both; Options reaches them through TypingOptions, off the main thread. Pure.

/** An entry: Replace, With, Using SmartCase (true) or Specified Case (guide p. 217, B). */
data class Substitution(val replace: String, val with: String, val smart: Boolean = true)

data class TypingData(val learning: Boolean, val substitutions: List<Substitution>)

/** Typing's provider (dev.bold7.platform.TypingContract); null or false when Typing is absent or refuses. Blocks on binder: io only. */
interface TypingOptions {
    fun read(): TypingData?
    fun setLearning(on: Boolean): Boolean
    fun forget(): Boolean
    fun setSubstitutions(rows: List<Substitution>): Boolean
}

/** Word Learning's note under its Typing box (sim-92a, C). */
const val LEARNING_NOTE = "This option adds new words that you type to your custom dictionary."

/** The button that deletes every learned word (D: no capture nor guide line for it). */
const val DELETE_LEARNED = "Delete Learned Words"

/** Its question (D). */
const val DELETE_LEARNED_QUESTION = "Delete all the words learned from your typing?"

const val ENTER_REPLACE = "Type the text to replace: one word, letters and digits."
const val ENTER_WITH = "Type the replacement text."

/**
 * Insert Macro (guide p. 217, B: "for example, the current date or time"): names and codes D, the
 * codes those apps/saisie expands (Substitutions.MACROS); OS 7's number and PIN have no value there.
 */
val MACROS = listOf(
    "Short Date" to "%d",
    "Long Date" to "%D",
    "Short Time" to "%t",
    "Long Time" to "%T",
    "Battery Level" to "%b",
    "Software Version" to "%V",
    "Percent Sign" to "%%",
)

/** [new] in place of [old] (null: a new entry) and of any entry with its Replace, case aside; sorted by Replace (D). */
fun List<Substitution>.put(old: Substitution?, new: Substitution): List<Substitution> =
    (filter { it != old && !it.replace.equals(new.replace, ignoreCase = true) } + new).sortedBy { it.replace.lowercase() }

fun List<Substitution>.remove(entry: Substitution): List<Substitution> = filter { it != entry }

/**
 * What keeps an entry from being saved, null when nothing does: Replace is one word of letters,
 * digits and apostrophes, what Typing takes as the word before a Space.
 */
fun problem(replace: String, with: String): String? = when {
    replace.isBlank() || !replace.trim().all { it.isLetterOrDigit() || it == '\'' } -> ENTER_REPLACE
    with.isBlank() -> ENTER_WITH
    else -> null
}

/** The entries whose Replace or With contain [query], case aside (the Search field of the list, sim-300, C). */
fun List<Substitution>.matching(query: String): List<Substitution> =
    if (query.isBlank()) this else filter { it.replace.contains(query.trim(), ignoreCase = true) || it.with.contains(query.trim(), ignoreCase = true) }

/** A row of the list: "abotu (about)" at the left, its mode at the right (sim-300, C). */
val Substitution.rowText get() = "$replace ($with)"
val Substitution.mode get() = if (smart) "SmartCase" else "Specified Case"
