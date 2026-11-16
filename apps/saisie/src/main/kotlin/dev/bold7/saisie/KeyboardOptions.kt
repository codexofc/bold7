package dev.bold7.saisie

/*
 * Options > Typing and Language > Keyboard (guide 7.1 p. 220, B), written by the launcher's
 * OptionsStore in Settings.Global and read by Typing at each field: Key Tone ("1" on, off by
 * default) and Currency Key ("$", "€", "£" or "¥").
 */

/**
 * Key Tone ("Turn on key tones"): a tone for a key of the keyboard pressed in a field Typing
 * types in, once per press (D), never while the profile is silent or vibrate (D). The same tone
 * for every key, a password's included (D: it tells nothing of what is typed).
 */
fun keyTone(setting: String?, kind: Kind, ringing: Boolean, keyboard: Boolean, repeat: Int) =
    setting == "1" && kind != Kind.PASS && ringing && keyboard && repeat == 0

/** Currency Key ("Change the default currency symbol"): what the $ key types; anything else, the dollar. */
fun currencyOf(setting: String?): Char = setting?.singleOrNull()?.takeIf { it in "$€£¥" } ?: '$'

/**
 * The character of a keyboard key: its map's [mapped] (0 when none), but the Q25's $ key sends
 * KEY_GRAVE (read with getevent -lt), which its character map leaves out and other maps
 * give « ` » to : the Q25 has no backquote key, so it always types the dollar, which Typist then
 * turns into Options' Currency Key.
 */
fun keyChar(keyCode: Int, mapped: Int): Char =
    if (keyCode == android.view.KeyEvent.KEYCODE_GRAVE) '$' else mapped.toChar()
