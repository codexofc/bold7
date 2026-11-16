package dev.bold7.launcher.home

import dev.bold7.icons.AppTable
import java.io.File
import java.io.IOException

// Which apps the grid leaves out : pure but for its file, plain
// java.io. Display only: a hidden app stays installed, enabled, launchable, and in the apps the
// search reads; its place in the layout is kept, as for an app unavailable for a while.

private val NO_TABLE = AppTable(emptyMap())

/**
 * The table's defaults (bbos7_apps.tsv), and over them the user's [choices], by component: true hides,
 * false shows. The table is one instance for the process, compared by identity.
 */
data class Hiding(val table: AppTable = NO_TABLE, val choices: Map<String, Boolean> = emptyMap()) {
    fun hides(app: App): Boolean = choices[app.component] ?: table.hidden(app.packageName, app.className)

    /** My choice for [app]; one that says what the table says is dropped, so a later table change applies. */
    fun with(app: App, hidden: Boolean): Hiding =
        copy(choices = if (hidden == table.hidden(app.packageName, app.className)) choices - app.component else choices + (app.component to hidden))
}

/** One line per choice, tab-separated: `package/class  hidden|shown`. */
fun encodeChoices(choices: Map<String, Boolean>): String = buildString {
    for ((component, hidden) in choices) append(component).append('\t').append(if (hidden) "hidden" else "shown").append('\n')
}

/** [encodeChoices] read back; a line it cannot read is skipped. */
fun decodeChoices(text: String): Map<String, Boolean> = buildMap {
    for (line in text.lines()) {
        val fields = line.split('\t')
        val component = fields[0].split('/')
        if (fields.size != 2 || component.size != 2 || component.any(String::isEmpty)) continue
        when (fields[1]) {
            "hidden" -> put(fields[0], true)
            "shown" -> put(fields[0], false)
        }
    }
}

/**
 * My choices in a private file, `files/hidden-apps.tsv`, read and written on io only, replaced
 * whole as [LayoutFile] does.
 */
class HiddenAppsFile(file: () -> File) {
    private val file by lazy(file)

    /** The saved choices, none before the first write. */
    @Throws(IOException::class)
    fun read(): Map<String, Boolean> = if (file.exists()) decodeChoices(file.readText()) else emptyMap()

    @Throws(IOException::class)
    fun write(choices: Map<String, Boolean>) = writeWhole(file, encodeChoices(choices))

    /** After a failed [read]: kept aside as `.bad`, the next write starts afresh; false if it could not be moved. */
    fun setAside(): Boolean = file.renameTo(File(file.path + ".bad"))
}
