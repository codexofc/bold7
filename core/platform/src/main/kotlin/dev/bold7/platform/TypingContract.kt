package dev.bold7.platform

/**
 * What Options (apps/launcher) asks of Typing (apps/saisie) through its provider:
 * ContentResolver.call on [AUTHORITY], behind [PERMISSION], Bold7's signature permission declared by
 * dev.bold7.saisie. Typing keeps the data; Options only shows it and sends the user's changes.
 */
object TypingContract {
    const val AUTHORITY = "dev.bold7.saisie.options"
    const val PERMISSION = "dev.bold7.permission.TYPING_OPTIONS"

    /** Gives [LEARNING] and the personal list ([REPLACE], [WITH], [SMART]). */
    const val READ = "read"
    /** Word Learning on ("1") or off ("0"), as the call's arg. */
    const val SET_LEARNING = "learning"
    /** Deletes every learned word. */
    const val FORGET = "forget"
    /** The whole personal list, in the extras ([REPLACE], [WITH], [SMART]). */
    const val SET_SUBSTITUTIONS = "substitutions"

    const val LEARNING = "learning"
    const val REPLACE = "replace"
    const val WITH = "with"
    const val SMART = "smart"
}
