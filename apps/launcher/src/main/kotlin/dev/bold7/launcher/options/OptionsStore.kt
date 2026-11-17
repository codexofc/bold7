package dev.bold7.launcher.options

import dev.bold7.launcher.home.HomeChoices
import dev.bold7.launcher.home.Panel
import dev.bold7.launcher.keys.LaunchByTyping
import dev.bold7.launcher.keys.LaunchByTypingSetting
import dev.bold7.launcher.profiles.Profile
import dev.bold7.launcher.profiles.ProfileVolumes
import dev.bold7.launcher.profiles.RINGING
import dev.bold7.platform.DeviceFactsSource
import dev.bold7.platform.DeviceSettings
import dev.bold7.platform.Tone
import dev.bold7.platform.ToneKind
import dev.bold7.platform.ZoneAndLanguage
import java.time.Instant

// The forms of Options read from Android and written back. Pure but for its two
// interfaces (DeviceSettings, ProfileVolumes), whose calls block on binder: io only.

/** bold7-trackpad's settings (system/module/trackpad): 20 to 100, 70 when absent. */
const val TRACKPAD_HORIZONTAL = "bold7_trackpad_horizontal"
const val TRACKPAD_VERTICAL = "bold7_trackpad_vertical"
const val TRACKPAD_AUDIBLE = "bold7_trackpad_audible"

// Settings.System's names, as strings (pure code).
const val SCREEN_OFF_TIMEOUT = "screen_off_timeout"
const val SCREEN_BRIGHTNESS = "screen_brightness"
const val SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"

// More of Settings.System and Settings.Global's names, as strings.
const val VIBRATE_WHEN_RINGING = "vibrate_when_ringing"
const val NOTIFICATION_LIGHT_PULSE = "notification_light_pulse"
const val TIME_12_24 = "time_12_24"
const val AUTO_TIME = "auto_time"
const val AUTO_TIME_ZONE = "auto_time_zone"

// Touchscreen Sensitivity's Hover Period is Android's touch-and-hold delay (Settings.Secure,
// guide p. 215: "touch and hold the screen to open a pop-up menu", B). The guide's Double Tap Interval and
// Open Pop-up Menu on Touch-hold have no setting in Android: not drawn (a field without effect would be false).
const val LONG_PRESS_TIMEOUT = "long_press_timeout"
val HOVER_LABELS = listOf("Fast", "Normal", "Slow")
val HOVER_MS = listOf(250, 400, 1000)

/** Keyboard's Key Tone and Currency Key (guide p. 220, B): Options only writes them, Typing (apps/saisie) will read them. */
const val SAISIE_KEY_TONE = "bold7_saisie_key_tone"
const val SAISIE_CURRENCY = "bold7_saisie_currency"
val CURRENCIES = listOf("$", "€", "£", "¥")

/** Accessibility's Color Contrast (guide p. 229, B): Android's colour inversion and its monochrome (greyscale) filter. */
const val INVERSION = "accessibility_display_inversion_enabled"
const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
const val DALTONIZER = "accessibility_display_daltonizer"
val CONTRASTS = listOf("Normal", "Greyscale", "Reverse Contrast")

/** Date and Time's Time Format values, "12" and "24" in Settings.System (D: the 9900 has them as its date format). */
val TIME_FORMATS = listOf("12 Hour", "24 Hour")

// The guide 7.1 (p. 200, B): Update Time is Automatic or Manual; Auto Update Time Zone is Prompt, On or
// Off, and Android has no prompt, so only On and Off are offered. Set Time and Set Date wait for SET_TIME.
val UPDATE_TIME = listOf("Automatic", "Manual")
val AUTO_ZONE = listOf("On", "Off")

/** Trackpad Sensitivity's values, 100 to 20 by 10 (sim-67, C). */
val SENSITIVITIES = (100 downTo 20 step 10).map { it.toString() }

/** Launch by Typing's values (guide 7.1 p. 24), in the order of LaunchByTyping. */
val LAUNCH_BY_TYPING = listOf("Universal Search", "Application Shortcuts")

/** Typing's Style (sim-91b, C), read by apps/saisie at each field. */
val STYLES = listOf("Direct", "Predictive")

