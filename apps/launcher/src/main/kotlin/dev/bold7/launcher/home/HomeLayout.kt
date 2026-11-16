package dev.bold7.launcher.home

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// What the home keeps between runs, pure (no android.*) but for its file, which is plain java.io:
// the order of All as the user arranged it (BBOS 7's one ordered list, folders in it), the favourites and
// the apps opened last. A BBOS 7 folder may be empty (sim-16, C) and stays until deleted.
//
// The layout keeps every app it was given until its package is uninstalled: an app disabled, on
// storage gone away, or between two activities during an update keeps its place, hidden, and
// comes back to it. Only [removed] takes apps out. Apps are kept by component (label "").

internal val App.component get() = "$packageName/$className"

/** An app as the layout keeps it: its component only, its name and kind come from LauncherApps. */
internal fun App.key() = if (label.isEmpty() && !removable && !media) this else App(packageName, className, "")

internal fun Tile.apps(): List<App> = when (this) {
    is App -> listOf(this)
    is Folder -> apps
}

/** What is saved: All's order, the favourites in their order, the last apps opened, newest first; by component. */
data class SavedHome(
    val layout: List<Tile> = emptyList(),
    val favourites: List<String> = emptyList(),
    val frequent: List<String> = emptyList(),
)

/**
 * [layout] with the [installed] apps it does not know: one of a package whose saved activity is
 * no longer launchable takes that activity's place (a launcher activity renamed by an update);
 * one of a package with an icon in All itself comes right after it (Text Messages beside
 * Messages, sim-03, C); the others go to the end, in the order of [installed]. Nothing is
 * removed. An app [hides] leaves out is not placed, and still launchable: one of the layout keeps
 * its place.
 */
fun placed(layout: List<Tile>, installed: List<App>, hides: (App) -> Boolean = { false }): List<Tile> {
    val launchable = installed.mapTo(HashSet()) { it.component }
    var tiles = layout
    val known = layout.flatMapTo(HashSet()) { tile -> tile.apps().map { it.component } }
    for (app in installed) {
        if (hides(app) || !known.add(app.component)) continue
        val stale = tiles.flatMap { it.apps() }.firstOrNull { it.packageName == app.packageName && it.component !in launchable }
        val beside = tiles.indexOfLast { it is App && it.packageName == app.packageName }
        tiles = when {
            stale != null -> tiles.map { it.withApp(stale, app.key()) }
            beside >= 0 -> tiles.toMutableList().apply { add(beside + 1, app.key()) }
            else -> tiles + app.key()
        }
    }
    return tiles
}

private fun Tile.withApp(old: App, new: App): Tile = when (this) {
    is App -> if (this == old) new else this
    is Folder -> copy(apps = apps.map { if (it == old) new else it })
}

/** [layout] without [packageName], uninstalled; its folders stay, emptied or not. */
fun removed(layout: List<Tile>, packageName: String): List<Tile> = layout.mapNotNull { tile ->
    when (tile) {
        is App -> tile.takeIf { it.packageName != packageName }
        is Folder -> tile.copy(apps = tile.apps.filter { it.packageName != packageName })
    }
}

/** What All shows of a layout: its tiles, and for each one shown its place in the layout. */
class Shown(val tiles: List<Tile>, val places: List<Int>)

/**
 * The tiles of [layout] that [installed] can launch, under their current names: an app not
 * installed leaves no hole, a folder always shows, with the apps it can launch.
 */
fun shown(layout: List<Tile>, installed: List<App>): Shown {
    val byComponent = installed.associateBy { it.component }
    val tiles = mutableListOf<Tile>()
    val places = mutableListOf<Int>()
    layout.forEachIndexed { i, tile ->
        val shownTile = when (tile) {
            is App -> byComponent[tile.component]
            is Folder -> tile.copy(apps = tile.apps.mapNotNull { byComponent[it.component] })
        }
        if (shownTile != null) {
            tiles += shownTile
            places += i
        }
    }
    return Shown(tiles, places)
}

/** A folder name as the layout keeps it: one line, no tab, trimmed. */
fun folderName(typed: String): String = typed.replace(Regex("\\s+"), " ").trim()

/**
 * One line per tile, favourite and frequent app, tab-separated: `app  package/class`,
 * `folder  name  package/class…`, `favourite  package/class`, `frequent  package/class`.
 * Labels are not kept: they come from LauncherApps.
 */
fun encodeHome(home: SavedHome): String = buildString {
    for (tile in home.layout) {
        when (tile) {
            is App -> append("app\t").append(tile.component)
            is Folder -> append("folder\t").append(tile.name).append(tile.apps.joinToString("") { "\t" + it.component })
        }
        append('\n')
    }
    for (c in home.favourites) append("favourite\t").append(c).append('\n')
    for (c in home.frequent) append("frequent\t").append(c).append('\n')
}

/**
 * [encodeHome] read back, apps with an empty label; a line it cannot read is skipped. A legacy
 * file (pages of 15, `page` lines) reads as its pages one after the other: its
 * `page` lines are skipped, its tiles keep their order.
 */
fun decodeHome(text: String): SavedHome {
    val layout = mutableListOf<Tile>()
    val favourites = mutableListOf<String>()
    val frequent = mutableListOf<String>()
    fun app(component: String) = component.split('/').takeIf { it.size == 2 && it.none(String::isEmpty) }?.let { App(it[0], it[1], "") }
    for (line in text.lines()) {
        val fields = line.split('\t')
        when (fields[0]) {
            "app" -> fields.getOrNull(1)?.let(::app)?.let { layout += it }
            "folder" -> if (fields.size > 1) layout += Folder(fields[1], fields.drop(2).mapNotNull(::app))
            "favourite" -> fields.getOrNull(1)?.let(::app)?.let { favourites += it.component }
            "frequent" -> fields.getOrNull(1)?.let(::app)?.let { frequent += it.component }
        }
    }
    return SavedHome(layout, favourites, frequent)
}

/**
 * The home in a private file, read and written on io only (StrictMode). [file] is resolved on
 * first use, there too: Context.getFilesDir touches the disk. Each write replaces the file whole,
 * as AtomicFile does: a temporary file synced to the disk, then renamed over it, so a crash or a
 * power cut mid-write leaves the previous one.
 */
open class LayoutFile(file: () -> File) {
    private val file by lazy(file)

    /** The saved home, empty before the first write. */
    @Throws(IOException::class)
    fun read(): SavedHome = if (file.exists()) decodeHome(file.readText()) else SavedHome()

    @Throws(IOException::class)
    open fun write(home: SavedHome) = writeWhole(file, encodeHome(home))

    /** After a failed [read]: the file is kept aside as `.bad`, for a look, and the next run starts afresh. */
    fun setAside() {
        file.renameTo(File(file.path + ".bad"))
    }
}

/** [text] as [file]'s whole content: a temporary file synced to the disk, then renamed over it. */
@Throws(IOException::class)
internal fun writeWhole(file: File, text: String) {
    val tmp = File(file.path + ".tmp")
    FileOutputStream(tmp).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
}
