package dev.bold7.launcher.home

import java.text.Collator

// The home screen of BBOS 7 as a pure state machine: no android.*, tested on the JVM. HomeModel
// feeds it what LauncherApps reports and the saved home, the views feed it what the user does,
// and render its state (docs/BBOS7-VISUEL.md §2 to §4).

/** What a cell of the tray holds: an app, or a folder of apps. */
sealed interface Tile

/**
 * A launchable activity of the user; [removable] when it can be uninstalled (not a system app),
 * which also puts it in Downloads; [media] when Android files it under audio, video or images,
 * which puts it in Media.
 */
data class App(
    val packageName: String,
    val className: String,
    val label: String,
    val removable: Boolean = false,
    val media: Boolean = false,
) : Tile

/**
 * An [App] that is a shortcut an app published rather than an activity: the icon of each mail
 * account, published by Messages (gsm 020); its shortcut id after this prefix in
 * [App.className], so the layout keeps it by component like any icon.
 */
const val SHORTCUT = "#shortcut:"

val App.shortcutId: String? get() = if (className.startsWith(SHORTCUT)) className.substring(SHORTCUT.length) else null

/** A BBOS 7 folder: apps only, none at all allowed (sim-16, C). */
data class Folder(val name: String, val apps: List<App>) : Tile

/**
 * The tray's views, in the loop the trackpad and a swipe go round (sim-02, sim-11 to sim-14, C):
 * Frequent, All, Favourites, Media, Downloads, then Frequent again.
 */
enum class Panel {
    FREQUENT, ALL, FAVOURITES, MEDIA, DOWNLOADS;

    /** The view [step] places further round the loop. */
    fun next(step: Int): Panel = entries[(ordinal + step).mod(entries.size)]
}

/** Frequent shows the apps opened last, newest first, up to three rows of the 9900 (D). */
const val FREQUENT_MAX = 18

data class HomeState(
    /** Launchable, sorted by [label][App.label]: the order of apps the layout does not know yet. */
    val apps: List<App> = emptyList(),
    /** All as the user arranged it, as saved by HomeModel each time it changes (HomeLayout.kt). */
    val layout: List<Tile> = emptyList(),
    /** The favourites, by component, in the order they were marked. */
    val favourites: List<String> = emptyList(),
    /** The apps opened from the home, by component, newest first. */
    val frequent: List<String> = emptyList(),
    /** The apps and the saved home are in: from now on [all] is the user's. */
    val loaded: Boolean = false,
    /**
     * Bumped each time LauncherApps reports the apps: an update that renames nothing still
     * makes a new state, so its new icon is drawn.
     */
    val appsVersion: Int = 0,
    val panel: Panel = Panel.ALL,
    /** The tray raised over the home (Maximise Panel, sim-06), or down to its one row (sim-02). */
    val expanded: Boolean = false,
    /** Which of [apps] the tray leaves out (HiddenApps.kt); they stay in [apps], for the search. */
    val hiding: Hiding = Hiding(),
    /** Show All (the home's menu, B): the hidden icons in All too, until Escape or End (D). */
    val showAll: Boolean = false,
    /** The icon being moved (Move, sim-17): its index in the view shown, which it follows. */
    val moving: Int? = null,
    /** What Move changes, as it was before: Escape puts it back. */
    val beforeMove: SavedHome? = null,
    /** The folder open over All (sim-16), by its place in [layout]. */
    val openFolder: Int? = null,
) {
    private val byComponent = apps.associateBy { it.component }

    private val visible = apps.filterNot(hiding::hides)
    private val shownAll = shown(layout, if (showAll) apps else visible).let { s ->
        // Shown by Show All, an app hidden before the layout knew it has no place: at the end.
        if (!showAll) return@let s
        val placed = layout.flatMapTo(HashSet()) { t -> t.apps().map { it.component } }
        val extra = hiddenApps.filter { it.component !in placed }
        Shown(s.tiles + extra, s.places + extra.map { -1 })
    }

    fun hides(app: App): Boolean = hiding.hides(app)

    /** The apps the tray leaves out, by name. */
    val hiddenApps: List<App> get() = apps.filter(hiding::hides)

    /** All as shown: launchable apps only, under their current names, every folder. */
    val all: List<Tile> get() = shownAll.tiles

    /** Where the tile shown at [index] of All is in the layout. */
    internal fun placeOf(index: Int): Int? = shownAll.places.getOrNull(index)?.takeIf { it >= 0 }

    private fun shownApp(component: String): App? = byComponent[component]?.takeUnless(hiding::hides)

    /** Every app All shows, those in folders included, in its order. */
    private val allApps: List<App> get() = all.flatMap { it.apps() }

    /** What [panel] shows, in its order. */
    fun items(panel: Panel): List<Tile> = when (panel) {
        Panel.ALL -> all
        Panel.FAVOURITES -> favourites.mapNotNull(::shownApp)
        Panel.FREQUENT -> frequent.mapNotNull(::shownApp).take(FREQUENT_MAX)
        // Media (sim-12: Music, Pictures, Videos, Video Camera, Podcasts) and Downloads (the apps
        // installed from App World): for Android's apps, its media categories and what the user installed (D).
        Panel.MEDIA -> allApps.filter { it.media }
        Panel.DOWNLOADS -> allApps.filter { it.removable }
    }

    /** The folder open, as All shows it. */
    val folder: Folder? get() = openFolder?.let { place -> shownAll.places.indexOf(place).takeIf { it >= 0 }?.let { shownAll.tiles[it] as? Folder } }

    /** What the tray shows now: the open folder's apps, or the view's tiles. */
    val shown: List<Tile> get() = folder?.apps ?: items(panel)

    fun tileAt(index: Int): Tile? = shown.getOrNull(index)
}

