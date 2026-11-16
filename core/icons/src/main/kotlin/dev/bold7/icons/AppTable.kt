package dev.bold7.icons

// How each app of the Q25 appears in Bold7: its BBOS 7 name, and whether the grid shows it
// (docs/BBOS7-VISUEL.md). Pure, no android.*, tested on the JVM. The table is
// src/main/res/raw/bbos7_apps.tsv of this library, beside bbos7_icons.tsv.

/** One row: [name] null keeps Android's; [source] is where the name, or the choice to hide, comes from. */
data class AppRow(val name: String?, val hidden: Boolean, val source: String)

class AppTable(
    /** (package, activity or null for the whole package) to its row. */
    val rows: Map<Pair<String, String?>, AppRow>,
) {
    private fun rowOf(packageName: String, className: String) = rows[packageName to className] ?: rows[packageName to null]

    /** The BBOS 7 name of this activity: its own row, else its package's; null keeps Android's. */
    fun nameFor(packageName: String, className: String): String? = rowOf(packageName, className)?.name

    /** The BBOS 7 name of the whole package (its `*` row), where no activity is known: a task. */
    fun nameForPackage(packageName: String): String? = rows[packageName to null]?.name

    /** True when the table leaves this activity off the grid; an app it does not know shows. */
    fun hidden(packageName: String, className: String): Boolean = rowOf(packageName, className)?.hidden == true
}

/**
 * Tab-separated rows `package  activity  name  state  source`: `*` as activity for every activity
 * of the package, `-` as name to keep Android's, `shown` or `hidden`; blank lines and `#` comments
 * skipped. Throws [IllegalArgumentException] naming the line of the first mistake.
 */
fun parseAppTable(text: String): AppTable {
    val rows = mutableMapOf<Pair<String, String?>, AppRow>()
    text.lines().forEachIndexed { i, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        fun check(ok: Boolean, what: () -> String) = require(ok) { "line ${i + 1}: ${what()}: $line" }
        val fields = line.split('\t')
        check(fields.size == 5) { "expected package, activity, name, state and source, tab-separated" }
        check(fields.none { it.isBlank() }) { "empty field (* for every activity, - for Android's name)" }
        val (pkg, activity, name, state, source) = fields
        check(state == "shown" || state == "hidden") { "state \"$state\" is neither shown nor hidden" }
        check(name != "-" || state == "hidden") { "a row shown under Android's name changes nothing" }
        val key = pkg to activity.takeUnless { it == "*" }
        check(rows.put(key, AppRow(name.takeUnless { it == "-" }, state == "hidden", source)) == null) { "$pkg $activity listed twice" }
    }
    return AppTable(rows)
}

/**
 * [parseAppTable], or every app shown under its own name after [onError]: an unreadable table
 * costs the BBOS 7 names, never an app (the tests keep it from shipping; this is the last guard).
 */
fun parseAppTableOrEmpty(text: String, onError: (IllegalArgumentException) -> Unit): AppTable =
    try {
        parseAppTable(text)
    } catch (e: IllegalArgumentException) {
        onError(e)
        AppTable(emptyMap())
    }
