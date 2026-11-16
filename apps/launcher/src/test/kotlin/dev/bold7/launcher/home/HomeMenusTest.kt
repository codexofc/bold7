package dev.bold7.launcher.home

import dev.bold7.icons.AppTable
import dev.bold7.launcher.home.HomeAction.ADD_FOLDER
import dev.bold7.launcher.home.HomeAction.DELETE
import dev.bold7.launcher.home.HomeAction.DELETE_FOLDER
import dev.bold7.launcher.home.HomeAction.EDIT_FOLDER
import dev.bold7.launcher.home.HomeAction.MOVE_TO_FOLDER
import dev.bold7.launcher.home.HomeAction.OPEN_FOLDER
import dev.bold7.launcher.home.HomeAction.HIDE
import dev.bold7.launcher.home.HomeAction.LAUNCH
import dev.bold7.launcher.home.HomeAction.MARK_FAVOURITE
import dev.bold7.launcher.home.HomeAction.MAXIMISE_PANEL
import dev.bold7.launcher.home.HomeAction.MOVE
import dev.bold7.launcher.home.HomeAction.RESTORE_PANEL
import dev.bold7.launcher.home.HomeAction.OPTIONS
import dev.bold7.launcher.home.HomeAction.SHOW_ALL
import dev.bold7.launcher.home.HomeAction.SWITCH_APPLICATION
import dev.bold7.launcher.home.HomeAction.UNHIDE
import dev.bold7.launcher.home.HomeAction.UNMARK_FAVOURITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeMenusTest {
    private val agenda = App("agenda", "agenda.Main", "Agenda")
    private val zoom = App("zoom", "zoom.Main", "Zoom", removable = true, media = true)

    private fun loaded(saved: SavedHome = SavedHome(), hiding: Hiding = Hiding()) = reduce(HomeState(), HomeEvent.AppsLoaded(listOf(agenda, zoom), saved, hiding))

    private fun HomeMenu.actions() = entries.map { it.action }

    @Test fun theShortMenuOfAnIconIsSim17sLaunchFocused() {
        val menu = shortMenu(loaded(), 0)!!
        assertEquals(listOf(MARK_FAVOURITE, MOVE, HIDE, LAUNCH), menu.actions())
        assertEquals(LAUNCH, menu.entries[menu.highlighted!!].action)
    }

    @Test fun anAppICanUninstallAddsDelete() {
        assertEquals(listOf(MARK_FAVOURITE, MOVE, HIDE, LAUNCH, DELETE), shortMenu(loaded(), 1)!!.actions())
    }

    @Test fun aFavouriteIsUnmarkedAndMovesInFavouritesOnly() {
        val s = loaded(SavedHome(favourites = listOf(agenda.component)))
        assertEquals(UNMARK_FAVOURITE, shortMenu(s, 0)!!.actions().first())
        assertEquals(listOf(UNMARK_FAVOURITE, MOVE, HIDE, LAUNCH), shortMenu(reduce(s, HomeEvent.ShowPanel(Panel.FAVOURITES)), 0)!!.actions())
        // Media, Downloads and Frequent are worked out, not arranged: no Move (D).
        assertEquals(listOf(MARK_FAVOURITE, HIDE, LAUNCH, DELETE), shortMenu(reduce(s, HomeEvent.ShowPanel(Panel.MEDIA)), 0)!!.actions())
    }

    @Test fun aHiddenIconShownByShowAllIsUnhidden() {
        val s = reduce(loaded(hiding = Hiding(AppTable(emptyMap()), mapOf(agenda.component to true))), HomeEvent.ShowAll)
        // Hidden since the load, never placed: shown at the end.
        assertEquals(listOf<Tile>(zoom, agenda), s.all)
        assertEquals(UNHIDE, shortMenu(s, 1)!!.actions()[2])
    }

    private val withFolders = SavedHome(listOf(agenda.key(), Folder("Fun", listOf(zoom.key())), Folder("Work", emptyList())))

    @Test fun withAFolderAroundAnAppCanMoveToFolderAsOnSim17() {
        assertEquals(listOf(MARK_FAVOURITE, MOVE, MOVE_TO_FOLDER, HIDE, LAUNCH), shortMenu(loaded(withFolders), 0)!!.actions())
        // Into each folder; from inside one, out to the home too, not into itself.
        assertEquals(listOf(FolderTarget(1, "Fun"), FolderTarget(2, "Work")), folderTargets(loaded(withFolders), 0))
        val open = reduce(loaded(withFolders), HomeEvent.OpenFolder(1))
        assertEquals(listOf(FolderTarget(null, ""), FolderTarget(2, "Work")), folderTargets(open, 0))
    }

    @Test fun aFolderOpensRenamesMovesAndDeletesOpenFocused() {
        val menu = shortMenu(loaded(withFolders), 1)!!
        assertEquals(listOf(OPEN_FOLDER, EDIT_FOLDER, MOVE, DELETE_FOLDER), menu.actions())
        assertEquals(OPEN_FOLDER, menu.entries[menu.highlighted!!].action)
    }

    @Test fun noShortMenuOffTheIcons() {
        assertNull(shortMenu(loaded(), 5))
    }

    @Test fun theFullMenuHasTheIconsActionsThenTheHomesWhatWorksOnly() {
        val s = loaded()
        // sim-04 without Manage Panels (not offered): Switch Application first, Maximise Panel focused.
        val home = fullMenu(s, null)
        assertEquals(listOf(SWITCH_APPLICATION, MAXIMISE_PANEL, ADD_FOLDER, SHOW_ALL, OPTIONS), home.actions())
        assertEquals(1, home.highlighted)
        val onIcon = fullMenu(s, 0)
        assertEquals(listOf(SWITCH_APPLICATION, MARK_FAVOURITE, MOVE, HIDE, LAUNCH, MAXIMISE_PANEL, ADD_FOLDER, SHOW_ALL, OPTIONS), onIcon.actions())
        assertEquals(LAUNCH, onIcon.entries[onIcon.highlighted!!].action)
        // A line above the home's actions only, after the icon's (sim-04's groups).
        assertEquals(listOf(1, 5), onIcon.entries.withIndex().filter { it.value.separatorAbove }.map { it.index })
        assertEquals(listOf(1), home.entries.withIndex().filter { it.value.separatorAbove }.map { it.index })
        // Raised, the tray is restored; all shown already, no Show All.
        assertEquals(listOf(SWITCH_APPLICATION, RESTORE_PANEL, ADD_FOLDER, OPTIONS), fullMenu(reduce(reduce(s, HomeEvent.Expand(true)), HomeEvent.ShowAll), null).actions())
    }
}