sealed interface HomeEvent {
    /** The apps LauncherApps lists, and the home saved by the last run (empty the first time). */
    data class AppsLoaded(val apps: List<App>, val saved: SavedHome = SavedHome(), val hiding: Hiding = Hiding()) : HomeEvent

    /** An app was hidden or shown: an app shown again comes back to its place, or to the end. */
    data class HidingChanged(val hiding: Hiding) : HomeEvent

    /**
     * [packageName] installed, updated, disabled or removed: [apps] are its launchable activities
     * now. Only [removed] (an uninstall, not the first half of an update) takes it out of the
     * home; otherwise its apps keep their places, hidden while there is nothing to launch.
     */
    data class PackageChanged(val packageName: String, val apps: List<App>, val removed: Boolean = false) : HomeEvent

    /** Another view, by the trackpad on the tab or a swipe. */
    data class ShowPanel(val panel: Panel) : HomeEvent

    /** The tray raised or lowered: Maximise Panel, a swipe, a click on the tab. */
    data class Expand(val expanded: Boolean) : HomeEvent

    /** The home key (an app left for the home): the tray lowered (D). End on the home changes nothing (sim-78b). */
    data object HomeKey : HomeEvent

    /** Escape: the tray lowered (D). */
    data object Back : HomeEvent

    /** The app with [component] opened from the home: first in Frequent. */
    data class Launched(val component: String) : HomeEvent

    /** Mark as Favourite ([on]), or Unmark: last of Favourites, or out of them. */
    data class Favourite(val component: String, val on: Boolean) : HomeEvent

    /** Show All (the home's menu). */
    data object ShowAll : HomeEvent

    /** Move (the short menu) on the tile at [index] of the view shown, where its order is the user's. */
    data class StartMove(val index: Int) : HomeEvent

    /** The moved icon goes to [index], the others make room. */
    data class MoveTo(val index: Int) : HomeEvent

    /** The click: the icon stays where it is. */
    data object EndMove : HomeEvent

    /** Add Folder (the home's menu): an empty folder at the end of All, named [name], numbered if taken (D). */
    data class AddFolder(val name: String) : HomeEvent

    /** The folder at [index] of All opened (a click, Open Folder). */
    data class OpenFolder(val index: Int) : HomeEvent

    data object CloseFolder : HomeEvent

    /** The open folder named [name] (Edit Folder); an empty name changes nothing. */
    data class RenameFolder(val name: String) : HomeEvent

    /**
     * Move to Folder: the app at [index] of what is shown into the folder at [target] of the
     * layout, last; null takes it out of the open folder, right after it.
     */
    data class MoveToFolder(val index: Int, val target: Int?) : HomeEvent

