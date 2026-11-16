package dev.bold7.icons

// Which BBOS 7 icon stands for which app: pure, no android.*, tested on the JVM. The table is
// src/main/res/raw/bbos7_icons.tsv of this library, merged into each APK that depends on it (the
// launcher, SystemUI's module); tools/import-assets.sh copies each source it names into
// assets-gen/<module>/res under the icon's name, which each module's build adds as resources.


private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")

class IconTable(
    /** (package, activity or null for the whole package) to icon resource name. */
    private val icons: Map<Pair<String, String?>, String>,
    /** Each icon resource name, with its file under assets-private/: its provenance. */
    val sources: Map<String, String>,
) {
    /** The BBOS 7 icon of this activity: its own row, else its package's; null: the generated icon. */
    fun iconFor(packageName: String, className: String): String? =
        icons[packageName to className] ?: icons[packageName to null]

    /** The BBOS 7 icon of the whole package (its `*` row), where no activity is known: a notification. */
    fun iconForPackage(packageName: String): String? = icons[packageName to null]
}

/**
 * Tab-separated rows `package  activity  icon  source  provenance`, `*` as activity for every
 * activity of the package, the provenance starting with A (firmware), B (manual), C (capture) or D (deduced); blank lines and `#` comments skipped. Throws [IllegalArgumentException] naming the
 * line of the first mistake.
 */
fun parseIconTable(text: String): IconTable {
    val icons = mutableMapOf<Pair<String, String?>, String>()
    val sources = linkedMapOf<String, String>()
    text.lines().forEachIndexed { i, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        fun check(ok: Boolean, what: () -> String) = require(ok) { "line ${i + 1}: ${what()}: $line" }
        val fields = line.split('\t')
        check(fields.size == 5) { "expected package, activity, icon, source and provenance, tab-separated" }
        val (pkg, activity, icon, source, provenance) = fields
        check(provenance.firstOrNull()?.let { it in "ABCD" } == true) { "provenance must start with A, B, C or D" }
        check(pkg.isNotEmpty() && activity.isNotEmpty()) { "empty package or activity (* for every activity)" }
        check(RESOURCE_NAME.matches(icon)) { "icon \"$icon\" is not a resource name ([a-z][a-z0-9_]*)" }
        check(source.endsWith(".png") && !source.startsWith("/") && ".." !in source.split('/')) {
            "source must be a PNG under assets-private/"
        }
        check(sources.getOrPut(icon) { source } == source) { "icon $icon already comes from ${sources[icon]}" }
        val key = pkg to activity.takeUnless { it == "*" }
        check(icons.put(key, icon) == null) { "$pkg $activity listed twice" }
    }
    return IconTable(icons, sources)
}

/**
 * [parseIconTable], or no table icon at all after [onError]: an unreadable table costs the table
 * icons, never the home (the tests keep it from shipping; this is the last guard).
 */
fun parseIconTableOrEmpty(text: String, onError: (IllegalArgumentException) -> Unit): IconTable =
    try {
        parseIconTable(text)
    } catch (e: IllegalArgumentException) {
        onError(e)
        IconTable(emptyMap(), emptyMap())
    }

/**
 * The icon over which the home writes the day of the month, alone: the Calendar's, a blank page in
 * the firmware (DynamicCalendar, "13" over it in sim-06; the launcher's CalendarDay).
 */
const val CALENDAR_ICON = "bbos7_calendar"

/**
 * The sizes a [from] px square icon goes through down to [to] px: halved while above twice [to],
 * then [to]. One bilinear pass reads 2x2 pixels, so past a factor 2 it skips some and aliases.
 */
fun downscaleSteps(from: Int, to: Int): List<Int> = buildList {
    var size = from
    while (size > 2 * to) {
        size /= 2
        add(size)
    }
    add(to)
}
