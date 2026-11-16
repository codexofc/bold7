package dev.bold7.launcher.options

import dev.bold7.launcher.R

// The pages of Options, pure: the lists and where each entry leads. Labels in English,
// OS 7's own. Sources: the main list's first seven rows, their subtitles and icons (sim-57,
// sim-58, C; cat_*.png, A); Change Sounds and Alerts (sim-30, C); the other rows' names from the
// guide 7.1's "Click <list> > <row>" (B), in an order of our own and without subtitle (D: the
// simulator could not be driven). The entries Bold7 does not draw yet open Android's
// page for the same thing.

/** A screen of Options: a list ([listing]) or a form, read and saved by OptionsStore. */
enum class Screen {
    MAIN, SOUNDS, DISPLAY, TYPING_LANGUAGE, PHONE_TONES, SCREEN_DISPLAY, TYPING, ADVANCED_STYLE, TRACKPAD, HOME,

    // The lists under Sounds, Networks, Device and Security, and their forms.
    PROFILE_SOUNDS, NOTIFIER, REMINDERS, PROFILES, DATE_TIME, LANGUAGE, NETWORKS, DEVICE, ABOUT, STORAGE, BATTERY, SECURITY, ENCRYPTION,

    // The forms that replace Android's pages for Touchscreen Sensitivity, Keyboard and Accessibility.
    TOUCHSCREEN, KEYBOARD, ACCESSIBILITY,
}

sealed interface Target {
    data class Open(val screen: Screen) : Target

    /** Android's page, the action of its intent: what Options does not draw yet. */
    data class Android(val action: String) : Target

    /** Word Substitution, the user's own list (WordSubstitutionActivity). */
    data object Substitutions : Target

    /** The home, with Manage Connections open over it. */
    data object Connections : Target

    /** Wi-Fi Network (WifiActivity). */
    data object Wifi : Target

    /** Setup of the texts through Google Messages (TextSetupActivity). */
    data object TextSetup : Target
}

/** The home's extra that opens Manage Connections once it is in front (Options > Networks and Connections). */
const val EXTRA_MANAGE_CONNECTIONS = "dev.bold7.launcher.MANAGE_CONNECTIONS"

/** A row of a list: its cat_*.png of the theme (A), title, grey subtitle, what it opens. */
data class Entry(val icon: Int, val title: String, val subtitle: String, val target: Target)

// Settings' intent actions, as strings: constants of android.provider.Settings and TelecomManager.
private const val SOUND = "android.settings.SOUND_SETTINGS"
private const val OPERATOR = "android.settings.NETWORK_OPERATOR_SETTINGS"
private const val BLUETOOTH = "android.settings.BLUETOOTH_SETTINGS"
private const val NFC = "android.settings.NFC_SETTINGS"
private const val NEW_PASSWORD = "android.app.action.SET_NEW_PASSWORD"
private const val CALLS = "android.telecom.action.SHOW_CALL_SETTINGS"
private const val LOCK_SCREEN = "android.settings.LOCK_SCREEN_SETTINGS"
private const val INPUT = "android.settings.INPUT_METHOD_SETTINGS"
private const val DICTIONARY = "android.settings.USER_DICTIONARY_SETTINGS"


