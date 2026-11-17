package dev.bold7.pictures

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withHelp
import dev.bold7.ui.withSwitchApplication

// The menus of Pictures, pure (docs/BBOS7-VISUEL.md §37.4), in the simulator's order (C: sim-346 to
// sim-352 for the categories and the grid, sim-368 to sim-373 for a picture of the camera, sim-354
// to sim-356 for the viewer) with only what Bold7 does: Add to Home Screen, Switch Input Language/Method,
// Select, Cut, Copy, Move to Folder, Rename, Properties, View by Date, Add Folder, Import Pictures,
// View (Thumbnails, List), Search, Media Home and Help are not there yet.

/** What the menus of Pictures do. */
enum class Act { SWITCH, OPEN, VIEW_SLIDESHOW, DELETE, SET_WALLPAPER, ASSIGN_CONTACT, SEND, SEND_TEXT, ROTATE, ZOOM, HELP, CLOSE }

/** The categories' full menu: Open (focus) and View Slide Show, then Close (sim-346, C). */
fun categoriesMenu(): Menu<Act> =
    Menu(listOf(MenuEntry(Act.OPEN), MenuEntry(Act.VIEW_SLIDESHOW), MenuEntry(Act.CLOSE, separatorAbove = true)), highlighted = 0)
        .withHelp(Act.HELP, before = Act.CLOSE).withSwitchApplication(Act.SWITCH)

/** Their short menu (sim-348, C: View Slide Show first). */
fun categoriesShortMenu(line: Int): Menu<Act> = Menu(listOf(MenuEntry(Act.VIEW_SLIDESHOW)), line = line, highlighted = 0)

// A picture's own actions, the grid's order (sim-369, sim-370, C): View Slide Show before them when
// the category has more than one picture (sim-349; D for its place among them, which no capture shows).
private fun pictureActions(count: Int): List<MenuEntry<Act>> = buildList {
    if (count > 1) add(MenuEntry(Act.VIEW_SLIDESHOW))
    add(MenuEntry(Act.DELETE))
    add(MenuEntry(Act.SET_WALLPAPER))
    add(MenuEntry(Act.ASSIGN_CONTACT))
    add(MenuEntry(Act.SEND, submenu = true))
}

/** The grid's full menu on a picture: Open (focus), the picture's actions, then Close (sim-349, sim-369, sim-370, C). */
fun gridMenu(count: Int): Menu<Act> =
    Menu(listOf(MenuEntry(Act.OPEN)) + pictureActions(count) + MenuEntry(Act.CLOSE, separatorAbove = true), highlighted = 0)
        .withHelp(Act.HELP, before = Act.CLOSE).withSwitchApplication(Act.SWITCH)

/** The grid's short menu: Assign To Contact, Set As Wallpaper, Delete, then Send..., focused (sim-373, C). */
fun gridShortMenu(line: Int): Menu<Act> =
    Menu(listOf(MenuEntry(Act.ASSIGN_CONTACT), MenuEntry(Act.SET_WALLPAPER), MenuEntry(Act.DELETE), MenuEntry(Act.SEND)), line = line, highlighted = 3)

/** The viewer's full menu: the picture's actions, Rotate and Zoom (focus), then Close (sim-354, sim-355, C; ps 33263, C). */
fun viewerMenu(count: Int): Menu<Act> {
    val entries = pictureActions(count) + MenuEntry(Act.ROTATE, separatorAbove = true) + MenuEntry(Act.ZOOM) + MenuEntry(Act.CLOSE, separatorAbove = true)
    return Menu(entries, highlighted = entries.indexOfFirst { it.action == Act.ZOOM }).withHelp(Act.HELP, before = Act.CLOSE).withSwitchApplication(Act.SWITCH)
}

/** The viewer's short menu: Assign To Contact, Set As Wallpaper, Delete, Send..., Zoom (focus), View Slide Show (ps 33262, sim-356, C). */
fun viewerShortMenu(count: Int): Menu<Act> {
    val entries = listOf(MenuEntry(Act.ASSIGN_CONTACT), MenuEntry(Act.SET_WALLPAPER), MenuEntry(Act.DELETE), MenuEntry(Act.SEND), MenuEntry(Act.ZOOM)) +
        (if (count > 1) listOf(MenuEntry(Act.VIEW_SLIDESHOW)) else emptyList())
    return Menu(entries, line = 0, highlighted = 4)
}

/** Send's submenu: Text Message, Bold7's Messages (ps 33263, C, 7.0; the simulator has no text messages: Email and Tag, sim-372). */
fun sendMenu(): Menu<Act> = Menu(listOf(MenuEntry(Act.SEND_TEXT)), highlighted = 0)