    /** Delete Folder: its apps take its place in All, in its order (D). */
    data class DeleteFolder(val index: Int) : HomeEvent
}

/** The views whose order is the user's: All, and Favourites (D); the others are worked out. */
fun Panel.arranged() = this == Panel.ALL || this == Panel.FAVOURITES

fun reduce(state: HomeState, event: HomeEvent): HomeState = when (event) {
    is HomeEvent.AppsLoaded -> sorted(event.apps).let { apps ->
        val layout = placed(event.saved.layout, apps, event.hiding::hides)
        state.copy(
            apps = apps,
            layout = layout,
            favourites = event.saved.favourites,
            frequent = event.saved.frequent,
            loaded = true,
            appsVersion = state.appsVersion + 1,
            hiding = event.hiding,
        )
    }
    is HomeEvent.HidingChanged -> state.copy(hiding = event.hiding, layout = placed(state.layout, state.apps, event.hiding::hides))
    // A change that concerns the tray during a move puts the move back first: the places it
    // knows may be gone, and what it would put back must not bring an uninstalled app back.
    is HomeEvent.PackageChanged -> {
        val pkg = event.packageName
        val inLayout = event.removed && state.layout.any { tile -> tile.apps().any { it.packageName == pkg } }
        // Most updates (services, libraries) concern no icon of the tray: nothing to redraw.
        if (event.apps.isEmpty() && state.apps.none { it.packageName == pkg } && !inLayout) {
            state
        } else {
            packageChanged(if (state.moving != null) state.cancelMove() else state, event)
        }
    }
    is HomeEvent.ShowPanel -> if (state.moving != null) state else state.copy(panel = event.panel, openFolder = null)
    is HomeEvent.Expand -> state.copy(expanded = event.expanded)
    HomeEvent.HomeKey -> state.cancelMove().copy(expanded = false, showAll = false, openFolder = null)
    HomeEvent.Back -> when {
        state.moving != null -> state.cancelMove()
        state.openFolder != null -> state.copy(openFolder = null)
        state.showAll -> state.copy(showAll = false)
        else -> state.copy(expanded = false)
    }
    is HomeEvent.Launched -> state.copy(frequent = (listOf(event.component) + (state.frequent - event.component)).take(FREQUENT_MAX))
    is HomeEvent.Favourite -> when {
        event.on == (event.component in state.favourites) -> state
        event.on -> state.copy(favourites = state.favourites + event.component)
        else -> state.copy(favourites = state.favourites - event.component)
    }
    HomeEvent.ShowAll -> state.copy(showAll = true)
    is HomeEvent.StartMove ->
        if (state.panel.arranged() && state.tileAt(event.index) != null) state.copy(moving = event.index, beforeMove = SavedHome(state.layout, state.favourites)) else state
    is HomeEvent.MoveTo -> state.moving?.let { from -> moveTo(state, from, event.index) } ?: state
    HomeEvent.EndMove -> state.copy(moving = null, beforeMove = null)
    is HomeEvent.AddFolder -> state.copy(layout = state.layout + Folder(newFolderName(state.layout, event.name), emptyList()))
    is HomeEvent.OpenFolder ->
        // Opened, it takes the raised tray (sim-16; D lowered).
        if (state.panel == Panel.ALL && state.openFolder == null && state.tileAt(event.index) is Folder) state.copy(openFolder = state.placeOf(event.index), expanded = true) else state
    HomeEvent.CloseFolder -> state.copy(openFolder = null)
    is HomeEvent.RenameFolder -> {
        val at = state.openFolder
        val saved = at?.let { state.layout[it] as? Folder }
        val name = folderName(event.name)
        if (saved == null || name.isEmpty()) state else state.copy(layout = state.layout.replaced(at, saved.copy(name = name)))
    }
    is HomeEvent.MoveToFolder -> movedToFolder(state, event.index, event.target) ?: state
    is HomeEvent.DeleteFolder -> {
        val at = state.placeOf(event.index)
        val folder = at?.let { state.layout[it] as? Folder }
        if (folder == null) state else state.copy(layout = state.layout.subList(0, at) + folder.apps + state.layout.subList(at + 1, state.layout.size))
    }
}

