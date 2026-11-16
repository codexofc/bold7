package dev.bold7.pictures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenusTest {
    private fun dev.bold7.ui.Menu<Act>.acts() = entries.map { it.action }
    private fun dev.bold7.ui.Menu<Act>.focused() = entries[highlighted!!].action

    @Test fun theCategoriesOpenWithOpenFocused() {
        val m = categoriesMenu()
        assertEquals(listOf(Act.SWITCH, Act.OPEN, Act.VIEW_SLIDESHOW, Act.CLOSE), m.acts())
        assertEquals(Act.OPEN, m.focused())
        assertEquals(Act.VIEW_SLIDESHOW, categoriesShortMenu(2).focused())
    }

    @Test fun theGridsMenuHasThePicturesActionsInTheSimulatorsOrder() {
        val m = gridMenu(14)
        assertEquals(listOf(Act.SWITCH, Act.OPEN, Act.VIEW_SLIDESHOW, Act.DELETE, Act.SET_WALLPAPER, Act.ASSIGN_CONTACT, Act.SEND, Act.CLOSE), m.acts())
        assertEquals(Act.OPEN, m.focused())
        assertTrue(m.entries.first { it.action == Act.SEND }.submenu)
        assertTrue(m.entries.first { it.action == Act.CLOSE }.separatorAbove)
    }

    @Test fun oneAloneHasNoSlideShow() {
        assertEquals(listOf(Act.SWITCH, Act.OPEN, Act.DELETE, Act.SET_WALLPAPER, Act.ASSIGN_CONTACT, Act.SEND, Act.CLOSE), gridMenu(1).acts())
        assertEquals(listOf(Act.ASSIGN_CONTACT, Act.SET_WALLPAPER, Act.DELETE, Act.SEND, Act.ZOOM), viewerShortMenu(1).acts())
    }

    @Test fun theGridsShortMenuFocusesSend() {
        val m = gridShortMenu(5)
        assertEquals(Act.SEND, m.focused())
        assertEquals(5, m.line)
    }

    @Test fun theViewerFocusesZoomAfterRotate() {
        val m = viewerMenu(3)
        assertEquals(listOf(Act.SWITCH, Act.VIEW_SLIDESHOW, Act.DELETE, Act.SET_WALLPAPER, Act.ASSIGN_CONTACT, Act.SEND, Act.ROTATE, Act.ZOOM, Act.CLOSE), m.acts())
        assertEquals(Act.ZOOM, m.focused())
        assertTrue(m.entries.first { it.action == Act.ROTATE }.separatorAbove)
        assertEquals(Act.ZOOM, viewerShortMenu(3).focused())
    }

    @Test fun sendOffersATextMessage() {
        assertEquals(listOf(Act.SEND_TEXT), sendMenu().acts())
    }
}
