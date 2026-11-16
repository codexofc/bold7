package dev.bold7.ui

/**
 * The keys a menu took: a key pressed while a menu is open belongs to it, its repeats and its
 * release too. The views choose on the press (the click of the trackpad, Enter, Escape): the menu
 * closes, and without this the release reached the page under it, a click on a date header
 * opening its short menu over the list (Messages, Display Style). A release while the
 * menu is still open goes on, for the views that choose on it (the home). Pure.
 */
class MenuKeyGate {
    private val taken = mutableSetOf<Int>()

    /** Whether the event of key [code] ([down]: a press, [repeat] its count) stops here; [menuOpen] when it comes. */
    fun swallows(code: Int, down: Boolean, repeat: Int, menuOpen: Boolean): Boolean {
        if (down && repeat == 0) {
            if (menuOpen) taken += code else taken -= code
            return false
        }
        val mine = if (down) code in taken else taken.remove(code)
        return mine && !menuOpen
    }
}
