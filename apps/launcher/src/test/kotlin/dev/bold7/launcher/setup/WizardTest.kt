package dev.bold7.launcher.setup

import dev.bold7.ui.Dir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardTest {
    private fun on(page: Page, s: State = State()) = s.copy(page = page, focus = defaultFocus(s.copy(page = page)))

    @Test
    fun `the pages come in their order, five of them, each telling where it stands`() {
        assertEquals(listOf(Page.WELCOME, Page.CHANGES, Page.TEXTS, Page.LOOK, Page.DONE), Page.entries)
        assertEquals("1 of 5", progress(Page.WELCOME))
        assertEquals("5 of 5", progress(Page.DONE))
    }

    @Test
    fun `it opens once, never over a phone that saw the text screen before, and never again`() {
        assertEquals(Opening.SHOW, opening(done = false, textScreenAsked = false))
        assertEquals(Opening.MARK_DONE, opening(done = false, textScreenAsked = true))
        assertEquals(Opening.NOTHING, opening(done = true, textScreenAsked = false))
        assertEquals(Opening.NOTHING, opening(done = true, textScreenAsked = true))
    }

    @Test
    fun `missing pictures are said only when neither the mounted folder nor the APK has them`() {
        assertTrue(assetsMissing(folder = false, apkImages = false))
        assertFalse(assetsMissing(folder = true, apkImages = false))
        assertFalse(assetsMissing(folder = false, apkImages = true))
    }

    @Test
    fun `Welcome has Next alone, the others Back and Next, the last Start`() {
        assertEquals(listOf(Control.Next), band(Page.WELCOME))
        assertEquals(listOf(Control.Back, Control.Next), band(Page.CHANGES))
        assertEquals("Start", nextLabel(on(Page.DONE)))
        assertEquals("Next", nextLabel(on(Page.WELCOME)))
    }

    @Test
    fun `Next and Back walk the pages, focus on Next`() {
        var s = on(Page.WELCOME)
        assertEquals(Control.Next, focused(s))
        s = reduce(s, Event.Click(Control.Next)).state
        assertEquals(Page.CHANGES, s.page)
        assertEquals(Control.Next, focused(s))
        s = reduce(s, Event.Click(Control.Back)).state
        assertEquals(Page.WELCOME, s.page)
    }

    @Test
    fun `Start ends the wizard`() {
        assertEquals(Effect.Finish, reduce(on(Page.DONE), Event.Click(Control.Next)).effect)
    }

    @Test
    fun `the trackpad moves through the rows, and between Back and Next`() {
        var s = on(Page.CHANGES)
        s = reduce(s, Event.Move(Dir.UP)).state
        assertEquals(Control.KeepPhone, focused(s))
        s = reduce(s, Event.Move(Dir.UP)).state
        assertEquals(Control.KeepHome, focused(s))
        s = reduce(s, Event.Move(Dir.UP)).state
        assertEquals(Control.KeepHome, focused(s))
        s = reduce(reduce(s, Event.Move(Dir.DOWN)).state, Event.Move(Dir.DOWN)).state
        assertEquals(Control.Next, focused(s))
        s = reduce(s, Event.Move(Dir.LEFT)).state
        assertEquals(Control.Back, focused(s))
        s = reduce(s, Event.Move(Dir.LEFT)).state
        assertEquals(Control.Back, focused(s))
    }

    @Test
    fun `the wallpapers are a grid of four, up and down by a row`() {
        var s = on(Page.LOOK, State(wallpapers = 6))
        assertEquals(listOf(listOf(Control.Profile), listOf(Control.Wallpaper(0), Control.Wallpaper(1), Control.Wallpaper(2), Control.Wallpaper(3)), listOf(Control.Wallpaper(4), Control.Wallpaper(5)), band(Page.LOOK)), rows(s))
        s = reduce(s, Event.Move(Dir.UP)).state
        // From Next, the last column: the row above has two, the focus goes to its last, then keeps its column.
        assertEquals(Control.Wallpaper(5), focused(s))
        s = reduce(s, Event.Move(Dir.UP)).state
        assertEquals(Control.Wallpaper(1), focused(s))
        s = reduce(s, Event.Move(Dir.RIGHT)).state
        assertEquals(Control.Wallpaper(2), focused(s))
    }

    @Test
    fun `keeping the home and the Phone is checked, and a click unchecks it`() {
        val s = on(Page.CHANGES)
        assertTrue(s.keepHome && s.keepPhone)
        assertFalse(reduce(s, Event.Click(Control.KeepHome)).state.keepHome)
        assertFalse(reduce(s, Event.Click(Control.KeepPhone)).state.keepPhone)
    }

    @Test
    fun `leaving Bold7 as home or Phone asks first and only then goes on`() {
        val kept = reduce(on(Page.CHANGES), Event.Click(Control.Next))
        assertNull(kept.effect)
        assertEquals(Page.TEXTS, kept.state.page)
        val left = reduce(on(Page.CHANGES, State(keepPhone = false)), Event.Click(Control.Next))
        assertEquals(Effect.ConfirmDefaultApps, left.effect)
        assertEquals(Page.CHANGES, left.state.page)
        assertEquals(Page.TEXTS, reduce(left.state, Event.Advance).state.page)
    }

    @Test
    fun `the texts page opens Google's page, and says Skip until the consent is read on`() {
        val s = on(Page.TEXTS)
        assertEquals(listOf(listOf(Control.OpenTexts), band(Page.TEXTS)), rows(s))
        assertEquals(Effect.OpenTexts, reduce(s, Event.Click(Control.OpenTexts)).effect)
        assertEquals("Skip", nextLabel(s))
        val on = reduce(s, Event.ConsentRead(Consent.ON, back = true)).state
        assertEquals("Next", nextLabel(on))
        val absent = s.copy(messagesInstalled = false)
        assertEquals(listOf(band(Page.TEXTS)), rows(absent))
        assertEquals("Next", nextLabel(absent))
    }

    @Test
    fun `back from Google's page the state read is said, before it nothing is`() {
        val s = on(Page.TEXTS)
        assertNull(consentLine(s))
        assertEquals(CONSENT_ON, consentLine(reduce(s, Event.ConsentRead(Consent.ON, back = false)).state))
        assertNull(consentLine(reduce(s, Event.ConsentRead(Consent.OFF, back = false)).state))
        assertEquals(CONSENT_OFF, consentLine(reduce(s, Event.ConsentRead(Consent.OFF, back = true)).state))
        assertEquals(CONSENT_UNKNOWN, consentLine(reduce(s, Event.ConsentRead(Consent.UNKNOWN, back = true)).state))
    }

    @Test
    fun `a wallpaper is asked before it is set, then shown chosen`() {
        val s = on(Page.LOOK, State(wallpapers = 3))
        assertEquals(Effect.ConfirmWallpaper(2), reduce(s, Event.Click(Control.Wallpaper(2))).effect)
        assertNull(s.chosenWallpaper)
        assertEquals(2, reduce(s, Event.WallpaperSet(2)).state.chosenWallpaper)
        assertEquals(Effect.PickProfile, reduce(s, Event.Click(Control.Profile)).effect)
    }

    @Test
    fun `a touch puts the focus where it touched`() {
        val s = reduce(on(Page.CHANGES), Event.Click(Control.KeepPhone)).state
        assertEquals(Control.KeepPhone, focused(s))
    }

    @Test
    fun `the texts are short and say nothing of Android's insides`() {
        val jargon = Regex("\\b(root|modules?|overlays?|systemui|su)\\b")
        for (t in ALL_TEXTS) {
            assertTrue(t.length <= 200, "${t.length}: $t")
            // KernelSU's own tab, the one place the user goes to undo it all.
            val plain = t.replace("KernelSU > Modules", "").lowercase()
            assertNull(jargon.find(plain), t)
        }
    }
}
