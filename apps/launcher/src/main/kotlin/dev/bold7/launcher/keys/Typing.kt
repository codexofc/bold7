package dev.bold7.launcher.keys

import dev.bold7.launcher.search.Folded
import dev.bold7.launcher.search.matchRanges
import java.io.File

// What typing on the home does, as a pure state machine: no android.*, tested on the JVM. OS 7.1
// has the Launch by Typing field (guide 7.1 p. 24): Universal Search, where a key typed opens the
// search with it (sim-54), or Application Shortcuts, where a key opens an app.

enum class LaunchByTyping { UNIVERSAL_SEARCH, APPLICATION_SHORTCUTS }

/** [text] typed on the home; [searching] once the search is asked for with it. */
data class Typing(val text: String = "", val searching: Boolean = false)

sealed interface TypingEvent {
    /** A first down (no repeat) of a key that types [char]. */
    data class Down(val char: Char) : TypingEvent

    /** The home goes away, or the home key: nothing typed is kept. */
    data object Reset : TypingEvent
}

sealed interface TypingEffect {
    data class Search(val text: String) : TypingEffect

    /** Application Shortcuts: the app of [letter]. */
    data class Open(val letter: Char) : TypingEffect

    /** Application Shortcuts: H opens Help, which has no icon (sim-288 then sim-440, C). */
    data object Help : TypingEffect
}

fun type(state: Typing, mode: LaunchByTyping, event: TypingEvent): Pair<Typing, TypingEffect?> = when (event) {
    is TypingEvent.Down -> when {
        // The search is open: the field takes the keys, the letters are only kept.
        state.searching -> state.copy(text = state.text + event.char) to null
        mode == LaunchByTyping.APPLICATION_SHORTCUTS && event.char.lowercaseChar() == 'h' -> state to TypingEffect.Help
        mode == LaunchByTyping.APPLICATION_SHORTCUTS -> state to TypingEffect.Open(event.char)
        else -> Typing(text = state.text + event.char, searching = true).let { it to TypingEffect.Search(it.text) }
    }
    TypingEvent.Reset -> Typing() to null
}

/** The setting as saved, universal search when absent or unreadable. */
fun parseLaunchByTyping(text: String?): LaunchByTyping = LaunchByTyping.entries.firstOrNull { it.name == text?.trim() } ?: LaunchByTyping.UNIVERSAL_SEARCH

/** The apps a letter opens before the first of its initial: M opens Messages on the simulator, MemoPad being there too (sim-288, C). */
private val PREFERRED = mapOf('m' to "Messages")

/**
 * The index of the first of [labels] that [letter] starts, or null. D: the guide describes
 * Application Shortcuts but lists no letter, so the letter is the app's own initial, but for
 * [PREFERRED] (sim-288, C).
 */
fun appForLetter(letter: Char, labels: List<String>): Int? {
    if (!letter.isLetter()) return null
    PREFERRED[letter.lowercaseChar()]?.let { p -> labels.indexOf(p).takeIf { it >= 0 }?.let { return it } }
    return labels.indexOfFirst { matchRanges(Folded(it.take(1)), letter.toString()) != null }.takeIf { it >= 0 }
}

/** Launch by Typing kept in a file of the launcher (credential-protected storage, like the home's layout). */
class LaunchByTypingSetting(private val file: File) {
    @Volatile
    var mode = LaunchByTyping.UNIVERSAL_SEARCH
        private set

    /** Reads the file: io only. */
    fun load() {
        mode = parseLaunchByTyping(file.takeIf { it.exists() }?.readText())
    }

    /** Writes [new] and makes it the mode: io only. */
    fun save(new: LaunchByTyping) {
        file.writeText(new.name)
        mode = new
    }
}
