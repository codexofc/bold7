package dev.bold7.launcher.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PagesTest {
    private val lists = listOf(Screen.MAIN, Screen.SOUNDS, Screen.PROFILE_SOUNDS, Screen.DISPLAY, Screen.TYPING_LANGUAGE, Screen.NETWORKS, Screen.DEVICE, Screen.SECURITY)

    @Test
    fun `every entry that opens a screen opens one that exists, a list or a form`() {
        val opened = lists.flatMap { entries(it) }.mapNotNull { (it.target as? Target.Open)?.screen }
        assertTrue(opened.isNotEmpty())
        // A screen is a list (entries) or a form (OptionsStore): the ones added later are both reached from Options.
        for (s in listOf(Screen.PROFILE_SOUNDS, Screen.PROFILES, Screen.DATE_TIME, Screen.LANGUAGE, Screen.NETWORKS, Screen.DEVICE, Screen.ABOUT, Screen.STORAGE, Screen.BATTERY, Screen.SECURITY, Screen.PASSWORD, Screen.ENCRYPTION, Screen.LOCK_MESSAGE, Screen.NOTIFIER, Screen.REMINDERS)) {
            assertTrue(s in opened, "$s is reached from no list")
        }
    }

    @Test
    fun `Options' first seven entries open Bold7's own screens instead of Android's pages`() {
        val main = entries(Screen.MAIN)
        assertEquals(listOf("Sounds and Ringtones", "Networks and Connections", "Display", "Typing and Language", "Device"), main.take(5).map { it.title })
        assertTrue(main.take(5).all { it.target is Target.Open })
        assertTrue(main.first { it.title == "Security" }.target is Target.Open)
    }

    // sim-59 (C): Password and Encryption with their subtitles; Password is Bold7's, never Android's page.
    @Test
    fun `Security opens Bold7's Password`() {
        val security = entries(Screen.SECURITY)
        assertEquals(listOf("Control access to your device", "Protect your information and files"), security.map { it.subtitle })
        assertEquals(Target.Open(Screen.PASSWORD), security.first().target)
        assertTrue(security.none { it.target is Target.Android })
    }

    @Test
    fun `Device reopens the text setup`() {
        assertEquals(Target.TextSetup, entries(Screen.DEVICE).first { it.title == "Text Messages Setup" }.target)
    }

    @Test
    fun `Device ends on Advanced System Settings, which opens Android's Settings`() {
        val last = entries(Screen.DEVICE).last()
        assertEquals("Advanced System Settings", last.title)
        assertEquals("Enterprise activation, service book", last.subtitle)
        assertEquals(Target.Android("android.settings.SETTINGS"), last.target)
    }

    @Test
    fun `Networks and Connections leads back to Manage Connections first`() {
        assertEquals(Target.Connections, entries(Screen.NETWORKS).first().target)
    }

    @Test
    fun `the sound entries that need a profile of their own open Profile Management`() {
        assertEquals(Target.Open(Screen.PROFILES), entries(Screen.SOUNDS).first { it.title == "Profile Management" }.target)
        assertEquals(Target.Open(Screen.PROFILE_SOUNDS), entries(Screen.SOUNDS).first { it.title == "Sounds for Selected Profile" }.target)
    }

    @Test
    fun `Touchscreen Sensitivity, Keyboard and Accessibility and Message on Lock Screen open Bold7's own forms`() {
        assertEquals(Target.Open(Screen.TOUCHSCREEN), entries(Screen.TYPING_LANGUAGE).first { it.title == "Touchscreen Sensitivity" }.target)
        assertEquals(Target.Open(Screen.KEYBOARD), entries(Screen.TYPING_LANGUAGE).first { it.title == "Keyboard" }.target)
        assertEquals(Target.Open(Screen.ACCESSIBILITY), entries(Screen.MAIN).first { it.title == "Accessibility" }.target)
        assertEquals(Target.Open(Screen.LOCK_MESSAGE), entries(Screen.DISPLAY).first { it.title == "Message on Lock Screen" }.target)
    }

    // sim-446 (C): Display's rows in their order with their subtitles; Message Categorisation is not drawn (D).
    @Test
    fun `Display lists Home Screen Preferences after Date and Time`() {
        val display = entries(Screen.DISPLAY)
        assertEquals(listOf("Date and Time", "Home Screen Preferences", "Screen Display", "Message on Lock Screen"), display.map { it.title })
        assertEquals(listOf("Change time zones and formats", "Change wallpaper, download folder", "Change theme, font, backlighting", "Set text to show on locked screen"), display.map { it.subtitle })
        assertEquals(Target.Open(Screen.HOME), display[1].target)
        assertEquals("opt_home_screen", display[1].icon)
        assertEquals("Manage Panels", title(Screen.PANELS))
    }
}