/** Typing's style for apps/saisie: "predictive", or Direct ("direct" or absent). */
const val SAISIE_STYLE = "bold7_saisie_style"

/**
 * Backlight Brightness, Backlight Timeout and Automatically Dim Backlight are the guide's fields of
 * Display > Screen Display (p. 226, B); their values and the group's title are our own (D, no capture):
 * brightness in %, linear on Android's 0 to 255.
 */
val BRIGHTNESSES = (10..100 step 10).toList()

/**
 * Screen Display's LED Coverage Indicator (Backlight group, under Automatically Dim Backlight, clear
 * by default: simulator 7.1.0.318, C; guide p. 229, B): the LED flashes green while in
 * coverage (led/Led.kt), "1" or absent in Settings.Global.
 */
const val LED_COVERAGE = "bold7_led_coverage"

/** Backlight Timeout, in ms (D). */
val TIMEOUTS = listOf(10_000, 15_000, 30_000, 60_000, 120_000)

/**
 * Password's Lock After (sim-60, sim-299, C: "30 Min."): Android's delay between the screen going
 * off and the lock (Settings.Secure, ms), which KeyguardViewMediator reads at each screen off. The
 * 9900 counted from the last key press, Android counts from the screen off: the difference is the
 * Backlight Timeout (D).
 */
const val LOCK_AFTER = "lock_screen_lock_after_timeout"

/** Lock After's values, in ms, in the order of its open list: 1 Hour at the top down to 1 Min. (simulator 7.1.0.318, C). */
val LOCK_AFTERS = listOf(60, 30, 20, 15, 10, 5, 2, 1).map { it * 60_000 }

/** "30 Min.", "1 Hour" (sim-60, C, for "30 Min."). */
fun lockAfterLabel(ms: Int): String = when {
    ms % 3_600_000 == 0 -> (ms / 3_600_000).let { if (it == 1) "1 Hour" else "$it Hours" }
    else -> timeoutLabel(ms)
}

/** Password's button when a password is set, else "Set Password" (sim-60, sim-299, C). */
const val CHANGE_PASSWORD = "Change Password"

/** Password after a key: Lock After grey while Enable is clear, as the 9900 greys it (sim-60, sim-460, C). */
fun followEnable(f: Form): Form = f.withEnabled(2, (f.fields[0] as Check).checked)

/** Whether Password was read with a password set: its button says Change Password. */
fun passwordWasSet(f: Form): Boolean = (f.fields[1] as Button).label == CHANGE_PASSWORD

/**
 * What Save asks of SystemUI's boxes on Password, by Enable against what Android holds: clear with a
 * password, "Enter Password:" then the password goes (sim-459, C); ticked without one, "New
 * Password:" then "Verify New Password:" (sim-461, sim-462, C); else nothing.
 */
fun passwordSaveTask(f: Form): PasswordSave? {
    val enable = (f.fields[0] as Check).checked
    val set = passwordWasSet(f)
    return when {
        enable && !set -> PasswordSave.SET
        !enable && set -> PasswordSave.REMOVE
        else -> null
    }
}

enum class PasswordSave { SET, REMOVE }

/**
 * What Home Screen Preferences sets beyond Launch by Typing: the home's Downloads Folder and
 * views (HomeModel, in this process) and Android's wallpaper. Blocking: io only.
 */
interface HomePreferences {
    /** The home's folders and choices; null while the home is not loaded yet. */
    fun read(): HomeChoices?

    /** False if the home could not take them (not loaded). */
    fun write(choices: HomeChoices): Boolean

    /** The home's and the lock screen's wallpaper (§22, A, C: the 9900 shows the same on both); false if refused. */
    fun setWallpaper(change: WallpaperChange): Boolean
}

/** Manage Panels' boxes, All first then the views' loop (D: no capture of the screen). */
val PANEL_ORDER = listOf(Panel.ALL, Panel.FAVOURITES, Panel.MEDIA, Panel.DOWNLOADS, Panel.FREQUENT)