fun title(screen: Screen): String = when (screen) {
    Screen.MAIN -> "Options"
    Screen.SOUNDS -> "Change Sounds and Alerts"
    Screen.DISPLAY -> "Display"
    Screen.TYPING_LANGUAGE -> "Typing and Language"
    Screen.PHONE_TONES -> "Phone"
    Screen.SCREEN_DISPLAY -> "Screen Display"
    Screen.TYPING -> "Typing"
    Screen.ADVANCED_STYLE -> "Advanced Style Options"
    Screen.TRACKPAD -> "Trackpad Sensitivity"
    Screen.HOME -> "Home Screen Preferences"
    Screen.PROFILE_SOUNDS -> "Sounds for Selected Profile"
    Screen.NOTIFIER -> "Messages and Email"
    Screen.REMINDERS -> "Reminders"
    Screen.PROFILES -> "Profile Management"
    Screen.DATE_TIME -> "Date and Time"
    Screen.LANGUAGE -> "Language"
    Screen.NETWORKS -> "Networks and Connections"
    Screen.DEVICE -> "Device"
    Screen.ABOUT -> "About"
    Screen.STORAGE -> "Storage"
    Screen.BATTERY -> "Battery"
    Screen.SECURITY -> "Security"
    Screen.ENCRYPTION -> "Encryption"
    Screen.TOUCHSCREEN -> "Touchscreen Sensitivity"
    Screen.KEYBOARD -> "Keyboard"
    Screen.ACCESSIBILITY -> "Accessibility"
}

private fun open(s: Screen) = Target.Open(s)
private fun android(action: String) = Target.Android(action)

