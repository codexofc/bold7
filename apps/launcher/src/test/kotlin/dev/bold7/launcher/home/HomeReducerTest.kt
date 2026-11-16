package dev.bold7.launcher.home

import dev.bold7.icons.AppTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HomeReducerTest {
    private val agenda = App("agenda", "agenda.Main", "Agenda")
    private val maps = App("maps", "maps.Main", "Maps")
    private val music = App("music", "music.Main", "Music", media = true)
    private val zoom = App("zoom", "zoom.Main", "Zoom", removable = true)
    private val apps = listOf(agenda, maps, music, zoom)

    private fun loaded(saved: SavedHome = SavedHome(), hiding: Hiding = Hiding()) = reduce(HomeState(), HomeEvent.AppsLoaded(apps, saved, hiding))

    @Test fun theFirstLoadPutsEveryAppInAllByName() {
        val s = loaded()
        assertEquals(listOf<Tile>(agenda, maps, music, zoom), s.all)
        assertEquals(Panel.ALL, s.panel)
        assertEquals(false, s.expanded)
    }

    @Test fun theSavedOrderAndFoldersComeBackNewAppsAtTheEnd() {
        val saved = SavedHome(listOf(zoom.key(), Folder("Work", listOf(maps.key())), Folder("Empty", emptyList())))
        val s = loaded(saved)
        assertEquals(listOf(zoom, Folder("Work", listOf(maps)), Folder("Empty", emptyList()), agenda, music), s.all)
    }

    @Test fun theViewsLoopFrequentAllFavouritesMediaDownloads() {
        assertEquals(listOf(Panel.FREQUENT, Panel.ALL, Panel.FAVOURITES, Panel.MEDIA, Panel.DOWNLOADS), Panel.entries)
        assertEquals(Panel.FREQUENT, Panel.DOWNLOADS.next(1))
        assertEquals(Panel.DOWNLOADS, Panel.FREQUENT.next(-1))
    }

    @Test fun eachViewShowsItsAppsMediaAndDownloadsInAllsOrderFoldersOpenedUp() {
        val saved = SavedHome(listOf(Folder("Fun", listOf(zoom.key(), music.key())), agenda.key(), maps.key()), favourites = listOf(maps.component, agenda.component))
        val s = loaded(saved)
        assertEquals(listOf<Tile>(maps, agenda), s.items(Panel.FAVOURITES))
        assertEquals(listOf<Tile>(music), s.items(Panel.MEDIA))
        assertEquals(listOf<Tile>(zoom), s.items(Panel.DOWNLOADS))
        assertEquals(listOf<Tile>(maps, agenda), reduce(s, HomeEvent.ShowPanel(Panel.FAVOURITES)).shown)
    }

    @Test fun frequentIsTheAppsLastOpenedNewestFirstAndKeptToItsMax() {
        var s = loaded()
        s = reduce(s, HomeEvent.Launched(maps.component))
        s = reduce(s, HomeEvent.Launched(zoom.component))
        s = reduce(s, HomeEvent.Launched(maps.component))
        assertEquals(listOf<Tile>(maps, zoom), s.items(Panel.FREQUENT))
        repeat(FREQUENT_MAX + 3) { s = reduce(s, HomeEvent.Launched("p$it/c")) }
        assertEquals(FREQUENT_MAX, s.frequent.size)
    }

    @Test fun aHiddenAppLeavesEveryViewAndComesBackToItsPlace() {
        val table = AppTable(emptyMap())
        val s = loaded(SavedHome(listOf(zoom.key(), agenda.key(), maps.key(), music.key()), favourites = listOf(zoom.component)))
        val hidden = reduce(s, HomeEvent.HidingChanged(Hiding(table, mapOf(zoom.component to true))))
        assertEquals(listOf<Tile>(agenda, maps, music), hidden.all)
        assertEquals(emptyList(), hidden.items(Panel.FAVOURITES))
        assertEquals(listOf(zoom), hidden.hiddenApps)
        val back = reduce(hidden, HomeEvent.HidingChanged(Hiding(table)))
        assertEquals(listOf<Tile>(zoom, agenda, maps, music), back.all)
    }

    @Test fun anUninstallTakesTheAppOutOfAllItsFolderTheFavouritesAndFrequent() {
        val s = loaded(SavedHome(listOf(Folder("Fun", listOf(zoom.key())), agenda.key()), favourites = listOf(zoom.component), frequent = listOf(zoom.component)))
        val after = reduce(s, HomeEvent.PackageChanged("zoom", emptyList(), removed = true))
        assertEquals(listOf(Folder("Fun", emptyList()), agenda, maps, music), after.all)
        assertEquals(emptyList(), after.favourites)
        assertEquals(emptyList(), after.frequent)
    }

    @Test fun aDisabledAppKeepsItsPlaceHidden() {
        val s = loaded(SavedHome(listOf(zoom.key(), agenda.key())))
        val off = reduce(s, HomeEvent.PackageChanged("zoom", emptyList()))
        assertEquals(listOf<Tile>(agenda, maps, music), off.all)
        val on = reduce(off, HomeEvent.PackageChanged("zoom", listOf(zoom)))
        assertEquals(listOf<Tile>(zoom, agenda, maps, music), on.all)
    }

    @Test fun anUpdateOfAPackageWithoutIconsChangesNothing() {
        val s = loaded()
        assertSame(s, reduce(s, HomeEvent.PackageChanged("some.service", emptyList())))
    }

    @Test fun endAndEscapeLowerTheTrayAndKeepTheView() {
        val up = reduce(reduce(loaded(), HomeEvent.ShowPanel(Panel.MEDIA)), HomeEvent.Expand(true))
        assertEquals(true, up.expanded)
        assertEquals(false, reduce(up, HomeEvent.HomeKey).expanded)
        assertEquals(false, reduce(up, HomeEvent.Back).expanded)
        assertEquals(Panel.MEDIA, reduce(up, HomeEvent.HomeKey).panel)
    }

    @Test fun theSavedHomeReadsBackAndLegacyPagesReadAsOneList() {
        val home = SavedHome(listOf(zoom.key(), Folder("A b", listOf(maps.key(), agenda.key())), Folder("Empty", emptyList())), listOf(maps.component), listOf(zoom.component, agenda.component))
        assertEquals(home, decodeHome(encodeHome(home)))
        // A legacy file: pages of 15, read one after the other.
        val legacy = "page\napp\tzoom/zoom.Main\nfolder\tWork\tmaps/maps.Main\tagenda/agenda.Main\npage\napp\tmusic/music.Main\n"
        assertEquals(SavedHome(listOf(zoom.key(), Folder("Work", listOf(maps.key(), agenda.key())), music.key())), decodeHome(legacy))
        assertEquals(SavedHome(), decodeHome("garbage\napp\tnot-a-component\n"))
    }

    @Test fun aRenamedLauncherActivityTakesItsPredecessorsPlace() {
        val renamed = App("maps", "maps.NewMain", "Maps")
        assertEquals(listOf<Tile>(agenda.key(), renamed.key()), placed(listOf(agenda.key(), maps.key()), listOf(agenda, renamed)))
    }

    // A second icon of an app on the grid comes beside it, as Text Messages
    // beside Messages on the 9900 (sim-03, C), not at the end of All.
    @Test fun aNewActivityOfAnAppOnTheGridComesBesideIt() {
        val texts = App("maps", "maps.Texts", "Texts")
        assertEquals(listOf<Tile>(agenda.key(), maps.key(), texts.key(), zoom.key()), placed(listOf(agenda.key(), maps.key(), zoom.key()), listOf(agenda, maps, zoom, texts)))
        // In a folder, the app's place is the folder's: the new icon goes to the end (D).
        val inFolder = listOf<Tile>(Folder("F", listOf(maps.key())), zoom.key())
        assertEquals(inFolder + texts.key(), placed(inFolder, listOf(maps, zoom, texts)))
    }

    @Test fun moveCarriesTheIconToWhereTheTrackpadTakesItAndDropsItThere() {
        val s = loaded() // agenda, maps, music, zoom
        val moving = reduce(s, HomeEvent.StartMove(0))
        assertEquals(0, moving.moving)
        val there = reduce(reduce(moving, HomeEvent.MoveTo(2)), HomeEvent.MoveTo(3))
        assertEquals(listOf<Tile>(maps, music, zoom, agenda), there.all)
        assertEquals(3, there.moving)
        val dropped = reduce(there, HomeEvent.EndMove)
        assertEquals(null, dropped.moving)
        assertEquals(listOf<Tile>(maps, music, zoom, agenda), dropped.all)
        // Back again to the second place.
        assertEquals(listOf<Tile>(maps, agenda, music, zoom), reduce(reduce(reduce(dropped, HomeEvent.StartMove(3)), HomeEvent.MoveTo(1)), HomeEvent.EndMove).all)
    }

    @Test fun addFolderPutsAnEmptyFolderLastNumberedIfTheNameIsTaken() {
        var s = loaded()
        s = reduce(s, HomeEvent.AddFolder("Folder"))
        s = reduce(s, HomeEvent.AddFolder("Folder"))
        assertEquals(listOf(agenda, maps, music, zoom, Folder("Folder", emptyList()), Folder("Folder 2", emptyList())), s.all)
    }

    private val folders = SavedHome(listOf(agenda.key(), Folder("Fun", listOf(zoom.key(), music.key())), Folder("Work", emptyList()), maps.key()))

    @Test fun anOpenFolderShowsItsAppsEscapeClosesIt() {
        val s = loaded(folders)
        val open = reduce(s, HomeEvent.OpenFolder(1))
        assertEquals(listOf<Tile>(zoom, music), open.shown)
        assertEquals("Fun", open.folder?.name)
        assertEquals(true, open.expanded) // it takes the raised tray
        assertEquals(null, reduce(open, HomeEvent.Back).folder)
        assertEquals(null, reduce(open, HomeEvent.HomeKey).folder)
        assertEquals(null, reduce(open, HomeEvent.ShowPanel(Panel.MEDIA)).folder)
        // An app is not a folder.
        assertEquals(null, reduce(s, HomeEvent.OpenFolder(0)).folder)
    }

    @Test fun renamingKeepsANameOfOneLineAndRefusesAnEmptyOne() {
        val open = reduce(loaded(folders), HomeEvent.OpenFolder(1))
        assertEquals("My games", reduce(open, HomeEvent.RenameFolder(" My\n\tgames ")).folder?.name)
        assertEquals("Fun", reduce(open, HomeEvent.RenameFolder("  ")).folder?.name)
    }

    @Test fun moveToFolderPutsTheAppLastInItAndOutOfAFolderRightAfterIt() {
        val s = loaded(folders)
        // Agenda into Work (its place in the layout is 2).
        val inWork = reduce(s, HomeEvent.MoveToFolder(0, 2))
        assertEquals(listOf(Folder("Fun", listOf(zoom, music)), Folder("Work", listOf(agenda)), maps), inWork.all)
        // Zoom out of the open Fun, right after it.
        val out = reduce(reduce(s, HomeEvent.OpenFolder(1)), HomeEvent.MoveToFolder(0, null))
        assertEquals(listOf<Tile>(music), out.shown)
        assertEquals(listOf(agenda, Folder("Fun", listOf(music)), zoom, Folder("Work", emptyList()), maps), out.all)
    }

    @Test fun deletingAFolderPutsItsAppsInItsPlace() {
        val s = loaded(folders)
        assertEquals(listOf<Tile>(agenda, zoom, music, Folder("Work", emptyList()), maps), reduce(s, HomeEvent.DeleteFolder(1)).all)
    }

    @Test fun moveInAnOpenFolderReordersItsApps() {
        val open = reduce(loaded(folders), HomeEvent.OpenFolder(1))
        val moved = reduce(reduce(reduce(open, HomeEvent.StartMove(1)), HomeEvent.MoveTo(0)), HomeEvent.EndMove)
        assertEquals(listOf<Tile>(music, zoom), moved.shown)
    }

    @Test fun anUninstallDuringAMoveEndsItWithoutBringingTheAppBack() {
        val s = loaded()
        val moving = reduce(reduce(s, HomeEvent.StartMove(0)), HomeEvent.MoveTo(3))
        // The moved app itself goes: the move is put back first, then the app leaves.
        val gone = reduce(moving, HomeEvent.PackageChanged("agenda", emptyList(), removed = true))
        assertEquals(null, gone.moving)
        assertEquals(listOf<Tile>(maps, music, zoom), gone.all)
        assertEquals(gone.all, reduce(gone, HomeEvent.Back).all)
        // Another app goes: same, and a step after it finds nothing out of bounds.
        val other = reduce(moving, HomeEvent.PackageChanged("zoom", emptyList(), removed = true))
        assertEquals(listOf<Tile>(agenda, maps, music), reduce(other, HomeEvent.MoveTo(2)).all)
    }

    @Test fun aMoveFromATileGoneDoesNothing() {
        val s = loaded().copy(moving = 9)
        assertEquals(s, reduce(s, HomeEvent.MoveTo(0)))
    }

    @Test fun escapeDuringAMovePutsTheIconBack() {
        val s = loaded()
        val moved = reduce(reduce(s, HomeEvent.StartMove(0)), HomeEvent.MoveTo(3))
        val back = reduce(moved, HomeEvent.Back)
        assertEquals(s.all, back.all)
        assertEquals(null, back.moving)
    }

    @Test fun aMoveInFavouritesReordersThemNotAll() {
        val s = reduce(loaded(SavedHome(favourites = listOf(agenda.component, maps.component, zoom.component))), HomeEvent.ShowPanel(Panel.FAVOURITES))
        val moved = reduce(reduce(reduce(s, HomeEvent.StartMove(2)), HomeEvent.MoveTo(0)), HomeEvent.EndMove)
        assertEquals(listOf<Tile>(zoom, agenda, maps), moved.shown)
        assertEquals(s.all, moved.all)
    }

    @Test fun moveOnlyWhereTheOrderIsMine() {
        val s = reduce(loaded(), HomeEvent.ShowPanel(Panel.MEDIA))
        assertEquals(null, reduce(s, HomeEvent.StartMove(0)).moving)
    }

    @Test fun markingAFavouritePutsItLastUnmarkingTakesItOut() {
        var s = loaded()
        s = reduce(s, HomeEvent.Favourite(zoom.component, true))
        s = reduce(s, HomeEvent.Favourite(agenda.component, true))
        s = reduce(s, HomeEvent.Favourite(zoom.component, true))
        assertEquals(listOf<Tile>(zoom, agenda), s.items(Panel.FAVOURITES))
        assertEquals(listOf<Tile>(agenda), reduce(s, HomeEvent.Favourite(zoom.component, false)).items(Panel.FAVOURITES))
    }

    @Test fun showAllShowsTheHiddenIconsInTheirPlaceUntilEscapeOrEnd() {
        val table = AppTable(emptyMap())
        val s = reduce(loaded(SavedHome(listOf(zoom.key(), agenda.key(), maps.key(), music.key()))), HomeEvent.HidingChanged(Hiding(table, mapOf(agenda.component to true))))
        val all = reduce(s, HomeEvent.ShowAll)
        assertEquals(listOf<Tile>(zoom, agenda, maps, music), all.all)
        assertEquals(false, reduce(all, HomeEvent.Back).showAll)
        assertEquals(false, reduce(all, HomeEvent.HomeKey).showAll)
    }
}
