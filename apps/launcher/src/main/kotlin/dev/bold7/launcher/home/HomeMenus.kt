package dev.bold7.launcher.home

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication

// The home's menus, pure: what they offer for what has the focus (docs/BBOS7-VISUEL.md §4).
// core/ui's MenuView draws them, in BBOS 7's look.

enum class HomeAction {
    LAUNCH, MARK_FAVOURITE, UNMARK_FAVOURITE, MOVE, MOVE_TO_FOLDER, HIDE, UNHIDE, DELETE,
    OPEN_FOLDER, EDIT_FOLDER, DELETE_FOLDER,
    MAXIMISE_PANEL, RESTORE_PANEL, ADD_FOLDER, SHOW_ALL, OPTIONS, SWITCH_APPLICATION,
}

/** Where Move to Folder can take an app: the folder at [place] of the layout, or out to the home (null). */
data class FolderTarget(val place: Int?, val name: String)

/** Move to Folder's choices for the app at [index] shown: out of the open folder first, then every other folder of All. */
fun folderTargets(state: HomeState, index: Int): List<FolderTarget> {
    if (state.tileAt(index) !is App || state.panel != Panel.ALL) return emptyList()
    val out = if (state.openFolder != null) listOf(FolderTarget(null, "")) else emptyList()
    return out + state.all.mapIndexedNotNull { i, tile ->
        val place = state.placeOf(i)
        if (tile is Folder && place != null && place != state.openFolder) FolderTarget(place, tile.name) else null
    }
}

typealias HomeMenu = Menu<HomeAction>

/**
 * What an icon offers, in sim-17's order (C): Mark as Favourite (Unmark once it is one), Move
 * where the order is the user's, Hide Icon (Unhide on one Show All shows), Launch; then Delete for an
 * app that can be uninstalled (D, sim-17's app could not be). The short menu's last cell, Full Menu, is core/ui's own.
 */
private fun iconActions(state: HomeState, index: Int): List<HomeAction>? = when (val tile = state.tileAt(index)) {
    is App -> buildList {
        add(if (tile.component in state.favourites) HomeAction.UNMARK_FAVOURITE else HomeAction.MARK_FAVOURITE)
        if (state.panel.arranged()) add(HomeAction.MOVE)
        if (folderTargets(state, index).isNotEmpty()) add(HomeAction.MOVE_TO_FOLDER)
        add(if (state.hides(tile)) HomeAction.UNHIDE else HomeAction.HIDE)
        add(HomeAction.LAUNCH)
        if (tile.removable) add(HomeAction.DELETE)
    }
    // A folder (sim-15 shows one focused; its actions after the theme's sm_folder_open,
    // sm_rename_folder and sm_delete_folder glyphs, D).
    is Folder -> listOf(HomeAction.OPEN_FOLDER, HomeAction.EDIT_FOLDER, HomeAction.MOVE, HomeAction.DELETE_FOLDER)
    null -> null
}

/** What a click does, and the menus' focus: Launch an app, open a folder. */
private fun HomeState.defaultAt(index: Int) = if (tileAt(index) is Folder) HomeAction.OPEN_FOLDER else HomeAction.LAUNCH

/** The short menu (the trackpad held on an icon, sim-17): its actions, Launch focused; MenuView adds Full Menu. */
fun shortMenu(state: HomeState, index: Int): HomeMenu? {
    val actions = iconActions(state, index) ?: return null
    return menu(actions, line = index, focused = state.defaultAt(index))
}

/**
 * The full menu (the Menu key, sim-04): Switch Application, the focused icon's
 * actions, then the home's. Of sim-04's, Manage Panels is not offered; Maximise Panel becomes Restore Panel raised (D), Show All goes once all shows.
 */
fun fullMenu(state: HomeState, index: Int?): HomeMenu {
    val icon = index?.let { iconActions(state, it) }.orEmpty()
    val home = buildList {
        add(if (state.expanded) HomeAction.RESTORE_PANEL else HomeAction.MAXIMISE_PANEL)
        add(HomeAction.ADD_FOLDER)
        if (!state.showAll) add(HomeAction.SHOW_ALL)
        add(HomeAction.OPTIONS)
    }
    val focused = if (icon.isEmpty() || index == null) home.first() else state.defaultAt(index)
    // A line between the icon's actions and the home's, as between sim-04's groups.
    return menu(icon + home, line = null, focused = focused, separatorAt = icon.size.takeIf { it > 0 }).withSwitchApplication(HomeAction.SWITCH_APPLICATION)
}

private fun menu(actions: List<HomeAction>, line: Int?, focused: HomeAction, separatorAt: Int? = null) =
    Menu(actions.mapIndexed { i, a -> MenuEntry(a, separatorAbove = i == separatorAt) }, line = line, highlighted = actions.indexOf(focused))