/** The entries of a list screen; empty for a form. */
fun entries(screen: Screen): List<Entry> = when (screen) {
    Screen.MAIN -> listOf(
        Entry(R.drawable.opt_sound, "Sounds and Ringtones", "Ringtones, reminders, profiles", open(Screen.SOUNDS)),
        Entry(R.drawable.opt_connectivity, "Networks and Connections", "Bluetooth connections, Wi-Fi, mode", open(Screen.NETWORKS)),
        Entry(R.drawable.opt_display, "Display", "Date, time, themes, screen", open(Screen.DISPLAY)),
        Entry(R.drawable.opt_typing, "Typing and Language", "Text input, keyboard, languages", open(Screen.TYPING_LANGUAGE)),
        Entry(R.drawable.opt_device, "Device", "PIN, auto on/off, battery, storage", open(Screen.DEVICE)),
        Entry(R.drawable.opt_calls, "Call Management", "Call behaviour, voice mail, speed dial", android(CALLS)),
        Entry(R.drawable.opt_security, "Security", "Password, encryption, firewall", open(Screen.SECURITY)),
        // Under Security, sim-58's arrow shows more; the guide names Accessibility (B).
        Entry(R.drawable.opt_accessibility, "Accessibility", "", open(Screen.ACCESSIBILITY)),
    )
    // sim-30 (C), which "Change Sounds and Alerts" of "Select a Profile" opens; Sounds and Ringtones too (D).
    Screen.SOUNDS -> listOf(
        Entry(R.drawable.opt_phone_ringtone, "Phone Ringtone", "Set ringtone for chosen profile", open(Screen.PHONE_TONES)),
        Entry(R.drawable.opt_profile_sounds, "Sounds for Selected Profile", "Set any alert in chosen profile", open(Screen.PROFILE_SOUNDS)),
        Entry(R.drawable.opt_contact_sounds, "Sounds for Contacts", "Add alerts for one or more contacts", android(SOUND)),
        Entry(R.drawable.opt_profiles, "Profile Management", "Add, edit or delete a sound profile", open(Screen.PROFILES)),
    )
    // Guide p. 160: "click Phone", then the notifiers and reminders (B); the sections are our own (D).
    Screen.PROFILE_SOUNDS -> listOf(
        Entry(R.drawable.opt_phone_ringtone, "Phone", "", open(Screen.PHONE_TONES)),
        Entry(R.drawable.opt_messages, "Messages and Email", "", open(Screen.NOTIFIER)),
        Entry(R.drawable.opt_reminders, "Reminders", "", open(Screen.REMINDERS)),
    )
    // The pages Manage Connections opens; the rows' names are the guide's (B), their order D.
    Screen.NETWORKS -> listOf(
        Entry(R.drawable.opt_connectivity, "Manage Connections", "", Target.Connections),
        Entry(R.drawable.opt_mobile, "Mobile Network", "", android(OPERATOR)),
        Entry(R.drawable.opt_wifi, "Wi-Fi Network", "", Target.Wifi),
        Entry(R.drawable.opt_bluetooth, "Bluetooth Connections", "", android(BLUETOOTH)),
        Entry(R.drawable.opt_nfc, "NFC", "", android(NFC)),
    )
    // The theme's cat_device_* (A); which rows the Q25 has (no PIN, no auto on/off) is D.
    Screen.DEVICE -> listOf(
        Entry(R.drawable.opt_about, "About", "", open(Screen.ABOUT)),
        Entry(R.drawable.opt_memory, "Storage", "", open(Screen.STORAGE)),
        Entry(R.drawable.opt_status, "Battery", "", open(Screen.BATTERY)),
        // Where Setup's sections live once the first run is over: OS 7 has no Messages Options page for this, so Device it is (D).
        Entry(R.drawable.opt_messages, "Text Messages Setup", "", Target.TextSetup),
    )
    // Verification of the password stays Android's: Password opens its own screen for it (D).
    Screen.SECURITY -> listOf(
        Entry(R.drawable.opt_password, "Password", "", android(NEW_PASSWORD)),
        Entry(R.drawable.opt_encryption, "Encryption", "", open(Screen.ENCRYPTION)),
    )
    // Guide p. 24, 226 to 229 (B); Date and Time from sim-57's subtitle "Date, time" and
    // cat_display_date_time (A), its name D. Message Categorization (messages' folders) has nothing on the Q25.
    Screen.DISPLAY -> listOf(
        Entry(R.drawable.opt_screen_display, "Screen Display", "", open(Screen.SCREEN_DISPLAY)),
        Entry(R.drawable.opt_date_time, "Date and Time", "", open(Screen.DATE_TIME)),
        Entry(R.drawable.opt_lock_message, "Message on Lock Screen", "", android(LOCK_SCREEN)),
    )
    // Guide p. 213 to 219 and "Language" (B).
    Screen.TYPING_LANGUAGE -> listOf(
        Entry(R.drawable.opt_language, "Language", "", open(Screen.LANGUAGE)),
        Entry(R.drawable.opt_typing_style, "Typing", "", open(Screen.TYPING)),
        Entry(R.drawable.opt_trackpad, "Trackpad Sensitivity", "", open(Screen.TRACKPAD)),
        Entry(R.drawable.opt_touchscreen, "Touchscreen Sensitivity", "", open(Screen.TOUCHSCREEN)),
        Entry(R.drawable.opt_keyboard, "Keyboard", "", open(Screen.KEYBOARD)),
        Entry(R.drawable.opt_dictionary, "Custom Dictionary", "", android(DICTIONARY)),
        Entry(R.drawable.opt_substitution, "Word Substitution", "", Target.Substitutions),
        Entry(R.drawable.opt_spell_check, "Spell Check", "", android(INPUT)),
    )
    else -> emptyList()
}

/** A list screen: its [entries] filtered by [query] (the Search field, D: titles and subtitles), [focus] among them. */
data class Listing(val screen: Screen, val entries: List<Entry> = entries(screen), val focus: Int = 0, val query: String = "") {
    val shown: List<Entry>
        get() = if (query.isBlank()) entries else entries.filter { it.title.contains(query.trim(), true) || it.subtitle.contains(query.trim(), true) }

    fun search(text: String) = copy(query = text, focus = 0)

    /** The trackpad moves the focus and stops at the ends. */
    fun step(down: Boolean) = copy(focus = (focus + if (down) 1 else -1).coerceIn(0, (shown.size - 1).coerceAtLeast(0)))

    /** What a click on the focus (or a tap on row [row]) opens; null on an empty list. */
    fun target(row: Int = focus): Target? = shown.getOrNull(row)?.target
}

/** Reset to Default Settings of Home Screen Preferences: the note over its button (sim-284, C) and the question asked first (D). */
const val RESET_HOME_NOTE = "When you click the Reset Settings button, you can select from a list of preferences to reset to factory default settings. Any customisations you made will be lost."
const val RESET_HOME_QUESTION = "Reset Home Screen Preferences to their default settings?"
