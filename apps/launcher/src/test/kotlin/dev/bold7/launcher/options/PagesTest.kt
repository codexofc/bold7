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
        for (s in listOf(Screen.PROFILE_SOUNDS, Screen.PROFILES, Screen.DATE_TIME, Screen.LANGUAGE, Screen.NETWORKS, Screen.DEVICE, Screen.ABOUT, Screen.STORAGE, Screen.BATTERY, Screen.SECURITY, Screen.ENCRYPTION, Screen.NOTIFIER, Screen.REMINDERS)) {
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

    @Test
    fun `Device reopens the text setup`() {
        assertEquals(Target.TextSetup, entries(Screen.DEVICE).first { it.title == "Text Messages Setup" }.target)
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
    fun `Touchscreen Sensitivity, Keyboard and Accessibility open Bold7's own forms, Message on Lock Screen Android's lock screen page`() {
        assertEquals(Target.Open(Screen.TOUCHSCREEN), entries(Screen.TYPING_LANGUAGE).first { it.title == "Touchscreen Sensitivity" }.target)
        assertEquals(Target.Open(Screen.KEYBOARD), entries(Screen.TYPING_LANGUAGE).first { it.title == "Keyboard" }.target)
        assertEquals(Target.Open(Screen.ACCESSIBILITY), entries(Screen.MAIN).first { it.title == "Accessibility" }.target)
        assertEquals(Target.Android("android.settings.LOCK_SCREEN_SETTINGS"), entries(Screen.DISPLAY).first { it.title == "Message on Lock Screen" }.target)
    }
}
