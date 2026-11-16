package dev.bold7.launcher.options

import dev.bold7.launcher.keys.LaunchByTyping
import dev.bold7.launcher.keys.LaunchByTypingSetting
import dev.bold7.launcher.profiles.Profile
import dev.bold7.launcher.profiles.ProfileVolumes
import dev.bold7.platform.DeviceFacts
import dev.bold7.platform.DeviceFactsSource
import dev.bold7.platform.DeviceSettings
import dev.bold7.platform.Tone
import dev.bold7.platform.ToneKind
import dev.bold7.platform.ZoneAndLanguage
import java.time.Instant
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionsStoreTest {
    private class FakeSettings : DeviceSettings {
        val global = HashMap<String, String>()
        val system = HashMap<String, String>()
        val secure = HashMap<String, String>()
        val chosen = hashMapOf<ToneKind, String?>(ToneKind.RINGTONE to "content://media/internal/audio/media/2", ToneKind.NOTIFICATION to "content://media/internal/audio/media/1")
        var refuse = false
        val tones = listOf(Tone("Antelope", "content://media/internal/audio/media/1"), Tone("Spirit", "content://media/internal/audio/media/2"))

        override fun global(name: String) = global[name]
        override fun putGlobal(name: String, value: String) = !refuse && global.put(name, value).let { true }
        override fun system(name: String) = system[name]
        override fun putSystem(name: String, value: String) = !refuse && system.put(name, value).let { true }
        override fun secure(name: String) = secure[name]
        override fun putSecure(name: String, value: String) = !refuse && secure.put(name, value).let { true }
        override fun ringtones(kind: ToneKind) = tones
        override fun ringtone(kind: ToneKind) = chosen[kind]
        override fun setRingtone(uri: String, kind: ToneKind) = true.also { chosen[kind] = uri }
        var tone: String?
            get() = chosen[ToneKind.RINGTONE]
            set(v) { chosen[ToneKind.RINGTONE] = v }
    }

    private class FakeVolumes(override var current: Profile = Profile.NORMAL) : ProfileVolumes {
        override fun select(profile: Profile) { current = profile }
        val tenths = HashMap<Profile, Int>()
        override fun volumeTenths(profile: Profile) = tenths[profile] ?: 7
        override fun setVolumeTenths(profile: Profile, tenths: Int) = true.also { this.tenths[profile] = tenths }
    }

    private val settings = FakeSettings()
    private val volumes = FakeVolumes()
    private val typing = LaunchByTypingSetting(File.createTempFile("launch-by-typing", null).also { it.delete() })
    private class FakeTyping : TypingOptions {
        var data: TypingData? = TypingData(learning = true, substitutions = emptyList())
        var forgotten = 0
        override fun read() = data
        override fun setLearning(on: Boolean) = data?.let { data = it.copy(learning = on); true } ?: false
        override fun forget() = (data != null).also { if (it) forgotten++ }
        override fun setSubstitutions(rows: List<Substitution>) = data?.let { data = it.copy(substitutions = rows); true } ?: false
    }

    private val typingOptions = FakeTyping()
    private val device = object : DeviceFactsSource {
        var current: DeviceFacts = dev.bold7.launcher.options.facts()
        override fun facts() = current
    }
    private class FakeZoneAndLanguage : ZoneAndLanguage {
        var zone: String? = null
        var language = "en-US"
        var refuse = false
        override fun setZone(id: String) = !refuse && true.also { zone = id }
        override fun languages() = listOf("fr-FR", "en-US", "de-DE")
        override fun language() = language
        override fun setLanguage(tag: String) = !refuse && true.also { language = tag }
    }

    private val zl = FakeZoneAndLanguage()
    private val store = OptionsStore(settings, volumes, typing, typingOptions, device, zl, { Instant.parse("2026-01-15T12:00:00Z") }) { "English(US)" }

    private fun Form.choice(i: Int) = fields[i] as Choice
    private fun Form.pick(field: Int, value: Int) = copy(focus = field).choose(value)

    @Test
    fun `Trackpad Sensitivity reads bold7-trackpad's settings, 70 when absent, by tens`() {
        settings.global[TRACKPAD_VERTICAL] = "45"
        settings.global[TRACKPAD_AUDIBLE] = "1"
        val f = store.form(Screen.TRACKPAD)
        assertEquals("70", f.choice(0).values[f.choice(0).chosen])
        assertEquals("50", f.choice(1).values[f.choice(1).chosen])
        assertTrue((f.fields[2] as Check).checked)
        settings.global[TRACKPAD_HORIZONTAL] = "garbage"
        assertEquals("70", store.form(Screen.TRACKPAD).let { it.choice(0).values[it.choice(0).chosen] })
    }

    @Test
    fun `Trackpad Sensitivity saves the three settings`() {
        val f = store.form(Screen.TRACKPAD).pick(0, 0)
        assertTrue(store.save(Screen.TRACKPAD, f))
        assertEquals(mapOf(TRACKPAD_HORIZONTAL to "100", TRACKPAD_VERTICAL to "70", TRACKPAD_AUDIBLE to "0"), settings.global)
    }

    @Test
    fun `a refused write is told`() {
        settings.refuse = true
        assertFalse(store.save(Screen.TRACKPAD, store.form(Screen.TRACKPAD)))
    }

    @Test
    fun `Typing reads and writes the style, Direct when absent, Advanced Style Options open`() {
        val f = store.form(Screen.TYPING)
        assertEquals("English(US)", f.fields[0].label)
        assertEquals(0, f.choice(1).chosen)
        assertTrue(f.choice(1).unavailable.isEmpty())
        assertTrue(f.fields[2].enabled)
        assertTrue(store.save(Screen.TYPING, f.pick(1, 1)))
        assertEquals(mapOf(SAISIE_STYLE to "predictive"), settings.global)
        assertEquals(1, store.form(Screen.TYPING).choice(1).chosen)
        assertTrue(store.save(Screen.TYPING, store.form(Screen.TYPING).pick(1, 0)))
        assertEquals("direct", settings.global[SAISIE_STYLE])
    }

    @Test
    fun `Screen Display reads and writes Android's backlight`() {
        settings.system[SCREEN_BRIGHTNESS] = "128"
        settings.system[SCREEN_OFF_TIMEOUT] = "60000"
        settings.system[SCREEN_BRIGHTNESS_MODE] = "1"
        val f = store.form(Screen.SCREEN_DISPLAY)
        assertEquals("50", f.choice(0).values[f.choice(0).chosen])
        assertEquals("1 Min.", f.choice(1).values[f.choice(1).chosen])
        assertTrue((f.fields[2] as Check).checked)
        val g = f.pick(0, BRIGHTNESSES.indexOf(100)).pick(1, 0)
        assertTrue(store.save(Screen.SCREEN_DISPLAY, g))
        assertEquals("255", settings.system[SCREEN_BRIGHTNESS])
        assertEquals("10000", settings.system[SCREEN_OFF_TIMEOUT])
        assertEquals("1", settings.system[SCREEN_BRIGHTNESS_MODE])
    }

    @Test
    fun `a timeout off OS 7's list is kept as a value of its own`() {
        settings.system[SCREEN_OFF_TIMEOUT] = "1800000"
        val f = store.form(Screen.SCREEN_DISPLAY)
        assertEquals("30 Min.", f.choice(1).values.last())
        assertEquals(f.choice(1).values.size - 1, f.choice(1).chosen)
        assertTrue(store.save(Screen.SCREEN_DISPLAY, f))
        assertEquals("1800000", settings.system[SCREEN_OFF_TIMEOUT])
    }

    @Test
    fun `Phone Ringtone shows the profile in use, its ringtone and volume, and saves both`() {
        volumes.tenths[Profile.LOUD] = 10
        volumes.current = Profile.LOUD
        val f = store.form(Screen.PHONE_TONES)
        assertEquals("Phone - Loud", f.title)
        assertEquals("Spirit", f.choice(0).values[f.choice(0).chosen])
        assertEquals("10", f.choice(1).values[f.choice(1).chosen])
        assertTrue(store.save(Screen.PHONE_TONES, f.pick(0, 0).pick(1, 4)))
        assertEquals("content://media/internal/audio/media/1", settings.tone)
        assertEquals(5, volumes.tenths[Profile.LOUD])
    }

    @Test
    fun `a profile that does not ring has no volume`() {
        volumes.current = Profile.VIBRATE
        val f = store.form(Screen.PHONE_TONES)
        assertEquals("Phone - Vibrate Only", f.title)
        assertEquals(listOf("Ringtone:", "Vibrate When Ringing:"), f.fields.map { it.label })
        assertTrue(store.save(Screen.PHONE_TONES, f))
        assertTrue(volumes.tenths.isEmpty())
    }

    @Test
    fun `the search keeps the entries whose title or subtitle has the text`() {
        val l = Listing(Screen.MAIN).search("wi-fi")
        assertEquals(listOf("Networks and Connections"), l.shown.map { it.title })
        assertEquals(0, Listing(Screen.MAIN).step(down = false).focus)
        assertEquals(null, Listing(Screen.MAIN).search("zzz").target())
    }

    // Guide 7.1 p. 24: Menu > Options, the Launch by Typing field, Universal Search first.
    @Test
    fun `Launch by Typing is Universal Search at first and is kept once saved`() {
        val f = store.form(Screen.HOME)
        assertEquals("Home Screen Preferences", f.title) // sim-284, C
        assertEquals("Launch by Typing", f.groups[0].title)
        assertEquals(listOf("Universal Search", "Application Shortcuts"), f.choice(0).values)
        assertEquals(0, f.choice(0).chosen)
        assertTrue(store.save(Screen.HOME, f.pick(0, 1)))
        assertEquals(LaunchByTyping.APPLICATION_SHORTCUTS, typing.mode)
        assertEquals(1, store.form(Screen.HOME).choice(0).chosen) // read back from the file
    }

    // sim-284 (C): Reset to Default Settings, the last group of Home Screen Preferences.
    @Test
    fun `Reset Settings puts Launch by Typing back to Universal Search`() {
        val f = store.form(Screen.HOME)
        assertEquals("Reset to Default Settings", f.groups[1].title)
        assertTrue(f.groups[1].fields[1] is Button)
        store.save(Screen.HOME, f.pick(0, 1))
        store.resetHome()
        assertEquals(LaunchByTyping.UNIVERSAL_SEARCH, typing.mode)
        assertEquals(0, store.form(Screen.HOME).choice(0).chosen)
    }

    // sim-92a (C): Advanced Style Options, Word Learning's Typing box; deleting the learned words (D).
    @Test
    fun `Advanced Style Options reads and saves Word Learning through Typing`() {
        val f = store.form(Screen.ADVANCED_STYLE)
        assertEquals("Word Learning", f.groups.single().title)
        val box = f.fields[0] as Check
        assertEquals("Typing:", box.label)
        assertTrue(box.checked && box.enabled)
        assertEquals(LEARNING_NOTE, (f.fields[1] as Note).note)
        assertTrue(f.fields[2] is Button && f.fields[2].enabled)
        val off = (f.key(FormKey.SPACE) as FormResult.Show).form
        assertTrue(store.save(Screen.ADVANCED_STYLE, off))
        assertEquals(false, typingOptions.data?.learning)
        assertFalse((store.form(Screen.ADVANCED_STYLE).fields[0] as Check).checked)
    }

    @Test
    fun `without Typing, Word Learning is grey and nothing is saved`() {
        typingOptions.data = null
        val f = store.form(Screen.ADVANCED_STYLE)
        assertFalse(f.fields[0].enabled)
        assertFalse(f.fields[2].enabled)
        assertFalse(store.save(Screen.ADVANCED_STYLE, f))
    }

    private fun Form.shown(i: Int) = choice(i).values[choice(i).chosen]

    @Test
    fun `Phone Ringtone also reads and writes Vibrate When Ringing`() {
        settings.system["vibrate_when_ringing"] = "1"
        val f = store.form(Screen.PHONE_TONES)
        assertTrue((f.fields.last() as Check).checked)
        val off = f.copy(focus = f.fields.lastIndex).let { (it.key(FormKey.CLICK) as FormResult.Show).form }
        assertTrue(store.save(Screen.PHONE_TONES, off))
        assertEquals("0", settings.system["vibrate_when_ringing"])
    }

    @Test
    fun `Messages and Email read the notifier tone and the LED, and save both`() {
        settings.system["notification_light_pulse"] = "1"
        val f = store.form(Screen.NOTIFIER)
        assertEquals("Antelope", f.shown(0))
        assertTrue((f.fields[1] as Check).checked)
        assertTrue(store.save(Screen.NOTIFIER, f.pick(0, 1)))
        assertEquals("content://media/internal/audio/media/2", settings.chosen[ToneKind.NOTIFICATION])
        assertEquals("1", settings.system["notification_light_pulse"])
        settings.system.remove("notification_light_pulse")
        assertFalse((store.form(Screen.NOTIFIER).fields[1] as Check).checked)
    }

    @Test
    fun `Reminders read and save the alarm tone`() {
        val f = store.form(Screen.REMINDERS)
        assertEquals("Antelope", f.shown(0))
        assertTrue(store.save(Screen.REMINDERS, f.pick(0, 1)))
        assertEquals("content://media/internal/audio/media/2", settings.chosen[ToneKind.ALARM])
    }

    @Test
    fun `Profile Management lists the seven profiles, the one in use marked, and Add Custom Profile grey`() {
        volumes.current = Profile.LOUD
        val f = store.form(Screen.PROFILES)
        val notes = f.fields.filterIsInstance<Note>()
        assertEquals(Profile.entries.map(::profileName), notes.map { it.label })
        assertEquals(listOf("Loud"), notes.filter { it.note == "In use" }.map { it.label })
        assertFalse(f.fields.last().enabled)
        assertEquals("Add Custom Profile", f.fields.last().label)
    }

    @Test
    fun `Date and Time reads the zone, the format, Update Time and Auto Update Time Zone`() {
        settings.global["auto_time"] = "1"
        settings.global["auto_time_zone"] = "0"
        settings.system["time_12_24"] = "24"
        val f = store.form(Screen.DATE_TIME)
        assertEquals("Toronto (GMT-5)", f.shown(0))
        assertEquals("24 Hour", f.shown(1))
        assertEquals(listOf("Update Time:", "Auto Update Time Zone:"), f.fields.drop(2).map { it.label })
        assertEquals("Automatic", f.shown(2))
        assertEquals(listOf("Automatic", "Manual"), f.choice(2).values)
        assertEquals("Off", f.shown(3))
        assertEquals(listOf("On", "Off"), f.choice(3).values)
        settings.global["auto_time"] = "0"
        settings.global["auto_time_zone"] = "1"
        val g = store.form(Screen.DATE_TIME)
        assertEquals("Manual", g.shown(2))
        assertEquals("On", g.shown(3))
    }

    @Test
    fun `without a format set Date and Time shows the locale's`() {
        assertEquals("12 Hour", store.form(Screen.DATE_TIME).shown(1))
        device.current = dev.bold7.launcher.options.facts().copy(locale24Hour = true)
        assertEquals("24 Hour", store.form(Screen.DATE_TIME).shown(1))
    }

    @Test
    fun `Date and Time saves the format, Update Time and Auto Update Time Zone, the zone only when changed`() {
        val f = store.form(Screen.DATE_TIME).pick(1, 1).pick(2, 0)
        assertTrue(store.save(Screen.DATE_TIME, f))
        assertEquals("24", settings.system["time_12_24"])
        assertEquals("1", settings.global["auto_time"])
        assertEquals("0", settings.global["auto_time_zone"])
        assertEquals(null, zl.zone)
        val form = store.form(Screen.DATE_TIME)
        val paris = form.choice(0).values.indexOf("Paris (GMT+1)")
        assertTrue(store.save(Screen.DATE_TIME, form.pick(0, paris)))
        assertEquals("Europe/Paris", zl.zone)
        zl.refuse = true
        assertFalse(store.save(Screen.DATE_TIME, store.form(Screen.DATE_TIME).pick(0, paris)))
    }

    @Test
    fun `Language lists the phone's languages in themselves and sets the one chosen`() {
        val f = store.form(Screen.LANGUAGE)
        assertEquals(listOf("Deutsch (Deutschland)", "English (United States)", "Français (France)"), f.choice(0).values)
        assertEquals("English (United States)", f.shown(0))
        assertTrue(store.save(Screen.LANGUAGE, f))
        assertEquals("en-US", zl.language)
        assertTrue(store.save(Screen.LANGUAGE, f.pick(0, 2)))
        assertEquals("fr-FR", zl.language)
    }

    @Test
    fun `Device screens are read-only rows of what Android reports`() {
        assertEquals(aboutRows(facts()).map { it.first }, store.form(Screen.ABOUT).fields.map { it.label })
        assertEquals("Q25", (store.form(Screen.ABOUT).fields[0] as Note).note)
        assertEquals("75%", (store.form(Screen.STORAGE).fields[2] as Note).note)
        assertEquals("80%", (store.form(Screen.BATTERY).fields[0] as Note).note)
        assertEquals("On", (store.form(Screen.ENCRYPTION).fields[0] as Note).note)
        assertFalse(store.form(Screen.ABOUT).changed)
    }

    @Test
    fun `Touchscreen Sensitivity reads the touch-hold delay of Android, Normal at 400 ms, the nearest when off the list`() {
        settings.secure[LONG_PRESS_TIMEOUT] = "400"
        assertEquals("Normal", store.form(Screen.TOUCHSCREEN).choice(0).let { it.values[it.chosen] })
        settings.secure[LONG_PRESS_TIMEOUT] = "1100"
        assertEquals("Slow", store.form(Screen.TOUCHSCREEN).choice(0).let { it.values[it.chosen] })
        settings.secure.remove(LONG_PRESS_TIMEOUT)
        assertEquals("Normal", store.form(Screen.TOUCHSCREEN).choice(0).let { it.values[it.chosen] })
    }

    @Test
    fun `Touchscreen Sensitivity writes the delay`() {
        assertTrue(store.save(Screen.TOUCHSCREEN, store.form(Screen.TOUCHSCREEN).pick(0, 0)))
        assertEquals("250", settings.secure[LONG_PRESS_TIMEOUT])
    }

    @Test
    fun `Keyboard reads and writes the key tone and the currency key for Typing`() {
        val f = store.form(Screen.KEYBOARD)
        assertFalse((f.fields[0] as Check).checked)
        assertEquals("$", f.choice(1).values[f.choice(1).chosen])
        val saved = f.copy(focus = 0).let { (it.key(FormKey.CLICK) as FormResult.Show).form }.pick(1, 1)
        assertTrue(store.save(Screen.KEYBOARD, saved))
        assertEquals(mapOf(SAISIE_KEY_TONE to "1", SAISIE_CURRENCY to "€"), settings.global)
        assertEquals("€", store.form(Screen.KEYBOARD).let { it.choice(1).values[it.choice(1).chosen] })
    }

    @Test
    fun `Accessibility Color Contrast maps to Android's inversion and monochrome filter`() {
        assertEquals(0, store.form(Screen.ACCESSIBILITY).choice(0).chosen)
        settings.secure[DALTONIZER_ENABLED] = "1"
        settings.secure[DALTONIZER] = "0"
        assertEquals(1, store.form(Screen.ACCESSIBILITY).choice(0).chosen)
        settings.secure[DALTONIZER_ENABLED] = "0"
        settings.secure[INVERSION] = "1"
        assertEquals(2, store.form(Screen.ACCESSIBILITY).choice(0).chosen)
        // A colour-blindness filter of Android's own (daltonizer 12) is not greyscale.
        settings.secure[INVERSION] = "0"; settings.secure[DALTONIZER_ENABLED] = "1"; settings.secure[DALTONIZER] = "12"
        assertEquals(0, store.form(Screen.ACCESSIBILITY).choice(0).chosen)
    }

    @Test
    fun `Accessibility saves each contrast and turns the other off`() {
        assertTrue(store.save(Screen.ACCESSIBILITY, store.form(Screen.ACCESSIBILITY).pick(0, 1)))
        assertEquals(mapOf(INVERSION to "0", DALTONIZER_ENABLED to "1", DALTONIZER to "0"), settings.secure)
        assertTrue(store.save(Screen.ACCESSIBILITY, store.form(Screen.ACCESSIBILITY).pick(0, 2)))
        assertEquals("1", settings.secure[INVERSION]); assertEquals("0", settings.secure[DALTONIZER_ENABLED])
        assertTrue(store.save(Screen.ACCESSIBILITY, store.form(Screen.ACCESSIBILITY).pick(0, 0)))
        assertEquals("0", settings.secure[INVERSION]); assertEquals("0", settings.secure[DALTONIZER_ENABLED])
    }
}