/** A view's name, as its tab shows it (sim-02, sim-11 to sim-14, C). */
fun panelName(p: Panel): String = when (p) {
    Panel.ALL -> "All"
    Panel.FAVOURITES -> "Favourites"
    Panel.MEDIA -> "Media"
    Panel.DOWNLOADS -> "Downloads"
    Panel.FREQUENT -> "Frequent"
}

/** Message on Lock Screen's two fields (simulator 7.1.0.318, C). */
const val OWNER_NAME = "Display Owner Name:"
const val OWNER_INFO = "Display Information:"

/** A ringing profile's Volume: 7 for Normal on sim-31 (C), 1 to 10 as the profiles count it (D). */
val VOLUMES = (1..10).map { it.toString() }

/** The profile's name as Options titles its screens ("Phone - Normal", sim-31, C). */
fun profileName(p: Profile): String = when (p) {
    Profile.NORMAL -> "Normal"
    Profile.VIBRATE -> "Vibrate Only"
    Profile.SILENT -> "Silent"
    Profile.LOUD -> "Loud"
    Profile.PHONE_ONLY -> "Phone Calls Only"
    Profile.ALERTS_OFF -> "All Alerts Off"
    Profile.MEDIUM -> "Medium"
}

/** "10 Sec.", "1 Min." (D); a value off the list keeps its own. */
fun timeoutLabel(ms: Int): String = if (ms % 60_000 == 0) "${ms / 60_000} Min." else "${ms / 1000} Sec."

/** A setting as Trackpad Sensitivity shows it: absent or unreadable 70, else 20 to 100 by 10. */
fun sensitivity(value: String?): Int = ((value?.toIntOrNull() ?: 70).coerceIn(20, 100) + 5) / 10 * 10

/**
 * Reads and saves the forms of Options. [language]: the input language as Typing shows it
 * ("English(UK)", sim-91a). What a form was read with (the ringtones, the profile) is kept for its
 * save, on the same thread.
 */
