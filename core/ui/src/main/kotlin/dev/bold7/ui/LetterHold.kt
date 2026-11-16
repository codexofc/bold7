package dev.bold7.ui

/**
 * A letter key whose action waits for its up, so that holding it can mean something else: OS 7's
 * speed dial (guide 7.1 p. 65: "from the Phone application, the home screen, a message, or a
 * message list, press and hold the key"). A short press is a [Out.Tap] on its up; Android's first
 * repeat of the down (the long press) is a [Out.Hold], once, and the up after it is nothing. Pure:
 * the views feed it key codes. Main thread only.
 */
class LetterHold {
    sealed interface Out {
        val letter: Char
        data class Tap(override val letter: Char) : Out
        data class Hold(override val letter: Char) : Out
    }

    private var key = NONE
    private var letter = ' '
    private var waiting = false

    /** A down of [keyCode] typing [letter] ([repeat] 0 for the first): what is decided now, in order. */
    fun down(keyCode: Int, letter: Char, repeat: Int): List<Out> {
        // A repeat of the key held; a first down of it again (its up was lost) starts over.
        if (keyCode == key && repeat > 0) {
            if (repeat != 1 || !waiting) return emptyList()
            waiting = false
            return listOf(Out.Hold(this.letter))
        }
        val before = flush()
        key = keyCode
        this.letter = letter
        waiting = true
        return listOfNotNull(before)
    }

    /** The up of [keyCode]: the tap it ends, none after a hold or for another key. */
    fun up(keyCode: Int): Out.Tap? {
        if (keyCode != key) return null
        val tap = if (waiting) Out.Tap(letter) else null
        clear()
        return tap
    }

    /** Another key came: the waiting letter acts as a tap now. */
    fun flush(): Out.Tap? {
        val tap = if (waiting) Out.Tap(letter) else null
        waiting = false
        return tap
    }

    /** The screen goes away: nothing waiting is kept. */
    fun clear() {
        key = NONE
        waiting = false
    }

    private companion object {
        const val NONE = -1
    }
}
