package dev.bold7.launcher.options

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

/** Backlight Timeout, in ms (D). */
val TIMEOUTS = listOf(10_000, 15_000, 30_000, 60_000, 120_000)

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
    private val language: () -> String,
) {
    private val tones = HashMap<ToneKind, List<Tone>>()
    private var profile: Profile = Profile.NORMAL
    private var timeouts: List<Int> = TIMEOUTS
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
        // Home Screen Preferences (sim-284, sim-287, C): Launch by Typing, one full-width button, then Reset to Default
        // Settings (its note in red, then its button). Wallpaper Settings, Downloads Folder and Home Screen Panels have
        // nothing to act on in Bold7 (no panels, no downloads folder on the home, the wallpaper is Android's): not drawn.
        Screen.HOME -> {
            typing.load()
            Form(title(screen), listOf(
                Group("Launch by Typing", listOf(Choice("", LAUNCH_BY_TYPING, typing.mode.ordinal))),
                Group("Reset to Default Settings", listOf(Note("", RESET_HOME_NOTE, enabled = false, red = true), Button("Reset Settings", enabled = true))),
            ))
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

    /** Writes what [form] shows; false if Android refused a part of it (the rest is written). */
    /** Reset to Default Settings of Home Screen Preferences: Launch by Typing back to Universal Search. */
    fun resetHome() = typing.save(LaunchByTyping.UNIVERSAL_SEARCH)

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
            Screen.HOME -> { typing.save(LaunchByTyping.entries[chosen(0)]); true }
            Screen.TYPING -> settings.putGlobal(SAISIE_STYLE, if (chosen(1) == 1) "predictive" else "direct")
            Screen.ADVANCED_STYLE -> f[0].enabled && typingOptions.setLearning(checked(0))
            Screen.SCREEN_DISPLAY -> listOf(
                settings.putSystem(SCREEN_BRIGHTNESS, (BRIGHTNESSES[chosen(0)] * 255 / 100).toString()),
                settings.putSystem(SCREEN_OFF_TIMEOUT, timeouts[chosen(1)].toString()),
                settings.putSystem(SCREEN_BRIGHTNESS_MODE, if (checked(2)) "1" else "0"),
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
