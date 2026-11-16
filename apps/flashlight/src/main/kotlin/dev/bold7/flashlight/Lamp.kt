package dev.bold7.flashlight

import android.view.KeyEvent

/** What a key does on the Flashlight's screen. */
enum class LampKey { TOGGLE, CLOSE, MENU }

/** Trackpad click, Enter and Space switch; Escape and Back close; held keys do nothing more. */
fun lampKey(code: Int, repeat: Int): LampKey? = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> if (repeat == 0) LampKey.TOGGLE else null
    KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> if (repeat == 0) LampKey.CLOSE else null
    KeyEvent.KEYCODE_MENU -> if (repeat == 0) LampKey.MENU else null
    else -> null
}

/**
 * The torch as the screen shows it. [on] is what the camera service last reported, never what was
 * asked, so another app taking the camera shows at once; [pending] is a request not yet answered.
 */
data class Lamp(
    val flash: Boolean = true,
    val on: Boolean = false,
    val busy: Boolean = false,
    val pending: Boolean = false,
) {
    enum class Status { OFF, ON, IN_USE, NO_FLASH }

    /** What to ask of the camera ([command], null for nothing) and the state to keep meanwhile. */
    data class Toggle(val command: Boolean?, val next: Lamp)

    val status get() = when {
        !flash -> Status.NO_FLASH
        busy -> Status.IN_USE
        on -> Status.ON
        else -> Status.OFF
    }

    fun toggle(): Toggle =
        if (!flash || busy) Toggle(null, this) else (!on).let { Toggle(it, copy(pending = true)) }

    fun reported(on: Boolean) = copy(on = on, busy = false, pending = false)
    fun unavailable() = copy(on = false, busy = true, pending = false)
    fun noFlash() = copy(flash = false, on = false)
}