private fun List<Tile>.replaced(at: Int, tile: Tile) = mapIndexed { i, t -> if (i == at) tile else t }

private fun movedToFolder(state: HomeState, index: Int, target: Int?): HomeState? {
    val app = state.tileAt(index) as? App ?: return null
    val key = app.key()
    val into = { layout: List<Tile> ->
        val f = target?.let { layout.getOrNull(it) as? Folder }
        if (f == null) null else layout.replaced(target, f.copy(apps = f.apps + key))
    }
    val open = state.openFolder
    if (open != null) {
        val f = state.layout[open] as? Folder ?: return null
        val without = state.layout.replaced(open, f.copy(apps = f.apps.filter { it.component != app.component }))
        val layout = if (target == null) without.toMutableList().apply { add(open + 1, key) } else into(without) ?: return null
        return state.copy(layout = layout)
    }
    if (state.panel != Panel.ALL) return null
    val place = state.placeOf(index) ?: return null
    val layout = into(state.layout) ?: return null
    return state.copy(layout = layout.filterIndexed { i, _ -> i != place }, openFolder = null)
}

/** "Name", then "Name 2"... (the name comes from the text box of Add Folder; numbered if it is taken, D). */
private fun newFolderName(layout: List<Tile>, base: String): String {
    val taken = layout.mapNotNullTo(HashSet()) { (it as? Folder)?.name }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
}

private fun packageChanged(state: HomeState, event: HomeEvent.PackageChanged): HomeState {
    val pkg = event.packageName
    val apps = sorted(state.apps.filter { it.packageName != pkg } + event.apps)
    // A hidden app of the layout keeps its place; one it does not know yet is placed only
    // when shown again (HidingChanged), at the end.
    val kept = if (event.removed) removed(state.layout, pkg) else state.layout
    val layout = placed(kept, apps, state.hiding::hides)
    val gone = { c: String -> event.removed && c.substringBefore('/') == pkg }
    return state.copy(
        apps = apps,
        layout = layout,
        favourites = state.favourites.filterNot(gone),
        frequent = state.frequent.filterNot(gone),
        // An app taken out of All shifts the places after it: the open folder closes.
        openFolder = state.openFolder.takeIf { kept.size == state.layout.size },
        appsVersion = state.appsVersion + 1,
    )
}

private fun HomeState.cancelMove(): HomeState {
    val before = beforeMove ?: return copy(moving = null)
    return copy(layout = before.layout, favourites = before.favourites, moving = null, beforeMove = null)
}

/** The tile at [from] of the view shown, moved to [to]: the others shift, as Move shows them. */
private fun moveTo(state: HomeState, from: Int, to: Int): HomeState {
    val shown = state.shown
    if (from !in shown.indices || to !in shown.indices || to == from) return state
    state.openFolder?.let { open ->
        val f = state.layout[open] as? Folder ?: return state
        val a = f.apps.indexOfFirst { it.component == (shown[from] as App).component }
        val b = f.apps.indexOfFirst { it.component == (shown[to] as App).component }
        return state.copy(layout = state.layout.replaced(open, f.copy(apps = f.apps.moved(a, b))), moving = to)
    }
    return when (state.panel) {
        Panel.ALL -> {
            val a = state.placeOf(from) ?: return state
            val b = state.placeOf(to) ?: return state
            state.copy(layout = state.layout.moved(a, b), moving = to)
        }
        Panel.FAVOURITES -> {
            val a = state.favourites.indexOf((shown[from] as App).component)
            val b = state.favourites.indexOf((shown[to] as App).component)
            state.copy(favourites = state.favourites.moved(a, b), moving = to)
        }
        else -> state
    }
}

/** The element at [from] taken out and put back at [to]: before what was there, or after it moving forward. */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

// A new Collator each time: the instances are not thread-safe. By name as a person reads it
// (case and accents aside), then by component so equal names keep one order.
private fun sorted(apps: List<App>) =
    apps.sortedWith(compareBy<App, String>(Collator.getInstance()) { it.label }.thenBy { it.packageName }.thenBy { it.className })