internal class OptionsStore(
    private val settings: DeviceSettings,
    private val volumes: ProfileVolumes,
    private val typing: LaunchByTypingSetting,
    private val typingOptions: TypingOptions,
    private val facts: DeviceFactsSource,
    private val zoneAndLanguage: ZoneAndLanguage,
    private val now: () -> Instant = Instant::now,
    /** Password and Message on Lock Screen, which SystemUI reads and writes for Options. */
    private val lock: LockSettings? = null,
    /** Home Screen Preferences' wallpaper, Downloads Folder and Manage Panels; without it they are grey. */
    private val home: HomePreferences? = null,
    private val language: () -> String,
) {
    private val tones = HashMap<ToneKind, List<Tone>>()
    private var profile: Profile = Profile.NORMAL
    private var timeouts: List<Int> = TIMEOUTS
    private var lockAfters: List<Int> = LOCK_AFTERS
    // Date and Time's zones and Language's tags as read, for the save.
    private var zones: List<String> = emptyList()
    private var languages: List<String> = emptyList()

    // The tones of [kind] and the one in use, remembered for the save, which writes the one chosen.
    private fun toneChoice(label: String, kind: ToneKind): Choice {
        val list = settings.ringtones(kind).also { tones[kind] = it }
        val current = settings.ringtone(kind)
        return Choice(label, list.map { it.title }, list.indexOfFirst { it.uri == current }.coerceAtLeast(0), enabled = list.isNotEmpty())
    }

    private fun saveTone(kind: ToneKind, chosen: Int): Boolean? = tones[kind]?.getOrNull(chosen)?.let { settings.setRingtone(it.uri, kind) }

    private fun rows(screen: Screen, rows: List<Pair<String, String>>) =
        Form(title(screen), listOf(Group(title(screen), rows.map { (label, value) -> Note(label, value, enabled = true) })))

    /** Profile Management: the seven profiles, [current] marked, and the custom profile Android cannot hold, grey (D). */
    fun profilesForm(current: Profile) = Form(
        title(Screen.PROFILES),
        listOf(Group("Profiles", Profile.entries.map { Note(profileName(it), if (it == current) "In use" else "", enabled = true) } + Button("Add Custom Profile", enabled = false))),
    )

    fun form(screen: Screen): Form = when (screen) {
        Screen.PROFILES -> profilesForm(volumes.current)
        Screen.NOTIFIER -> Form(
            title(screen),
            listOf(Group("Notifier", listOf(
                toneChoice("Notifier Tone:", ToneKind.NOTIFICATION),
                Check("LED:", settings.system(NOTIFICATION_LIGHT_PULSE) == "1"),
            ))),
        )
        Screen.REMINDERS -> Form(title(screen), listOf(Group("Reminder", listOf(toneChoice("Reminder Tone:", ToneKind.ALARM)))))
        // Time Zone: OS 7's list (Zones.kt), set by SET_TIME_ZONE (ZoneAndLanguage).
        Screen.DATE_TIME -> {
            val f = facts.facts()
            val is24 = settings.system(TIME_12_24)?.let { it == "24" } ?: f.locale24Hour
            val at = now()
            zones = timeZones(at, f.timeZoneId)
            Form(
                title(screen),
                listOf(Group("Date and Time", listOf(
                    Choice("Time Zone:", zones.map { zoneName(it, at) }, zones.indexOf(f.timeZoneId).coerceAtLeast(0)),
                    Choice("Time Format:", TIME_FORMATS, if (is24) 1 else 0),
                    Choice("Update Time:", UPDATE_TIME, if (settings.global(AUTO_TIME) == "1") 0 else 1),
                    Choice("Auto Update Time Zone:", AUTO_ZONE, if (settings.global(AUTO_TIME_ZONE) == "1") 0 else 1),
                ))),
            )
        }
        // Display Language (guide p. 224: "For each field, click the language", B), set by
        // CHANGE_CONFIGURATION (ZoneAndLanguage); the input language is Typing's, every list (D).
        Screen.LANGUAGE -> {
            val current = zoneAndLanguage.language()
            languages = displayLanguages(zoneAndLanguage.languages(), current)
            Form(title(screen), listOf(Group("Language", listOf(Choice("Display Language:", languages.map(::languageName), languages.indexOf(current))))))
        }
        Screen.ABOUT -> rows(screen, aboutRows(facts.facts()))
        Screen.STORAGE -> rows(screen, storageRows(facts.facts()))
        Screen.BATTERY -> rows(screen, batteryRows(facts.facts()))
        // sim-60, sim-299 (C): the group General. The button opens SystemUI's boxes at once
        // (PasswordScreen), Enable acts at Save (passwordSaveTask), as the 9900 does. Number of Password
        // Attempts (Android never wipes after failed attempts but through a device policy), Prompt on
        // Application Install, Allow Outgoing Calls While Locked (Android only lets emergency calls
        // through) and Lock Handheld Upon Holstering (no holster sensor on the Q25) have nothing
        // in Android to act on: not drawn.
        Screen.PASSWORD -> {
            val set = lock?.passwordSet() ?: false
            // Absent: Android's 5 s (KeyguardViewMediator's KEYGUARD_LOCK_AFTER_DELAY_DEFAULT), shown as it is.
            val after = settings.secure(LOCK_AFTER)?.toIntOrNull()?.takeIf { it >= 0 } ?: 5_000
            lockAfters = if (after in LOCK_AFTERS) LOCK_AFTERS else (LOCK_AFTERS + after).sortedDescending()
            Form(
                title(screen),
                listOf(Group("General", listOf(
                    Check("Enable:", set),
                    Button(if (set) CHANGE_PASSWORD else "Set Password", enabled = true),
                    Choice("Lock After:", lockAfters.map(::lockAfterLabel), lockAfters.indexOf(after), enabled = set),
                ))),
            )
        }
        // Simulator 7.1.0.318 (C): the group "Text on Home Screen", Display Owner Name and Display
        // Information, edited here in OS 7's text box (D: the 9900 types in the field itself);
        // grey when SystemUI does not answer.
        Screen.LOCK_MESSAGE -> {
            val message = lock?.lockMessage()
            Form(
                title(screen),
                listOf(Group("Text on Home Screen", listOf(
                    Note(OWNER_NAME, message?.first.orEmpty(), enabled = message != null),
                    Note(OWNER_INFO, message?.second.orEmpty(), enabled = message != null),
                ))),
            )
        }
        Screen.ENCRYPTION -> rows(screen, listOf("Device Encryption" to encryptionLabel(facts.facts())))
        Screen.TOUCHSCREEN -> {
            val ms = settings.secure(LONG_PRESS_TIMEOUT)?.toIntOrNull() ?: 400
            val nearest = HOVER_MS.indices.minBy { kotlin.math.abs(HOVER_MS[it] - ms) }
            Form(title(screen), listOf(Group("Touchscreen", listOf(Choice("Hover Period:", HOVER_LABELS, nearest)))))
        }
        Screen.KEYBOARD -> Form(
            title(screen),
            listOf(Group("Keyboard", listOf(
                Check("Key Tone:", settings.global(SAISIE_KEY_TONE) == "1"),
                Choice("Currency Key:", CURRENCIES, CURRENCIES.indexOf(settings.global(SAISIE_CURRENCY)).coerceAtLeast(0)),
            ))),
        )
        Screen.ACCESSIBILITY -> {
            val grey = settings.secure(DALTONIZER_ENABLED) == "1" && settings.secure(DALTONIZER) == "0"
            val contrast = if (settings.secure(INVERSION) == "1") 2 else if (grey) 1 else 0
            Form(title(screen), listOf(Group("Screen Display", listOf(Choice("Color Contrast:", CONTRASTS, contrast)))))
        }
        Screen.TRACKPAD -> Form(
            title(screen),
            listOf(Group("Trackpad", listOf(
                Choice("Horizontal Sensitivity:", SENSITIVITIES, SENSITIVITIES.indexOf(sensitivity(settings.global(TRACKPAD_HORIZONTAL)).toString())),
                Choice("Vertical Sensitivity:", SENSITIVITIES, SENSITIVITIES.indexOf(sensitivity(settings.global(TRACKPAD_VERTICAL)).toString())),
                Check("Audible Roll:", settings.global(TRACKPAD_AUDIBLE) == "1"),
            ))),
        )
        // Home Screen Preferences (sim-284, sim-287, C): Launch by Typing, one full-width button; Wallpaper
        // Settings, the thumbnail then Change Wallpaper and Reset Wallpaper; Downloads Folder, Home or a
        // folder of All (guide 7.1 p. 23, B); Home Screen Panels, Manage Panels; then Reset to Default
        // Settings (its note in red, then its button).
        Screen.HOME -> {
            typing.load()
            val c = home?.read()
            val folders = c?.folders.orEmpty()
            Form(title(screen), listOf(
                Group("Launch by Typing", listOf(Choice("", LAUNCH_BY_TYPING, typing.mode.ordinal))),
                Group("Wallpaper Settings", listOf(Wallpaper(), Button(CHANGE_WALLPAPER, enabled = home != null), Button(RESET_WALLPAPER, enabled = home != null))),
                Group("Downloads Folder", listOf(Choice("", listOf(HOME_FOLDER) + folders, folders.indexOf(c?.downloads) + 1, enabled = c != null, folder = true))),
                Group("Home Screen Panels", listOf(Button(MANAGE_PANELS, enabled = c != null))),
                Group("Reset to Default Settings", listOf(Note("", RESET_HOME_NOTE, enabled = false, red = true), Button("Reset Settings", enabled = true))),
            ))
        }
        // Manage Panels (guide 7.1 p. 23, B: "Select or clear the checkbox for each panel"): All stays (D).
        Screen.PANELS -> {
            val c = home?.read()
            Form(title(screen), listOf(Group("Home Screen Panels", PANEL_ORDER.map { p ->
                Check(panelName(p), c == null || p !in c.hiddenPanels, enabled = c != null && p != Panel.ALL)
            })))
        }
        Screen.TYPING -> Form(
            title(screen),
            listOf(
                Group("Input Language", listOf(Note(language(), "The availability of typing style(s) is dependent on the active input language(s).", enabled = true))),
                Group("Physical Keyboard", listOf(
                    Choice("Style:", STYLES, if (settings.global(SAISIE_STYLE) == "predictive") 1 else 0),
                    // Greyed under Direct on sim-91a (C); open under both here (D): Typing learns
                    // words under Direct too, and their deletion stays reachable.
                    Button("Advanced Style Options", enabled = true),
                )),
            ),
        )
        // sim-92a (C): its Word Learning group, the Typing box and its note; Word Suggestions (Spelling),
        // Names and Email are not drawn (D: none of them is done). The deletion of the learned words is D.
        Screen.ADVANCED_STYLE -> {
            val data = typingOptions.read()
            Form(
                title(screen),
                listOf(Group("Word Learning", listOf(
                    Check("Typing:", data?.learning ?: false, enabled = data != null),
                    Note("", LEARNING_NOTE, enabled = false),
                    Button(DELETE_LEARNED, enabled = data != null),
                ))),
            )
        }
        Screen.SCREEN_DISPLAY -> {
            val timeout = settings.system(SCREEN_OFF_TIMEOUT)?.toIntOrNull()
            timeouts = if (timeout == null || timeout in TIMEOUTS || timeout <= 0) TIMEOUTS else (TIMEOUTS + timeout).sorted()
            val brightness = settings.system(SCREEN_BRIGHTNESS)?.toIntOrNull() ?: 128
            val percent = ((brightness * 100 + 127) / 255 + 5) / 10 * 10
            Form(
                title(screen),
                listOf(Group("Backlight", listOf(
                    Choice("Backlight Brightness:", BRIGHTNESSES.map { it.toString() }, BRIGHTNESSES.indexOf(percent.coerceIn(10, 100))),
                    Choice("Backlight Timeout:", timeouts.map(::timeoutLabel), timeouts.indexOf(timeout ?: 30_000).coerceAtLeast(0)),
                    Check("Automatically Dim Backlight:", settings.system(SCREEN_BRIGHTNESS_MODE) == "1"),
                    Check("LED Coverage Indicator:", settings.global(LED_COVERAGE) == "1"),
                ))),
            )
        }
        Screen.PHONE_TONES -> {
            profile = volumes.current
            val fields = buildList<Field> {
                add(toneChoice("Ringtone:", ToneKind.RINGTONE))
                // A volume of its own for the profiles that ring; the others ring at none (D).
                if (profile in RINGING) add(Choice("Volume:", VOLUMES, volumes.volumeTenths(profile) - 1))
                // The ring's vibration, Android's (D: one setting for every profile).
                add(Check("Vibrate When Ringing:", settings.system(VIBRATE_WHEN_RINGING) == "1"))
            }
            Form("Phone - ${profileName(profile)}", listOf(Group("Sound Alerts", fields)))
        }
        else -> error("$screen is a list")
    }

    /**
     * Reset to Default Settings of Home Screen Preferences: Launch by Typing back to Universal Search,
     * Downloads Folder to Home, every view shown, the 9900's wallpaper; false if a part was refused.
     */
    fun resetHome(): Boolean {
        typing.save(LaunchByTyping.UNIVERSAL_SEARCH)
        val choices = home?.read()?.let { home.write(it.copy(downloads = null, hiddenPanels = emptySet())) } ?: true
        return choices && home?.setWallpaper(WallpaperChange.Default) != false
    }

    /** Writes what [form] shows; false if Android refused a part of it (the rest is written). */
    fun save(screen: Screen, form: Form): Boolean {
        val f = form.fields
        fun chosen(i: Int) = (f[i] as Choice).chosen
        fun checked(i: Int) = (f[i] as Check).checked
        return when (screen) {
            Screen.TRACKPAD -> listOf(
                settings.putGlobal(TRACKPAD_HORIZONTAL, SENSITIVITIES[chosen(0)]),
                settings.putGlobal(TRACKPAD_VERTICAL, SENSITIVITIES[chosen(1)]),
                settings.putGlobal(TRACKPAD_AUDIBLE, if (checked(2)) "1" else "0"),
            ).all { it }
            Screen.LOCK_MESSAGE -> !f[0].enabled || lock?.setLockMessage((f[0] as Note).note, (f[1] as Note).note) == true
            Screen.PASSWORD -> !f[2].enabled || settings.putSecure(LOCK_AFTER, lockAfters[chosen(2)].toString())
            Screen.TOUCHSCREEN -> settings.putSecure(LONG_PRESS_TIMEOUT, HOVER_MS[chosen(0)].toString())
            Screen.KEYBOARD -> listOf(
                settings.putGlobal(SAISIE_KEY_TONE, if (checked(0)) "1" else "0"),
                settings.putGlobal(SAISIE_CURRENCY, CURRENCIES[chosen(1)]),
            ).all { it }
            // Greyscale is the monochrome filter (daltonizer 0); either contrast turns the other off.
            Screen.ACCESSIBILITY -> chosen(0).let { c ->
                listOf(
                    settings.putSecure(INVERSION, if (c == 2) "1" else "0"),
                    settings.putSecure(DALTONIZER_ENABLED, if (c == 1) "1" else "0"),
                ).all { it } && (c != 1 || settings.putSecure(DALTONIZER, "0"))
            }
            Screen.HOME -> {
                typing.save(LaunchByTyping.entries[chosen(0)])
                val wallpaper = (f[1] as Wallpaper).pending?.let { home?.setWallpaper(it) } ?: true
                val folder = f[4] as Choice
                val downloads = if (!folder.enabled) true else home?.read()?.let { c ->
                    home.write(c.copy(downloads = folder.values.getOrNull(folder.chosen).takeIf { folder.chosen > 0 }))
                } ?: false
                wallpaper && downloads
            }
            Screen.PANELS -> !f[1].enabled || home?.read()?.let { c ->
                home.write(c.copy(hiddenPanels = PANEL_ORDER.filterIndexed { i, _ -> !checked(i) }.toSet()))
            } ?: false
            Screen.TYPING -> settings.putGlobal(SAISIE_STYLE, if (chosen(1) == 1) "predictive" else "direct")
            Screen.ADVANCED_STYLE -> f[0].enabled && typingOptions.setLearning(checked(0))
            Screen.SCREEN_DISPLAY -> listOf(
                settings.putSystem(SCREEN_BRIGHTNESS, (BRIGHTNESSES[chosen(0)] * 255 / 100).toString()),
                settings.putSystem(SCREEN_OFF_TIMEOUT, timeouts[chosen(1)].toString()),
                settings.putSystem(SCREEN_BRIGHTNESS_MODE, if (checked(2)) "1" else "0"),
                settings.putGlobal(LED_COVERAGE, if (checked(3)) "1" else "0"),
            ).all { it }
            Screen.PHONE_TONES -> listOfNotNull(
                saveTone(ToneKind.RINGTONE, chosen(0)),
                if (profile in RINGING) volumes.setVolumeTenths(profile, chosen(1) + 1) else null,
                settings.putSystem(VIBRATE_WHEN_RINGING, if (checked(f.lastIndex)) "1" else "0"),
            ).all { it }
            Screen.NOTIFIER -> listOfNotNull(
                saveTone(ToneKind.NOTIFICATION, chosen(0)),
                settings.putSystem(NOTIFICATION_LIGHT_PULSE, if (checked(1)) "1" else "0"),
            ).all { it }
            Screen.REMINDERS -> saveTone(ToneKind.ALARM, chosen(0)) ?: true
            // The automatic zone first: a zone chosen by hand under it would be taken back by Android's detection.
            Screen.DATE_TIME -> listOfNotNull(
                settings.putSystem(TIME_12_24, if (chosen(1) == 1) "24" else "12"),
                settings.putGlobal(AUTO_TIME, if (chosen(2) == 0) "1" else "0"),
                settings.putGlobal(AUTO_TIME_ZONE, if (chosen(3) == 0) "1" else "0"),
                zones.getOrNull(chosen(0))?.takeIf { it != facts.facts().timeZoneId }?.let(zoneAndLanguage::setZone),
            ).all { it }
            Screen.LANGUAGE -> languages.getOrNull(chosen(0))?.takeIf { it != zoneAndLanguage.language() }?.let(zoneAndLanguage::setLanguage) ?: true
            else -> true
        }
    }
}
